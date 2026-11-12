package magicdosa.agent.shadow;

import magicdosa.adapter.forge.CardKeys;
import magicdosa.agent.policy.Policy;
import magicdosa.protocol.v1.DecisionRecord;
import magicdosa.protocol.v1.DecisionRequest;
import magicdosa.protocol.v1.DecisionResponse;
import magicdosa.protocol.v1.DecisionType;
import magicdosa.protocol.v1.Event;
import magicdosa.protocol.v1.GameOver;
import magicdosa.protocol.v1.Hello;
import magicdosa.protocol.v1.LogHeader;
import magicdosa.protocol.v1.TurnStartSnapshot;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The shadow Forge AI policy for one running game (phase-2 gate, second verification ② run): a live shadow game
 * (belief samples for hidden cards, design: 그림자·탐색) follows the real game from agent-core's messages only, and the
 * newest request is answered by the shadow game's Forge AI, mapped back to the real candidates (an intended
 * delegation, design: 정책 → 탐색 → 그림자 게임의 Forge AI). Decisions the shadow cannot answer are answered by the
 * stand-in for the network alone (the random policy) and counted with the reason: before the game start (play or draw,
 * sideboarding), search skipped (recovery ③), the shadow ended. A Forge AI answer that matches no real candidate is
 * not answered at all, so the real game records an unintended fallback; it is counted by decision type here too.
 *
 * <p>Synchronous windows: the shadow thread runs only while the stream thread waits for the answer to the newest
 * request; after it hands the answer over it waits until the next request (or the game end) is in the record. Both
 * threads never touch the record at the same time. One Forge game per JVM (decision 11): agent-core with this policy
 * serves one game at a time (a worker plays its games one after another).
 */
public final class LiveShadow implements ShadowGame.Live {
    private static final AtomicInteger THREADS = new AtomicInteger();
    /** Safeguard against a hung shadow (never a decision budget): the stream thread stops waiting after this. */
    private static final long HANG_MS = Long.getLong("magicdosa.shadowHangMs", 600_000);

    /** Totals over the games of this JVM (for the agent-core summary line). */
    private static final Stats TOTAL = new Stats();

    /** What the policy did, by decision type and reason. */
    public static final class Stats {
        public final Map<String, Integer> delegated = new TreeMap<>();
        public final Map<String, Integer> delegatedByMethod = new TreeMap<>();
        public final Map<String, Integer> withoutShadow = new TreeMap<>();
        public final Map<String, Integer> unmapped = new TreeMap<>();
        public final List<String> unmappedSamples = new ArrayList<>();
        public int decisions, games, shadowErrors;

        synchronized void add(Stats o) {
            o.delegated.forEach((k, v) -> delegated.merge(k, v, Integer::sum));
            o.delegatedByMethod.forEach((k, v) -> delegatedByMethod.merge(k, v, Integer::sum));
            o.withoutShadow.forEach((k, v) -> withoutShadow.merge(k, v, Integer::sum));
            o.unmapped.forEach((k, v) -> unmapped.merge(k, v, Integer::sum));
            decisions += o.decisions;
            games += o.games;
            shadowErrors += o.shadowErrors;
        }

        synchronized String line() {
            int d = delegated.values().stream().mapToInt(Integer::intValue).sum();
            int w = withoutShadow.values().stream().mapToInt(Integer::intValue).sum();
            int u = unmapped.values().stream().mapToInt(Integer::intValue).sum();
            return "shadow-forge-ai games=" + games + " decisions=" + decisions + " delegated=" + d + " withoutShadow=" + w
                + " unmapped=" + u + " shadowErrors=" + shadowErrors + " unmappedByType=" + unmapped
                + " withoutShadowBy=" + withoutShadow;
        }
    }

    public static String totals() {
        return TOTAL.line();
    }

    private final Hello hello;
    private final int viewer;
    private final CardKeys keys;
    private final Policy alone;
    private final GameRecord rec;
    private final Stats stats = new Stats();

