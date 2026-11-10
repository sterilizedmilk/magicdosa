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
        /** END: stop; RESTORE: recovery ① (turn-start memento, the turn again); REBUILD: rebuild from a real snapshot. */
        enum Kind { END, RESTORE, REBUILD }

        final String why;
        final Kind kind;

        Unwind(String why, Kind kind) {
            super(why, null, false, false);
            this.why = why;
            this.kind = kind;
        }
    }

    /** What the run measured. */
    public static final class Result {
        public final String game;
        /** Decisions as first asked (the gate) and after recovery; decided without search after recovery failed. */
        public int decisions, decisionsMatched, decisionsMatchedAfterRecovery, searchSkipped;
        /** Recoveries: ① memento and the turn again, ② rebuild from the turn-start snapshot, ③ search skip. */
        public int recoveries1, recoveries2, recoveries3;
        public int mementos, libraryCorrections;
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
            r.gaps.addAll(new ArrayList<>(gaps));
            r.replayMismatches.addAll(new ArrayList<>(replayMismatches));
            r.samples.addAll(new ArrayList<>(samples));
            r.eventSamples.addAll(new ArrayList<>(eventSamples));
            r.end = end;
            r.error = error;
            return r;
        }
    }

    private final GameRecord rec;
    private final CardKeys keys;
    private final boolean forcedRegeneration;
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
    private record TurnPoint(int turn, GameMemento memento, Runnable session, List<StateCopy> controllers, int decisionIdx,
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
        this.rec = rec;
        this.keys = keys;
        this.forcedRegeneration = forcedRegeneration;
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
            long firstSeq = rec.snapshots.get(si).snapshot().getSeq();
            while (true) {
                try {
                    if (pregame) {
                        pregame = false;
                        pregame();
                    }
                    loop();
                    result.end = game.isGameOver() ? "game over" : "loop ended";
                    eventCounts(firstSeq);
                    return;
                } catch (Throwable t) {
                    Unwind u = unwind(t);
                    if (u == null) {
                        throw t;
                    }
                    if (u.kind == Unwind.Kind.END) {
                        result.end = u.why;
                        eventCounts(firstSeq);
                        return;
                    }
                    if (u.kind == Unwind.Kind.RESTORE && turnStart != null) {
                        restore(turnStart);
                        continue;
                    }
                    // recovery ②, a turn start that does not match, or forced per-turn regeneration (measurement):
                    // rebuild from the real snapshot of the turn
                    int turn = u.kind == Unwind.Kind.RESTORE ? game.getPhaseHandler().getTurn() : lastComparedTurn;
                    GameRecord.Snapshot s = snapshotOfTurn(turn);
                    if (s == null) {
                        result.end = "no snapshot for turn " + turn;
                        return;
                    }
                    int keep = stage;
                    rebuild(s);
                    stage = keep;
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
        if (Boolean.getBoolean("magicdosa.shadowProbe")) {
            probe();
            if (game.getRegisteredPlayers().get(viewer).getController() instanceof AgentPlayerController apc) {
                System.out.println("PROBE pregame legal " + apc.debugLegalActions());
            }
        }
    }

    /** Debugging: why each hand card's spells of the seat are or are not castable now. */
    private void probe() {
        Player me = game.getRegisteredPlayers().get(viewer);
        for (Card c : me.getCardsIn(ZoneType.Hand)) {
            var all = forge.ai.ComputerUtilAbility.getSpellAbilities(new forge.game.card.CardCollection(c), me);
            for (var sa : forge.ai.ComputerUtilAbility.getOriginalAndAltCostAbilities(all, me)) {
                sa.setActivatingPlayer(me);
                System.out.println("PROBE " + c + " | " + sa + " | restrictions " + sa.checkRestrictions(c, me)
                    + " canPlay " + sa.canPlay() + " addl " + forge.game.cost.CostPayment.canPayAdditionalCosts(sa.getPayCosts(), sa, false)
                    + " altCost " + sa.isAlternativeCost(forge.game.spellability.AlternativeCost.Evoke)
                    + " legalAfterStack " + sa.isLegalAfterStack() + " targetable " + forge.ai.ComputerUtilAbility.isFullyTargetable(sa)
                    + " payable " + magicdosa.adapter.forge.ManaPayments.payableNow(me, sa));
            }
        }
    }

    private void loop() {
        PhaseHandler ph = game.getPhaseHandler();
        while (!game.isGameOver()) {
            // the turn-start checkpoint: a loop boundary in the cleanup step of the turn before (2026-11-10 decision)
            if (ph.getPhase() == PhaseType.CLEANUP && game.getStack().isEmpty() && !Boolean.getBoolean("magicdosa.shadowNoMemento")) {
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
        TurnPoint tp = new TurnPoint(game.getPhaseHandler().getTurn(), m, session.checkpoint(), ctl, decisionIdx, opponentIdx,
            new java.util.HashSet<>(opponentUsed), new java.util.HashMap<>(shuffles), lastShadowSeq, shadowEvents.size());
        result.mementos++;
        result.mementoNanos += System.nanoTime() - t0;
        return tp;
    }

    /** Recovery ①: back to the turn-start checkpoint, the record cursors with it. */
    private void restore(TurnPoint tp) {
        long t0 = System.nanoTime(); // measurement only
        tp.memento().restore();
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
        SnapshotBuilder.Result built = new SnapshotBuilder(keys).build(s, hidden.getPrivileged(), nextId, id -> {
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
        checkLibraries(hidden, "rebuild at turn " + s.getObservation().getGame().getTurn());
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
        lastComparedTurn = s.getObservation().getGame().getTurn() - 1;
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

    private void sample(List<String> where, String s) {
        if (where.size() < 6) {
            where.add(s);
        }
    }

    private final class Client implements DecisionClient {
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
            if (Boolean.getBoolean("magicdosa.shadowProbe") && e.getEngineHandled() && e.hasPassPriority()
                    && game.getPhaseHandler().getTurn() <= 1) {
                System.out.println("PROBE at " + game.getPhaseHandler().getPhase() + " available "
                    + forge.ai.ComputerUtilAbility.getAvailableCards(game, game.getRegisteredPlayers().get(viewer))
                    + " controller " + game.getRegisteredPlayers().get(viewer).getController().getClass().getSimpleName()
                    + " legal " + (game.getRegisteredPlayers().get(viewer).getController() instanceof AgentPlayerController apc
                        ? apc.debugLegalActions() : "-")
                    + " priority " + game.getPhaseHandler().getPriorityPlayer() + " active " + game.getPhaseHandler().getPlayerTurn());
                probe();
            }
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
                if (k < realSeqs.size()) {
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
                if (Fingerprint.hash(a).equals(Fingerprint.hash(b))) {
                    result.snapshotsMatched++;
                    if (turn > stageTurn) {
                        stage = 0; // a new turn starts matching: tracking again (design: 다음 턴 시작에 ... 이어서 추적)
                    }
                } else {
                    for (Fingerprint.Diff d : Fingerprint.diff(a, b)) {
                        sample(result.samples, "turn " + turn + " start: " + d);
                    }
                    if (!forcedRegeneration && turn != rebuiltTurn) {
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
            if (decisionIdx >= rec.decisions.size()) {
                throw new Unwind("the record has no more decisions", Unwind.Kind.END);
            }
            var d = rec.decisions.get(decisionIdx++).record();
            DecisionRequest real = d.getRequest();
            if (request.getRejectionsCount() > 0) {
                sample(result.samples, "shadow refused an answer before decision " + real.getDecisionId() + ": "
                    + request.getRejectionsList());
            }
            long id = real.getDecisionId();
            // verification ①: hidden cards take their real values at every decision (library order is hidden; a
            // card put into a library in an order the stream does not show is placed as it really is)
            libraryCorrections += syncLibraries(id);
            if (FAULT_AT > 0 && firstTry.size() + 1 == FAULT_AT && !firstTry.containsKey(id)) {
                // recovery test: corrupt the shadow silently; recovery ① must bring it back
                Player p = game.getRegisteredPlayers().get(opponent);
                p.setLife(p.getLife() - 1, null);
                request = session.reask(request);
            }
            DecisionRequest a = Fingerprint.normalise(real);
            DecisionRequest b = Fingerprint.normalise(request);
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
                    int turnNow = game.getPhaseHandler().getTurn();
                    if (turnNow != stageTurn) {
                        stage = 0;
                        stageTurn = turnNow;
                    }
                    if (stage == 0) {
                        stage = 1;
                        result.recoveries1++;
                        stageCause = where + ": " + diffs.get(0);
                        throw new Unwind("recovery 1 at " + where, Unwind.Kind.RESTORE);
                    } else if (stage == 1) {
                        stage = 2;
                        result.recoveries2++;
                        throw new Unwind("recovery 2 at " + where, Unwind.Kind.RESTORE);
                    } else {
                        stage = 3;
                        result.recoveries3++;
                        sample(result.samples, "search skipped for the rest of turn "
                            + real.getObservation().getGame().getTurn() + ": " + where + ": " + diffs.get(0));
                        result.searchSkipped++;
                    }
                }
            }
            // the real answer, by candidate where the lists differ
            DecisionResponse resp = d.getResponse();
            DecisionResponse.Builder out = DecisionResponse.newBuilder().setGameId(request.getGameId())
                .setDecisionId(request.getDecisionId());
            for (int i : resp.getChosenList()) {
                var c = real.getCandidates(i);
                int j = request.getCandidatesList().indexOf(c);
                if (j < 0) {
                    throw new Unwind("decision " + real.getDecisionId() + ": the real answer " + text(c)
                        + " is not a shadow candidate", Unwind.Kind.END);
                }
                out.addChosen(j);
            }
            return out.build();
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
                    sample(result.samples, "decision " + decisionId + ": library card " + cid + " of seat " + lib.getSeat()
                        + " is not in the shadow's library");
                    return changed;
                }
                order.add(c);
            }
            if (order.size() == now.size() && !order.equals(now)) {
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
            if (opponentIdx >= opponentRecord.size() && opponentRecord.size() > 0 && rec.events.lastKey() <= lastSeq()) {
                throw new Unwind("the record of the other seat has no more", Unwind.Kind.END);
            }
            return opponentIdx < opponentRecord.size() ? opponentRecord.get(opponentIdx) : null;
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
                    return e;
                }
            }
            return null;
        }

        @Override
        public List<Long> libraryAfter(int seat, long seq) {
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
