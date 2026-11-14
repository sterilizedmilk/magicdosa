package magicdosa.adapter.forge;

import com.google.common.collect.ListMultimap;
import com.google.common.collect.Lists;
import com.google.common.collect.Multimap;
import forge.LobbyPlayer;
import forge.ai.PlayerControllerAi;
import forge.card.ColorSet;
import forge.card.ICardFace;
import forge.card.mana.ManaCost;
import forge.card.mana.ManaCostShard;
import forge.deck.Deck;
import forge.deck.DeckSection;
import forge.game.*;
import forge.game.Game;
import forge.game.GameEntity;
import forge.game.GameObject;
import forge.game.GameOutcome.AnteResult;
import forge.game.ability.AbilityUtils;
import forge.game.ability.effects.RollDiceEffect;
import forge.game.card.*;
import forge.game.card.Card;
import forge.game.card.CardCollection;
import forge.game.card.CardCollectionView;
import forge.game.card.CounterType;
import forge.game.card.sticker.Sticker;
import forge.game.combat.Combat;
import forge.game.cost.*;
import forge.game.cost.CostPartWithList;
import forge.game.keyword.KeywordInterface;
import forge.game.mana.Mana;
import forge.game.mana.ManaConversionMatrix;
import forge.game.mana.ManaCostBeingPaid;
import forge.game.player.DelayedReveal;
import forge.game.player.Player;
import forge.game.player.PlayerActionConfirmMode;
import forge.game.replacement.ReplacementEffect;
import forge.game.spellability.*;
import forge.game.spellability.AbilitySub;
import forge.game.spellability.SpellAbility;
import forge.game.staticability.StaticAbility;
import forge.game.trigger.WrappedAbility;
import forge.game.zone.PlayerZone;
import forge.game.zone.ZoneType;
import forge.item.PaperCard;
import forge.util.ITriggerEvent;
import forge.util.collect.FCollectionView;
import magicdosa.protocol.v1.BottomCards;
import magicdosa.protocol.v1.ChoiceValue;
import magicdosa.protocol.v1.TriggerOrderChoice;
import magicdosa.protocol.v1.DecisionType;
import magicdosa.protocol.v1.MulliganChoice;
import magicdosa.protocol.v1.PassPriority;
import magicdosa.protocol.v1.PlayOrDrawChoice;
import org.apache.commons.lang3.tuple.ImmutablePair;
import org.apache.commons.lang3.tuple.Pair;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/**
 * Forge AI whose choices are reported (design: forge-adapter 설계 RlPlayerController; 폴백 검증).
 *
 * Used as is for seats agent-core does not decide for (their actions feed the agent seats' event streams and the log
 * as opponent decisions), and as the base of {@link AgentPlayerController}, where every method not overridden there is an
 * intended delegation to Forge AI and is recorded as one. Every decision method of {@code PlayerController} is
 * overridden: those not classified yet (see docs/player-controller-methods.md) are counted as unclassified decisions.
 */
public class ObservedAiController extends PlayerControllerAi {
    protected final ForgeGameSession session;

    /** False for a Forge AI controller an opponent uses to play an agent seat it controls (deck item 4). */
    private final boolean forAgent;

    public ObservedAiController(Game game, Player player, LobbyPlayer lobbyPlayer, ForgeGameSession session) {
        this(game, player, lobbyPlayer, session, session.isAgent(player));
    }

    ObservedAiController(Game game, Player player, LobbyPlayer lobbyPlayer, ForgeGameSession session, boolean forAgent) {
        super(game, player, lobbyPlayer);
        this.session = session;
        this.forAgent = forAgent;
    }

    private boolean fallingBack;

    private void delegated(String method, DecisionType type, List<? extends GameObject> chosen) {
        if (!fallingBack) {
            session.delegated(player, forAgent, method, type, chosen);
        }
    }

