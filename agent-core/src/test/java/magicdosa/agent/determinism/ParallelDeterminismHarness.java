package magicdosa.agent.determinism;

import magicdosa.agent.shadow.GameCreation;

import forge.deck.Deck;
import magicdosa.agent.DeckFiles;
import forge.game.*;
import forge.game.player.RegisteredPlayer;
import forge.GuiDesktop;
import forge.gui.GuiBase;
import forge.model.FModel;
import forge.player.GamePlayerUtil;
import forge.util.MyRandom;

import java.io.File;
import java.util.*;
import java.util.concurrent.*;

/**
 * Reproduction harness: plays the same set of seeded Bo3 matches twice, one after the other on a single
 * thread in one JVM, and checks that each seed produces the same game log both times (RESULT ... different=0).
 *
 * A third, concurrent pass measures throughput only. Forge keeps JVM-wide state (static id counters, AiCache)
 * that concurrent matches share, so a parallel match may differ from its sequential run; that is reported but
 * is not a failure. Training runs in parallel and keeps the full logs; reproduction is sequential with a seed.
 *
 * Forge keeps one global Random (MyRandom). Instead of patching Forge, we install a Random whose
 * draws are delegated to a per-thread Random, so each match thread owns its own seeded stream.
 *
 * Usage: java -cp forge.jar:classes magicdosa.agent.determinism.ParallelDeterminismHarness deckDir deckA deckB nMatches threads baseSeed
 */
public final class ParallelDeterminismHarness {

    /** Delegates every draw to the calling thread's own Random. */
    static final class PerThreadRandom extends Random {
        // Inheritable: Forge AI evaluates on a fresh "Game AI Eval" thread spawned (and awaited) by the game thread,
        // so that thread must draw from the same seeded stream as the match that spawned it.
        static final InheritableThreadLocal<Random> CURRENT = new InheritableThreadLocal<>() {
            @Override protected Random initialValue() {
                if (Boolean.getBoolean("traceRandom")) {
                    new Throwable("unseeded Random drawn on thread " + Thread.currentThread().getName()).printStackTrace(System.out);
                }
                return new Random();
            }
        };
        /** Optional per-match trace of who drew random numbers (shared with spawned threads). */
        static final InheritableThreadLocal<List<String>> TRACE = new InheritableThreadLocal<>();
        PerThreadRandom() { super(0); }
        @Override protected int next(int bits) { trace(); return CURRENT.get().nextInt() >>> (32 - bits); }
        @Override public int nextInt() { trace(); return CURRENT.get().nextInt(); }
        private static void trace() {
            List<String> t = TRACE.get();
            if (t == null) return;
            String caller = StackWalker.getInstance().walk(fr -> fr
                    .filter(f -> !f.getClassName().startsWith("java.") && !f.getClassName().startsWith("magicdosa.")
                            && !f.getClassName().equals("forge.util.MyRandom") && !f.getClassName().startsWith("forge.util.MyRandom$"))
                    .limit(Integer.getInteger("traceDepth", 3)).map(f -> f.getClassName().replaceAll(".*\\.", "") + "." + f.getMethodName() + ":" + f.getLineNumber())
                    .reduce((a, b) -> a + " < " + b).orElse("?"));
            synchronized (t) { t.add(Thread.currentThread().getName().replaceAll("-\\d+$", "") + " " + caller); }
        }
        @Override public void setSeed(long seed) { /* seeds are set per thread via CURRENT */ }
    }

    record Result(long seed, String winner, int games, String log, long millis, String error) {}

    public static void main(String[] args) throws Exception {
        String deckDir = args[0];
        int nMatches = Integer.parseInt(args[3]);
        int threads = Integer.parseInt(args[4]);
        long baseSeed = Long.parseLong(args[5]);

        // Same headless setup as forge.view.Main, minus crash reporting and the GUI.
        System.setProperty("java.awt.headless", "true");
        System.setProperty("java.util.Arrays.useLegacyMergeSort", "true");
        GuiBase.setInterface(new GuiDesktop());
        FModel.initialize(null, null);
        MyRandom.setRandom(new PerThreadRandom());
        // decks resolve card names against the card database, so they load after FModel.initialize
        Deck deckA = DeckFiles.load(new File(deckDir, args[1]));
        Deck deckB = DeckFiles.load(new File(deckDir, args[2]));

        // Pass 1 and 2: the same seeds, sequentially, twice. This comparison is the check.
        long t0 = System.nanoTime();
        Map<Long, Result> first = runAll(deckA, deckB, nMatches, 1, baseSeed);
        long seqMs = (System.nanoTime() - t0) / 1_000_000;
        Map<Long, Result> second = runAll(deckA, deckB, nMatches, 1, baseSeed);
        if (System.getProperty("dumpSeed") != null) {
            long ds = Long.parseLong(System.getProperty("dumpSeed"));
            java.nio.file.Files.writeString(java.nio.file.Path.of("dump_a.log"), first.get(ds).log());
            java.nio.file.Files.writeString(java.nio.file.Path.of("dump_b.log"), second.get(ds).log());
        }
        int[] seq = compare("seq", first, second, true);

        // Pass 3: the same seeds concurrently, for throughput. Differences here are expected and only counted.
        int games = 0;
        for (Result r : first.values()) games += r.games();
        long parMs = 0;
        int[] par = {0, 0, 0};
        if (threads > 1) {
            long t1 = System.nanoTime();
            Map<Long, Result> parallel = runAll(deckA, deckB, nMatches, threads, baseSeed);
            parMs = (System.nanoTime() - t1) / 1_000_000;
            par = compare("par", first, parallel, false);
        }

        Runtime rt = Runtime.getRuntime();
        System.out.printf("RESULT matches=%d identical=%d different=%d errors=%d%n", nMatches, seq[0], seq[1], seq[2]);
        Map<String, Integer> wins = new TreeMap<>();
        for (Result r : first.values()) wins.merge(r.winner(), 1, Integer::sum);
        System.out.printf("WINS A=%s B=%s %s%n", args[1], args[2], wins);
        if (threads > 1) {
            System.out.printf("PARALLEL threads=%d sameAsSequential=%d differentFromSequential=%d errors=%d (informational)%n",
                    threads, par[0], par[1], par[2]);
        }
        System.out.printf("TIMING sequential=%dms (%.2f games/s) parallel=%dms (%.2f games/s) games=%d%n",
                seqMs, games * 1000.0 / seqMs, parMs, parMs == 0 ? 0.0 : games * 1000.0 / parMs, games);
        System.out.printf("MEMORY usedAfterGC=%dMB (call after runs)%n", usedAfterGc(rt));
        System.exit(0);
    }

