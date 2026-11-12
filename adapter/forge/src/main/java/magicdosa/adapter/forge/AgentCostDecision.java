package magicdosa.adapter.forge;

import forge.ai.AiCostDecision;
import forge.game.card.Card;
import forge.game.card.CardCollection;
import forge.game.card.CardCollectionView;
import forge.game.card.CardLists;
import forge.game.card.CardPredicates;
import forge.game.cost.CostDiscard;
import forge.game.cost.CostExile;
import forge.game.cost.CostPart;
import forge.game.cost.CostReturn;
import forge.game.cost.CostSacrifice;
import forge.game.cost.CostTapType;
import forge.game.cost.PaymentDecision;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;

import java.util.List;
import java.util.function.Supplier;

/**
 * Non-mana costs of an agent seat's spells and abilities (2026-11-06 decision, deck item 2: the cards a cost uses are
 * chosen by agent-core). Covers the plain forms of sacrifice, discard, exile, tap and return costs with a number of
 * cards; a choice with only one outcome (the source itself, exactly as many valid cards as needed) is made without
 * asking. Other forms and other cost parts are answered by Forge AI; the uncommon forms are recorded as intended
 * delegations.
 */
final class AgentCostDecision extends AiCostDecision {
    private final AgentPlayerController agent;

    AgentCostDecision(AgentPlayerController agent, Player player, SpellAbility sa, boolean effect) {
        super(player, sa, effect);
        this.agent = agent;
    }

    /** Asks for {@code amount} of {@code options}; forced choices are made without asking. */
    private PaymentDecision choose(CostPart cost, CardCollectionView options, int amount, Supplier<PaymentDecision> forgeAi) {
        if (amount == 0) {
            return PaymentDecision.number(0);
        }
        if (options.size() < amount) {
            return null;
        }
        if (options.size() == amount) {
            return PaymentDecision.card(options);
        }
        List<Card> chosen = agent.chooseCostCards(cost.getClass().getSimpleName(), options, amount, ability, () -> {
            PaymentDecision d = forgeAi.get(); // Forge AI's payment, when the decision is delegated to it
            return d == null ? null : d.cards;
        });
        return chosen == null ? forgeAi.get() : PaymentDecision.card(new CardCollection(chosen));
    }

    private PaymentDecision delegated(CostPart cost, Supplier<PaymentDecision> forgeAi) {
        agent.costDelegated(cost.getClass().getSimpleName() + "(" + cost.getType() + ")");
        return forgeAi.get();
    }

    private static boolean plainType(String type) {
        return !type.contains("+") && !type.equals("All") && !type.equals("OriginalHost");
    }

    /**
     * A card type with at most a color and "other than this card" (2026-11-10: Card.White+Other for March of
     * Otherworldly Light and Solitude's evoke, Card.Blue+Other for Force of Negation): CardLists.getValidCards reads it,
     * and the spell itself is on the stack while its costs are paid, so it is not among the hand cards anyway.
     */
    private static boolean plainOrOther(String type) {
        return plainType(type) || type.matches("[A-Za-z]+(\\.[A-Za-z]+)?\\+Other");
    }

    @Override
    public PaymentDecision visit(CostSacrifice cost) {
        if (cost.payCostFromSource()) {
            return super.visit(cost);
        }
        if (!plainType(cost.getType()) || cost.getAmount().equals("All")) {
            return delegated(cost, () -> super.visit(cost));
        }
        CardCollectionView list = CardLists.filter(player.getCardsIn(ZoneType.Battlefield),
            CardPredicates.canBeSacrificedBy(ability, isEffect()));
        list = CardLists.getValidCards(list, cost.getType().split(";"), player, source, ability);
        return choose(cost, list, cost.getAbilityAmount(ability), () -> super.visit(cost));
    }

    @Override
    public PaymentDecision visit(CostDiscard cost) {
        String type = cost.getType();
        if (cost.payCostFromSource() || type.equals("Hand") || type.equals("LastDrawn") || type.equals("Random")) {
            return super.visit(cost); // no choice
        }
        if (!plainType(type)) {
            return delegated(cost, () -> super.visit(cost));
        }
        CardCollectionView hand = CardLists.getValidCards(player.getCardsIn(ZoneType.Hand), type.split(";"), player, source, ability);
        return choose(cost, hand, cost.getAbilityAmount(ability), () -> super.visit(cost));
    }

    @Override
    public PaymentDecision visit(CostExile cost) {
        String type = cost.getType();
        if (cost.payCostFromSource() || type.equals("All")
                || (cost.from.size() == 1 && cost.from.get(0) == ZoneType.Library)) {
            return super.visit(cost); // no choice
        }
        if (!plainOrOther(type) || type.contains("FromTopGrave")) {
            return delegated(cost, () -> super.visit(cost));
        }
        CardCollection list = cost.zoneRestriction != 1
            ? new CardCollection(player.getGame().getCardsIn(cost.from)) : new CardCollection(player.getCardsIn(cost.from));
        list = CardLists.getValidCards(list, type.split(";"), player, source, ability);
        list = CardLists.filter(list, CardPredicates.canExiledBy(ability, isEffect()));
        return choose(cost, list, cost.getAbilityAmount(ability), () -> super.visit(cost));
    }

    @Override
    public PaymentDecision visit(CostTapType cost) {
        String type = cost.getType();
        if (!plainType(type) || type.contains("sharesCreatureTypeWith") || cost.getAmount().equals("Any")) {
            return delegated(cost, () -> super.visit(cost));
        }
        CardCollection list = CardLists.getValidCards(player.getCardsIn(ZoneType.Battlefield), type.split(";"), player, source, ability);
        list = CardLists.filter(list, ability.isCrew() ? CardPredicates.CAN_CREW : CardPredicates.CAN_TAP);
        return choose(cost, list, cost.getAbilityAmount(ability), () -> super.visit(cost));
    }

    @Override
    public PaymentDecision visit(CostReturn cost) {
        if (cost.payCostFromSource()) {
            return super.visit(cost);
        }
        if (!plainType(cost.getType())) {
            return delegated(cost, () -> super.visit(cost));
        }
        CardCollection list = CardLists.getValidCards(player.getCardsIn(ZoneType.Battlefield), cost.getType().split(";"), player, source, ability);
        return choose(cost, list, cost.getAbilityAmount(ability), () -> super.visit(cost));
    }
}
