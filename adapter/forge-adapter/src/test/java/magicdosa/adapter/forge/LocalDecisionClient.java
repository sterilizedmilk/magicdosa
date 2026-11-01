package magicdosa.adapter.forge;

import magicdosa.agent.policy.Policy;
import magicdosa.agent.session.Agent;
import magicdosa.agent.session.GameSession;
import magicdosa.protocol.v1.AdapterMessage;
import magicdosa.protocol.v1.DecisionRequest;
import magicdosa.protocol.v1.DecisionResponse;
import magicdosa.protocol.v1.Hello;
import magicdosa.protocol.v1.HelloReply;

import java.util.HashMap;
import java.util.Map;

/**
 * agent-core's game handling called in-process, without gRPC: the same {@link Agent} checks and policy the server
 * runs, for harnesses that want one JVM (determinism, log replay). A protocol error throws, as a failed stream would.
 */
public final class LocalDecisionClient implements DecisionClient {
    private final Agent agent;
    private final Map<String, GameSession> sessions = new HashMap<>();

    public LocalDecisionClient(Policy policy) {
        agent = new Agent(policy);
    }

    public Agent agent() {
        return agent;
    }

    @Override
    public HelloReply hello(Hello hello) {
        return agent.hello(hello);
    }

    @Override
    public synchronized void send(AdapterMessage message) throws Exception {
        String gameId = message.hasGameOver() ? message.getGameOver().getGameId()
            : message.hasSnapshot() ? message.getSnapshot().getGameId() : message.getEvent().getGameId();
        session(gameId).accept(message);
        if (message.hasGameOver()) {
            sessions.remove(gameId);
        }
    }

    @Override
    public synchronized DecisionResponse decide(DecisionRequest request) throws Exception {
        return session(request.getGameId()).accept(AdapterMessage.newBuilder().setDecisionRequest(request).build());
    }

    private GameSession session(String gameId) throws Exception {
        GameSession s = sessions.get(gameId);
        if (s == null) {
            s = agent.open(gameId);
            sessions.put(gameId, s);
        }
        return s;
    }
}
