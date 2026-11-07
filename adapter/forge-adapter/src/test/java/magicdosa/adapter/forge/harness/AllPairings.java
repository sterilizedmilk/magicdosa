package magicdosa.adapter.forge.harness;

import magicdosa.adapter.forge.CardKeys;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * RandomGames for every pairing of the decks in the decks directory (2026-11-06 plan, section 4): each deck as the
 * random agent (seat 0) against each deck as Forge AI, mirrors included, N matches each, with game logs. One summary
 * line per pairing plus each pairing's full report, saved as {@code runs/<date>-AllPairings-<n>/summary.txt}. PASS
 * needs every pairing to pass.
 *
 * <pre>mvn -pl adapter/forge-adapter exec:exec "-Dexec.mainClass=magicdosa.adapter.forge.harness.AllPairings"
 *     "-Dexec.args=&lt;abs decks dir&gt; 5 &lt;abs empty log dir&gt; [spawn|local|host:port]"</pre>
 */
public final class AllPairings {
    private AllPairings() {}

    public static void main(String[] args) throws Exception {
        String deckDir = args[0];
        int matches = Integer.parseInt(args[1]);
        Path logRoot = Path.of(args[2]);
        String agentSpec = args.length > 3 ? args[3] : "spawn";
        RandomGames.initForge();
        CardKeys keys = CardKeys.load();
        String[] decks = new File(deckDir).list((d, n) -> n.endsWith(".dck"));
        Arrays.sort(decks);
        List<String> table = new ArrayList<>();
        List<String> details = new ArrayList<>();
        boolean pass = true;
        AgentConnection agent = AgentConnection.open(agentSpec);
        for (String a : decks) {
            for (String b : decks) {
                String name = a.replace(".dck", "") + "-vs-" + b.replace(".dck", "");
                Path logs = logRoot.resolve(name);
                Files.createDirectories(logs);
                RandomGames.Summary s = RandomGames.run(deckDir, a, b, matches, logs, agent, keys);
                pass &= s.pass();
                table.add(String.format("%-40s %s  %s", name, s.pass() ? "PASS" : "FAIL", s.lines().get(1)));
                details.add("== " + name);
                details.addAll(s.lines());
            }
        }
        agent.close();
        int protocolErrors = agent.protocolErrorCount();
        pass &= protocolErrors == 0;
        List<String> lines = new ArrayList<>(table);
        lines.add("protocol errors seen by agent-core: " + (protocolErrors < 0 ? "not reported" : protocolErrors));
        lines.add("RESULT " + (pass ? "PASS" : "FAIL"));
        lines.add("");
        lines.addAll(details);
        lines.forEach(System.out::println);
        RandomGames.save(Path.of(deckDir), "AllPairings", matches, lines);
        System.exit(pass ? 0 : 1);
    }
}
