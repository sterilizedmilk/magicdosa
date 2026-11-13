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
    /** Writes each finished game's training samples (Arrow shards); null when shards are off. */
    private final magicdosa.agent.encoder.ShardWriter shards;
    private final AtomicLong shardsWritten = new AtomicLong();
    private final AtomicLong shardErrors = new AtomicLong();
    private final Map<String, Hello> accepted = new ConcurrentHashMap<>();
    private final Map<String, GameSession> open = new ConcurrentHashMap<>();
    private final AtomicLong refused = new AtomicLong();
    private final AtomicLong finished = new AtomicLong();
    private final AtomicLong events = new AtomicLong();
    private final AtomicLong decisions = new AtomicLong();
    private final List<String> protocolErrors = new ArrayList<>();

    public Agent(Policy policy) {
        this(policy, null);
    }

    public Agent(Policy policy, magicdosa.agent.encoder.ShardWriter shards) {
        this(policy, shards, null);
    }

    /**
     * {@code shadowKeys}: the shadow Forge AI policy (Forge initialised in this JVM): every game gets a live shadow
     * whose Forge AI answers its first agent seat's requests; {@code policy} answers what the shadow cannot.
     */
    public Agent(Policy policy, magicdosa.agent.encoder.ShardWriter shards, magicdosa.adapter.forge.CardKeys shadowKeys) {
        this.policy = policy;
        this.shards = shards;
        this.shadowKeys = shadowKeys;
    }

    private final magicdosa.adapter.forge.CardKeys shadowKeys;

    /** The live shadow of a new game (shadow Forge AI policy), or null. */
    magicdosa.agent.shadow.LiveShadow liveShadow(Hello hello) {
        return shadowKeys == null ? null
            : new magicdosa.agent.shadow.LiveShadow(hello, hello.getAgentSeats(0), shadowKeys, policy);
    }

    magicdosa.agent.encoder.ShardWriter shards() {
        return shards;
    }

    void shardWritten(boolean ok) {
        (ok ? shardsWritten : shardErrors).incrementAndGet();
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

    private String check(Hello h) {
        if (h.getProtocolVersion() != Versions.PROTOCOL) {
            return "protocol version " + h.getProtocolVersion() + ", agent-core has " + Versions.PROTOCOL;
        }
        if (policy.encoderVersion() != AgentCore.ENCODER_VERSION) {
            // design (버전): a game does not start when a version differs; the model's tensors are another encoder's
            return "model " + policy.modelVersion() + " was trained with encoder version " + policy.encoderVersion()
                + ", agent-core has encoder version " + AgentCore.ENCODER_VERSION;
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
        GameSession s = open.remove(gameId);
        if (s != null) {
            s.abandon();
        }
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
            + events.get() + " decisions=" + decisions.get() + " protocolErrors=" + protocolErrors().size()
            + (shards == null ? "" : " shards=" + shardsWritten.get() + " shardErrors=" + shardErrors.get()
                + " oov=" + shards.vocabulary().oovCount() + " " + shards.vocabulary().oovValues().keySet())
            + (shadowKeys == null ? "" : " " + magicdosa.agent.shadow.LiveShadow.totals());
    }
}
