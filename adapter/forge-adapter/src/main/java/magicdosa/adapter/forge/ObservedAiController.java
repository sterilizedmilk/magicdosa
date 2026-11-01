package magicdosa.adapter.forge;

import forge.LobbyPlayer;
import forge.ai.PlayerControllerAi;
import forge.card.ColorSet;
import forge.game.Game;
import forge.game.GameEntity;
import forge.game.GameObject;
import forge.game.card.Card;
import forge.game.card.CardCollection;
import forge.game.card.CardCollectionView;
import forge.game.card.CounterType;
import forge.game.cost.CostPartWithList;
import forge.game.player.DelayedReveal;
import forge.game.player.Player;
import forge.game.player.PlayerActionConfirmMode;
import forge.game.replacement.ReplacementEffect;
import forge.game.spellability.AbilitySub;
import forge.game.spellability.SpellAbility;
import forge.game.trigger.WrappedAbility;
import forge.game.zone.ZoneType;
import forge.util.collect.FCollectionView;
import magicdosa.protocol.v1.BottomCards;
import magicdosa.protocol.v1.DecisionType;
import magicdosa.protocol.v1.MulliganChoice;
import magicdosa.protocol.v1.PassPriority;
import magicdosa.protocol.v1.PlayOrDrawChoice;
import org.apache.commons.lang3.tuple.ImmutablePair;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Forge AI whose choices are reported (design: forge-adapter 설계 RlPlayerController; 폴백 검증).
 *
 * Used as is for seats agent-core does not decide for (their actions feed the agent seats' event streams and the log
 * as opponent decisions), and as the base of {@link RlPlayerController}, where every method not overridden there is an
 * intended delegation to Forge AI and is recorded as one. Only the decision methods the target decks reach are
 * wrapped so far; see {@link #wrapped()}.
 */
public class ObservedAiController extends PlayerControllerAi {
    protected final ForgeGameSession session;

    public ObservedAiController(Game game, Player player, LobbyPlayer lobbyPlayer, ForgeGameSession session) {
        super(game, player, lobbyPlayer);
        this.session = session;
    }

    /** Names of the wrapped Forge decision methods, for reports. */
    public static List<String> wrapped() {
        List<String> out = new ArrayList<>();
        for (var m : ObservedAiController.class.getDeclaredMethods()) {
            if (java.lang.reflect.Modifier.isPublic(m.getModifiers()) && !java.lang.reflect.Modifier.isStatic(m.getModifiers())) {
                out.add(m.getName());
            }
        }
        return out;
    }

    private void delegated(String method, DecisionType type, List<? extends GameObject> chosen) {
        session.delegated(player, method, type, chosen);
    }

    private void delegated(String method, DecisionType type) {
        delegated(method, type, null);
    }

    private static List<GameObject> list(GameObject o) {
        List<GameObject> out = new ArrayList<>();
        if (o != null) {
            out.add(o);
        }
        return out;
    }

    // ---- Forge AI's own answers, for fallbacks in subclasses ----

    protected final boolean forgeAiMulliganKeepHand(Player firstPlayer, int cardsToReturn) {
        return super.mulliganKeepHand(firstPlayer, cardsToReturn);
    }

    protected final CardCollectionView forgeAiTuckCards(CardCollectionView hand, int cardsToReturn) {
        return super.tuckCardsViaMulligan(hand, cardsToReturn);
    }

    protected final Player forgeAiStartingPlayer(boolean isFirstGame) {
        return super.chooseStartingPlayer(isFirstGame);
    }

    // ---- actions every seat reports ----

    @Override
    public List<SpellAbility> chooseSpellAbilityToPlay() {
        List<SpellAbility> chosen = super.chooseSpellAbilityToPlay();
        if (chosen == null || chosen.isEmpty()) {
            session.action(player, false, b -> b.setPassPriority(PassPriority.newBuilder().setSeat(session.seat(player))));
        }
        return chosen;
    }

    @Override
    public boolean mulliganKeepHand(Player firstPlayer, int cardsToReturn) {
        boolean keep = super.mulliganKeepHand(firstPlayer, cardsToReturn);
        reportMulligan(keep);
        delegated("mulliganKeepHand", DecisionType.DECISION_TYPE_MULLIGAN);
        return keep;
    }

    protected void reportMulligan(boolean keep) {
        if (!keep) {
            session.tookMulligan(player);
        }
        session.action(player, false, b -> b.setMulligan(MulliganChoice.newBuilder().setSeat(session.seat(player)).setKeep(keep)));
    }

    @Override
    public CardCollectionView tuckCardsViaMulligan(CardCollectionView hand, int cardsToReturn) {
        CardCollectionView chosen = super.tuckCardsViaMulligan(hand, cardsToReturn);
        reportBottom(chosen);
        delegated("tuckCardsViaMulligan", DecisionType.DECISION_TYPE_BOTTOM_CARDS);
        return chosen;
    }

    protected void reportBottom(CardCollectionView chosen) {
        BottomCards.Builder bc = BottomCards.newBuilder().setSeat(session.seat(player));
        for (Card c : chosen) {
            bc.addCardIds(session.ids().idOf(c));
        }
        session.action(player, false, b -> b.setBottomCards(bc));
    }

    @Override
    public Player chooseStartingPlayer(boolean isFirstGame) {
        Player first = super.chooseStartingPlayer(isFirstGame);
        reportPlayOrDraw(first == player);
        delegated("chooseStartingPlayer", DecisionType.DECISION_TYPE_PLAY_OR_DRAW);
        return first;
    }

    protected void reportPlayOrDraw(boolean play) {
        session.action(player, false, b -> b.setPlayOrDraw(PlayOrDrawChoice.newBuilder().setSeat(session.seat(player)).setPlay(play)));
    }

    // ---- choices left to Forge AI (intended delegation for agent seats) ----

    @Override
    public List<SpellAbility> orderSimultaneousSa(List<SpellAbility> activePlayerSAs) {
        List<SpellAbility> r = super.orderSimultaneousSa(activePlayerSAs);
        delegated("orderSimultaneousSa", DecisionType.DECISION_TYPE_TRIGGER_ORDER);
        return r;
    }

    @Override
    public void orderAndPlaySimultaneousSa(List<SpellAbility> activePlayerSAs) {
        if (activePlayerSAs.size() > 1) {
            delegated("orderAndPlaySimultaneousSa", DecisionType.DECISION_TYPE_TRIGGER_ORDER);
        }
        super.orderAndPlaySimultaneousSa(activePlayerSAs);
    }

    @Override
    public boolean playTrigger(Card host, WrappedAbility wrapperAbility, boolean isMandatory) {
        boolean r = super.playTrigger(host, wrapperAbility, isMandatory);
        if (!isMandatory || usesTargeting(wrapperAbility)) {
            delegated("playTrigger", DecisionType.DECISION_TYPE_TARGET);
        }
        return r;
    }

    private static boolean usesTargeting(SpellAbility sa) {
        for (SpellAbility s = sa; s != null; s = s.getSubAbility()) {
            if (s.usesTargeting()) {
                return true;
            }
        }
        return false;
    }

    @Override
    public Map<Card, Integer> assignCombatDamage(Card attacker, CardCollectionView blockers, CardCollectionView remaining,
                                                 int damageDealt, GameEntity defender, boolean overrideOrder) {
        Map<Card, Integer> r = super.assignCombatDamage(attacker, blockers, remaining, damageDealt, defender, overrideOrder);
        if (blockers.size() > 1) {
            delegated("assignCombatDamage", DecisionType.DECISION_TYPE_CHOOSE_OPTION);
        }
        return r;
    }

    @Override
    public CardCollectionView choosePermanentsToSacrifice(SpellAbility sa, int min, int max, CardCollectionView validTargets, String message) {
        CardCollectionView r = super.choosePermanentsToSacrifice(sa, min, max, validTargets, message);
        delegated("choosePermanentsToSacrifice", DecisionType.DECISION_TYPE_CHOOSE_CARDS, new ArrayList<>(r));
        return r;
    }

    @Override
    public CardCollectionView choosePermanentsToDestroy(SpellAbility sa, int min, int max, CardCollectionView validTargets, String message) {
        CardCollectionView r = super.choosePermanentsToDestroy(sa, min, max, validTargets, message);
        delegated("choosePermanentsToDestroy", DecisionType.DECISION_TYPE_CHOOSE_CARDS, new ArrayList<>(r));
        return r;
    }

    @Override
    public Integer announceRequirements(SpellAbility ability, int min, int max, String announce) {
        Integer r = super.announceRequirements(ability, min, max, announce);
        delegated("announceRequirements", DecisionType.DECISION_TYPE_CHOOSE_OPTION);
        return r;
    }

    @Override
    public CardCollectionView chooseCardsForEffect(CardCollectionView sourceList, SpellAbility sa, String title, int min, int max,
                                                   boolean isOptional, Map<String, Object> params) {
        CardCollectionView r = super.chooseCardsForEffect(sourceList, sa, title, min, max, isOptional, params);
        delegated("chooseCardsForEffect", DecisionType.DECISION_TYPE_CHOOSE_CARDS, new ArrayList<>(r));
        return r;
    }

    @Override
    public <T extends GameEntity> T chooseSingleEntityForEffect(FCollectionView<T> optionList, DelayedReveal delayedReveal, SpellAbility sa,
                                                               String title, boolean isOptional, Player relatedPlayer, Map<String, Object> params) {
        T r = super.chooseSingleEntityForEffect(optionList, delayedReveal, sa, title, isOptional, relatedPlayer, params);
        delegated("chooseSingleEntityForEffect", DecisionType.DECISION_TYPE_CHOOSE_CARDS, list(r));
        return r;
    }

    @Override
    public <T extends GameEntity> List<T> chooseEntitiesForEffect(FCollectionView<T> optionList, int min, int max, DelayedReveal delayedReveal,
                                                                 SpellAbility sa, String title, Player relatedPlayer, Map<String, Object> params) {
        List<T> r = super.chooseEntitiesForEffect(optionList, min, max, delayedReveal, sa, title, relatedPlayer, params);
        delegated("chooseEntitiesForEffect", DecisionType.DECISION_TYPE_CHOOSE_CARDS, r);
        return r;
    }

    @Override
    public List<SpellAbility> chooseSpellAbilitiesForEffect(List<SpellAbility> spells, SpellAbility sa, String title, int num,
                                                           Map<String, Object> params) {
        List<SpellAbility> r = super.chooseSpellAbilitiesForEffect(spells, sa, title, num, params);
        delegated("chooseSpellAbilitiesForEffect", DecisionType.DECISION_TYPE_CHOOSE_OPTION);
        return r;
    }

    @Override
    public SpellAbility chooseSingleSpellForEffect(List<SpellAbility> spells, SpellAbility sa, String title, Map<String, Object> params) {
        SpellAbility r = super.chooseSingleSpellForEffect(spells, sa, title, params);
        if (spells.size() > 1) {
            delegated("chooseSingleSpellForEffect", DecisionType.DECISION_TYPE_CHOOSE_OPTION);
        }
        return r;
    }

    @Override
    public boolean confirmAction(SpellAbility sa, PlayerActionConfirmMode mode, String message, List<String> options, Card cardToShow,
                                 Map<String, Object> params) {
        boolean r = super.confirmAction(sa, mode, message, options, cardToShow, params);
        delegated("confirmAction", DecisionType.DECISION_TYPE_CHOOSE_OPTION);
        return r;
    }

    @Override
    public boolean confirmReplacementEffect(ReplacementEffect replacementEffect, SpellAbility effectSA, GameEntity affected, String question) {
        boolean r = super.confirmReplacementEffect(replacementEffect, effectSA, affected, question);
        delegated("confirmReplacementEffect", DecisionType.DECISION_TYPE_CHOOSE_OPTION);
        return r;
    }

    @Override
    public boolean confirmTrigger(WrappedAbility sa) {
        boolean r = super.confirmTrigger(sa);
        delegated("confirmTrigger", DecisionType.DECISION_TYPE_CHOOSE_OPTION);
        return r;
    }

    @Override
    public CardCollection orderBlockers(Card attacker, CardCollection blockers) {
        CardCollection r = super.orderBlockers(attacker, blockers);
        if (blockers.size() > 1) {
            delegated("orderBlockers", DecisionType.DECISION_TYPE_CHOOSE_CARDS);
        }
        return r;
    }

    @Override
    public ImmutablePair<CardCollection, CardCollection> arrangeForScry(CardCollection topN) {
        ImmutablePair<CardCollection, CardCollection> r = super.arrangeForScry(topN);
        delegated("arrangeForScry", DecisionType.DECISION_TYPE_CHOOSE_CARDS);
        return r;
    }

    @Override
    public ImmutablePair<CardCollection, CardCollection> arrangeForSurveil(CardCollection topN) {
        ImmutablePair<CardCollection, CardCollection> r = super.arrangeForSurveil(topN);
        delegated("arrangeForSurveil", DecisionType.DECISION_TYPE_CHOOSE_CARDS);
        return r;
    }

    @Override
    public boolean willPutCardOnTop(Card c) {
        boolean r = super.willPutCardOnTop(c);
        delegated("willPutCardOnTop", DecisionType.DECISION_TYPE_CHOOSE_OPTION);
        return r;
    }

    @Override
    public CardCollectionView orderMoveToZoneList(CardCollectionView cards, ZoneType destinationZone, SpellAbility source) {
        CardCollectionView r = super.orderMoveToZoneList(cards, destinationZone, source);
        if (cards.size() > 1 && (destinationZone == ZoneType.Library || destinationZone == ZoneType.Graveyard)) {
            delegated("orderMoveToZoneList", DecisionType.DECISION_TYPE_CHOOSE_CARDS);
        }
        return r;
    }

    @Override
    public CardCollection chooseCardsToDiscardFrom(Player playerDiscard, SpellAbility sa, CardCollection validCards, int min, int max,
                                                       CardCollectionView visibleToChooser) {
        CardCollection r = super.chooseCardsToDiscardFrom(playerDiscard, sa, validCards, min, max, visibleToChooser);
        delegated("chooseCardsToDiscardFrom", DecisionType.DECISION_TYPE_CHOOSE_CARDS, new ArrayList<>(r));
        return r;
    }

    @Override
    public CardCollectionView chooseCardsToDiscardToMaximumHandSize(int numDiscard) {
        CardCollectionView r = super.chooseCardsToDiscardToMaximumHandSize(numDiscard);
        delegated("chooseCardsToDiscardToMaximumHandSize", DecisionType.DECISION_TYPE_CHOOSE_CARDS, new ArrayList<>(r));
        return r;
    }

    @Override
    public CardCollectionView chooseCardsToDelve(int genericAmount, CardCollection grave) {
        CardCollectionView r = super.chooseCardsToDelve(genericAmount, grave);
        delegated("chooseCardsToDelve", DecisionType.DECISION_TYPE_CHOOSE_CARDS, new ArrayList<>(r));
        return r;
    }

    @Override
    public List<AbilitySub> chooseModeForAbility(SpellAbility sa, List<AbilitySub> possible, int min, int num, boolean allowRepeat) {
        List<AbilitySub> r = super.chooseModeForAbility(sa, possible, min, num, allowRepeat);
        delegated("chooseModeForAbility", DecisionType.DECISION_TYPE_CHOOSE_OPTION);
        return r;
    }

    @Override
    public int chooseNumber(SpellAbility sa, String title, int min, int max) {
        int r = super.chooseNumber(sa, title, min, max);
        delegated("chooseNumber", DecisionType.DECISION_TYPE_CHOOSE_OPTION);
        return r;
    }

    @Override
    public boolean chooseBinary(SpellAbility sa, String question, BinaryChoiceType kindOfChoice, Boolean defaultChoice) {
        boolean r = super.chooseBinary(sa, question, kindOfChoice, defaultChoice);
        delegated("chooseBinary", DecisionType.DECISION_TYPE_CHOOSE_OPTION);
        return r;
    }

    @Override
    public byte chooseColor(String message, SpellAbility sa, ColorSet colors) {
        byte r = super.chooseColor(message, sa, colors);
        if (colors.countColors() > 1) {
            delegated("chooseColor", DecisionType.DECISION_TYPE_CHOOSE_OPTION);
        }
        return r;
    }

    @Override
    public CounterType chooseCounterType(List<CounterType> options, SpellAbility sa, String prompt, Map<String, Object> params) {
        CounterType r = super.chooseCounterType(options, sa, prompt, params);
        if (options.size() > 1) {
            delegated("chooseCounterType", DecisionType.DECISION_TYPE_CHOOSE_OPTION);
        }
        return r;
    }

    @Override
    public CardCollectionView chooseCardsForCost(CardCollectionView optionList, SpellAbility sa, CostPartWithList cpl, int amount,
                                                 boolean isOptional, String prompt) {
        CardCollectionView r = super.chooseCardsForCost(optionList, sa, cpl, amount, isOptional, prompt);
        if (r != null) {
            delegated("chooseCardsForCost", DecisionType.DECISION_TYPE_CHOOSE_CARDS, new ArrayList<>(r));
        }
        return r;
    }

    @Override
    public Card chooseSingleCardForZoneChange(ZoneType destination, List<ZoneType> origin, SpellAbility sa, CardCollection fetchList,
                                              DelayedReveal delayedReveal, String selectPrompt, boolean isOptional, Player decider) {
        Card r = super.chooseSingleCardForZoneChange(destination, origin, sa, fetchList, delayedReveal, selectPrompt, isOptional, decider);
        delegated("chooseSingleCardForZoneChange", DecisionType.DECISION_TYPE_CHOOSE_CARDS, list(r));
        return r;
    }

    @Override
    public List<Card> chooseCardsForZoneChange(ZoneType destination, List<ZoneType> origin, SpellAbility sa, CardCollection fetchList, int min,
                                               int max, DelayedReveal delayedReveal, String selectPrompt, Player decider) {
        List<Card> r = super.chooseCardsForZoneChange(destination, origin, sa, fetchList, min, max, delayedReveal, selectPrompt, decider);
        delegated("chooseCardsForZoneChange", DecisionType.DECISION_TYPE_CHOOSE_CARDS, r);
        return r;
    }

    @Override
    public void reveal(CardCollectionView cards, ZoneType zone, Player owner, String messagePrefix, boolean addMsgSuffix) {
        super.reveal(cards, zone, owner, messagePrefix, addMsgSuffix);
        session.syncZones(); // reveals are detected from Forge's visibility
    }
}
