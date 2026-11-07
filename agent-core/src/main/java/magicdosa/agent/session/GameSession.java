package magicdosa.agent.session;

import magicdosa.agent.policy.Policy;
import magicdosa.protocol.Versions;
import magicdosa.protocol.v1.AdapterMessage;
import magicdosa.protocol.v1.DecisionRequest;
import magicdosa.protocol.v1.DecisionResponse;
import magicdosa.protocol.v1.Event;
import magicdosa.protocol.v1.GameOver;
import magicdosa.protocol.v1.Hello;
import magicdosa.protocol.v1.Mode;
import magicdosa.protocol.v1.TurnStartSnapshot;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * One game on agent-core's side: checks what the adapter streams (design: protocol 사건 동기화) and answers decision
 * requests with the policy. Per seat, event sequence numbers must be gapless, a snapshot must reflect the last event
 * and a decision request's applied_seq must be the last event. Messages come in order from one thread at a time.
 */
public final class GameSession {
    private final Agent agent;
    private final Hello hello;
    private final Policy policy;
    private final Map<Integer, Long> lastSeq = new HashMap<>();
    private long lastDecisionId;
    private final List<magicdosa.agent.encoder.ShardWriter.Decided> decided = new java.util.ArrayList<>();
    private long events;
    private long decisions;
    private boolean over;

    GameSession(Agent agent, Hello hello, Policy policy) {
        this.agent = agent;
        this.hello = hello;
        this.policy = policy;
        for (int seat : hello.getAgentSeatsList()) {
            lastSeq.put(seat, 0L);
        }
    }

    public String gameId() {
        return hello.getGameId();
    }

    public boolean over() {
        return over;
    }

    /** Takes the next message of the game's stream; returns the response to a decision request, otherwise null. */
    public DecisionResponse accept(AdapterMessage m) throws ProtocolException {
        if (over) {
            throw error("message after game over");
        }
        switch (m.getMessageCase()) {
            case EVENT:
                event(m.getEvent());
                return null;
            case SNAPSHOT:
                snapshot(m.getSnapshot());
                return null;
            case DECISION_REQUEST:
                return decide(m.getDecisionRequest());
            case GAME_OVER:
                gameOver(m.getGameOver());
                return null;
            default:
                throw error("empty adapter message");
        }
    }

    private void event(Event e) throws ProtocolException {
        sameGame(e.getGameId());
        long last = seat(e.getViewerSeat());
        if (e.getSeq() != last + 1) {
            throw error("seat " + e.getViewerSeat() + " event seq " + e.getSeq() + " after " + last);
        }
        lastSeq.put(e.getViewerSeat(), e.getSeq());
        events++;
    }

    private void snapshot(TurnStartSnapshot s) throws ProtocolException {
        sameGame(s.getGameId());
        long last = seat(s.getSeat());
        if (s.getSeq() != last) {
            throw error("seat " + s.getSeat() + " snapshot at seq " + s.getSeq() + " but last event " + last);
        }
    }

    private DecisionResponse decide(DecisionRequest r) throws ProtocolException {
        sameGame(r.getGameId());
        long last = seat(r.getSeat());
        if (r.getProtocolVersion() != Versions.PROTOCOL) {
            throw error("decision " + r.getDecisionId() + " protocol version " + r.getProtocolVersion());
        }
        if (r.getDecisionId() <= lastDecisionId) {
            throw error("decision id " + r.getDecisionId() + " after " + lastDecisionId);
        }
        if (r.getAppliedSeq() != last) {
            throw error("decision " + r.getDecisionId() + " applied_seq " + r.getAppliedSeq() + " but last event " + last);
        }
        if (r.getCandidatesCount() == 0) {
            throw error("decision " + r.getDecisionId() + " without candidates");
        }
        lastDecisionId = r.getDecisionId();
        decisions++;
        DecisionResponse response = policy.decide(r);
        if (agent.shards() != null) {
            decided.add(new magicdosa.agent.encoder.ShardWriter.Decided(r, response));
        }
        return response;
    }

    private void gameOver(GameOver g) throws ProtocolException {
        sameGame(g.getGameId());
        // design: 학습 전용 블록 "추론 모드에서 이 블록이 들어오면 거부한다"
        if (hello.getMode() == Mode.MODE_INFERENCE && g.getPrivilegedCount() > 0) {
            throw error("privileged data in inference mode");
        }
        over = true;
        if (agent.shards() != null) {
            try {
                agent.shards().write(hello, decided, g);
                agent.shardWritten(true);
            } catch (java.io.IOException | RuntimeException e) {
                agent.shardWritten(false);
                System.err.println("agent-core: shard of " + hello.getGameId() + " not written: " + e);
            }
            decided.clear();
        }
        agent.finished(this, events, decisions);
    }

    private void sameGame(String gameId) throws ProtocolException {
        if (!gameId.equals(hello.getGameId())) {
            throw error("message for game " + gameId + " on the stream of " + hello.getGameId());
        }
    }

    private long seat(int seat) throws ProtocolException {
        Long last = lastSeq.get(seat);
        if (last == null) {
            throw error("seat " + seat + " is not an agent seat");
        }
        return last;
    }

    private ProtocolException error(String message) {
        return agent.protocolError(hello.getGameId() + ": " + message);
    }
}
