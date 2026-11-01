package magicdosa.adapter.forge.harness;

import forge.deck.Deck;
import forge.gui.GuiBase;
import forge.model.FModel;
import forge.GuiDesktop;
import magicdosa.adapter.forge.CardKeys;
import magicdosa.adapter.forge.DecisionStats;
import magicdosa.adapter.forge.ForgeMatchRunner;
import magicdosa.adapter.forge.ObservedAiController;
import magicdosa.adapter.forge.RandomDecisionClient;
import magicdosa.agent.DeckFiles;
import magicdosa.protocol.v1.Mode;

import java.io.File;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Phase-1 gate (CLAUDE.md "지금 단계"): a uniform random policy in seat 0 against Forge AI plays N Bo3 matches through
 * the forge-adapter; there must be no unintended fallback, no protocol error and no unmapped card or ability.
 * Sequential, one JVM. Main program in the test sources, so mvn install does not run it:
 *
 * <pre>mvn -pl adapter/forge-adapter exec:exec "-Dexec.args=&lt;abs decks dir&gt; IzzetProwess.dck EsperBlink.dck 20 &lt;abs log dir or -&gt;"</pre>
 */
public final class RandomGames {
    private RandomGames() {}

    public static void main(String[] args) throws Exception {
        String deckDir = args[0];
        int matches = Integer.parseInt(args[3]);
        Path logDir = args.length > 4 && !args[4].equals("-") ? Path.of(args[4]) : null;

        System.setProperty("java.awt.headless", "true");
        System.setProperty("java.util.Arrays.useLegacyMergeSort", "true");
        GuiBase.setInterface(new GuiDesktop());
        FModel.initialize(null, null);
        Deck deck0 = DeckFiles.load(new File(deckDir, args[1]));
        Deck deck1 = DeckFiles.load(new File(deckDir, args[2]));

        CardKeys keys = CardKeys.load();
        RandomDecisionClient client = new RandomDecisionClient();
        ForgeMatchRunner runner = new ForgeMatchRunner(client, keys, Mode.MODE_TRAINING, "forge@87cb532", false);
        DecisionStats total = new DecisionStats();
        int[] won = new int[2];
        int games = 0;
        int errors = 0;
        long start = System.currentTimeMillis();
        for (int m = 0; m < matches; m++) {
            try {
                ForgeMatchRunner.Result r = runner.playMatch("m" + m, deck0, deck1, Set.of(0), 1000 + m, logDir);
                total.add(r.stats());
                won[0] += r.gamesWon()[0];
                won[1] += r.gamesWon()[1];
                games += r.games();
            } catch (Exception | Error e) {
                errors++;
                System.out.println("MATCH " + m + " FAILED: " + e);
                e.printStackTrace(System.out);
            }
        }
        long ms = System.currentTimeMillis() - start;
        System.out.printf("matches=%d games=%d agentWins=%d forgeAiWins=%d seconds=%.1f%n", matches, games, won[0], won[1], ms / 1000.0);
        System.out.println("decided by agent: " + total.decided() + " (auto passes " + total.autoPasses() + ")");
        System.out.println("intended delegations: " + total.delegated());
        System.out.println("wrapped Forge methods: " + ObservedAiController.wrapped().size());
        Map<String, Long> fallbacks = new java.util.TreeMap<>();
        total.fallbacks().forEach(f -> fallbacks.merge(f, 1L, Long::sum));
        System.out.println("unintended fallbacks: " + total.fallbacks().size() + " " + fallbacks);
        System.out.println("engine refusals asked again (not fallbacks): " + total.rejected());
        total.notes().forEach((k, v) -> System.out.println("  " + v + "x " + k));
        System.out.println("protocol errors: " + client.protocolErrors().size() + " "
            + client.protocolErrors().subList(0, Math.min(10, client.protocolErrors().size())));
        System.out.println("unmapped: " + keys.unmapped().size());
        keys.unmapped().forEach(u -> System.out.println("  " + u));
        System.out.println("events=" + client.events() + " decisions=" + client.decisions());
        List<String> logProblems = logDir == null ? List.of() : LogCheck.check(logDir);
        if (logDir != null) {
            System.out.println("log problems: " + logProblems.size() + " " + logProblems.subList(0, Math.min(10, logProblems.size())));
        }
        boolean pass = logProblems.isEmpty() && errors == 0 && total.fallbacks().isEmpty() && client.protocolErrors().isEmpty()
            && keys.unmapped().isEmpty() && total.errors() == 0;
        System.out.println("RESULT " + (pass ? "PASS" : "FAIL"));
        System.exit(pass ? 0 : 1);
    }
}
