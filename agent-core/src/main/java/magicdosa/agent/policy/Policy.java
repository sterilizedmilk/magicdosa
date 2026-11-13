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

    /**
     * The encoder version the policy's model was trained with (a model records it, design: 버전). A game is not started
     * when it is not agent-core's own. A policy without a model (random, Forge AI) works with any.
     */
    default int encoderVersion() {
        return magicdosa.agent.AgentCore.ENCODER_VERSION;
    }

    /** Must be a pure function of the request (its seed included), so that runs with the same seeds repeat. */
    DecisionResponse decide(DecisionRequest request);
}
