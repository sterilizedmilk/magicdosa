package magicdosa.agent.search;

import magicdosa.protocol.v1.LeafEvaluation;
import magicdosa.protocol.v1.RolloutPolicy;
import magicdosa.protocol.v1.SearchHorizon;

import java.util.List;

/**
 * Settings of one IS-MCTS run (3단계 기획 쟁점 1·2·5). The budget is a number of simulations, never time (design:
 * 탐색 "예산은 시간이 아니라 노드 수"): each simulation adds at most one tree node, so the tree never has more nodes
 * than the budget.
 *
 * @param budget          simulations per decision when {@code perCandidate} is 0 (a fixed budget)
 * @param exploration     UCB exploration constant (rewards are in [0, 1])
 * @param opponentInTree  the other seat's decisions are tree nodes split by its own information (decision 1, 11-15);
 *                        false hands them to the rollout policy (the switch to compare when the budget runs short)
 * @param wideningMin     progressive widening (decision 2, 11-16): a node with more available actions than this
 *                        considers only its first {@code max(wideningMin, ceil(wideningC * visits^wideningAlpha))}
 *                        actions in a per-node random order; 0 turns widening off
 * @param wideningC       see {@code wideningMin}
 * @param wideningAlpha   see {@code wideningMin}
 * @param perCandidate    the budget by root (2026-11-25 planning decision): simulations per root action; a root with
 *                        n actions gets {@code n * perCandidate * multiplier} simulations (1x = 12 per candidate, 2x and
 *                        4x multiply it); 0 uses the fixed {@code budget}
 * @param multiplier      see {@code perCandidate}
 * @param rollout         who plays a simulation once it leaves the tree (2026-11-26 user decision): plain Forge AI
 *                        controllers ({@link RolloutPolicy#ROLLOUT_POLICY_PLAIN_FORGE_AI}, the default, a temporary
 *                        early-stage choice until the network evaluates leaves) or the decision layer delegating to
 *                        Forge AI ({@link RolloutPolicy#ROLLOUT_POLICY_LAYER})
 * @param horizon         where a simulation stops when the game has not ended (2026-11-26 user decision: the start of
 *                        the next turn, {@link SearchHorizon#SEARCH_HORIZON_NEXT_TURN}; before it the root seat's next
 *                        turn)
 * @param leafEvaluation  how a simulation's last state is valued without a network (2026-11-26 user decision: Forge's
 *                        score of the board as it stands, {@link LeafEvaluation#LEAF_EVALUATION_FORGE_STATIC}; before it
 *                        with Forge's simulation of the coming combat)
 */