    private final Object lock = new Object();
    /** Position of the next message in the game's stream (GameRecord's {@code at}). */
    private int at;
    private DecisionRequest pending;
    private int pendingAt;
    private DecisionResponse answer;
    private boolean answered;
    private boolean over;
    private boolean stopped;
    private boolean shadowDone;
    private ShadowGame shadow;
    private Thread thread;
    private ShadowGame.Result result;

    /**
     * {@code alone}: answers without the shadow (the network alone; the random policy until there is a network).
     * {@code viewer}: the agent seat the shadow follows.
     */
    public LiveShadow(Hello hello, int viewer, CardKeys keys, Policy alone) {
        this.hello = hello;
        this.viewer = viewer;
        this.keys = keys;
        this.alone = alone;
        // the engine seed is hidden: the shadow's own random numbers come from the game ID
        LogHeader header = LogHeader.newBuilder().setGameId(hello.getGameId()).setMatchId(hello.getMatchId())
            .setGameNumber(hello.getGameNumber()).setMode(hello.getMode()).addAllAgentSeats(hello.getAgentSeatsList())
            .setGameSeed(hello.getGameId().hashCode() * 0x9E3779B97F4A7C15L).build();
        this.rec = GameRecord.live(header, viewer);
    }

    // ---- the stream thread ----

    public void event(Event e) {
        synchronized (lock) {
            at++;
            if (e.getViewerSeat() == viewer) {
                rec.events.put(e.getSeq(), e);
            }
        }
    }

    public void snapshot(TurnStartSnapshot s) {
        synchronized (lock) {
            int pos = at++;
            if (s.getSeat() == viewer) {
                rec.snapshots.add(new GameRecord.Snapshot(pos, s));
            }
        }
    }

    /** The answer to a request of the game (the stream thread waits while the shadow plays up to it). */
    public DecisionResponse decide(DecisionRequest r) throws InterruptedException {
        synchronized (lock) {
            int pos = at++;
            stats.decisions++;
            if (r.getSeat() != viewer) {
                return withoutShadowLocked(r, "not the shadow's seat");
            }
            if (shadow == null) {
                if (r.getType() == DecisionType.DECISION_TYPE_PLAY_OR_DRAW || r.getType() == DecisionType.DECISION_TYPE_SIDEBOARD
                        || !hasGameStartSnapshot()) {
                    DecisionResponse resp = pregame(r);
                    if (resp == null) {
                        resp = withoutShadowLocked(r, "before the game start");
                    }
                    rec.decisions.add(new GameRecord.Decision(pos, record(r, resp)));
                    return resp;
                }
                start();
            }
            if (shadowDone || stopped) {
                return withoutShadowLocked(r, "shadow ended: " + (result == null ? "stopped" : end(result)));
            }
            pending = r;
            pendingAt = pos;
            answered = false;
            lock.notifyAll();
            long deadline = System.currentTimeMillis() + HANG_MS; // safeguard only
            while (!answered && !shadowDone) {
                long left = deadline - System.currentTimeMillis();
                if (left <= 0) {
                    stopShadow("shadow hung at decision " + r.getDecisionId());
                    break;
                }
                lock.wait(left);
            }
            if (!answered) {
                pending = null;
                return withoutShadowLocked(r, "shadow ended: " + (result == null ? "hung" : end(result)));
            }
            DecisionResponse a = answer;
            answer = null;
            return a;
        }
    }

    /** The game end; waits for the shadow to finish its replay of the game (its end is compared with the real one). */
    public void gameOver(GameOver g) throws InterruptedException {
        synchronized (lock) {
            at++;
            rec.gameOver = g.toBuilder().clearPrivileged().build();
            over = true;
            pending = null;
            lock.notifyAll();
        }
        if (thread != null) {
            thread.join(HANG_MS);
            if (thread.isAlive()) {
                synchronized (lock) {
                    stopShadow("shadow did not finish after the game over");
                }
                thread.join(5_000);
            }
        }
        stats.games++;
        TOTAL.add(stats);
        report();
    }

    /** The stream ended without a game end. */
    public void abandon() {
        synchronized (lock) {
            stopShadow("stream abandoned");
        }
    }

