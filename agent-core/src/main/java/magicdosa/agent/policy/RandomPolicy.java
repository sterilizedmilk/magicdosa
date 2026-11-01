package magicdosa.agent.policy;

import magicdosa.protocol.v1.Candidate;
import magicdosa.protocol.v1.Decider;
import magicdosa.protocol.v1.DecisionRequest;
import magicdosa.protocol.v1.DecisionResponse;
import magicdosa.protocol.v1.DecisionStep;
import magicdosa.protocol.v1.Method;
import magicdosa.protocol.v1.PairLimit;
import magicdosa.protocol.v1.Prior;
import magicdosa.protocol.v1.SearchSkipReason;
import magicdosa.protocol.v1.SelectionConstraints;
import magicdosa.protocol.v1.StepCandidate;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.SplittableRandom;
import java.util.function.IntConsumer;
import java.util.function.IntPredicate;

/**
 * Uniform random policy (phase-1 gate: random policy, zero unintended fallbacks). Recorded as the model with a uniform
 * prior and no search ({@link SearchSkipReason#SEARCH_SKIP_REASON_NOT_AVAILABLE}). Multi-selects are made one step at a
 * time with "done" (Q9); pair selections (attackers, blockers) keep the combination rules of the constraints (Q15).
 * Random numbers come from the request's seed only.
 */
public final class RandomPolicy implements Policy {
    private static final Method UNIFORM = Method.newBuilder().setPrior(Prior.PRIOR_UNIFORM)
        .setSkipped(SearchSkipReason.SEARCH_SKIP_REASON_NOT_AVAILABLE).build();

    @Override
    public String modelVersion() {
        return "random";
    }

    @Override
    public DecisionResponse decide(DecisionRequest request) {
        SplittableRandom rnd = new SplittableRandom(request.getSeed());
        SelectionConstraints c = request.getConstraints();
        List<Integer> target = c.getRequiredIdsCount() > 0 || c.getPairLimitsCount() > 0
            ? pairSelection(request, rnd) : null;
        DecisionResponse.Builder resp = DecisionResponse.newBuilder().setGameId(request.getGameId())
            .setDecisionId(request.getDecisionId());
        List<Integer> remaining = new ArrayList<>();
        for (int i = 0; i < request.getCandidatesCount(); i++) {
            remaining.add(i);
        }
        int picked = 0;
        while (picked < c.getMax() && !remaining.isEmpty()) {
            boolean doneAllowed = picked >= c.getMin() && !(c.getMin() == 1 && c.getMax() == 1);
            int pick;
            if (target != null) {
                pick = picked < target.size() ? remaining.indexOf(target.get(picked)) : remaining.size();
            } else {
                pick = rnd.nextInt(remaining.size() + (doneAllowed ? 1 : 0));
            }
            DecisionStep.Builder step = DecisionStep.newBuilder().setDecider(Decider.DECIDER_MODEL).setMethod(UNIFORM);
            for (int i : remaining) {
                step.addCandidates(StepCandidate.newBuilder().setIndex(i));
            }
            if (doneAllowed) {
                step.addCandidates(StepCandidate.newBuilder().setDone(true));
            }
            step.setChosen(pick);
            resp.addSteps(step);
            if (pick == remaining.size()) {
                break; // done
            }
            resp.addChosen(remaining.remove(pick));
            picked++;
        }
        return resp.build();
    }

    /** The objects a pair candidate (attack, block) involves. */
    private static List<Long> pairIds(Candidate cand) {
        if (cand.hasAttack()) {
            List<Long> out = new ArrayList<>(List.of(cand.getAttack().getAttackerId()));
            if (cand.getAttack().getDefender().hasEntityId()) {
                out.add(cand.getAttack().getDefender().getEntityId());
            }
            return out;
        }
        if (cand.hasBlock()) {
            return List.of(cand.getBlock().getBlockerId(), cand.getBlock().getAttackerId());
        }
        return List.of();
    }

    /**
     * A random pair selection that keeps the combination rules (Q15): required objects first, then random extra
     * pairs within the per-object maximum, then objects below their minimum are completed or dropped.
     */
    private static List<Integer> pairSelection(DecisionRequest request, SplittableRandom rnd) {
        SelectionConstraints c = request.getConstraints();
        Map<Long, PairLimit> limits = new HashMap<>();
        for (PairLimit l : c.getPairLimitsList()) {
            limits.put(l.getId(), l);
        }
        int n = request.getCandidatesCount();
        List<Integer> order = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            order.add(i);
        }
        Collections.shuffle(order, new Random(rnd.nextLong()));
        List<Integer> chosen = new ArrayList<>();
        Map<Long, Integer> uses = new HashMap<>();
        IntPredicate fits = i -> {
            if (chosen.contains(i)) {
                return false;
            }
            for (long id : pairIds(request.getCandidates(i))) {
                PairLimit l = limits.get(id);
                if (l != null && l.getMax() > 0 && uses.getOrDefault(id, 0) >= l.getMax()) {
                    return false;
                }
            }
            return true;
        };
        IntConsumer add = i -> {
            chosen.add(i);
            for (long id : pairIds(request.getCandidates(i))) {
                uses.merge(id, 1, Integer::sum);
            }
        };
        for (long required : c.getRequiredIdsList()) {
            for (int i : order) {
                if (pairIds(request.getCandidates(i)).contains(required) && fits.test(i)) {
                    add.accept(i);
                    break;
                }
            }
        }
        for (int i : order) {
            if (rnd.nextBoolean() && fits.test(i)) {
                add.accept(i);
            }
        }
        for (PairLimit l : c.getPairLimitsList()) {
            int used = uses.getOrDefault(l.getId(), 0);
            if (used == 0 || used >= l.getMinIfAny()) {
                continue;
            }
            for (int i : order) {
                if (uses.getOrDefault(l.getId(), 0) >= l.getMinIfAny()) {
                    break;
                }
                if (pairIds(request.getCandidates(i)).contains(l.getId()) && fits.test(i)) {
                    add.accept(i);
                }
            }
            if (uses.getOrDefault(l.getId(), 0) < l.getMinIfAny()) {
                for (int i : new ArrayList<>(chosen)) {
                    if (pairIds(request.getCandidates(i)).contains(l.getId())) {
                        chosen.remove(Integer.valueOf(i));
                        for (long id : pairIds(request.getCandidates(i))) {
                            uses.merge(id, -1, Integer::sum);
                        }
                    }
                }
            }
        }
        return chosen.subList(0, Math.min(chosen.size(), c.getMax()));
    }
}
