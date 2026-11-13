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

    private String poolText() {
        StringBuilder b = new StringBuilder();
        for (forge.game.mana.Mana m : player.getManaPool()) {
            b.append(ObservationBuilder.colorLetter(m.getColor()));
        }
        return b.toString();
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
        ForgeGameSession.Asked asked = session.resolveDelegation(
            session.ask(decider, player, DecisionType.DECISION_TYPE_PRIORITY, candidates, ONE, null, List.copyOf(priorityRejections)),
            () -> forgeAiPriority(actions, candidates), "chooseSpellAbilityToPlay");
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

    /**
     * Forge AI's priority action as a candidate index: pass (0), the action itself when it is offered, or, when Forge AI
     * would cast or activate something the pool cannot pay for yet, the first offered mana ability of Forge AI's own
     * payment plan for it (this protocol activates mana as priority actions; Forge AI taps while it pays). Null when
     * neither is offered.
     */
    private List<Integer> forgeAiPriority(List<SpellAbility> actions, List<Candidate> candidates) {
        if (intentStillOn()) {
            SpellAbility intent = forgeAiIntent;
            List<Integer> r = priorityFor(intent, actions, candidates);
            if (r != null) {
                return r;
            }
            // the remembered action cannot go on (no source left that helps): Forge AI decides again
            session.stats().note("chooseSpellAbilityToPlay(shadow Forge AI): remembered action dropped: "
                + intent.getHostCard().getName() + " (pool " + poolText() + ")");
        }
        forgeAiIntent = null;
        List<SpellAbility> ai = forgeAiAction();
        if (ai == null || ai.isEmpty()) {
            return List.of(0);
        }
        rememberPlan(ai.get(0));
        List<Integer> r = priorityFor(ai.get(0), actions, candidates);
        if (r == null) {
            // Forge AI's action cannot be played from what is offered (its castability check counts what this seat
            // cannot use now: a source it would fetch first, restricted mana, an alternative cost): in Forge its
            // attempt fails and the seat goes on; here it passes (an intended delegation, counted)
            session.stats().note("chooseSpellAbilityToPlay(shadow Forge AI): unplayable action, passes: "
                + ai.get(0).getHostCard().getName());
            return List.of(0);
        }
        return r;
    }

    /** Forge AI's action {@code want} as a candidate index (the action, or mana for it), or null. */
    private List<Integer> priorityFor(SpellAbility want, List<SpellAbility> actions, List<Candidate> candidates) {
        session.delegationDetail("Forge AI wants " + want + " [" + want.getApi() + ", cost " + want.getPayCosts() + "]");
        for (int i = 0; i < actions.size(); i++) {
            if (actions.get(i) == want) {
                forgeAiIntent = null;
                return List.of(i + 1);
            }
        }
        Candidate c = candidate(want);
        int same = candidates.indexOf(c);
        if (same > 0) {
            forgeAiIntent = null;
            return List.of(same);
        }
        // what the action still needs: its cost (X as Forge AI planned it, cost changes applied) less the pool mana it
        // can use (a priority mana activation adds to the pool; the spell is offered once the pool pays)
        // a spell's mana cost is not among its pay costs' parts until it is cast: Forge AI's own cost computation
        // (the mana cost for this ability, X as its play logic set it, cost changes)
        ManaCostBeingPaid cost;
        try {
            cost = ComputerUtilMana.calculateManaCost(want.getPayCosts(), want, player, true, 0, false);
        } catch (RuntimeException e) {
            cost = new ManaCostBeingPaid(ManaCost.ZERO);
        }
        for (forge.game.mana.Mana m : com.google.common.collect.Lists.newArrayList(player.getManaPool())) {
            if (!cost.isPaid() && m.meetsManaRestrictions(want) && cost.isNeeded(m, player.getManaPool())) {
                cost.payMana(m, player.getManaPool()); // the cost only (the pool keeps its mana)
            }
        }
        if (cost.isPaid()) {
            forgeAiIntent = null;
            return null; // the pool pays it and the action is not offered: nothing to map it to
        }
        // Forge AI's own sources for the rest first, each with an ability that makes a color the rest needs
        // (Forge pays the cost object it plans with: it gets a copy)
        CardCollection sources = ComputerUtilMana.getManaSourcesToPayCost(new ManaCostBeingPaid(cost), want, player, false);
        if (sources != null) {
            for (Card src : sources) {
                int i = bestManaAbility(session.ids().idOf(src), want, cost, actions, candidates);
                if (i > 0) {
                    // Forge AI pays for the action it chose, as it does when it taps while paying: the next priority
                    // asks go on paying for it (asking again with mana floating could choose something else)
                    rememberIntent(want);
                    return List.of(i);
                }
            }
        }
        // a planned source makes the color only with a mana ability that costs mana itself (a filter land: Mystic
        // Gate's {W/U}, {T}: add two): the input for it first, from another offered source
        if (sources != null) {
            for (Card src : sources) {
                for (SpellAbility filter : src.getManaAbilities()) {
                    if (!makesNeeded(filter, cost) || filter.getPayCosts() == null || filter.getPayCosts().getTotalMana() == null
                            || filter.getPayCosts().getTotalMana().isNoCost() || filter.getPayCosts().getTotalMana().getCMC() == 0) {
                        continue;
                    }
                    ManaCostBeingPaid input = new ManaCostBeingPaid(filter.getPayCosts().getTotalMana());
                    for (int i = 1; i < candidates.size(); i++) {
                        Candidate k = candidates.get(i);
                        if (k.hasActivateAbility() && k.getActivateAbility().getManaAbility() && i - 1 < actions.size()
                                && actions.get(i - 1).getHostCard() != src && makesNeeded(actions.get(i - 1), input)
                                && !tapsOwnSource(want, actions.get(i - 1))) {
                            rememberIntent(want);
                            return List.of(i);
                        }
                    }
                }
            }
        }
        // no planned source is offered (Forge AI's plan can come back empty for a part-paid cost): the sources in Forge
        // AI's own order of use (lands without drawbacks first; creatures, sacrifices and costs last)
        for (Card src : ComputerUtilMana.getAvailableManaSources(player, false)) {
            int i = bestManaAbility(session.ids().idOf(src), want, cost, actions, candidates);
            if (i > 0) {
                rememberIntent(want);
                return List.of(i);
            }
        }
        // no offered source of Forge AI's plan makes what is still needed (it counts pool mana the action cannot use,
        // restricted mana): another offered mana ability that does
        for (int i = 1; i < candidates.size(); i++) {
            Candidate k = candidates.get(i);
            if (k.hasActivateAbility() && k.getActivateAbility().getManaAbility() && i - 1 < actions.size()
                    && makesNeeded(actions.get(i - 1), cost) && !tapsOwnSource(want, actions.get(i - 1))) {
                int best = bestManaAbility(k.getActivateAbility().getSourceId(), want, cost, actions, candidates);
                session.stats().note("chooseSpellAbilityToPlay(shadow Forge AI): mana from a source outside Forge AI's plan");
                rememberIntent(want);
                return List.of(best > 0 ? best : i);
            }
        }
        forgeAiIntent = null;
        return null;
    }

    // Forge AI's plan for the action it chose: it sets targets, modes and X while it weighs the action; the play path
    // asks for them again (and resets them first), so the delegated answers take them from here
    private SpellAbility planned;
    private final Map<SpellAbility, List<GameObject>> plannedTargets = new java.util.IdentityHashMap<>();
    private List<AbilitySub> plannedModes;
    private Integer plannedX;

    private void rememberPlan(SpellAbility want) {
        planned = want;
        plannedTargets.clear();
        for (SpellAbility part = want; part != null; part = part.getSubAbility()) {
            if (part.usesTargeting() && !part.getTargets().isEmpty()) {
                plannedTargets.put(part, new ArrayList<>(part.getTargets()));
            }
        }
        plannedModes = want.getChosenList() == null ? null : new ArrayList<>(want.getChosenList());
        plannedX = want.getXManaCostPaid();
    }

    /** Forge AI's planned targets of {@code s} when it is a part of the planned action and they are offered. */
    private List<GameObject> plannedTargetsOf(SpellAbility s, List<GameObject> options, int min, int max) {
        if (planned == null || s.getRootAbility() != planned.getRootAbility()) {
            return null;
        }
        List<GameObject> t = plannedTargets.get(s);
        return t != null && t.size() >= min && t.size() <= max && options.containsAll(t) ? t : null;
    }

    /**
     * Whether making mana with {@code mana} would tap the source the action itself must tap (Monumental Henge's
     * {2}{W}{W}, {T}): that mana would leave the action unplayable.
     */
    private static boolean tapsOwnSource(SpellAbility want, SpellAbility mana) {
        return mana.getHostCard() == want.getHostCard() && want.getPayCosts() != null && want.getPayCosts().hasTapCost();
    }

    /**
     * Of a source's offered mana abilities that make what the cost still needs, the one that makes the most mana the
     * action may spend (Eldrazi Temple: {C}{C} for a colorless Eldrazi spell rather than {C}); 0 when none.
     */
    private int bestManaAbility(long sourceId, SpellAbility want, ManaCostBeingPaid cost, List<SpellAbility> actions,
                                List<Candidate> candidates) {
        int best = 0;
        int bestAmount = -1;
        int bestScarcity = Integer.MAX_VALUE;
        for (int i = 1; i < candidates.size(); i++) {
            Candidate k = candidates.get(i);
            if (!k.hasActivateAbility() || !k.getActivateAbility().getManaAbility() || k.getActivateAbility().getSourceId() != sourceId
                    || i - 1 >= actions.size()) {
                continue;
            }
            SpellAbility m = actions.get(i - 1);
            if (!makesNeeded(m, cost) || m.getManaPart() == null || !m.getManaPart().meetsManaRestrictions(want)
                    || tapsOwnSource(want, m)) {
                continue;
            }
            int amount = m.amountOfManaGenerated(true);
            int scarce = scarcity(m, cost, actions, candidates);
            // the color the fewest other offered sources can make first (a dual land makes the color only it has:
            // Godless Shrine's {B} for Ketramose when the other lands make {W}), then the most mana
            if (best == 0 || scarce < bestScarcity || (scarce == bestScarcity && amount > bestAmount)) {
                best = i;
                bestAmount = amount;
                bestScarcity = scarce;
            }
        }
        return best;
    }

    /**
     * For the colored shards the cost still needs that {@code mana} can pay: the fewest other offered sources (other
     * cards) that can pay one of them; a large number when it pays generic mana only.
     */
    private int scarcity(SpellAbility mana, ManaCostBeingPaid cost, List<SpellAbility> actions, List<Candidate> candidates) {
        int least = 1000;
        for (forge.card.mana.ManaCostShard shard : cost.getUnpaidShards()) {
            if (shard == forge.card.mana.ManaCostShard.GENERIC || shard.isPhyrexian() || !pays(mana, shard)) {
                continue;
            }
            Set<Card> others = new java.util.HashSet<>();
            for (int i = 1; i < candidates.size() && i - 1 < actions.size(); i++) {
                Candidate k = candidates.get(i);
                SpellAbility o = actions.get(i - 1);
                if (k.hasActivateAbility() && k.getActivateAbility().getManaAbility() && o.getHostCard() != mana.getHostCard()
                        && o.getManaPart() != null && pays(o, shard)) {
                    others.add(o.getHostCard());
                }
            }
            least = Math.min(least, others.size());
        }
        return least;
    }

    /**
     * Whether the mana ability makes {@code color} now: each part of it whose condition holds (River of Tears: {U}, or
     * {B} by its sub-ability once a land was played this turn).
     */
    private static boolean produces(SpellAbility mana, String color) {
        for (SpellAbility part = mana; part != null; part = part.getSubAbility()) {
            if (part.getManaPart() != null && part.metConditions() && part.getManaPart().canProduce(color, part)) {
                return true;
            }
        }
        return false;
    }

    /** Whether the mana ability can make a color that pays the shard ({C} only with colorless mana). */
    private static boolean pays(SpellAbility mana, forge.card.mana.ManaCostShard shard) {
        if (mana.getManaPart() == null) {
            return false;
        }
        for (String color : List.of("W", "U", "B", "R", "G", "C")) {
            boolean ok = color.equals("C") ? shard == forge.card.mana.ManaCostShard.COLORLESS
                || shard.canBePaidWithManaOfColor(forge.card.MagicColor.COLORLESS)
                : shard.canBePaidWithManaOfColor(forge.card.MagicColor.fromName(color));
            if (ok && produces(mana, color)) {
                return true;
            }
        }
        return false;
    }

    /** The action Forge AI chose and is making mana for (priority actions in the same window). */
    private SpellAbility forgeAiIntent;
    private int intentTurn;
    private forge.game.phase.PhaseType intentPhase;
    private int intentStack;
    private forge.game.zone.ZoneType intentZone;

    private void rememberIntent(SpellAbility want) {
        forgeAiIntent = want;
        intentTurn = game().getPhaseHandler().getTurn();
        intentPhase = game().getPhaseHandler().getPhase();
        intentStack = game().getStack().size();
        intentZone = want.getHostCard().getZone() == null ? null : want.getHostCard().getZone().getZoneType();
    }

    /** Whether the remembered action is still the one being paid for: same window, its card where it was. */
    private boolean intentStillOn() {
        if (forgeAiIntent == null) {
            return false;
        }
        Card host = game().getCardState(forgeAiIntent.getHostCard());
        return intentTurn == game().getPhaseHandler().getTurn() && intentPhase == game().getPhaseHandler().getPhase()
            && intentStack == game().getStack().size() && host != null && host.getZone() != null
            && host.getZone().getZoneType() == intentZone;
    }

    private forge.game.Game game() {
        return player.getGame();
    }

    /** Whether a mana ability makes a color some unpaid shard of the cost can take. */
    private static boolean makesNeeded(SpellAbility mana, ManaCostBeingPaid cost) {
        if (mana.getManaPart() == null) {
            return false;
        }
        // colored shards decide (any color pays generic); generic only when no colored shard is left
        List<forge.card.mana.ManaCostShard> shards = new ArrayList<>();
        for (forge.card.mana.ManaCostShard shard : cost.getUnpaidShards()) {
            // Phyrexian shards can be paid with life (the payment offers it): no color has to be made for them
            if (shard != forge.card.mana.ManaCostShard.GENERIC && !shard.isPhyrexian()) {
                shards.add(shard);
            }
        }
        if (shards.isEmpty()) {
            return true;
        }
        for (forge.card.mana.ManaCostShard shard : shards) {
            for (String color : List.of("W", "U", "B", "R", "G", "C")) {
                // {C} takes colorless mana only (canBePaidWithManaOfColor has no colorless code for it)
                boolean pays = color.equals("C") ? shard == forge.card.mana.ManaCostShard.COLORLESS
                    || shard.canBePaidWithManaOfColor(forge.card.MagicColor.COLORLESS)
                    : shard.canBePaidWithManaOfColor(forge.card.MagicColor.fromName(color));
                if (produces(mana, color) && pays) {
                    return true;
                }
            }
        }
        return false;
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
                } else {
                    session.playedWithoutStack(player, sa);
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
        return decide(type, method, candidates, c, ctx, null, null);
    }

    /**
     * {@code forgeAi}: Forge AI's answer to this decision as candidate indices, used when the client delegates the
     * decision to Forge AI (the shadow game's Forge AI: {@link DelegatingClient}); null: not mappable here.
     */
    List<Integer> decide(DecisionType type, String method, List<Candidate> candidates, SelectionConstraints c,
                         DecisionContext ctx, java.util.function.Supplier<List<Integer>> forgeAi) {
        return decide(type, method, candidates, c, ctx, null, forgeAi);
    }

    /** {@code bundle}: the request is a step of a step bundle (Q18). */
    List<Integer> decide(DecisionType type, String method, List<Candidate> candidates, SelectionConstraints c,
                         DecisionContext ctx, ForgeGameSession.Bundle bundle) {
        return decide(type, method, candidates, c, ctx, bundle, null);
    }

    List<Integer> decide(DecisionType type, String method, List<Candidate> candidates, SelectionConstraints c,
                         DecisionContext ctx, ForgeGameSession.Bundle bundle, java.util.function.Supplier<List<Integer>> forgeAi) {
        ForgeGameSession.Asked asked = session.resolveDelegation(
            session.ask(decider, player, type, candidates, c, ctx, List.of(), bundle), forgeAi, method);
        if (!asked.ok()) {
            session.record(asked, List.of(), method, true, asked.failure());
            return null;
        }
        session.record(asked, asked.response().getChosenList(), method, false, null);
        return asked.response().getChosenList();
    }

    // ---- delegation to Forge AI: its answer as candidate indices (phase 2 shadow Forge AI policy, reused by phase 3) ----

    /** Indices of the answer's elements in {@code options} (the same object, else an equal one); null when one is missing. */
    static List<Integer> indicesOf(List<?> options, java.util.Collection<?> answer) {
        if (answer == null) {
            return List.of();
        }
        List<Integer> out = new ArrayList<>();
        for (Object a : answer) {
            int found = -1;
            for (int k = 0; k < options.size() && found < 0; k++) {
                if (options.get(k) == a && !out.contains(k)) {
                    found = k;
                }
            }
            for (int k = 0; k < options.size() && found < 0; k++) {
                if (java.util.Objects.equals(options.get(k), a) && !out.contains(k)) {
                    found = k;
                }
            }
            if (found < 0) {
                return null;
            }
            out.add(found);
        }
        return out;
    }

    /** A single answer as a list (none: empty). */
    static <T> List<T> one(T answer) {
        return answer == null ? List.of() : List.of(answer);
    }

    /**
     * Forge's own pick of pool mana for the next payment step: the first group that can pay the most demanding unpaid
     * shard (Forge pays the most difficult shards first); the first candidate otherwise.
     */
    private int forgePoolPick(ManaCostBeingPaid toPay, List<List<Mana>> groups, int candidates) {
        List<forge.card.mana.ManaCostShard> shards = new ArrayList<>(toPay.getUnpaidShards());
        java.util.Collections.sort(shards);
        // restricted mana first (it pays only some spells: Eldrazi Temple's), as Forge's payment spends it where it can
        for (boolean restrictedPass : new boolean[] {true, false}) {
            for (forge.card.mana.ManaCostShard shard : shards) {
                for (int g = 0; g < groups.size(); g++) {
                    Mana m = groups.get(g).get(0);
                    boolean restricted = m.getManaAbility() != null && m.getManaAbility().getManaRestrictions() != null
                        && !m.getManaAbility().getManaRestrictions().isEmpty();
                    if (restricted == restrictedPass && shard.canBePaidWithManaOfColor(m.getColor())) {
                        return g;
                    }
                }
            }
        }
        return candidates > 0 ? 0 : -1;
    }

    /** The index of a single answer in {@code options}; null when it is not there (or no answer). */
    static List<Integer> indexOf(List<?> options, Object answer) {
        return answer == null ? null : indicesOf(options, List.of(answer));
    }

    /** The index of the option holding the same elements as {@code answer} (optional cost combinations). */
    static List<Integer> sameElements(List<? extends java.util.Collection<?>> options, java.util.Collection<?> answer) {
        java.util.Collection<?> a = answer == null ? List.of() : answer;
        for (int k = 0; k < options.size(); k++) {
            if (options.get(k).size() == a.size() && options.get(k).containsAll(a)) {
                return List.of(k);
            }
        }
        return null;
    }

    private static SelectionConstraints between(int min, int max) {
        return SelectionConstraints.newBuilder().setMin(min).setMax(max).build();
    }

    private static Candidate option(int index, String label) {
        // engine text names cards with Forge's own card number, "Name (45)": not part of the label (the number
        // differs between two games of the same state, e.g. a shadow game)
        return Candidate.newBuilder().setOption(OptionCandidate.newBuilder().setIndex(index)
            .setLabel(label.replaceAll(" \\(\\d+\\)", ""))).build();
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
        return chooseCostCards(costPart, options, amount, sa, null);
    }

    List<Card> chooseCostCards(String costPart, CardCollectionView options, int amount, SpellAbility sa,
                               java.util.function.Supplier<? extends java.util.Collection<Card>> forgeAiCards) {
        List<Card> cards = toList(options);
        List<Candidate> candidates = new ArrayList<>();
        for (Card c : cards) {
            candidates.add(Candidate.newBuilder().setCardId(session.ids().idOf(c)).build());
        }
        List<Integer> chosen = decide(DecisionType.DECISION_TYPE_CHOOSE_CARDS, "cost:" + costPart, candidates,
            between(amount, amount), context(sa).setEnginePrompt(costPart).build(),
            () -> forgeAiCards == null ? null : indicesOf(cards, forgeAiCards.get()));
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
            context(ability).setEnginePrompt("cost change order: final cost").build(),
            () -> List.of(0) /* Forge applies the reductions in its own order: the first one listed */);
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
                    bundle,
            () -> List.of(forgePoolPick(toPay, groups, candidates.size())));
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
                        // a source activated to pay: one of the payment's mana abilities, as Forge's own payment
                        // input records it (InputPayMana: payManaFromAbility), so that a payment that is not made
                        // undoes it (untapped, its mana gone) instead of leaving the mana in the pool
                        ability.getPayingManaAbilities().add(source);
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
    protected boolean answerPayCostToPreventEffect(Cost cost, SpellAbility sa, boolean alreadyPaid, FCollectionView<Player> allPayers) {
        return PlaySpellAbility.payCostDuringAbilityResolve(this, player, cost, sa, null);
    }

    @Override
    protected boolean answerConfirmPayment(CostPart costPart, String question, SpellAbility sa) {
        List<Integer> chosen = decide(DecisionType.DECISION_TYPE_CHOOSE_OPTION, "confirmPayment",
            List.of(option(0, "do not pay"), option(1, "pay")), ONE, context(sa).setEnginePrompt(question).build(),
            () -> List.of(forgeAi(() -> super.answerConfirmPayment(costPart, question, sa)) ? 1 : 0));
        return chosen == null ? forgeAi(() -> super.answerConfirmPayment(costPart, question, sa)) : chosen.get(0) == 1;
    }

    @Override
    protected CardCollectionView answerChooseCardsForCost(CardCollectionView optionList, SpellAbility sa, CostPartWithList cpl,
                                                 int amount, boolean isOptional, String prompt) {
        if (optionList.size() <= amount && !isOptional) {
            return optionList;
        }
        List<Card> chosen = chooseCostCards(cpl.getClass().getSimpleName(), optionList, amount, sa,
            () -> forgeAi(() -> super.answerChooseCardsForCost(optionList, sa, cpl, amount, isOptional, prompt)));
        return chosen == null ? forgeAi(() -> super.answerChooseCardsForCost(optionList, sa, cpl, amount, isOptional, prompt)) : new CardCollection(chosen);
    }

    @Override
    protected CardCollectionView answerChooseCardsToDelve(int genericAmount, CardCollection grave) {
        int max = Math.min(genericAmount, grave.size());
        if (checkingCosts || max == 0) {
            return new CardCollection(grave.subList(0, max)); // castability checks assume the largest reduction
        }
        int min = Math.max(0, Math.min(max, genericAmount - player.getManaPool().totalMana()));
        List<Card> cards = toList(grave);
        List<Candidate> candidates = new ArrayList<>();
        cards.forEach(c -> candidates.add(Candidate.newBuilder().setCardId(session.ids().idOf(c)).build()));
        List<Integer> chosen = decide(DecisionType.DECISION_TYPE_CHOOSE_CARDS, "chooseCardsToDelve", candidates,
            between(min, max), context(player.getPaidForSA()).setEnginePrompt("delve").build(),
            () -> indicesOf(cards, forgeAi(() -> super.answerChooseCardsToDelve(genericAmount, grave))));
        if (chosen == null) {
            return forgeAi(() -> super.answerChooseCardsToDelve(genericAmount, grave));
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
    protected Integer answerAnnounceRequirements(SpellAbility ability, int min, int max, String announce) {
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
        final List<Integer> offeredValues = values;
        List<Integer> chosen = decide(DecisionType.DECISION_TYPE_CHOOSE_OPTION, "announceRequirements", candidates, ONE,
            // the value being announced is not paid yet: the ability's X is one left from Forge AI weighing it earlier
            context(ability).clearX().setEnginePrompt(announce).build(),
            () -> indexOf(offeredValues, forgeAiAnnounce(ability, min, max, announce, offeredValues)));
        final List<Integer> offered = values;
        return chosen == null ? forgeAi(() -> super.answerAnnounceRequirements(ability, min, max, announce))
            : offered.get(chosen.get(0));
    }

    /**
     * Forge AI's announced value. Forge AI announces most values (X) in its own play logic, not here (it answers null):
     * then the X its play logic set on the ability, or else the largest value offered (its play logic takes the most it
     * can pay, ComputerUtilMana.determineLeftoverMana; the offered values are the payable ones).
     */
    private Integer forgeAiAnnounce(SpellAbility ability, int min, int max, String announce, List<Integer> offered) {
        Integer v = forgeAi(() -> super.answerAnnounceRequirements(ability, min, max, announce));
        if (v == null) {
            Integer x = planned != null && ability.getRootAbility() == planned.getRootAbility() && plannedX != null ? plannedX
                : ability.getRootAbility().getXManaCostPaid();
            v = x != null && offered.contains(x) ? x : offered.get(offered.size() - 1);
            session.stats().note("announceRequirements(shadow Forge AI): value from its play logic (" + announce + ")");
        }
        return v;
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
    protected List<AbilitySub> answerChooseModeForAbility(SpellAbility sa, List<AbilitySub> possible, int min, int num,
                                                 boolean allowRepeat) {
        if (!allowRepeat && possible.size() == min) {
            return new ArrayList<>(possible); // one possible outcome (Kozilek's Command with two modes left)
        }
        if (allowRepeat || possible.size() < min) {
            return super.answerChooseModeForAbility(sa, possible, min, num, allowRepeat);
        }
        List<Candidate> candidates = new ArrayList<>();
        for (int i = 0; i < possible.size(); i++) {
            candidates.add(option(i, possible.get(i).getDescription()));
        }
        List<Integer> chosen = decide(DecisionType.DECISION_TYPE_CHOOSE_OPTION, "chooseModeForAbility", candidates,
            between(min, Math.min(num, possible.size())), context(sa).setEnginePrompt("modes").build(),
            () -> indicesOf(possible, asOffered(possible, forgeAiModes(sa, possible, min, num, allowRepeat))));
        if (chosen == null) {
            return forgeAi(() -> super.answerChooseModeForAbility(sa, possible, min, num, allowRepeat));
        }
        List<AbilitySub> out = new ArrayList<>();
        chosen.forEach(i -> out.add(possible.get(i)));
        return out;
    }

    /** Forge AI's modes as the offered objects (its own list may hold copies): the same object, else the same text. */
    private static List<AbilitySub> asOffered(List<AbilitySub> possible, List<AbilitySub> chosen) {
        if (chosen == null) {
            return null;
        }
        List<AbilitySub> out = new ArrayList<>();
        for (AbilitySub c : chosen) {
            AbilitySub match = possible.contains(c) ? c : null;
            for (int i = 0; i < possible.size() && match == null; i++) {
                if (!out.contains(possible.get(i)) && possible.get(i).getDescription().equals(c.getDescription())) {
                    match = possible.get(i);
                }
            }
            out.add(match == null ? c : match);
        }
        return out;
    }

    /**
     * Forge AI's modes. Forge AI chooses a charm's modes while it weighs the spell (CharmAi sets the chosen list) and
     * answers null here: then the modes its weighing chose, weighing it now when it has not.
     */
    private List<AbilitySub> forgeAiModes(SpellAbility sa, List<AbilitySub> possible, int min, int num, boolean allowRepeat) {
        List<AbilitySub> v = forgeAi(() -> super.answerChooseModeForAbility(sa, possible, min, num, allowRepeat));
        if (fits(asOffered(possible, v), possible, min, num)) {
            return asOffered(possible, v);
        }
        SpellAbility root = sa.getRootAbility();
        List<List<AbilitySub>> tried = new ArrayList<>();
        if (planned != null && root == planned.getRootAbility() && plannedModes != null) {
            tried.add(asOffered(possible, plannedModes));
        }
        if (root.getChosenList() != null) {
            tried.add(asOffered(possible, new ArrayList<>(root.getChosenList())));
        }
        for (List<AbilitySub> t : tried) {
            if (fits(t, possible, min, num)) {
                return t;
            }
        }
        // weighed now (CharmAi chooses while it weighs the spell)
        forgeAi(() -> forge.ai.SpellApiToAi.Converter.get(forge.game.ability.ApiType.Charm).canPlayWithSubs(player, root));
        List<AbilitySub> fresh = root.getChosenList() == null ? null : asOffered(possible, new ArrayList<>(root.getChosenList()));
        if (fits(fresh, possible, min, num)) {
            return fresh;
        }
        // Forge AI's choice names a mode that is not offered now, or too few: its offered modes, the rest in the
        // offered order up to the minimum (noted)
        List<AbilitySub> out = new ArrayList<>();
        List<AbilitySub> base = fresh != null ? fresh : !tried.isEmpty() ? tried.get(0) : List.of();
        for (AbilitySub m : base) {
            if (possible.contains(m) && !out.contains(m) && out.size() < num) {
                out.add(m);
            }
        }
        for (int i = 0; i < possible.size() && out.size() < min; i++) {
            if (!out.contains(possible.get(i))) {
                out.add(possible.get(i));
            }
        }
        session.stats().note("chooseModeForAbility(shadow Forge AI): its modes completed from the offered ones");
        return out;
    }

    private static boolean fits(List<AbilitySub> modes, List<AbilitySub> possible, int min, int num) {
        return modes != null && modes.size() >= min && modes.size() <= num && possible.containsAll(modes)
            && new java.util.HashSet<>(modes).size() == modes.size();
    }

    @Override
    protected List<OptionalCostValue> answerChooseOptionalCosts(SpellAbility choosen, List<OptionalCostValue> optionalCostValues) {
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
            context(choosen).setEnginePrompt("optional costs").build(),
            () -> sameElements(combos, forgeAi(() -> super.answerChooseOptionalCosts(choosen, optionalCostValues))));
        return chosen == null ? forgeAi(() -> super.answerChooseOptionalCosts(choosen, optionalCostValues)) : combos.get(chosen.get(0));
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
            context(payable.get(0)).setEnginePrompt("additional cost").build(),
            () -> indexOf(payable, forgeAi(() -> super.getAbilityToPlay(hostCard, abilities, triggerEvent))));
        return chosen == null ? forgeAi(() -> super.getAbilityToPlay(hostCard, abilities, triggerEvent)) : payable.get(chosen.get(0));
    }

    @Override
    protected byte answerChooseColor(String message, SpellAbility sa, ColorSet colors) {
        List<Byte> options = new ArrayList<>();
        for (byte c : MagicColor.WUBRG) {
            if (colors.hasAnyColor(c)) {
                options.add(c);
            }
        }
        if (options.size() <= 1) {
            return options.isEmpty() ? super.answerChooseColor(message, sa, colors) : options.get(0);
        }
        List<Candidate> candidates = new ArrayList<>();
        for (int i = 0; i < options.size(); i++) {
            candidates.add(option(i, ObservationBuilder.colorLetter(options.get(i))));
        }
        List<Integer> chosen = decide(DecisionType.DECISION_TYPE_CHOOSE_OPTION, "chooseColor", candidates, ONE,
            context(sa).setEnginePrompt(message == null ? "color" : message).build(),
            () -> {
                // the color a mana ability makes while this seat makes mana for Forge AI's action (Talisman of
                // Resilience: {B} or {G}): the one that pays what that action still needs, as Forge AI's payment does
                if (sa != null && sa.isManaAbility()) {
                    List<Map<Byte, Integer>> single = new ArrayList<>();
                    options.forEach(c -> single.add(Map.of(c, 1)));
                    int forAction = comboForIntent(single);
                    if (forAction >= 0) {
                        return List.of(forAction);
                    }
                }
                return indexOf(options, forgeAi(() -> super.answerChooseColor(message, sa, colors)));
            });
        return chosen == null ? forgeAi(() -> super.answerChooseColor(message, sa, colors)) : options.get(chosen.get(0));
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
            context(sa).setEnginePrompt("mana combination").build(),
            () -> {
                // making mana for Forge AI's action (a filter land: Mystic Gate's {W}{W}, {W}{U} or {U}{U}): the
                // combination that pays most of what that action still needs, as Forge AI's own payment chooses it
                int forAction = comboForIntent(combos);
                return forAction >= 0 ? List.of(forAction)
                    : indexOf(combos, forgeAi(() -> super.specifyManaCombo(sa, colorSet, manaAmount, different)));
            });
        return chosen == null ? forgeAi(() -> super.specifyManaCombo(sa, colorSet, manaAmount, different)) : combos.get(chosen.get(0));
    }

    /** The combination that pays most of the remembered action's remaining cost (colored shards first); -1: none. */
    private int comboForIntent(List<Map<Byte, Integer>> combos) {
        if (!intentStillOn()) {
            return -1;
        }
        ManaCostBeingPaid rest = remainingCost(forgeAiIntent);
        if (rest == null || rest.isPaid()) {
            return -1;
        }
        int best = -1;
        int bestScore = -1;
        for (int i = 0; i < combos.size(); i++) {
            List<forge.card.mana.ManaCostShard> shards = new ArrayList<>(rest.getUnpaidShards());
            int score = 0;
            for (Map.Entry<Byte, Integer> e : combos.get(i).entrySet()) {
                for (int k = 0; k < e.getValue(); k++) {
                    forge.card.mana.ManaCostShard hit = null;
                    for (forge.card.mana.ManaCostShard sh : shards) {
                        if (sh != forge.card.mana.ManaCostShard.GENERIC && sh.canBePaidWithManaOfColor(e.getKey())) {
                            hit = sh;
                            break;
                        }
                    }
                    if (hit != null) {
                        score += 10;
                    } else if (shards.contains(forge.card.mana.ManaCostShard.GENERIC)) {
                        hit = forge.card.mana.ManaCostShard.GENERIC;
                        score += 1;
                    }
                    if (hit != null) {
                        shards.remove(hit);
                    }
                }
            }
            if (score > bestScore) {
                best = i;
                bestScore = score;
            }
        }
        return best;
    }

    /** What the action still needs: its cost as Forge AI computes it less the pool mana it can use; null if unknown. */
    private ManaCostBeingPaid remainingCost(SpellAbility want) {
        ManaCostBeingPaid cost;
        try {
            cost = ComputerUtilMana.calculateManaCost(want.getPayCosts(), want, player, true, 0, false);
        } catch (RuntimeException e) {
            return null;
        }
        for (forge.game.mana.Mana m : com.google.common.collect.Lists.newArrayList(player.getManaPool())) {
            if (!cost.isPaid() && m.meetsManaRestrictions(want) && cost.isNeeded(m, player.getManaPool())) {
                cost.payMana(m, player.getManaPool());
            }
        }
        return cost;
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
            SelectionConstraints.newBuilder().setMin(n).setMax(n).setOrdered(true).build(), null,
            () -> indicesOf(activePlayerSAs, forgeAi(() -> super.orderSimultaneousSa(new ArrayList<>(activePlayerSAs)))));
        if (chosen == null) {
            // AiController.orderPlaySa removes entries from the list it is given (upstream candidate): a copy
            return forgeAi(() -> super.orderSimultaneousSa(new ArrayList<>(activePlayerSAs)));
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
    protected boolean answerConfirmTrigger(WrappedAbility wrapper) {
        SpellAbility sa = wrapper.getWrappedAbility();
        if (sa.hasParam("Cost") && !sa.getParam("Cost").equals("0")) {
            return true;
        }
        List<Integer> chosen = decide(DecisionType.DECISION_TYPE_CHOOSE_OPTION, "confirmTrigger",
            List.of(option(0, "no"), option(1, "yes")), ONE, context(wrapper).setEnginePrompt("optional trigger").build(),
            () -> List.of(forgeAi(() -> super.answerConfirmTrigger(wrapper)) ? 1 : 0));
        if (chosen == null) {
            return forgeAi(() -> super.answerConfirmTrigger(wrapper));
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
                                                 SpellAbility sa, String prompt,
                                                 java.util.function.Supplier<? extends java.util.Collection<?>> forgeAi) {
        session.showTo(decider, options);
        List<Candidate> candidates = new ArrayList<>();
        for (Card c : options) {
            candidates.add(Candidate.newBuilder().setCardId(session.ids().idOf(c)).build());
        }
        List<Integer> chosen = decide(DecisionType.DECISION_TYPE_CHOOSE_CARDS, method, candidates,
            SelectionConstraints.newBuilder().setMin(min).setMax(max).setOrdered(ordered).build(),
            context(sa).setEnginePrompt(prompt == null ? method : prompt).build(),
            () -> forgeAi == null ? null : indicesOf(options, forgeAi.get()));
        if (chosen == null) {
            return null;
        }
        List<T> out = new ArrayList<>();
        chosen.forEach(i -> out.add(options.get(i)));
        return out;
    }

    /** Players and cards chosen from {@code options}; candidates are object references. */
    private <T extends GameEntity> List<T> chooseEntities(String method, List<T> options, int min, int max,
                                                          SpellAbility sa, String prompt,
                                                          java.util.function.Supplier<? extends java.util.Collection<?>> forgeAi) {
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
            context(sa).setEnginePrompt(prompt == null ? method : prompt).build(),
            () -> forgeAi == null ? null : indicesOf(options, forgeAi.get()));
        if (chosen == null) {
            return null;
        }
        List<T> out = new ArrayList<>();
        chosen.forEach(i -> out.add(options.get(i)));
        return out;
    }

    private boolean yesNo(String method, SpellAbility sa, String prompt, java.util.function.BooleanSupplier forgeAi) {
        List<Integer> chosen = decide(DecisionType.DECISION_TYPE_CHOOSE_OPTION, method,
            List.of(option(0, "no"), option(1, "yes")), ONE, context(sa).setEnginePrompt(prompt == null ? method : prompt).build(),
            () -> List.of(forgeAi.getAsBoolean() ? 1 : 0));
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
    protected CardCollectionView answerChooseCardsForEffect(CardCollectionView sourceList, SpellAbility sa, String title, int min,
                                                   int max, boolean isOptional, Map<String, Object> params) {
        List<Card> options = toList(sourceList);
        int lo = isOptional ? 0 : Math.min(min, options.size());
        int hi = Math.min(max, options.size());
        if (hi == 0 || (lo == hi && hi == options.size())) {
            return new CardCollection(options.subList(0, hi));
        }
        List<Card> chosen = chooseCards("chooseCardsForEffect", options, lo, hi, false, sa, title,
            () -> forgeAi(() -> super.answerChooseCardsForEffect(sourceList, sa, title, min, max, isOptional, params)));
        return chosen == null ? forgeAi(() -> super.answerChooseCardsForEffect(sourceList, sa, title, min, max, isOptional, params))
            : new CardCollection(chosen);
    }

    @Override
    protected <T extends GameEntity> T answerChooseSingleEntityForEffect(FCollectionView<T> optionList, DelayedReveal delayedReveal,
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
        List<T> chosen = chooseEntities("chooseSingleEntityForEffect", options, isOptional ? 0 : 1, 1, sa, title,
            () -> one(forgeAi(() -> super.answerChooseSingleEntityForEffect(optionList, delayedReveal, sa, title, isOptional,
                relatedPlayer, params))));
        if (chosen == null) {
            return forgeAi(() -> super.answerChooseSingleEntityForEffect(optionList, delayedReveal, sa, title, isOptional, relatedPlayer, params));
        }
        return chosen.isEmpty() ? null : chosen.get(0);
    }

    @Override
    protected <T extends GameEntity> List<T> answerChooseEntitiesForEffect(FCollectionView<T> optionList, int min, int max,
                                                                 DelayedReveal delayedReveal, SpellAbility sa, String title,
                                                                 Player relatedPlayer, Map<String, Object> params) {
        List<T> options = new ArrayList<>();
        optionList.forEach(options::add);
        int lo = Math.min(min, options.size());
        int hi = Math.min(max, options.size());
        if (hi == 0 || (lo == hi && hi == options.size())) {
            return new ArrayList<>(options.subList(0, hi));
        }
        List<T> chosen = chooseEntities("chooseEntitiesForEffect", options, lo, hi, sa, title,
            () -> forgeAi(() -> super.answerChooseEntitiesForEffect(optionList, min, max, delayedReveal, sa, title, relatedPlayer, params)));
        return chosen == null
            ? forgeAi(() -> super.answerChooseEntitiesForEffect(optionList, min, max, delayedReveal, sa, title, relatedPlayer, params))
            : chosen;
    }

    @Override
    protected Card answerChooseSingleCardForZoneChange(ZoneType destination, List<ZoneType> origin, SpellAbility sa,
                                              CardCollection fetchList, DelayedReveal delayedReveal, String selectPrompt,
                                              boolean isOptional, Player decider) {
        List<Card> options = toList(fetchList);
        if (options.isEmpty()) {
            return null;
        }
        if (options.size() == 1 && !isOptional) {
            return options.get(0);
        }
        List<Card> chosen = chooseCards("chooseSingleCardForZoneChange", options, isOptional ? 0 : 1, 1, false, sa, selectPrompt,
            () -> one(forgeAi(() -> super.answerChooseSingleCardForZoneChange(destination, origin, sa, fetchList, delayedReveal,
                selectPrompt, isOptional, decider))));
        if (chosen == null) {
            return forgeAi(() -> super.answerChooseSingleCardForZoneChange(destination, origin, sa, fetchList, delayedReveal,
                selectPrompt, isOptional, decider));
        }
        return chosen.isEmpty() ? null : chosen.get(0);
    }

    @Override
    protected List<Card> answerChooseCardsForZoneChange(ZoneType destination, List<ZoneType> origin, SpellAbility sa,
                                               CardCollection fetchList, int min, int max, DelayedReveal delayedReveal,
                                               String selectPrompt, Player decider) {
        List<Card> options = toList(fetchList);
        int lo = Math.min(min, options.size());
        int hi = Math.min(max, options.size());
        if (hi == 0 || (lo == hi && hi == options.size())) {
            return new ArrayList<>(options.subList(0, hi));
        }
        List<Card> chosen = chooseCards("chooseCardsForZoneChange", options, lo, hi, false, sa, selectPrompt,
            () -> forgeAi(() -> super.answerChooseCardsForZoneChange(destination, origin, sa, fetchList, min, max,
                delayedReveal, selectPrompt, decider)));
        return chosen == null ? forgeAi(() -> super.answerChooseCardsForZoneChange(destination, origin, sa, fetchList, min, max,
            delayedReveal, selectPrompt, decider)) : chosen;
    }

    @Override
    protected CardCollectionView answerChoosePermanentsToSacrifice(SpellAbility sa, int min, int max, CardCollectionView validTargets,
                                                          String message) {
        return chooseGroup("choosePermanentsToSacrifice", validTargets, min, max, sa, message,
            () -> super.answerChoosePermanentsToSacrifice(sa, min, max, validTargets, message));
    }

    @Override
    protected CardCollectionView answerChoosePermanentsToDestroy(SpellAbility sa, int min, int max, CardCollectionView validTargets,
                                                        String message) {
        return chooseGroup("choosePermanentsToDestroy", validTargets, min, max, sa, message,
            () -> super.answerChoosePermanentsToDestroy(sa, min, max, validTargets, message));
    }

    private CardCollectionView chooseGroup(String method, CardCollectionView valid, int min, int max, SpellAbility sa,
                                           String prompt, java.util.function.Supplier<CardCollectionView> forgeAi) {
        List<Card> options = toList(valid);
        int lo = Math.min(min, options.size());
        int hi = Math.min(max, options.size());
        if (hi == 0 || (lo == hi && hi == options.size())) {
            return new CardCollection(options.subList(0, hi));
        }
        List<Card> chosen = chooseCards(method, options, lo, hi, false, sa, prompt, () -> forgeAi(forgeAi));
        return chosen == null ? forgeAi(forgeAi) : new CardCollection(chosen);
    }

    @Override
    protected CardCollection answerChooseCardsToDiscardFrom(Player playerDiscard, SpellAbility sa, CardCollection validCards, int min,
                                                   int max, CardCollectionView visibleToChooser) {
        List<Card> options = toList(validCards);
        int lo = Math.min(min, options.size());
        int hi = Math.min(max, options.size());
        if (hi == 0 || (lo == hi && hi == options.size())) {
            return new CardCollection(options.subList(0, hi));
        }
        List<Card> chosen = chooseCards("chooseCardsToDiscardFrom", options, lo, hi, false, sa, "discard",
            () -> forgeAi(() -> super.answerChooseCardsToDiscardFrom(playerDiscard, sa, validCards, min, max, visibleToChooser)));
        return chosen == null
            ? forgeAi(() -> super.answerChooseCardsToDiscardFrom(playerDiscard, sa, validCards, min, max, visibleToChooser))
            : new CardCollection(chosen);
    }

    @Override
    protected CardCollectionView answerChooseCardsToDiscardToMaximumHandSize(int numDiscard) {
        List<Card> hand = toList(player.getCardsIn(ZoneType.Hand));
        if (numDiscard >= hand.size()) {
            return new CardCollection(hand);
        }
        List<Card> chosen = chooseCards("chooseCardsToDiscardToMaximumHandSize", hand, numDiscard, numDiscard, false, null, "cleanup discard",
            () -> forgeAi(() -> super.answerChooseCardsToDiscardToMaximumHandSize(numDiscard)));
        return chosen == null ? forgeAi(() -> super.answerChooseCardsToDiscardToMaximumHandSize(numDiscard)) : new CardCollection(chosen);
    }

    @Override
    protected boolean answerConfirmAction(SpellAbility sa, PlayerActionConfirmMode mode, String message, List<String> options,
                                 Card cardToShow, Map<String, Object> params) {
        try {
            return yesNo("confirmAction", sa, message,
                () -> forgeAi(() -> super.answerConfirmAction(sa, mode, message, options, cardToShow, params)));
        } catch (NoAnswer e) {
            return forgeAi(() -> super.answerConfirmAction(sa, mode, message, options, cardToShow, params));
        }
    }

    @Override
    protected boolean answerConfirmReplacementEffect(ReplacementEffect replacementEffect, SpellAbility effectSA, GameEntity affected,
                                            String question) {
        try {
            return yesNo("confirmReplacementEffect", effectSA, question,
                () -> forgeAi(() -> super.answerConfirmReplacementEffect(replacementEffect, effectSA, affected, question)));
        } catch (NoAnswer e) {
            return forgeAi(() -> super.answerConfirmReplacementEffect(replacementEffect, effectSA, affected, question));
        }
    }

    @Override
    protected boolean answerChooseBinary(SpellAbility sa, String question, BinaryChoiceType kindOfChoice, Boolean defaultChoice) {
        List<Integer> chosen = decide(DecisionType.DECISION_TYPE_CHOOSE_OPTION, "chooseBinary",
            List.of(option(0, kindOfChoice + ":first"), option(1, kindOfChoice + ":second")), ONE,
            context(sa).setEnginePrompt(question == null ? "binary" : question).build(),
            () -> List.of(forgeAi(() -> super.answerChooseBinary(sa, question, kindOfChoice, defaultChoice)) ? 0 : 1));
        return chosen == null ? forgeAi(() -> super.answerChooseBinary(sa, question, kindOfChoice, defaultChoice)) : chosen.get(0) == 0;
    }

    @Override
    protected int answerChooseNumber(SpellAbility sa, String title, int min, int max) {
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
            context(sa).setEnginePrompt(title == null ? "number" : title).build(),
            () -> { Integer v = forgeAi(() -> super.answerChooseNumber(sa, title, min, max)); return v == null || v < min || v - min >= candidates.size() ? null : List.of(v - min); });
        return chosen == null ? forgeAi(() -> super.answerChooseNumber(sa, title, min, max)) : min + chosen.get(0);
    }

    @Override
    protected CounterType answerChooseCounterType(List<CounterType> options, SpellAbility sa, String prompt, Map<String, Object> params) {
        if (options.size() <= 1) {
            return options.isEmpty() ? null : options.get(0);
        }
        List<Candidate> candidates = new ArrayList<>();
        for (int i = 0; i < options.size(); i++) {
            candidates.add(option(i, ObservationBuilder.counterName(options.get(i))));
        }
        List<Integer> chosen = decide(DecisionType.DECISION_TYPE_CHOOSE_OPTION, "chooseCounterType", candidates, ONE,
            context(sa).setEnginePrompt(prompt == null ? "counter" : prompt).build(),
            () -> indexOf(options, forgeAi(() -> super.answerChooseCounterType(options, sa, prompt, params))));
        return chosen == null ? forgeAi(() -> super.answerChooseCounterType(options, sa, prompt, params)) : options.get(chosen.get(0));
    }

    @Override
    protected SpellAbility answerChooseSingleSpellForEffect(List<SpellAbility> spells, SpellAbility sa, String title,
                                                   Map<String, Object> params) {
        if (spells.size() <= 1) {
            return spells.isEmpty() ? null : spells.get(0);
        }
        List<Candidate> candidates = new ArrayList<>();
        for (int i = 0; i < spells.size(); i++) {
            candidates.add(option(i, spells.get(i).getDescription()));
        }
        List<Integer> chosen = decide(DecisionType.DECISION_TYPE_CHOOSE_OPTION, "chooseSingleSpellForEffect", candidates, ONE,
            context(sa).setEnginePrompt(title == null ? "spell" : title).build(),
            () -> indexOf(spells, forgeAi(() -> super.answerChooseSingleSpellForEffect(spells, sa, title, params))));
        return chosen == null ? forgeAi(() -> super.answerChooseSingleSpellForEffect(spells, sa, title, params)) : spells.get(chosen.get(0));
    }

    @Override
    protected List<SpellAbility> answerChooseSpellAbilitiesForEffect(List<SpellAbility> spells, SpellAbility sa, String title, int num,
                                                           Map<String, Object> params) {
        if (spells.size() <= num) {
            return new ArrayList<>(spells);
        }
        List<Candidate> candidates = new ArrayList<>();
        for (int i = 0; i < spells.size(); i++) {
            candidates.add(option(i, spells.get(i).getDescription()));
        }
        List<Integer> chosen = decide(DecisionType.DECISION_TYPE_CHOOSE_OPTION, "chooseSpellAbilitiesForEffect", candidates,
            between(num, num), context(sa).setEnginePrompt(title == null ? "spells" : title).build(),
            () -> indicesOf(spells, forgeAi(() -> super.answerChooseSpellAbilitiesForEffect(spells, sa, title, num, params))));
        if (chosen == null) {
            return forgeAi(() -> super.answerChooseSpellAbilitiesForEffect(spells, sa, title, num, params));
        }
        List<SpellAbility> out = new ArrayList<>();
        chosen.forEach(i -> out.add(spells.get(i)));
        return out;
    }

    /** Which replacement effect applies first (616.1). */
    @Override
    protected ReplacementEffect answerChooseSingleReplacementEffect(List<ReplacementEffect> possibleReplacers) {
        if (possibleReplacers.size() <= 1) {
            return possibleReplacers.isEmpty() ? null : possibleReplacers.get(0);
        }
        List<Candidate> candidates = new ArrayList<>();
        for (int i = 0; i < possibleReplacers.size(); i++) {
            candidates.add(option(i, possibleReplacers.get(i).getHostCard().getName() + ": " + possibleReplacers.get(i)));
        }
        List<Integer> chosen = decide(DecisionType.DECISION_TYPE_CHOOSE_OPTION, "chooseSingleReplacementEffect", candidates,
            ONE, context(null).setEnginePrompt("replacement effect").build(),
            () -> indexOf(possibleReplacers, forgeAi(() -> super.answerChooseSingleReplacementEffect(possibleReplacers))));
        return chosen == null ? forgeAi(() -> super.answerChooseSingleReplacementEffect(possibleReplacers))
            : possibleReplacers.get(chosen.get(0));
    }

    /** Whether Forge AI of another seat is weighing its own action right now. */
    private boolean otherSeatWeighing() {
        for (Player p : player.getGame().getPlayers()) {
            if (p != player && p.getController() instanceof ObservedAiController o && o.isWeighing()) {
                return true;
            }
        }
        return false;
    }

    /** One of several pool mana an effect may use (MANA_PAYMENT). */
    @Override
    public Mana chooseManaFromPool(List<Mana> manaChoices) {
        if (manaChoices.size() <= 1) {
            return manaChoices.isEmpty() ? null : manaChoices.get(0);
        }
        if (otherSeatWeighing()) {
            // Forge AI of another seat estimates whether this seat can pay (a test payment from this seat's pool,
            // undone at once): part of that AI's own weighing, not a choice in the game. Intended delegation.
            session.delegated(player, "chooseManaFromPool(estimate)", DecisionType.DECISION_TYPE_MANA_PAYMENT, null);
            return forgeAiManaFromPool(manaChoices);
        }
        List<Candidate> candidates = new ArrayList<>();
        manaChoices.forEach(m -> candidates.add(Candidate.newBuilder().setPoolMana(observations().poolMana(m)).build()));
        List<Integer> chosen = decide(DecisionType.DECISION_TYPE_MANA_PAYMENT, "chooseManaFromPool", candidates, ONE,
            context(player.getPaidForSA()).setEnginePrompt("mana from pool").build(),
            () -> indexOf(manaChoices, forgeAiManaFromPool(manaChoices)));
        return chosen == null ? forgeAi(() -> super.chooseManaFromPool(manaChoices)) : manaChoices.get(chosen.get(0));
    }

    @Override
    protected int answerChooseNumberForKeywordCost(SpellAbility sa, Cost cost, KeywordInterface keyword, String prompt, int max) {
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
        final int offeredMax = Math.min(hi, 30);
        return numberFrom("chooseNumberForKeywordCost", 0, hi, sa, prompt, () -> {
            int v = forgeAiNumberForKeywordCost(sa, cost, keyword, prompt, max);
            if (v > offeredMax) {
                // Forge AI counts its untapped sources too; this seat pays from the pool (mana activated first)
                session.stats().note("chooseNumberForKeywordCost(shadow Forge AI): " + v + " capped to " + offeredMax);
                v = offeredMax;
            }
            return v;
        });
    }

    @Override
    protected int answerChooseNumberForCostReduction(SpellAbility sa, int min, int max) {
        return numberFrom("chooseNumberForCostReduction", min, max, sa, "cost reduction",
            () -> super.answerChooseNumberForCostReduction(sa, min, max));
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
            context(sa).setEnginePrompt(prompt == null ? method : prompt).build(),
            () -> { Integer v = forgeAi(forgeAi); return v == null || v < min || v - min >= candidates.size() ? null : List.of(v - min); });
        return chosen == null ? forgeAi(forgeAi) : min + chosen.get(0);
    }

    /** Opening-hand actions (Gemstone Caverns, Leylines): any of them, in the chosen order. */
    @Override
    protected List<SpellAbility> answerChooseSaToActivateFromOpeningHand(List<SpellAbility> usableFromOpeningHand) {
        if (usableFromOpeningHand.isEmpty()) {
            return usableFromOpeningHand;
        }
        List<Candidate> candidates = new ArrayList<>();
        for (int i = 0; i < usableFromOpeningHand.size(); i++) {
            candidates.add(option(i, usableFromOpeningHand.get(i).getHostCard().getName()));
        }
        List<Integer> chosen = decide(DecisionType.DECISION_TYPE_CHOOSE_OPTION, "chooseSaToActivateFromOpeningHand",
            candidates, between(0, candidates.size()), context(null).setEnginePrompt("opening hand").build(),
            () -> indicesOf(usableFromOpeningHand, forgeAi(() -> super.answerChooseSaToActivateFromOpeningHand(usableFromOpeningHand))));
        if (chosen == null) {
            return forgeAi(() -> super.answerChooseSaToActivateFromOpeningHand(usableFromOpeningHand));
        }
        List<SpellAbility> out = new ArrayList<>();
        chosen.forEach(i -> out.add(usableFromOpeningHand.get(i)));
        return out;
    }

    // ---- library order: scry, surveil, cards put on top or bottom (lists are top to bottom) ----

    @Override
    protected ImmutablePair<CardCollection, CardCollection> answerArrangeForScry(CardCollection topN) {
        return arrange("arrangeForScry", topN, "bottom", () -> super.answerArrangeForScry(topN));
    }

    @Override
    protected ImmutablePair<CardCollection, CardCollection> answerArrangeForSurveil(CardCollection topN) {
        return arrange("arrangeForSurveil", topN, "graveyard", () -> super.answerArrangeForSurveil(topN));
    }

    /**
     * Two steps: which cards leave the top (to the bottom, or the graveyard), then the order of each pile that has more
     * than one card (ordered selections, top to bottom; the graveyard pile in the order the cards are put there).
     */
    private ImmutablePair<CardCollection, CardCollection> arrange(String method, CardCollection topN, String away,
                                                                 java.util.function.Supplier<ImmutablePair<CardCollection, CardCollection>> forgeAi) {
        List<Card> cards = toList(topN);
        // Forge AI's arrangement (when delegated): computed once, it answers each step
        java.util.function.Supplier<ImmutablePair<CardCollection, CardCollection>> ai = new java.util.function.Supplier<>() {
            private ImmutablePair<CardCollection, CardCollection> pair;

            @Override
            public ImmutablePair<CardCollection, CardCollection> get() {
                if (pair == null) {
                    pair = forgeAi(forgeAi);
                }
                return pair;
            }
        };
        List<Card> leave = chooseCards(method + ":" + away, cards, 0, cards.size(), false, null, away, () -> ai.get().getRight());
        if (leave == null) {
            return forgeAi(forgeAi);
        }
        List<Card> stay = new ArrayList<>(cards);
        stay.removeAll(leave);
        List<Card> top = order(method + ":top", stay, () -> ai.get().getLeft());
        List<Card> bottom = order(method + ":" + away + " order", leave, () -> ai.get().getRight());
        if (top == null || bottom == null) {
            return forgeAi(forgeAi);
        }
        // E3: the deciding seat knows where these cards are (surveil's pile goes to the graveyard instead)
        session.arranged(decider, top, away.equals("bottom") ? bottom : List.of());
        return ImmutablePair.of(new CardCollection(top), new CardCollection(bottom));
    }

    /** The order of {@code cards}, asked when there is more than one; null when agent-core gave no usable answer. */
    private List<Card> order(String method, List<Card> cards,
                             java.util.function.Supplier<? extends java.util.Collection<?>> forgeAi) {
        if (cards.size() < 2) {
            return cards;
        }
        return chooseCards(method, cards, cards.size(), cards.size(), true, null, method, forgeAi);
    }

    @Override
    protected boolean answerWillPutCardOnTop(Card c) {
        session.showTo(decider, List.of(c));
        try {
            return yesNo("willPutCardOnTop", null, "put on top", () -> forgeAi(() -> super.answerWillPutCardOnTop(c)));
        } catch (NoAnswer e) {
            return forgeAi(() -> super.answerWillPutCardOnTop(c));
        }
    }

    @Override
    protected CardCollectionView answerOrderMoveToZoneList(CardCollectionView cards, ZoneType destinationZone, SpellAbility source) {
        if (cards.size() < 2 || (destinationZone != ZoneType.Library && destinationZone != ZoneType.Graveyard)) {
            return forgeAi(() -> super.answerOrderMoveToZoneList(cards, destinationZone, source));
        }
        List<Card> ordered = order("orderMoveToZoneList:" + destinationZone, toList(cards),
            () -> forgeAi(() -> super.answerOrderMoveToZoneList(cards, destinationZone, source)));
        if (ordered != null && destinationZone == ZoneType.Library) {
            session.facts().orderedBy(session.seat(decider), ordered);
        }
        return ordered == null ? forgeAi(() -> super.answerOrderMoveToZoneList(cards, destinationZone, source)) : new CardCollection(ordered);
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
        ForgeGameSession.Asked asked = session.resolveDelegation(
            session.ask(decider, player, DecisionType.DECISION_TYPE_TARGET, candidates, c, ctx, List.of()),
            () -> {
                List<GameObject> plan = plannedTargetsOf(s, options, Math.min(min, max), max);
                if (plan != null) {
                    return indicesOf(options, plan);
                }
                s.resetTargets();
                forgeAi(() -> getAi().doTrigger(s, true));
                List<Object> picked = new ArrayList<>(s.getTargets());
                s.resetTargets();
                return indicesOf(options, picked);
            }, "chooseTargetsFor");
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
            ForgeGameSession.Asked asked = session.resolveDelegation(
                session.ask(decider, player, DecisionType.DECISION_TYPE_DECLARE_ATTACKERS, candidates, c.build(), null, rejections),
                () -> {
                    forgeAi(() -> {
                        super.declareAttackers(attacker, combat);
                        return null;
                    });
                    List<Integer> pairs = new ArrayList<>();
                    for (Card a : new ArrayList<>(combat.getAttackers())) {
                        GameEntity d = combat.getDefenderByAttacker(a);
                        int k = -1;
                        for (int i = 0; i < creatures.size() && k < 0; i++) {
                            if (creatures.get(i) == a && defenders.get(i) == d) {
                                k = i;
                            }
                        }
                        combat.removeFromCombat(a);
                        if (k < 0) {
                            return null;
                        }
                        pairs.add(k);
                    }
                    return pairs;
                }, "declareAttackers");
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
            ForgeGameSession.Asked asked = session.resolveDelegation(
                session.ask(decider, player, DecisionType.DECISION_TYPE_DECLARE_BLOCKERS, candidates, c.build(), null, rejections),
                () -> {
                    forgeAi(() -> {
                        super.declareBlockers(defender, combat);
                        return null;
                    });
                    List<Integer> pairs = new ArrayList<>();
                    boolean ok = true;
                    for (Card a : new ArrayList<>(combat.getAttackers())) {
                        for (Card b : new ArrayList<>(combat.getBlockers(a))) {
                            int k = -1;
                            for (int i = 0; i < attackers.size() && k < 0; i++) {
                                if (attackers.get(i) == a && blockers.get(i) == b) {
                                    k = i;
                                }
                            }
                            combat.removeBlockAssignment(a, b);
                            if (k < 0) {
                                ok = false;
                            } else {
                                pairs.add(k);
                            }
                        }
                    }
                    return ok ? pairs : null;
                }, "declareBlockers");
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
    protected Map<Card, Integer> answerAssignCombatDamage(Card attacker, CardCollectionView blockers, CardCollectionView remaining,
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
        Map<Card, Integer> engine = forgeAi(() -> super.answerAssignCombatDamage(attacker, blockers, remaining, damageDealt, defender, overrideOrder));
        DamageAssignment.Builder base = DamageAssignment.newBuilder().setTotal(damageDealt).setTrample(trample);
        lethal.forEach((c, l) -> base.addLethal(DamageShare.newBuilder().setTo(session.ref(c)).setAmount(l)));
        if (engine != null) {
            // Forge AI's split is a hash map keyed by Forge's own card numbers: put in entity-ID order (the
            // recipients' order), the defender last
            for (Card c : recipients) {
                if (engine.containsKey(c)) {
                    base.addEngineDefault(DamageShare.newBuilder().setTo(session.ref(c)).setAmount(engine.get(c)));
                }
            }
            engine.forEach((c, n) -> {
                if (c == null || !recipients.contains(c)) {
                    base.addEngineDefault(DamageShare.newBuilder()
                        .setTo(c == null ? session.ref(defender) : session.ref(c)).setAmount(n));
                }
            });
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
                List<Integer> chosen = decide(DecisionType.DECISION_TYPE_DAMAGE_ASSIGNMENT, "assignCombatDamage", candidates, ONE, ctx,
            () -> {
                if (engine == null) {
                    return null;
                }
                int v = engine.getOrDefault(c, 0);
                if (v >= candidates.size()) {
                    // Forge AI gives this blocker more than lethal damage; the protocol offers up to lethal (the rest
                    // goes on by 510.1c): the most offered (noted)
                    session.stats().note("assignCombatDamage(shadow Forge AI): more than lethal capped to lethal");
                    v = candidates.size() - 1;
                }
                return List.of(v);
            });
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
    protected String answerChooseCardName(SpellAbility sa, Predicate<ICardFace> cpp, String valid, String message) {
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
        return nameFrom("chooseCardName", names, sa, message, () -> super.answerChooseCardName(sa, cpp, valid, message));
    }

    @Override
    protected String answerChooseCardName(SpellAbility sa, List<ICardFace> faces, String message) {
        Map<String, String> names = new java.util.TreeMap<>();
        faces.forEach(f -> names.putIfAbsent(session.keys().oracleIdByName(f.getName()), f.getName()));
        return nameFrom("chooseCardName(faces)", names, sa, message, () -> super.answerChooseCardName(sa, faces, message));
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
            context(sa).setEnginePrompt(message == null ? "name a card" : message).build(),
            () -> { String n = forgeAi(forgeAi);
                for (int k = 0; k < oracles.size(); k++) {
                    if (names.get(oracles.get(k)).equals(n)) {
                        return List.of(k);
                    }
                }
                return null; });
        return chosen == null ? forgeAi(forgeAi) : names.get(oracles.get(chosen.get(0)));
    }

    /** A type: for creature types, those of the creatures in the deciding player's 75 (Cavern of Souls). */
    @Override
    protected String answerChooseSomeType(String kindOfType, SpellAbility sa, Collection<String> validTypes, boolean isOptional) {
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
            return super.answerChooseSomeType(kindOfType, sa, validTypes, isOptional); // counted as unclassified
        }
        if (options.size() == 1 && !isOptional) {
            return options.get(0);
        }
        List<Candidate> candidates = new ArrayList<>();
        options.forEach(t -> candidates.add(Candidate.newBuilder().setNamedType(t).build()));
        List<Integer> chosen = decide(DecisionType.DECISION_TYPE_NAME, "chooseSomeType", candidates, ONE,
            context(sa).setEnginePrompt(kindOfType + " type").build(),
            () -> indexOf(options, forgeAi(() -> super.answerChooseSomeType(kindOfType, sa, validTypes, isOptional))));
        return chosen == null ? forgeAi(() -> super.answerChooseSomeType(kindOfType, sa, validTypes, isOptional)) : options.get(chosen.get(0));
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
            // Forge AI keeps its main deck between games (as the Forge AI seats do, 2026-11-06): done
            List<Integer> chosen = decide(DecisionType.DECISION_TYPE_SIDEBOARD, "sideboard", candidates, ONE, null, bundle,
                () -> List.of(0));
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
        ForgeGameSession.Asked asked = session.resolveDelegation(
            session.ask(decider, player, DecisionType.DECISION_TYPE_MULLIGAN, candidates, ONE, null, List.of()),
            () -> List.of(forgeAiMulliganKeepHand(firstPlayer, cardsToReturn) ? 0 : 1), "mulliganKeepHand");
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
        ForgeGameSession.Asked asked = session.resolveDelegation(
            session.ask(decider, player, DecisionType.DECISION_TYPE_BOTTOM_CARDS, candidates, c, null, List.of()),
            () -> indicesOf(cards, toList(forgeAiTuckCards(hand, cardsToReturn))), "tuckCardsViaMulligan");
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
        ForgeGameSession.Asked asked = session.resolveDelegation(
            session.ask(decider, player, DecisionType.DECISION_TYPE_PLAY_OR_DRAW, candidates, ONE, null, List.of()),
            () -> List.of(forgeAiStartingPlayer(isFirstGame) == player ? 0 : 1), "chooseStartingPlayer");
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