    /**
     * Forge AI's answer after agent-core gave no usable one; the unintended fallback is already recorded, so the
     * wrapped method does not record an intended delegation as well.
     */
    protected final <T> T forgeAi(java.util.function.Supplier<T> answer) {
        fallingBack = true;
        try {
            return answer.get();
        } finally {
            fallingBack = false;
        }
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

    protected final int forgeAiNumberForKeywordCost(SpellAbility sa, Cost cost, KeywordInterface keyword, String prompt, int max) {
        return super.chooseNumberForKeywordCost(sa, cost, keyword, prompt, max);
    }

    protected final Player forgeAiStartingPlayer(boolean isFirstGame) {
        return super.chooseStartingPlayer(isFirstGame);
    }

    // ---- actions every seat reports ----

    /**
     * True while Forge AI picks its priority action, attackers or blockers: it then asks the controller's choice methods
     * itself to weigh options, and those calls are not choices made in the game (not reported).
     */
    private volatile boolean weighing;

    /** Whether Forge AI of this seat is weighing its own action now (its questions to others are estimates). */
    public boolean isWeighing() {
        return weighing;
    }

    /**
     * How many public choices this controller is answering now. A choice asked while answering another one (Forge AI
     * answers "choose cards" by choosing them one by one) is the controller's own way of answering: not reported.
     */
    protected int answering;

    private <T> T weighing(java.util.function.Supplier<T> s) {
        boolean before = weighing;
        weighing = true;
        try {
            return s.get();
        } finally {
            weighing = before;
        }
    }

    @Override
    public void declareAttackers(Player attacker, forge.game.combat.Combat combat) {
        weighing(() -> {
            super.declareAttackers(attacker, combat);
            return null;
        });
    }

    @Override
    public void declareBlockers(Player defender, forge.game.combat.Combat combat) {
        weighing(() -> {
            super.declareBlockers(defender, combat);
            return null;
        });
    }

    /** Forge AI's own priority action, prepared as it prepares it (nothing reported). */
    protected final List<SpellAbility> forgeAiAction() {
        return weighing(super::chooseSpellAbilityToPlay);
    }

    /**
     * A cost paid while {@code sa} resolves (605.3a "unless its controller pays", ward): the mana abilities that paid
     * for casting {@code sa} are still listed on it (Forge clears the list before a payment, not after), and when this
     * payment is not made Forge's refund would undo them too, untapping the caster's lands (upstream candidate 10).
     * The list starts empty, for this payment's own sources.
     */
    static void forgetCastPayment(SpellAbility sa) {
        if (sa != null) {
            sa.getPayingManaAbilities().clear();
        }
    }

    @Override
    public boolean payManaCost(forge.card.mana.ManaCost toPay, forge.game.cost.CostPartMana costPartMana, SpellAbility sa,
                               String prompt, forge.game.mana.ManaConversionMatrix matrix, boolean effect) {
        if (effect) {
            forgetCastPayment(sa);
        }
        return super.payManaCost(toPay, costPartMana, sa, prompt, matrix, effect);
    }

    @Override
    public List<SpellAbility> chooseSpellAbilityToPlay() {
        List<SpellAbility> chosen = weighing(super::chooseSpellAbilityToPlay);
        if (chosen == null || chosen.isEmpty()) {
            session.action(player, false, b -> b.setPassPriority(PassPriority.newBuilder().setSeat(session.seat(player))));
        }
        return chosen;
    }

    /** Casting, activating or playing a land: its action event comes before the changes it causes. */
    @Override
    public boolean playChosenSpellAbility(SpellAbility sa) {
        // Forge AI may pay part of a cost (an Eldrazi Spawn sacrificed for its mana) and then fail to pay the rest: the
        // action is abandoned, but what it paid stays paid (upstream candidate 8). Such an action is taken back here:
        // the game goes back to Forge's undo mark taken before it, and the session to its state then (the events held
        // back during the action are dropped). Forge AI has noted the failure (not part of the game), so it does not
        // try the action again this turn. The mark reads the whole game: taken only for actions that can leave a paid
        // cost behind.
        forge.game.Game game = player.getGame();
        boolean failedBefore = getAi().hasFailedToCast(sa);
        forge.game.undo.UndoManager.Mark mark = !failedBefore && mayLeavePaidCosts(sa)
            ? game.getUndoManager().markForRollback(game) : null;
        Runnable sessionBefore = mark == null ? null : session.checkpoint();
        session.beginAction();
        boolean takenBack = false;
        try {
            boolean r = super.playChosenSpellAbility(sa);
            if (mark != null && !session.actionEventSeen() && getAi().hasFailedToCast(sa)) {
                boolean clean = game.getUndoManager().rollbackTo(game, mark);
                sessionBefore.run();
                takenBack = true;
                session.stats().note("Forge AI action abandoned after paying, taken back: " + sa.getHostCard().getName()
                    + (clean ? "" : " (restore reported problems)"));
                // Nothing was played: PhaseHandler must not make this player the first to have priority again (it does
                // when this returns true), or the opponent, who had already passed, would get priority once more.
                return false;
            }
            session.playedWithoutStack(player, sa);
            return r;
        } finally {
            if (!takenBack) {
                session.endAction();
            }
        }
    }

    /** Whether paying for {@code sa} may pay something that a failed payment does not give back. */
    private boolean mayLeavePaidCosts(SpellAbility sa) {
        if (sa.isLandAbility() || sa.isManaAbility() || sa.isTrigger() || sa.isCopied()) {
            return false;
        }
        if (irreversible(sa.getPayCosts())) {
            return true;
        }
        for (Card c : player.getCardsIn(ZoneType.Battlefield)) {
            for (SpellAbility m : c.getManaAbilities()) {
                if (irreversible(m.getPayCosts())) {
                    return true; // a mana source that sacrifices or discards (an Eldrazi Spawn)
                }
            }
        }
        return false;
    }

    private static boolean irreversible(forge.game.cost.Cost cost) {
        if (cost == null) {
            return false;
        }
        for (forge.game.cost.CostPart p : cost.getCostParts()) {
            if (!(p instanceof forge.game.cost.CostPartMana) && !(p instanceof forge.game.cost.CostTap)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean playSaFromPlayEffect(SpellAbility tgtSA) {
        session.beginAction();
        try {
            return super.playSaFromPlayEffect(tgtSA);
        } finally {
            session.endAction();
        }
    }

    /** Forge AI opponents keep their main deck between games (2026-11-06: no sideboarding, no SideboardingPlan hints). */
    @Override
    public List<forge.item.PaperCard> sideboard(forge.deck.Deck deck, forge.game.GameType gameType, String message) {
        return null;
    }

    @Override
    public boolean sideboardsByItself() {
        return false;
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
        if (r != null && r.size() > 1) {
            // the order triggers go on the stack in is public (protocol 9); entries without a trigger ID are left out
            TriggerOrderChoice.Builder t = TriggerOrderChoice.newBuilder().setSeat(session.seat(player));
            for (SpellAbility sa : r) {
                long id = session.triggerId(sa);
                if (id != 0) {
                    t.addAbilityIds(id);
                }
            }
            session.reportAction(b -> b.setTriggerOrder(t));
        }
        return r;
    }

    @Override
    public void orderAndPlaySimultaneousSa(List<SpellAbility> activePlayerSAs) {
        List<SpellAbility> entries = List.copyOf(activePlayerSAs);
        session.beginStacking(entries);
        try {
            if (activePlayerSAs.size() > 1) {
                delegated("orderAndPlaySimultaneousSa", DecisionType.DECISION_TYPE_TRIGGER_ORDER);
            }
            super.orderAndPlaySimultaneousSa(activePlayerSAs);
        } finally {
            session.endStacking(entries);
        }
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

    protected Map<Card, Integer> answerAssignCombatDamage(Card attacker, CardCollectionView blockers, CardCollectionView remaining,
                                                 int damageDealt, GameEntity defender, boolean overrideOrder) {
        Map<Card, Integer> r = super.assignCombatDamage(attacker, blockers, remaining, damageDealt, defender, overrideOrder);
        if (blockers.size() > 1) {
            delegated("assignCombatDamage", DecisionType.DECISION_TYPE_CHOOSE_OPTION);
        }
        return r;
    }

    protected CardCollectionView answerChoosePermanentsToSacrifice(SpellAbility sa, int min, int max, CardCollectionView validTargets, String message) {
        CardCollectionView r = super.choosePermanentsToSacrifice(sa, min, max, validTargets, message);
        delegated("choosePermanentsToSacrifice", DecisionType.DECISION_TYPE_CHOOSE_CARDS, new ArrayList<>(r));
        return r;
    }

    protected CardCollectionView answerChoosePermanentsToDestroy(SpellAbility sa, int min, int max, CardCollectionView validTargets, String message) {
        CardCollectionView r = super.choosePermanentsToDestroy(sa, min, max, validTargets, message);
        delegated("choosePermanentsToDestroy", DecisionType.DECISION_TYPE_CHOOSE_CARDS, new ArrayList<>(r));
        return r;
    }

    protected Integer answerAnnounceRequirements(SpellAbility ability, int min, int max, String announce) {
        Integer r = super.announceRequirements(ability, min, max, announce);
        delegated("announceRequirements", DecisionType.DECISION_TYPE_CHOOSE_OPTION);
        return r;
    }

    protected CardCollectionView answerChooseCardsForEffect(CardCollectionView sourceList, SpellAbility sa, String title, int min, int max,
                                                   boolean isOptional, Map<String, Object> params) {
        CardCollectionView r = super.chooseCardsForEffect(sourceList, sa, title, min, max, isOptional, params);
        delegated("chooseCardsForEffect", DecisionType.DECISION_TYPE_CHOOSE_CARDS, new ArrayList<>(r));
        return r;
    }

    protected <T extends GameEntity> T answerChooseSingleEntityForEffect(FCollectionView<T> optionList, DelayedReveal delayedReveal, SpellAbility sa,
                                                               String title, boolean isOptional, Player relatedPlayer, Map<String, Object> params) {
        T r = super.chooseSingleEntityForEffect(optionList, delayedReveal, sa, title, isOptional, relatedPlayer, params);
        delegated("chooseSingleEntityForEffect", DecisionType.DECISION_TYPE_CHOOSE_CARDS, list(r));
        return r;
    }

    protected <T extends GameEntity> List<T> answerChooseEntitiesForEffect(FCollectionView<T> optionList, int min, int max, DelayedReveal delayedReveal,
                                                                 SpellAbility sa, String title, Player relatedPlayer, Map<String, Object> params) {
        List<T> r = super.chooseEntitiesForEffect(optionList, min, max, delayedReveal, sa, title, relatedPlayer, params);
        delegated("chooseEntitiesForEffect", DecisionType.DECISION_TYPE_CHOOSE_CARDS, r);
        return r;
    }

    protected List<SpellAbility> answerChooseSpellAbilitiesForEffect(List<SpellAbility> spells, SpellAbility sa, String title, int num,
                                                           Map<String, Object> params) {
        List<SpellAbility> r = super.chooseSpellAbilitiesForEffect(spells, sa, title, num, params);
        delegated("chooseSpellAbilitiesForEffect", DecisionType.DECISION_TYPE_CHOOSE_OPTION);
        return r;
    }

    protected SpellAbility answerChooseSingleSpellForEffect(List<SpellAbility> spells, SpellAbility sa, String title, Map<String, Object> params) {
        SpellAbility r = super.chooseSingleSpellForEffect(spells, sa, title, params);
        if (spells.size() > 1) {
            delegated("chooseSingleSpellForEffect", DecisionType.DECISION_TYPE_CHOOSE_OPTION);
        }
        return r;
    }

    protected boolean answerConfirmAction(SpellAbility sa, PlayerActionConfirmMode mode, String message, List<String> options, Card cardToShow,
                                 Map<String, Object> params) {
        boolean r = super.confirmAction(sa, mode, message, options, cardToShow, params);
        delegated("confirmAction", DecisionType.DECISION_TYPE_CHOOSE_OPTION);
        return r;
    }

    protected boolean answerConfirmReplacementEffect(ReplacementEffect replacementEffect, SpellAbility effectSA, GameEntity affected, String question) {
        boolean r = super.confirmReplacementEffect(replacementEffect, effectSA, affected, question);
        delegated("confirmReplacementEffect", DecisionType.DECISION_TYPE_CHOOSE_OPTION);
        return r;
    }

    protected boolean answerConfirmTrigger(WrappedAbility sa) {
        boolean r = super.confirmTrigger(sa);
        delegated("confirmTrigger", DecisionType.DECISION_TYPE_CHOOSE_OPTION);
        return r;
    }

    protected CardCollection answerOrderBlockers(Card attacker, CardCollection blockers) {
        CardCollection r = super.orderBlockers(attacker, blockers);
        if (blockers.size() > 1) {
            delegated("orderBlockers", DecisionType.DECISION_TYPE_CHOOSE_CARDS);
        }
        return r;
    }

    protected ImmutablePair<CardCollection, CardCollection> answerArrangeForScry(CardCollection topN) {
        ImmutablePair<CardCollection, CardCollection> r = super.arrangeForScry(topN);
        delegated("arrangeForScry", DecisionType.DECISION_TYPE_CHOOSE_CARDS);
        return r;
    }

    protected ImmutablePair<CardCollection, CardCollection> answerArrangeForSurveil(CardCollection topN) {
        ImmutablePair<CardCollection, CardCollection> r = super.arrangeForSurveil(topN);
        delegated("arrangeForSurveil", DecisionType.DECISION_TYPE_CHOOSE_CARDS);
        return r;
    }

    protected boolean answerWillPutCardOnTop(Card c) {
        boolean r = super.willPutCardOnTop(c);
        delegated("willPutCardOnTop", DecisionType.DECISION_TYPE_CHOOSE_OPTION);
        return r;
    }

    protected CardCollectionView answerOrderMoveToZoneList(CardCollectionView cards, ZoneType destinationZone, SpellAbility source) {
        CardCollectionView r = super.orderMoveToZoneList(cards, destinationZone, source);
        if (cards.size() > 1 && (destinationZone == ZoneType.Library || destinationZone == ZoneType.Graveyard)) {
            delegated("orderMoveToZoneList", DecisionType.DECISION_TYPE_CHOOSE_CARDS);
        }
        return r;
    }

    protected CardCollection answerChooseCardsToDiscardFrom(Player playerDiscard, SpellAbility sa, CardCollection validCards, int min, int max,
                                                       CardCollectionView visibleToChooser) {
        CardCollection r = super.chooseCardsToDiscardFrom(playerDiscard, sa, validCards, min, max, visibleToChooser);
        delegated("chooseCardsToDiscardFrom", DecisionType.DECISION_TYPE_CHOOSE_CARDS, new ArrayList<>(r));
        return r;
    }

    protected CardCollectionView answerChooseCardsToDiscardToMaximumHandSize(int numDiscard) {
        CardCollectionView r = super.chooseCardsToDiscardToMaximumHandSize(numDiscard);
        delegated("chooseCardsToDiscardToMaximumHandSize", DecisionType.DECISION_TYPE_CHOOSE_CARDS, new ArrayList<>(r));
        return r;
    }

    protected CardCollectionView answerChooseCardsToDelve(int genericAmount, CardCollection grave) {
        CardCollectionView r = super.chooseCardsToDelve(genericAmount, grave);
        delegated("chooseCardsToDelve", DecisionType.DECISION_TYPE_CHOOSE_CARDS, new ArrayList<>(r));
        return r;
    }

    protected List<AbilitySub> answerChooseModeForAbility(SpellAbility sa, List<AbilitySub> possible, int min, int num, boolean allowRepeat) {
        List<AbilitySub> r = super.chooseModeForAbility(sa, possible, min, num, allowRepeat);
        delegated("chooseModeForAbility", DecisionType.DECISION_TYPE_CHOOSE_OPTION);
        return r;
    }

    protected int answerChooseNumber(SpellAbility sa, String title, int min, int max) {
        int r = super.chooseNumber(sa, title, min, max);
        delegated("chooseNumber", DecisionType.DECISION_TYPE_CHOOSE_OPTION);
        return r;
    }

    protected boolean answerChooseBinary(SpellAbility sa, String question, BinaryChoiceType kindOfChoice, Boolean defaultChoice) {
        boolean r = super.chooseBinary(sa, question, kindOfChoice, defaultChoice);
        delegated("chooseBinary", DecisionType.DECISION_TYPE_CHOOSE_OPTION);
        return r;
    }

    protected byte answerChooseColor(String message, SpellAbility sa, ColorSet colors) {
        byte r = super.chooseColor(message, sa, colors);
        if (colors.countColors() > 1) {
            delegated("chooseColor", DecisionType.DECISION_TYPE_CHOOSE_OPTION);
        }
        return r;
    }

    protected CounterType answerChooseCounterType(List<CounterType> options, SpellAbility sa, String prompt, Map<String, Object> params) {
        CounterType r = super.chooseCounterType(options, sa, prompt, params);
        if (options.size() > 1) {
            delegated("chooseCounterType", DecisionType.DECISION_TYPE_CHOOSE_OPTION);
        }
        return r;
    }

    protected CardCollectionView answerChooseCardsForCost(CardCollectionView optionList, SpellAbility sa, CostPartWithList cpl, int amount,
                                                 boolean isOptional, String prompt) {
        CardCollectionView r = super.chooseCardsForCost(optionList, sa, cpl, amount, isOptional, prompt);
        if (r != null) {
            delegated("chooseCardsForCost", DecisionType.DECISION_TYPE_CHOOSE_CARDS, new ArrayList<>(r));
        }
        return r;
    }

    protected Card answerChooseSingleCardForZoneChange(ZoneType destination, List<ZoneType> origin, SpellAbility sa, CardCollection fetchList,
                                              DelayedReveal delayedReveal, String selectPrompt, boolean isOptional, Player decider) {
        Card r = super.chooseSingleCardForZoneChange(destination, origin, sa, fetchList, delayedReveal, selectPrompt, isOptional, decider);
        delegated("chooseSingleCardForZoneChange", DecisionType.DECISION_TYPE_CHOOSE_CARDS, list(r));
        return r;
    }

    protected List<Card> answerChooseCardsForZoneChange(ZoneType destination, List<ZoneType> origin, SpellAbility sa, CardCollection fetchList, int min,
                                               int max, DelayedReveal delayedReveal, String selectPrompt, Player decider) {
        List<Card> r = super.chooseCardsForZoneChange(destination, origin, sa, fetchList, min, max, delayedReveal, selectPrompt, decider);
        delegated("chooseCardsForZoneChange", DecisionType.DECISION_TYPE_CHOOSE_CARDS, r);
        return r;
    }

    // ---- reporting public choices (protocol 9) ----

    private void objects(String method, DecisionType type, Collection<? extends GameObject> chosen) {
        if (weighing || answering > 0) {
            return;
        }
        session.choice(player, type, method, chosen == null ? List.of() : chosen, null, null);
    }

    private void flag(String method, boolean value) {
        if (weighing || answering > 0) {
            return;
        }
        session.choice(player, DecisionType.DECISION_TYPE_CHOOSE_OPTION, method, null, null,
            List.of(ChoiceValue.newBuilder().setFlag(value).build()));
    }

    private void number(String method, Integer value) {
        if (weighing || answering > 0) {
            return;
        }
        session.choice(player, DecisionType.DECISION_TYPE_CHOOSE_OPTION, method, null, null,
            value == null ? List.of() : List.of(ChoiceValue.newBuilder().setNumber(value).build()));
    }

    private void text(String method, DecisionType type, String value) {
        if (weighing || answering > 0) {
            return;
        }
        session.choice(player, type, method, null, null,
            value == null ? List.of() : List.of(ChoiceValue.newBuilder().setText(value).build()));
    }

    private void options(String method, List<Integer> chosen) {
        if (weighing || answering > 0) {
            return;
        }
        session.choice(player, DecisionType.DECISION_TYPE_CHOOSE_OPTION, method, null, chosen, null);
    }

    private static <T> List<T> one(T x) {
        List<T> out = new ArrayList<>();
        if (x != null) {
            out.add(x);
        }
        return out;
    }

    /** Positions of {@code chosen} in the offered list (the engine's order), in choice order. */
    private static List<Integer> indices(List<?> offered, Collection<?> chosen) {
        List<Integer> out = new ArrayList<>();
        if (chosen != null) {
            for (Object o : chosen) {
                int i = offered.indexOf(o);
                if (i >= 0) {
                    out.add(i);
                }
            }
        }
        return out;
    }

    private static ChoiceValue count(Collection<?> c) {
        return ChoiceValue.newBuilder().setNumber(c == null ? 0 : c.size()).build();
    }

    /** Scry: only how many cards went to the top and to the bottom are public. */
    private void scry(ImmutablePair<CardCollection, CardCollection> r) {
        if (weighing || answering > 0) {
            return;
        }
        session.choice(player, DecisionType.DECISION_TYPE_CHOOSE_CARDS, "arrangeForScry", null, null,
            r == null ? List.of() : List.of(count(r.getLeft()), count(r.getRight())));
    }

    /** Surveil: the cards put into the graveyard are public, the ones left on top only by number. */
    private void surveil(ImmutablePair<CardCollection, CardCollection> r) {
        if (weighing || answering > 0) {
            return;
        }
        session.choice(player, DecisionType.DECISION_TYPE_CHOOSE_CARDS, "arrangeForSurveil",
            r == null || r.getRight() == null ? List.of() : r.getRight(), null,
            r == null ? List.of() : List.of(count(r.getLeft())));
    }

    /** The order cards go to a zone in: public, except into a library (only their number). */
    private void moveOrder(CardCollectionView r, ZoneType destination) {
        if (weighing || answering > 0) {
            return;
        }
        if (destination == ZoneType.Library) {
            session.choice(player, DecisionType.DECISION_TYPE_CHOOSE_CARDS, "orderMoveToZoneList", null, null,
                List.of(count(r == null ? null : new ArrayList<>(r))));
        } else {
            objects("orderMoveToZoneList", DecisionType.DECISION_TYPE_CHOOSE_CARDS, r == null ? null : new ArrayList<>(r));
        }
    }

    /** Combat damage of one attacker: each recipient (the defender for a null key) with its amount. */
    private void damage(Map<Card, Integer> r, GameEntity defender) {
        if (weighing || answering > 0) {
            return;
        }
        List<GameObject> to = new ArrayList<>();
        List<ChoiceValue> amounts = new ArrayList<>();
        if (r != null) {
            for (Map.Entry<Card, Integer> e : r.entrySet()) {
                to.add(e.getKey() == null ? defender : e.getKey());
                amounts.add(ChoiceValue.newBuilder().setNumber(e.getValue() == null ? 0 : e.getValue()).build());
            }
        }
        session.choice(player, DecisionType.DECISION_TYPE_DAMAGE_ASSIGNMENT, "assignCombatDamage", to, null, amounts);
    }

    /** New targets chosen for a copy. */
    private void newTargets(TargetChoices r) {
        List<GameObject> to = new ArrayList<>();
        if (r != null) {
            to.addAll(r);
        }
        objects("chooseNewTargetsFor", DecisionType.DECISION_TYPE_TARGET, to);
    }

    // ---- public choices (protocol 9): reported once here, answered by answer* ----
    // A choice with one possible answer is not reported (it tells a human opponent nothing), and a replay answers it
    // without looking at the record.

    @Override
    public final CardCollectionView choosePermanentsToSacrifice(SpellAbility sa, int min, int max, CardCollectionView validTargets, String message) {
        CardCollectionView r;
        answering++;
        try {
            r = answerChoosePermanentsToSacrifice(sa, min, max, validTargets, message);
        } finally {
            answering--;
        }
        objects("choosePermanentsToSacrifice", DecisionType.DECISION_TYPE_CHOOSE_CARDS, r);
        return r;
    }

    @Override
    public final CardCollectionView choosePermanentsToDestroy(SpellAbility sa, int min, int max, CardCollectionView validTargets, String message) {
        CardCollectionView r;
        answering++;
        try {
            r = answerChoosePermanentsToDestroy(sa, min, max, validTargets, message);
        } finally {
            answering--;
        }
        objects("choosePermanentsToDestroy", DecisionType.DECISION_TYPE_CHOOSE_CARDS, r);
        return r;
    }

    @Override
    public final Integer announceRequirements(SpellAbility ability, int min, int max, String announce) {
        Integer r;
        answering++;
        try {
            r = answerAnnounceRequirements(ability, min, max, announce);
        } finally {
            answering--;
        }
        number("announceRequirements", r);
        return r;
    }

    @Override
    public final CardCollectionView chooseCardsForEffect(CardCollectionView sourceList, SpellAbility sa, String title, int min, int max,
                                                   boolean isOptional, Map<String, Object> params) {
        CardCollectionView r;
        answering++;
        try {
            r = answerChooseCardsForEffect(sourceList, sa, title, min, max, isOptional, params);
        } finally {
            answering--;
        }
        objects("chooseCardsForEffect", DecisionType.DECISION_TYPE_CHOOSE_CARDS, r);
        return r;
    }

    @Override
    public final <T extends GameEntity> T chooseSingleEntityForEffect(FCollectionView<T> optionList, DelayedReveal delayedReveal, SpellAbility sa,
                                                               String title, boolean isOptional, Player relatedPlayer, Map<String, Object> params) {
        T r;
        answering++;
        try {
            r = answerChooseSingleEntityForEffect(optionList, delayedReveal, sa, title, isOptional, relatedPlayer, params);
        } finally {
            answering--;
        }
        objects("chooseSingleEntityForEffect", DecisionType.DECISION_TYPE_CHOOSE_CARDS, list(r));
        return r;
    }

    @Override
    public final <T extends GameEntity> List<T> chooseEntitiesForEffect(FCollectionView<T> optionList, int min, int max, DelayedReveal delayedReveal,
                                                                 SpellAbility sa, String title, Player relatedPlayer, Map<String, Object> params) {
        List<T> r;
        answering++;
        try {
            r = answerChooseEntitiesForEffect(optionList, min, max, delayedReveal, sa, title, relatedPlayer, params);
        } finally {
            answering--;
        }
        objects("chooseEntitiesForEffect", DecisionType.DECISION_TYPE_CHOOSE_CARDS, r);
        return r;
    }

    @Override
    public final List<SpellAbility> chooseSpellAbilitiesForEffect(List<SpellAbility> spells, SpellAbility sa, String title, int num,
                                                           Map<String, Object> params) {
        List<SpellAbility> r;
        answering++;
        try {
            r = answerChooseSpellAbilitiesForEffect(spells, sa, title, num, params);
        } finally {
            answering--;
        }
        options("chooseSpellAbilitiesForEffect", indices(spells, r));
        return r;
    }

    @Override
    public final SpellAbility chooseSingleSpellForEffect(List<SpellAbility> spells, SpellAbility sa, String title, Map<String, Object> params) {
        SpellAbility r;
        answering++;
        try {
            r = answerChooseSingleSpellForEffect(spells, sa, title, params);
        } finally {
            answering--;
        }
        if (spells.size() > 1) {
            options("chooseSingleSpellForEffect", indices(spells, one(r)));
        }
        return r;
    }

    @Override
    public final boolean confirmAction(SpellAbility sa, PlayerActionConfirmMode mode, String message, List<String> options, Card cardToShow,
                                 Map<String, Object> params) {
        boolean r;
        answering++;
        try {
            r = answerConfirmAction(sa, mode, message, options, cardToShow, params);
        } finally {
            answering--;
        }
        flag("confirmAction", r);
        return r;
    }

    @Override
    public final boolean confirmReplacementEffect(ReplacementEffect replacementEffect, SpellAbility effectSA, GameEntity affected, String question) {
        boolean r;
        answering++;
        try {
            r = answerConfirmReplacementEffect(replacementEffect, effectSA, affected, question);
        } finally {
            answering--;
        }
        flag("confirmReplacementEffect", r);
        return r;
    }

    @Override
    public final boolean confirmTrigger(WrappedAbility sa) {
        boolean r;
        answering++;
        try {
            r = answerConfirmTrigger(sa);
        } finally {
            answering--;
        }
        flag("confirmTrigger", r);
        return r;
    }

    @Override
    public final CardCollection orderBlockers(Card attacker, CardCollection blockers) {
        CardCollection r;
        answering++;
        try {
            r = answerOrderBlockers(attacker, blockers);
        } finally {
            answering--;
        }
        objects("orderBlockers", DecisionType.DECISION_TYPE_CHOOSE_CARDS, r);
        return r;
    }

    @Override
    public final ImmutablePair<CardCollection, CardCollection> arrangeForScry(CardCollection topN) {
        ImmutablePair<CardCollection, CardCollection> r;
        answering++;
        try {
            r = answerArrangeForScry(topN);
        } finally {
            answering--;
        }
        scry(r);
        return r;
    }

    @Override
    public final ImmutablePair<CardCollection, CardCollection> arrangeForSurveil(CardCollection topN) {
        ImmutablePair<CardCollection, CardCollection> r;
        answering++;
        try {
            r = answerArrangeForSurveil(topN);
        } finally {
            answering--;
        }
        surveil(r);
        return r;
    }

    @Override
    public final boolean willPutCardOnTop(Card c) {
        boolean r;
        answering++;
        try {
            r = answerWillPutCardOnTop(c);
        } finally {
            answering--;
        }
        flag("willPutCardOnTop", r);
        return r;
    }

    @Override
    public final CardCollectionView orderMoveToZoneList(CardCollectionView cards, ZoneType destinationZone, SpellAbility source) {
        CardCollectionView r;
        answering++;
        try {
            r = answerOrderMoveToZoneList(cards, destinationZone, source);
        } finally {
            answering--;
        }
        moveOrder(r, destinationZone);
        return r;
    }

    @Override
    public final CardCollection chooseCardsToDiscardFrom(Player playerDiscard, SpellAbility sa, CardCollection validCards, int min, int max,
                                                       CardCollectionView visibleToChooser) {
        CardCollection r;
        answering++;
        try {
            r = answerChooseCardsToDiscardFrom(playerDiscard, sa, validCards, min, max, visibleToChooser);
        } finally {
            answering--;
        }
        objects("chooseCardsToDiscardFrom", DecisionType.DECISION_TYPE_CHOOSE_CARDS, r);
        return r;
    }

    @Override
    public final CardCollectionView chooseCardsToDiscardToMaximumHandSize(int numDiscard) {
        CardCollectionView r;
        answering++;
        try {
            r = answerChooseCardsToDiscardToMaximumHandSize(numDiscard);
        } finally {
            answering--;
        }
        objects("chooseCardsToDiscardToMaximumHandSize", DecisionType.DECISION_TYPE_CHOOSE_CARDS, r);
        return r;
    }

    @Override
    public final CardCollectionView chooseCardsToDelve(int genericAmount, CardCollection grave) {
        CardCollectionView r;
        answering++;
        try {
            r = answerChooseCardsToDelve(genericAmount, grave);
        } finally {
            answering--;
        }
        objects("chooseCardsToDelve", DecisionType.DECISION_TYPE_CHOOSE_CARDS, r);
        return r;
    }

    @Override
    public final List<AbilitySub> chooseModeForAbility(SpellAbility sa, List<AbilitySub> possible, int min, int num, boolean allowRepeat) {
        List<AbilitySub> r;
        answering++;
        try {
            r = answerChooseModeForAbility(sa, possible, min, num, allowRepeat);
        } finally {
            answering--;
        }
        options("chooseModeForAbility", session.modeIndices(sa, r));
        return r;
    }

    @Override
    public final int chooseNumber(SpellAbility sa, String title, List<Integer> values, Player relatedPlayer) {
        int r;
        answering++;
        try {
            r = answerChooseNumber(sa, title, values, relatedPlayer);
        } finally {
            answering--;
        }
        if (values.size() > 1) {
            number("chooseNumber", r);
        }
        return r;
    }

    @Override
    public final int chooseNumber(SpellAbility sa, String title, int min, int max) {
        int r;
        answering++;
        try {
            r = answerChooseNumber(sa, title, min, max);
        } finally {
            answering--;
        }
        if (max > min) {
            number("chooseNumber", r);
        }
        return r;
    }

    @Override
    public final boolean chooseBinary(SpellAbility sa, String question, BinaryChoiceType kindOfChoice, Boolean defaultChoice) {
        boolean r;
        answering++;
        try {
            r = answerChooseBinary(sa, question, kindOfChoice, defaultChoice);
        } finally {
            answering--;
        }
        flag("chooseBinary", r);
        return r;
    }

    @Override
    public final byte chooseColor(String message, SpellAbility sa, ColorSet colors) {
        byte r;
        answering++;
        try {
            r = answerChooseColor(message, sa, colors);
        } finally {
            answering--;
        }
        if (colors.countColors() > 1) {
            text("chooseColor", DecisionType.DECISION_TYPE_CHOOSE_OPTION, ObservationBuilder.colorLetter(r));
        }
        return r;
    }

    @Override
    public final CounterType chooseCounterType(List<CounterType> options, SpellAbility sa, String prompt, Map<String, Object> params) {
        CounterType r;
        answering++;
        try {
            r = answerChooseCounterType(options, sa, prompt, params);
        } finally {
            answering--;
        }
        if (options.size() > 1) {
            text("chooseCounterType", DecisionType.DECISION_TYPE_CHOOSE_OPTION, r == null ? null : ObservationBuilder.counterName(r));
        }
        return r;
    }

    @Override
    public final CardCollectionView chooseCardsForCost(CardCollectionView optionList, SpellAbility sa, CostPartWithList cpl, int amount,
                                                 boolean isOptional, String prompt) {
        CardCollectionView r;
        answering++;
        try {
            r = answerChooseCardsForCost(optionList, sa, cpl, amount, isOptional, prompt);
        } finally {
            answering--;
        }
        objects("chooseCardsForCost", DecisionType.DECISION_TYPE_CHOOSE_CARDS, r);
        return r;
    }

    @Override
    public final Card chooseSingleCardForZoneChange(ZoneType destination, List<ZoneType> origin, SpellAbility sa, CardCollection fetchList,
                                              DelayedReveal delayedReveal, String selectPrompt, boolean isOptional, Player decider) {
        Card r;
        answering++;
        try {
            r = answerChooseSingleCardForZoneChange(destination, origin, sa, fetchList, delayedReveal, selectPrompt, isOptional, decider);
        } finally {
            answering--;
        }
        objects("chooseSingleCardForZoneChange", DecisionType.DECISION_TYPE_CHOOSE_CARDS, list(r));
        return r;
    }

    @Override
    public final List<Card> chooseCardsForZoneChange(ZoneType destination, List<ZoneType> origin, SpellAbility sa, CardCollection fetchList, int min,
                                               int max, DelayedReveal delayedReveal, String selectPrompt, Player decider) {
        List<Card> r;
        answering++;
        try {
            r = answerChooseCardsForZoneChange(destination, origin, sa, fetchList, min, max, delayedReveal, selectPrompt, decider);
        } finally {
            answering--;
        }
        objects("chooseCardsForZoneChange", DecisionType.DECISION_TYPE_CHOOSE_CARDS, r);
        return r;
    }

    @Override
    public final List<SpellAbility> chooseSaToActivateFromOpeningHand(List<SpellAbility> usableFromOpeningHand) {
        List<SpellAbility> r;
        answering++;
        try {
            r = answerChooseSaToActivateFromOpeningHand(usableFromOpeningHand);
        } finally {
            answering--;
        }
        if (!weighing && answering == 0) {
            // the cards the abilities belong to as well: which card leaves the hand is public (it goes to the
            // battlefield or is revealed), and the list's order depends on the hidden rest of the hand
            List<GameObject> hosts = new ArrayList<>();
            if (r != null) {
                r.forEach(sa -> hosts.add(sa.getHostCard()));
            }
            session.choice(player, DecisionType.DECISION_TYPE_CHOOSE_OPTION, "chooseSaToActivateFromOpeningHand", hosts,
                indices(usableFromOpeningHand, r), null);
        }
        return r;
    }

    @Override
    public final String chooseSomeType(String kindOfType, SpellAbility sa, Collection<String> validTypes, boolean isOptional) {
        String r;
        answering++;
        try {
            r = answerChooseSomeType(kindOfType, sa, validTypes, isOptional);
        } finally {
            answering--;
        }
        text("chooseSomeType", DecisionType.DECISION_TYPE_NAME, r);
        return r;
    }

    @Override
    public final String chooseCardName(SpellAbility sa, List<ICardFace> faces, String message) {
        String r;
        answering++;
        try {
            r = answerChooseCardName(sa, faces, message);
        } finally {
            answering--;
        }
        text("chooseCardName", DecisionType.DECISION_TYPE_NAME, r == null ? null : session.keys().oracleIdByName(r));
        return r;
    }

    @Override
    public final String chooseCardName(SpellAbility sa, Predicate<ICardFace> cpp, String valid, String message) {
        String r;
        answering++;
        try {
            r = answerChooseCardName(sa, cpp, valid, message);
        } finally {
            answering--;
        }
        text("chooseCardName", DecisionType.DECISION_TYPE_NAME, r == null ? null : session.keys().oracleIdByName(r));
        return r;
    }

    @Override
    public final ReplacementEffect chooseSingleReplacementEffect(List<ReplacementEffect> possibleReplacers) {
        ReplacementEffect r;
        answering++;
        try {
            r = answerChooseSingleReplacementEffect(possibleReplacers);
        } finally {
            answering--;
        }
        if (possibleReplacers.size() > 1) {
            options("chooseSingleReplacementEffect", indices(possibleReplacers, one(r)));
        }
        return r;
    }

    @Override
    public final int chooseNumberForCostReduction(SpellAbility sa, int min, int max) {
        int r;
        answering++;
        try {
            r = answerChooseNumberForCostReduction(sa, min, max);
        } finally {
            answering--;
        }
        if (max > min) {
            number("chooseNumberForCostReduction", r);
        }
        return r;
    }

    @Override
    public final int chooseNumberForKeywordCost(SpellAbility sa, Cost cost, KeywordInterface keyword, String prompt, int max) {
        int r;
        answering++;
        try {
            r = answerChooseNumberForKeywordCost(sa, cost, keyword, prompt, max);
        } finally {
            answering--;
        }
        if (max > 0) {
            number("chooseNumberForKeywordCost", r);
        }
        return r;
    }

    @Override
    public final Map<Card, Integer> assignCombatDamage(Card attacker, CardCollectionView blockers, CardCollectionView remaining,
                                                 int damageDealt, GameEntity defender, boolean overrideOrder) {
        Map<Card, Integer> r;
        answering++;
        try {
            r = answerAssignCombatDamage(attacker, blockers, remaining, damageDealt, defender, overrideOrder);
        } finally {
            answering--;
        }
        damage(r, defender);
        return r;
    }

    @Override
    public final TargetChoices chooseNewTargetsFor(SpellAbility ability, Predicate<GameObject> filter, boolean optional) {
        TargetChoices r;
        answering++;
        try {
            r = answerChooseNewTargetsFor(ability, filter, optional);
        } finally {
            answering--;
        }
        newTargets(r);
        return r;
    }

    protected boolean answerConfirmPayment(CostPart costPart, String string, SpellAbility sa) {
        return super.confirmPayment(costPart, string, sa);
    }

    @Override
    public final boolean confirmPayment(CostPart costPart, String string, SpellAbility sa) {
        boolean r;
        answering++;
        try {
            r = answerConfirmPayment(costPart, string, sa);
        } finally {
            answering--;
        }
        flag("confirmPayment", r);
        return r;
    }

    protected boolean answerPayCostToPreventEffect(Cost cost, SpellAbility sa, boolean alreadyPaid, FCollectionView<Player> allPayers) {
        return super.payCostToPreventEffect(cost, sa, alreadyPaid, allPayers);
    }

    @Override
    public final boolean payCostToPreventEffect(Cost cost, SpellAbility sa, boolean alreadyPaid, FCollectionView<Player> allPayers) {
        boolean r;
        answering++;
        try {
            r = answerPayCostToPreventEffect(cost, sa, alreadyPaid, allPayers);
        } finally {
            answering--;
        }
        flag("payCostToPreventEffect", r);
        return r;
    }

    protected List<OptionalCostValue> answerChooseOptionalCosts(SpellAbility choosen, List<OptionalCostValue> optionalCostValues) {
        return super.chooseOptionalCosts(choosen, optionalCostValues);
    }

    @Override
    public final List<OptionalCostValue> chooseOptionalCosts(SpellAbility choosen, List<OptionalCostValue> optionalCostValues) {
        List<OptionalCostValue> r;
        answering++;
        try {
            r = answerChooseOptionalCosts(choosen, optionalCostValues);
        } finally {
            answering--;
        }
        if (session.inAction()) {
            // Forge AI also asks this while it only weighs a spell; the choice is made when the spell is cast
            options("chooseOptionalCosts", indices(optionalCostValues, r));
        }
        return r;
    }

    // ---- decisions not classified yet: Forge AI answers and each call is counted (docs/player-controller-methods.md) ----

    private void unclassified(String method) {
        // inside forgeAi(...) the decision is a classified one answered by Forge AI (a delegation or a recorded fallback)
        if (forAgent && !fallingBack) {
            session.unclassified(player, method);
        }
    }

    @Override
    public List<PaperCard> chooseCardsYouWonToAddToDeck(List<PaperCard> losses) {
        unclassified("chooseCardsYouWonToAddToDeck");
        return super.chooseCardsYouWonToAddToDeck(losses);
    }

    @Override
    public Map<GameEntity, Integer> divideShield(Card effectSource, Map<GameEntity, Integer> affected, int shieldAmount) {
        unclassified("divideShield");
        return super.divideShield(effectSource, affected, shieldAmount);
    }

    protected TargetChoices answerChooseNewTargetsFor(SpellAbility ability, Predicate<GameObject> filter, boolean optional) {
        delegated("chooseNewTargetsFor", DecisionType.DECISION_TYPE_TARGET); // new targets for a copy
        return super.chooseNewTargetsFor(ability, filter, optional);
    }

    @Override
    public Pair<SpellAbilityStackInstance, GameObject> chooseTarget(SpellAbility sa, List<Pair<SpellAbilityStackInstance, GameObject>> allTargets) {
        unclassified("chooseTarget");
        return super.chooseTarget(sa, allTargets);
    }

    @Override
    public boolean helpPayForAssistSpell(ManaCostBeingPaid cost, SpellAbility sa, int max, int requested) {
        unclassified("helpPayForAssistSpell");
        return super.helpPayForAssistSpell(cost, sa, max, requested);
    }

    @Override
    public Player choosePlayerToAssistPayment(FCollectionView<Player> optionList, SpellAbility sa, String title, int max) {
        unclassified("choosePlayerToAssistPayment");
        return super.choosePlayerToAssistPayment(optionList, sa, title, max);
    }

    @Override
    public CardCollection chooseCardsForEffectMultiple(Map<String, CardCollection> validMap, SpellAbility sa, String title, boolean isOptional) {
        unclassified("chooseCardsForEffectMultiple");
        return super.chooseCardsForEffectMultiple(validMap, sa, title, isOptional);
    }

    @Override
    public boolean confirmBidAction(SpellAbility sa, PlayerActionConfirmMode bidlife, String string, int bid, Player winner) {
        unclassified("confirmBidAction");
        return super.confirmBidAction(sa, bidlife, string, bid, winner);
    }

    @Override
    public boolean confirmStaticApplication(Card hostCard, PlayerActionConfirmMode mode, String message, String logic) {
        unclassified("confirmStaticApplication");
        return super.confirmStaticApplication(hostCard, mode, message, logic);
    }

    @Override
    public List<Card> exertAttackers(List<Card> attackers) {
        unclassified("exertAttackers");
        return super.exertAttackers(attackers);
    }

    @Override
    public List<Card> enlistAttackers(List<Card> attackers) {
        unclassified("enlistAttackers");
        return super.enlistAttackers(attackers);
    }

    @Override
    public CardCollection orderBlocker(Card attacker, Card blocker, CardCollection oldBlockers) {
        unclassified("orderBlocker");
        return super.orderBlocker(attacker, blocker, oldBlockers);
    }

    @Override
    public CardCollection orderAttackers(Card blocker, CardCollection attackers) {
        unclassified("orderAttackers");
        return super.orderAttackers(blocker, attackers);
    }

    @Override
    public CardCollectionView chooseCardsToDiscardUnlessType(int min, CardCollectionView hand, String[] unlessTypes, SpellAbility sa) {
        unclassified("chooseCardsToDiscardUnlessType");
        return super.chooseCardsToDiscardUnlessType(min, hand, unlessTypes, sa);
    }

    @Override
    public Map<Card, ManaCostShard> chooseCardsForConvokeOrImprovise(SpellAbility sa, ManaCost manaCost, CardCollectionView untappedCards, boolean artifacts, boolean creatures, Integer maxReduction) {
        unclassified("chooseCardsForConvokeOrImprovise");
        return super.chooseCardsForConvokeOrImprovise(sa, manaCost, untappedCards, artifacts, creatures, maxReduction);
    }

    @Override
    public List<Card> chooseCardsForSplice(SpellAbility sa, List<Card> cards) {
        unclassified("chooseCardsForSplice");
        return super.chooseCardsForSplice(sa, cards);
    }

    @Override
    public CardCollectionView chooseCardsToRevealFromHand(int min, int max, CardCollectionView valid) {
        unclassified("chooseCardsToRevealFromHand");
        return super.chooseCardsToRevealFromHand(min, max, valid);
    }

    protected List<SpellAbility> answerChooseSaToActivateFromOpeningHand(List<SpellAbility> usableFromOpeningHand) {
        unclassified("chooseSaToActivateFromOpeningHand");
        return super.chooseSaToActivateFromOpeningHand(usableFromOpeningHand);
    }

    @Override
    public PlayerZone chooseStartingHand(List<PlayerZone> zones) {
        unclassified("chooseStartingHand");
        return super.chooseStartingHand(zones);
    }

    @Override
    public Mana chooseManaFromPool(List<Mana> manaChoices) {
        unclassified("chooseManaFromPool");
        return super.chooseManaFromPool(manaChoices);
    }

    /** Forge AI's own pick of pool mana, for a caller that has classified the call itself. */
    protected Mana forgeAiManaFromPool(List<Mana> manaChoices) {
        return super.chooseManaFromPool(manaChoices);
    }

    protected String answerChooseSomeType(String kindOfType, SpellAbility sa, Collection<String> validTypes, boolean isOptional) {
        unclassified("chooseSomeType");
        return super.chooseSomeType(kindOfType, sa, validTypes, isOptional);
    }

    @Override
    public String chooseSector(Card assignee, String ai, List<String> sectors) {
        unclassified("chooseSector");
        return super.chooseSector(assignee, ai, sectors);
    }

    @Override
    public Sticker chooseSticker(List<Sticker> options, Card target, SpellAbility sa, boolean isOptional) {
        unclassified("chooseSticker");
        return super.chooseSticker(options, target, sa, isOptional);
    }

    @Override
    public int chooseStickerNamePosition(Sticker sticker, Card target) {
        unclassified("chooseStickerNamePosition");
        return super.chooseStickerNamePosition(sticker, target);
    }

    @Override
    public Card chooseCardToKeepStickers(CardCollectionView options) {
        unclassified("chooseCardToKeepStickers");
        return super.chooseCardToKeepStickers(options);
    }

    @Override
    public List<Card> chooseContraptionsToCrank(List<Card> contraptions) {
        unclassified("chooseContraptionsToCrank");
        return super.chooseContraptionsToCrank(contraptions);
    }

    @Override
    public int chooseSprocket(Card assignee, List<Integer> sprockets) {
        unclassified("chooseSprocket");
        return super.chooseSprocket(assignee, sprockets);
    }

    @Override
    public PlanarDice choosePDRollToIgnore(List<PlanarDice> rolls) {
        unclassified("choosePDRollToIgnore");
        return super.choosePDRollToIgnore(rolls);
    }

    @Override
    public Integer chooseRollToIgnore(List<Integer> rolls) {
        unclassified("chooseRollToIgnore");
        return super.chooseRollToIgnore(rolls);
    }

    @Override
    public List<Integer> chooseDiceToReroll(List<Integer> rolls) {
        unclassified("chooseDiceToReroll");
        return super.chooseDiceToReroll(rolls);
    }

    @Override
    public Integer chooseRollToModify(List<Integer> rolls) {
        unclassified("chooseRollToModify");
        return super.chooseRollToModify(rolls);
    }

    @Override
    public RollDiceEffect.DieRollResult chooseRollToSwap(List<RollDiceEffect.DieRollResult> rolls) {
        unclassified("chooseRollToSwap");
        return super.chooseRollToSwap(rolls);
    }

    @Override
    public String chooseRollSwapValue(List<String> swapChoices, Integer currentResult, int power, int toughness) {
        unclassified("chooseRollSwapValue");
        return super.chooseRollSwapValue(swapChoices, currentResult, power, toughness);
    }

    @Override
    public Object vote(SpellAbility sa, String prompt, List<Object> options, ListMultimap<Object, Player> votes, Player forPlayer, boolean optional) {
        unclassified("vote");
        return super.vote(sa, prompt, options, votes, forPlayer, optional);
    }

    protected int answerChooseNumberForCostReduction(SpellAbility sa, int min, int max) {
        unclassified("chooseNumberForCostReduction");
        return super.chooseNumberForCostReduction(sa, min, max);
    }

    protected int answerChooseNumberForKeywordCost(SpellAbility sa, Cost cost, KeywordInterface keyword, String prompt, int max) {
        unclassified("chooseNumberForKeywordCost");
        return super.chooseNumberForKeywordCost(sa, cost, keyword, prompt, max);
    }

    @Override
    public boolean chooseFlipResult(SpellAbility sa, Player flipper, boolean call) {
        unclassified("chooseFlipResult");
        return super.chooseFlipResult(sa, flipper, call);
    }

    @Override
    public byte chooseColorAllowColorless(String message, Card c, ColorSet colors) {
        unclassified("chooseColorAllowColorless");
        return super.chooseColorAllowColorless(message, c, colors);
    }

    @Override
    public ColorSet chooseColors(String message, SpellAbility sa, int min, int max, ColorSet options) {
        unclassified("chooseColors");
        return super.chooseColors(message, sa, min, max, options);
    }

    @Override
    public ICardFace chooseSingleCardFace(SpellAbility sa, String message, Predicate<ICardFace> cpp, String name) {
        unclassified("chooseSingleCardFace");
        return super.chooseSingleCardFace(sa, message, cpp, name);
    }

    @Override
    public ICardFace chooseSingleCardFace(SpellAbility sa, List<ICardFace> faces, String message) {
        unclassified("chooseSingleCardFace");
        return super.chooseSingleCardFace(sa, faces, message);
    }

    @Override
    public CardState chooseSingleCardState(SpellAbility sa, List<CardState> states, String message, Map<String, Object> params) {
        unclassified("chooseSingleCardState");
        return super.chooseSingleCardState(sa, states, message, params);
    }

    @Override
    public boolean chooseCardsPile(SpellAbility sa, CardCollectionView pile1, CardCollectionView pile2, String faceUp) {
        unclassified("chooseCardsPile");
        return super.chooseCardsPile(sa, pile1, pile2, faceUp);
    }

    @Override
    public String chooseKeywordForPump(List<String> options, SpellAbility sa, String prompt, Card tgtCard) {
        unclassified("chooseKeywordForPump");
        return super.chooseKeywordForPump(options, sa, prompt, tgtCard);
    }

    protected ReplacementEffect answerChooseSingleReplacementEffect(List<ReplacementEffect> possibleReplacers) {
        unclassified("chooseSingleReplacementEffect");
        return super.chooseSingleReplacementEffect(possibleReplacers);
    }

    @Override
    public StaticAbility chooseSingleStaticAbility(List<StaticAbility> possibleReplacers) {
        delegated("chooseSingleStaticAbility", DecisionType.DECISION_TYPE_CHOOSE_OPTION); // order cost changes apply in
        return super.chooseSingleStaticAbility(possibleReplacers);
    }

    @Override
    public String chooseProtectionType(SpellAbility sa, List<String> choices) {
        unclassified("chooseProtectionType");
        return super.chooseProtectionType(sa, choices);
    }

    @Override
    public List<CostPart> orderCosts(List<CostPart> costs) {
        delegated("orderCosts", DecisionType.DECISION_TYPE_CHOOSE_OPTION); // engine sequencing of cost parts
        return super.orderCosts(costs);
    }

    @Override
    public boolean payCostDuringRoll(Cost cost, SpellAbility sa) {
        unclassified("payCostDuringRoll");
        return super.payCostDuringRoll(cost, sa);
    }

    @Override
    public boolean payCombatCost(Card card, Cost cost, SpellAbility sa, String prompt) {
        unclassified("payCombatCost");
        return super.payCombatCost(card, cost, sa, prompt);
    }

    protected String answerChooseCardName(SpellAbility sa, Predicate<ICardFace> cpp, String valid, String message) {
        unclassified("chooseCardName");
        return super.chooseCardName(sa, cpp, valid, message);
    }

    protected String answerChooseCardName(SpellAbility sa, List<ICardFace> faces, String message) {
        unclassified("chooseCardName");
        return super.chooseCardName(sa, faces, message);
    }

    protected int answerChooseNumber(SpellAbility sa, String title, List<Integer> values, Player relatedPlayer) {
        unclassified("chooseNumber(values)");
        return super.chooseNumber(sa, title, values, relatedPlayer);
    }

    /**
     * A look at cards that is not a choice: Forge shows the digger every card it dug (Monumental Henge's "look at the
     * top five") while it chooses among the qualifying ones. The chooser saw them all.
     */
    @Override
    public void tempShowCards(Iterable<Card> cards) {
        super.tempShowCards(cards);
        List<Card> shown = new ArrayList<>();
        cards.forEach(shown::add);
        session.showTo(player, shown);
    }

    /**
     * A dig with no qualifying card shows nothing and only notifies: the digger still looked at the cards, which are
     * the top of its library until the effect moves them.
     */
    @Override
    public void notifyOfValue(SpellAbility sa, forge.game.GameObject target, String value) {
        super.notifyOfValue(sa, target, value);
        if (sa == null || target != null || sa.getApi() != forge.game.ability.ApiType.Dig || sa.hasParam("Defined")
                || sa.usesTargeting() || sa.hasParam("Reveal") || sa.getActivatingPlayer() != player) {
            return;
        }
        ZoneType zone = sa.hasParam("SourceZone") ? ZoneType.smartValueOf(sa.getParam("SourceZone")) : ZoneType.Library;
        if (zone != ZoneType.Library) {
            return;
        }
        List<Card> lib = new ArrayList<>();
        player.getCardsIn(ZoneType.Library).forEach(lib::add);
        if (sa.hasParam("FromBottom")) {
            java.util.Collections.reverse(lib);
        }
        int n = forge.game.ability.AbilityUtils.calculateAmount(sa.getHostCard(), sa.getParamOrDefault("DigNum", "1"), sa);
        session.showTo(player, lib.subList(0, Math.min(n, lib.size())));
    }

    @Override
    public void reveal(CardCollectionView cards, ZoneType zone, Player owner, String messagePrefix, boolean addMsgSuffix) {
        super.reveal(cards, zone, owner, messagePrefix, addMsgSuffix);
        List<Card> shown = new ArrayList<>();
        cards.forEach(shown::add);
        session.showTo(player, shown); // Forge shows these to this controller's player
    }
}