    // ---- the shadow thread (ShadowGame.Live) ----

    @Override
    public DecisionRequest pending() {
        synchronized (lock) {
            if (stopped) {
                throw ShadowGame.stopped("stopped");
            }
            return pending;
        }
    }

    @Override
    public boolean over() {
        synchronized (lock) {
            return over;
        }
    }

    @Override
    public void answer(DecisionResponse response, String how) {
        synchronized (lock) {
            if (stopped || pending == null) {
                throw ShadowGame.stopped(stopped ? "stopped" : "no request to answer");
            }
            if (response.getStepsCount() > 0
                    && response.getSteps(0).getDecider() == magicdosa.protocol.v1.Decider.DECIDER_INTENDED_DELEGATION) {
                stats.delegated.merge(pending.getType().name().replace("DECISION_TYPE_", ""), 1, Integer::sum);
                stats.delegatedByMethod.merge(how.replace("shadow Forge AI: ", ""), 1, Integer::sum);
            }
            hand(response);
        }
    }

    @Override
    public DecisionResponse withoutShadow(DecisionRequest request, String why) {
        synchronized (lock) {
            return withoutShadowLocked(request, why);
        }
    }

    @Override
    public void unmapped(DecisionRequest request, String engineDecision, String why) {
        synchronized (lock) {
            if (stopped || pending == null) {
                throw ShadowGame.stopped(stopped ? "stopped" : "no request to answer");
            }
            stats.unmapped.merge(request.getType().name().replace("DECISION_TYPE_", "") + " " + engineDecision, 1, Integer::sum);
            if (stats.unmappedSamples.size() < 20) {
                stats.unmappedSamples.add("decision " + request.getDecisionId() + " " + request.getType() + " ("
                    + engineDecision + "): " + why);
            }
            // no valid answer: the real game falls back (an unintended fallback, recorded there)
            hand(DecisionResponse.newBuilder().setGameId(request.getGameId()).setDecisionId(ShadowGame.UNKNOWN_ANSWER).build());
        }
    }

    /** Hands the answer to the stream thread and waits for the next request or the game end. Holds the lock. */
    private void hand(DecisionResponse response) {
        rec.decisions.add(new GameRecord.Decision(pendingAt, record(pending, response)));
        pending = null;
        answer = response;
        answered = true;
        lock.notifyAll();
        while (pending == null && !over && !stopped) {
            try {
                lock.wait();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw ShadowGame.stopped("interrupted");
            }
        }
        if (stopped) {
            throw ShadowGame.stopped("stopped");
        }
    }

    // ---- helpers ----

    /**
     * Before the game start there is no shadow game: Forge AI's own rules answer (PlayerControllerAi plays first;
     * Forge AI seats keep their main deck between games, 2026-11-06). Null for any other request.
     */
    private DecisionResponse pregame(DecisionRequest r) {
        int pick = -1;
        for (int i = 0; i < r.getCandidatesCount(); i++) {
            var c = r.getCandidates(i);
            if (r.getType() == DecisionType.DECISION_TYPE_PLAY_OR_DRAW && c.hasPlay() && c.getPlay()) {
                pick = i;
            } else if (r.getType() == DecisionType.DECISION_TYPE_SIDEBOARD && c.hasPass()) {
                pick = i;
            }
        }
        if (pick < 0) {
            return null;
        }
        stats.delegated.merge(r.getType().name().replace("DECISION_TYPE_", ""), 1, Integer::sum);
        stats.delegatedByMethod.merge("pregame rule", 1, Integer::sum);
        return DecisionResponse.newBuilder().setGameId(r.getGameId()).setDecisionId(r.getDecisionId()).addChosen(pick)
            .addSteps(magicdosa.protocol.v1.DecisionStep.newBuilder()
                .setDecider(magicdosa.protocol.v1.Decider.DECIDER_INTENDED_DELEGATION)).build();
    }

    private DecisionResponse withoutShadowLocked(DecisionRequest r, String why) {
        stats.withoutShadow.merge(why.length() > 60 ? why.substring(0, 60) : why, 1, Integer::sum);
        return alone.decide(r);
    }

