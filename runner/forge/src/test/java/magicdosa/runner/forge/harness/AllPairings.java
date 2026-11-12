package magicdosa.runner.forge.harness;

import magicdosa.adapter.forge.CardKeys;
import magicdosa.adapter.forge.DecisionStats;
import magicdosa.protocol.v1.DecisionType;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * RandomGames for every pairing of the decks in the decks directory (2026-11-06 plan, section 4): each deck as the
 * random agent (seat 0) against each deck as Forge AI, mirrors included, N matches each, with game logs. Also the
 * phase-1 final gate (design: "1단계 완료 판정"): a gate table over all pairings first (unintended fallbacks, protocol
 * errors, unmapped names, unclassified decisions and ignored-constraint refusals all 0; other refusals by cause; every
 * protocol decision type and whether it was seen; the intended delegations to watch; priority stops and throughput),
 * then one line per pairing and each pairing's full report, saved as {@code runs/<date>-AllPairings-<n>/summary.txt}.
 *
 * <pre>mvn -pl runner/forge exec:exec "-Dexec.mainClass=magicdosa.runner.forge.harness.AllPairings"
 *     "-Dexec.args=&lt;abs decks dir&gt; 5 &lt;abs empty log dir&gt; [spawn|local|host:port] [--workers N] [--worker-xmx 2g]"</pre>
 *
 * {@code --workers N} (2026-11-11 decision): pairings run in N worker JVMs at a time ({@link PairingWorker}, one JVM
 * per pairing, its games one after another), each with its own agent-core connection by the agent spec (spawn: its own
 * agent-core JVM). Forge games running at the same time in one JVM affect each other (docs/upstream-candidates.md), so
 * gate verdicts come from runs without threads. {@code --threads N} (pairings on threads of this JVM) is kept for
 * comparison only; its result is marked as not valid for a verdict.
 */
public final class AllPairings {
    private AllPairings() {}

    /** Intended delegations the gate reports by count (2026-11-07 wrap-up): copy retargets, uncommon costs, division. */
    private static final List<String> WATCHED = List.of("chooseNewTargetsFor", "cost:", "chooseTargetsFor(divided)",
        "orderCosts");

