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
 * as opponent decisions), and as the base of {@link RlPlayerController}, where every method not overridden there is an
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

    /** Casting, activating or playing a land: its action event comes before the changes it causes. */
    @Override
    public boolean playChosenSpellAbility(SpellAbility sa) {
        session.beginAction();
        try {
            return super.playChosenSpellAbility(sa);
        } finally {
            session.endAction();
        }
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

    // ---- decisions not classified yet: Forge AI answers and each call is counted (docs/player-controller-methods.md) ----

    private void unclassified(String method) {
        if (forAgent) {
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

    @Override
    public TargetChoices chooseNewTargetsFor(SpellAbility ability, Predicate<GameObject> filter, boolean optional) {
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

    @Override
    public List<SpellAbility> chooseSaToActivateFromOpeningHand(List<SpellAbility> usableFromOpeningHand) {
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

    @Override
    public String chooseSomeType(String kindOfType, SpellAbility sa, Collection<String> validTypes, boolean isOptional) {
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

    @Override
    public int chooseNumberForCostReduction(SpellAbility sa, int min, int max) {
        unclassified("chooseNumberForCostReduction");
        return super.chooseNumberForCostReduction(sa, min, max);
    }

    @Override
    public int chooseNumberForKeywordCost(SpellAbility sa, Cost cost, KeywordInterface keyword, String prompt, int max) {
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

    @Override
    public ReplacementEffect chooseSingleReplacementEffect(List<ReplacementEffect> possibleReplacers) {
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

    @Override
    public String chooseCardName(SpellAbility sa, Predicate<ICardFace> cpp, String valid, String message) {
        unclassified("chooseCardName");
        return super.chooseCardName(sa, cpp, valid, message);
    }

    @Override
    public String chooseCardName(SpellAbility sa, List<ICardFace> faces, String message) {
        unclassified("chooseCardName");
        return super.chooseCardName(sa, faces, message);
    }

    @Override
    public int chooseNumber(SpellAbility sa, String title, List<Integer> values, Player relatedPlayer) {
        unclassified("chooseNumber(values)");
        return super.chooseNumber(sa, title, values, relatedPlayer);
    }

    @Override
    public void reveal(CardCollectionView cards, ZoneType zone, Player owner, String messagePrefix, boolean addMsgSuffix) {
        super.reveal(cards, zone, owner, messagePrefix, addMsgSuffix);
        List<Card> shown = new ArrayList<>();
        cards.forEach(shown::add);
        session.showTo(player, shown); // Forge shows these to this controller's player
    }
}
