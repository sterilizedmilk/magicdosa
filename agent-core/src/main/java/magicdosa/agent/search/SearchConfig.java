package magicdosa.agent.search;

/**
 * Settings of one IS-MCTS run (3단계 기획 쟁점 1·2·5). The budget is a number of simulations, never time (design:
 * 탐색 "예산은 시간이 아니라 노드 수"): each simulation adds at most one tree node, so the tree never has more nodes
 * than the budget.
 *
 * @param budget          simulations per decision (the "node budget"; the phase-3 gate runs it at 1x, 2x and 4x)
 * @param exploration     UCB exploration constant (rewards are in [0, 1])
 * @param opponentInTree  the other seat's decisions are tree nodes split by its own information (decision 1, 11-15);
 *                        false hands them to the rollout policy (the switch to compare when the budget runs short)
 * @param wideningMin     progressive widening (decision 2, 11-16): a node with more available actions than this
 *                        considers only its first {@code max(wideningMin, ceil(wideningC * visits^wideningAlpha))}
 *                        actions in a per-node random order; 0 turns widening off
 * @param wideningC       see {@code wideningMin}
 * @param wideningAlpha   see {@code wideningMin}
 */
public record SearchConfig(long budget, double exploration, boolean opponentInTree, int wideningMin, double wideningC,
                           double wideningAlpha) {

    public SearchConfig {
        if (budget < 1) {
            throw new IllegalArgumentException("budget must be at least 1 simulation: " + budget);
        }
        if (exploration < 0 || wideningMin < 0 || wideningC <= 0 || wideningAlpha <= 0 || wideningAlpha > 1) {
            throw new IllegalArgumentException("bad search settings");
        }
    }

    /** Phase-3 defaults with the given budget (UCB constant sqrt(2), opponent in the tree, widening past 20 actions). */
    public static SearchConfig of(long budget) {
        return new SearchConfig(budget, Math.sqrt(2), true, 20, 2.0, 0.5);
    }

    public SearchConfig withBudget(long b) {
        return new SearchConfig(b, exploration, opponentInTree, wideningMin, wideningC, wideningAlpha);
    }

    public SearchConfig withOpponentInTree(boolean in) {
        return new SearchConfig(budget, exploration, in, wideningMin, wideningC, wideningAlpha);
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
