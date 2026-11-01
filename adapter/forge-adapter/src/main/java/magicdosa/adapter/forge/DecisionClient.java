package magicdosa.adapter.forge;

import magicdosa.protocol.v1.AdapterMessage;
import magicdosa.protocol.v1.DecisionRequest;
import magicdosa.protocol.v1.DecisionResponse;
import magicdosa.protocol.v1.Hello;
import magicdosa.protocol.v1.HelloReply;

/**
 * The adapter's connection to agent-core (design: 설계에 미치는 영향 통신; Q12): {@link GrpcDecisionClient}, or in
 * process in test harnesses. Calls for one game come from that game's thread, in order.
 */
public interface DecisionClient {
    /** Game-start handshake; the game is not played unless accepted. */
    HelloReply hello(Hello hello) throws Exception;

    /** Events, turn-start snapshots and the game end, in sequence order (no reply). */
    void send(AdapterMessage message) throws Exception;

    /** Blocks until agent-core answers. */
    DecisionResponse decide(DecisionRequest request) throws Exception;
}
