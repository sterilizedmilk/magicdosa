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
 * <p>
 * Diagnosis option (2026-11-28 directive): {@code -Dmagicdosa.network.temperature=T} with T &gt; 0 samples each step from
 * the policy at temperature T (probabilities proportional to prior^(1/T), the softmax of logits / T) instead of the most
 * probable candidate, drawn from the request's seed, so reproducible mode still reproduces. 0 or unset: argmax. The
 * run's model version names it ({@link #temperatureTag}).
 */
public final class NetworkPolicy implements Policy {
    private static final Method LEARNED = Method.newBuilder().setPrior(Prior.PRIOR_LEARNED_HEAD)
        .setSkipped(SearchSkipReason.SEARCH_SKIP_REASON_NOT_AVAILABLE).build();

    /** The network-alone policy's sampling temperature (property magicdosa.network.temperature; 0: argmax). */
    public static final double TEMPERATURE = Double.parseDouble(System.getProperty("magicdosa.network.temperature", "0"));

    private final Evaluator network;
    private final String name;
    private final double temperature;

    /** @param name reported as the model version (the model's file name); argmax at every step */
    public NetworkPolicy(Evaluator network, String name) {
        this(network, name, 0);
    }

    /** @param temperature &gt; 0: each step sampled at this temperature from the request's seed; 0: argmax */
    public NetworkPolicy(Evaluator network, String name, double temperature) {
        if (temperature < 0 || Double.isNaN(temperature)) {
            throw new IllegalArgumentException("temperature " + temperature);
        }
        this.network = network;
        this.name = name;
        this.temperature = temperature;
    }

    /** What the model version adds for a sampling temperature: "@T=1.0", or nothing for argmax. */
    public static String temperatureTag(double temperature) {
        return temperature > 0 ? "@T=" + temperature : "";
    }

    @Override
    public String modelVersion() {
        return "network:" + name + temperatureTag(temperature);
    }

    /** The index drawn with probability proportional to priors[k]^(1/temperature). */
    static int sample(float[] priors, double temperature, java.util.SplittableRandom rng) {
        double[] w = new double[priors.length];
        double max = 0;
        for (float p : priors) {
            max = Math.max(max, p);
        }
        double sum = 0;
        for (int k = 0; k < w.length; k++) {
            // relative to the largest, in logs: no underflow at small temperatures
            w[k] = priors[k] <= 0 ? 0 : Math.exp((Math.log(priors[k]) - Math.log(max)) / temperature);
            sum += w[k];
        }
        if (!(sum > 0)) {
            return 0;
        }
        double r = rng.nextDouble() * sum;
        for (int k = 0; k < w.length; k++) {
            r -= w[k];
            if (r < 0) {
                return k;
            }
        }
        return w.length - 1;
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
        // sampling only: one stream per decision from its seed, a draw per step
        java.util.SplittableRandom rng = temperature > 0 ? new java.util.SplittableRandom(request.getSeed() ^ 0x6E65745447L) : null;
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
                if (rng != null) {
                    pick = sample(priors, temperature, rng);
                } else {
                    for (int k = 1; k < priors.length; k++) {
                        if (priors[k] > priors[pick]) {
                            pick = k;
                        }
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
