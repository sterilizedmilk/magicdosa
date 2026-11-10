package magicdosa.adapter.forge;

import forge.LobbyPlayer;
import forge.ai.ComputerUtilAbility;
import forge.ai.ComputerUtilCost;
import forge.ai.ComputerUtilMana;
import forge.card.ColorSet;
import forge.card.ICardFace;
import forge.card.MagicColor;
import forge.card.mana.ManaCost;
import forge.game.Game;
import forge.game.GameActionUtil;
import forge.game.GameEntity;
import forge.game.GameObject;
import forge.game.card.Card;
import forge.game.card.CardCollection;
import forge.game.card.CardCollectionView;
import forge.game.card.CounterType;
import forge.game.combat.AttackRequirement;
import forge.game.combat.Combat;
import forge.game.combat.CombatUtil;
import forge.game.keyword.Keyword;
import forge.game.keyword.KeywordInterface;
import forge.game.cost.Cost;
import forge.game.cost.CostDecisionMakerBase;
import forge.game.cost.CostPart;
import forge.game.cost.CostPartMana;
import forge.game.cost.CostPartWithList;
import forge.game.mana.Mana;
import forge.game.mana.ManaConversionMatrix;
import forge.game.mana.ManaCostBeingPaid;
import forge.game.mana.ManaPool;
import forge.game.player.DelayedReveal;
import forge.game.player.PlaySpellAbility;
import forge.game.player.Player;
import forge.game.player.PlayerActionConfirmMode;
import forge.game.replacement.ReplacementEffect;
import forge.game.spellability.AbilitySub;
import forge.game.spellability.OptionalCostValue;
import forge.game.spellability.SpellAbility;
import forge.game.staticability.StaticAbility;
import forge.game.staticability.StaticAbilityCantAttackBlock;
import forge.game.trigger.WrappedAbility;
import forge.game.spellability.SpellAbilityStackInstance;
import forge.game.spellability.TargetRestrictions;
import forge.game.zone.ZoneType;
import forge.game.ability.AbilityUtils;
import forge.deck.Deck;
import forge.deck.DeckSection;
import forge.game.GameType;
import forge.item.PaperCard;
import forge.util.ITriggerEvent;
import forge.util.collect.FCollectionView;
import magicdosa.protocol.v1.AbilityKey;
import magicdosa.protocol.v1.ActivateAbilityCandidate;
import magicdosa.protocol.v1.Attack;
import magicdosa.protocol.v1.Block;
import magicdosa.protocol.v1.Candidate;
import magicdosa.protocol.v1.CastSpellCandidate;
import magicdosa.protocol.v1.DamageAssignment;
import magicdosa.protocol.v1.DamageShare;
import magicdosa.protocol.v1.DecisionContext;
import magicdosa.protocol.v1.DecisionType;
import magicdosa.protocol.v1.FallbackReason;
import magicdosa.protocol.v1.ManaSourceCandidate;
import magicdosa.protocol.v1.ManaUnit;
import magicdosa.protocol.v1.OptionCandidate;
import magicdosa.protocol.v1.PairLimit;
import magicdosa.protocol.v1.PassCandidate;
import magicdosa.protocol.v1.PassPriority;
import magicdosa.protocol.v1.PlayLandCandidate;
import magicdosa.protocol.v1.PotentialCast;
import magicdosa.protocol.v1.Rejection;
import magicdosa.protocol.v1.SelectionConstraints;
import magicdosa.protocol.v1.SideboardSwap;
import magicdosa.protocol.v1.TriggerOrderChoice;

import org.apache.commons.lang3.tuple.ImmutablePair;
import org.apache.commons.lang3.tuple.Pair;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Forge controller for a seat agent-core decides for: the decision layer of the adapter/forge library (2026-11-10
 * split; the design's RlPlayerController). Each Forge question becomes a request (or is answered without asking when
 * it has one outcome), and the chosen candidates go back to Forge. Requests go through the session's
 * {@link DecisionClient}: agent-core over gRPC in runner/forge.
 *
 * Spells and abilities are cast the way a human player casts them ({@link PlaySpellAbility}, 601.2: modes, X and
 * costs, targets, payment), with agent-core answering every choice the controller is asked: priority actions
 * including mana abilities, targets, modes, X, optional costs, the cards non-mana costs use ({@link AgentCostDecision})
 * and mana payment from the pool (no auto-tap, 2026-11-06), plus attackers, blockers, mulligan, cards to bottom and
 * play/draw. Everything else is left to Forge AI as an intended delegation and recorded by
 * {@link ObservedAiController}. When agent-core gives no usable answer, Forge AI decides and the decision is recorded
 * as an unintended fallback; nothing falls back silently.
 */
public class AgentPlayerController extends ObservedAiController {
    private static final SelectionConstraints ONE = SelectionConstraints.newBuilder().setMin(1).setMax(1).build();
    /** Engine refusals of one decision before it counts as an unintended fallback (Q15). */
    private static final int MAX_REJECTIONS = 3;

    /** While set, cost-change callbacks (delve) answer without asking: castability checks assume the largest reduction. */
    private boolean checkingCosts;
    /** The priority decision whose action is being played; recorded once Forge played or refused it. */
    private ForgeGameSession.Asked pendingPriority;
    private int pendingChosen;
    private final List<Rejection> priorityRejections = new ArrayList<>();
    private void clearPriorityRefusals() {
        priorityRejections.clear();
    }
    /** After the refusals of a priority decision are used up: pass once. */
    private boolean forcePass;
    private int priorityAsks;

    /**
     * Runaway-repeat safety cap (2026-11-06 plan, section 4): the same action chosen more than this many times in one
     * priority window (turn and phase) is not played; the seat passes and the decision is recorded as a safety cap.
     * Not a rule about combos; loop shortcuts (CR 732) are not modelled.
     */
    static final int REPEAT_CAP = 200;
    private String repeatWindow = "";
    private final Map<String, Integer> repeats = new java.util.HashMap<>();

    /** Counts {@code action} in the current window; true when it went over the cap. */
    private boolean overRepeatCap(String action) {
        Game g = player.getGame();
        String window = g.getPhaseHandler().getTurn() + "/" + g.getPhaseHandler().getPhase();
        if (!window.equals(repeatWindow)) {
            repeatWindow = window;
            repeats.clear();
        }
        return repeats.merge(action, 1, Integer::sum) > REPEAT_CAP;
    }

    /** The agent seat that decides: the player itself, or the agent seat that controls it (deck item 4). */
    private final Player decider;

    public AgentPlayerController(Game game, Player player, LobbyPlayer lobbyPlayer, ForgeGameSession session) {
        this(game, player, lobbyPlayer, session, player);
    }

    /** A controller for {@code player} while the agent seat {@code decider} controls it (Emrakul, the Promised End). */
    AgentPlayerController(Game game, Player player, LobbyPlayer lobbyPlayer, ForgeGameSession session, Player decider) {
        super(game, player, lobbyPlayer, session, true);
        this.decider = decider;
    }

    private static Candidate pass() {
        return Candidate.newBuilder().setPass(PassCandidate.getDefaultInstance()).build();
    }

    // ---- priority (design: ?ㅺ퀎 寃??2, Q14) ----

    /**
     * Legal priority actions now: land plays, mana abilities, and spells and abilities whose costs the pool and
     * the non-mana costs can pay (mana is made in advance, 2026-11-06). Spells that more mana would pay for are kept
     * for the observation (potential casts, approximate).
     */
    private List<SpellAbility> legalActions() {
        Game game = player.getGame();
        CardCollection cards = ComputerUtilAbility.getAvailableCards(game, player);
        List<SpellAbility> all = ComputerUtilAbility.getSpellAbilities(cards, player);
        List<SpellAbility> out = new ArrayList<>();
        List<PotentialCast> potential = new ArrayList<>();
        List<SpellAbility> considered = new ArrayList<>();
        checkingCosts = true;
        try {
            for (SpellAbility sa : ComputerUtilAbility.getOriginalAndAltCostAbilities(all, player)) {
                sa.setActivatingPlayer(player);
                if (!sa.checkRestrictions(sa.getHostCard(), player)) {
                    continue;
                }
                if (sa.isLandAbility()) {
                    if (sa.canPlay()) {
                        out.add(sa);
                    }
                    continue;
                }
                if (!sa.canPlay() || (!sa.isManaAbility() && !(sa.isLegalAfterStack() && ComputerUtilAbility.isFullyTargetable(sa)))) {
                    continue;
                }
                considered.add(sa);
                if (ManaPayments.payableNow(player, sa)) {
                    out.add(sa);
                } else if (!sa.isManaAbility() && ComputerUtilCost.canPayCost(sa, player, false)) {
                    potential.add(potentialCast(sa));
                }
            }
        } finally {
            checkingCosts = false;
        }
        if (decider == player) {
            session.potentialCasts(player, potential);
        }
        priorityAsks++;
        session.priorityCheck().check(player, priorityAsks, out, considered, session.stats());
        return out;
    }

    private PotentialCast potentialCast(SpellAbility sa) {
        PotentialCast.Builder p = PotentialCast.newBuilder().setSourceId(session.ids().idOf(sa.getHostCard()));
        if (sa.isSpell()) {
            p.setSpell(session.keys().spellKey(sa));
        } else {
            AbilityKey k = session.keys().abilityKey(sa);
            if (k != null) {
                p.setAbility(k);
            }
        }
        return p.build();
    }

    private Candidate candidate(SpellAbility sa) {
        Card host = sa.getHostCard();
        long id = session.ids().idOf(host);
        if (sa.isLandAbility()) {
            return Candidate.newBuilder().setPlayLand(PlayLandCandidate.newBuilder().setCardId(id)
                .setLand(session.keys().faceKey(host, sa.getCardState() != null ? sa.getCardState() : host.getCurrentState()))).build();
        }
        if (sa.isSpell()) {
            return Candidate.newBuilder().setCastSpell(CastSpellCandidate.newBuilder().setCardId(id)
                .setSpell(session.keys().spellKey(sa))).build();
        }
        ActivateAbilityCandidate.Builder a = ActivateAbilityCandidate.newBuilder().setSourceId(id).setManaAbility(sa.isManaAbility());
        var key = session.keys().abilityKey(sa);
        if (key != null) {
            a.setAbility(key);
        }
        return Candidate.newBuilder().setActivateAbility(a).build();
    }

    @Override
    public List<SpellAbility> chooseSpellAbilityToPlay() {
        session.syncZones();
        List<SpellAbility> actions = legalActions();
        if (forcePass) {
            forcePass = false;
            session.action(player, false, b -> b.setPassPriority(PassPriority.newBuilder().setSeat(session.seat(player))));
            return null;
        }
        if (actions.isEmpty()) {
            clearPriorityRefusals();
            session.stats().autoPass();
            session.action(player, true, b -> b.setPassPriority(PassPriority.newBuilder().setSeat(session.seat(player))));
            return null;
        }
        List<Candidate> candidates = new ArrayList<>();
        candidates.add(pass());
        for (SpellAbility sa : actions) {
            candidates.add(candidate(sa));
        }
        ForgeGameSession.Asked asked = session.ask(decider, player, DecisionType.DECISION_TYPE_PRIORITY, candidates, ONE, null,
            List.copyOf(priorityRejections));
        if (!asked.ok()) {
            session.record(asked, List.of(), "chooseSpellAbilityToPlay", true, asked.failure());
            clearPriorityRefusals();
            return super.chooseSpellAbilityToPlay();
        }
        int chosen = asked.response().getChosen(0);
        if (chosen == 0) {
            session.record(asked, List.of(chosen), "chooseSpellAbilityToPlay", false, null);
            clearPriorityRefusals();
            session.action(player, false, b -> b.setPassPriority(PassPriority.newBuilder().setSeat(session.seat(player))));
            return null;
        }
        if (overRepeatCap(candidates.get(chosen).toString())) {
            session.recordSafetyCap(asked, "chooseSpellAbilityToPlay", candidates.get(chosen));
            session.action(player, false, b -> b.setPassPriority(PassPriority.newBuilder().setSeat(session.seat(player))));
            return null;
        }
        pendingPriority = asked;
        pendingChosen = chosen;
        List<SpellAbility> out = new ArrayList<>();
        out.add(actions.get(chosen - 1));
        return out;
    }

