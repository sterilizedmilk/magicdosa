package magicdosa.runner.forge.harness;

import forge.GuiDesktop;
import forge.deck.Deck;
import forge.gui.GuiBase;
import forge.model.FModel;
import magicdosa.adapter.forge.CardKeys;
import magicdosa.adapter.forge.DecisionStats;
import magicdosa.runner.forge.ForgeMatchRunner;
import magicdosa.agent.DeckFiles;
import magicdosa.protocol.v1.DecisionType;
import magicdosa.protocol.v1.GameOver;
import magicdosa.protocol.v1.Mode;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Phase-1 gate (CLAUDE.md "지금 단계"): a uniform random policy in seat 0 against Forge AI plays N Bo3 matches through
 * the forge-adapter; there must be no unintended fallback, protocol error, ignored constraint, unmapped card or ability
 * and (with a log dir) no log problem. Sequential. By default agent-core runs as a separate JVM over gRPC (see
 * {@link AgentConnection} for the last argument: spawn, host:port or local). The summary is printed and saved as
 * {@code runs/<date>-RandomGames-<matches>/summary.txt} next to the decks directory. Main program in the test sources,
 * so mvn install does not run it:
 *
 * <pre>mvn -pl runner/forge exec:exec "-Dexec.args=&lt;abs decks dir&gt; IzzetProwess.dck EsperBlink.dck 20 &lt;abs log dir or -&gt; [spawn]"</pre>
 */
public final class RandomGames {
    private RandomGames() {}

    /** One run's report and verdict, plus the totals AllPairings adds up. */
    record Summary(List<String> lines, boolean pass, DecisionStats stats, int matches, int games, int errors,
                   double seconds, int logProblems, Set<String> unmapped) implements java.io.Serializable {}

    /** Headless Forge; samples the priority candidates (CandidateCheck) unless set otherwise. Call once per JVM. */
    static void initForge() {
        if (System.getProperty("magicdosa.candidateCheck") == null) {
            System.setProperty("magicdosa.candidateCheck", "20");
        }
        System.setProperty("java.awt.headless", "true");
        System.setProperty("java.util.Arrays.useLegacyMergeSort", "true");
        GuiBase.setInterface(new GuiDesktop());
        FModel.initialize(null, null);
    }

    public static void main(String[] args) throws Exception {
        String deckDir = args[0];
        int matches = Integer.parseInt(args[3]);
        Path logDir = args.length > 4 && !args[4].equals("-") ? Path.of(args[4]) : null;
        String agentSpec = args.length > 5 ? args[5] : "spawn";
        initForge();
        CardKeys keys = CardKeys.load();
        AgentConnection agent = AgentConnection.open(agentSpec);
        // optional 7th argument: match ID prefix (default "m"), as AllPairings names its matches
        Summary s = run(deckDir, args[1], args[2], matches, logDir, agent, keys, args.length > 6 ? args[6] : "m");
        agent.close(); // a spawned agent-core reports the protocol errors it saw when it stops
        int protocolErrors = agent.protocolErrorCount();
        List<String> lines = new ArrayList<>(s.lines());
        lines.add("protocol errors seen by agent-core: " + (protocolErrors < 0 ? "not reported" : protocolErrors) + " "
            + agent.protocolErrors().subList(0, Math.min(10, agent.protocolErrors().size())));
        if (agent.shardReport() != null) {
            lines.add("training shards (encoder E5; vocabulary misses must be 0): " + agent.shardReport());
        }
        boolean pass = s.pass() && protocolErrors == 0 && agent.shardsClean();
        lines.add("RESULT " + (pass ? "PASS" : "FAIL"));
        lines.forEach(System.out::println);
        save(Path.of(deckDir), "RandomGames", matches, lines);
        System.exit(pass ? 0 : 1);
    }

