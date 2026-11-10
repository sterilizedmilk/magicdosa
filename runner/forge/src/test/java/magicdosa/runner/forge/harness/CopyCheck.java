package magicdosa.runner.forge.harness;

import magicdosa.adapter.forge.CardKeys;
import magicdosa.adapter.forge.DecisionStats;

import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * Regression check for agent-core's {@code GameCopier} (2026-11-10: devoid cards in the sideboard made every copy fail
 * with "Couldn't map"): every pairing of the decks directory, N matches each, copies the whole game at every priority
 * decision of the agent seat and maps every card of every zone back (system property {@code magicdosa.copyCheck=1}).
 * Both decks of a pairing are in the game, so every card of every deck is copied in every zone it reaches. PASS needs
 * zero copy failures and RandomGames' usual conditions; failures are listed with their cause.
 *
 * <pre>mvn -pl runner/forge exec:exec "-Dexec.mainClass=magicdosa.runner.forge.harness.CopyCheck"
 *     "-Dexec.args=&lt;abs decks dir&gt; 2 [spawn|local|host:port]"</pre>
 */
public final class CopyCheck {
    private CopyCheck() {}

    public static void main(String[] args) throws Exception {
        System.setProperty("magicdosa.copyCheck", "1"); // before any controller class reads it
        String deckDir = args[0];
        int matches = Integer.parseInt(args[1]);
        String agentSpec = args.length > 2 ? args[2] : "spawn";
        RandomGames.initForge();
        CardKeys keys = CardKeys.load();
        String[] decks = new File(deckDir).list((d, n) -> n.endsWith(".dck"));
        Arrays.sort(decks);
        DecisionStats total = new DecisionStats();
        List<String> lines = new ArrayList<>();
        boolean pass = true;
        try (AgentConnection agent = AgentConnection.open(agentSpec)) {
            for (String a : decks) {
                for (String b : decks) {
                    String name = a.replace(".dck", "") + "-vs-" + b.replace(".dck", "");
                    RandomGames.Summary s = RandomGames.run(deckDir, a, b, matches, null, agent, keys, name + "-m");
                    pass &= s.pass();
                    total.add(s.stats());
                    String line = String.format("%-40s %s  games=%d copies=%d failed=%d", name, s.pass() ? "PASS" : "FAIL",
                        s.games(), s.stats().copies(), s.stats().copyFailures());
                    lines.add(line);
                    System.out.println(line);
                }
            }
        }
        lines.add("game copies: " + total.copies() + ", failed: " + total.copyFailures());
        for (Map.Entry<String, Integer> n : total.notes().entrySet()) {
            if (n.getKey().contains("copy")) {
                lines.add("  " + n.getValue() + "x " + n.getKey());
            }
        }
        lines.add("candidate omissions: " + total.candidateOmissions() + " in " + total.candidateChecks() + " checks");
        pass &= total.copyFailures() == 0 && total.copies() > 0;
        lines.add("RESULT " + (pass ? "PASS" : "FAIL"));
        lines.forEach(System.out::println);
        RandomGames.save(Path.of(deckDir), "CopyCheck", matches, lines);
        System.exit(pass ? 0 : 1);
    }
}