    @Override
    public boolean playChosenSpellAbility(SpellAbility sa) {
        ForgeGameSession.Asked asked = pendingPriority;
        int chosen = pendingChosen;
        pendingPriority = null;
        if (asked == null || sa.isLandAbility()) {
            if (asked != null) {
                session.record(asked, List.of(chosen), "chooseSpellAbilityToPlay", false, null);
                clearPriorityRefusals();
            }
            return super.playChosenSpellAbility(sa); // a land, or Forge AI's choice after a fallback
        }
        session.beginAction();
        try {
            if (PlaySpellAbility.playSpellAbility(this, player, sa)) {
                session.record(asked, List.of(chosen), "chooseSpellAbilityToPlay", false, null);
                clearPriorityRefusals();
                if (sa.isManaAbility()) {
                    session.manaAbilityActivated(player, sa);
                }
                return true;
            }
            // Forge refused the action (a cost or target the candidate check did not foresee): ask again (Q15)
            String reason = "could not be played: " + sa.getHostCard().getName();
            session.stats().note("chooseSpellAbilityToPlay refused by Forge: " + reason);
            if (priorityRejections.size() + 1 >= MAX_REJECTIONS) {
                session.record(asked, List.of(), "chooseSpellAbilityToPlay", true, FallbackReason.FALLBACK_REASON_ILLEGAL_ACTION);
                clearPriorityRefusals();
                forcePass = true;
            } else {
                session.recordRejected(asked, "chooseSpellAbilityToPlay", reason, DecisionStats.RejectionKind.MISSING_CONSTRAINT);
                priorityRejections.add(Rejection.newBuilder().setDecisionId(asked.request().getDecisionId())
                    .addChosen(chosen).setReason(reason).build());
            }
            return false;
        } finally {
            session.endAction();
        }
    }

    // ---- asking agent-core ----

    /** Asks agent-core; when it gives no usable answer, records the unintended fallback and returns null. */
    List<Integer> decide(DecisionType type, String method, List<Candidate> candidates, SelectionConstraints c,
                         DecisionContext ctx) {
        return decide(type, method, candidates, c, ctx, null);
    }

    /** {@code bundle}: the request is a step of a step bundle (Q18). */
    List<Integer> decide(DecisionType type, String method, List<Candidate> candidates, SelectionConstraints c,
                         DecisionContext ctx, ForgeGameSession.Bundle bundle) {
        ForgeGameSession.Asked asked = session.ask(decider, player, type, candidates, c, ctx, List.of(), bundle);
        if (!asked.ok()) {
            session.record(asked, List.of(), method, true, asked.failure());
            return null;
        }
        session.record(asked, asked.response().getChosenList(), method, false, null);
        return asked.response().getChosenList();
    }

    private static SelectionConstraints between(int min, int max) {
        return SelectionConstraints.newBuilder().setMin(min).setMax(max).build();
    }

    private static Candidate option(int index, String label) {
        return Candidate.newBuilder().setOption(OptionCandidate.newBuilder().setIndex(index).setLabel(label)).build();
    }

    /** What a decision made while casting or paying is about (601.2 order: modes, X and costs, targets, payment). */
    DecisionContext.Builder context(SpellAbility sa) {
        DecisionContext.Builder b = DecisionContext.newBuilder();
        if (sa == null) {
            return b;
        }
        b.setSourceId(session.ids().idOf(sa.getHostCard()));
        if (sa.getHostCard().getName().isEmpty()) {
            return b; // Forge's placeholder ability for a choice no card makes (e.g. combat): no key
        }
        SpellAbility root = sa.getRootAbility(); // a resolving sub-ability is named by the spell or ability it belongs to
        if (root.isSpell()) {
            b.setSpell(session.keys().spellKey(root));
        } else {
            AbilityKey k = session.keys().abilityKey(root);
            if (k != null) {
                b.setAbility(k);
            }
        }
        b.addAllTargets(session.targets(sa));
        if (sa.getXManaCostPaid() != null) {
            b.setX(sa.getXManaCostPaid());
        }
        for (Mana m : sa.getPayingMana()) {
            b.addPaid(ManaUnit.newBuilder().setColor(ObservationBuilder.colorLetter(m.getColor())).setAmount(1));
        }
        return b;
    }

    // ---- costs (2026-11-06: costs are paid from the pool; the cards a cost uses are chosen by agent-core) ----

    /** Cards for a non-mana cost, asked by {@link AgentCostDecision}; null when agent-core gave no usable answer. */
    List<Card> chooseCostCards(String costPart, CardCollectionView options, int amount, SpellAbility sa) {
        List<Card> cards = toList(options);
        List<Candidate> candidates = new ArrayList<>();
        for (Card c : cards) {
            candidates.add(Candidate.newBuilder().setCardId(session.ids().idOf(c)).build());
        }
        List<Integer> chosen = decide(DecisionType.DECISION_TYPE_CHOOSE_CARDS, "cost:" + costPart, candidates,
            between(amount, amount), context(sa).setEnginePrompt(costPart).build());
        if (chosen == null) {
            return null;
        }
        List<Card> out = new ArrayList<>();
        chosen.forEach(i -> out.add(cards.get(i)));
        return out;
    }

    void costDelegated(String costPart) {
        session.delegated(player, "cost:" + costPart, DecisionType.DECISION_TYPE_CHOOSE_CARDS, null);
    }

    @Override
    public CostDecisionMakerBase getCostDecisionMaker(Player p, SpellAbility ability, boolean effect, String prompt) {
        return new AgentCostDecision(this, p, ability, effect);
    }

    // ---- the order cost reductions apply in (601.2f; 2026-11-10 decision) ----

    /** Set while Forge adjusts the mana cost of a real payment (PlaySpellAbility.payManaCost, before the payment). */
    private boolean realCostAdjust;
    /** The order the reductions of the current real payment apply in; null when no order was planned. */
    private List<StaticAbility> costOrderPlan;
    /** While probing the final cost of one apply order: the order, and the reductions the first call listed. */
    private List<StaticAbility> probeOrder;
    private List<StaticAbility> probeSeen;

    /**
     * Cost increases apply first; the player orders the reductions (601.2f). Calls outside a real payment (castability
     * and look-ahead checks, Forge's test payments, Forge AI's estimates) only compute a cost: answered with Forge AI's
     * pick, neither a decision nor a delegation, counted. In a real payment the order was planned beforehand
     * ({@link #planCostOrder}).
     */
    @Override
    public StaticAbility chooseSingleStaticAbility(List<StaticAbility> possible) {
        if (probeOrder != null) {
            if (probeSeen == null) {
                probeSeen = new ArrayList<>(possible);
            }
            for (StaticAbility s : probeOrder) {
                if (possible.contains(s)) {
                    return s;
                }
            }
            return possible.get(0);
        }
        if (!realCostAdjust || checkingCosts) {
            session.stats().costOrderComputations++;
            return possible.get(0); // Forge AI's own pick (PlayerControllerAi: the first)
        }
        session.stats().costOrderRealCalls++;
        if (possible.size() == 1) {
            return possible.get(0);
        }
        if (costOrderPlan != null) {
            for (StaticAbility s : costOrderPlan) {
                if (possible.contains(s)) {
                    return s;
                }
            }
        }
        // not foreseen by the plan (should not happen): Forge AI orders, recorded as an intended delegation
        session.delegated(player, "chooseSingleStaticAbility(unplanned)", DecisionType.DECISION_TYPE_CHOOSE_OPTION, null);
        return possible.get(0); // Forge AI's pick
    }

    /**
     * Before a real payment: the final mana cost of every order the reductions can apply in (Forge's own cost
     * adjustment in test mode, the order scripted here). One reduction, or all orders with the same final cost: no
     * choice. Different final costs: CHOOSE_OPTION over the distinct final costs (orders with the same result are one
     * candidate); Forge is then answered with an order that gives the chosen cost.
     */
    private void planCostOrder(ManaCost realCost, CostPartMana mc, SpellAbility ability, boolean effect) {
        costOrderPlan = null;
        if (effect) {
            return; // Forge applies no reductions to a cost paid while resolving
        }
        List<StaticAbility> all = probe(realCost, mc, ability, List.of());
        if (all == null || all.size() < 2) {
            return;
        }
        session.stats().costOrderMulti++;
        if (all.size() > 6) {
            costOrderPlan = null; // 720+ orders: not planned (none in the decks); Forge AI orders, recorded
            return;
        }
        Map<String, List<StaticAbility>> byCost = new java.util.LinkedHashMap<>();
        for (List<StaticAbility> order : permutations(all)) {
            String cost = finalCost(realCost, mc, ability, order);
            byCost.putIfAbsent(cost, order);
        }
        List<String> costs = new ArrayList<>(byCost.keySet());
        if (costs.size() == 1) {
            costOrderPlan = byCost.get(costs.get(0));
            return;
        }
        session.stats().costOrderDiffered++;
        session.stats().note("cost change order changes the cost: " + ability.getHostCard().getName() + " " + costs);
        List<Candidate> candidates = new ArrayList<>();
        for (int i = 0; i < costs.size(); i++) {
            candidates.add(option(i, costs.get(i)));
        }
        List<Integer> chosen = decide(DecisionType.DECISION_TYPE_CHOOSE_OPTION, "chooseSingleStaticAbility", candidates, ONE,
            context(ability).setEnginePrompt("cost change order: final cost").build());
        costOrderPlan = chosen == null ? null : byCost.get(costs.get(chosen.get(0)));
    }

    /** Runs Forge's cost adjustment in test mode with the reductions applied in {@code order}; the reductions seen. */
    private List<StaticAbility> probe(ManaCost realCost, CostPartMana mc, SpellAbility ability, List<StaticAbility> order) {
        probeOrder = order;
        probeSeen = null;
        boolean wasChecking = checkingCosts;
        checkingCosts = true;
        try {
            forge.game.cost.CostAdjustment.adjust(toPayLike(realCost, mc, ability), ability, player, new CardCollection(),
                true, false);
            return probeSeen;
        } finally {
            probeOrder = null;
            checkingCosts = wasChecking;
        }
    }

    private String finalCost(ManaCost realCost, CostPartMana mc, SpellAbility ability, List<StaticAbility> order) {
        probeOrder = order;
        probeSeen = null;
        boolean wasChecking = checkingCosts;
        checkingCosts = true;
        try {
            ManaCostBeingPaid m = toPayLike(realCost, mc, ability);
            forge.game.cost.CostAdjustment.adjust(m, ability, player, new CardCollection(), true, false);
            return m.toString();
        } finally {
            probeOrder = null;
            checkingCosts = wasChecking;
        }
    }

    /** The cost PlaySpellAbility.payManaCost adjusts: the real cost with the announced or computed X. */
    private static ManaCostBeingPaid toPayLike(ManaCost realCost, CostPartMana mc, SpellAbility ability) {
        ManaCostBeingPaid toPay = new ManaCostBeingPaid(realCost);
        String xInCard = ability.getParamOrDefault("XAlternative", ability.getSVar("X"));
        if (mc != null && mc.getAmountOfX() > 0 && !"Count$xPaid".equals(xInCard)) {
            toPay.setXManaCostPaid(AbilityUtils.calculateAmount(ability.getHostCard(), xInCard, ability), ability.getXColor());
        } else if (ability.getXManaCostPaid() != null) {
            toPay.setXManaCostPaid(ability.getXManaCostPaid(), ability.getXColor());
        }
        return toPay;
    }

    private static List<List<StaticAbility>> permutations(List<StaticAbility> items) {
        List<List<StaticAbility>> out = new ArrayList<>();
        if (items.size() <= 1) {
            out.add(new ArrayList<>(items));
            return out;
        }
        for (int i = 0; i < items.size(); i++) {
            List<StaticAbility> rest = new ArrayList<>(items);
            StaticAbility first = rest.remove(i);
            for (List<StaticAbility> p : permutations(rest)) {
                p.add(0, first);
                out.add(p);
            }
        }
        return out;
    }

