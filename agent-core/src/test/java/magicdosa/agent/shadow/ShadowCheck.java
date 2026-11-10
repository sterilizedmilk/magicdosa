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
 *     "-Dexec.args=&lt;abs log dir&gt; [--threads N] [--regen] [--limit N]"</pre>
 */
public final class ShadowCheck {
    public static void main(String[] args) throws Exception {
        Path dir = Path.of(args[0]);
        int threads = 1;
        boolean regen = false;
        int limit = Integer.MAX_VALUE;
        for (int i = 1; i < args.length; i++) {
            switch (args[i]) {
                case "--threads" -> threads = Integer.parseInt(args[++i]);
                case "--regen" -> regen = true;
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
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        boolean fRegen = regen;
        List<Future<List<ShadowGame.Result>>> futures = new ArrayList<>();
        for (Path log : logs) {
            futures.add(pool.submit(() -> {
                List<LogRecord> records = GameRecord.read(log);
                List<ShadowGame.Result> out = new ArrayList<>();
                for (int seat : records.get(0).getHeader().getAgentSeatsList()) {
                    out.add(new ShadowGame(GameRecord.of(log, records, seat), keys, fRegen).run());
                }
                return out;
            }));
        }
        List<ShadowGame.Result> results = new ArrayList<>();
        for (Future<List<ShadowGame.Result>> f : futures) {
            results.addAll(f.get());
        }
        pool.shutdown();
        report(dir, results, regen, threads);
    }

    private static void report(Path dir, List<ShadowGame.Result> results, boolean regen, int threads) throws IOException {
        long libFix = 0;
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
        Path out = dir.resolve(regen ? "shadow-check-regen.txt" : "shadow-check.txt");
        try (PrintStream p = new PrintStream(Files.newOutputStream(out), true, "UTF-8")) {
            for (PrintStream s : List.of(System.out, p)) {
                s.println("shadow check (verification 1" + (regen ? ", forced regeneration every turn" : "") + "): "
                    + results.size() + " streams, " + threads + " thread(s)");
                s.println("decision fingerprints: " + matched + " of " + decisions + " match ("
                    + pct(matched, decisions) + "); mismatches: critical " + critical + ", minor only " + minorOnly
                    + ", by level " + byLevel);
                s.println("after recovery: " + afterRecovery + " of " + decisions + " match (" + pct(afterRecovery, decisions)
                    + "); search skipped " + skipped + " (" + pct(skipped, decisions) + "); recoveries: memento " + rec1
                    + ", rebuild " + rec2 + ", search skip " + rec3);
                s.println("turn-start mementos: " + mementos + ", " + String.format("%.2f", mementos == 0 ? 0 : mementoNanos / 1e6 / mementos)
                    + " ms each; restores took " + String.format("%.1f", restoreNanos / 1e6) + " ms in all");
                s.println("library orders taken from the hidden state at a decision (verification 1): " + libFix);
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
                for (ShadowGame.Result r : results) {
                    boolean clean = r.error == null && r.decisionsMatched == r.decisions && r.snapshotsMatched == r.snapshots;
                    if (clean && r.replayMismatches.isEmpty() && r.eventSamples.isEmpty()) {
                        continue;
                    }
                    s.println("== " + r.game + ": decisions " + r.decisionsMatched + "/" + r.decisions + ", snapshots "
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
