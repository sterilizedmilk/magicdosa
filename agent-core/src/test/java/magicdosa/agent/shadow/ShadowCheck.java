package magicdosa.agent.shadow;

import forge.GuiDesktop;
import forge.gui.GuiBase;
import forge.model.FModel;
import magicdosa.adapter.forge.CardKeys;
import magicdosa.protocol.v1.LogRecord;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Stream;

/**
 * Phase-2 verification ① (design: 개요 2단계): every game log under a folder is followed by a shadow game from each
 * agent seat's stream, with the hidden cards filled with the real ones. PASS needs every decision's fingerprint (L1, L2,
 * L3) to match and no run error; every mismatch is an event-application bug. With {@code --regen} the shadow is
 * rebuilt from the real turn-start snapshot at every turn instead (the builder-coverage measurement mode).
 *
 * <pre>mvn -pl agent-core exec:exec "-Dexec.mainClass=magicdosa.agent.shadow.ShadowCheck"
 *     "-Dexec.args=&lt;abs log dir&gt; [--workers N] [--worker-xmx 2g] [--regen] [--limit N]"</pre>
 *
 * {@code --workers N} (2026-11-11 decision: parallel runs are by process): each folder of logs (a gate run's pairing)
 * is checked in its own JVM, N at a time, its games one after another; the results are reported together here.
 * Forge games running at the same time in one JVM affect each other, so {@code --threads N} is for comparison only
 * and its report is marked as not valid for a verdict. {@code --emit <file>} (used by the workers) writes the results
 * to a file instead of reporting.
 */
public final class ShadowCheck {
    public static void main(String[] args) {
        // Forge leaves non-daemon threads: exit explicitly, also when the check fails
        int code = 1;
        try {
            run(args);
            code = 0;
        } catch (Throwable t) {
            t.printStackTrace();
        }
        System.exit(code);
    }

    private static void run(String[] args) throws Exception {
        Path dir = Path.of(args[0]);
        int threads = 1;
        boolean regen = false;
        boolean belief = false; // verification ②: hidden cards from belief samples
        int limit = Integer.MAX_VALUE;
        int workers = 0;
        String workerXmx = "2g";
        Path emit = null;
        for (int i = 1; i < args.length; i++) {
            switch (args[i]) {
                case "--threads" -> threads = Integer.parseInt(args[++i]);
                case "--workers" -> workers = Integer.parseInt(args[++i]);
                case "--worker-xmx" -> workerXmx = args[++i];
                case "--emit" -> emit = Path.of(args[++i]);
                case "--regen" -> regen = true;
                case "--belief" -> belief = true;
                case "--limit" -> limit = Integer.parseInt(args[++i]);
                default -> throw new IllegalArgumentException(args[i]);
            }
        }
        System.setProperty("java.awt.headless", "true");
        System.setProperty("java.util.Arrays.useLegacyMergeSort", "true");
        GuiBase.setInterface(new GuiDesktop());
        FModel.initialize(null, null);
        CardKeys keys = CardKeys.load();

        List<Path> logs;
        try (Stream<Path> s = Files.walk(dir)) {
            logs = s.filter(p -> p.toString().endsWith(".pb.gz")).sorted().limit(limit).toList();
        }
        if (workers > 0) {
            byWorkers(dir, logs, workers, workerXmx, regen, belief);
            return;
        }
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        boolean fRegen = regen;
        boolean fBelief = belief;
        List<Future<List<ShadowGame.Result>>> futures = new ArrayList<>();
        for (Path log : logs) {
            futures.add(pool.submit(() -> {
                List<LogRecord> records = GameRecord.read(log);
                List<ShadowGame.Result> out = new ArrayList<>();
                for (int seat : records.get(0).getHeader().getAgentSeatsList()) {
                    out.add(new ShadowGame(GameRecord.of(log, records, seat), keys, fRegen, fBelief).run());
                }
                return out;
            }));
        }
        List<ShadowGame.Result> results = new ArrayList<>();
        for (Future<List<ShadowGame.Result>> f : futures) {
            results.addAll(f.get());
        }
        pool.shutdown();
        if (emit != null) {
            try (java.io.ObjectOutputStream out = new java.io.ObjectOutputStream(Files.newOutputStream(emit))) {
                out.writeObject(new ArrayList<>(results));
                out.writeLong(peakHeapMb());
            }
            return;
        }
        report(dir, results, regen, belief, threads, "");
    }

