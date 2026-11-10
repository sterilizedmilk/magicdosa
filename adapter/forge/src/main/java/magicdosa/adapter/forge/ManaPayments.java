package magicdosa.adapter.forge;

import forge.game.card.Card;
import forge.game.cost.Cost;
import forge.game.cost.CostAdjustment;
import forge.game.cost.CostPartMana;
import forge.game.cost.CostPayment;
import forge.game.mana.Mana;
import forge.game.mana.ManaCostBeingPaid;
import forge.game.mana.ManaPool;
import forge.game.player.Player;
import forge.game.ability.AbilityUtils;
import forge.game.spellability.SpellAbility;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Paying costs from the mana pool only (2026-11-06 decision: mana is made in advance, no auto-tap during casting).
 * These checks never change the pool: they pay a copy of the cost greedily, the way Forge assigns each mana to the
 * most restrictive shard it can pay.
 */
public final class ManaPayments {
    private ManaPayments() {}

    /** Pool mana that can pay part of {@code cost} for {@code sa} now (restrictions such as Eldrazi Temple applied). */
    static List<Mana> usable(Player p, ManaCostBeingPaid cost, SpellAbility sa) {
        ManaPool pool = p.getManaPool();
        List<Mana> out = new ArrayList<>();
        for (Mana m : pool) {
            if (m.meetsManaRestrictions(sa) && sa.allowsPayingWithShard(m.getSourceCard(), m.getColor())
                    && cost.isNeeded(m, pool)) {
                out.add(m);
            }
        }
        return out;
    }

    /**
     * Whether {@code cost} can be paid with pool mana, leaving out {@code without}, plus up to {@code lifeBudget} life
     * for Phyrexian shards.
     */
    static boolean payable(Player p, ManaCostBeingPaid cost, SpellAbility sa, List<Mana> without, int lifeBudget) {
        ManaCostBeingPaid copy = new ManaCostBeingPaid(cost);
        ManaPool pool = p.getManaPool();
        List<Mana> left = new ArrayList<>();
        pool.forEach(left::add);
        // one occurrence per use: Forge may put the same Mana object into the pool more than once ({C}{C})
        for (Mana w : without) {
            for (int i = 0; i < left.size(); i++) {
                if (left.get(i) == w) {
                    left.remove(i);
                    break;
                }
            }
        }
        // repeated passes: payMana puts each mana on the most restrictive shard it can pay
        boolean progress = true;
        while (!copy.isPaid() && progress) {
            progress = false;
            for (Mana m : new ArrayList<>(left)) {
                if (copy.isPaid()) {
                    break;
                }
                if (m.meetsManaRestrictions(sa) && sa.allowsPayingWithShard(m.getSourceCard(), m.getColor())
                        && copy.isNeeded(m, pool) && copy.payMana(m, pool)) {
                    left.remove(m); // first occurrence
                    progress = true;
                }
            }
        }
        int life = lifeBudget;
        while (!copy.isPaid() && life >= 2 && copy.payPhyrexian()) {
            life -= 2;
        }
        return copy.isPaid();
    }

    /** Life the player may spend on Phyrexian mana for {@code sa} now. */
    static int lifeBudget(Player p, SpellAbility sa, boolean effect) {
        int budget = 0;
        while (budget < 40 && p.canPayLife(budget + 2, effect, sa)) {
            budget += 2;
        }
        return budget;
    }

    /**
     * Whether {@code sa} can be cast or activated now with pool mana and its non-mana costs (approximate: the
     * smallest X, cost changes as Forge computes them before payment). Mana abilities without a mana cost pass.
     */
    public static boolean payableNow(Player p, SpellAbility sa) {
        Cost cost = sa.getPayCosts();
        if (cost == null) {
            return true;
        }
        Cost adjusted = CostAdjustment.adjust(cost, sa, false);
        if (!CostPayment.canPayAdditionalCosts(adjusted, sa, false)) {
            return false;
        }
        CostPartMana part = adjusted.getCostMana();
        if (part == null) {
            return true;
        }
        ManaCostBeingPaid m = new ManaCostBeingPaid(part.getManaCostFor(sa));
        if (m.getXcounter() > 0) {
            int minX = Math.max(0, AbilityUtils.getAnnouncementBounds(sa, "X").getMinimum());
            m.setXManaCostPaid(minX, sa.getXColor());
        }
        Card host = sa.getHostCard();
        if (m.isPaid() && (host == null || !host.hasConverge())) {
            return true;
        }
        CostAdjustment.adjust(m, sa, p, null, true, false);
        return payable(p, m, sa, List.of(), lifeBudget(p, sa, false));
    }

    /**
     * The usable pool mana grouped by what an engine-neutral observer can tell apart: color, source and spending
     * restriction. Paying with any member of a group gives the same result.
     */
    static Map<String, List<Mana>> groups(List<Mana> usable) {
        Map<String, List<Mana>> out = new LinkedHashMap<>();
        for (Mana m : usable) {
            String key = m.getColor() + "|" + (m.getSourceCard() == null ? 0 : m.getSourceCard().getId()) + "|"
                + Objects.toString(m.getManaAbility() == null ? null : m.getManaAbility().getManaRestrictions(), "")
                + "|" + m.isPersistentMana();
            out.computeIfAbsent(key, k -> new ArrayList<>()).add(m);
        }
        return out;
    }
}
