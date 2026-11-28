package magicdosa.agent.search;

import magicdosa.agent.encoder.ShardWriter;
import magicdosa.protocol.v1.CandidateStats;
import magicdosa.protocol.v1.DecisionStep;
import magicdosa.protocol.v1.Method;
import magicdosa.protocol.v1.SearchSkipReason;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.SplittableRandom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Protocol 17 (2026-11-28 user directive): every searched step records N, Q and the prior per candidate and the root
 * estimate, and every step a search status, so Python can build targets.
 */
class SearchStatsTest {

    /** Seat 0 picks one of three; candidate 2 is never available after the first look, so it may stay unvisited. */
    private record Three() implements SearchGame {
        @Override
        public int rootSeat() {
            return 0;
        }

        @Override
        public List<String> rootActions() {
            return List.of("a", "b", "c");
        }

        @Override
        public Simulation determinize(SplittableRandom rng) {
            return chooser -> {
                int i = chooser.choose(new Decision(0, "root", rootActions()));
                return rng.nextDouble() < (i == 1 ? 0.7 : 0.3) ? 1 : 0;
            };
        }
    }

    @Test
    void eachCandidateGetsItsVisitsAndMeanValue() {
        SearchResult r = new IsMcts(SearchConfig.of(60)).search(new Three(), 5);
        List<CandidateStats> stats = SearchResult.candidateStats(r.steps.get(0));
        assertEquals(3, stats.size());
        long n = 0;
        for (int i = 0; i < 3; i++) {
            CandidateStats c = stats.get(i);
            assertEquals(r.visits(i), c.getVisits());
            assertEquals(c.getVisits() > 0, c.hasMeanValue(), "Q is unset exactly when N is 0");
            if (c.hasMeanValue()) {
                assertEquals(r.steps.get(0).mean()[i], c.getMeanValue(), 1e-6);
                assertTrue(c.getMeanValue() >= 0 && c.getMeanValue() <= 1);
            }
            assertFalse(c.hasPrior(), "a uniform prior is not recorded");
            n += c.getVisits();
        }
        assertEquals(60, n);
    }

    @Test
    void theRootEstimateIsTheNetworksWhenGivenElseTheRootMean() {
        SearchResult r = new IsMcts(SearchConfig.of(30)).search(new Three(), 5);
        assertEquals(r.rootValue, r.method().getRan().getRootEstimate(), 1e-6);
        r.withRootEstimate(0.25);
        assertEquals(0.25f, r.method().getRan().getRootEstimate(), 1e-6);
    }

    @Test
    void searchStatusPerStep() {
        SearchResult r = new IsMcts(SearchConfig.of(9)).search(new Three(), 5);
        assertEquals(ShardWriter.STATUS_COMPLETED, ShardWriter.searchStatus(DecisionStep.newBuilder().setMethod(r.method()).build()));
        assertEquals(ShardWriter.STATUS_SKIPPED, ShardWriter.searchStatus(DecisionStep.newBuilder().setMethod(
            Method.newBuilder().setSkipped(SearchSkipReason.SEARCH_SKIP_REASON_SINGLE_CANDIDATE)).build()));
        assertEquals(ShardWriter.STATUS_ABORTED, ShardWriter.searchStatus(DecisionStep.newBuilder().setMethod(
            Method.newBuilder().setSkipped(SearchSkipReason.SEARCH_SKIP_REASON_SHADOW_STOPPED)).build()));
        assertEquals(ShardWriter.STATUS_NONE, ShardWriter.searchStatus(DecisionStep.getDefaultInstance()));
    }
}
