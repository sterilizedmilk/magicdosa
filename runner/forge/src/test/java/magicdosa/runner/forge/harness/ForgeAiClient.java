package magicdosa.runner.forge.harness;

import magicdosa.adapter.forge.DelegatingClient;
import magicdosa.protocol.Versions;
import magicdosa.protocol.v1.AdapterMessage;
import magicdosa.protocol.v1.DecisionRequest;
import magicdosa.protocol.v1.DecisionResponse;
import magicdosa.protocol.v1.Hello;
import magicdosa.protocol.v1.HelloReply;

import java.util.List;

/**
 * Win-rate baseline for the shadow Forge AI policy (phase 2): the agent seat is played by the real game's own Forge AI
 * through the same decision layer (every request delegated in process, Forge AI's answer mapped to a candidate), so the
 * two runs differ only in where Forge AI looks: the real game here, the live shadow there. Agent spec {@code forge-ai}.
 */
final class ForgeAiClient implements DelegatingClient {
    @Override
    public HelloReply hello(Hello hello) {
        return HelloReply.newBuilder().setAccepted(true).setProtocolVersion(Versions.PROTOCOL)
            .setEncoderVersion(magicdosa.agent.AgentCore.ENCODER_VERSION).setModelVersion("real-forge-ai").build();
    }

    @Override
    public void send(AdapterMessage message) {
        // the real game is at hand
    }

    @Override
    public DecisionResponse decide(DecisionRequest request) {
        return DecisionResponse.getDefaultInstance();
    }

    @Override
    public boolean delegateLast() {
        return true;
    }

    @Override
    public void delegated(DecisionRequest request, List<Integer> chosen, String engineDecision) {
        // recorded by the session as an intended delegation
    }

    @Override
    public void unmapped(DecisionRequest request, String engineDecision) {
        // recorded by the session as an unintended fallback
    }
}