    @Override
    public boolean payManaCost(ManaCost toPay, CostPartMana costPartMana, SpellAbility sa, String prompt,
                               ManaConversionMatrix matrix, boolean effect) {
        // X, cost changes, delve: as for a human player; the mana itself goes through applyManaToCost
        if (checkingCosts) {
            return PlaySpellAbility.payManaCost(this, toPay, costPartMana, sa, player, prompt, matrix, effect);
        }
        planCostOrder(toPay, costPartMana, sa, effect);
        realCostAdjust = true; // until applyManaToCost starts: Forge adjusts the cost of this payment in between
        try {
            return PlaySpellAbility.payManaCost(this, toPay, costPartMana, sa, player, prompt, matrix, effect);
        } finally {
            realCostAdjust = false;
            costOrderPlan = null;
        }
    }

    /**
     * Pays {@code toPay} one step at a time (Q6). While casting, only pool mana and Phyrexian life can pay; a step with
     * one possible outcome is made without asking. A cost paid while something resolves (605.3a: "unless its
     * controller pays", ward) may also activate mana abilities, and may stop: the cost is then not paid.
     */
    @Override
    public boolean applyManaToCost(ManaCostBeingPaid toPay, SpellAbility ability, String prompt,
                                   ManaConversionMatrix matrix, boolean effect) {
        realCostAdjust = false; // the cost is adjusted; what follows is the payment itself
        player.pushPaidForSA(ability);
        ability.setManaCostBeingPaid(toPay);
        int lifeToPay = 0;
        ForgeGameSession.Bundle bundle = new ForgeGameSession.Bundle();
        boolean completed = false;
        try {
            while (!toPay.isPaid()) {
                ManaPool pool = player.getManaPool();
                int budget = ManaPayments.lifeBudget(player, ability, effect) - lifeToPay;
                List<List<Mana>> groups = new ArrayList<>();
                for (List<Mana> g : ManaPayments.groups(ManaPayments.usable(player, toPay, ability)).values()) {
                    ManaCostBeingPaid after = new ManaCostBeingPaid(toPay);
                    if (after.payMana(g.get(0), pool)
                            && (effect || ManaPayments.payable(player, after, ability, List.of(g.get(0)), budget))) {
                        groups.add(g);
                    }
                }
                boolean life = false;
                if (toPay.containsPhyrexianMana() && budget >= 2) {
                    ManaCostBeingPaid after = new ManaCostBeingPaid(toPay);
                    life = after.payPhyrexian() && (effect || ManaPayments.payable(player, after, ability, List.of(), budget - 2));
                }
                List<SpellAbility> sources = effect ? manaAbilitiesNow() : List.of();
                if (groups.isEmpty() && !life && sources.isEmpty()) {
                    if (!effect) {
                        StringBuilder why = new StringBuilder();
                        for (Mana m : pool) {
                            why.append(" ").append(ObservationBuilder.colorLetter(m.getColor())).append(":")
                                .append(m.meetsManaRestrictions(ability) ? "r" : "R")
                                .append(ability.allowsPayingWithShard(m.getSourceCard(), m.getColor()) ? "s" : "S")
                                .append(toPay.isNeeded(m, pool) ? "n" : "N");
                        }
                        session.stats().note("pool cannot pay " + toPay + " for " + ability.getHostCard().getName() + " (" + ability.getClass().getSimpleName() + ")" + why);
                    }
                    // during resolution nothing left can pay: the cost is not paid (the bundle ends by its rule); while
                    // casting the feasibility checks rule this out, so a bundle ending here was cut short
                    completed = effect;
                    return false;
                }
                if (!effect && !life && groups.size() == 1) {
                    payWith(toPay, ability, groups.get(0).get(0)); // one possible outcome
                    continue;
                }
                List<Candidate> candidates = new ArrayList<>();
                for (List<Mana> g : groups) {
                    candidates.add(Candidate.newBuilder().setPoolMana(observations().poolMana(g.get(0)).toBuilder()
                        .setAmount(g.size())).build());
                }
                if (life) {
                    candidates.add(Candidate.newBuilder().setPayLife(true).build());
                }
                for (SpellAbility s : sources) {
                    ManaSourceCandidate.Builder ms = ManaSourceCandidate.newBuilder().addSourceIds(session.ids().idOf(s.getHostCard()));
                    AbilityKey k = session.keys().abilityKey(s);
                    if (k != null) {
                        ms.setAbility(k);
                    }
                    candidates.add(Candidate.newBuilder().setManaSource(ms).build());
                }
                if (effect) {
                    candidates.add(pass()); // stop: the cost is not paid
                }
                List<Integer> chosen = decide(DecisionType.DECISION_TYPE_MANA_PAYMENT, "applyManaToCost", candidates, ONE,
                    context(ability).setManaCost(toPay.toString()).setEnginePrompt(prompt == null ? "" : prompt).build(),
                    bundle);
                if (chosen == null) {
                    return ComputerUtilMana.payManaCost(toPay, ability, player, effect);
                }
                int i = chosen.get(0);
                if (i < groups.size()) {
                    payWith(toPay, ability, groups.get(i).get(0));
                } else if (life && i == groups.size()) {
                    toPay.payPhyrexian();
                    ability.setSpendPhyrexianMana(true);
                    lifeToPay += 2;
                } else if (i < groups.size() + (life ? 1 : 0) + sources.size()) {
                    SpellAbility source = sources.get(i - groups.size() - (life ? 1 : 0));
                    if (PlaySpellAbility.playSpellAbility(this, player, source)) {
                        session.manaAbilityActivated(player, source);
                    }
                } else {
                    completed = true;
                    return false; // stopped paying
                }
            }
            if (lifeToPay > 0) {
                player.payLife(lifeToPay, ability, effect);
            }
            completed = true;
            return true;
        } finally {
            session.endBundle(bundle, completed);
            ability.setManaCostBeingPaid(null);
            player.popPaidForSA();
        }
    }

    private void payWith(ManaCostBeingPaid toPay, SpellAbility ability, Mana mana) {
        if (player.getManaPool().tryPayCostWithMana(ability, toPay, mana, false)) {
            ability.getPayingMana().add(mana);
        }
    }

    /** Mana abilities the player can activate now (605.3a: while paying a cost during resolution). */
    private List<SpellAbility> manaAbilitiesNow() {
        List<SpellAbility> out = new ArrayList<>();
        checkingCosts = true;
        try {
            for (Card c : player.getCardsIn(ZoneType.Battlefield)) {
                for (SpellAbility sa : c.getManaAbilities()) {
                    sa.setActivatingPlayer(player);
                    if (sa.canPlay() && ManaPayments.payableNow(player, sa)) {
                        out.add(sa);
                    }
                }
            }
        } finally {
            checkingCosts = false;
        }
        return out;
    }

    private ObservationBuilder observations() {
        return session.observationBuilder();
    }

    @Override
    public boolean payCostToPreventEffect(Cost cost, SpellAbility sa, boolean alreadyPaid, FCollectionView<Player> allPayers) {
        return PlaySpellAbility.payCostDuringAbilityResolve(this, player, cost, sa, null);
    }

    @Override
    public boolean confirmPayment(CostPart costPart, String question, SpellAbility sa) {
        List<Integer> chosen = decide(DecisionType.DECISION_TYPE_CHOOSE_OPTION, "confirmPayment",
            List.of(option(0, "do not pay"), option(1, "pay")), ONE, context(sa).setEnginePrompt(question).build());
        return chosen == null ? forgeAi(() -> super.confirmPayment(costPart, question, sa)) : chosen.get(0) == 1;
    }

    @Override
    public CardCollectionView chooseCardsForCost(CardCollectionView optionList, SpellAbility sa, CostPartWithList cpl,
                                                 int amount, boolean isOptional, String prompt) {
        if (optionList.size() <= amount && !isOptional) {
            return optionList;
        }
        List<Card> chosen = chooseCostCards(cpl.getClass().getSimpleName(), optionList, amount, sa);
        return chosen == null ? forgeAi(() -> super.chooseCardsForCost(optionList, sa, cpl, amount, isOptional, prompt)) : new CardCollection(chosen);
    }

    @Override
    public CardCollectionView chooseCardsToDelve(int genericAmount, CardCollection grave) {
        int max = Math.min(genericAmount, grave.size());
        if (checkingCosts || max == 0) {
            return new CardCollection(grave.subList(0, max)); // castability checks assume the largest reduction
        }
        int min = Math.max(0, Math.min(max, genericAmount - player.getManaPool().totalMana()));
        List<Card> cards = toList(grave);
        List<Candidate> candidates = new ArrayList<>();
        cards.forEach(c -> candidates.add(Candidate.newBuilder().setCardId(session.ids().idOf(c)).build()));
        List<Integer> chosen = decide(DecisionType.DECISION_TYPE_CHOOSE_CARDS, "chooseCardsToDelve", candidates,
            between(min, max), context(player.getPaidForSA()).setEnginePrompt("delve").build());
        if (chosen == null) {
            return forgeAi(() -> super.chooseCardsToDelve(genericAmount, grave));
        }
        CardCollection out = new CardCollection();
        chosen.forEach(i -> out.add(cards.get(i)));
        return out;
    }

    // ---- choices while casting: X, modes, optional and alternative costs, mana colors ----

    /**
     * X and the other values announced while casting (601.2b: March of Otherworldly Light's number of white cards to
     * exile, then X). Only values for which the whole cost can still be paid are offered: with the values announced so
     * far, this value, and some value of each announcement still to come (2026-11-10: no refusals). The resources do not
     * change while the spell is cast, so the check is the castability check's own (non-mana costs payable, the pool
     * pays the adjusted mana cost), whichever order Forge asks in. A number asked while resolving is offered as is.
     */
    @Override
    public Integer announceRequirements(SpellAbility ability, int min, int max, String announce) {
        if (!castingAnnouncement(ability, announce)) {
            return announceNumber(ability, min, max, announce, null);
        }
        List<String> later = laterAnnouncements(ability, announce);
        List<Integer> values = new ArrayList<>();
        checkingCosts = true;
        try {
            for (int v = min; v <= cap(min, max); v++) {
                if (feasible(ability, announce, v, later)) {
                    values.add(v);
                }
            }
        } finally {
            checkingCosts = false;
        }
        if (values.isEmpty()) {
            return null; // cannot be cast now (the castability check rules this out)
        }
        return announceNumber(ability, min, max, announce, values);
    }

    private Integer announceNumber(SpellAbility ability, int min, int max, String announce, List<Integer> allowed) {
        List<Integer> values = allowed;
        if (values == null) {
            values = new ArrayList<>();
            for (int v = min; v <= cap(min, max); v++) {
                values.add(v);
            }
        }
        if (values.size() == 1) {
            return values.get(0);
        }
        List<Candidate> candidates = new ArrayList<>();
        for (int i = 0; i < values.size(); i++) {
            candidates.add(Candidate.newBuilder().setOption(OptionCandidate.newBuilder().setIndex(i).setX(values.get(i))
                .setLabel(announce)).build());
        }
        List<Integer> chosen = decide(DecisionType.DECISION_TYPE_CHOOSE_OPTION, "announceRequirements", candidates, ONE,
            context(ability).setEnginePrompt(announce).build());
        final List<Integer> offered = values;
        return chosen == null ? forgeAi(() -> super.announceRequirements(ability, min, max, announce))
            : offered.get(chosen.get(0));
    }

    /** Upper end of an announced value worth offering: the pool and the hand bound what a cost can use. */
    private int cap(int min, int max) {
        long c = (long) min + player.getManaPool().totalMana() + player.getCardsIn(ZoneType.Hand).size() + 20;
        return (int) Math.min(max, c);
    }

    /** X, or a name of the Announce list, of a spell or ability being cast or activated (not a number while resolving). */
    private static boolean castingAnnouncement(SpellAbility ability, String announce) {
        return "X".equals(announce) || announceList(ability).contains(announce);
    }

    private static List<String> announceList(SpellAbility ability) {
        List<String> out = new ArrayList<>();
        String a = ability.getParam("Announce");
        if (a != null) {
            for (String v : a.split(",")) {
                out.add(v.trim());
            }
        }
        return out;
    }

