package magicdosa.agent.search;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.SplittableRandom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Tree reuse on toy games (no Forge): the next decision is found below the played action (past the other seat's node),
 * becomes the next root with its visits, inherited visits count toward the budget, and the same inputs give the same
 * search.
 */
class TreeReuseTest {

    /** Names that are already canonical (a tree keyed canonically). */
    private static final TreeReuse.Names SAME = new TreeReuse.Names() {
        @Override
        public String canonKey(String info) {
            return info;
        }

        @Override
        public String canonAction(String info, String action) {
            return action;
        }

        @Override
        public String rootCanonAction(String rootAction) {
            return rootAction;
        }
    };

    /** Seat 0 plays a or b; after a the other seat answers, then seat 0 picks x (wins 80%) or y (20%); b is worth 0.3. */
    private static final class Line implements SearchGame {
        @Override
        public int rootSeat() {
            return 0;
        }

        @Override
        public List<String> rootActions() {
            return List.of("a", "b");
        }

        @Override
        public Simulation determinize(SplittableRandom rng) {
            return chooser -> {
                if (chooser.choose(new Decision(0, "root", rootActions())) == 1) {
                    return 0.3;
                }
                chooser.choose(new Decision(1, "other", List.of("p", "q")));
                int i = chooser.choose(new Decision(0, "next", List.of("x", "y")));
                if (i == SearchGame.Chooser.ROLLOUT) {
                    i = rng.nextInt(2);
                }
                return rng.nextDouble() < (i == 0 ? 0.8 : 0.2) ? 1 : 0;
            };
        }
    }

    /** The decision after a and the other seat's answer, as the next search's root. */
    private static final class Next implements SearchGame {
        @Override
        public int rootSeat() {
            return 0;
        }

        @Override
        public List<String> rootActions() {
            return List.of("x", "y");
        }

        @Override
        public Simulation determinize(SplittableRandom rng) {
            return chooser -> {
                int i = chooser.choose(new Decision(0, "root", rootActions()));
                return rng.nextDouble() < (i == 0 ? 0.8 : 0.2) ? 1 : 0;
            };
        }
    }

    private static SearchResult reuse(long budget) {
        IsMcts first = new IsMcts(SearchConfig.of(400));
        first.search(new Line(), 3);
        TreeReuse.Previous prev = new TreeReuse.Previous(first.lastTree(), SAME, 0, "a");
        TreeReuse.Found found = TreeReuse.find(prev, "next");
        assertNotNull(found);
        assertEquals(2, found.depth(), "below the other seat's node");
        SearchResult r = new IsMcts(SearchConfig.of(budget)).search(new Next(), 11, TreeReuse.inherit(prev, found, SAME));
        assertEquals(found.visits(), r.inheritedVisits);
        return r;
    }

    @Test
    void theNextDecisionStartsFromItsSubtreeAndInheritedVisitsCountTowardTheBudget() {
        SearchResult r = reuse(300);
        assertEquals(Math.max(300 - r.inheritedVisits, 2), r.iterations);
        assertEquals(r.inheritedVisits + r.determinizations, r.visits(0) + r.visits(1));
        assertEquals(0, r.chosen(), r.toString());
    }

    @Test
    void anInheritedRootAboveTheBudgetStillPlaysOneSimulationPerAction() {
        SearchResult r = reuse(10);
        assertEquals(2, r.iterations);
    }

    @Test
    void sameInputsSameSearch() {
        SearchResult a = reuse(300);
        SearchResult b = reuse(300);
        assertEquals(a.toString(), b.toString());
    }

    @Test
    void aKeyNotBelowThePlayedActionIsNotFound() {
        IsMcts first = new IsMcts(SearchConfig.of(200));
        first.search(new Line(), 3);
        assertNull(TreeReuse.find(new TreeReuse.Previous(first.lastTree(), SAME, 0, "b"), "next"));
        assertNull(TreeReuse.find(new TreeReuse.Previous(first.lastTree(), SAME, 0, "a"), "elsewhere"));
    }
}