    /** Plays the matches and builds the report (without the RESULT line). */
    static Summary run(String deckDir, String deck0Name, String deck1Name, int matches, Path logDir, AgentConnection agent,
                       CardKeys keys) throws Exception {
        return run(deckDir, deck0Name, deck1Name, matches, logDir, agent, keys, "m");
    }

    /**
     * First match seed (default 1000): a pairing split over several workers gives each part its own range
     * ({@code -Dmagicdosa.seedBase}).
     */
    private static final long SEED_BASE = Long.getLong("magicdosa.seedBase", 1000);

    /** {@code matchPrefix} keeps match and game IDs unique when one agent-core serves several runs (shard file names). */
    static Summary run(String deckDir, String deck0Name, String deck1Name, int matches, Path logDir, AgentConnection agent,
                       CardKeys keys, String matchPrefix) throws Exception {
        Deck deck0 = DeckFiles.load(new File(deckDir, deck0Name));
        Deck deck1 = DeckFiles.load(new File(deckDir, deck1Name));
        ForgeMatchRunner runner = new ForgeMatchRunner(agent.client(), keys, Mode.MODE_TRAINING, "forge@87cb532",
            Boolean.getBoolean("magicdosa.reproducible")); // debugging: Forge AI without its wall-clock limit
        DecisionStats total = new DecisionStats();
        int[] matchWins = new int[2];
        int[] gameWins = new int[2];
        int games = 0;
        int errors = 0;
        long turns = 0;
        Map<String, Integer> reasons = new TreeMap<>();
        Set<String> unmappedBefore = new TreeSet<>(keys.unmapped());
        long start = System.nanoTime();
        for (int m = 0; m < matches; m++) {
            try {
                // -Dmagicdosa.agentSeats=none: plain Forge AI in both seats (win-rate reference, no decision layer)
                Set<Integer> seats = "none".equals(System.getProperty("magicdosa.agentSeats")) ? Set.of() : Set.of(0);
                ForgeMatchRunner.Result r = runner.playMatch(matchPrefix + m, deck0, deck1, seats, SEED_BASE + m, logDir);
                total.add(r.stats());
                gameWins[0] += r.gamesWon()[0];
                gameWins[1] += r.gamesWon()[1];
                if (r.gamesWon()[0] != r.gamesWon()[1]) {
                    matchWins[r.gamesWon()[0] > r.gamesWon()[1] ? 0 : 1]++;
                }
                games += r.games();
                for (GameOver o : r.overs()) {
                    turns += o.getTurns();
                    reasons.merge(o.getReason().name().replace("GAME_END_REASON_", "").toLowerCase(), 1, Integer::sum);
                }
            } catch (Exception | Error e) {
                errors++;
                System.out.println("MATCH " + m + " FAILED: " + e);
                e.printStackTrace(System.out);
            }
        }
        double seconds = (System.nanoTime() - start) / 1e9;
        List<String> out = new ArrayList<>();
        out.add(String.format("%s (agent, random) vs %s (Forge AI): matches=%d games=%d errors=%d", deck0Name, deck1Name,
            matches, games, errors));
        out.add(String.format("match wins agent/forge-ai: %d/%d; game wins: %d/%d; end reasons %s; avg turns %.1f",
            matchWins[0], matchWins[1], gameWins[0], gameWins[1], reasons, games == 0 ? 0.0 : (double) turns / games));
        int decided = total.decided().values().stream().mapToInt(Integer::intValue).sum();
        out.add(String.format("throughput: %.1f s, %.2f s/game, %.2f ms/decision waiting for agent-core (gRPC + agent-core),"
                + " 1 match at a time on 1 thread, heap used %d MB", seconds, games == 0 ? 0.0 : seconds / games,
            decided == 0 ? 0.0 : total.decideNanos() / 1e6 / decided,
            (Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()) >> 20));
        out.add(String.format("priority stops (Q14): %d requests (%.1f per game), %d auto passes, %.0f%% with an action other"
                + " than pass and mana abilities", total.priorityAsks(), games == 0 ? 0.0 : (double) total.priorityAsks() / games,
            total.autoPasses(), total.priorityAsks() == 0 ? 0.0 : 100.0 * total.priorityAsksWithAction() / total.priorityAsks()));
        out.add("by decision type (decided / intended delegations / refusals):");
        Set<DecisionType> types = new TreeSet<>(total.decided().keySet());
        types.addAll(total.delegatedByType().keySet());
        types.addAll(total.rejectedByType().keySet());
        for (DecisionType t : types) {
            out.add(String.format("  %-34s %7d %5d %5d", t.name().replace("DECISION_TYPE_", ""),
                total.decided().getOrDefault(t, 0), total.delegatedByType().getOrDefault(t, 0),
                total.rejectedByType().getOrDefault(t, 0)));
        }
        out.add("intended delegations: " + total.delegated());
        out.add("unclassified decisions (Forge AI answered, see docs/player-controller-methods.md): " + total.unclassified());
        Map<String, Long> fallbacks = new TreeMap<>();
        total.fallbacks().forEach(f -> fallbacks.merge(f, 1L, Long::sum));
        out.add("unintended fallbacks: " + total.fallbacks().size() + " " + fallbacks);
        out.add("engine refusals asked again (not fallbacks): " + total.rejected());
        int ignored = total.rejections(DecisionStats.RejectionKind.IGNORED_CONSTRAINT);
        out.add("refusals of answers that broke a stated constraint (agent-core bug): " + ignored);
        out.add("safety caps (runaway repeats): " + total.safetyCaps());
        total.notes().forEach((k, v) -> out.add("  " + v + "x " + k));
        out.add("candidate omissions (sampled pool checks): " + total.candidateOmissions() + " in " + total.candidateChecks() + " checks");
        out.add(costOrderLine(total));
        out.add("game copies (agent-core GameCopier, candidate and copy checks): " + total.copyFailures() + " failed of "
            + total.copies());
        out.add("tokens identified by their creating ability (Q3 fallback): " + keys.tokenFallbacks());
        Set<String> unmapped = new TreeSet<>(keys.unmapped());
        unmapped.removeAll(unmappedBefore);
        out.add("unmapped: " + unmapped.size());
        unmapped.forEach(u -> out.add("  " + u));
        List<String> logProblems = logDir == null ? List.of() : LogCheck.check(logDir);
        if (logDir != null) {
            out.add("log problems: " + logProblems.size() + " " + logProblems.subList(0, Math.min(10, logProblems.size())));
        }
        boolean pass = logProblems.isEmpty() && errors == 0 && total.fallbacks().isEmpty() && ignored == 0
            && unmapped.isEmpty() && total.errors() == 0 && total.unclassified().isEmpty() && total.copyFailures() == 0;
        return new Summary(out, pass, total, matches, games, errors, seconds, logProblems.size(), unmapped);
    }

    /** chooseSingleStaticAbility (601.2f reduction order): where the calls came from and whether the order mattered. */
    static String costOrderLine(DecisionStats s) {
        return "cost change order (chooseSingleStaticAbility): " + s.costOrderComputations()
            + " cost-computation calls (not decisions), " + s.costOrderRealCalls() + " calls in real payments, "
            + s.costOrderMulti() + " real payments with 2+ reductions, " + s.costOrderDiffered()
            + " where the final cost depended on the order";
    }

    /** Writes {@code runs/<date>-<harness>-<matches>/summary.txt} beside the decks directory. */
    static void save(Path deckDir, String harness, int matches, List<String> lines) throws Exception {
        Path dir = deckDir.toAbsolutePath().getParent().resolve("runs").resolve(LocalDate.now() + "-" + harness + "-" + matches);
        Files.createDirectories(dir);
        Files.write(dir.resolve("summary.txt"), lines, StandardCharsets.UTF_8);
        System.out.println("summary: " + dir.resolve("summary.txt"));
    }
}
