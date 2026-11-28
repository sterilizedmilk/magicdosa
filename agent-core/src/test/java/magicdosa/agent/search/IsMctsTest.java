package magicdosa.agent.search;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.SplittableRandom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The search core on toy games (no Forge): budget, determinism, information sets, the opponent switch, widening. */
class IsMctsTest {

    /** One decision of seat 0; candidate i wins with probability p[i] (a coin from the simulation's random). */
    private record Bandit(double[] p) implements SearchGame {
        @Override
        public int rootSeat() {
            return 0;
        }

        @Override
        public List<String> rootActions() {
            List<String> out = new ArrayList<>();
            for (int i = 0; i < p.length; i++) {
                out.add("a" + i);
            }
            return out;
        }

        @Override
        public Simulation determinize(SplittableRandom rng) {
            return chooser -> {
                int i = chooser.choose(new Decision(0, "root", rootActions()));
                return rng.nextDouble() < p[i] ? 1 : 0;
            };
        }
    }

    /**
     * Seat 0 plays it safe (0.6) or attacks. The other seat holds a hidden card, a blocker or not (sampled 50/50); with
     * the blocker it may block (seat 0 loses) or not (seat 0 wins); without it seat 0 wins. Attacking is worth 0.5 against
     * a seat that blocks whenever it can, 0.75 against one that blocks half the time (the rollout policy here).
     */
    private static final class Ambush implements SearchGame {
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
                int a = chooser.choose(new Decision(0, "start", rootActions()));
                if (a == 0) {
                    return 0.6;
                }
                List<String> options = blocker ? List.of("block", "pass") : List.of("pass");
                // the other seat's information set: what it holds
                int b = chooser.choose(new Decision(1, blocker ? "holds blocker" : "holds nothing", options));
                if (b == SearchGame.Chooser.ROLLOUT) {
                    b = blocker && rng.nextBoolean() ? 0 : options.size() - 1;
                }
                return options.get(b).equals("block") ? 0 : 1;
            };
        }
    }

    @Test
    void findsTheBestCandidateAndReportsVisitRatios() {
        SearchResult r = new IsMcts(SearchConfig.of(400).withGumbel(null)).search(new Bandit(new double[] {0.3, 0.8, 0.4}), 7);
        assertEquals(1, r.chosen(), r.toString());
        assertEquals(400, r.iterations);
        assertEquals(400, r.determinizations);
        float sum = 0;
        for (float f : r.visitRatios()) {
            sum += f;
        }
        assertEquals(1f, sum, 1e-5);
        assertTrue(r.visitRatios().get(1) > 0.5f, r.toString());
        assertEquals(400, r.method().getRan().getBudget());
        assertEquals(7, r.method().getRan().getSeed());
    }

    /**
     * 2026-11-25: equal visits are told apart by the mean value, then by a pick drawn from the seed, never by candidate
     * order, so the first candidate (pass at priority) is not favoured. With one simulation per candidate every
     * candidate has one visit.
     */
    @Test
    void equalVisitsAreDecidedByMeanValueNotByCandidateOrder() {
        SearchResult r = new IsMcts(SearchConfig.of(3).withGumbel(null)).search(new Bandit(new double[] {0, 1, 0}), 11);
        assertEquals(1, r.visits(0), r.toString());
        assertEquals(1, r.visits(1), r.toString());
        assertEquals(1, r.visits(2), r.toString());
        assertEquals(1, r.chosen(), r.toString()); // not candidate 0 ("pass"): the higher mean value
        assertEquals(SearchResult.Tie.MEAN_VALUE, r.rootTie());
        // equal means too: a pick drawn from the seed, the same for the same seed, and not always candidate 0
        java.util.Set<Integer> picks = new java.util.TreeSet<>();
        for (long seed = 1; seed <= 40; seed++) {
            SearchResult same = new IsMcts(SearchConfig.of(3).withGumbel(null)).search(new Bandit(new double[] {1, 1, 1}), seed);
            assertEquals(SearchResult.Tie.SEED, same.rootTie(), same.toString());
            assertEquals(same.chosen(), new IsMcts(SearchConfig.of(3).withGumbel(null)).search(new Bandit(new double[] {1, 1, 1}), seed).chosen());
            picks.add(same.chosen());
        }
        assertEquals(java.util.Set.of(0, 1, 2), picks); // every candidate is picked for some seed
        // a clear visit leader needs no tie-break
        SearchResult clear = new IsMcts(SearchConfig.of(400).withGumbel(null)).search(new Bandit(new double[] {0.3, 0.8, 0.4}), 7);
        assertEquals(SearchResult.Tie.NONE, clear.rootTie(), clear.toString());
    }

    @Test
    void sameSeedAndBudgetSameResult() {
        Bandit g = new Bandit(new double[] {0.5, 0.52, 0.49, 0.51});
        SearchResult a = new IsMcts(SearchConfig.of(200).withGumbel(null)).search(g, 42);
        SearchResult b = new IsMcts(SearchConfig.of(200).withGumbel(null)).search(g, 42);
        assertEquals(a.toString(), b.toString());
        assertEquals(a.method(), b.method());
        SearchResult c = new IsMcts(SearchConfig.of(200).withGumbel(null)).search(g, 43);
        assertTrue(!a.toString().equals(c.toString()), "another seed should give another run");
    }

    /**
     * 2026-11-28: exact ties in the tree's UCB pick are broken by the seeded random, not by candidate order. Every action
     * is worth the same, so after each round of visits all scores tie: candidate order gave candidate 0 the extra visit
     * of every unfinished round (and so the answer); the seeded pick spreads it, and the same seed gives the same search.
     */
    @Test
    void ucbTiesAreBrokenByTheSeedNotByCandidateOrder() {
        int firstLeads = 0;
        for (long seed = 1; seed <= 20; seed++) {
            SearchResult r = new IsMcts(SearchConfig.of(31).withGumbel(null)).search(new Constant(3), seed);
            if (r.visits(0) > r.visits(1) && r.visits(0) > r.visits(2)) {
                firstLeads++;
            }
            assertEquals(r.toString(), new IsMcts(SearchConfig.of(31).withGumbel(null)).search(new Constant(3), seed).toString());
        }
        assertTrue(firstLeads < 20, "candidate 0 took the odd visit for every seed");
    }

    /** One decision of seat 0 whose every action is worth exactly 0.5 (no randomness in the value). */
    private record Constant(int n) implements SearchGame {
        @Override
        public int rootSeat() {
            return 0;
        }

        @Override
        public List<String> rootActions() {
            List<String> out = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                out.add("a" + i);
            }
            return out;
        }

        @Override
        public Simulation determinize(SplittableRandom rng) {
            return chooser -> {
                chooser.choose(new Decision(0, "root", rootActions()));
                return 0.5;
            };
        }
    }

    /** 2026-11-25: the budget by root is its actions x simulations per action x the multiplier. */
    @Test
    void theBudgetPerCandidateScalesWithTheRoot() {
        SearchConfig oneX = SearchConfig.of(1).withGumbel(null).perCandidate(12, 1);
        SearchResult r = new IsMcts(oneX).search(new Bandit(new double[] {0.5, 0.5, 0.5}), 4);
        assertEquals(36, r.budget);
        assertEquals(36, r.iterations);
        assertEquals(36, r.method().getRan().getBudget());
        SearchResult four = new IsMcts(SearchConfig.of(1).withGumbel(null).perCandidate(12, 4)).search(new Bandit(new double[] {0.5, 0.5}), 4);
        assertEquals(96, four.budget);
    }

    @Test
    void theBudgetBoundsTheTree() {
        for (long budget : new long[] {1, 10, 100}) {
            SearchResult r = new IsMcts(SearchConfig.of(budget).withGumbel(null)).search(new Ambush(), 1);
            assertEquals(budget, r.iterations);
            assertTrue(r.nodes <= budget + 1, r.toString()); // the root plus at most one node per simulation
        }
    }

    @Test
    void theOtherSeatsNodesAreItsInformationSets() {
        // in the tree, the other seat learns to block whenever it holds the blocker: attacking is worth 0.5 < 0.6
        SearchResult in = new IsMcts(SearchConfig.of(3000).withGumbel(null)).search(new Ambush(), 3);
        assertEquals(0, in.chosen(), in.toString());
        // the switch: the other seat answered by the rollout policy (blocks half the time), attacking is worth 0.75
        SearchResult out = new IsMcts(SearchConfig.of(3000).withGumbel(null).withOpponentInTree(false)).search(new Ambush(), 3);
        assertEquals(1, out.chosen(), out.toString());
    }

    @Test
    void wideningLimitsTheCandidatesTried() {
        double[] p = new double[50];
        java.util.Arrays.fill(p, 0.5);
        SearchResult r = new IsMcts(SearchConfig.of(60).withGumbel(null)).search(new Bandit(p), 5);
        int tried = 0;
        for (int i = 0; i < r.candidates(); i++) {
            tried += r.visits(i) > 0 ? 1 : 0;
        }
        assertEquals(20, tried, r.toString()); // ceil(2 * sqrt(61)) < 20: the first 20 in the node's random order
        SearchResult all = new IsMcts(new SearchConfig(60, Math.sqrt(2), true, 0, 2, 0.5).withGumbel(null)).search(new Bandit(p), 5);
        tried = 0;
        for (int i = 0; i < all.candidates(); i++) {
            tried += all.visits(i) > 0 ? 1 : 0;
        }
        assertEquals(50, tried, all.toString());
    }

    @Test
    void aSimulationThatDoesNotAskTheRootIsNotBackedUp() {
        SearchGame g = new SearchGame() {
            @Override
            public int rootSeat() {
                return 0;
            }

            @Override
            public List<String> rootActions() {
                return List.of("a0", "a1");
            }

            @Override
            public Simulation determinize(SplittableRandom rng) {
                boolean differ = rng.nextInt(4) == 0;
                return chooser -> chooser.choose(new Decision(0, "root", differ ? List.of("a0") : rootActions())) == 0 ? 1 : 0;
            }
        };
        SearchResult r = new IsMcts(SearchConfig.of(100).withGumbel(null)).search(g, 9);
        assertTrue(r.rootMismatches > 0, r.toString());
        assertEquals(100, r.determinizations + r.rootMismatches);
        assertEquals(r.rootMismatches, r.method().getRan().getFailed());
        assertEquals(0, r.chosen(), r.toString());
    }

    /** Data-run sampling of the played move: drawn in proportion to visits, never an unvisited action, same seed same pick. */
    @Test
    void sampledPlayedMoveFollowsVisitsAndSeed() {
        long[] visits = {30, 0, 10, 60};
        SearchResult.Choice fallback = new SearchResult.Choice(3, SearchResult.Tie.NONE);
        int[] counts = new int[4];
        for (long seed = 0; seed < 20_000; seed++) {
            SearchResult.Choice c = IsMcts.sampled(visits, new SplittableRandom(seed), fallback);
            counts[c.index()]++;
            assertEquals(SearchResult.Tie.SAMPLED, c.tie());
            assertEquals(c.index(), IsMcts.sampled(visits, new SplittableRandom(seed), fallback).index());
        }
        assertEquals(0, counts[1]);
        assertTrue(Math.abs(counts[0] / 20_000.0 - 0.3) < 0.02, java.util.Arrays.toString(counts));
        assertTrue(Math.abs(counts[3] / 20_000.0 - 0.6) < 0.02, java.util.Arrays.toString(counts));
        assertEquals(3, IsMcts.sampled(new long[] {0, 0}, new SplittableRandom(1), fallback).index());
    }
}