    /** What Forge announces after {@code announce} for this cast: later names of the Announce list, then X. */
    private static List<String> laterAnnouncements(SpellAbility ability, String announce) {
        List<String> list = announceList(ability);
        List<String> out = new ArrayList<>();
        int i = list.indexOf(announce);
        if (i >= 0) {
            out.addAll(list.subList(i + 1, list.size()));
        }
        boolean xInList = list.stream().anyMatch("X"::equalsIgnoreCase);
        Cost cost = ability.getPayCosts();
        if (!"X".equals(announce) && !xInList && cost != null && cost.hasXInAnyCostPart()
                && "Count$xPaid".equals(ability.getParamOrDefault("XAlternative", ability.getSVar("X")))) {
            out.add("X");
        }
        return out;
    }

    /** With {@code var} = {@code value}: can some values of the {@code later} announcements make the cost payable? */
    private boolean feasible(SpellAbility ability, String var, int value, List<String> later) {
        Card host = ability.getHostCard();
        Integer oldX = ability.getXManaCostPaid();
        String oldSa = ability.hasSVar(var) ? ability.getSVar(var) : null;
        String oldCard = host.hasSVar(var) ? host.getSVar(var) : null;
        try {
            if ("X".equals(var)) {
                ability.setXManaCostPaid(value);
            } else {
                ability.setSVar(var, Integer.toString(value));
                host.setSVar(var, Integer.toString(value));
            }
            if (later.isEmpty()) {
                return costPayable(ability);
            }
            String next = later.get(0);
            org.apache.commons.lang3.Range<Integer> r = AbilityUtils.getAnnouncementBounds(ability, next);
            for (int v = r.getMinimum(); v <= cap(r.getMinimum(), r.getMaximum()); v++) {
                if (feasible(ability, next, v, later.subList(1, later.size()))) {
                    return true;
                }
            }
            return false;
        } finally {
            if ("X".equals(var)) {
                ability.setXManaCostPaid(oldX);
            } else {
                if (oldSa == null) {
                    ability.removeSVar(var);
                } else {
                    ability.setSVar(var, oldSa);
                }
                if (oldCard == null) {
                    host.removeSVar(var);
                } else {
                    host.setSVar(var, oldCard);
                }
            }
        }
    }

    /** The whole cost with the values announced now: non-mana parts payable, the adjusted mana cost from the pool. */
    private boolean costPayable(SpellAbility ability) {
        Cost cost = ability.getPayCosts();
        if (cost == null) {
            return true;
        }
        Cost adjusted = forge.game.cost.CostAdjustment.adjust(cost, ability, false);
        if (!forge.game.cost.CostPayment.canPayAdditionalCosts(adjusted, ability, false)) {
            return false;
        }
        CostPartMana part = adjusted.getCostMana();
        if (part == null) {
            return true;
        }
        ManaCostBeingPaid m = new ManaCostBeingPaid(part.getManaCostFor(ability));
        if (m.getXcounter() > 0) {
            Integer x = ability.getXManaCostPaid();
            m.setXManaCostPaid(x == null ? 0 : x, ability.getXColor());
        }
        forge.game.cost.CostAdjustment.adjust(m, ability, player, null, true, false);
        return ManaPayments.payable(player, m, ability, List.of(), ManaPayments.lifeBudget(player, ability, false));
    }

    @Override
    public List<AbilitySub> chooseModeForAbility(SpellAbility sa, List<AbilitySub> possible, int min, int num,
                                                 boolean allowRepeat) {
        if (!allowRepeat && possible.size() == min) {
            return new ArrayList<>(possible); // one possible outcome (Kozilek's Command with two modes left)
        }
        if (allowRepeat || possible.size() < min) {
            return super.chooseModeForAbility(sa, possible, min, num, allowRepeat);
        }
        List<Candidate> candidates = new ArrayList<>();
        for (int i = 0; i < possible.size(); i++) {
            candidates.add(option(i, possible.get(i).getDescription()));
        }
        List<Integer> chosen = decide(DecisionType.DECISION_TYPE_CHOOSE_OPTION, "chooseModeForAbility", candidates,
            between(min, Math.min(num, possible.size())), context(sa).setEnginePrompt("modes").build());
        if (chosen == null) {
            return forgeAi(() -> super.chooseModeForAbility(sa, possible, min, num, allowRepeat));
        }
        List<AbilitySub> out = new ArrayList<>();
        chosen.forEach(i -> out.add(possible.get(i)));
        return out;
    }

