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
    private final long HANG_MS = Long.getLong("magicdosa.shadowHangMs", 600_000);

    /** Totals over the games of this JVM (for the agent-core summary line). */
    private static final Stats TOTAL = new Stats();

    /** What the policy did, by decision type and reason. */
    public static final class Stats {
        public final Map<String, Integer> delegated = new TreeMap<>();
        public final Map<String, Integer> delegatedByMethod = new TreeMap<>();
        public final Map<String, Integer> withoutShadow = new TreeMap<>();
        public final Map<String, Integer> unmapped = new TreeMap<>();
        public final List<String> unmappedSamples = new ArrayList<>();
        /** Search (phase 3): what the searches and their simulations did. */
        public final Map<String, Integer> search = new TreeMap<>();
        public long searches, simulations, backedUp, searchNodes;
        /** Hang safeguard: fired, the shadow started again, a Forge thread could not be ended. */
        public int hangs, hangRestarts, hangTainted;
        public int decisions, games, shadowErrors;

        synchronized void add(Stats o) {
            o.delegated.forEach((k, v) -> delegated.merge(k, v, Integer::sum));
            o.delegatedByMethod.forEach((k, v) -> delegatedByMethod.merge(k, v, Integer::sum));
            o.withoutShadow.forEach((k, v) -> withoutShadow.merge(k, v, Integer::sum));
            o.unmapped.forEach((k, v) -> unmapped.merge(k, v, Integer::sum));
            o.search.forEach((k, v) -> search.merge(k, v, Integer::sum));
            searches += o.searches;
            simulations += o.simulations;
            backedUp += o.backedUp;
            searchNodes += o.searchNodes;
            hangs += o.hangs;
            hangRestarts += o.hangRestarts;
            hangTainted += o.hangTainted;
            decisions += o.decisions;
            games += o.games;
            shadowErrors += o.shadowErrors;
        }

        synchronized String line() {
            int d = delegated.values().stream().mapToInt(Integer::intValue).sum();
            int w = withoutShadow.values().stream().mapToInt(Integer::intValue).sum();
            int u = unmapped.values().stream().mapToInt(Integer::intValue).sum();
            return "shadow-forge-ai games=" + games + " decisions=" + decisions + " delegated=" + d + " withoutShadow=" + w
                + " unmapped=" + u + " shadowErrors=" + shadowErrors
                + (hangs == 0 ? "" : " hangs=" + hangs + " hangRestarts=" + hangRestarts + " hangTainted=" + hangTainted) + " unmappedByType=" + unmapped
                + " withoutShadowBy=" + withoutShadow
                + (searches == 0 && search.isEmpty() ? "" : " searches=" + searches + " simulations=" + simulations
                    + " backedUp=" + backedUp + " searchNodes=" + searchNodes + " search=" + search);
        }
    }

    public static String totals() {
        return TOTAL.line();
    }

    private final Hello hello;
    private final int viewer;
    private final CardKeys keys;
    private final Policy alone;
    private final magicdosa.agent.search.SearchConfig searchConfig;
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
        this(hello, viewer, keys, alone, null);
    }

    /** {@code search}: the policy searches the viewer's priority decisions (phase 3); null for the shadow Forge AI policy. */
    public LiveShadow(Hello hello, int viewer, CardKeys keys, Policy alone, magicdosa.agent.search.SearchConfig search) {
        this.searchConfig = search;
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
        boolean hung;
        synchronized (lock) {
            DecisionResponse early = decideLocked(r);
            if (early != null) {
                return early;
            }
            hung = !answered;
        }
        if (hung) {
            // outside the lock: the shadow's threads take the lock as they end
            hang(r);
            synchronized (lock) {
                pending = null;
                DecisionResponse resp = skippedLocked(r, "shadow stop: hung", magicdosa.protocol.v1.SearchSkipReason.SEARCH_SKIP_REASON_SHADOW_STOPPED);
                rec.decisions.add(new GameRecord.Decision(pendingAt, record(r, resp))); // a restarted shadow replays it
                return resp;
            }
        }
        synchronized (lock) {
            DecisionResponse a = answer;
            answer = null;
            return a;
        }
    }

    /** Waits for the shadow's answer; returns an answer made without it, or null once the shadow answered or hung. */
    private DecisionResponse decideLocked(DecisionRequest r) throws InterruptedException {
        {
            int pos = at++;
            stats.decisions++;
            if (r.getSeat() != viewer) {
                return withoutShadowLocked(r, "not the shadow's seat");
            }
            if (shadow == null) {
                // the shadow starts with the game itself (the first mulligan, or a turn under way): requests before it
                // (sideboarding, play or draw, the companion) come before the game-start snapshot
                boolean gameStarted = r.getType() == DecisionType.DECISION_TYPE_MULLIGAN
                    || r.getObservation().getGame().getTurn() > 0;
                if (!gameStarted || !hasGameStartSnapshot()) {
                    DecisionResponse resp = pregame(r);
                    if (resp == null) {
                        resp = withoutShadowLocked(r, "before the game start");
                    }
                    rec.decisions.add(new GameRecord.Decision(pos, record(r, resp)));
                    return resp;
                }
                start();
            }
            if (restartAfterHang) {
                restart();
            }
            if (shadowDone || stopped) {
                DecisionResponse resp = skippedLocked(r, "shadow stop: " + (result == null ? "stopped" : end(result)), magicdosa.protocol.v1.SearchSkipReason.SEARCH_SKIP_REASON_SHADOW_STOPPED);
                rec.decisions.add(new GameRecord.Decision(pos, record(r, resp)));
                return resp;
            }
            pending = r;
            pendingAt = pos;
            answered = false;
            lock.notifyAll();
            // safeguard only (2026-11-24 directive): it never picks a decision; a game where it fires is marked
            // outside the reproducibility guarantee
            long deadline = System.currentTimeMillis() + HANG_MS;
            while (!answered && !shadowDone) {
                long left = deadline - System.currentTimeMillis();
                if (left <= 0) {
                    return null; // hung: handled outside the lock
                }
                lock.wait(left);
            }
            if (!answered) {
                pending = null;
                DecisionResponse resp = skippedLocked(r, "shadow stop: " + (result == null ? "ended" : end(result)), magicdosa.protocol.v1.SearchSkipReason.SEARCH_SKIP_REASON_SHADOW_STOPPED);
                rec.decisions.add(new GameRecord.Decision(pos, record(r, resp)));
                return resp;
            }
            return null;
        }
    }

    // ---- the hang safeguard (2026-11-24 directive, phase-2 open risk 1) ----

    /** The hang safeguard fired in this game: its decisions are outside the reproducibility guarantee. */
    private boolean outsideReproducibility;
    /** The shadow is started again from the record at the next decision (its stuck threads were ended). */
    private boolean restartAfterHang;
    /** A stuck Forge thread could not be ended: this JVM must not run another Forge game (restart agent-core). */
    private static volatile boolean tainted;
    private final List<String> hangDumps = new ArrayList<>();

    /** Whether this game's decisions are all inside the reproducibility guarantee (no hang safeguard fired). */
    public boolean reproducible() {
        synchronized (lock) {
            return !outsideReproducibility;
        }
    }

    /** A Forge thread of an earlier game could not be ended: agent-core must be restarted before another game. */
    public static boolean tainted() {
        return tainted;
    }

    /**
     * The shadow did not answer within the safeguard: a thread dump of every thread running Forge code, the shadow
     * stopped, and its threads ended. Ended for certain (all gone), the shadow starts again from the record at the next
     * decision (one Forge thread per JVM still holds); else the shadow stays stopped for this game and agent-core is
     * marked for a restart (its remaining decisions are search skipped, cause shadow stop).
     */
    private void hang(DecisionRequest r) throws InterruptedException {
        List<Thread> forge = forgeThreads();
        StringBuilder dump = new StringBuilder("shadow hang at decision " + r.getDecisionId() + " (" + r.getType()
            + ", turn " + r.getObservation().getGame().getTurn() + "): " + forge.size() + " Forge threads\n");
        for (Thread t : forge) {
            dump.append("  thread ").append(t.getName()).append(' ').append(t.getState()).append('\n');
            StackTraceElement[] st = t.getStackTrace();
            for (int i = 0; i < Math.min(st.length, 120); i++) {
                dump.append("    at ").append(st[i]).append('\n');
            }
        }
        System.err.println("agent-core: " + hello.getGameId() + " " + dump);
        synchronized (lock) {
            outsideReproducibility = true;
            stats.hangs++;
            hangDumps.add(dump.toString());
            stopShadow("hang at decision " + r.getDecisionId());
        }
        boolean ended = endThreads(forge);
        synchronized (lock) {
            if (ended) {
                restartAfterHang = true;
                stats.hangRestarts++;
            } else {
                tainted = true;
                stats.hangTainted++;
            }
        }
    }

    /** Threads other than this one whose stack runs Forge code (one game per JVM: they are this game's shadow). */
    private List<Thread> forgeThreads() {
        List<Thread> out = new ArrayList<>();
        for (Map.Entry<Thread, StackTraceElement[]> e : Thread.getAllStackTraces().entrySet()) {
            Thread t = e.getKey();
            if (t == Thread.currentThread() || !t.isAlive()) {
                continue;
            }
            boolean runsForge = t == thread;
            for (StackTraceElement f : e.getValue()) {
                runsForge |= f.getClassName().startsWith("forge.");
            }
            if (runsForge) {
                out.add(t);
            }
        }
        return out;
    }

    /** Ends the threads; true when every one is gone. */
    @SuppressWarnings({"deprecation", "removal"})
    private static boolean endThreads(List<Thread> threads) throws InterruptedException {
        for (Thread t : threads) {
            try {
                t.stop(); // JDK 17: ThreadDeath in the thread; the shadow is rebuilt from the record anyway
            } catch (UnsupportedOperationException e) {
                return false; // a JDK without Thread.stop
            }
        }
        boolean all = true;
        for (Thread t : threads) {
            t.join(10_000);
            all &= !t.isAlive();
        }
        return all;
    }

    /** A new shadow from the record (the game start snapshot, every decision so far), after a hang ended the old one. */
    private void restart() {
        restartAfterHang = false;
        if (tainted) {
            return;
        }
        stats.withoutShadow.merge("shadow restarted after a hang", 0, Integer::sum);
        shadow = null;
        thread = null;
        result = null;
        stopped = false;
        shadowDone = false;
        start();
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
    public magicdosa.agent.search.SearchConfig search() {
        return searchConfig;
    }

    @Override
    public void searched(magicdosa.agent.search.SearchResult r, Map<String, Integer> counts) {
        synchronized (lock) {
            counts.forEach((k, v) -> stats.search.merge(k, v, Integer::sum));
            if (r != null) {
                stats.searches++;
                stats.simulations += r.iterations;
                stats.backedUp += r.determinizations;
                stats.searchNodes += r.nodes;
                r.problems.forEach((k, v) -> stats.search.merge("problem: " + (k.length() > 80 ? k.substring(0, 80) : k), v,
                    Integer::sum));
            }
        }
    }

    @Override
    public DecisionResponse withoutShadow(DecisionRequest request, String why) {
        synchronized (lock) {
            if (why.startsWith("search skipped")) {
                // recovery 3: the turn could not be followed
                return skippedLocked(request, why.substring("search skipped".length()).replaceFirst("^: ", ""),
                    magicdosa.protocol.v1.SearchSkipReason.SEARCH_SKIP_REASON_SHADOW_RECOVERY_FAILED);
            }
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
        long idle = magicdosa.agent.Timing.start(); // measurement only: the shadow waits for the real game
        while (pending == null && !over && !stopped) {
            try {
                lock.wait();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw ShadowGame.stopped("interrupted");
            }
        }
        magicdosa.agent.Timing.add("shadow.idle", idle);
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
            } else if (r.getType() == DecisionType.DECISION_TYPE_CHOOSE_CARDS && pick < 0 && !c.hasPass()
                    && r.getContext().getEnginePrompt().toLowerCase(java.util.Locale.ROOT).contains("companion")) {
                pick = i; // Forge AI always takes a legal companion (ChooseCompanionAi: one of them at random)
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

    /**
     * Search skipped (no network: the uniform random answer of {@code alone}), counted by cause and recorded with
     * the skip reason (2026-11-24 directive: "shadow stop" is a cause of search skipped, not a category of its own).
     */
    private DecisionResponse skippedLocked(DecisionRequest r, String cause, magicdosa.protocol.v1.SearchSkipReason reason) {
        String key = "search skipped: " + cause;
        stats.withoutShadow.merge(key.length() > 70 ? key.substring(0, 70) : key, 1, Integer::sum);
        DecisionResponse.Builder b = alone.decide(r).toBuilder();
        for (int i = 0; i < b.getStepsCount(); i++) {
            b.setSteps(i, b.getSteps(i).toBuilder().setMethod(b.getSteps(i).getMethod().toBuilder().setSkipped(reason)));
        }
        return b.build();
    }

    private static DecisionRecord record(DecisionRequest r, DecisionResponse resp) {
        return DecisionRecord.newBuilder().setRequest(r).setResponse(resp).build();
    }

    private boolean hasGameStartSnapshot() {
        for (GameRecord.Snapshot s : rec.snapshots) {
            // turn 0: the game start; a first snapshot of a later turn: a game that starts in a turn under way (a
            // scenario built from a snapshot)
            if (s.snapshot().getObservation().getGame().getTurn() == 0 || s == rec.snapshots.get(0)) {
                return true;
            }
        }
        return false;
    }

    /** This game's counts (tests). */
    Stats stats() {
        return stats;
    }

    /** Tests: stops the shadow (the stream ends here) and returns its result once its thread is over. */
    ShadowGame.Result stopAndResult() throws InterruptedException {
        synchronized (lock) {
            stopShadow("scenario over");
        }
        if (thread != null) {
            thread.join(60_000);
        }
        synchronized (lock) {
            return result;
        }
    }

    private void start() {
        shadow = new ShadowGame(rec, keys, this);
        thread = new Thread(() -> {
            ShadowGame.Result r;
            long t0 = magicdosa.agent.Timing.start(); // measurement only: the shadow thread from start to end
            try {
                r = shadow.runLive();
            } catch (Throwable t) {
                r = null;
                System.err.println("agent-core: shadow of " + hello.getGameId() + " failed: " + t);
            }
            magicdosa.agent.Timing.add("shadow.thread", t0);
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
        out.add("reproducible=" + !outsideReproducibility);
        hangDumps.forEach(d -> out.add("hangDump=" + d.replace('\n', '|')));
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
            r.recovery1Failed.forEach((k, n) -> out.add("shadow.recovery1Failed=" + n + " " + k));
            r.recovery2Failed.forEach((k, n) -> out.add("shadow.recovery2Failed=" + n + " " + k));
            r.skippedBy.forEach((k, n) -> out.add("shadow.skippedBy=" + n + " " + k));
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
