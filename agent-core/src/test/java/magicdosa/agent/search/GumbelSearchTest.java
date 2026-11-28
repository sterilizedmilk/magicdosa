package magicdosa.agent.search;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.SplittableRandom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Gumbel search (2026-11-28 directive "Gumbel v2"): sequential halving at the root within the budget, the completed-Q
 * rule below it, the same seed the same search, noise only when asked.
 */
class GumbelSearchTest {
    private static final SearchConfig.Gumbel EVAL = new SearchConfig.Gumbel(false, 16, 50, 0.1, true);
    private static final SearchConfig.Gumbel SELF_PLAY = new SearchConfig.Gumbel(true, 16, 50, 0.1, true);

    /** One decision of seat 0; action i is worth exactly value[i] (no randomness in the value). */
    private record Values(double[] value) implements SearchGame {
        @Override
        public int rootSeat() {
            return 0;
        }

        @Override
        public List<String> rootActions() {
            List<String> out = new ArrayList<>();
            for (int i = 0; i < value.length; i++) {
                out.add("a" + i);
            }
            return out;
        }

        @Override
        public Simulation determinize(SplittableRandom rng) {
            return chooser -> value[chooser.choose(new Decision(0, "root", rootActions()))];
        }
    }

    private static SearchConfig oneX(SearchConfig.Gumbel g) {
        return SearchConfig.of(1).perCandidate(12, 1).withGumbel(g);
    }

    private static double[] flat(int n, int high, double highValue) {
        double[] v = new double[n];
        java.util.Arrays.fill(v, 0.4);
        if (high >= 0) {
            v[high] = highValue;
        }
        return v;
    }

    @Test
    void sequentialHalvingStaysWithinTheBudget() {
        for (int n : new int[] {2, 3, 5, 7, 16, 17, 30}) {
            for (SearchConfig.Gumbel g : List.of(EVAL, SELF_PLAY)) {
                SearchResult r = new IsMcts(oneX(g)).search(new Values(flat(n, n / 2, 0.7)), 11);
                long total = 0;
                for (int i = 0; i < n; i++) {
                    total += r.visits(i);
                }
                assertEquals(12L * n, r.budget);
                assertTrue(r.iterations <= r.budget && total <= r.budget, n + ": " + r);
                assertEquals(Math.min(16, n), r.gumbelK);
            }
        }
    }

    @Test
    void theClearlyBetterCandidateIsChosen() {
        for (long seed = 1; seed <= 10; seed++) {
            SearchResult r = new IsMcts(oneX(EVAL)).search(new Values(flat(6, 4, 0.8)), seed);
            assertEquals(4, r.chosen(), r.toString());
        }
        // with noise (self-play) the answer is g + sigma(q_hat): the noise may outweigh a value gap, which is the
        // exploration it is for; only the noiseless (evaluation) answer is the value's
    }

    @Test
    void sameSeedSameSearch() {
        for (SearchConfig.Gumbel g : List.of(EVAL, SELF_PLAY)) {
            SearchResult a = new IsMcts(oneX(g)).search(new Values(flat(20, 3, 0.6)), 42);
            SearchResult b = new IsMcts(oneX(g)).search(new Values(flat(20, 3, 0.6)), 42);
            assertEquals(a.toString(), b.toString());
            assertEquals(a.method(), b.method());
            // protocol 18: the run records the algorithm and its settings
            magicdosa.protocol.v1.SearchRun run = a.method().getRan();
            assertEquals(magicdosa.protocol.v1.SearchAlgorithm.SEARCH_ALGORITHM_GUMBEL, run.getAlgorithm());
            assertEquals(g.noise(), run.getGumbelNoise());
            assertEquals(16, run.getGumbelK());
            assertEquals(50f, run.getCVisit(), 0);
            assertEquals(0.1f, run.getCScale(), 1e-7);
            assertTrue(run.getQNormalize());
        }
        assertEquals(magicdosa.protocol.v1.SearchAlgorithm.SEARCH_ALGORITHM_UCB1, new IsMcts(oneX(null))
            .search(new Values(flat(3, 0, 0.6)), 1).method().getRan().getAlgorithm());
    }

    @Test
    void noiseChangesWhichCandidatesEnterOnlyWhenOn() {
        // 30 equal candidates, k = 16: which enter is decided by the seed either way (rank ties or noise), but the noise
        // run's entrants differ from the noiseless run's for most seeds
        int differ = 0;
        for (long seed = 1; seed <= 10; seed++) {
            SearchResult quiet = new IsMcts(oneX(EVAL)).search(new Values(flat(30, -1, 0)), seed);
            SearchResult noisy = new IsMcts(oneX(SELF_PLAY)).search(new Values(flat(30, -1, 0)), seed);
            if (!entrants(quiet).equals(entrants(noisy))) {
                differ++;
            }
        }
        assertTrue(differ > 0);
    }

    private static List<Integer> entrants(SearchResult r) {
        List<Integer> out = new ArrayList<>();
        for (int i = 0; i < r.candidates(); i++) {
            if (r.visits(i) > 0) {
                out.add(i);
            }
        }
        return out;
    }

    /**
     * Below the root (the other seat's node): seat 0 plays it safe (0.6) or attacks into a seat that holds a blocker half
     * the time and blocks (seat 0 loses) or passes (seat 0 wins). Searched, the other seat blocks whenever it can, so
     * attacking is worth 0.5 and the safe play is chosen.
     */
    @Test
    void theOtherSeatsNodesUseTheCompletedQRule() {
        SearchGame ambush = new SearchGame() {
            @Override
            public int rootSeat() {
                return 0;
            }

            @Override
            public List<String> rootActions() {
                return List.of("safe", "attack");
            }

            @Override
            public Simulation determinize(SplittableRandom rng) {
                boolean blocker = rng.nextBoolean();
                return chooser -> {
                    if (chooser.choose(new Decision(0, "start", rootActions())) == 0) {
                        return 0.6;
                    }
                    List<String> options = blocker ? List.of("block", "pass") : List.of("pass");
                    int b = chooser.choose(new Decision(1, blocker ? "holds blocker" : "holds nothing", options));
                    if (b == SearchGame.Chooser.ROLLOUT) {
                        b = blocker && rng.nextBoolean() ? 0 : options.size() - 1;
                    }
                    return options.get(b).equals("block") ? 0 : 1;
                };
            }
        };
        SearchResult r = new IsMcts(SearchConfig.of(400).withGumbel(EVAL)).search(ambush, 9);
        assertEquals(0, r.chosen(), r.toString());
        assertNotEquals(0, r.visits(1), "the attack was tried");
    }

    /** A decision with one candidate that must be chosen is answered without search: no simulation at all. */
    @Test
    void aSingleCandidateDecisionRunsNoSimulation() {
        magicdosa.protocol.v1.DecisionRequest one = magicdosa.protocol.v1.DecisionRequest.newBuilder()
            .setType(magicdosa.protocol.v1.DecisionType.DECISION_TYPE_TARGET)
            .setConstraints(magicdosa.protocol.v1.SelectionConstraints.newBuilder().setMin(1).setMax(1))
            .addCandidates(magicdosa.protocol.v1.Candidate.newBuilder().setCardId(3)).build();
        assertTrue(ForgeSearchGame.forced(one), "ShadowGame answers it before any search starts");
    }
}
