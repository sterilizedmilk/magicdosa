package magicdosa.runner.forge.harness;

import forge.GuiDesktop;
import forge.deck.Deck;
import forge.gui.GuiBase;
import forge.model.FModel;
import magicdosa.adapter.forge.CardKeys;
import magicdosa.agent.DeckFiles;
import magicdosa.protocol.v1.Mode;
import magicdosa.runner.forge.ForgeMatchRunner;
import magicdosa.runner.forge.ReplayDecisionClient;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Replays logged matches of a gate run (RandomGames seeds 1000 + m) one after another on this thread and reports,
 * per match, whether the sequential replay asks every request the log has. A match whose replay differs was not
 * reproducible as it ran (a parallel run's games interfered). Main program in the test sources:
 *
 * <pre>ReplayCheck &lt;abs decks dir&gt; &lt;abs gate log root&gt; &lt;pairing dir&gt;... </pre>
 *
 * Writes {@code replay-check-<first pairing>.txt} in the log root.
 */
public final class ReplayCheck {
    private ReplayCheck() {}

    public static void main(String[] args) throws Exception {
        System.setProperty("java.awt.headless", "true");
        System.setProperty("java.util.Arrays.useLegacyMergeSort", "true");
        GuiBase.setInterface(new GuiDesktop());
        FModel.initialize(null, null);
        CardKeys keys = CardKeys.load();
        Path root = Path.of(args[1]);
        List<String> out = new ArrayList<>();
        for (int a = 2; a < args.length; a++) {
            String pairing = args[a];
            String[] decks = pairing.split("-vs-");
            Deck deck0 = DeckFiles.load(new File(args[0], decks[0] + ".dck"));
            Deck deck1 = DeckFiles.load(new File(args[0], decks[1] + ".dck"));
            Path dir = root.resolve(pairing);
            for (int m = 0; ; m++) {
                String id = pairing + "-m" + m;
                List<Path> logs;
                try (Stream<Path> files = Files.list(dir)) {
                    logs = files.filter(p -> p.getFileName().toString().startsWith(id + "-g")).sorted().toList();
                }
                if (logs.isEmpty()) {
                    break;
                }
                ReplayDecisionClient replay = new ReplayDecisionClient(logs);
                String line;
                try {
                    new ForgeMatchRunner(replay, keys, Mode.MODE_TRAINING, "forge@87cb532", true)
                        .playMatch(id, deck0, deck1, Set.of(0), 1000 + m, null);
                    boolean same = replay.mismatches().isEmpty() && replay.replayed() == replay.recorded();
                    line = id + " " + (same ? "SAME" : "DIFF") + " " + replay.replayed() + "/" + replay.recorded()
                        + (same ? "" : " " + replay.mismatches().subList(0, Math.min(1, replay.mismatches().size())));
                } catch (Exception e) {
                    line = id + " ERROR " + e;
                }
                System.out.println("REPLAY " + line);
                out.add(line);
            }
        }
        Files.write(root.resolve("replay-check-" + args[2] + ".txt"), out);
        System.exit(0);
    }
}
