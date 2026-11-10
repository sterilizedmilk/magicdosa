package magicdosa.adapter.forge;

import magicdosa.protocol.v1.DecisionType;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Per-game decision counts for the fallback gate (design: 폴백 검증 집계): decisions by agent-core per type, intended
 * delegations per Forge method, and every unintended fallback with its reason. Game-thread only.
 */
public final class DecisionStats {
    private final Map<DecisionType, Integer> decided = new TreeMap<>();
    private final Map<String, Integer> delegated = new TreeMap<>();
    private final List<String> fallbacks = new ArrayList<>();
    private int autoPasses;
    int translationErrors;
    /** Priority decisions whose left-out actions were checked against Forge's own pool payment, and omissions found. */
    public int candidateChecks;
    public int candidateOmissions;
    /** Game copies made by the sampled checks (agent-core's GameCopier, as shadow games will) and those that failed. */
    public int copies;
    public int copyFailures;
    /**
     * chooseSingleStaticAbility (the order cost reductions apply in, 601.2f): calls that only computed a cost, calls
     * in real payments, real payments with two or more reductions, and those whose final cost depended on the order.
     */
    int costOrderComputations;
    int costOrderRealCalls;
    int costOrderMulti;
    int costOrderDiffered;
    int sendFailures;

    void decided(DecisionType type) {
        decided.merge(type, 1, Integer::sum);
    }

    private final Map<String, Integer> unclassified = new TreeMap<>();

    /** A decision method not classified yet was called (Forge AI answered). */
    void unclassified(String method) {
        unclassified.merge(method, 1, Integer::sum);
    }

    public Map<String, Integer> unclassified() {
        return unclassified;
    }

    void delegated(String engineDecision, DecisionType type) {
        delegated.merge(engineDecision, 1, Integer::sum);
        delegatedByType.merge(type, 1, Integer::sum);
    }

    private final Map<DecisionType, Integer> delegatedByType = new TreeMap<>();
    private final Map<DecisionType, Integer> rejectedByType = new TreeMap<>();
    /** Q14: PRIORITY requests, and those with a candidate other than pass and mana abilities. */
    int priorityAsks;
    int priorityAsksWithAction;
    /** Time the adapter waited for agent-core's answers (gRPC and agent-core), for the throughput baseline. */
    long decideNanos;
    /** Priority windows where the same action reached the repeat cap (runaway loops; 2026-11-06 plan section 4). */
    int safetyCaps;

    public Map<DecisionType, Integer> delegatedByType() {
        return delegatedByType;
    }

    public Map<DecisionType, Integer> rejectedByType() {
        return rejectedByType;
    }

    public int priorityAsks() {
        return priorityAsks;
    }

    public int priorityAsksWithAction() {
        return priorityAsksWithAction;
    }

    public long decideNanos() {
        return decideNanos;
    }

    public int safetyCaps() {
        return safetyCaps;
    }

    void fallback(String what) {
        fallbacks.add(what);
    }

    private final Map<String, Integer> notes = new TreeMap<>();
    private final Map<String, Integer> rejected = new TreeMap<>();

    /**
     * Why the engine refused an answer (2026-11-06, deck item 1). An ignored constraint is an agent-core bug: the
     * answer broke a rule the request stated. A missing constraint is a rule the request could not state (or a
     * candidate the adapter offered that was not playable after all). An engine bug is a refusal of a legal answer.
     */
    public enum RejectionKind { MISSING_CONSTRAINT, IGNORED_CONSTRAINT, ENGINE_BUG }

    private final Map<RejectionKind, Integer> rejectionKinds = new TreeMap<>();

    /** An answer the engine refused and that was asked again (Q15). */
    void rejected(String engineDecision, DecisionType type, RejectionKind kind) {
        rejectedByType.merge(type, 1, Integer::sum);
        rejected.merge(engineDecision + ": " + kind, 1, Integer::sum);
        rejectionKinds.merge(kind, 1, Integer::sum);
    }

    public int rejections(RejectionKind kind) {
        return rejectionKinds.getOrDefault(kind, 0);
    }

    public Map<String, Integer> rejected() {
        return rejected;
    }

    /** Why a fallback happened, in more detail than its reason. */
    public void note(String what) {
        notes.merge(what, 1, Integer::sum);
    }

    public Map<String, Integer> notes() {
        return notes;
    }

    void autoPass() {
        autoPasses++;
    }

    public Map<DecisionType, Integer> decided() {
        return decided;
    }

    public Map<String, Integer> delegated() {
        return delegated;
    }

    public List<String> fallbacks() {
        return fallbacks;
    }

    public int autoPasses() {
        return autoPasses;
    }

    public int candidateChecks() {
        return candidateChecks;
    }

    public int costOrderComputations() {
        return costOrderComputations;
    }

    public int costOrderRealCalls() {
        return costOrderRealCalls;
    }

    public int costOrderMulti() {
        return costOrderMulti;
    }

    public int costOrderDiffered() {
        return costOrderDiffered;
    }

    public int copies() {
        return copies;
    }

    public int copyFailures() {
        return copyFailures;
    }

    public int candidateOmissions() {
        return candidateOmissions;
    }

    public int errors() {
        return translationErrors + sendFailures;
    }

    /** Adds another game's counts. */
    public void add(DecisionStats o) {
        o.decided.forEach((k, v) -> decided.merge(k, v, Integer::sum));
        o.delegated.forEach((k, v) -> delegated.merge(k, v, Integer::sum));
        o.unclassified.forEach((k, v) -> unclassified.merge(k, v, Integer::sum));
        o.delegatedByType.forEach((k, v) -> delegatedByType.merge(k, v, Integer::sum));
        o.rejectedByType.forEach((k, v) -> rejectedByType.merge(k, v, Integer::sum));
        priorityAsks += o.priorityAsks;
        priorityAsksWithAction += o.priorityAsksWithAction;
        decideNanos += o.decideNanos;
        safetyCaps += o.safetyCaps;
        fallbacks.addAll(o.fallbacks);
        o.notes.forEach((k, v) -> notes.merge(k, v, Integer::sum));
        o.rejected.forEach((k, v) -> rejected.merge(k, v, Integer::sum));
        o.rejectionKinds.forEach((k, v) -> rejectionKinds.merge(k, v, Integer::sum));
        autoPasses += o.autoPasses;
        translationErrors += o.translationErrors;
        candidateChecks += o.candidateChecks;
        candidateOmissions += o.candidateOmissions;
        copies += o.copies;
        costOrderComputations += o.costOrderComputations;
        costOrderRealCalls += o.costOrderRealCalls;
        costOrderMulti += o.costOrderMulti;
        costOrderDiffered += o.costOrderDiffered;
        copyFailures += o.copyFailures;
        sendFailures += o.sendFailures;
    }
}