    /** Sum of the heap pools' peak use of this JVM, in MB (sizing: memory per worker). */
    private static long peakHeapMb() {
        long peak = 0;
        for (var pool : java.lang.management.ManagementFactory.getMemoryPoolMXBeans()) {
            if (pool.getType() == java.lang.management.MemoryType.HEAP && pool.getPeakUsage() != null) {
                peak += pool.getPeakUsage().getUsed();
            }
        }
        return peak >> 20;
    }

    /** One JVM per folder of logs, {@code workers} at a time; the results reported together. */
    @SuppressWarnings("unchecked")
    private static void byWorkers(Path dir, List<Path> logs, int workers, String xmx, boolean regen, boolean belief)
            throws Exception {
        List<Path> folders = logs.stream().map(Path::getParent).distinct().sorted().toList();
        ExecutorService pool = Executors.newFixedThreadPool(workers);
        List<Future<Object[]>> runs = new ArrayList<>();
        for (Path folder : folders) {
            runs.add(pool.submit(() -> {
                Path out = folder.resolve("shadow-check.worker.bin");
                List<String> cmd = new ArrayList<>(List.of(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                    "-Xmx" + xmx, "-Dfile.encoding=UTF-8"));
                for (String k : System.getProperties().stringPropertyNames()) {
                    if (k.startsWith("magicdosa.")) {
                        cmd.add("-D" + k + "=" + System.getProperty(k));
                    }
                }
                cmd.addAll(List.of("-cp", System.getProperty("java.class.path"), ShadowCheck.class.getName(),
                    folder.toString(), "--emit", out.toString()));
                if (regen) {
                    cmd.add("--regen");
                }
                if (belief) {
                    cmd.add("--belief");
                }
                // -Dmagicdosa.stopFile=<path>: once the file exists no new worker starts and running ones are ended
                String stop = System.getProperty("magicdosa.stopFile");
                if (stop != null && Files.exists(java.nio.file.Path.of(stop))) {
                    throw new IllegalStateException("stopped before " + folder);
                }
                Process p = new ProcessBuilder(cmd).redirectErrorStream(true)
                    .redirectOutput(folder.resolve("shadow-check.worker.txt").toFile()).start();
                while (!p.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)) {
                    if (stop != null && Files.exists(java.nio.file.Path.of(stop))) {
                        p.destroyForcibly();
                        p.waitFor();
                        throw new IllegalStateException("stopped during " + folder);
                    }
                }
                int code = p.exitValue();
                if (code != 0 || !Files.exists(out)) {
                    throw new IllegalStateException("shadow worker for " + folder + " failed (exit " + code + ")");
                }
                try (java.io.ObjectInputStream in = new java.io.ObjectInputStream(Files.newInputStream(out))) {
                    return new Object[] {in.readObject(), in.readLong()};
                }
            }));
        }
        List<ShadowGame.Result> results = new ArrayList<>();
        long peak = 0;
        for (Future<Object[]> f : runs) {
            Object[] r = f.get();
            results.addAll((List<ShadowGame.Result>) r[0]);
            peak = Math.max(peak, (Long) r[1]);
        }
        pool.shutdown();
        report(dir, results, regen, belief, 1, String.format("worker JVMs: %d at a time, one per folder (%d folders), -Xmx%s;"
            + " peak heap of the busiest worker %d MB", workers, folders.size(), xmx, peak));
    }

    private static void report(Path dir, List<ShadowGame.Result> results, boolean regen, boolean belief, int threads,
                               String workersLine)
            throws IOException {
        long libFix = 0, hostFix = 0, poolFix = 0;
        long afterRecovery = 0, skipped = 0, rec1 = 0, rec2 = 0, rec3 = 0, mementos = 0, mementoNanos = 0, restoreNanos = 0;
        long decisions = 0, matched = 0, snapshots = 0, snapMatched = 0, events = 0, evMatched = 0, critical = 0,
            minorOnly = 0, errors = 0, millis = 0, rebuilds = 0, withGaps = 0;
        Map<Fingerprint.Level, Long> byLevel = new TreeMap<>();
        Map<String, Long> ends = new TreeMap<>();
        Map<String, Long> gapKinds = new TreeMap<>();
        for (ShadowGame.Result r : results) {
            decisions += r.decisions;
            afterRecovery += r.decisionsMatchedAfterRecovery;
            skipped += r.searchSkipped;
            rec1 += r.recoveries1;
            rec2 += r.recoveries2;
            rec3 += r.recoveries3;
            mementos += r.mementos;
            libFix += r.libraryCorrections;
            hostFix += r.hostRepairs;
            poolFix += r.poolOrders;
            mementoNanos += r.mementoNanos;
            restoreNanos += r.restoreNanos;
            matched += r.decisionsMatched;
            snapshots += r.snapshots;
            snapMatched += r.snapshotsMatched;
            events += r.events;
            evMatched += r.eventsMatched;
            critical += r.criticalMismatches;
            minorOnly += r.minorOnlyMismatches;
            millis += r.millis;
            rebuilds += r.rebuilds;
            r.mismatchesByLevel.forEach((l, n) -> byLevel.merge(l, (long) n, Long::sum));
            if (r.error != null) {
                errors++;
            }
            ends.merge(r.error != null ? "error" : r.end, 1L, Long::sum);
            if (!r.gaps.isEmpty()) {
                withGaps++;
            }
            for (String g : r.gaps) {
                String kind = g.replaceAll("^turn \\d+: ", "").replaceAll("[0-9a-f]{8}-[0-9a-f-]{27}", "<oracle>")
                    .replaceAll("\\d+", "#");
                gapKinds.merge(kind, 1L, Long::sum);
            }
        }
        Path out = dir.resolve(regen ? "shadow-check-regen.txt" : belief ? "shadow-check-belief.txt" : "shadow-check.txt");
        long samples2 = 0, sampleFailures = 0, swaps = 0, swapFailures = 0;
        for (ShadowGame.Result r : results) {
            samples2 += r.samples2;
            sampleFailures += r.sampleFailures;
            swaps += r.identitySwaps;
            swapFailures += r.identitySwapFailures;
        }
        try (PrintStream p = new PrintStream(Files.newOutputStream(out), true, "UTF-8")) {
            for (PrintStream s : List.of(System.out, p)) {
                s.println("shadow check (verification " + (belief ? "2: hidden cards from belief samples" : "1")
                    + (regen ? ", forced regeneration every turn" : "") + "): "
                    + results.size() + " streams, " + threads + " thread(s)");
                if (belief) {
                    s.println("belief samples drawn " + samples2 + " (no fitting 60: " + sampleFailures + "); identity swaps "
                        + swaps + ", failures " + swapFailures + " (diagnostic); gate: after recovery >= 99 %, search"
                        + " skipped <= 1 %");
                }
                if (!workersLine.isEmpty()) {
                    s.println(workersLine);
                }
                if (threads > 1) {
                    s.println("NOT VALID FOR A VERDICT: " + threads + " threads in one JVM (Forge games running at the same"
                        + " time in one JVM affect each other; use --workers)");
                }
                s.println("decision fingerprints: " + matched + " of " + decisions + " match ("
                    + pct(matched, decisions) + "); mismatches: critical " + critical + ", minor only " + minorOnly
                    + ", by level " + byLevel);
                s.println("after recovery: " + afterRecovery + " of " + decisions + " match (" + pct(afterRecovery, decisions)
                    + "); search skipped " + skipped + " (" + pct(skipped, decisions) + "); recoveries: memento " + rec1
                    + ", rebuild " + rec2 + ", search skip " + rec3);
                s.println("turn-start mementos: " + mementos + ", " + String.format("%.2f", mementos == 0 ? 0 : mementoNanos / 1e6 / mementos)
                    + " ms each; restores took " + String.format("%.1f", restoreNanos / 1e6) + " ms in all; ability hosts repaired after restores: " + hostFix);
                s.println("library orders taken from the hidden state at a decision or turn start (verification 1): " + libFix);
                s.println("mana pools put in the real (public) order at a decision: " + poolFix);
                s.println("turn-start snapshots: " + snapMatched + " of " + snapshots + " match (" + pct(snapMatched, snapshots) + ")");
                s.println("events (diagnostic): " + evMatched + " of " + events + " identical (" + pct(evMatched, events) + ")");
                s.println("rebuilds: " + rebuilds + "; streams with builder gaps: " + withGaps);
                s.println("ends: " + ends);
                s.println("run errors: " + errors + "; time " + millis + " ms (sum over streams)");
                s.println("builder gaps by kind:");
                gapKinds.forEach((k, n) -> s.println("  " + n + "  " + k));
                boolean pass = errors == 0 && matched == decisions && snapMatched == snapshots && decisions > 0;
                s.println("RESULT " + (pass ? "PASS" : "FAIL"));
                s.println();
                // by pairing (game names <deck>-vs-<deck>-m<N>-g<N>): first try and after recovery
                Map<String, int[]> pairings = new java.util.TreeMap<>();
                for (ShadowGame.Result r : results) {
                    String pairing = r.game.replaceAll("-m\\d+-g\\d+.*$", "");
                    int[] c = pairings.computeIfAbsent(pairing, k -> new int[3]);
                    c[0] += r.decisions;
                    c[1] += r.decisionsMatched;
                    c[2] += r.decisionsMatchedAfterRecovery;
                }
                // event-log replay (Q19): games reproduced end to end, the other seat's decisions included
                int reproduced = 0, sameEnd = 0, withEnd = 0;
                long oppRecorded = 0, oppReplayed = 0;
                List<String> notReproduced = new ArrayList<>();
                for (ShadowGame.Result r : results) {
                    reproduced += r.reproduced ? 1 : 0;
                    sameEnd += r.sameEnd ? 1 : 0;
                    withEnd += r.endReal.isEmpty() ? 0 : 1;
                    oppRecorded += r.opponentRecorded;
                    oppReplayed += r.opponentReplayed;
                    if (!r.reproduced) {
                        notReproduced.add(r.game + ": decisions " + r.decisionsMatched + "/" + r.decisions + ", other seat "
                            + r.opponentReplayed + "/" + r.opponentRecorded + ", replay problems " + r.replayMismatches.size()
                            + ", same end " + r.sameEnd + (r.sameEnd ? "" : " (real " + r.endReal + "; shadow " + r.endShadow + ")"));
                    }
                }
                s.println("event-log replay (Q19): " + reproduced + " of " + results.size() + " games reproduced end to end;"
                    + " same game end " + sameEnd + " of " + withEnd + "; the other seat's recorded actions and choices used "
                    + oppReplayed + " of " + oppRecorded);
                notReproduced.forEach(x -> s.println("  not reproduced: " + x));
                s.println("by pairing: decisions, first try, after recovery");
                pairings.forEach((name, c) -> s.printf("  %-40s %6d  %6.2f%%  %6.2f%%%n", name, c[0],
                    100.0 * c[1] / Math.max(1, c[0]), 100.0 * c[2] / Math.max(1, c[0])));
                s.println();
                for (ShadowGame.Result r : results) {
                    boolean clean = r.error == null && r.decisionsMatched == r.decisions && r.snapshotsMatched == r.snapshots;
                    if (clean && r.replayMismatches.isEmpty() && r.eventSamples.isEmpty()) {
                        continue;
                    }
                    s.println("== " + r.game + ": decisions " + r.decisionsMatched + "/" + r.decisions + " (after recovery " + r.decisionsMatchedAfterRecovery
                        + "), snapshots "
                        + r.snapshotsMatched + "/" + r.snapshots + ", events " + r.eventsMatched + "/" + r.events
                        + ", end: " + r.end);
                    if (r.error != null) {
                        s.println("  ERROR " + r.error);
                    }
                    r.samples.forEach(x -> s.println("  " + x));
                    r.replayMismatches.forEach(x -> s.println("  replay: " + x));
                    r.eventSamples.forEach(x -> s.println("  event: " + x));
                }
            }
        }
        System.out.println("summary: " + out);
    }

    private static String pct(long a, long b) {
        return b == 0 ? "-" : String.format("%.2f%%", 100.0 * a / b);
    }
}
