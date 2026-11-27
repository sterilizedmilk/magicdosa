package magicdosa.agent.policy;

import magicdosa.agent.model.Evaluator;
import magicdosa.agent.search.ForgeSearchGame;
import magicdosa.protocol.v1.Decider;
import magicdosa.protocol.v1.DecisionRequest;
import magicdosa.protocol.v1.DecisionResponse;
import magicdosa.protocol.v1.DecisionStep;
import magicdosa.protocol.v1.Method;
import magicdosa.protocol.v1.Prior;
import magicdosa.protocol.v1.SearchSkipReason;
import magicdosa.protocol.v1.SelectionConstraints;
import magicdosa.protocol.v1.StepCandidate;

import java.util.ArrayList;
import java.util.List;

/**
 * The network alone (gate 1 since the 2026-11-27 planning decision: "does search beat the net"): every step takes the
 * policy head's most probable candidate, no search. Recorded as the model with the learned prior and search not
 * available. Multi-selects go one step at a time with "done" (Q9), pair selections keep their objects' limits (Q15),
 * the same way the search walks a selection. Ties go to the earlier candidate; no random numbers, so the answer is a
 * pure function of the request.
 */
public final class NetworkPolicy implements Policy {
    private static final Method LEARNED = Method.newBuilder().setPrior(Prior.PRIOR_LEARNED_HEAD)
        .setSkipped(SearchSkipReason.SEARCH_SKIP_REASON_NOT_AVAILABLE).build();

    private final Evaluator network;
    private final String name;

    /** @param name reported as the model version (the model's file name) */
    public NetworkPolicy(Evaluator network, String name) {
        this.network = network;
        this.name = name;
    }

    @Override
    public String modelVersion() {
        return "network:" + name;
    }

    @Override
    public DecisionResponse decide(DecisionRequest request) {
        SelectionConstraints c = request.getConstraints();
        DecisionResponse.Builder resp = DecisionResponse.newBuilder().setGameId(request.getGameId())
            .setDecisionId(request.getDecisionId());
        List<Integer> remaining = new ArrayList<>();
        for (int i = 0; i < request.getCandidatesCount(); i++) {
            remaining.add(i);
        }
        List<Integer> chosen = new ArrayList<>();
        while (chosen.size() < c.getMax()) {
            List<Integer> fit = ForgeSearchGame.fits(request, remaining, chosen);
            boolean doneAllowed = ForgeSearchGame.mayStop(request, chosen) && !(c.getMin() == 1 && c.getMax() == 1);
            if (fit.isEmpty() && !doneAllowed) {
                throw new IllegalStateException(request.getType() + ": no legal way to complete the selection");
            }
            if (fit.isEmpty()) {
                break;
            }
            List<Integer> stepCandidates = new ArrayList<>(fit);
            if (doneAllowed) {
                stepCandidates.add(-1);
            }
            int pick = 0;
            if (stepCandidates.size() > 1) {
                float[] priors = network.evaluate(request, stepCandidates, chosen).priors();
                for (int k = 1; k < priors.length; k++) {
                    if (priors[k] > priors[pick]) {
                        pick = k;
                    }
                }
            }
            DecisionStep.Builder step = DecisionStep.newBuilder().setDecider(Decider.DECIDER_MODEL).setMethod(LEARNED);
            for (int i : stepCandidates) {
                step.addCandidates(i < 0 ? StepCandidate.newBuilder().setDone(true) : StepCandidate.newBuilder().setIndex(i));
            }
            step.setChosen(pick);
            resp.addSteps(step);
            int idx = stepCandidates.get(pick);
            if (idx < 0) {
                break; // done
            }
            chosen.add(idx);
            remaining.remove(Integer.valueOf(idx));
        }
        return resp.addAllChosen(chosen).build();
    }
}
