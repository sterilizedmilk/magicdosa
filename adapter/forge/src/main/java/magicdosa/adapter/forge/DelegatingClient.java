package magicdosa.adapter.forge;

import magicdosa.protocol.v1.DecisionRequest;

import java.util.List;

/**
 * An in-process {@link DecisionClient} that may hand a decision to Forge AI of the same game (design: the shadow
 * game's Forge AI, an intended delegation; the last link of policy -> search -> shadow Forge AI). After
 * {@link #decide} the session asks {@link #delegateLast()}; when true, the decision layer answers with Forge AI, maps
 * the answer back to candidate indices of the request and reports them with {@link #delegated}. An answer that maps to
 * no candidate is reported with {@link #unmapped} (an unintended fallback, counted by decision type, never silent).
 */
public interface DelegatingClient extends DecisionClient {
    /** Whether the answer to the request just decided is "Forge AI decides". */
    boolean delegateLast();

    /** Forge AI's answer to a delegated request, as indices into its candidates. */
    void delegated(DecisionRequest request, List<Integer> chosen, String engineDecision);

    /** Forge AI's answer to a delegated request matched no candidate. */
    void unmapped(DecisionRequest request, String engineDecision);
}