public record SearchConfig(long budget, double exploration, boolean opponentInTree, int wideningMin, double wideningC,
                           double wideningAlpha, int perCandidate, int multiplier, RolloutPolicy rollout,
                           SearchHorizon horizon, LeafEvaluation leafEvaluation) {

    public SearchConfig {
        if (budget < 1) {
            throw new IllegalArgumentException("budget must be at least 1 simulation: " + budget);
        }
        if (exploration < 0 || wideningMin < 0 || wideningC <= 0 || wideningAlpha <= 0 || wideningAlpha > 1
                || perCandidate < 0 || multiplier < 1
                || (rollout != RolloutPolicy.ROLLOUT_POLICY_LAYER && rollout != RolloutPolicy.ROLLOUT_POLICY_PLAIN_FORGE_AI)
                || !HORIZONS.contains(horizon) || !LEAF_EVALUATIONS.contains(leafEvaluation)) {
            throw new IllegalArgumentException("bad search settings");
        }
    }

    /** A fixed budget of {@code budget} simulations per decision. */
    public SearchConfig(long budget, double exploration, boolean opponentInTree, int wideningMin, double wideningC,
                        double wideningAlpha) {
        this(budget, exploration, opponentInTree, wideningMin, wideningC, wideningAlpha, 0, 1, DEFAULT_ROLLOUT, DEFAULT_HORIZON,
            DEFAULT_LEAF_EVALUATION);
    }

    /** Simulations for a root with {@code actions} actions (its first step's candidates and "done"). */
    public long budgetFor(int actions) {
        return perCandidate == 0 ? budget : Math.max(1L, (long) actions * perCandidate * multiplier);
    }

    /** The budget by root: {@code perCandidate} simulations per root action, times {@code multiplier}. */
    public SearchConfig perCandidate(int simulationsPerAction, int times) {
        return new SearchConfig(budget, exploration, opponentInTree, wideningMin, wideningC, wideningAlpha,
            simulationsPerAction, times, rollout, horizon, leafEvaluation);
    }

    /** The default rollout policy (2026-11-26 user decision, early stage only). */
    public static final RolloutPolicy DEFAULT_ROLLOUT = RolloutPolicy.ROLLOUT_POLICY_PLAIN_FORGE_AI;
    /** The default horizon (2026-11-26 user decision, after the gate step-1 measurement in docs/search-perf.md). */
    public static final SearchHorizon DEFAULT_HORIZON = SearchHorizon.SEARCH_HORIZON_NEXT_TURN;
    /** The default leaf evaluation (2026-11-26 user decision, after the same measurement). */
    public static final LeafEvaluation DEFAULT_LEAF_EVALUATION = LeafEvaluation.LEAF_EVALUATION_FORGE_STATIC;

    private static final List<SearchHorizon> HORIZONS = List.of(SearchHorizon.SEARCH_HORIZON_OWN_TURN,
        SearchHorizon.SEARCH_HORIZON_NEXT_TURN);
    private static final List<LeafEvaluation> LEAF_EVALUATIONS = List.of(LeafEvaluation.LEAF_EVALUATION_FORGE_COMBAT,
        LeafEvaluation.LEAF_EVALUATION_FORGE_STATIC);

    /** "12 per candidate x4" or "budget 40", the rollout policy, the horizon and the leaf evaluation. */
    public String describe() {
        return (perCandidate == 0 ? "budget " + budget : perCandidate + " per candidate x" + multiplier) + ", rollout "
            + rolloutName(rollout) + ", horizon " + horizonName(horizon) + ", leaf " + leafName(leafEvaluation);
    }

    /** "own-turn" or "next-turn". */
    public static String horizonName(SearchHorizon h) {
        return h.name().substring("SEARCH_HORIZON_".length()).toLowerCase(java.util.Locale.ROOT).replace('_', '-');
    }

    /** The horizon named "own-turn" or "next-turn". */
    public static SearchHorizon horizonNamed(String name) {
        for (SearchHorizon h : HORIZONS) {
            if (horizonName(h).equals(name)) {
                return h;
            }
        }
        throw new IllegalArgumentException("unknown horizon " + name + " (own-turn, next-turn)");
    }

    /** "forge-combat" or "forge-static". */
    public static String leafName(LeafEvaluation e) {
        return e.name().substring("LEAF_EVALUATION_".length()).toLowerCase(java.util.Locale.ROOT).replace('_', '-');
    }

    /** The leaf evaluation named "forge-combat" or "forge-static". */
    public static LeafEvaluation leafNamed(String name) {
        for (LeafEvaluation e : LEAF_EVALUATIONS) {
            if (leafName(e).equals(name)) {
                return e;
            }
        }
        throw new IllegalArgumentException("unknown leaf evaluation " + name + " (forge-combat, forge-static)");
    }

    /** "layer" or "plain-forge-ai". */
    public static String rolloutName(RolloutPolicy p) {
        return p.name().substring("ROLLOUT_POLICY_".length()).toLowerCase(java.util.Locale.ROOT).replace('_', '-');
    }

    /** The policy named "layer" or "plain-forge-ai". */
    public static RolloutPolicy rolloutNamed(String name) {
        for (RolloutPolicy p : List.of(RolloutPolicy.ROLLOUT_POLICY_LAYER, RolloutPolicy.ROLLOUT_POLICY_PLAIN_FORGE_AI)) {
            if (rolloutName(p).equals(name)) {
                return p;
            }
        }
        throw new IllegalArgumentException("unknown rollout policy " + name + " (layer, plain-forge-ai)");
    }

    public SearchConfig withRollout(RolloutPolicy p) {
        return new SearchConfig(budget, exploration, opponentInTree, wideningMin, wideningC, wideningAlpha, perCandidate,
            multiplier, p, horizon, leafEvaluation);
    }

    public SearchConfig withHorizon(SearchHorizon h) {
        return new SearchConfig(budget, exploration, opponentInTree, wideningMin, wideningC, wideningAlpha, perCandidate,
            multiplier, rollout, h, leafEvaluation);
    }

    public SearchConfig withLeafEvaluation(LeafEvaluation e) {
        return new SearchConfig(budget, exploration, opponentInTree, wideningMin, wideningC, wideningAlpha, perCandidate,
            multiplier, rollout, horizon, e);
    }

    /** Phase-3 defaults with the given budget (UCB constant sqrt(2), opponent in the tree, widening past 20 actions). */
    public static SearchConfig of(long budget) {
        return new SearchConfig(budget, Math.sqrt(2), true, 20, 2.0, 0.5);
    }

    public SearchConfig withBudget(long b) {
        return new SearchConfig(b, exploration, opponentInTree, wideningMin, wideningC, wideningAlpha, perCandidate, multiplier, rollout,
            horizon, leafEvaluation);
    }

    public SearchConfig withOpponentInTree(boolean in) {
        return new SearchConfig(budget, exploration, in, wideningMin, wideningC, wideningAlpha, perCandidate, multiplier, rollout,
            horizon, leafEvaluation);
    }

    /**
     * Settings from system properties ({@code magicdosa.search.budget}, {@code .exploration}, {@code .opponentInTree},
     * {@code .wideningMin}, {@code .wideningC}, {@code .wideningAlpha}, {@code .rollout}: layer or plain-forge-ai, {@code .horizon}: own-turn or next-turn, {@code .leafEval}:
     * forge-combat or forge-static) over
     * {@link #of(long)}.
     */
    public static SearchConfig fromSystemProperties(long defaultBudget) {
        SearchConfig d = of(Long.getLong("magicdosa.search.budget", defaultBudget));
        return new SearchConfig(d.budget,
            Double.parseDouble(System.getProperty("magicdosa.search.exploration", Double.toString(d.exploration))),
            Boolean.parseBoolean(System.getProperty("magicdosa.search.opponentInTree", Boolean.toString(d.opponentInTree))),
            Integer.getInteger("magicdosa.search.wideningMin", d.wideningMin),
            Double.parseDouble(System.getProperty("magicdosa.search.wideningC", Double.toString(d.wideningC))),
            Double.parseDouble(System.getProperty("magicdosa.search.wideningAlpha", Double.toString(d.wideningAlpha))),
            0, 1, rolloutNamed(System.getProperty("magicdosa.search.rollout", rolloutName(d.rollout))),
            horizonNamed(System.getProperty("magicdosa.search.horizon", horizonName(d.horizon))),
            leafNamed(System.getProperty("magicdosa.search.leafEval", leafName(d.leafEvaluation))));
    }
}
