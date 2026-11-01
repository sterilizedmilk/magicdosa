package magicdosa.agent.session;

import magicdosa.agent.AgentCore;
import magicdosa.agent.policy.Policy;
import magicdosa.protocol.Versions;
import magicdosa.protocol.v1.Hello;
import magicdosa.protocol.v1.HelloReply;
import magicdosa.protocol.v1.Mode;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * agent-core's games, independent of the transport: the handshake (design: protocol 버전 검증) and one
 * {@link GameSession} per accepted game. Thread-safe; each game's messages come in order from one stream.
 */
public final class Agent {
    private final Policy policy;
    private final Map<String, Hello> accepted = new ConcurrentHashMap<>();
    private final Map<String, GameSession> open = new ConcurrentHashMap<>();
    private final AtomicLong refused = new AtomicLong();
    private final AtomicLong finished = new AtomicLong();
    private final AtomicLong events = new AtomicLong();
    private final AtomicLong decisions = new AtomicLong();
    private final List<String> protocolErrors = new ArrayList<>();

    public Agent(Policy policy) {
        this.policy = policy;
    }

    /** Accepts the game only when every version matches and the hello is complete; never plays on otherwise. */
    public HelloReply hello(Hello hello) {
        String reason = check(hello);
        if (reason == null && accepted.putIfAbsent(hello.getGameId(), hello) != null) {
            reason = "game " + hello.getGameId() + " already started";
        }
        if (reason != null) {
            refused.incrementAndGet();
        }
        return HelloReply.newBuilder().setAccepted(reason == null).setProtocolVersion(Versions.PROTOCOL)
            .setEncoderVersion(AgentCore.ENCODER_VERSION).setModelVersion(policy.modelVersion())
            .setReason(reason == null ? "" : reason).build();
    }

    private static String check(Hello h) {
        if (h.getProtocolVersion() != Versions.PROTOCOL) {
            return "protocol version " + h.getProtocolVersion() + ", agent-core has " + Versions.PROTOCOL;
        }
        if (h.getGameId().isEmpty()) {
            return "no game_id";
        }
        if (h.getMode() != Mode.MODE_TRAINING && h.getMode() != Mode.MODE_INFERENCE) {
            return "no mode";
        }
        if (h.getAgentSeatsCount() == 0) {
            return "no agent seats";
        }
        return null;
    }

    /** The session for the first message of a game's stream; the game must have been accepted and not opened yet. */
    public GameSession open(String gameId) throws ProtocolException {
        Hello hello = accepted.get(gameId);
        if (hello == null) {
            throw protocolError("game " + gameId + " was not accepted by a handshake");
        }
        GameSession s = new GameSession(this, hello, policy);
        if (open.putIfAbsent(gameId, s) != null) {
            throw protocolError("game " + gameId + " already has a stream");
        }
        return s;
    }

    void finished(GameSession s, long gameEvents, long gameDecisions) {
        open.remove(s.gameId());
        accepted.remove(s.gameId());
        finished.incrementAndGet();
        events.addAndGet(gameEvents);
        decisions.addAndGet(gameDecisions);
    }

    /** The stream of a game ended without a game over (adapter gone, or a protocol error). */
    public void abandoned(String gameId) {
        open.remove(gameId);
        accepted.remove(gameId);
    }

    ProtocolException protocolError(String message) {
        synchronized (protocolErrors) {
            protocolErrors.add(message);
        }
        return new ProtocolException(message);
    }

    public List<String> protocolErrors() {
        synchronized (protocolErrors) {
            return List.copyOf(protocolErrors);
        }
    }

    /** One line for the agent-core console. */
    public String summary() {
        return "games finished=" + finished.get() + " refused=" + refused.get() + " open=" + open.size() + " events="
            + events.get() + " decisions=" + decisions.get() + " protocolErrors=" + protocolErrors().size();
    }
}
