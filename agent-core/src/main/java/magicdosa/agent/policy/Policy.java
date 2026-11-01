package magicdosa.agent.policy;

import magicdosa.protocol.v1.DecisionRequest;
import magicdosa.protocol.v1.DecisionResponse;

/**
 * Answers a decision request (design: 결정 주체 원칙). Every step of the response records its decider and method
 * (design: 폴백 검증 "결정별 기록"); a policy never answers as an unintended fallback.
 */
public interface Policy {
    /** Name reported as the model version in the handshake and the logs. */
    String modelVersion();

    /** Must be a pure function of the request (its seed included), so that runs with the same seeds repeat. */
    DecisionResponse decide(DecisionRequest request);
}
