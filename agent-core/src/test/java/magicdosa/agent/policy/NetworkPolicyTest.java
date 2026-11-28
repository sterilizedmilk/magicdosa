package magicdosa.agent.policy;

import magicdosa.agent.model.Evaluator;
import magicdosa.protocol.v1.Block;
import magicdosa.protocol.v1.Candidate;
import magicdosa.protocol.v1.Decider;
import magicdosa.protocol.v1.DecisionRequest;
import magicdosa.protocol.v1.DecisionResponse;
import magicdosa.protocol.v1.DecisionStep;
import magicdosa.protocol.v1.PairLimit;
import magicdosa.protocol.v1.Prior;
import magicdosa.protocol.v1.SearchSkipReason;
import magicdosa.protocol.v1.SelectionConstraints;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The network-alone policy takes the policy head's most probable candidate at every step, within the constraints. */
class NetworkPolicyTest {

    /** A stand-in network: each candidate's score is fixed (index -1 is "done"); priors are the scores, unnormalised. */
    private static final class Scores implements Evaluator {
        final double[] score;
        final double done;
        final List<List<Integer>> asked = new ArrayList<>();

        Scores(double done, double... score) {
            this.score = score;
            this.done = done;
        }

        @Override
        public Evaluation evaluate(DecisionRequest request, List<Integer> candidates, List<Integer> chosen) {
            asked.add(List.copyOf(candidates));
            float[] p = new float[candidates.size()];
            for (int k = 0; k < p.length; k++) {
                p[k] = (float) (candidates.get(k) < 0 ? done : score[candidates.get(k)]);
            }
            return new Evaluation(p, 0.5);
        }

        @Override
        public void close() {
        }
    }

    private static DecisionRequest request(int n, int min, int max) {
        DecisionRequest.Builder r = DecisionRequest.newBuilder().setGameId("g").setDecisionId(7)
            .setConstraints(SelectionConstraints.newBuilder().setMin(min).setMax(max));
        for (int i = 0; i < n; i++) {
            r.addCandidates(Candidate.newBuilder().setCardId(100 + i));
        }
        return r.build();
    }

    /** 2026-11-28: at temperature 1 a step is drawn from the policy, from the request's seed. */
    @Test
    void temperatureOneSamplesThePolicyFromTheSeed() {
        Scores net = new Scores(0, 0.1, 0.6, 0.3);
        NetworkPolicy t1 = new NetworkPolicy(net, "m.onnx", 1.0);
        assertEquals("network:m.onnx@T=1.0", t1.modelVersion());
        assertEquals("network:m.onnx", new NetworkPolicy(net, "m.onnx").modelVersion());
        int[] counts = new int[3];
        int n = 6000;
        for (int s = 0; s < n; s++) {
            DecisionRequest r = request(3, 1, 1).toBuilder().setSeed(1000 + s).build();
            List<Integer> a = t1.decide(r).getChosenList();
            assertEquals(a, t1.decide(r).getChosenList(), "the same seed gives the same sample");
            counts[a.get(0)]++;
        }
        double[] p = {0.1, 0.6, 0.3};
        for (int k = 0; k < 3; k++) {
            assertEquals(p[k], counts[k] / (double) n, 0.03, "candidate " + k + ": " + java.util.Arrays.toString(counts));
        }
        // a low temperature sharpens towards the argmax
        java.util.SplittableRandom rng = new java.util.SplittableRandom(3);
        int top = 0;
        for (int i = 0; i < 1000; i++) {
            top += NetworkPolicy.sample(new float[] {0.1f, 0.6f, 0.3f}, 0.1, rng) == 1 ? 1 : 0;
        }
        assertTrue(top > 990, "T=0.1: " + top);
    }

    @Test
    void singleChoiceTakesTheMostProbable() {
        Scores net = new Scores(0, 0.1, 0.6, 0.3);
        DecisionResponse resp = new NetworkPolicy(net, "m.onnx").decide(request(3, 1, 1));
        assertEquals(List.of(1), resp.getChosenList());
        assertEquals(1, resp.getStepsCount());
        DecisionStep step = resp.getSteps(0);
        assertEquals(Decider.DECIDER_MODEL, step.getDecider());
        assertEquals(Prior.PRIOR_LEARNED_HEAD, step.getMethod().getPrior());
        assertEquals(SearchSkipReason.SEARCH_SKIP_REASON_NOT_AVAILABLE, step.getMethod().getSkipped());
        assertEquals(3, step.getCandidatesCount(), "no done in a choose-one");
        assertEquals(1, step.getChosen());
    }

    @Test
    void tiesGoToTheEarlierCandidate() {
        assertEquals(List.of(0), new NetworkPolicy(new Scores(0, 0.5, 0.5), "m").decide(request(2, 1, 1)).getChosenList());
    }

    @Test
    void multiSelectStopsWhenDoneIsMostProbable() {
        // 2 beats done beats 0 and 1: pick 2, then done
        Scores net = new Scores(0.4, 0.1, 0.2, 0.9);
        DecisionResponse resp = new NetworkPolicy(net, "m").decide(request(3, 0, 3));
        assertEquals(List.of(2), resp.getChosenList());
        assertEquals(2, resp.getStepsCount());
        assertEquals(List.of(List.of(0, 1, 2, -1), List.of(0, 1, -1)), net.asked);
        DecisionStep last = resp.getSteps(1);
        assertTrue(last.getCandidates(last.getChosen()).getDone());
    }

    @Test
    void doneNotOfferedBelowTheMinimum() {
        Scores net = new Scores(1.0, 0.1, 0.2, 0.3);
        DecisionResponse resp = new NetworkPolicy(net, "m").decide(request(3, 2, 3));
        assertEquals(List.of(2, 1), resp.getChosenList());
        assertEquals(List.of(List.of(0, 1, 2), List.of(0, 1), List.of(0, -1)), net.asked);
    }

    @Test
    void oneCandidateNeedsNoNetwork() {
        Scores net = new Scores(0, 1.0);
        assertEquals(List.of(0), new NetworkPolicy(net, "m").decide(request(1, 1, 1)).getChosenList());
        assertTrue(net.asked.isEmpty());
    }

    @Test
    void pairLimitsHold() {
        // blocker 1 may block one attacker: after 1→10, the other pair with blocker 1 is not offered
        DecisionRequest r = DecisionRequest.newBuilder().setGameId("g").setDecisionId(1)
            .setConstraints(SelectionConstraints.newBuilder().setMin(0).setMax(3)
                .addPairLimits(PairLimit.newBuilder().setId(1).setMax(1)))
            .addCandidates(Candidate.newBuilder().setBlock(Block.newBuilder().setBlockerId(1).setAttackerId(10)))
            .addCandidates(Candidate.newBuilder().setBlock(Block.newBuilder().setBlockerId(1).setAttackerId(11)))
            .addCandidates(Candidate.newBuilder().setBlock(Block.newBuilder().setBlockerId(2).setAttackerId(11)))
            .build();
        Scores net = new Scores(0.0, 0.9, 0.8, 0.5);
        DecisionResponse resp = new NetworkPolicy(net, "m").decide(r);
        assertEquals(List.of(0, 2), resp.getChosenList());
        assertEquals(List.of(2, -1), net.asked.get(1));
    }
}
