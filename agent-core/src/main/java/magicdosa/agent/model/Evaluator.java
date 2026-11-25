package magicdosa.agent.model;

import magicdosa.protocol.v1.DecisionRequest;

import java.util.List;

/**
 * A network's answer to one decision step (design: 학습·평가 "추론 위치"): policy priors over the step's candidates and
 * the value of the deciding seat. Only the observation in the request is read (an inference-mode model has no
 * privileged input).
 */
public interface Evaluator extends AutoCloseable {

    /**
     * @param priors softmax of the policy logits, parallel to {@code candidates}
     * @param value  P(win) of the request's deciding seat, in [0, 1]
     */
    record Evaluation(float[] priors, double value) {
    }

    /**
     * Evaluates one step of {@code request}.
     *
     * @param candidates the step's candidates: indices into the request's candidates, -1 for "done"
     * @param chosen     candidates (indices) chosen in the request's earlier steps, in order
     */
    Evaluation evaluate(DecisionRequest request, List<Integer> candidates, List<Integer> chosen);

    /** The first step of {@code request}: all its candidates, nothing chosen yet. */
    default Evaluation evaluate(DecisionRequest request) {
        List<Integer> all = new java.util.ArrayList<>();
        for (int i = 0; i < request.getCandidatesCount(); i++) {
            all.add(i);
        }
        return evaluate(request, all, List.of());
    }

    @Override
    void close();
}