    /** Runs one pairing in its own JVM ({@link PairingWorker}) and reads its result. */
    private static PairingWorker.Result worker(String deckDir, String a, String b, int matches, Path logs, String agentSpec,
                                               String name, Path logRoot, String xmx) throws Exception {
        Path result = logRoot.resolve(name + ".worker.bin");
        List<String> cmd = new ArrayList<>();
        cmd.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
        cmd.add("-Xmx" + xmx);
        cmd.add("-Dfile.encoding=UTF-8");
        for (String k : System.getProperties().stringPropertyNames()) {
            if (k.startsWith("magicdosa.")) {
                cmd.add("-D" + k + "=" + System.getProperty(k)); // reproducible, agentXmx, candidateCheck, shards, ...
            }
        }
        cmd.add("-cp");
        cmd.add(System.getProperty("java.class.path"));
        cmd.add(PairingWorker.class.getName());
        cmd.addAll(List.of(deckDir, a, b, String.valueOf(matches), logs.toString(), agentSpec, name + "-m", result.toString()));
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true)
            .redirectOutput(logRoot.resolve(name + ".worker.txt").toFile()).start();
        int code = p.waitFor();
        if (code != 0 || !Files.exists(result)) {
            throw new IllegalStateException("worker for " + name + " failed (exit " + code + "), see " + name + ".worker.txt");
        }
        try (java.io.ObjectInputStream in = new java.io.ObjectInputStream(Files.newInputStream(result))) {
            return (PairingWorker.Result) in.readObject();
        }
    }

    public static void main(String[] args) throws Exception {
        String deckDir = args[0];
        int matches = Integer.parseInt(args[1]);
        Path logRoot = Path.of(args[2]);
        String agentSpec = "spawn";
        int threads = 1;
        int workers = 0;
        String workerXmx = "2g";
        for (int i = 3; i < args.length; i++) {
            if (args[i].equals("--threads")) {
                threads = Integer.parseInt(args[++i]);
            } else if (args[i].equals("--workers")) {
                workers = Integer.parseInt(args[++i]);
            } else if (args[i].equals("--worker-xmx")) {
                workerXmx = args[++i];
            } else {
                agentSpec = args[i];
            }
        }
        RandomGames.initForge();
        CardKeys keys = CardKeys.load();
        Set<String> unmappedBefore = new TreeSet<>(keys.unmapped());
        String[] decks = new File(deckDir).list((d, n) -> n.endsWith(".dck"));
        Arrays.sort(decks);
        List<String> table = new ArrayList<>();
        List<String> details = new ArrayList<>();
        boolean pass = true;
        DecisionStats total = new DecisionStats();
        int allMatches = 0;
        int games = 0;
        int errors = 0;
        int logProblems = 0;
        double seconds = 0;
        Set<String> unmapped = new TreeSet<>();
        AgentConnection agent = workers > 0 ? null : AgentConnection.open(agentSpec);
        int poolSize = workers > 0 ? workers : threads;
        // --threads N: pairings run on N threads at once (2026-11-10). Each match seeds its own thread's Forge random
        // (ForgeRandom, InheritableThreadLocal) and creates its game through GameCreation; one agent-core serves every
        // game on its own stream. Results are read back in pairing order, so the summary does not depend on threads.
        ExecutorService pool = Executors.newFixedThreadPool(poolSize);
        List<String> names = new ArrayList<>();
        List<Future<PairingWorker.Result>> runs = new ArrayList<>();
        String fWorkerXmx = workerXmx;
        String fAgentSpec = agentSpec;
        boolean byWorkers = workers > 0;
        long wallStart = System.nanoTime();
        List<PairingWorker.Result> workerResults = new ArrayList<>();
        for (String a : decks) {
            for (String b : decks) {
                String name = a.replace(".dck", "") + "-vs-" + b.replace(".dck", "");
                Path logs = logRoot.resolve(name);
                Files.createDirectories(logs);
                names.add(name);
                if (byWorkers) {
                    runs.add(pool.submit(() -> {
                        PairingWorker.Result r = worker(deckDir, a, b, matches, logs, fAgentSpec, name, logRoot, fWorkerXmx);
                        System.out.println(String.format("%-40s %s  %s  (peak heap %d of %d MB)", name,
                            r.summary().pass() ? "PASS" : "FAIL", r.summary().lines().get(1), r.peakHeapMb(), r.maxHeapMb()));
                        return r;
                    }));
                    continue;
                }
                runs.add(pool.submit(() -> {
                    RandomGames.Summary r = RandomGames.run(deckDir, a, b, matches, logs, agent, keys, name + "-m");
                    System.out.println(String.format("%-40s %s  %s", name, r.pass() ? "PASS" : "FAIL", r.lines().get(1)));
                    return new PairingWorker.Result(r, 0, true, "", Set.of(), 0, 0);
                }));
            }
        }
        pool.shutdown();
        for (int k = 0; k < runs.size(); k++) {
            {
                String name = names.get(k);
                PairingWorker.Result wr = runs.get(k).get();
                RandomGames.Summary s = wr.summary();
                workerResults.add(wr);
                pass &= s.pass();
                total.add(s.stats());
                allMatches += s.matches();
                games += s.games();
                errors += s.errors();
                logProblems += s.logProblems();
                seconds += s.seconds();
                unmapped.addAll(s.unmapped());
                table.add(String.format("%-40s %s  %s", name, s.pass() ? "PASS" : "FAIL", s.lines().get(1)));
                details.add("== " + name);
                details.addAll(s.lines());
            }
        }
        double wallSeconds = (System.nanoTime() - wallStart) / 1e9;
        int protocolErrors;
        boolean shardsClean;
        String shardReport;
        long peakHeap = 0;
        long maxHeap = 0;
        if (agent != null) {
            agent.close();
            // with threads, a pairing's own unmapped list may name what another pairing met first; the gate uses the run's
            unmapped = new TreeSet<>(keys.unmapped());
            unmapped.removeAll(unmappedBefore);
            protocolErrors = agent.protocolErrorCount();
            shardsClean = agent.shardsClean();
            shardReport = agent.shardReport();
        } else {
            protocolErrors = 0;
            shardsClean = true;
            List<String> reports = new ArrayList<>();
            for (PairingWorker.Result r : workerResults) {
                unmapped.addAll(r.unmapped());
                protocolErrors = protocolErrors < 0 || r.protocolErrors() < 0 ? -1 : protocolErrors + r.protocolErrors();
                shardsClean &= r.shardsClean();
                if (r.shardReport() != null && !r.shardReport().isEmpty()) {
                    reports.add(r.shardReport());
                }
                peakHeap = Math.max(peakHeap, r.peakHeapMb());
                maxHeap = Math.max(maxHeap, r.maxHeapMb());
            }
            shardReport = reports.isEmpty() ? null : reports.size() + " workers, first: " + reports.get(0);
        }
        String shardDir = System.getProperty("magicdosa.shards");
        long shardFiles = -1;
        if (shardDir != null) {
            try (var files = Files.list(Path.of(shardDir))) {
                shardFiles = files.filter(p -> p.toString().endsWith(".arrow")).count();
            }
            shardsClean &= shardFiles == games; // one file per game, none overwritten
        }
        pass &= protocolErrors == 0 && shardsClean;

        List<String> gate = new ArrayList<>();
        int ignored = total.rejections(DecisionStats.RejectionKind.IGNORED_CONSTRAINT);
        gate.add(String.format("gate over %d pairings: matches=%d games=%d match errors=%d", table.size(), allMatches, games, errors));
        Map<String, Long> byFallback = new TreeMap<>();
        total.fallbacks().forEach(f -> byFallback.merge(f, 1L, Long::sum));
        gate.add("unintended fallbacks: " + total.fallbacks().size() + " " + byFallback);
        Map<String, Integer> notes = new TreeMap<>();
        total.notes().forEach((k, v) -> {
            if (k.startsWith("Forge AI's answer") || k.contains("(shadow Forge AI)")) {
                notes.put(k, v);
            }
        });
        if (!notes.isEmpty()) {
            gate.add("delegation notes: " + notes);
        }
        gate.add("protocol errors seen by agent-core: " + (protocolErrors < 0 ? "not reported" : protocolErrors));
        gate.add("unmapped names: " + unmapped.size() + (unmapped.isEmpty() ? "" : " " + unmapped));
        gate.add("unclassified decisions: " + total.unclassified().values().stream().mapToInt(Integer::intValue).sum()
            + (total.unclassified().isEmpty() ? "" : " " + total.unclassified()));
        gate.add("ignored-constraint refusals: " + ignored);
        gate.add("other refusals by cause (asked again, not fallbacks): " + total.rejected());
        gate.add("adapter errors (translation, send): " + total.errors() + "; log problems: " + logProblems
            + "; safety caps: " + total.safetyCaps());
        gate.add(RandomGames.costOrderLine(total));
        total.notes().forEach((k, v) -> {
            if (k.startsWith("cost change order changes the cost")) {
                gate.add("  " + v + "x " + k);
            }
        });
        gate.add("game copies (agent-core GameCopier): " + total.copyFailures() + " failed of " + total.copies()
            + "; candidate omissions " + total.candidateOmissions() + " in " + total.candidateChecks() + " checks");
        gate.add("training shards: " + shardReport + (shardFiles < 0 ? "" : "; files " + shardFiles + " for " + games
            + " games") + (shardsClean ? "" : " NOT CLEAN"));
        gate.add("decision types (decided / intended delegations / refusals):");
        List<String> neverSeen = new ArrayList<>();
        List<String> neverDecided = new ArrayList<>();
        for (DecisionType t : DecisionType.values()) {
            if (t == DecisionType.DECISION_TYPE_UNSPECIFIED || t == DecisionType.UNRECOGNIZED) {
                continue;
            }
            int d = total.decided().getOrDefault(t, 0);
            int g = total.delegatedByType().getOrDefault(t, 0);
            int r = total.rejectedByType().getOrDefault(t, 0);
            String n = t.name().replace("DECISION_TYPE_", "");
            gate.add(String.format("  %-34s %8d %6d %5d", n, d, g, r));
            if (d + g + r == 0) {
                neverSeen.add(n);
            } else if (d == 0) {
                neverDecided.add(n);
            }
        }
        gate.add("decision types never seen: " + (neverSeen.isEmpty() ? "none" : neverSeen));
        gate.add("decision types seen only as delegations: " + (neverDecided.isEmpty() ? "none" : neverDecided));
        Map<String, Integer> watched = new TreeMap<>();
        for (String w : WATCHED) {
            watched.put(w.endsWith(":") ? w + "*" : w, total.delegated().entrySet().stream()
                .filter(e -> w.endsWith(":") ? e.getKey().startsWith(w) : e.getKey().equals(w))
                .mapToInt(Map.Entry::getValue).sum());
        }
        gate.add("watched intended delegations: " + watched);
        gate.add("all intended delegations: " + total.delegated());
        int decided = total.decided().values().stream().mapToInt(Integer::intValue).sum();
        gate.add(String.format("priority stops (Q14): %d requests (%.1f per game), %d auto passes, %.0f%% with an action",
            total.priorityAsks(), games == 0 ? 0.0 : (double) total.priorityAsks() / games, total.autoPasses(),
            total.priorityAsks() == 0 ? 0.0 : 100.0 * total.priorityAsksWithAction() / total.priorityAsks()));
        if (workers > 0) {
            gate.add(String.format("worker JVMs: %d at a time, one per pairing, -Xmx%s; peak heap of the busiest worker %d MB"
                + " (max %d MB); agent-core: %s per worker", workers, workerXmx, peakHeap, maxHeap, agentSpec));
        } else if (threads > 1) {
            gate.add("NOT VALID FOR A GATE VERDICT: " + threads + " threads in one JVM (Forge games running at the same time"
                + " in one JVM affect each other; use --workers)");
            pass = false;
        }
        gate.add(String.format("throughput: %d thread(s), wall %.0f s, %.1f games/min, %.2f s/game per thread (sum %.0f s),"
                + " %d agent-core decisions, %.2f ms/decision waiting for agent-core", threads, wallSeconds,
            wallSeconds == 0 ? 0.0 : games * 60.0 / wallSeconds, games == 0 ? 0.0 : seconds / games, seconds, decided,
            decided == 0 ? 0.0 : total.decideNanos() / 1e6 / decided));
        gate.add("RESULT " + (pass ? "PASS" : "FAIL"));

        List<String> lines = new ArrayList<>(gate);
        lines.add("");
        lines.addAll(table);
        lines.add("");
        lines.addAll(details);
        gate.forEach(System.out::println);
        RandomGames.save(Path.of(deckDir), "AllPairings", matches, lines);
        System.exit(pass ? 0 : 1);
    }
}
