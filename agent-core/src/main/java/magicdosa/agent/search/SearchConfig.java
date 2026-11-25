package magicdosa.agent.search;

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
 */
public record SearchConfig(long budget, double exploration, boolean opponentInTree, int wideningMin, double wideningC,
                           double wideningAlpha, int perCandidate, int multiplier) {

    public SearchConfig {
        if (budget < 1) {
            throw new IllegalArgumentException("budget must be at least 1 simulation: " + budget);
        }
        if (exploration < 0 || wideningMin < 0 || wideningC <= 0 || wideningAlpha <= 0 || wideningAlpha > 1
                || perCandidate < 0 || multiplier < 1) {
            throw new IllegalArgumentException("bad search settings");
        }
    }

    /** A fixed budget of {@code budget} simulations per decision. */
    public SearchConfig(long budget, double exploration, boolean opponentInTree, int wideningMin, double wideningC,
                        double wideningAlpha) {
        this(budget, exploration, opponentInTree, wideningMin, wideningC, wideningAlpha, 0, 1);
    }

    /** Simulations for a root with {@code actions} actions (its first step's candidates and "done"). */
    public long budgetFor(int actions) {
        return perCandidate == 0 ? budget : Math.max(1L, (long) actions * perCandidate * multiplier);
    }

    /** The budget by root: {@code perCandidate} simulations per root action, times {@code multiplier}. */
    public SearchConfig perCandidate(int simulationsPerAction, int times) {
        return new SearchConfig(budget, exploration, opponentInTree, wideningMin, wideningC, wideningAlpha,
            simulationsPerAction, times);
    }

    /** "12 per candidate x4" or "budget 40". */
    public String describe() {
        return perCandidate == 0 ? "budget " + budget : perCandidate + " per candidate x" + multiplier;
    }

    /** Phase-3 defaults with the given budget (UCB constant sqrt(2), opponent in the tree, widening past 20 actions). */
    public static SearchConfig of(long budget) {
        return new SearchConfig(budget, Math.sqrt(2), true, 20, 2.0, 0.5);
    }

    public SearchConfig withBudget(long b) {
        return new SearchConfig(b, exploration, opponentInTree, wideningMin, wideningC, wideningAlpha, perCandidate, multiplier);
    }

    public SearchConfig withOpponentInTree(boolean in) {
        return new SearchConfig(budget, exploration, in, wideningMin, wideningC, wideningAlpha, perCandidate, multiplier);
    }

    /**
     * Settings from system properties ({@code magicdosa.search.budget}, {@code .exploration}, {@code .opponentInTree},
     * {@code .wideningMin}, {@code .wideningC}, {@code .wideningAlpha}) over {@link #of(long)}.
     */
    public static SearchConfig fromSystemProperties(long defaultBudget) {
        SearchConfig d = of(Long.getLong("magicdosa.search.budget", defaultBudget));
        return new SearchConfig(d.budget,
            Double.parseDouble(System.getProperty("magicdosa.search.exploration", Double.toString(d.exploration))),
            Boolean.parseBoolean(System.getProperty("magicdosa.search.opponentInTree", Boolean.toString(d.opponentInTree))),
            Integer.getInteger("magicdosa.search.wideningMin", d.wideningMin),
            Double.parseDouble(System.getProperty("magicdosa.search.wideningC", Double.toString(d.wideningC))),
            Double.parseDouble(System.getProperty("magicdosa.search.wideningAlpha", Double.toString(d.wideningAlpha))));
    }
}
