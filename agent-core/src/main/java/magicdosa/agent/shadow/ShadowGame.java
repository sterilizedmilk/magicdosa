package magicdosa.agent.shadow;

import com.google.protobuf.Descriptors.FieldDescriptor;
import com.google.protobuf.Message;
import forge.deck.Deck;
import forge.game.Game;
import forge.game.card.Card;
import forge.game.phase.PhaseHandler;
import forge.game.player.Player;
import forge.game.player.RegisteredPlayer;
import forge.game.phase.PhaseType;
import forge.game.undo.GameMemento;
import forge.game.zone.ZoneType;
import magicdosa.adapter.forge.StateCopy;
import magicdosa.adapter.forge.AgentPlayerController;
import magicdosa.adapter.forge.CardKeys;
import magicdosa.adapter.forge.DecisionClient;
import magicdosa.adapter.forge.ForgeGameSession;
import magicdosa.adapter.forge.ForgeRandom;
import magicdosa.adapter.forge.LogSink;
import magicdosa.adapter.forge.ReplayAiController;
import magicdosa.adapter.forge.ReplaySource;
import magicdosa.adapter.forge.SnapshotBuilder;
import magicdosa.protocol.v1.AdapterMessage;
import magicdosa.protocol.v1.DecisionRequest;
import magicdosa.protocol.v1.DecisionResponse;
import magicdosa.protocol.v1.Event;
import magicdosa.protocol.v1.Hello;
import magicdosa.protocol.v1.HelloReply;
import magicdosa.protocol.v1.LibraryOrder;
import magicdosa.protocol.v1.MatchPlayer;
import magicdosa.protocol.v1.Privileged;
import magicdosa.protocol.v1.PrivilegedRecord;
import magicdosa.protocol.v1.TurnStartSnapshot;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * One shadow game following a recorded real game from one seat's stream (design: 그림자·탐색; phase 2 verification
 * ①: the hidden cards are the real ones, from the log's privileged records).
 *
 * The shadow is rebuilt from the first turn-start snapshot and then runs Forge's own loop
 * ({@link PhaseHandler#mainLoopStep()}) on a thread named "Game-shadow-…" (2026-11-10 decision). The seat's controller
 * is the same decision layer the real game uses ({@link AgentPlayerController}); its requests are the sync points: the
 * shadow's request is fingerprinted against the real one and answered with the real response. The other seat is
 * replayed from its recorded actions and choices ({@link ReplayAiController}). The shadow's own events and turn-start
 * snapshots are compared with the real ones too (events as a diagnostic; snapshots count as turn-start decision
 * points). Offline every answer is already in the record, so nothing parks; live, the sources would wait.
 */
public final class ShadowGame {
    /** Unwinds the shadow thread (an Error, so that Forge's catch (Exception) blocks do not swallow it). */
    static final class Unwind extends Error {
        /**
         * END: stop; RESTORE: recovery ① (turn-start memento, the turn again); REBUILD: rebuild from a real snapshot;
         * NEXT_TURN: recovery ③ when the shadow cannot go on in this turn: the rest of the turn is decided without
         * search, and tracking resumes from the real snapshot of the next turn.
         */
        enum Kind { END, RESTORE, REBUILD, NEXT_TURN }

        final String why;
        final Kind kind;
        /** NEXT_TURN: the real turn the shadow gave up in. */
        final int turn;

        Unwind(String why, Kind kind) {
            this(why, kind, -1);
        }

        Unwind(String why, Kind kind, int turn) {
            super(why, null, false, false);
            this.why = why;
            this.kind = kind;
            this.turn = turn;
        }
    }

    /** What the run measured. */
    public static final class Result implements java.io.Serializable {
        public final String game;
        /** Decisions as first asked (the gate) and after recovery; decided without search after recovery failed. */
        public int decisions, decisionsMatched, decisionsMatchedAfterRecovery, searchSkipped;
        /** Decisions where a mana pool took the real (public) order (verification of public state; counted). */
        public int poolOrders;
        /**
         * Event-log replay (Q19): the other seat's recorded actions and public choices since the last (re)build and how
         * many of them the replay used; whether the shadow ended as the log did (winner, reason, turns); and whether the
         * game was reproduced end to end (every decision at the first try, no recovery, every recorded action of the
         * other seat used, no replay problem, the same end).
         */
        public int opponentRecorded, opponentReplayed;
        public String endReal = "", endShadow = "";
        public boolean sameEnd, reproduced;
        /** Verification ②: belief samples drawn, samples that found no fitting 60, identity swaps and their failures. */
        public int samples2, sampleFailures, identitySwaps, identitySwapFailures, sixtyChanges;
        /** Recoveries: ① memento and the turn again, ② rebuild from the turn-start snapshot, ③ search skip. */
        public int recoveries1, recoveries2, recoveries3;
        public int mementos, libraryCorrections, hostRepairs;
        public long mementoNanos, restoreNanos;
        public final Map<Fingerprint.Level, Integer> mismatchesByLevel = new TreeMap<>();
        public int criticalMismatches, minorOnlyMismatches;
        public int snapshots, snapshotsMatched;
        public int events, eventsMatched;
        public int rebuilds;
        public final List<String> gaps = new ArrayList<>();
        public final List<String> replayMismatches = new ArrayList<>();
        /** The first differences seen (a few per game), with the decision they came from. */
        public final List<String> samples = new ArrayList<>();
        public final List<String> eventSamples = new ArrayList<>();
        public String end = "";
        public String error;
        public long millis;

        Result(String game) {
            this.game = game;
        }

        /** A copy that a shadow thread still running cannot change. */
        synchronized Result copy() {
            Result r = new Result(game);
            r.decisions = decisions;
            r.decisionsMatched = decisionsMatched;
            r.decisionsMatchedAfterRecovery = decisionsMatchedAfterRecovery;
            r.searchSkipped = searchSkipped;
            r.recoveries1 = recoveries1;
            r.recoveries2 = recoveries2;
            r.recoveries3 = recoveries3;
            r.mementos = mementos;
            r.mementoNanos = mementoNanos;
            r.restoreNanos = restoreNanos;
            r.mismatchesByLevel.putAll(mismatchesByLevel);
            r.criticalMismatches = criticalMismatches;
            r.minorOnlyMismatches = minorOnlyMismatches;
            r.snapshots = snapshots;
            r.snapshotsMatched = snapshotsMatched;
            r.events = events;
            r.eventsMatched = eventsMatched;
            r.rebuilds = rebuilds;
            r.hostRepairs = hostRepairs;
            r.poolOrders = poolOrders;
            r.opponentRecorded = opponentRecorded;
            r.opponentReplayed = opponentReplayed;
            r.endReal = endReal;
            r.endShadow = endShadow;
            r.sameEnd = sameEnd;
            r.reproduced = reproduced;
            r.samples2 = samples2;
            r.sampleFailures = sampleFailures;
            r.identitySwaps = identitySwaps;
            r.identitySwapFailures = identitySwapFailures;
            r.sixtyChanges = sixtyChanges;
            r.libraryCorrections = libraryCorrections;
            r.gaps.addAll(new ArrayList<>(gaps));
            r.replayMismatches.addAll(new ArrayList<>(replayMismatches));
            r.samples.addAll(new ArrayList<>(samples));
            r.eventSamples.addAll(new ArrayList<>(eventSamples));
            r.end = end;
            r.error = error;
            return r;
        }
    }

    /**
     * Live mode (the shadow Forge AI policy): the record grows while the real game runs. The shadow runs only while
     * the real game waits for the answer to its newest request, so it never runs past the real stream; the newest
     * request is answered by the shadow game's Forge AI (an intended delegation) while the shadow tracks, otherwise
     * without the shadow (counted with the reason).
     */
    public interface Live {
        /** The newest real request, not answered yet; null once the game is over. */
        DecisionRequest pending();

        /** Whether the real game is over (its end is in the record). */
        boolean over();

        /**
         * Answers the pending request (it joins the record with its answer) and waits until the next request or the
         * game over is in the record.
         */
        void answer(DecisionResponse response, String how);

        /** An answer without the shadow (the network alone; here the random policy), counted with its reason. */
        DecisionResponse withoutShadow(DecisionRequest request, String why);

        /**
         * Forge AI's answer matched no real candidate: no answer goes to the real game (an unintended fallback there,
         * counted by decision type); the record keeps an unknown answer. Waits like {@link #answer}.
         */
        void unmapped(DecisionRequest request, String engineDecision, String why);
    }

    /** The record marks an answer the shadow does not know (an unintended fallback of the real game) with this ID. */
    static final long UNKNOWN_ANSWER = 0;

    private final Live live;
    private final GameRecord rec;
    private final CardKeys keys;
    private final boolean forcedRegeneration;
    private final boolean beliefSamples;
    /** IDs whose identity the real game has shown the viewer (identity swaps never take these cards). */
    private final Set<Long> shownIds = new java.util.HashSet<>();
    private final Result result;
    private final int viewer;
    private final int opponent;

    // cursors into the record
    private int decisionIdx;
    private final List<Event> opponentRecord = new ArrayList<>();
    private int opponentIdx;
    private final Map<Integer, Integer> shuffles = new java.util.HashMap<>();
    private final Map<Integer, List<Long>> realShuffles = new java.util.HashMap<>();
    private final Set<Integer> opponentUsed = new java.util.HashSet<>();

    private Game game;
    private ForgeGameSession session;
    private final List<StateCopy> controllers = new ArrayList<>();

    /** Everything a recovery ① needs to go back to the start of the turn. */
    private record TurnPoint(int turn, GameMemento memento, Runnable gaps, Runnable session, List<StateCopy> controllers, int decisionIdx,
                             int opponentIdx, Set<Integer> opponentUsed, Map<Integer, Integer> shuffles, long lastShadowSeq,
                             int shadowEvents) {}

    private GameMemento.Recorder recorder = new GameMemento.Recorder();
    private TurnPoint candidate;
    private TurnPoint turnStart;
    /** Recovery stage of the current turn: 0 tracking, 1 after ①, 2 after ②, 3 search skipped for the rest of it. */
    private int stage;
    /** Real decision ID -> matched, at the first attempt and as last seen (after recoveries). */
    private final Map<Long, Boolean> firstTry = new TreeMap<>(), lastTry = new TreeMap<>();
    private String stageCause = "";
    /** The turn the recovery stage belongs to: replaying that turn after a restore keeps the stage. */
    private int stageTurn = -1;
    private int lastComparedTurn = -1;

    public ShadowGame(GameRecord rec, CardKeys keys, boolean forcedRegeneration) {
        this(rec, keys, forcedRegeneration, false);
    }

    /**
     * {@code beliefSamples}: verification ② (design: 개요 2단계): the hidden cards are a belief sample instead of the
     * real ones (BeliefSampler at every (re)build); identities are swapped when the real game shows them; nothing hidden
     * is taken from the privileged records.
     */
    public ShadowGame(GameRecord rec, CardKeys keys, boolean forcedRegeneration, boolean beliefSamples) {
        this(rec, keys, forcedRegeneration, beliefSamples, null);
    }

    /** Live mode (verification ② on belief samples, the record filled as the real game runs). */
    public ShadowGame(GameRecord rec, CardKeys keys, Live live) {
        this(rec, keys, false, true, live);
    }

    private ShadowGame(GameRecord rec, CardKeys keys, boolean forcedRegeneration, boolean beliefSamples, Live live) {
        this.live = live;
        this.rec = rec;
        this.keys = keys;
        this.forcedRegeneration = forcedRegeneration;
        this.beliefSamples = beliefSamples;
        this.viewer = rec.viewer;
        this.opponent = 1 - rec.viewer;
        this.result = new Result(rec.header.getGameId() + "/seat" + rec.viewer);
    }

    private static int threads;
    /** Wall-clock limit for one shadow run: a harness safeguard against a hang, never part of a decision. */
    private static final long TIMEOUT_MS = Long.getLong("magicdosa.shadowTimeoutMs", 120_000);
    private volatile boolean stop;
    /** Recovery test (-Dmagicdosa.shadowFaultAt=N): corrupt the shadow at its N-th decision of each game. */
    private static final int FAULT_AT = Integer.getInteger("magicdosa.shadowFaultAt", 0);
    private long lastShadowSeq;
    private final List<Event> shadowEvents = new ArrayList<>();

    /** Runs the shadow to the end of the record on its own game thread. */
    public Result run() {
        tally();
        Result r = run0();
        dump();
        if (r == result) {
            tally();
        } else {
            r.decisions = firstTry.size();
            r.decisionsMatched = (int) firstTry.values().stream().filter(x -> x).count();
            r.decisionsMatchedAfterRecovery = (int) lastTry.values().stream().filter(x -> x).count();
        }
        return r;
    }

    /** Live mode: runs the shadow on the calling thread (a Game-shadow-* thread) until the real game ends. */
    public Result runLive() {
        long start = System.nanoTime(); // measurement only
        body();
        result.millis = (System.nanoTime() - start) / 1_000_000;
        tally();
        dump();
        return result;
    }

    /** Live mode: the shadow unwinds at its next question about the record (the stream was abandoned). */
    public void stop() {
        stop = true;
    }

    /** An unwind that ends the shadow (live mode: stopped from outside). */
    static Error stopped(String why) {
        return new Unwind(why, Unwind.Kind.END);
    }

    private Result run0() {
        long start = System.nanoTime(); // measurement only
        Thread t = new Thread(this::body, "Game-shadow-" + (++threads));
        t.setDaemon(true);
        t.start();
        try {
            t.join(TIMEOUT_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        if (t.isAlive()) {
            // a shadow that does not reach the end of its record is a failure; where it hangs is the diagnosis
            StringBuilder where = new StringBuilder("timeout after " + TIMEOUT_MS + " ms at");
            StackTraceElement[] st = t.getStackTrace();
            for (int i = 0; i < Math.min(12, st.length); i++) {
                where.append("\n    at ").append(st[i]);
            }
            stop = true; // the shadow unwinds at its next question about the record
            try {
                t.join(5_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            Result frozen = result.copy();
            frozen.error = where.toString();
            frozen.millis = (System.nanoTime() - start) / 1_000_000;
            return frozen;
        }
        result.millis = (System.nanoTime() - start) / 1_000_000;
        return result;
    }

    private void body() {
        try {
            ForgeRandom.install();
            ForgeRandom.seed(rec.header.getGameSeed());
            // the game-start snapshot: the last one of turn 0 (earlier ones were taken at the play/draw or
            // sideboarding requests, before the game itself started)
            int si = -1;
            for (int i = 0; i < rec.snapshots.size(); i++) {
                if (rec.snapshots.get(i).snapshot().getObservation().getGame().getTurn() == 0) {
                    si = i;
                }
            }
            if (si < 0) {
                result.end = "no turn-start snapshot";
                return;
            }
            startSnapshot = rec.snapshots.get(si);
            rebuild(startSnapshot);
            // decisions before the game start (sideboarding, play or draw) are not replayed, also when the last
            // turn-0 snapshot of this seat came before them (the other seat chose play or draw)
            while (decisionIdx < rec.decisions.size()) {
                var t = rec.decisions.get(decisionIdx).record().getRequest().getType();
                if (t != magicdosa.protocol.v1.DecisionType.DECISION_TYPE_SIDEBOARD
                        && t != magicdosa.protocol.v1.DecisionType.DECISION_TYPE_PLAY_OR_DRAW) {
                    break;
                }
                decisionIdx++;
            }
            startDecisionIdx = decisionIdx;
            long firstSeq = rec.snapshots.get(si).snapshot().getSeq();
            // an unwind is handled at the top of the loop, so that one thrown while recovering (a rebuild whose belief
            // sample does not fit: recovery 3 to the next turn start) is handled in turn
            Unwind carried = null;
            while (true) {
                try {
                    if (carried != null) {
                        Unwind u = carried;
                        carried = null;
                        if (recover(u, firstSeq)) {
                            return;
                        }
                        continue;
                    }
                    if (pregame) {
                        pregame = false;
                        pregame();
                    }
                    loop();
                    if (live != null && !live.over()) {
                        // live: the shadow game ended but the real one goes on (it diverged): recovery 3 from the
                        // next real turn start
                        result.recoveries3++;
                        carried = new Unwind("the shadow game ended before the real game", Unwind.Kind.NEXT_TURN,
                            game.getPhaseHandler().getTurn());
                        continue;
                    }
                    result.end = game.isGameOver() ? "game over" : "loop ended";
                    eventCounts(firstSeq);
                    replayVerdict();
                    return;
                } catch (Throwable t) {
                    Unwind u = unwind(t);
                    if (u == null) {
                        throw t;
                    }
                    carried = u;
                }
            }
        } catch (Throwable e) {
            Unwind u = unwind(e);
            if (u != null) {
                result.end = u.why;
                return;
            }
            result.error = e.toString();
            StackTraceElement[] st = e.getStackTrace();
            for (int i = 0; i < Math.min(8, st.length); i++) {
                result.error += "\n    at " + st[i];
            }
        }
    }

    /** Handles an unwind of the shadow; true when the shadow ends. May throw another unwind (handled in turn). */
    private boolean recover(Unwind u, long firstSeq) {
        if (u.kind == Unwind.Kind.END) {
            result.end = u.why;
            eventCounts(firstSeq);
            return true;
        }
        if (u.kind == Unwind.Kind.RESTORE && turnStart != null) {
            restore(turnStart);
            return false;
        }
        if (u.kind == Unwind.Kind.NEXT_TURN) {
            GameRecord.Snapshot next = snapshotAfter(u.turn);
            while (next == null && live != null && live.pending() != null) {
                // live: the real game has not reached a later turn start yet; its requests until then are
                // decided without the shadow (search skipped)
                DecisionRequest p = live.pending();
                live.answer(live.withoutShadow(p, "search skipped: " + u.why), "without shadow");
                next = snapshotAfter(u.turn);
            }
            if (next == null) {
                // no later turn start: the rest of the game is decided without search
                skipDecisions(rec.decisions.size());
                result.end = u.why + " (no later turn start)";
                eventCounts(firstSeq);
                return true;
            }
            int from = decisionIdx;
            rebuild(next);
            int to = decisionIdx;
            decisionIdx = from;
            skipDecisions(to);
            return false;
        }
        // recovery 2, a turn start that does not match, or forced per-turn regeneration (measurement):
        // rebuild from the real snapshot of the turn
        int turn = u.kind == Unwind.Kind.RESTORE ? game.getPhaseHandler().getTurn() : lastComparedTurn;
        GameRecord.Snapshot snap = snapshotOfTurn(turn);
        if (snap == null) {
            result.end = "no snapshot for turn " + turn;
            return true;
        }
        int keep = stage;
        rebuild(snap);
        stage = keep;
        return false;
    }

    /** The unwind behind {@code t}, also when Forge wrapped it on the way out (AbilityFactory wraps Errors). */
    private static Unwind unwind(Throwable t) {
        for (Throwable c = t; c != null; c = c.getCause()) {
            if (c instanceof Unwind u) {
                return u;
            }
        }
        return null;
    }

    /** Debugging (-Dmagicdosa.shadowDump=<dir>): both streams of each game as text, side by side by sequence number. */
    private void dump() {
        String dir = System.getProperty("magicdosa.shadowDump");
        if (dir == null) {
            return;
        }
        try {
            java.nio.file.Files.createDirectories(java.nio.file.Path.of(dir));
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
        try (var out = new java.io.PrintWriter(java.nio.file.Files.newBufferedWriter(
                java.nio.file.Path.of(dir, rec.header.getGameId() + "-seat" + viewer + ".txt")))) {
            Map<Long, Event> shadow = new TreeMap<>();
            shadowEvents.forEach(e -> shadow.put(e.getSeq(), e));
            for (long seq : rec.events.keySet()) {
                Event r = rec.events.get(seq);
                Event sh = shadow.get(seq);
                String a = com.google.protobuf.TextFormat.shortDebugString(r.toBuilder().clearGameId().build());
                String b = sh == null ? "-" : com.google.protobuf.TextFormat.shortDebugString(sh.toBuilder().clearGameId().build());
                out.println((a.equals(b) ? "  " : "! ") + a);
                if (!a.equals(b)) {
                    out.println("  >> " + b);
                }
            }
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    private void tally() {
        result.libraryCorrections = libraryCorrections;
        result.decisions = firstTry.size();
        result.decisionsMatched = (int) firstTry.values().stream().filter(x -> x).count();
        result.decisionsMatchedAfterRecovery = (int) lastTry.values().stream().filter(x -> x).count();
    }

    private int startDecisionIdx;

    /**
     * Verification ②: which library card a draw takes is public (its entity ID), its identity is not. The cards the
     * real stream takes from the seat's library after {@code fromSeq}, up to the next shuffle, go on top in that order
     * (design: 내 드로우는 실제로 뽑은 카드를 라이브러리 맨 위에 놓고 적용; the identities stay the sampled ones until shown).
     */
    private void departuresOnTop(int seat, long fromSeq) {
        List<Long> leaving = departures(seat, fromSeq);
        placeOnTop(seat, leaving);
    }

    /**
     * The library cards the real stream takes from the top of the seat's library after {@code fromSeq}, or looks at
     * there (a dig: look at the top N), in that order, up to the next shuffle; a run of looks followed by a shuffle is
     * a search (the whole library), not the top. Public: which object a draw takes is known, not what it is.
     */
    private List<Long> departures(int seat, long fromSeq) {
        List<Long> leaving = new ArrayList<>();
        int keptLeft = 0;
        int bottomed = 0;
        List<Event> after = new ArrayList<>(rec.events.tailMap(fromSeq, false).values());
        // cards the viewer looks at in its own library without a reveal event (a dig: "look at the top N") are the
        // library cards among a real request's candidates, at the point of that request
        TreeMap<Long, List<Long>> looked = new TreeMap<>();
        if (seat == viewer) {
            for (GameRecord.Decision d : rec.decisions) {
                var r = d.record().getRequest();
                if (r.getAppliedSeq() < fromSeq || r.getType() != magicdosa.protocol.v1.DecisionType.DECISION_TYPE_CHOOSE_CARDS) {
                    continue;
                }
                Set<Long> inLibrary = new java.util.HashSet<>();
                for (var g : r.getObservation().getObjectsList()) {
                    if (g.getZone() == magicdosa.protocol.v1.Zone.ZONE_LIBRARY && g.getOwnerSeat() == seat) {
                        inLibrary.add(g.getId());
                    }
                }
                List<Long> ids = new ArrayList<>();
                for (var c : r.getCandidatesList()) {
                    long id = c.hasTarget() ? c.getTarget().getEntityId() : c.getCardId();
                    if (inLibrary.contains(id)) {
                        ids.add(id);
                    }
                }
                boolean search = false; // a search: a shuffle of that library follows at once
                int n = 0;
                for (Event f : rec.events.tailMap(r.getAppliedSeq(), false).values()) {
                    if (++n > 12) {
                        break;
                    }
                    if (f.getBodyCase() == Event.BodyCase.LIBRARY_SHUFFLED && f.getLibraryShuffled().getSeat() == seat) {
                        search = true;
                        break;
                    }
                }
                if (!ids.isEmpty() && !search) {
                    looked.merge(r.getAppliedSeq(), ids, (x, y) -> { x.addAll(y); return x; });
                }
            }
        }
        for (int i = 0; i < after.size(); i++) {
            Event e = after.get(i);
            while (!looked.isEmpty() && looked.firstKey() < e.getSeq()) {
                for (long id : looked.pollFirstEntry().getValue()) {
                    if (!leaving.contains(id)) {
                        leaving.add(id);
                    }
                }
            }
            if (e.getBodyCase() == Event.BodyCase.LIBRARY_SHUFFLED && e.getLibraryShuffled().getSeat() == seat) {
                break;
            }
            if (e.getBodyCase() == Event.BodyCase.CARD_REVEALED
                    && e.getCardRevealed().getReason() == magicdosa.protocol.v1.RevealReason.REVEAL_REASON_LOOKED_AT
                    && e.getCardRevealed().getZone() == magicdosa.protocol.v1.Zone.ZONE_LIBRARY && inLibraryOf(seat, e.getCardRevealed().getId())) {
                // a seat looks at its top cards (Narset, Consult the Star Charts; the viewer sees which objects even
                // when it is the other seat): they are on top in that order, unless the run of looks is a search (a
                // shuffle of that library follows)
                int k = i;
                while (k < after.size() && after.get(k).getBodyCase() == Event.BodyCase.CARD_REVEALED) {
                    k++;
                }
                boolean search = false;
                for (int f = k; f < Math.min(after.size(), k + 8); f++) {
                    if (after.get(f).getBodyCase() == Event.BodyCase.LIBRARY_SHUFFLED && after.get(f).getLibraryShuffled().getSeat() == seat) {
                        search = true;
                        break;
                    }
                }
                if (!search && !leaving.contains(e.getCardRevealed().getId())) {
                    leaving.add(e.getCardRevealed().getId());
                }
                continue;
            }
            if (e.getBodyCase() == Event.BodyCase.ZONE_MOVE && e.getZoneMove().getFrom() == magicdosa.protocol.v1.Zone.ZONE_LIBRARY
                    && e.getZoneMove().getOwnerSeat() == seat) {
                // a card searched for (a shuffle of that library follows at once) does not come from the top
                boolean searched = false;
                for (int k = i + 1; k < Math.min(after.size(), i + 6); k++) {
                    Event f = after.get(k);
                    if (f.getBodyCase() == Event.BodyCase.LIBRARY_SHUFFLED && f.getLibraryShuffled().getSeat() == seat) {
                        searched = true;
                        break;
                    }
                }
                if (!searched) {
                    leaving.add(e.getZoneMove().getPrevId());
                    if (keptLeft > 0 && --keptLeft == 0) {
                        for (int b = 0; b < bottomed; b++) {
                            leaving.add(UNKNOWN); // a card the arrangement put on the bottom: not one taken later
                        }
                        bottomed = 0;
                    }
                }
            } else if (e.getBodyCase() == Event.BodyCase.RESOLUTION_CHOICE && e.getResolutionChoice().getChosenCount() > 0) {
                // cards chosen from among the top cards (Expressive Iteration's card for the bottom): library cards of
                // this seat named by a public choice, unless the choice is a search (a shuffle follows)
                boolean search = false;
                for (int k = i + 1; k < Math.min(after.size(), i + 8); k++) {
                    Event f = after.get(k);
                    if (f.getBodyCase() == Event.BodyCase.LIBRARY_SHUFFLED && f.getLibraryShuffled().getSeat() == seat) {
                        search = true;
                        break;
                    }
                }
                if (!search) {
                    Player owner = game.getRegisteredPlayers().get(seat);
                    for (var c : e.getResolutionChoice().getChosenList()) {
                        Card card = c.getEntityId() == 0 ? null : session.cardOf(c.getEntityId());
                        if (card != null && card.getOwner() == owner && card.isInZone(ZoneType.Library)
                                && !leaving.contains(c.getEntityId())) {
                            leaving.add(c.getEntityId());
                        }
                    }
                }
            } else if (e.getBodyCase() == Event.BodyCase.LIBRARY_ARRANGED && e.getLibraryArranged().getSeat() == seat
                    && e.getLibraryArranged().getToBottom() > 0 && e.getLibraryArranged().getCardsCount() == 0) {
                // (an arrangement that names its cards is the viewer's own: they were looked at, so already listed)
                // scry: the cards looked at were on top (their IDs are hidden): those kept come out first (they are the
                // next cards taken), those put on the bottom were above the cards after them
                keptLeft = e.getLibraryArranged().getToTop();
                bottomed = e.getLibraryArranged().getToBottom();
                if (keptLeft == 0) {
                    for (int b = 0; b < bottomed; b++) {
                        leaving.add(UNKNOWN);
                    }
                    bottomed = 0;
                }
            } else if (e.getBodyCase() == Event.BodyCase.CARD_REVEALED
                    && e.getCardRevealed().getZone() == magicdosa.protocol.v1.Zone.ZONE_LIBRARY) {
                int end = i;
                while (end + 1 < after.size() && after.get(end + 1).getBodyCase() == Event.BodyCase.CARD_REVEALED) {
                    end++;
                }
                boolean search = false;
                for (int k = end + 1; k < Math.min(after.size(), end + 8); k++) {
                    Event f = after.get(k);
                    if (f.getBodyCase() == Event.BodyCase.LIBRARY_SHUFFLED && f.getLibraryShuffled().getSeat() == seat) {
                        search = true;
                        break;
                    }
                }
                if (!search) {
                    for (int k = i; k <= end; k++) {
                        if (after.get(k).getCardRevealed().getZone() == magicdosa.protocol.v1.Zone.ZONE_LIBRARY) {
                            leaving.add(after.get(k).getCardRevealed().getId());
                        }
                    }
                }
                i = end;
            }
        }
        return leaving;
    }

    /** A slot in {@link #departures} for a card whose ID is hidden (put on the bottom by a scry). */
    private static final long UNKNOWN = -1;

    /**
     * Verification ②: library cards whose position the viewer knows (put on top or bottom where it saw them) take that
     * position as the real observation shows it, after the cards that leave later were put on top.
     */
    private boolean knownPositions(int seat, magicdosa.protocol.v1.Observation o) {
        Player owner = game.getRegisteredPlayers().get(seat);
        List<Card> lib = new ArrayList<>();
        owner.getCardsIn(ZoneType.Library).forEach(lib::add);
        int n = lib.size();
        Card[] slots = new Card[n];
        Set<Card> placed = new java.util.HashSet<>();
        for (var g : o.getObjectsList()) {
            if (g.getZone() != magicdosa.protocol.v1.Zone.ZONE_LIBRARY || g.getOwnerSeat() != seat || !g.hasLibraryPosition()) {
                continue;
            }
            Card c = session.cardOf(g.getId());
            if (c == null || !lib.contains(c) || placed.contains(c)) {
                continue;
            }
            var pos = g.getLibraryPosition();
            int at = pos.hasFromTop() ? pos.getFromTop() : pos.hasFromBottom() ? n - 1 - pos.getFromBottom() : -1;
            if (at >= 0 && at < n && slots[at] == null) {
                slots[at] = c;
                placed.add(c);
            }
        }
        if (placed.isEmpty()) {
            return false;
        }
        List<Card> order = new ArrayList<>();
        java.util.Iterator<Card> rest = lib.stream().filter(c -> !placed.contains(c)).iterator();
        for (int i = 0; i < n; i++) {
            order.add(slots[i] != null ? slots[i] : rest.next());
        }
        if (!order.equals(lib)) {
            owner.getZone(ZoneType.Library).setCards(order);
            return true;
        }
        return false;
    }

    /** Whether {@code id} is a card in the seat's library in the shadow now. */
    private boolean inLibraryOf(int seat, long id) {
        Card c = session.cardOf(id);
        return c != null && c.isInZone(ZoneType.Library) && game.getRegisteredPlayers().indexOf(c.getOwner()) == seat;
    }

    private void placeOnTop(int seat, List<Long> leaving) {
        Player owner = game.getRegisteredPlayers().get(seat);
        List<Card> now = new ArrayList<>();
        owner.getCardsIn(ZoneType.Library).forEach(now::add);
        Set<Card> named = new java.util.HashSet<>();
        for (long id : leaving) {
            Card c = id == UNKNOWN ? null : session.cardOf(id);
            if (c != null) {
                named.add(c);
            }
        }
        List<Card> others = new ArrayList<>();
        for (Card c : now) {
            if (!named.contains(c)) {
                others.add(c);
            }
        }
        List<Card> top = new ArrayList<>();
        int nextOther = 0;
        for (long id : leaving) {
            Card c = id == UNKNOWN ? (nextOther < others.size() ? others.get(nextOther++) : null) : session.cardOf(id);
            if (c != null && now.contains(c) && !top.contains(c)) {
                top.add(c);
            }
        }
        if (top.isEmpty()) {
            return;
        }
        List<Card> order = new ArrayList<>(top);
        for (Card c : now) {
            if (!top.contains(c)) {
                order.add(c);
            }
        }
        owner.getZone(ZoneType.Library).setCards(order);
    }

    private static boolean publicZone(magicdosa.protocol.v1.Zone z) {
        return z == magicdosa.protocol.v1.Zone.ZONE_BATTLEFIELD || z == magicdosa.protocol.v1.Zone.ZONE_GRAVEYARD
            || z == magicdosa.protocol.v1.Zone.ZONE_EXILE || z == magicdosa.protocol.v1.Zone.ZONE_STACK
            || z == magicdosa.protocol.v1.Zone.ZONE_COMMAND;
    }

    private List<Long> libraryTop(int seat) {
        List<Long> out = new ArrayList<>();
        for (Card c : game.getRegisteredPlayers().get(seat).getCardsIn(ZoneType.Library)) {
            out.add(session.idOf(c));
            if (out.size() >= 8) {
                break;
            }
        }
        return out;
    }

    private static String candidateIds(DecisionRequest r) {
        List<String> out = new ArrayList<>();
        for (var c : r.getCandidatesList()) {
            out.add(c.hasTarget() ? "t" + c.getTarget().getEntityId() : String.valueOf(c.getCardId()));
        }
        return out.toString();
    }

    private static DecisionRequest sortedCandidates(DecisionRequest r) {
        List<magicdosa.protocol.v1.Candidate> c = new ArrayList<>(r.getCandidatesList());
        c.sort(java.util.Comparator.comparing(com.google.protobuf.TextFormat::shortDebugString));
        return r.toBuilder().clearCandidates().addAllCandidates(c).build();
    }

    /**
     * Verification ②: the cards a recorded cost uses (a card exiled from hand for evoke, a discarded card) are the
     * cards their move shows right after.
     */
    private void costIdentities(long seq, List<Long> costIds) {
        for (long id : costIds) {
            for (Event m : rec.events.tailMap(seq, false).headMap(seq + 40).values()) {
                if (m.getBodyCase() == Event.BodyCase.ZONE_MOVE && m.getZoneMove().getPrevId() == id) {
                    identity(id, m.getZoneMove().getOracleId());
                    break;
                }
            }
        }
    }

    /** Verification ②: the object {@code id} is {@code oracle} (shown by the real game); swap if the sample says otherwise. */
    private boolean identity(long id, String oracle) {
        return identity(id, oracle, true);
    }

    private boolean identity(long id, String oracle, boolean sync) {
        if (oracle.isEmpty()) {
            return false;
        }
        // the opponent's 60 is itself a sample in games 2 and 3: a card it left out comes in from the sideboard
        Card owner = session.cardOf(id);
        boolean sampledSixty = startSnapshot.snapshot().getObservation().getMatch().getGameNumber() > 1
            && owner != null && game.getRegisteredPlayers().indexOf(owner.getOwner()) != viewer;
        int r = session.swapIdentity(id, oracle, shownIds, sync, sampledSixty);
        if (r == 2) {
            result.sixtyChanges++;
        }
        shownIds.add(id);
        if (r > 0) {
            result.identitySwaps++;
        } else if (r < 0) {
            result.identitySwapFailures++;
            Card a = session.cardOf(id);
            StringBuilder where = new StringBuilder();
            if (a != null) {
                for (Card c : a.getOwner().getAllCards()) {
                    if (oracle.equals(keys.oracleId(c))) {
                        long cid = session.idOf(c);
                        where.append(" ").append(cid).append("@").append(c.getZone() == null ? "-" : c.getZone().getZoneType())
                            .append(shownIds.contains(cid) ? "(shown)" : "");
                    }
                }
            }
            sample(result.samples, "identity swap failed: " + id + " is " + oracle + " (the shadow's card there: "
                + (a == null ? "none" : a + " in " + a.getZone()) + "), cards of it:" + where);
        }
        return r > 0;
    }

    /**
     * Verification ②: the viewer looks through its library when it searches it, and the real request names the library
     * objects that qualify. Where the sample gave the qualifying identities to other library objects, those identities
     * move to the objects the real request names (which real object is which of them is not known: in candidate
     * order; the move that follows shows it). True when an identity was swapped.
     */
    private boolean searchIdentities(DecisionRequest real, DecisionRequest shadow) {
        List<Long> r = libraryCandidates(real), s = libraryCandidates(shadow);
        List<Long> missing = new ArrayList<>(r), extra = new ArrayList<>(s);
        missing.removeAll(s);
        extra.removeAll(r);
        if (missing.isEmpty() && !extra.isEmpty()) {
            return lookedAtIdentities(s, extra);
        }
        if (missing.isEmpty() || missing.size() != extra.size()) {
            return false;
        }
        boolean changed = false;
        for (int i = 0; i < missing.size(); i++) {
            Card c = session.cardOf(extra.get(i));
            String oracle = c == null ? null : keys.oracleId(c);
            if (oracle != null) {
                changed |= identity(missing.get(i), oracle, false);
            }
        }
        return changed;
    }

    /**
     * Verification ②: the viewer looks at its top cards and the real request offers fewer of them than the shadow
     * (Narset, Consult the Star Charts: the ones that qualify). A shadow candidate the real game does not offer does
     * not qualify: it takes the identity of a looked-at card that does not qualify in the shadow either, swapped from
     * deeper in the library (the looked-at cards are kept out of the swap). True when an identity was swapped.
     */
    private boolean lookedAtIdentities(List<Long> shadowCandidates, List<Long> extra) {
        List<Card> lib = new ArrayList<>();
        game.getRegisteredPlayers().get(viewer).getCardsIn(ZoneType.Library).forEach(lib::add);
        int deepest = -1;
        for (int i = 0; i < lib.size(); i++) {
            if (shadowCandidates.contains(session.idOf(lib.get(i)))) {
                deepest = i;
            }
        }
        if (deepest < 0) {
            return false;
        }
        List<Long> window = new ArrayList<>();
        List<String> ineligible = new ArrayList<>();
        for (int i = 0; i <= deepest; i++) {
            long id = session.idOf(lib.get(i));
            window.add(id);
            String o = keys.oracleId(lib.get(i));
            if (!shadowCandidates.contains(id) && o != null && !ineligible.contains(o)) {
                ineligible.add(o);
            }
        }
        if (ineligible.isEmpty()) {
            return false;
        }
        List<Long> added = new ArrayList<>();
        for (long id : window) {
            if (shownIds.add(id)) {
                added.add(id);
            }
        }
        boolean changed = false;
        try {
            for (long e : extra) {
                shownIds.remove(e);
                for (String o : ineligible) {
                    if (identity(e, o, false)) {
                        changed = true;
                        break;
                    }
                }
            }
        } finally {
            shownIds.removeAll(added);
        }
        return changed;
    }

    /**
     * Verification ②: a shadow candidate among the viewer's library cards that the real request does not offer still
     * qualifies after {@link #lookedAtIdentities} (no looked-at card was there to lend an identity): identities from
     * deeper in the library are tried one by one, the shadow's own request telling whether the card still qualifies.
     */
    private DecisionRequest notQualifying(DecisionRequest real, DecisionRequest request) {
        List<Long> r = libraryCandidates(real);
        List<Long> extra = new ArrayList<>(libraryCandidates(request));
        extra.removeAll(r);
        if (extra.isEmpty() || !r.containsAll(libraryCandidates(request).stream().filter(r::contains).toList())
                || !libraryCandidates(request).containsAll(r)) {
            return request;
        }
        List<Card> lib = new ArrayList<>();
        game.getRegisteredPlayers().get(viewer).getCardsIn(ZoneType.Library).forEach(lib::add);
        for (long e : extra) {
            Set<String> tried = new java.util.HashSet<>();
            Card ce = session.cardOf(e);
            if (ce != null) {
                tried.add(keys.oracleId(ce));
            }
            for (int li = 0; li < lib.size(); li++) {
                Card c = lib.get(li);
                long id = session.idOf(c);
                String o = keys.oracleId(c);
                if (li < 8 || id == e || r.contains(id) || o == null || !tried.add(o) || tried.size() > 12) {
                    continue;
                }
                // the cards looked at stay where they are: the top of the library (8 cards at least) is kept out of
                // the swap, so the identity comes from below it
                List<Long> keep = new ArrayList<>(r);
                for (int t = 0; t < Math.min(8, lib.size()); t++) {
                    keep.add(session.idOf(lib.get(t)));
                }
                List<Long> added = new ArrayList<>();
                for (long k : keep) {
                    if (k != e && shownIds.add(k)) {
                        added.add(k);
                    }
                }
                shownIds.remove(e);
                boolean swapped;
                try {
                    swapped = identity(e, o, false);
                } finally {
                    shownIds.removeAll(added);
                }
                if (swapped) {
                    request = session.reask(request);
                    if (!libraryCandidates(request).contains(e)) {
                        break;
                    }
                }
            }
        }
        return request;
    }

    /** The candidates of a choice that are objects in the viewer's library (by the request's observation). */
    private List<Long> libraryCandidates(DecisionRequest q) {
        Set<Long> inLibrary = new java.util.HashSet<>();
        for (var g : q.getObservation().getObjectsList()) {
            if (g.getZone() == magicdosa.protocol.v1.Zone.ZONE_LIBRARY && g.getOwnerSeat() == viewer) {
                inLibrary.add(g.getId());
            }
        }
        List<Long> out = new ArrayList<>();
        for (var c : q.getCandidatesList()) {
            long id = c.hasTarget() ? c.getTarget().getEntityId() : c.getCardId();
            if (inLibrary.contains(id)) {
                out.add(id);
            }
        }
        return out;
    }

    /** Verification ②: every identity the viewer's observation shows is taken; true when a card was swapped. */
    private boolean reconcile(magicdosa.protocol.v1.Observation o) {
        for (var g : o.getObjectsList()) {
            if (!g.getOracleId().isEmpty() && !g.getToken()) {
                shownIds.add(g.getId());
            }
        }
        boolean changed = false;
        for (var g : o.getObjectsList()) {
            if (!g.getOracleId().isEmpty() && !g.getToken()) {
                changed |= identity(g.getId(), g.getOracleId());
            }
        }
        return changed;
    }

    /** Event-log replay (Q19): did the shadow reproduce the logged game, the other seat's decisions included? */
    private void replayVerdict() {
        int used = 0;
        for (int i = 0; i < opponentRecord.size(); i++) {
            if (i < opponentIdx || opponentUsed.contains(i)) {
                used++;
            }
        }
        result.opponentRecorded = opponentRecord.size();
        result.opponentReplayed = used;
        if (rec.gameOver != null && game.isGameOver()) {
            magicdosa.protocol.v1.GameOver mine = session.outcome().toBuilder().setGameId(rec.gameOver.getGameId()).build();
            result.endReal = com.google.protobuf.TextFormat.shortDebugString(rec.gameOver);
            result.endShadow = com.google.protobuf.TextFormat.shortDebugString(mine);
            result.sameEnd = mine.equals(rec.gameOver);
        }
        boolean allFirst = !firstTry.isEmpty() && firstTry.values().stream().allMatch(x -> x)
            && firstTry.size() == rec.decisions.size() - startDecisionIdx;
        result.reproduced = allFirst && result.recoveries1 + result.recoveries2 + result.recoveries3 == 0
            && used == opponentRecord.size() && result.replayMismatches.isEmpty() && result.sameEnd;
    }

    /** Recovery ③ past the shadow: the real decisions up to {@code to} (exclusive) are decided without search. */
    private void skipDecisions(int to) {
        while (decisionIdx < to) {
            long id = rec.decisions.get(decisionIdx++).record().getRequest().getDecisionId();
            firstTry.putIfAbsent(id, false);
            lastTry.put(id, false);
            result.searchSkipped++;
        }
    }

    /** The first real turn-start snapshot after {@code turn}, or null. */
    private GameRecord.Snapshot snapshotAfter(int turn) {
        for (GameRecord.Snapshot x : rec.snapshots) {
            if (x.snapshot().getObservation().getGame().getTurn() > turn) {
                return x;
            }
        }
        return null;
    }

    private GameRecord.Snapshot snapshotOfTurn(int turn) {
        if (turn == 0) {
            return startSnapshot;
        }
        for (GameRecord.Snapshot s : rec.snapshots) {
            if (s.snapshot().getObservation().getGame().getTurn() == turn) {
                return s;
            }
        }
        return null;
    }

    /** Set by a rebuild at the game start: the shadow plays the rest of the game's start (draws, mulligans). */
    private boolean pregame;
    /** The turn the shadow was last rebuilt for: its own start of that turn is compared, not rebuilt again. */
    private int rebuiltTurn = -1;
    private GameRecord.Snapshot startSnapshot;

    /**
     * The rest of Forge's game start after the game-start snapshot (GameAction.startGame from the
     * GameEventGameStarted on): actions before the opening hands, the opening hands, mulligans, opening-hand actions,
     * the first turn. Two steps are private in Forge and are called by reflection (read-only use, nothing replaced).
     */
    private void pregame() throws Exception {
        TurnStartSnapshot first = startSnapshot.snapshot();
        Player goesFirst = game.getPlayers().get(first.getObservation().getMatch().getStartingSeat());
        java.lang.reflect.Method pre = forge.game.GameAction.class.getDeclaredMethod("runPreOpeningHandActions", Player.class);
        pre.setAccessible(true);
        pre.invoke(game.getAction(), goesFirst);
        game.setAge(forge.game.GameStage.Mulligan);
        for (Player p : game.getPlayers()) {
            p.drawCards(p.getStartingHandSize());
        }
        new forge.game.mulligan.MulliganService(goesFirst).perform();
        if (game.isGameOver()) {
            return;
        }
        game.setAge(forge.game.GameStage.Play);
        java.lang.reflect.Method open = forge.game.GameAction.class.getDeclaredMethod("runOpeningHandActions", Player.class);
        open.setAccessible(true);
        goesFirst = (Player) open.invoke(game.getAction(), goesFirst);
        game.getAction().checkStateEffects(true);
        game.getTriggerHandler().runTrigger(forge.game.trigger.TriggerType.NewGame, forge.game.ability.AbilityKey.newMap(), true);
        game.setStartingPlayer(goesFirst);
        game.getPhaseHandler().setupFirstTurn(goesFirst, null);
    }

    private void loop() {
        PhaseHandler ph = game.getPhaseHandler();
        while (!game.isGameOver()) {
            // the turn-start checkpoint: a loop boundary in the cleanup step of the turn before (2026-11-10 decision)
            if (ph.getPhase() == PhaseType.CLEANUP && game.getStack().isEmpty()) {
                candidate = checkpoint();
            }
            int turn = ph.getTurn();
            ph.mainLoopStep();
            if (ph.getTurn() != turn && candidate != null) {
                turnStart = candidate;
                candidate = null;
            }
        }
    }

    private TurnPoint checkpoint() {
        long t0 = System.nanoTime(); // measurement only
        GameMemento m = recorder.capture(game);
        List<StateCopy> ctl = new ArrayList<>();
        for (Player p : game.getRegisteredPlayers()) {
            ctl.add(StateCopy.of(p.getController(), forge.ai.PlayerControllerAi.class));
        }
        TurnPoint tp = new TurnPoint(game.getPhaseHandler().getTurn(), m, MementoGaps.capture(game), session.checkpoint(), ctl,
            decisionIdx, opponentIdx,
            new java.util.HashSet<>(opponentUsed), new java.util.HashMap<>(shuffles), lastShadowSeq, shadowEvents.size());
        result.mementos++;
        result.mementoNanos += System.nanoTime() - t0;
        return tp;
    }

    /**
     * After a GameMemento restore, a card's (lazily rebuilt) keyword abilities can point to a copy of the card made
     * during the turn that was undone (seen: Solitude's evoke spell hosted by its battlefield copy, so Forge refuses to
     * cast it from hand). Every ability, trigger and static ability of every card is given back its own card as host.
     * A GameMemento issue (upstream candidate, see the report); our code only works around it.
     */
    private int repairHosts() {
        int repaired = 0;
        List<Card> all = new ArrayList<>();
        game.getCardsInGame().forEach(all::add);
        for (Player p : game.getRegisteredPlayers()) {
            p.getCardsIn(ZoneType.Sideboard).forEach(all::add);
        }
        for (Card c : all) {
            for (var sa : c.getSpellAbilities()) {
                if (sa.getHostCard() != c && sa.getHostCard() != null && sa.getHostCard().getId() == c.getId()) {
                    sa.setHostCard(c);
                    repaired++;
                }
            }
            for (var t : c.getTriggers()) {
                if (t.getHostCard() != c && t.getHostCard() != null && t.getHostCard().getId() == c.getId()) {
                    t.setHostCard(c);
                    repaired++;
                }
            }
            for (var st : c.getStaticAbilities()) {
                if (st.getHostCard() != c && st.getHostCard() != null && st.getHostCard().getId() == c.getId()) {
                    st.setHostCard(c);
                    repaired++;
                }
            }
        }
        return repaired;
    }

    /** Recovery ①: back to the turn-start checkpoint, the record cursors with it. */
    private void restore(TurnPoint tp) {
        long t0 = System.nanoTime(); // measurement only
        tp.memento().restore();
        tp.gaps().run(); // what the memento leaves out (until maps, cards added this / last turn)
        result.hostRepairs += repairHosts();
        tp.session().run();
        tp.controllers().forEach(StateCopy::restore);
        decisionIdx = tp.decisionIdx();
        opponentIdx = tp.opponentIdx();
        opponentUsed.clear();
        opponentUsed.addAll(tp.opponentUsed());
        shuffles.clear();
        shuffles.putAll(tp.shuffles());
        lastShadowSeq = tp.lastShadowSeq();
        while (shadowEvents.size() > tp.shadowEvents()) {
            shadowEvents.remove(shadowEvents.size() - 1);
        }
        result.restoreNanos += System.nanoTime() - t0;
    }

    /** A new shadow game from a real turn-start snapshot and the hidden state recorded with it. */
    private void rebuild(GameRecord.Snapshot snap) {
        TurnStartSnapshot s = snap.snapshot();
        PrivilegedRecord hidden = rec.privilegedAt(s.getSeq());
        if (beliefSamples) {
            // cards from outside the game (a companion: in the command zone, or put into hand from there) are not
            // part of the 60
            Map<Integer, Set<String>> outside = new java.util.HashMap<>();
            Map<Long, String> known = new java.util.HashMap<>();
            for (var g : s.getObservation().getObjectsList()) {
                if (g.getZone() == magicdosa.protocol.v1.Zone.ZONE_COMMAND && !g.getOracleId().isEmpty()) {
                    outside.computeIfAbsent(g.getOwnerSeat(), k -> new java.util.HashSet<>()).add(g.getOracleId());
                }
            }
            // identities the viewer saw, followed through moves into hidden zones from public ones (the stream does
            // not repeat the identity there)
            Map<Long, String> oracleOf = new java.util.HashMap<>();
            for (var g : startSnapshot.snapshot().getObservation().getObjectsList()) {
                if (!g.getOracleId().isEmpty()) {
                    oracleOf.put(g.getId(), g.getOracleId());
                }
            }
            for (Event e : rec.events.headMap(s.getSeq(), true).values()) {
                if (e.getBodyCase() == Event.BodyCase.CARD_REVEALED && !e.getCardRevealed().getOracleId().isEmpty()) {
                    oracleOf.put(e.getCardRevealed().getId(), e.getCardRevealed().getOracleId());
                }
                if (e.getBodyCase() != Event.BodyCase.ZONE_MOVE) {
                    continue;
                }
                var m = e.getZoneMove();
                String o = !m.getOracleId().isEmpty() ? m.getOracleId() : publicZone(m.getFrom()) ? oracleOf.get(m.getPrevId()) : null;
                if (o == null) {
                    continue;
                }
                oracleOf.put(m.getId(), o);
                if (m.getOracleId().isEmpty()) {
                    known.put(m.getId(), o);
                }
                if (m.getFrom() == magicdosa.protocol.v1.Zone.ZONE_COMMAND) {
                    outside.computeIfAbsent(m.getOwnerSeat(), k -> new java.util.HashSet<>()).add(o);
                }
            }
            Privileged sampled = BeliefSampler.sample(s.getObservation(), outside, known,
                new java.util.Random(rec.header.getGameSeed() * 31 + s.getSeq()), 50);
            result.samples2++;
            if (sampled == null) {
                result.sampleFailures++;
                sample(result.samples, "no belief sample at turn " + s.getObservation().getGame().getTurn() + ": "
                    + BeliefSampler.lastFailure.get());
                result.recoveries3++;
                throw new Unwind("no belief sample fits the snapshot at turn " + s.getObservation().getGame().getTurn(),
                    Unwind.Kind.NEXT_TURN, s.getObservation().getGame().getTurn());
            }
            hidden = PrivilegedRecord.newBuilder().setSeq(s.getSeq()).setSeat(viewer).setPrivileged(sampled).build();
            shownIds.clear();
            pendingReveals.clear();
        }
        if (hidden == null) {
            throw new Unwind("no hidden state recorded at the snapshot (seq " + s.getSeq() + ")", Unwind.Kind.END);
        }
        long nextId = maxId(s, hidden) + 1;
        Map<Long, Long> prev = new java.util.HashMap<>();
        for (Event e : rec.events.headMap(s.getSeq(), true).values()) {
            if (e.getBodyCase() == Event.BodyCase.ZONE_MOVE && e.getZoneMove().getPrevId() != 0) {
                prev.put(e.getZoneMove().getId(), e.getZoneMove().getPrevId());
            }
        }
        SnapshotBuilder builder = new SnapshotBuilder(keys);
        SnapshotBuilder.Result built = builder.build(s, hidden.getPrivileged(), nextId, id -> {
            long first = id;
            while (prev.containsKey(first)) {
                first = prev.get(first);
            }
            return first;
        });
        result.rebuilds++;
        built.gaps().forEach(g -> result.gaps.add("turn " + s.getObservation().getGame().getTurn() + ": " + g));
        game = built.game();
        // Forge AI decides under a wall-clock limit; under load that changes its choices. Like the runner's
        // reproducible runs, the shadow gives it a long limit (a safeguard, never a decision budget)
        game.AI_TIMEOUT = 600;
        List<Deck> decks = new ArrayList<>();
        for (RegisteredPlayer rp : built.match().getPlayers()) {
            decks.add(rp.getDeck());
        }
        int[] won = new int[s.getObservation().getMatch().getPlayersCount()];
        for (MatchPlayer mp : s.getObservation().getMatch().getPlayersList()) {
            won[mp.getSeat()] = mp.getGamesWon();
        }
        session = new ForgeGameSession(game, rec.header.getMatchId(), rec.header.getGameNumber(), decks, won, Set.of(viewer),
            new Client(), keys, LogSink.DISCARD, rec.header.getMode(), rec.header.getGameSeed());
        for (Player p : game.getRegisteredPlayers()) {
            int seat = game.getRegisteredPlayers().indexOf(p);
            if (seat == viewer) {
                p.dangerouslySetController(new AgentPlayerController(game, p, p.getLobbyPlayer(), session));
            } else {
                p.dangerouslySetController(new ReplayAiController(game, p, p.getLobbyPlayer(), session, new Replay()));
            }
        }
        session.resume(s, built.ids(), built.effectIds(), built.nextId(), seenIds(s));
        pregame = s.getObservation().getGame().getTurn() == 0;
        rebuiltTurn = s.getObservation().getGame().getTurn();
        recorder = new GameMemento.Recorder();
        candidate = null;
        turnStart = null;
        stage = 0;
        if (!beliefSamples) {
            checkLibraries(hidden, "rebuild at turn " + s.getObservation().getGame().getTurn());
        } else {
            for (Player p : game.getRegisteredPlayers()) {
                departuresOnTop(game.getRegisteredPlayers().indexOf(p), s.getSeq());
            }
            revealsAheadAfterRebuild = s.getSeq();
        }
        // the record from here on
        long before = 0; // the last decision asked before the snapshot
        for (GameRecord.Decision d : rec.decisions) {
            if (d.at() < snap.at()) {
                before = Math.max(before, d.record().getRequest().getDecisionId());
            }
        }
        decisionIdx = 0;
        while (decisionIdx < rec.decisions.size()
                && rec.decisions.get(decisionIdx).record().getRequest().getDecisionId() <= before) {
            decisionIdx++;
        }
        shuffles.clear();
        realShuffles.clear();
        for (Event e : rec.events.tailMap(s.getSeq(), false).values()) {
            if (e.getBodyCase() == Event.BodyCase.LIBRARY_SHUFFLED) {
                realShuffles.computeIfAbsent(e.getLibraryShuffled().getSeat(), k -> new ArrayList<>()).add(e.getSeq());
            }
        }
        opponentRecord.clear();
        opponentUsed.clear();
        opponentIdx = 0;
        for (Event e : rec.events.tailMap(s.getSeq(), false).values()) {
            if (answerBearing(e, opponent)) {
                opponentRecord.add(e);
            }
        }
        ingestedSeq = rec.events.isEmpty() ? s.getSeq() : Math.max(s.getSeq(), rec.events.lastKey());
        lastComparedTurn = s.getObservation().getGame().getTurn() - 1;
        if (revealsAheadAfterRebuild >= 0) {
            revealsAhead(revealsAheadAfterRebuild);
            revealsAheadAfterRebuild = -1;
        }
    }

    /**
     * The objects whose identity the seat has seen, as its stream shows it up to the snapshot: every ID the stream
     * named with an oracle_id, and the objects the snapshot shows outside libraries (a library card the seat knows is
     * one it saw before it went there, under an earlier ID, unless the stream named it).
     */
    private Set<Long> seenIds(TurnStartSnapshot s) {
        Set<Long> out = new java.util.HashSet<>();
        for (Event e : rec.events.headMap(s.getSeq(), true).values()) {
            switch (e.getBodyCase()) {
                case ZONE_MOVE -> {
                    // a card put into a library is named to a seat that saw it before, under its old ID
                    if (!e.getZoneMove().getOracleId().isEmpty()
                            && e.getZoneMove().getTo() != magicdosa.protocol.v1.Zone.ZONE_LIBRARY) {
                        out.add(e.getZoneMove().getId());
                    }
                }
                case CARD_REVEALED -> {
                    if (!e.getCardRevealed().getOracleId().isEmpty()) {
                        out.add(e.getCardRevealed().getId());
                    }
                }
                case OBJECT_CREATED -> {
                    if (!e.getObjectCreated().getOracleId().isEmpty()) {
                        out.add(e.getObjectCreated().getId());
                    }
                }
                default -> { }
            }
        }
        for (var g : s.getObservation().getObjectsList()) {
            if (!g.getOracleId().isEmpty() && g.getZone() != magicdosa.protocol.v1.Zone.ZONE_LIBRARY) {
                out.add(g.getId());
            }
        }
        return out;
    }

    private static boolean answerBearing(Event e, int seat) {
        return switch (e.getBodyCase()) {
            case PASS_PRIORITY -> e.getPassPriority().getSeat() == seat;
            case CAST_SPELL -> e.getCastSpell().getSeat() == seat;
            case ACTIVATE_ABILITY -> e.getActivateAbility().getSeat() == seat;
            case PLAY_LAND -> e.getPlayLand().getSeat() == seat;
            case ACTIVATE_MANA_ABILITY -> e.getActivateManaAbility().getSeat() == seat;
            case DECLARE_ATTACKERS -> e.getDeclareAttackers().getSeat() == seat;
            case DECLARE_BLOCKERS -> e.getDeclareBlockers().getSeat() == seat;
            case TRIGGER_ORDER -> e.getTriggerOrder().getSeat() == seat;
            case MULLIGAN -> e.getMulligan().getSeat() == seat;
            case BOTTOM_CARDS -> e.getBottomCards().getSeat() == seat;
            case RESOLUTION_CHOICE -> e.getResolutionChoice().getSeat() == seat;
            default -> false;
        };
    }

    /** The largest entity ID the stream has named up to the snapshot, the snapshot and its hidden state included. */
    private long maxId(TurnStartSnapshot s, PrivilegedRecord hidden) {
        long[] max = {0};
        for (Event e : rec.events.headMap(s.getSeq(), true).values()) {
            maxId(e, max);
        }
        maxId(s, max);
        maxId(hidden, max);
        return max[0];
    }

    private static void maxId(Message m, long[] max) {
        for (Map.Entry<FieldDescriptor, Object> e : m.getAllFields().entrySet()) {
            FieldDescriptor f = e.getKey();
            String n = f.getName();
            if (f.getJavaType() == FieldDescriptor.JavaType.MESSAGE) {
                if (f.isRepeated()) {
                    for (Object o : (List<?>) e.getValue()) {
                        maxId((Message) o, max);
                    }
                } else {
                    maxId((Message) e.getValue(), max);
                }
            } else if (f.getJavaType() == FieldDescriptor.JavaType.LONG
                    && (n.equals("id") || n.endsWith("_id") || n.endsWith("_ids")) && !n.equals("decision_id")) {
                if (f.isRepeated()) {
                    for (Object o : (List<?>) e.getValue()) {
                        max[0] = Math.max(max[0], (Long) o);
                    }
                } else {
                    max[0] = Math.max(max[0], (Long) e.getValue());
                }
            }
        }
    }

    // ---- the seat's stream: compare and answer ----

    /** Differences kept per game for the report (-Dmagicdosa.shadowSamples, default 6). */
    private static final int SAMPLES = Integer.getInteger("magicdosa.shadowSamples", 6);

    private void sample(List<String> where, String s) {
        if (where.size() < SAMPLES) {
            where.add(s);
        }
    }

    private final class Client implements magicdosa.adapter.forge.DelegatingClient {
        /** Live: the next answer is the shadow game's Forge AI's, for this real request. */
        private boolean delegateNext;
        private DecisionRequest pendingReal;

        @Override
        public boolean delegateLast() {
            boolean d = delegateNext;
            delegateNext = false;
            return d;
        }

        @Override
        public void delegated(DecisionRequest request, List<Integer> chosen, String engineDecision) {
            DecisionRequest real = pendingReal;
            pendingReal = null;
            // the shadow's answer, by candidate, in the real request (equal candidates by their rank among the equal ones)
            DecisionResponse.Builder out = DecisionResponse.newBuilder().setGameId(real.getGameId())
                .setDecisionId(real.getDecisionId());
            for (int i : chosen) {
                var c = request.getCandidates(i);
                int rank = 0;
                for (int k = 0; k < i; k++) {
                    if (request.getCandidates(k).equals(c)) {
                        rank++;
                    }
                }
                int j = -1;
                for (int k = 0, seen = 0; k < real.getCandidatesCount(); k++) {
                    if (real.getCandidates(k).equals(c) && seen++ == rank) {
                        j = k;
                        break;
                    }
                }
                if (j < 0) {
                    unmappedLive(real, engineDecision, "the shadow's answer " + text(c) + " is not a real candidate");
                }
                out.addChosen(j);
            }
            out.addSteps(magicdosa.protocol.v1.DecisionStep.newBuilder()
                .setDecider(magicdosa.protocol.v1.Decider.DECIDER_INTENDED_DELEGATION));
            live.answer(out.build(), "shadow Forge AI: " + engineDecision);
            decisionIdx++;
            afterAnswer(real);
            revealsAhead(real.getAppliedSeq());
        }

        @Override
        public void unmapped(DecisionRequest request, String engineDecision) {
            DecisionRequest real = pendingReal;
            pendingReal = null;
            unmappedLive(real, engineDecision, "Forge AI's answer is not a shadow candidate");
        }

        /** No answer to the real request (an unintended fallback there): the shadow goes on from the next turn start. */
        private void unmappedLive(DecisionRequest real, String engineDecision, String why) {
            live.unmapped(real, engineDecision, why);
            decisionIdx++;
            lastTry.put(real.getDecisionId(), false);
            sample(result.samples, "decision " + real.getDecisionId() + " " + real.getType() + " (" + engineDecision
                + "): " + why);
            throw new Unwind("unmapped answer at decision " + real.getDecisionId() + ": " + why, Unwind.Kind.NEXT_TURN,
                real.getObservation().getGame().getTurn());
        }

        @Override
        public HelloReply hello(Hello hello) {
            return HelloReply.newBuilder().setAccepted(true).build();
        }

        @Override
        public void send(AdapterMessage m) {
            switch (m.getMessageCase()) {
                case EVENT -> event(m.getEvent());
                case SNAPSHOT -> snapshot(m.getSnapshot());
                default -> { }
            }
        }

        private void event(Event e) {
            result.events++;
            lastShadowSeq = e.getSeq();
            // a recorded action of the other seat that the shadow produced by itself (Forge declared no attackers
            // without asking, ...) is used up
            // only declarations Forge makes without asking the controller (no attackers, no blockers); every other
            // recorded action is consumed by the controller that plays it
            Event next = opponentIdx < opponentRecord.size() ? opponentRecord.get(opponentIdx) : null;
            if (next != null && (e.getBodyCase() == Event.BodyCase.DECLARE_ATTACKERS
                    || e.getBodyCase() == Event.BodyCase.DECLARE_BLOCKERS) && sameBody(next, e)) {
                opponentIdx++;
            }
            shadowEvents.add(e);
            Event real = rec.events.get(e.getSeq());
            if (beliefSamples && !pendingReveals.isEmpty() && e.getBodyCase() == Event.BodyCase.ZONE_MOVE) {
                applyPendingReveals(false);
            }
            if (beliefSamples && real != null) {
                // a card the real game shows as it moves or is revealed takes that identity at once (design: 턴 중에
                //카드가 공개되면 손패·라이브러리 사이에서 카드 정체를 교환), before the next request is built
                if (real.getBodyCase() == Event.BodyCase.ZONE_MOVE && e.getBodyCase() == Event.BodyCase.ZONE_MOVE
                        && real.getZoneMove().getId() == e.getZoneMove().getId()) {
                    identity(real.getZoneMove().getId(), real.getZoneMove().getOracleId(), false);
                } else if (real.getBodyCase() == Event.BodyCase.CARD_REVEALED) {
                    // also when the shadow revealed another card at this point (a search shows the searcher the
                    // library: the real cards get their identities before the choice is asked); the whole run of
                    // real reveals from here, as the shadow may reveal fewer cards than the real game did
                    for (Event r : rec.events.tailMap(e.getSeq(), true).values()) {
                        if (r.getBodyCase() != Event.BodyCase.CARD_REVEALED) {
                            break;
                        }
                        identity(r.getCardRevealed().getId(), r.getCardRevealed().getOracleId(), false);
                        if (r.getCardRevealed().getRevealedToSeatsList().contains(viewer)) {
                            session.markSeen(r.getCardRevealed().getId());
                        }
                    }
                }
            }
            if (real != null && real.equals(e)) {
                result.eventsMatched++;
            } else {
                sample(result.eventSamples, "seq " + e.getSeq() + " real=" + text(real) + " shadow=" + text(e));
            }
            if (e.getBodyCase() == Event.BodyCase.LIBRARY_SHUFFLED) {
                // the k-th shuffle of a library in the shadow is the k-th real one (event numbers may have drifted)
                int seat = e.getLibraryShuffled().getSeat();
                int k = shuffles.merge(seat, 1, Integer::sum) - 1;
                List<Long> realSeqs = realShuffles.getOrDefault(seat, List.of());
                if (beliefSamples && !realSeqs.isEmpty()) {
                    // ②: the real shuffle of this library nearest to this point (the k-th one is off when the shadow
                    // shuffled a different number of times, e.g. in mulligans)
                    long near = realSeqs.get(0);
                    for (long r : realSeqs) {
                        if (Math.abs(r - e.getSeq()) < Math.abs(near - e.getSeq())) {
                            near = r;
                        }
                    }
                    departuresOnTop(seat, near);
                } else if (k < realSeqs.size()) {
                    reorderLibrary(seat, realSeqs.get(k));
                } else {
                    sample(result.samples, "shuffle " + (k + 1) + " of seat " + seat + " has no real counterpart");
                }
            }
        }

        private void snapshot(TurnStartSnapshot s) {
            int turn = s.getObservation().getGame().getTurn();
            GameRecord.Snapshot real = snapshotOfTurn(turn);
            result.snapshots++;
            lastComparedTurn = turn;
            if (real == null) {
                sample(result.samples, "turn " + turn + ": the real game has no snapshot for it");
            } else {
                var a = Fingerprint.normalise(real.snapshot());
                var b = Fingerprint.normalise(s);
                if (beliefSamples) {
                    for (Player p : game.getRegisteredPlayers()) {
                        departuresOnTop(game.getRegisteredPlayers().indexOf(p), real.snapshot().getSeq());
                        knownPositions(game.getRegisteredPlayers().indexOf(p), real.snapshot().getObservation());
                    }
                }
                if (Fingerprint.hash(a).equals(Fingerprint.hash(b))) {
                    result.snapshotsMatched++;
                    // verification ①: hidden library order from the record at the turn start too (the other seat
                    // may draw before any decision of ours in its turn)
                    if (!beliefSamples) {
                        libraryCorrections += syncLibraries(rec.privilegedAt(real.snapshot().getSeq()), "turn " + turn + " start");
                    }
                    if (turn > stageTurn) {
                        stage = 0; // a new turn starts matching: tracking again (design: 다음 턴 시작에 ... 이어서 추적)
                    }
                } else {
                    List<Fingerprint.Diff> diffs = Fingerprint.diff(a, b);
                    for (Fingerprint.Diff d : diffs) {
                        sample(result.samples, "turn " + turn + " start: " + d);
                    }
                    boolean critical = diffs.stream().anyMatch(Fingerprint.Diff::critical);
                    if (!critical && !forcedRegeneration) {
                        // minor differences only (as at a decision: recorded, no recovery)
                        if (!beliefSamples) {
                            libraryCorrections += syncLibraries(rec.privilegedAt(real.snapshot().getSeq()), "turn " + turn + " start");
                        }
                        if (turn > stageTurn) {
                            stage = 0;
                        }
                    } else if (!forcedRegeneration && turn != rebuiltTurn) {
                        stage = 2;
                        result.recoveries2++;
                        throw new Unwind("turn " + turn + " start does not match: rebuild", Unwind.Kind.REBUILD);
                    } else if (!forcedRegeneration) {
                        // rebuilt for this turn and still different: what the builder cannot express (a gap)
                        stage = 3;
                        result.recoveries3++;
                        sample(result.samples, "search skipped for turn " + turn + ": the rebuilt turn start differs");
                    }
                }
            }
            if (forcedRegeneration && real != null && turn > rebuiltTurn) {
                throw new Unwind("regenerate at turn " + turn, Unwind.Kind.REBUILD);
            }
        }

        private int firstTurn() {
            return 1;
        }

        @Override
        public DecisionResponse decide(DecisionRequest request) {
            DecisionRequest real;
            DecisionResponse recorded;
            if (decisionIdx < rec.decisions.size()) {
                var d = rec.decisions.get(decisionIdx++).record();
                real = d.getRequest();
                recorded = d.getResponse();
            } else if (live != null && live.pending() != null) {
                // live: the newest real request, not answered yet
                real = live.pending();
                recorded = null;
            } else {
                throw new Unwind("the record has no more decisions", Unwind.Kind.END);
            }
            if (request.getRejectionsCount() > 0) {
                sample(result.samples, "shadow refused an answer before decision " + real.getDecisionId() + ": "
                    + request.getRejectionsList());
            }
            long id = real.getDecisionId();
            // verification ①: hidden cards take their real values at every decision (library order is hidden; a
            // card put into a library in an order the stream does not show is placed as it really is)
            if (!beliefSamples) {
                libraryCorrections += syncLibraries(id);
            } else {
                // the cards the real stream takes from each library from here on go on top (robust to a different
                // number of shuffles in the shadow, e.g. during mulligans)
                boolean moved = false;
                for (Player p : game.getRegisteredPlayers()) {
                    departuresOnTop(game.getRegisteredPlayers().indexOf(p), real.getAppliedSeq());
                    moved |= knownPositions(game.getRegisteredPlayers().indexOf(p), real.getObservation());
                }
                if (reconcile(real.getObservation()) || moved) {
                    request = session.reask(request);
                }
            }
            // mana pools take the real (public) order: the other seat's Forge AI, weighing its action, test-pays from
            // pools and the refund moves mana to the end (the replayed seat does not weigh)
            boolean reordered = false;
            for (var ps : real.getObservation().getPlayersList()) {
                reordered |= session.orderPool(ps.getSeat(), ps.getManaPoolList());
            }
            if (reordered) {
                result.poolOrders++;
                request = session.reask(request);
            }
            if (FAULT_AT > 0 && firstTry.size() + 1 == FAULT_AT && !firstTry.containsKey(id)) {
                // recovery test: corrupt the shadow silently; recovery ① must bring it back
                Player p = game.getRegisteredPlayers().get(opponent);
                p.setLife(p.getLife() - 1, null);
                request = session.reask(request);
            }
            if (beliefSamples && real.getType() == magicdosa.protocol.v1.DecisionType.DECISION_TYPE_CHOOSE_CARDS
                    && searchIdentities(real, request)) {
                request = session.reask(request);
            }
            if (beliefSamples && real.getType() == magicdosa.protocol.v1.DecisionType.DECISION_TYPE_CHOOSE_CARDS) {
                request = notQualifying(real, request);
            }
            DecisionRequest a = Fingerprint.normalise(real);
            DecisionRequest b = Fingerprint.normalise(request);
            if (beliefSamples && real.getType() == magicdosa.protocol.v1.DecisionType.DECISION_TYPE_CHOOSE_CARDS) {
                // verification ②: cards chosen from a hidden zone (a library search) come in that zone's order,
                // which is hidden: the candidates are compared as a set (answers are matched by candidate anyway)
                a = sortedCandidates(a);
                b = sortedCandidates(b);
                if (!a.getCandidatesList().equals(b.getCandidatesList()) && !firstTry.containsKey(real.getDecisionId())) {
                    sample(result.samples, "decision " + real.getDecisionId() + " choose cards (" + real.getContext().getEnginePrompt()
                        + "): real " + candidateIds(a) + " shadow " + candidateIds(b) + "; shadow library top " + libraryTop(viewer));
                }
            }
            boolean match = Fingerprint.hash(a).equals(Fingerprint.hash(b));
            boolean first = !firstTry.containsKey(id);
            if (first) {
                firstTry.put(id, match);
            }
            lastTry.put(id, match);
            if (stage == 3) {
                result.searchSkipped++; // decided by the network alone: search skipped, cause recorded at the turn
            } else if (match) {
                // tracking
            } else {
                List<Fingerprint.Diff> diffs = Fingerprint.diff(a, b);
                boolean critical = false;
                Set<Fingerprint.Level> levels = new java.util.TreeSet<>();
                for (Fingerprint.Diff x : diffs) {
                    levels.add(x.level());
                    critical |= x.critical();
                }
                levels.forEach(l -> result.mismatchesByLevel.merge(l, 1, Integer::sum));
                if (critical) {
                    result.criticalMismatches++;
                } else {
                    result.minorOnlyMismatches++;
                }
                String where = "decision " + real.getDecisionId() + " " + real.getType() + " turn "
                    + real.getObservation().getGame().getTurn() + " " + real.getObservation().getGame().getStep();
                if (first) {
                    for (int i = 0; i < Math.min(4, diffs.size()); i++) {
                        sample(result.samples, where + ": " + diffs.get(i));
                    }
                }
                if (critical && !forcedRegeneration) {
                    escalate(real, where + ": " + diffs.get(0));
                    result.searchSkipped++;
                }
            }
            DecisionResponse resp = recorded;
            if (resp == null) {
                if (stage != 3) {
                    // live, tracking: the shadow game's Forge AI answers (the decision layer maps its answer back)
                    pendingReal = real;
                    delegateNext = true;
                    return DecisionResponse.getDefaultInstance();
                }
                resp = live.withoutShadow(real, "search skipped: " + stageCause);
                live.answer(resp, "without shadow");
                decisionIdx++;
                afterAnswer(real);
            } else if (resp.getDecisionId() == UNKNOWN_ANSWER) {
                lastTry.put(id, false);
                throw new Unwind("decision " + id + ": the real game's answer is unknown (unintended fallback)",
                    Unwind.Kind.NEXT_TURN, real.getObservation().getGame().getTurn());
            }
            // the real answer, by candidate where the lists differ
            DecisionResponse.Builder out = DecisionResponse.newBuilder().setGameId(request.getGameId())
                .setDecisionId(request.getDecisionId());
            for (int i : resp.getChosenList()) {
                var c = real.getCandidates(i);
                // equal candidates (two abilities under one key) are matched by their rank among the equal ones
                int rank = 0;
                for (int k = 0; k < i; k++) {
                    if (real.getCandidates(k).equals(c)) {
                        rank++;
                    }
                }
                int j = -1;
                for (int k = 0, seen = 0; k < request.getCandidatesCount(); k++) {
                    if (request.getCandidates(k).equals(c) && seen++ == rank) {
                        j = k;
                        break;
                    }
                }
                if (j < 0) {
                    String why = "decision " + real.getDecisionId() + ": the real answer " + text(c) + " is not a shadow candidate";
                    if (stage != 3 && !forcedRegeneration) {
                        escalate(real, why);
                    }
                    // the shadow cannot go on in this turn: this and the rest of the turn's decisions without search
                    lastTry.put(id, false);
                    throw new Unwind(why, Unwind.Kind.NEXT_TURN, real.getObservation().getGame().getTurn());
                }
                out.addChosen(j);
            }
            if (beliefSamples) {
                revealsAhead(real.getAppliedSeq());
            }
            return out.build();
        }
    }

    /**
     * Verification ②: the identities the real game shows before our next decision are taken now, before the action
     * just answered starts to use the cards (Ancient Stirrings takes the top five before it shows them: a swap after
     * that would move the cards it holds to other IDs). The cards are where the real game says at this point.
     */
    /** Set by a rebuild in verification ②: the reveals up to the first decision are taken once the record cursor is set. */
    private long revealsAheadAfterRebuild = -1;

    private void revealsAhead(long fromSeq) {
        long to = Long.MAX_VALUE;
        if (decisionIdx < rec.decisions.size()) {
            to = rec.decisions.get(decisionIdx).record().getRequest().getAppliedSeq();
        }
        for (Event r : rec.events.subMap(fromSeq, false, to, true).values()) {
            if (r.getBodyCase() == Event.BodyCase.CARD_REVEALED) {
                pendingReveals.add(r.getCardRevealed());
            } else if (r.getBodyCase() == Event.BodyCase.ZONE_MOVE && !r.getZoneMove().getOracleId().isEmpty()
                    && (r.getZoneMove().getFrom() == magicdosa.protocol.v1.Zone.ZONE_LIBRARY
                        || r.getZoneMove().getFrom() == magicdosa.protocol.v1.Zone.ZONE_HAND)) {
                // a card leaving a hidden zone face up (milled, surveilled, discarded, searched for): its identity is
                // taken before the effect that moves it starts holding the cards it looks at
                pendingReveals.add(magicdosa.protocol.v1.CardRevealed.newBuilder().setId(r.getZoneMove().getPrevId())
                    .setOracleId(r.getZoneMove().getOracleId()).build());
            }
        }
        applyPendingReveals(true);
    }

    /** Reveals of the real game taken ahead whose card the shadow has not made yet (a card a mulligan draws later). */
    private final List<magicdosa.protocol.v1.CardRevealed> pendingReveals = new ArrayList<>();

    private void applyPendingReveals(boolean sync) {
        for (var it = pendingReveals.iterator(); it.hasNext(); ) {
            var r = it.next();
            if (session.cardOf(r.getId()) == null) {
                continue;
            }
            it.remove();
            identity(r.getId(), r.getOracleId(), sync);
            Card c = session.cardOf(r.getId());
            // the other seat's card the real game showed the viewer (an opening-hand reveal the shadow did not make);
            // the viewer's own cards it sees anyway (marking them ahead would only drop the shadow's own reveal)
            if (r.getRevealedToSeatsList().contains(viewer) && c != null
                    && game.getRegisteredPlayers().indexOf(c.getOwner()) != viewer) {
                session.markSeen(r.getId());
            }
        }
    }

    /**
     * Recovery after a critical mismatch at {@code real}: ① the turn again from its memento, ② a rebuild from the
     * real turn-start snapshot, ③ (returns) the rest of the turn without search.
     */
    private void escalate(DecisionRequest real, String cause) {
        int turnNow = game.getPhaseHandler().getTurn();
        if (turnNow != stageTurn) {
            stage = 0;
            stageTurn = turnNow;
        }
        String where = "decision " + real.getDecisionId();
        if (stage == 0) {
            stage = 1;
            result.recoveries1++;
            stageCause = cause;
            throw new Unwind("recovery 1 at " + where, Unwind.Kind.RESTORE);
        } else if (stage == 1) {
            stage = 2;
            result.recoveries2++;
            throw new Unwind("recovery 2 at " + where, Unwind.Kind.RESTORE);
        }
        stage = 3;
        result.recoveries3++;
        sample(result.samples, "search skipped for the rest of turn " + real.getObservation().getGame().getTurn() + ": " + cause);
    }

    /** Live mode: the last real event taken into the other seat's record and the shuffle list. */
    private long ingestedSeq;

    /** Live mode: the real events that came in since the last look join the other seat's record and the shuffles. */
    private void extendRecord() {
        if (rec.events.isEmpty()) {
            return;
        }
        for (Event e : rec.events.tailMap(ingestedSeq, false).values()) {
            if (e.getBodyCase() == Event.BodyCase.LIBRARY_SHUFFLED) {
                realShuffles.computeIfAbsent(e.getLibraryShuffled().getSeat(), k -> new ArrayList<>()).add(e.getSeq());
            }
            if (answerBearing(e, opponent)) {
                opponentRecord.add(e);
            }
        }
        ingestedSeq = Math.max(ingestedSeq, rec.events.lastKey());
    }

    /**
     * Live mode, after an answer went to the real game and the next request (or the end) came in: the new events join
     * the record, and what the real stream shows up to the next request is taken (draws on top, identities shown).
     */
    private void afterAnswer(DecisionRequest real) {
        extendRecord();
        for (Player p : game.getRegisteredPlayers()) {
            departuresOnTop(game.getRegisteredPlayers().indexOf(p), real.getAppliedSeq());
        }
    }

    /** The sequence number the shadow's stream has reached. */
    private long lastSeq() {
        return lastShadowSeq;
    }

    private static boolean sameBody(Event a, Event b) {
        return a.toBuilder().clearSeq().clearTurn().clearStep().build().equals(b.toBuilder().clearSeq().clearTurn().clearStep().build());
    }

    /** Diagnostic: the first turn whose events differ in number by kind between the real and the shadow stream. */
    private void eventCounts(long fromSeq) {
        Map<Integer, Map<String, Integer>> real = new TreeMap<>(), shadow = new TreeMap<>();
        for (Event e : rec.events.tailMap(fromSeq, false).values()) {
            real.computeIfAbsent(e.getTurn(), k -> new TreeMap<>()).merge(e.getBodyCase().name(), 1, Integer::sum);
        }
        for (Event e : shadowEvents) {
            shadow.computeIfAbsent(e.getTurn(), k -> new TreeMap<>()).merge(e.getBodyCase().name(), 1, Integer::sum);
        }
        for (int turn : real.keySet()) {
            Map<String, Integer> a = real.get(turn), b = shadow.getOrDefault(turn, Map.of());
            if (!a.equals(b)) {
                Map<String, Integer> d = new TreeMap<>();
                a.forEach((k, n) -> d.merge(k, n, Integer::sum));
                b.forEach((k, n) -> d.merge(k, -n, Integer::sum));
                d.values().removeIf(n -> n == 0);
                sample(result.eventSamples, "turn " + turn + " event counts real-shadow: " + d);
                return;
            }
        }
    }

    /** Self-check: the shadow's libraries are in the recorded order. */
    private void checkLibraries(PrivilegedRecord hidden, String where) {
        for (LibraryOrder lib : hidden.getPrivileged().getLibrariesList()) {
            Player owner = game.getRegisteredPlayers().get(lib.getSeat());
            List<Long> shadow = new ArrayList<>();
            for (Card c : owner.getCardsIn(ZoneType.Library)) {
                shadow.add(session.idOf(c));
            }
            if (!shadow.equals(lib.getIdsList())) {
                sample(result.samples, where + ": library of seat " + lib.getSeat() + " real=" + lib.getIdsList()
                    + " shadow=" + shadow);
            }
        }
    }

    private int libraryCorrections;

    /** The libraries in the order the hidden-state record of decision {@code decisionId} gives; 1 if one changed. */
    private int syncLibraries(long decisionId) {
        PrivilegedRecord p = null;
        for (PrivilegedRecord r : rec.privileged) {
            if (r.getDecisionId() == decisionId) {
                p = r;
            }
        }
        return syncLibraries(p, "decision " + decisionId);
    }

    private int syncLibraries(PrivilegedRecord p, String where) {
        if (p == null) {
            return 0;
        }
        int changed = 0;
        for (LibraryOrder lib : p.getPrivileged().getLibrariesList()) {
            Player owner = game.getRegisteredPlayers().get(lib.getSeat());
            List<Card> now = new ArrayList<>();
            owner.getCardsIn(ZoneType.Library).forEach(now::add);
            List<Card> order = new ArrayList<>();
            for (long cid : lib.getIdsList()) {
                Card c = session.cardOf(cid);
                if (c == null || !now.contains(c)) {
                    sample(result.samples, where + ": library card " + cid + " of seat " + lib.getSeat()
                        + " is not in the shadow's library");
                    return changed;
                }
                order.add(c);
            }
            if (order.size() != now.size()) {
                sample(result.samples, where + ": library of seat " + lib.getSeat() + " has " + now.size()
                    + " cards in the shadow, " + order.size() + " in the real game");
            } else if (!order.equals(now)) {
                owner.getZone(ZoneType.Library).setCards(order);
                changed = 1;
            }
        }
        return changed;
    }

    /** Verification ①: after a shuffle, the shadow library takes the real order (from the privileged record). */
    private void reorderLibrary(int seat, long seq) {
        PrivilegedRecord p = rec.privilegedAt(seq);
        if (p == null) {
            sample(result.samples, "shuffle at seq " + seq + ": no hidden state recorded");
            return;
        }
        for (LibraryOrder lib : p.getPrivileged().getLibrariesList()) {
            if (lib.getSeat() != seat) {
                continue;
            }
            List<Card> order = new ArrayList<>();
            for (long id : lib.getIdsList()) {
                Card c = session.cardOf(id);
                if (c == null) {
                    sample(result.samples, "shuffle at seq " + seq + ": library card " + id + " unknown to the shadow");
                    return;
                }
                order.add(c);
            }
            Player owner = game.getRegisteredPlayers().get(seat);
            owner.getZone(ZoneType.Library).setCards(order);
        }
    }

    // ---- the other seat's record ----

    private final class Replay implements ReplaySource {
        @Override
        public Event peek(int seat) {
            if (stop) {
                throw new Unwind("stopped", Unwind.Kind.END);
            }
            while (opponentIdx < opponentRecord.size() && opponentUsed.contains(opponentIdx)) {
                opponentIdx++;
            }
            if (opponentIdx >= opponentRecord.size() && opponentRecord.size() > 0 && rec.events.lastKey() <= lastSeq()
                    && (live == null || live.over())) {
                throw new Unwind("the record of the other seat has no more", Unwind.Kind.END);
            }
            Event next = opponentIdx < opponentRecord.size() ? opponentRecord.get(opponentIdx) : null;
            if (beliefSamples && next != null) {
                // the card a recorded cast or land play uses is the card the record names
                switch (next.getBodyCase()) {
                    case CAST_SPELL -> {
                        identity(next.getCastSpell().getCardId(), next.getCastSpell().getSpell().getCard().getOracleId());
                        costIdentities(next.getSeq(), next.getCastSpell().getCostIdsList());
                    }
                    case ACTIVATE_ABILITY -> costIdentities(next.getSeq(), next.getActivateAbility().getCostIdsList());
                    case PLAY_LAND -> identity(next.getPlayLand().getCardId(), next.getPlayLand().getLand().getOracleId());
                    default -> { }
                }
            }
            return next;
        }

        @Override
        public void consume(int seat) {
            peek(seat);
            opponentIdx++;
        }

        @Override
        public Event takeChoice(int seat, String method) {
            peek(seat);
            for (int i = opponentIdx; i < opponentRecord.size(); i++) {
                Event e = opponentRecord.get(i);
                if (e.getBodyCase() != Event.BodyCase.RESOLUTION_CHOICE) {
                    return null; // the seat's next action ends the window
                }
                if (!opponentUsed.contains(i) && e.getResolutionChoice().getEnginePrompt().equals(method)) {
                    opponentUsed.add(i);
                    if (beliefSamples) {
                        // a chosen hidden card (discard, search) is the card its later move shows
                        for (var c : e.getResolutionChoice().getChosenList()) {
                            for (Event m : rec.events.tailMap(e.getSeq(), false).values()) {
                                if (m.getBodyCase() == Event.BodyCase.ZONE_MOVE && m.getZoneMove().getPrevId() == c.getEntityId()) {
                                    identity(c.getEntityId(), m.getZoneMove().getOracleId());
                                    break;
                                }
                            }
                        }
                    }
                    return e;
                }
            }
            return null;
        }

        @Override
        public boolean noRecordMeansNone() {
            return beliefSamples;
        }

        @Override
        public List<Long> libraryAfter(int seat, long seq) {
            if (beliefSamples) {
                // verification ②: the order the real stream later takes cards from the top is public (which
                // objects, not what they are); the rest is left to Forge AI's arrangement
                return departures(seat, seq);
            }
            // the next hidden-state record after the event, with the cards that left that library in between put
            // back in front, in the order they left (drawn, milled, ...)
            PrivilegedRecord next = null;
            for (PrivilegedRecord r : rec.privileged) {
                if (r.getSeq() >= seq) {
                    next = r;
                    break;
                }
            }
            if (next == null) {
                return List.of();
            }
            List<Long> lib = null;
            for (LibraryOrder l : next.getPrivileged().getLibrariesList()) {
                if (l.getSeat() == seat) {
                    lib = l.getIdsList();
                }
            }
            if (lib == null) {
                return List.of();
            }
            List<Long> out = new ArrayList<>();
            for (Event e : rec.events.subMap(seq, false, next.getSeq(), true).values()) {
                if (e.getBodyCase() == Event.BodyCase.ZONE_MOVE && e.getZoneMove().getFrom() == magicdosa.protocol.v1.Zone.ZONE_LIBRARY
                        && e.getZoneMove().getOwnerSeat() == seat) {
                    out.add(e.getZoneMove().getPrevId());
                }
            }
            out.addAll(lib);
            return out;
        }

        @Override
        public void mismatch(int seat, String what) {
            if (result.replayMismatches.size() < 20) {
                result.replayMismatches.add("turn " + game.getPhaseHandler().getTurn() + " "
                    + game.getPhaseHandler().getPhase() + ": " + what);
            }
        }
    }

    private static String text(Message m) {
        if (m == null) {
            return "none";
        }
        String s = com.google.protobuf.TextFormat.shortDebugString(m);
        return s.length() > 300 ? s.substring(0, 300) + "..." : s;
    }
}
