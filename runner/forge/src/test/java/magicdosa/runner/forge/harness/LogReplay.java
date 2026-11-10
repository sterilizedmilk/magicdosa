package magicdosa.runner.forge.harness;

import forge.GuiDesktop;
import forge.deck.Deck;
import forge.gui.GuiBase;
import forge.model.FModel;
import magicdosa.adapter.forge.CardKeys;
import magicdosa.runner.forge.ForgeMatchRunner;
import magicdosa.runner.forge.ReplayDecisionClient;
import magicdosa.agent.DeckFiles;
import magicdosa.protocol.v1.Mode;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Phase-1 check "로그로 한 판을 재연해 같은 결과가 나오는지": plays N matches with the random policy and writes their logs,
 * then replays each match from its logs alone (agent-core's logged responses, same seed) and requires every decision
 * request and every game log to come out identical. Main program in the test sources:
 *
 * <pre>mvn -pl runner/forge exec:exec "-Dexec.mainClass=magicdosa.runner.forge.harness.LogReplay"
 *     "-Dexec.args=&lt;abs decks dir&gt; IzzetProwess.dck EsperBlink.dck 10 &lt;abs empty log dir&gt; [spawn|local|host:port]"</pre>
 *
 * The original matches go to agent-core (by default a separate JVM); the replays need only the logs.
 */
public final class LogReplay {
    private LogReplay() {}

    public static void main(String[] args) throws Exception {
        String deckDir = args[0];
        int matches = Integer.parseInt(args[3]);
        Path logDir = Path.of(args[4]);
        String agentSpec = args.length > 5 ? args[5] : "spawn";
        System.setProperty("java.awt.headless", "true");
        System.setProperty("java.util.Arrays.useLegacyMergeSort", "true");
        GuiBase.setInterface(new GuiDesktop());
        FModel.initialize(null, null);
        Deck deck0 = DeckFiles.load(new File(deckDir, args[1]));
        Deck deck1 = DeckFiles.load(new File(deckDir, args[2]));
        CardKeys keys = CardKeys.load();

        AgentConnection agent = AgentConnection.open(agentSpec);
        int identical = 0;
        int different = 0;
        long decisions = 0;
        for (int m = 0; m < matches; m++) {
            String id = "r" + m;
            long seed = 9000 + m;
            ForgeMatchRunner original = new ForgeMatchRunner(agent.client(), keys, Mode.MODE_TRAINING,
                "forge@87cb532", true);
            List<String> before = original.playMatch(id, deck0, deck1, Set.of(0), seed, logDir).logDigests();

            List<Path> logs;
            try (Stream<Path> files = Files.list(logDir)) {
                logs = files.filter(p -> p.getFileName().toString().startsWith(id + "-g")).sorted().toList();
            }
            ReplayDecisionClient replay = new ReplayDecisionClient(logs);
            ForgeMatchRunner again = new ForgeMatchRunner(replay, keys, Mode.MODE_TRAINING, "forge@87cb532", true);
            List<String> after = again.playMatch(id, deck0, deck1, Set.of(0), seed, null).logDigests();
            decisions += replay.replayed();
            boolean same = before.equals(after) && replay.mismatches().isEmpty() && replay.replayed() == replay.recorded();
            if (same) {
                identical++;
            } else {
                different++;
                System.out.println("match " + id + ": games " + before.size() + " vs " + after.size() + ", replayed "
                    + replay.replayed() + "/" + replay.recorded() + ", " + replay.mismatches().subList(0, Math.min(5, replay.mismatches().size())));
            }
        }
        agent.close();
        System.out.printf("RESULT matches=%d identical=%d different=%d replayedDecisions=%d%n", matches, identical, different, decisions);
        System.exit(different == 0 ? 0 : 1);
    }
}