    @Override
    public List<OptionalCostValue> chooseOptionalCosts(SpellAbility choosen, List<OptionalCostValue> optionalCostValues) {
        // each payable combination is one option (there are at most a few optional costs)
        List<List<OptionalCostValue>> combos = new ArrayList<>();
        int n = Math.min(optionalCostValues.size(), 4);
        for (int mask = 0; mask < (1 << n); mask++) {
            List<OptionalCostValue> combo = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                if ((mask & (1 << i)) != 0) {
                    combo.add(optionalCostValues.get(i));
                }
            }
            if (mask == 0 || payableWith(choosen, combo)) {
                combos.add(combo);
            }
        }
        if (combos.size() == 1) {
            return combos.get(0);
        }
        List<Candidate> candidates = new ArrayList<>();
        for (int i = 0; i < combos.size(); i++) {
            candidates.add(option(i, combos.get(i).isEmpty() ? "none" : combos.get(i).toString()));
        }
        List<Integer> chosen = decide(DecisionType.DECISION_TYPE_CHOOSE_OPTION, "chooseOptionalCosts", candidates, ONE,
            context(choosen).setEnginePrompt("optional costs").build());
        return chosen == null ? forgeAi(() -> super.chooseOptionalCosts(choosen, optionalCostValues)) : combos.get(chosen.get(0));
    }

    private boolean payableWith(SpellAbility sa, List<OptionalCostValue> costs) {
        checkingCosts = true;
        try {
            return ManaPayments.payableNow(player, GameActionUtil.addOptionalCosts(sa, costs));
        } finally {
            checkingCosts = false;
        }
    }

    @Override
    public SpellAbility getAbilityToPlay(Card hostCard, List<SpellAbility> abilities, ITriggerEvent triggerEvent) {
        if (abilities.size() <= 1) {
            return abilities.isEmpty() ? null : abilities.get(0);
        }
        List<SpellAbility> payable = new ArrayList<>();
        checkingCosts = true;
        try {
            for (SpellAbility sa : abilities) {
                sa.setActivatingPlayer(player);
                if (ManaPayments.payableNow(player, sa)) {
                    payable.add(sa);
                }
            }
        } finally {
            checkingCosts = false;
        }
        if (payable.size() <= 1) {
            return payable.isEmpty() ? abilities.get(0) : payable.get(0);
        }
        List<Candidate> candidates = new ArrayList<>();
        for (int i = 0; i < payable.size(); i++) {
            candidates.add(option(i, payable.get(i).getDescription()));
        }
        List<Integer> chosen = decide(DecisionType.DECISION_TYPE_CHOOSE_OPTION, "getAbilityToPlay", candidates, ONE,
            context(payable.get(0)).setEnginePrompt("additional cost").build());
        return chosen == null ? forgeAi(() -> super.getAbilityToPlay(hostCard, abilities, triggerEvent)) : payable.get(chosen.get(0));
    }

    @Override
    public byte chooseColor(String message, SpellAbility sa, ColorSet colors) {
        List<Byte> options = new ArrayList<>();
        for (byte c : MagicColor.WUBRG) {
            if (colors.hasAnyColor(c)) {
                options.add(c);
            }
        }
        if (options.size() <= 1) {
            return options.isEmpty() ? super.chooseColor(message, sa, colors) : options.get(0);
        }
        List<Candidate> candidates = new ArrayList<>();
        for (int i = 0; i < options.size(); i++) {
            candidates.add(option(i, ObservationBuilder.colorLetter(options.get(i))));
        }
        List<Integer> chosen = decide(DecisionType.DECISION_TYPE_CHOOSE_OPTION, "chooseColor", candidates, ONE,
            context(sa).setEnginePrompt(message == null ? "color" : message).build());
        return chosen == null ? forgeAi(() -> super.chooseColor(message, sa, colors)) : options.get(chosen.get(0));
    }

    @Override
    public Map<Byte, Integer> specifyManaCombo(SpellAbility sa, ColorSet colorSet, int manaAmount, boolean different) {
        List<Byte> colors = new ArrayList<>();
        for (byte c : MagicColor.WUBRG) {
            if (colorSet.hasAnyColor(c)) {
                colors.add(c);
            }
        }
        List<Map<Byte, Integer>> combos = new ArrayList<>();
        combos(colors, 0, manaAmount, different, new java.util.TreeMap<>(), combos);
        if (combos.size() <= 1 || combos.size() > 64) {
            return super.specifyManaCombo(sa, colorSet, manaAmount, different);
        }
        List<Candidate> candidates = new ArrayList<>();
        for (int i = 0; i < combos.size(); i++) {
            StringBuilder label = new StringBuilder();
            combos.get(i).forEach((c, k) -> label.append(ObservationBuilder.colorLetter(c).repeat(k)));
            candidates.add(option(i, label.toString()));
        }
        List<Integer> chosen = decide(DecisionType.DECISION_TYPE_CHOOSE_OPTION, "specifyManaCombo", candidates, ONE,
            context(sa).setEnginePrompt("mana combination").build());
        return chosen == null ? forgeAi(() -> super.specifyManaCombo(sa, colorSet, manaAmount, different)) : combos.get(chosen.get(0));
    }

    /** Every multiset of {@code left} mana over {@code colors} (each color at most once when {@code different}). */
    private static void combos(List<Byte> colors, int from, int left, boolean different, java.util.TreeMap<Byte, Integer> cur,
                               List<Map<Byte, Integer>> out) {
        if (left == 0) {
            out.add(new java.util.TreeMap<>(cur));
            return;
        }
        for (int i = from; i < colors.size(); i++) {
            byte c = colors.get(i);
            cur.merge(c, 1, Integer::sum);
            combos(colors, different ? i + 1 : i, left - 1, different, cur, out);
            cur.merge(c, -1, Integer::sum);
            if (cur.get(c) == 0) {
                cur.remove(c);
            }
        }
    }


    // ---- triggered abilities: order, optional triggers, their targets and costs ----

    /**
     * The order simultaneous triggers go on the stack (TRIGGER_ORDER, Q9 ordered selection): candidates are the IDs
     * issued when they triggered; the first one picked resolves first. Copies and other non-trigger entries keep
     * Forge's order.
     */
    @Override
    public List<SpellAbility> orderSimultaneousSa(List<SpellAbility> activePlayerSAs) {
        if (activePlayerSAs.size() < 2) {
            return activePlayerSAs;
        }
        List<Candidate> candidates = new ArrayList<>();
        for (SpellAbility sa : activePlayerSAs) {
            long id = session.triggerId(sa);
            if (!sa.isTrigger() || id == 0) {
                return super.orderSimultaneousSa(activePlayerSAs);
            }
            candidates.add(Candidate.newBuilder().setTriggeredAbilityId(id).build());
        }
        int n = activePlayerSAs.size();
        List<Integer> chosen = decide(DecisionType.DECISION_TYPE_TRIGGER_ORDER, "orderSimultaneousSa", candidates,
            SelectionConstraints.newBuilder().setMin(n).setMax(n).setOrdered(true).build(), null);
        if (chosen == null) {
            return forgeAi(() -> super.orderSimultaneousSa(activePlayerSAs));
        }
        List<SpellAbility> out = new ArrayList<>();
        TriggerOrderChoice.Builder t = TriggerOrderChoice.newBuilder().setSeat(session.seat(player));
        for (int i : chosen) {
            out.add(activePlayerSAs.get(i));
            t.addAbilityIds(candidates.get(i).getTriggeredAbilityId());
        }
        session.reportAction(b -> b.setTriggerOrder(t));
        return out;
    }

    /** Puts the seat's simultaneous triggers on the stack as a human player would: ordered, then each one set up. */
    @Override
    public void orderAndPlaySimultaneousSa(List<SpellAbility> activePlayerSAs) {
        List<SpellAbility> entries = List.copyOf(activePlayerSAs);
        session.beginStacking(entries);
        try {
            List<SpellAbility> ordered = orderSimultaneousSa(activePlayerSAs);
            for (int i = ordered.size() - 1; i >= 0; i--) {
                SpellAbility next = ordered.get(i);
                if (next.isTrigger() && !next.isCopied()) {
                    PlaySpellAbility.playSpellAbility(this, player, next);
                } else {
                    if (next.isCopied()) {
                        if (next.isSpell()) {
                            if (!next.getHostCard().isInZone(ZoneType.Stack)) {
                                next.setHostCard(player.getGame().getAction().moveToStack(next.getHostCard(), next));
                            } else {
                                player.getGame().getStackZone().add(next.getHostCard());
                            }
                        }
                        if (next.isMayChooseNewTargets()) {
                            next.setupNewTargets(player);
                        }
                    }
                    player.getGame().getStack().add(next);
                }
            }
        } finally {
            session.endStacking(entries);
        }
    }

    /** A static trigger (resolved at once, without the stack): its targets and costs are chosen like a cast's. */
    @Override
    public boolean playTrigger(Card host, WrappedAbility wrapperAbility, boolean isMandatory) {
        return PlaySpellAbility.playSpellAbilityNoStack(this, player, wrapperAbility, false);
    }

    @Override
    public void playSpellAbilityNoStack(SpellAbility effectSA, boolean canSetupTargets) {
        PlaySpellAbility.playSpellAbilityNoStack(this, player, effectSA, !canSetupTargets);
    }

    /** "You may" triggers (OptionalDecider): asked when they resolve. A trigger with a cost is declined by not paying. */
    @Override
    public boolean confirmTrigger(WrappedAbility wrapper) {
        SpellAbility sa = wrapper.getWrappedAbility();
        if (sa.hasParam("Cost") && !sa.getParam("Cost").equals("0")) {
            return true;
        }
        List<Integer> chosen = decide(DecisionType.DECISION_TYPE_CHOOSE_OPTION, "confirmTrigger",
            List.of(option(0, "no"), option(1, "yes")), ONE, context(wrapper).setEnginePrompt("optional trigger").build());
        if (chosen == null) {
            return forgeAi(() -> super.confirmTrigger(wrapper));
        }
        if (chosen.get(0) == 1 && overRepeatCap("trigger:" + sa.getHostCard().getId() + ":" + session.keys().abilityKey(sa))) {
            session.safetyCap("confirmTrigger: " + sa.getHostCard().getName());
            return false; // declined past the cap
        }
        return chosen.get(0) == 1;
    }

    @Override
    public boolean playSaFromPlayEffect(SpellAbility tgtSA) {
        session.beginAction();
        try {
            return PlaySpellAbility.playSpellAbility(this, player, tgtSA);
        } finally {
            session.endAction();
        }
    }

    // ---- choices while something resolves (Q9 multi-selects; hidden cards are shown to the chooser first) ----

    /** Cards chosen from {@code options}, {@code min}..{@code max}; null when agent-core gave no usable answer. */
    private <T extends Card> List<T> chooseCards(String method, List<T> options, int min, int max, boolean ordered,
                                                 SpellAbility sa, String prompt) {
        session.showTo(decider, options);
        List<Candidate> candidates = new ArrayList<>();
        for (Card c : options) {
            candidates.add(Candidate.newBuilder().setCardId(session.ids().idOf(c)).build());
        }
        List<Integer> chosen = decide(DecisionType.DECISION_TYPE_CHOOSE_CARDS, method, candidates,
            SelectionConstraints.newBuilder().setMin(min).setMax(max).setOrdered(ordered).build(),
            context(sa).setEnginePrompt(prompt == null ? method : prompt).build());
        if (chosen == null) {
            return null;
        }
        List<T> out = new ArrayList<>();
        chosen.forEach(i -> out.add(options.get(i)));
        return out;
    }

    /** Players and cards chosen from {@code options}; candidates are object references. */
    private <T extends GameEntity> List<T> chooseEntities(String method, List<T> options, int min, int max,
                                                          SpellAbility sa, String prompt) {
        List<Card> cards = new ArrayList<>();
        options.forEach(o -> {
            if (o instanceof Card c) {
                cards.add(c);
            }
        });
        session.showTo(decider, cards);
        List<Candidate> candidates = new ArrayList<>();
        for (T o : options) {
            candidates.add(Candidate.newBuilder().setTarget(session.ref(o)).build());
        }
        List<Integer> chosen = decide(DecisionType.DECISION_TYPE_CHOOSE_CARDS, method, candidates, between(min, max),
            context(sa).setEnginePrompt(prompt == null ? method : prompt).build());
        if (chosen == null) {
            return null;
        }
        List<T> out = new ArrayList<>();
        chosen.forEach(i -> out.add(options.get(i)));
        return out;
    }

    private boolean yesNo(String method, SpellAbility sa, String prompt) {
        List<Integer> chosen = decide(DecisionType.DECISION_TYPE_CHOOSE_OPTION, method,
            List.of(option(0, "no"), option(1, "yes")), ONE, context(sa).setEnginePrompt(prompt == null ? method : prompt).build());
        if (chosen == null) {
            throw new NoAnswer();
        }
        return chosen.get(0) == 1;
    }

    /** No usable answer from agent-core (already recorded as an unintended fallback): Forge AI answers. */
    private static final class NoAnswer extends RuntimeException {
        NoAnswer() {
            super(null, null, false, false);
        }
    }

    @Override
    public CardCollectionView chooseCardsForEffect(CardCollectionView sourceList, SpellAbility sa, String title, int min,
                                                   int max, boolean isOptional, Map<String, Object> params) {
        List<Card> options = toList(sourceList);
        int lo = isOptional ? 0 : Math.min(min, options.size());
        int hi = Math.min(max, options.size());
        if (hi == 0 || (lo == hi && hi == options.size())) {
            return new CardCollection(options.subList(0, hi));
        }
        List<Card> chosen = chooseCards("chooseCardsForEffect", options, lo, hi, false, sa, title);
        return chosen == null ? forgeAi(() -> super.chooseCardsForEffect(sourceList, sa, title, min, max, isOptional, params))
            : new CardCollection(chosen);
    }

    @Override
    public <T extends GameEntity> T chooseSingleEntityForEffect(FCollectionView<T> optionList, DelayedReveal delayedReveal,
                                                               SpellAbility sa, String title, boolean isOptional,
                                                               Player relatedPlayer, Map<String, Object> params) {
        List<T> options = new ArrayList<>();
        optionList.forEach(options::add);
        if (options.isEmpty()) {
            return null;
        }
        if (options.size() == 1 && !isOptional) {
            return options.get(0);
        }
        List<T> chosen = chooseEntities("chooseSingleEntityForEffect", options, isOptional ? 0 : 1, 1, sa, title);
        if (chosen == null) {
            return forgeAi(() -> super.chooseSingleEntityForEffect(optionList, delayedReveal, sa, title, isOptional, relatedPlayer, params));
        }
        return chosen.isEmpty() ? null : chosen.get(0);
    }

    @Override
    public <T extends GameEntity> List<T> chooseEntitiesForEffect(FCollectionView<T> optionList, int min, int max,
                                                                 DelayedReveal delayedReveal, SpellAbility sa, String title,
                                                                 Player relatedPlayer, Map<String, Object> params) {
        List<T> options = new ArrayList<>();
        optionList.forEach(options::add);
        int lo = Math.min(min, options.size());
        int hi = Math.min(max, options.size());
        if (hi == 0 || (lo == hi && hi == options.size())) {
            return new ArrayList<>(options.subList(0, hi));
        }
        List<T> chosen = chooseEntities("chooseEntitiesForEffect", options, lo, hi, sa, title);
        return chosen == null
            ? forgeAi(() -> super.chooseEntitiesForEffect(optionList, min, max, delayedReveal, sa, title, relatedPlayer, params))
            : chosen;
    }

    @Override
    public Card chooseSingleCardForZoneChange(ZoneType destination, List<ZoneType> origin, SpellAbility sa,
                                              CardCollection fetchList, DelayedReveal delayedReveal, String selectPrompt,
                                              boolean isOptional, Player decider) {
        List<Card> options = toList(fetchList);
        if (options.isEmpty()) {
            return null;
        }
        if (options.size() == 1 && !isOptional) {
            return options.get(0);
        }
        List<Card> chosen = chooseCards("chooseSingleCardForZoneChange", options, isOptional ? 0 : 1, 1, false, sa, selectPrompt);
        if (chosen == null) {
            return forgeAi(() -> super.chooseSingleCardForZoneChange(destination, origin, sa, fetchList, delayedReveal,
                selectPrompt, isOptional, decider));
        }
        return chosen.isEmpty() ? null : chosen.get(0);
    }

    @Override
    public List<Card> chooseCardsForZoneChange(ZoneType destination, List<ZoneType> origin, SpellAbility sa,
                                               CardCollection fetchList, int min, int max, DelayedReveal delayedReveal,
                                               String selectPrompt, Player decider) {
        List<Card> options = toList(fetchList);
        int lo = Math.min(min, options.size());
        int hi = Math.min(max, options.size());
        if (hi == 0 || (lo == hi && hi == options.size())) {
            return new ArrayList<>(options.subList(0, hi));
        }
        List<Card> chosen = chooseCards("chooseCardsForZoneChange", options, lo, hi, false, sa, selectPrompt);
        return chosen == null ? forgeAi(() -> super.chooseCardsForZoneChange(destination, origin, sa, fetchList, min, max,
            delayedReveal, selectPrompt, decider)) : chosen;
    }

    @Override
    public CardCollectionView choosePermanentsToSacrifice(SpellAbility sa, int min, int max, CardCollectionView validTargets,
                                                          String message) {
        return chooseGroup("choosePermanentsToSacrifice", validTargets, min, max, sa, message,
            () -> super.choosePermanentsToSacrifice(sa, min, max, validTargets, message));
    }

    @Override
    public CardCollectionView choosePermanentsToDestroy(SpellAbility sa, int min, int max, CardCollectionView validTargets,
                                                        String message) {
        return chooseGroup("choosePermanentsToDestroy", validTargets, min, max, sa, message,
            () -> super.choosePermanentsToDestroy(sa, min, max, validTargets, message));
    }

    private CardCollectionView chooseGroup(String method, CardCollectionView valid, int min, int max, SpellAbility sa,
                                           String prompt, java.util.function.Supplier<CardCollectionView> forgeAi) {
        List<Card> options = toList(valid);
        int lo = Math.min(min, options.size());
        int hi = Math.min(max, options.size());
        if (hi == 0 || (lo == hi && hi == options.size())) {
            return new CardCollection(options.subList(0, hi));
        }
        List<Card> chosen = chooseCards(method, options, lo, hi, false, sa, prompt);
        return chosen == null ? forgeAi(forgeAi) : new CardCollection(chosen);
    }

    @Override
    public CardCollection chooseCardsToDiscardFrom(Player playerDiscard, SpellAbility sa, CardCollection validCards, int min,
                                                   int max, CardCollectionView visibleToChooser) {
        List<Card> options = toList(validCards);
        int lo = Math.min(min, options.size());
        int hi = Math.min(max, options.size());
        if (hi == 0 || (lo == hi && hi == options.size())) {
            return new CardCollection(options.subList(0, hi));
        }
        List<Card> chosen = chooseCards("chooseCardsToDiscardFrom", options, lo, hi, false, sa, "discard");
        return chosen == null
            ? forgeAi(() -> super.chooseCardsToDiscardFrom(playerDiscard, sa, validCards, min, max, visibleToChooser))
            : new CardCollection(chosen);
    }

    @Override
    public CardCollectionView chooseCardsToDiscardToMaximumHandSize(int numDiscard) {
        List<Card> hand = toList(player.getCardsIn(ZoneType.Hand));
        if (numDiscard >= hand.size()) {
            return new CardCollection(hand);
        }
        List<Card> chosen = chooseCards("chooseCardsToDiscardToMaximumHandSize", hand, numDiscard, numDiscard, false, null, "cleanup discard");
        return chosen == null ? forgeAi(() -> super.chooseCardsToDiscardToMaximumHandSize(numDiscard)) : new CardCollection(chosen);
    }

    @Override
    public boolean confirmAction(SpellAbility sa, PlayerActionConfirmMode mode, String message, List<String> options,
                                 Card cardToShow, Map<String, Object> params) {
        try {
            return yesNo("confirmAction", sa, message);
        } catch (NoAnswer e) {
            return forgeAi(() -> super.confirmAction(sa, mode, message, options, cardToShow, params));
        }
    }

    @Override
    public boolean confirmReplacementEffect(ReplacementEffect replacementEffect, SpellAbility effectSA, GameEntity affected,
                                            String question) {
        try {
            return yesNo("confirmReplacementEffect", effectSA, question);
        } catch (NoAnswer e) {
            return forgeAi(() -> super.confirmReplacementEffect(replacementEffect, effectSA, affected, question));
        }
    }

    @Override
    public boolean chooseBinary(SpellAbility sa, String question, BinaryChoiceType kindOfChoice, Boolean defaultChoice) {
        List<Integer> chosen = decide(DecisionType.DECISION_TYPE_CHOOSE_OPTION, "chooseBinary",
            List.of(option(0, kindOfChoice + ":first"), option(1, kindOfChoice + ":second")), ONE,
            context(sa).setEnginePrompt(question == null ? "binary" : question).build());
        return chosen == null ? forgeAi(() -> super.chooseBinary(sa, question, kindOfChoice, defaultChoice)) : chosen.get(0) == 0;
    }

    @Override
    public int chooseNumber(SpellAbility sa, String title, int min, int max) {
        if (min >= max) {
            return min;
        }
        int hi = Math.min(max, min + 30);
        List<Candidate> candidates = new ArrayList<>();
        for (int v = min; v <= hi; v++) {
            candidates.add(Candidate.newBuilder().setOption(OptionCandidate.newBuilder().setIndex(v - min).setX(v)
                .setLabel("number")).build());
        }
        List<Integer> chosen = decide(DecisionType.DECISION_TYPE_CHOOSE_OPTION, "chooseNumber", candidates, ONE,
            context(sa).setEnginePrompt(title == null ? "number" : title).build());
        return chosen == null ? forgeAi(() -> super.chooseNumber(sa, title, min, max)) : min + chosen.get(0);
    }

    @Override
    public CounterType chooseCounterType(List<CounterType> options, SpellAbility sa, String prompt, Map<String, Object> params) {
        if (options.size() <= 1) {
            return options.isEmpty() ? null : options.get(0);
        }
        List<Candidate> candidates = new ArrayList<>();
        for (int i = 0; i < options.size(); i++) {
            candidates.add(option(i, ObservationBuilder.counterName(options.get(i))));
        }
        List<Integer> chosen = decide(DecisionType.DECISION_TYPE_CHOOSE_OPTION, "chooseCounterType", candidates, ONE,
            context(sa).setEnginePrompt(prompt == null ? "counter" : prompt).build());
        return chosen == null ? forgeAi(() -> super.chooseCounterType(options, sa, prompt, params)) : options.get(chosen.get(0));
    }

    @Override
    public SpellAbility chooseSingleSpellForEffect(List<SpellAbility> spells, SpellAbility sa, String title,
                                                   Map<String, Object> params) {
        if (spells.size() <= 1) {
            return spells.isEmpty() ? null : spells.get(0);
        }
        List<Candidate> candidates = new ArrayList<>();
        for (int i = 0; i < spells.size(); i++) {
            candidates.add(option(i, spells.get(i).getDescription()));
        }
        List<Integer> chosen = decide(DecisionType.DECISION_TYPE_CHOOSE_OPTION, "chooseSingleSpellForEffect", candidates, ONE,
            context(sa).setEnginePrompt(title == null ? "spell" : title).build());
        return chosen == null ? forgeAi(() -> super.chooseSingleSpellForEffect(spells, sa, title, params)) : spells.get(chosen.get(0));
    }

    @Override
    public List<SpellAbility> chooseSpellAbilitiesForEffect(List<SpellAbility> spells, SpellAbility sa, String title, int num,
                                                           Map<String, Object> params) {
        if (spells.size() <= num) {
            return new ArrayList<>(spells);
        }
        List<Candidate> candidates = new ArrayList<>();
        for (int i = 0; i < spells.size(); i++) {
            candidates.add(option(i, spells.get(i).getDescription()));
        }
        List<Integer> chosen = decide(DecisionType.DECISION_TYPE_CHOOSE_OPTION, "chooseSpellAbilitiesForEffect", candidates,
            between(num, num), context(sa).setEnginePrompt(title == null ? "spells" : title).build());
        if (chosen == null) {
            return forgeAi(() -> super.chooseSpellAbilitiesForEffect(spells, sa, title, num, params));
        }
        List<SpellAbility> out = new ArrayList<>();
        chosen.forEach(i -> out.add(spells.get(i)));
        return out;
    }

    /** Which replacement effect applies first (616.1). */
    @Override
    public ReplacementEffect chooseSingleReplacementEffect(List<ReplacementEffect> possibleReplacers) {
        if (possibleReplacers.size() <= 1) {
            return possibleReplacers.isEmpty() ? null : possibleReplacers.get(0);
        }
        List<Candidate> candidates = new ArrayList<>();
        for (int i = 0; i < possibleReplacers.size(); i++) {
            candidates.add(option(i, possibleReplacers.get(i).getHostCard().getName() + ": " + possibleReplacers.get(i)));
        }
        List<Integer> chosen = decide(DecisionType.DECISION_TYPE_CHOOSE_OPTION, "chooseSingleReplacementEffect", candidates,
            ONE, context(null).setEnginePrompt("replacement effect").build());
        return chosen == null ? forgeAi(() -> super.chooseSingleReplacementEffect(possibleReplacers))
            : possibleReplacers.get(chosen.get(0));
    }

    /** One of several pool mana an effect may use (MANA_PAYMENT). */
    @Override
    public Mana chooseManaFromPool(List<Mana> manaChoices) {
        if (manaChoices.size() <= 1) {
            return manaChoices.isEmpty() ? null : manaChoices.get(0);
        }
        List<Candidate> candidates = new ArrayList<>();
        manaChoices.forEach(m -> candidates.add(Candidate.newBuilder().setPoolMana(observations().poolMana(m)).build()));
        List<Integer> chosen = decide(DecisionType.DECISION_TYPE_MANA_PAYMENT, "chooseManaFromPool", candidates, ONE,
            context(player.getPaidForSA()).setEnginePrompt("mana from pool").build());
        return chosen == null ? forgeAi(() -> super.chooseManaFromPool(manaChoices)) : manaChoices.get(chosen.get(0));
    }

    @Override
    public int chooseNumberForKeywordCost(SpellAbility sa, Cost cost, KeywordInterface keyword, String prompt, int max) {
        // replicate and the like: only as many times as the pool can pay on top of the spell
        int payable = 0;
        if (cost != null && cost.getTotalMana() != null && sa.getPayCosts() != null) {
            checkingCosts = true;
            try {
                ManaCostBeingPaid m = new ManaCostBeingPaid(sa.getPayCosts().getTotalMana());
                int life = ManaPayments.lifeBudget(player, sa, false);
                while (payable < max) {
                    m.addManaCost(cost.getTotalMana());
                    if (!ManaPayments.payable(player, m, sa, List.of(), life)) {
                        break;
                    }
                    payable++;
                }
            } finally {
                checkingCosts = false;
            }
        }
        int hi = cost == null || cost.getTotalMana() == null || cost.getTotalMana().isNoCost() ? max : payable;
        return numberFrom("chooseNumberForKeywordCost", 0, hi, sa, prompt,
            () -> super.chooseNumberForKeywordCost(sa, cost, keyword, prompt, max));
    }

    @Override
    public int chooseNumberForCostReduction(SpellAbility sa, int min, int max) {
        return numberFrom("chooseNumberForCostReduction", min, max, sa, "cost reduction",
            () -> super.chooseNumberForCostReduction(sa, min, max));
    }

    private int numberFrom(String method, int min, int max, SpellAbility sa, String prompt,
                           java.util.function.Supplier<Integer> forgeAi) {
        if (max <= min) {
            return min;
        }
        int hi = Math.min(max, min + 30);
        List<Candidate> candidates = new ArrayList<>();
        for (int v = min; v <= hi; v++) {
            candidates.add(Candidate.newBuilder().setOption(OptionCandidate.newBuilder().setIndex(v - min).setX(v)
                .setLabel("number")).build());
        }
        List<Integer> chosen = decide(DecisionType.DECISION_TYPE_CHOOSE_OPTION, method, candidates, ONE,
            context(sa).setEnginePrompt(prompt == null ? method : prompt).build());
        return chosen == null ? forgeAi(forgeAi) : min + chosen.get(0);
    }

    /** Opening-hand actions (Gemstone Caverns, Leylines): any of them, in the chosen order. */
    @Override
    public List<SpellAbility> chooseSaToActivateFromOpeningHand(List<SpellAbility> usableFromOpeningHand) {
        if (usableFromOpeningHand.isEmpty()) {
            return usableFromOpeningHand;
        }
        List<Candidate> candidates = new ArrayList<>();
        for (int i = 0; i < usableFromOpeningHand.size(); i++) {
            candidates.add(option(i, usableFromOpeningHand.get(i).getHostCard().getName()));
        }
        List<Integer> chosen = decide(DecisionType.DECISION_TYPE_CHOOSE_OPTION, "chooseSaToActivateFromOpeningHand",
            candidates, between(0, candidates.size()), context(null).setEnginePrompt("opening hand").build());
        if (chosen == null) {
            return forgeAi(() -> super.chooseSaToActivateFromOpeningHand(usableFromOpeningHand));
        }
        List<SpellAbility> out = new ArrayList<>();
        chosen.forEach(i -> out.add(usableFromOpeningHand.get(i)));
        return out;
    }

    // ---- library order: scry, surveil, cards put on top or bottom (lists are top to bottom) ----

    @Override
    public ImmutablePair<CardCollection, CardCollection> arrangeForScry(CardCollection topN) {
        return arrange("arrangeForScry", topN, "bottom", () -> super.arrangeForScry(topN));
    }

    @Override
    public ImmutablePair<CardCollection, CardCollection> arrangeForSurveil(CardCollection topN) {
        return arrange("arrangeForSurveil", topN, "graveyard", () -> super.arrangeForSurveil(topN));
    }

    /**
     * Two steps: which cards leave the top (to the bottom, or the graveyard), then the order of each pile that has more
     * than one card (ordered selections, top to bottom; the graveyard pile in the order the cards are put there).
     */
    private ImmutablePair<CardCollection, CardCollection> arrange(String method, CardCollection topN, String away,
                                                                 java.util.function.Supplier<ImmutablePair<CardCollection, CardCollection>> forgeAi) {
        List<Card> cards = toList(topN);
        List<Card> leave = chooseCards(method + ":" + away, cards, 0, cards.size(), false, null, away);
        if (leave == null) {
            return forgeAi(forgeAi);
        }
        List<Card> stay = new ArrayList<>(cards);
        stay.removeAll(leave);
        List<Card> top = order(method + ":top", stay);
        List<Card> bottom = order(method + ":" + away + " order", leave);
        if (top == null || bottom == null) {
            return forgeAi(forgeAi);
        }
        // E3: the deciding seat knows where these cards are (surveil's pile goes to the graveyard instead)
        session.arranged(decider, top, away.equals("bottom") ? bottom : List.of());
        return ImmutablePair.of(new CardCollection(top), new CardCollection(bottom));
    }

    /** The order of {@code cards}, asked when there is more than one; null when agent-core gave no usable answer. */
    private List<Card> order(String method, List<Card> cards) {
        if (cards.size() < 2) {
            return cards;
        }
        return chooseCards(method, cards, cards.size(), cards.size(), true, null, method);
    }

    @Override
    public boolean willPutCardOnTop(Card c) {
        session.showTo(decider, List.of(c));
        try {
            return yesNo("willPutCardOnTop", null, "put on top");
        } catch (NoAnswer e) {
            return forgeAi(() -> super.willPutCardOnTop(c));
        }
    }

    @Override
    public CardCollectionView orderMoveToZoneList(CardCollectionView cards, ZoneType destinationZone, SpellAbility source) {
        if (cards.size() < 2 || (destinationZone != ZoneType.Library && destinationZone != ZoneType.Graveyard)) {
            return forgeAi(() -> super.orderMoveToZoneList(cards, destinationZone, source));
        }
        List<Card> ordered = order("orderMoveToZoneList:" + destinationZone, toList(cards));
        if (ordered != null && destinationZone == ZoneType.Library) {
            session.facts().orderedBy(session.seat(decider), ordered);
        }
        return ordered == null ? forgeAi(() -> super.orderMoveToZoneList(cards, destinationZone, source)) : new CardCollection(ordered);
    }

    // ---- targets ----

    private boolean chooseTargetsOf(SpellAbility s) {
        TargetRestrictions tgt = s.getTargetRestrictions();
        s.resetTargets(); // Forge may ask again for the same ability (e.g. after a cost changed it)
        if (s.isDividedAsYouChoose()) {
            // dividing damage is an option choice the protocol has no candidates for yet: Forge AI targets it
            session.delegated(player, "chooseTargetsFor(divided)", DecisionType.DECISION_TYPE_TARGET, null);
            return getAi().doTrigger(s, true);
        }
        List<GameObject> options = new ArrayList<>(tgt.getAllCandidates(s));
        if (tgt.getZone().contains(ZoneType.Stack)) {
            // spells and abilities on the stack are targeted as SpellAbility objects, not as cards
            options.removeIf(o -> o instanceof Card c && c.getZone() != null && c.getZone().is(ZoneType.Stack));
            for (SpellAbilityStackInstance si : player.getGame().getStack()) {
                if (s.canTarget(si.getSpellAbility())) {
                    options.add(si.getSpellAbility());
                }
            }
        }
        options.removeIf(o -> o == s.getRootAbility() || o == s);
        int min = tgt.getMinTargets(s.getHostCard(), s);
        int max = Math.min(tgt.getMaxTargets(s.getHostCard(), s), options.size());
        if (options.isEmpty() || max == 0) {
            return min == 0;
        }
        List<Candidate> candidates = new ArrayList<>();
        for (GameObject o : options) {
            candidates.add(Candidate.newBuilder().setTarget(session.ref(o)).build());
        }
        SelectionConstraints c = SelectionConstraints.newBuilder().setMin(Math.min(min, max)).setMax(max).build();
        DecisionContext ctx = DecisionContext.newBuilder().setSourceId(session.ids().idOf(s.getHostCard())).build();
        ForgeGameSession.Asked asked = session.ask(decider, player, DecisionType.DECISION_TYPE_TARGET, candidates, c, ctx, List.of());
        if (!asked.ok()) {
            session.record(asked, List.of(), "chooseTargetsFor", true, asked.failure());
            return getAi().doTrigger(s, true);
        }
        session.record(asked, asked.response().getChosenList(), "chooseTargetsFor", false, null);
        for (int i : asked.response().getChosenList()) {
            s.getTargets().add(options.get(i));
        }
        return true;
    }

    @Override
    public boolean chooseTargetsFor(SpellAbility currentAbility) {
        boolean ok = true;
        for (SpellAbility s = currentAbility; s != null; s = s.getSubAbility()) {
            if (s.usesTargeting()) {
                ok &= chooseTargetsOf(s);
            }
        }
        return ok;
    }

    // ---- combat ----

    @Override
    public void declareAttackers(Player attacker, Combat combat) {
        session.syncZones();
        List<Card> creatures = new ArrayList<>();
        List<GameEntity> defenders = new ArrayList<>();
        List<Candidate> candidates = new ArrayList<>();
        for (Card c : attacker.getCreaturesInPlay()) {
            if (!CombatUtil.canAttack(c)) {
                continue;
            }
            for (GameEntity d : combat.getDefenders()) {
                if (CombatUtil.canAttack(c, d)) {
                    creatures.add(c);
                    defenders.add(d);
                    candidates.add(Candidate.newBuilder().setAttack(Attack.newBuilder()
                        .setAttackerId(session.ids().idOf(c)).setDefender(session.ref(d))).build());
                }
            }
        }
        if (candidates.isEmpty()) {
            return;
        }
        // Q15: the combination rules single pairs cannot show
        SelectionConstraints.Builder c = SelectionConstraints.newBuilder().setMin(0).setMax(candidates.size());
        Map<Card, AttackRequirement> requirements = combat.getAttackConstraints().getRequirements();
        for (Card a : new LinkedHashSet<>(creatures)) {
            long id = session.ids().idOf(a);
            AttackRequirement req = requirements.get(a);
            if (req != null && req.hasRequirement()) {
                c.addRequiredIds(id);
            }
            c.addPairLimits(PairLimit.newBuilder().setId(id).setMax(1));
        }
        List<Rejection> rejections = new ArrayList<>();
        while (true) {
            ForgeGameSession.Asked asked = session.ask(decider, player, DecisionType.DECISION_TYPE_DECLARE_ATTACKERS, candidates,
                c.build(), null, rejections);
            if (!asked.ok()) {
                session.record(asked, List.of(), "declareAttackers", true, asked.failure());
                break;
            }
            List<Integer> chosen = asked.response().getChosenList();
            String refused = null;
            Set<Card> used = new HashSet<>();
            for (int i : chosen) {
                if (!used.add(creatures.get(i))) {
                    refused = creatures.get(i) + " attacks twice";
                    break;
                }
                combat.addAttacker(creatures.get(i), defenders.get(i));
            }
            if (refused == null && !CombatUtil.validateAttackers(combat)) {
                refused = "attack requirements not met";
            }
            if (refused == null) {
                session.record(asked, chosen, "declareAttackers", false, null);
                return;
            }
            for (Card a : new ArrayList<>(combat.getAttackers())) {
                combat.removeFromCombat(a);
            }
            if (!rejected(asked, "declareAttackers", refused, rejections, kind(c.build(), candidates, chosen))) {
                break;
            }
        }
        super.declareAttackers(attacker, combat);
    }

    /**
     * Records a refused answer (Q15) and adds it to {@code rejections}; false when the refusals are used up, after
     * recording the unintended fallback.
     */
    private boolean rejected(ForgeGameSession.Asked asked, String method, String reason, List<Rejection> rejections,
                             DecisionStats.RejectionKind kind) {
        session.stats().note(method + " refused by Forge: "
            + reason.replaceAll(" \\(\\d+\\)", "").replaceAll("\\d+ creatures", "N creatures"));
        if (rejections.size() + 1 >= MAX_REJECTIONS) {
            session.record(asked, List.of(), method, true, FallbackReason.FALLBACK_REASON_ILLEGAL_ACTION);
            return false;
        }
        session.recordRejected(asked, method, reason, kind);
        rejections.add(Rejection.newBuilder().setDecisionId(asked.request().getDecisionId())
            .addAllChosen(asked.response().getChosenList()).setReason(reason).build());
        return true;
    }

    /**
     * Why a pair selection was refused (deck item 1): breaking a stated rule is agent-core's fault (an ignored
     * constraint); otherwise the request lacked the rule (a missing constraint).
     */
    private static DecisionStats.RejectionKind kind(SelectionConstraints c, List<Candidate> candidates, List<Integer> chosen) {
        Map<Long, Integer> uses = new java.util.HashMap<>();
        for (int i : chosen) {
            Candidate cand = candidates.get(i);
            List<Long> ids = cand.hasAttack() ? List.of(cand.getAttack().getAttackerId())
                : List.of(cand.getBlock().getBlockerId(), cand.getBlock().getAttackerId());
            ids.forEach(id -> uses.merge(id, 1, Integer::sum));
        }
        for (long id : c.getRequiredIdsList()) {
            if (!uses.containsKey(id)) {
                return DecisionStats.RejectionKind.IGNORED_CONSTRAINT;
            }
        }
        for (PairLimit l : c.getPairLimitsList()) {
            int n = uses.getOrDefault(l.getId(), 0);
            if ((l.getMax() > 0 && n > l.getMax()) || (n > 0 && n < l.getMinIfAny())) {
                return DecisionStats.RejectionKind.IGNORED_CONSTRAINT;
            }
        }
        return DecisionStats.RejectionKind.MISSING_CONSTRAINT;
    }

    @Override
    public void declareBlockers(Player defender, Combat combat) {
        session.syncZones();
        List<Card> blockers = new ArrayList<>();
        List<Card> attackers = new ArrayList<>();
        List<Candidate> candidates = new ArrayList<>();
        for (Card b : defender.getCreaturesInPlay()) {
            if (!CombatUtil.canBlock(b, combat)) {
                continue;
            }
            for (Card a : combat.getAttackers()) {
                if (CombatUtil.canBlock(a, b, combat)) {
                    blockers.add(b);
                    attackers.add(a);
                    candidates.add(Candidate.newBuilder().setBlock(Block.newBuilder()
                        .setBlockerId(session.ids().idOf(b)).setAttackerId(session.ids().idOf(a))).build());
                }
            }
        }
        if (candidates.isEmpty()) {
            return;
        }
        // Q15: blocks per blocker and blockers per attacker (menace and the like)
        SelectionConstraints.Builder c = SelectionConstraints.newBuilder().setMin(0).setMax(candidates.size());
        for (Card b : new LinkedHashSet<>(blockers)) {
            c.addPairLimits(PairLimit.newBuilder().setId(session.ids().idOf(b))
                .setMax(b.canBlockAny() ? 0 : 1 + b.canBlockAdditional()));
        }
        for (Card a : new LinkedHashSet<>(attackers)) {
            Pair<Integer, Integer> minMax = StaticAbilityCantAttackBlock.getMinMaxBlocker(a, defender);
            int min = minMax.getLeft() > 1 ? minMax.getLeft() : 0;
            int max = minMax.getRight() == Integer.MAX_VALUE ? 0 : minMax.getRight();
            if (min > 0 || max > 0) {
                c.addPairLimits(PairLimit.newBuilder().setId(session.ids().idOf(a)).setMinIfAny(min).setMax(max));
            }
        }
        List<Rejection> rejections = new ArrayList<>();
        while (true) {
            ForgeGameSession.Asked asked = session.ask(decider, player, DecisionType.DECISION_TYPE_DECLARE_BLOCKERS, candidates,
                c.build(), null, rejections);
            if (!asked.ok()) {
                session.record(asked, List.of(), "declareBlockers", true, asked.failure());
                break;
            }
            List<Integer> chosen = asked.response().getChosenList();
            for (int i : chosen) {
                combat.addBlocker(attackers.get(i), blockers.get(i));
            }
            String invalid = CombatUtil.validateBlocks(combat, defender);
            if (invalid == null) {
                session.record(asked, chosen, "declareBlockers", false, null);
                return;
            }
            for (int i : chosen) {
                combat.removeBlockAssignment(attackers.get(i), blockers.get(i));
            }
            if (!rejected(asked, "declareBlockers", invalid, rejections, kind(c.build(), candidates, chosen))) {
                break;
            }
        }
        super.declareBlockers(defender, combat);
    }

    // ---- combat damage division (deck item 3) ----

    /**
     * Divides a source's combat damage among several recipients (DAMAGE_ASSIGNMENT): one request per recipient in
     * entity ID order, 0 .. min(lethal, left); what is left goes to the defending player or planeswalker for a
     * trampler once every recipient has lethal damage, otherwise to the last recipient. A single recipient without
     * trample takes everything without asking.
     */
    @Override
    public Map<Card, Integer> assignCombatDamage(Card attacker, CardCollectionView blockers, CardCollectionView remaining,
                                                 int damageDealt, GameEntity defender, boolean overrideOrder) {
        boolean trample = attacker.hasKeyword(Keyword.TRAMPLE) && defender != null;
        List<Card> recipients = toList(blockers);
        recipients.sort(java.util.Comparator.comparingLong(c -> session.ids().idOf(c)));
        Map<Card, Integer> out = new java.util.LinkedHashMap<>();
        if (recipients.isEmpty()) {
            return out;
        }
        if (recipients.size() == 1 && !trample) {
            out.put(recipients.get(0), damageDealt);
            return out;
        }
        Map<Card, Integer> lethal = new java.util.LinkedHashMap<>();
        for (Card c : recipients) {
            int l = Math.max(0, c.getLethalDamage());
            lethal.put(c, attacker.hasKeyword(Keyword.DEATHTOUCH) ? Math.min(1, l) : l);
        }
        Map<Card, Integer> engine = forgeAi(() -> super.assignCombatDamage(attacker, blockers, remaining, damageDealt, defender, overrideOrder));
        DamageAssignment.Builder base = DamageAssignment.newBuilder().setTotal(damageDealt).setTrample(trample);
        lethal.forEach((c, l) -> base.addLethal(DamageShare.newBuilder().setTo(session.ref(c)).setAmount(l)));
        if (engine != null) {
            engine.forEach((c, n) -> base.addEngineDefault(DamageShare.newBuilder()
                .setTo(c == null ? session.ref(defender) : session.ref(c)).setAmount(n)));
        }
        int left = damageDealt;
        for (Card c : recipients) {
            int cap = Math.min(lethal.get(c), left);
            int amount = 0;
            if (cap > 0) {
                List<Candidate> candidates = new ArrayList<>();
                for (int v = 0; v <= cap; v++) {
                    candidates.add(Candidate.newBuilder().setOption(OptionCandidate.newBuilder().setIndex(v).setX(v)
                        .setLabel("damage")).build());
                }
                DecisionContext ctx = context(null).setSourceId(session.ids().idOf(attacker))
                    .setDamage(base.clone().setRecipient(session.ref(c)).setRemaining(left)).build();
                List<Integer> chosen = decide(DecisionType.DECISION_TYPE_DAMAGE_ASSIGNMENT, "assignCombatDamage", candidates, ONE, ctx);
                if (chosen == null) {
                    return engine;
                }
                amount = chosen.get(0);
            }
            out.merge(c, amount, Integer::sum);
            left -= amount;
        }
        if (left > 0) {
            boolean allLethal = recipients.stream().allMatch(c -> out.getOrDefault(c, 0) >= lethal.get(c));
            if (trample && allLethal) {
                out.put(null, left);
            } else {
                Card to = recipients.get(recipients.size() - 1);
                for (Card c : recipients) {
                    if (out.getOrDefault(c, 0) < lethal.get(c)) {
                        to = c; // 510.1c: lethal damage to each before more to a later one or the player
                        break;
                    }
                }
                out.merge(to, left, Integer::sum);
            }
        }
        return out;
    }

    // ---- naming (deck item 6) ----

    /** Card names from both players' 75 (Disruptor Flute and the like), by oracle_id. */
    @Override
    public String chooseCardName(SpellAbility sa, Predicate<ICardFace> cpp, String valid, String message) {
        Map<String, String> names = new java.util.TreeMap<>(); // oracle_id -> face name
        for (int seat = 0; seat < session.seatCount(); seat++) {
            Deck deck = session.registeredDeck(seat);
            List<PaperCard> cards = new ArrayList<>(deck.getMain().toFlatList());
            if (deck.has(DeckSection.Sideboard)) {
                cards.addAll(deck.get(DeckSection.Sideboard).toFlatList());
            }
            for (PaperCard pc : cards) {
                ICardFace face = pc.getRules().getMainPart();
                if (cpp == null || cpp.test(face)) {
                    names.putIfAbsent(session.keys().oracleIdByName(pc.getName()), face.getName());
                }
            }
        }
        return nameFrom("chooseCardName", names, sa, message, () -> super.chooseCardName(sa, cpp, valid, message));
    }

    @Override
    public String chooseCardName(SpellAbility sa, List<ICardFace> faces, String message) {
        Map<String, String> names = new java.util.TreeMap<>();
        faces.forEach(f -> names.putIfAbsent(session.keys().oracleIdByName(f.getName()), f.getName()));
        return nameFrom("chooseCardName(faces)", names, sa, message, () -> super.chooseCardName(sa, faces, message));
    }

    private String nameFrom(String method, Map<String, String> names, SpellAbility sa, String message,
                            java.util.function.Supplier<String> forgeAi) {
        if (names.size() <= 1) {
            return names.isEmpty() ? forgeAi(forgeAi) : names.values().iterator().next();
        }
        List<String> oracles = new ArrayList<>(names.keySet());
        List<Candidate> candidates = new ArrayList<>();
        oracles.forEach(o -> candidates.add(Candidate.newBuilder().setNamedOracleId(o).build()));
        List<Integer> chosen = decide(DecisionType.DECISION_TYPE_NAME, method, candidates, ONE,
            context(sa).setEnginePrompt(message == null ? "name a card" : message).build());
        return chosen == null ? forgeAi(forgeAi) : names.get(oracles.get(chosen.get(0)));
    }

    /** A type: for creature types, those of the creatures in the deciding player's 75 (Cavern of Souls). */
    @Override
    public String chooseSomeType(String kindOfType, SpellAbility sa, Collection<String> validTypes, boolean isOptional) {
        List<String> options = new ArrayList<>();
        if ("Creature".equals(kindOfType)) {
            Deck deck = session.registeredDeck(session.seat(player));
            List<PaperCard> cards = new ArrayList<>(deck.getMain().toFlatList());
            if (deck.has(DeckSection.Sideboard)) {
                cards.addAll(deck.get(DeckSection.Sideboard).toFlatList());
            }
            java.util.TreeSet<String> types = new java.util.TreeSet<>();
            for (PaperCard pc : cards) {
                if (pc.getRules().getType().isCreature()) {
                    for (String t : pc.getRules().getType().getCreatureTypes()) {
                        if (validTypes.contains(t)) {
                            types.add(t);
                        }
                    }
                }
            }
            options.addAll(types);
        } else if (validTypes.size() <= 40) {
            options.addAll(new java.util.TreeSet<>(validTypes));
        }
        if (options.isEmpty()) {
            return super.chooseSomeType(kindOfType, sa, validTypes, isOptional); // counted as unclassified
        }
        if (options.size() == 1 && !isOptional) {
            return options.get(0);
        }
        List<Candidate> candidates = new ArrayList<>();
        options.forEach(t -> candidates.add(Candidate.newBuilder().setNamedType(t).build()));
        List<Integer> chosen = decide(DecisionType.DECISION_TYPE_NAME, "chooseSomeType", candidates, ONE,
            context(sa).setEnginePrompt(kindOfType + " type").build());
        return chosen == null ? forgeAi(() -> super.chooseSomeType(kindOfType, sa, validTypes, isOptional)) : options.get(chosen.get(0));
    }

    // ---- between games ----

    /**
     * Sideboarding (2026-11-06: decided by agent-core, no fixed guide): one (in, out) swap per request until "done",
     * at most 15; each request lists the swaps the deck as changed so far allows.
     */
    @Override
    public List<PaperCard> sideboard(Deck deck, GameType gameType, String message) {
        if (!deck.has(DeckSection.Sideboard)) {
            return null;
        }
        List<PaperCard> main = new ArrayList<>(deck.get(DeckSection.Main).toFlatList());
        List<PaperCard> side = new ArrayList<>(deck.get(DeckSection.Sideboard).toFlatList());
        int swaps = 0;
        ForgeGameSession.Bundle bundle = new ForgeGameSession.Bundle();
        boolean completed = true; // done, the swap limit, or no swap left
        while (swaps < 15) {
            Map<String, PaperCard> ins = new java.util.TreeMap<>();
            Map<String, PaperCard> outs = new java.util.TreeMap<>();
            side.forEach(c -> ins.putIfAbsent(session.keys().oracleIdByName(c.getName()), c));
            main.forEach(c -> outs.putIfAbsent(session.keys().oracleIdByName(c.getName()), c));
            List<Candidate> candidates = new ArrayList<>();
            List<PaperCard[]> pairs = new ArrayList<>();
            candidates.add(pass()); // done
            for (Map.Entry<String, PaperCard> in : ins.entrySet()) {
                for (Map.Entry<String, PaperCard> out : outs.entrySet()) {
                    if (in.getKey().equals(out.getKey())) {
                        continue;
                    }
                    candidates.add(Candidate.newBuilder().setSideboardSwap(SideboardSwap.newBuilder()
                        .setInOracleId(in.getKey()).setOutOracleId(out.getKey())).build());
                    pairs.add(new PaperCard[] {in.getValue(), out.getValue()});
                }
            }
            if (pairs.isEmpty()) {
                break;
            }
            List<Integer> chosen = decide(DecisionType.DECISION_TYPE_SIDEBOARD, "sideboard", candidates, ONE, null, bundle);
            if (chosen == null || chosen.get(0) == 0) {
                completed = chosen != null;
                break;
            }
            PaperCard[] swap = pairs.get(chosen.get(0) - 1);
            side.remove(swap[0]);
            main.add(swap[0]);
            main.remove(swap[1]);
            side.add(swap[1]);
            swaps++;
        }
        session.endBundle(bundle, completed);
        return swaps == 0 ? null : main;
    }

    // ---- game start ----

    @Override
    public boolean mulliganKeepHand(Player firstPlayer, int cardsToReturn) {
        List<Candidate> candidates = List.of(
            Candidate.newBuilder().setKeep(true).build(), Candidate.newBuilder().setKeep(false).build());
        ForgeGameSession.Asked asked = session.ask(decider, player, DecisionType.DECISION_TYPE_MULLIGAN, candidates, ONE, null, List.of());
        boolean keep;
        if (asked.ok()) {
            keep = asked.response().getChosen(0) == 0;
            session.record(asked, List.of(asked.response().getChosen(0)), "mulliganKeepHand", false, null);
        } else {
            session.record(asked, List.of(), "mulliganKeepHand", true, asked.failure());
            keep = forgeAiMulliganKeepHand(firstPlayer, cardsToReturn);
        }
        reportMulligan(keep);
        return keep;
    }

    @Override
    public CardCollectionView tuckCardsViaMulligan(CardCollectionView hand, int cardsToReturn) {
        List<Card> cards = toList(hand);
        List<Candidate> candidates = new ArrayList<>();
        for (Card c : cards) {
            candidates.add(Candidate.newBuilder().setCardId(session.ids().idOf(c)).build());
        }
        SelectionConstraints c = SelectionConstraints.newBuilder().setMin(cardsToReturn).setMax(cardsToReturn).setOrdered(true).build();
        ForgeGameSession.Asked asked = session.ask(decider, player, DecisionType.DECISION_TYPE_BOTTOM_CARDS, candidates, c, null, List.of());
        CardCollection chosen = new CardCollection();
        if (asked.ok()) {
            for (int i : asked.response().getChosenList()) {
                chosen.add(cards.get(i));
            }
            session.record(asked, asked.response().getChosenList(), "tuckCardsViaMulligan", false, null);
        } else {
            session.record(asked, List.of(), "tuckCardsViaMulligan", true, asked.failure());
            chosen.addAll(forgeAiTuckCards(hand, cardsToReturn));
        }
        reportBottom(chosen);
        return chosen;
    }

    private static List<Card> toList(CardCollectionView view) {
        List<Card> out = new ArrayList<>();
        view.forEach(out::add);
        return out;
    }

    @Override
    public Player chooseStartingPlayer(boolean isFirstGame) {
        List<Candidate> candidates = List.of(
            Candidate.newBuilder().setPlay(true).build(), Candidate.newBuilder().setPlay(false).build());
        ForgeGameSession.Asked asked = session.ask(decider, player, DecisionType.DECISION_TYPE_PLAY_OR_DRAW, candidates, ONE, null, List.of());
        boolean play;
        if (asked.ok()) {
            play = asked.response().getChosen(0) == 0;
            session.record(asked, List.of(asked.response().getChosen(0)), "chooseStartingPlayer", false, null);
        } else {
            session.record(asked, List.of(), "chooseStartingPlayer", true, asked.failure());
            play = forgeAiStartingPlayer(isFirstGame) == player;
        }
        reportPlayOrDraw(play);
        if (play) {
            return player;
        }
        for (Player p : player.getGame().getPlayers()) {
            if (p != player) {
                return p;
            }
        }
        return player;
    }
}