    private static DecisionRecord record(DecisionRequest r, DecisionResponse resp) {
        return DecisionRecord.newBuilder().setRequest(r).setResponse(resp).build();
    }

    private boolean hasGameStartSnapshot() {
        for (GameRecord.Snapshot s : rec.snapshots) {
            if (s.snapshot().getObservation().getGame().getTurn() == 0) {
                return true;
            }
        }
        return false;
    }

    private void start() {
        shadow = new ShadowGame(rec, keys, this);
        thread = new Thread(() -> {
            ShadowGame.Result r;
            try {
                r = shadow.runLive();
            } catch (Throwable t) {
                r = null;
                System.err.println("agent-core: shadow of " + hello.getGameId() + " failed: " + t);
            }
            synchronized (lock) {
                result = r;
                shadowDone = true;
                if (r == null || r.error != null) {
                    stats.shadowErrors++;
                }
                lock.notifyAll();
            }
        }, "Game-shadow-live-" + THREADS.incrementAndGet());
        thread.setDaemon(true);
        thread.start();
    }

    private void stopShadow(String why) {
        if (!stopped) {
            stopped = true;
            if (shadow != null) {
                shadow.stop();
            }
            stats.withoutShadow.merge(why.length() > 60 ? why.substring(0, 60) : why, 0, Integer::sum);
            lock.notifyAll();
        }
    }

    private static String end(ShadowGame.Result r) {
        String e = r.error != null ? "error" : r.end;
        return e.length() > 50 ? e.substring(0, 50) : e;
    }

    /** One file per game (-Dmagicdosa.shadowStats=<dir>) with the policy's counters and the shadow's measurements. */
    private void report() {
        String dir = System.getProperty("magicdosa.shadowStats");
        if (dir == null) {
            return;
        }
        ShadowGame.Result r = result;
        List<String> out = new ArrayList<>();
        out.add("game=" + hello.getGameId());
        out.add("decisions=" + stats.decisions);
        out.add("delegated=" + stats.delegated);
        out.add("delegatedByMethod=" + stats.delegatedByMethod);
        out.add("withoutShadow=" + stats.withoutShadow);
        out.add("unmapped=" + stats.unmapped);
        stats.unmappedSamples.forEach(x -> out.add("unmappedSample=" + x));
        out.add("winner=" + (rec.gameOver == null || !rec.gameOver.hasWinnerSeat() ? "none" : rec.gameOver.getWinnerSeat())
            + " viewer=" + viewer);
        if (r != null) {
            out.add("shadow.decisions=" + r.decisions);
            out.add("shadow.matched=" + r.decisionsMatched);
            out.add("shadow.matchedAfterRecovery=" + r.decisionsMatchedAfterRecovery);
            out.add("shadow.searchSkipped=" + r.searchSkipped);
            out.add("shadow.recoveries=" + r.recoveries1 + "/" + r.recoveries2 + "/" + r.recoveries3);
            out.add("shadow.snapshots=" + r.snapshots + "/" + r.snapshotsMatched);
            out.add("shadow.events=" + r.events + "/" + r.eventsMatched);
            out.add("shadow.samples=" + r.samples2 + " sampleFailures=" + r.sampleFailures);
            out.add("shadow.sameEnd=" + r.sameEnd);
            out.add("shadow.end=" + r.end.replace('\n', ' '));
            out.add("shadow.error=" + (r.error == null ? "" : r.error.replace('\n', ' ')));
            r.samples.forEach(x -> out.add("shadow.sample=" + x.replace('\n', ' ')));
        } else {
            out.add("shadow.error=" + (shadow == null ? "not started" : "failed"));
        }
        try {
            Files.createDirectories(Path.of(dir));
            Files.write(Path.of(dir, hello.getGameId() + ".txt"), out, StandardCharsets.UTF_8);
        } catch (IOException e) {
            System.err.println("agent-core: shadow stats of " + hello.getGameId() + " not written: " + e);
        }
    }
}
