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
    int sendFailures;

    void decided(DecisionType type) {
        decided.merge(type, 1, Integer::sum);
    }

    void delegated(String engineDecision) {
        delegated.merge(engineDecision, 1, Integer::sum);
    }

    void fallback(String what) {
        fallbacks.add(what);
    }

    private final Map<String, Integer> notes = new TreeMap<>();
    private final Map<String, Integer> rejected = new TreeMap<>();

    /** An answer the engine refused and that was asked again (Q15). */
    void rejected(String engineDecision) {
        rejected.merge(engineDecision, 1, Integer::sum);
    }

    public Map<String, Integer> rejected() {
        return rejected;
    }

    /** Why a fallback happened, in more detail than its reason. */
    void note(String what) {
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

    public int errors() {
        return translationErrors + sendFailures;
    }

    /** Adds another game's counts. */
    public void add(DecisionStats o) {
        o.decided.forEach((k, v) -> decided.merge(k, v, Integer::sum));
        o.delegated.forEach((k, v) -> delegated.merge(k, v, Integer::sum));
        fallbacks.addAll(o.fallbacks);
        o.notes.forEach((k, v) -> notes.merge(k, v, Integer::sum));
        o.rejected.forEach((k, v) -> rejected.merge(k, v, Integer::sum));
        autoPasses += o.autoPasses;
        translationErrors += o.translationErrors;
        sendFailures += o.sendFailures;
    }
}