    /** Counts {identical, different, errors} between two runs of the same seeds; prints each mismatch if verbose. */
    static int[] compare(String label, Map<Long, Result> ref, Map<Long, Result> other, boolean verbose) {
        int same = 0, diff = 0, errors = 0;
        for (long seed : ref.keySet()) {
            Result a = ref.get(seed), b = other.get(seed);
            if (a.error() != null || b.error() != null) {
                errors++;
                System.out.println(label + " seed " + seed + " ERROR first=" + a.error() + " other=" + b.error());
            } else if (a.log().equals(b.log())) {
                same++;
            } else {
                diff++;
                if (verbose) {
                    System.out.println(label + " seed " + seed + " DIFFERS: first winner=" + a.winner() + " other winner="
                            + b.winner() + " firstDiffLine=" + firstDiff(a.log(), b.log()));
                }
            }
        }
        return new int[] {same, diff, errors};
    }

    static long usedAfterGc(Runtime rt) {
        System.gc();
        return (rt.totalMemory() - rt.freeMemory()) / (1024 * 1024);
    }

    static Map<Long, Result> runAll(Deck a, Deck b, int n, int threads, long baseSeed) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        List<Future<Result>> fs = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            final long seed = baseSeed + i;
            fs.add(pool.submit(() -> playMatch(a, b, seed)));
        }
        Map<Long, Result> out = new TreeMap<>();
        for (Future<Result> f : fs) {
            Result r = f.get();
            out.put(r.seed(), r);
        }
        pool.shutdown();
        return out;
    }

    static Result playMatch(Deck deckA, Deck deckB, long seed) {
        PerThreadRandom.CURRENT.set(new Random(seed));
        List<String> trace = Boolean.getBoolean("traceDraws") ? new ArrayList<>() : null;
        PerThreadRandom.TRACE.set(trace);
        long start = System.nanoTime();
        StringBuilder log = new StringBuilder();
        int games = 0;
        try {
            GameRules rules = new GameRules(GameType.Constructed);
            rules.setAppliedVariants(EnumSet.of(GameType.Constructed));
            rules.setGamesPerMatch(3);
            List<RegisteredPlayer> players = new ArrayList<>();
            RegisteredPlayer p1 = new RegisteredPlayer(deckA);
            p1.setPlayer(GamePlayerUtil.createAiPlayer("A", 0));
            RegisteredPlayer p2 = new RegisteredPlayer(deckB);
            p2.setPlayer(GamePlayerUtil.createAiPlayer("B", 1));
            players.add(p1);
            players.add(p2);
            Match match = new Match(rules, players, "seed" + seed);
            while (!match.isMatchOver() && games < 3) {
                Game g = GameCreation.createGame(match);
                g.setNoGUIUser();
                g.AI_TIMEOUT = 600; // keep wall-clock timeouts out of the comparison
                match.startGame(g);
                games++;
                List<GameLogEntry> entries = g.getGameLog().getLogEntries(null);
                Collections.reverse(entries);
                log.append("=== game ").append(games).append('\n');
                for (GameLogEntry e : entries) {
                    String line = e.toString();
                    if (line.contains("Match Duration")) {
                        continue; // wall-clock time, not game state
                    }
                    log.append(line).append('\n');
                }
            }
            String winner = match.getWinner() == null ? "draw" : match.getWinner().getPlayer().getName();
            if (trace != null) {
                log.append("=== random draws\n");
                synchronized (trace) { for (String d : trace) log.append("RND ").append(d).append('\n'); }
            }
            return new Result(seed, winner, games, log.toString(), (System.nanoTime() - start) / 1_000_000, null);
        } catch (Throwable t) {
            return new Result(seed, "?", games, log.toString(), 0, t.toString());
        }
    }

    static int firstDiff(String a, String b) {
        String[] x = a.split("\n"), y = b.split("\n");
        for (int i = 0; i < Math.min(x.length, y.length); i++) {
            if (!x[i].equals(y[i])) return i + 1;
        }
        return Math.min(x.length, y.length) + 1;
    }
}
