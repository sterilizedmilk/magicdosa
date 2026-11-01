package magicdosa.adapter.forge.harness;

import forge.GuiDesktop;
import forge.deck.Deck;
import forge.gui.GuiBase;
import forge.model.FModel;
import magicdosa.adapter.forge.CardKeys;
import magicdosa.adapter.forge.ForgeMatchRunner;
import magicdosa.adapter.forge.RandomDecisionClient;
import magicdosa.agent.DeckFiles;
import magicdosa.protocol.v1.Mode;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Regression check for the adapter (CLAUDE.md 재현: "같은 JVM에서 같은 시드를 두 번 돌려 different=0"): N random-policy
 * matches, single thread, run twice with the same seeds; each game's protocol log (events, snapshots, decisions,
 * privileged records) must be byte-identical. Main program in the test sources:
 *
 * <pre>mvn -pl adapter/forge-adapter exec:exec "-Dexec.mainClass=magicdosa.adapter.forge.harness.AdapterDeterminism"
 *     "-Dexec.args=&lt;abs decks dir&gt; IzzetProwess.dck EsperBlink.dck 20"</pre>
 */
public final class AdapterDeterminism {
    private AdapterDeterminism() {}

    public static void main(String[] args) throws Exception {
        String deckDir = args[0];
        int matches = Integer.parseInt(args[3]);
        System.setProperty("java.awt.headless", "true");
        System.setProperty("java.util.Arrays.useLegacyMergeSort", "true");
        GuiBase.setInterface(new GuiDesktop());
        FModel.initialize(null, null);
        Deck deck0 = DeckFiles.load(new File(deckDir, args[1]));
        Deck deck1 = DeckFiles.load(new File(deckDir, args[2]));
        CardKeys keys = CardKeys.load();

        List<List<String>> first = run(keys, deck0, deck1, matches);
        List<List<String>> second = run(keys, deck0, deck1, matches);
        int identical = 0;
        int different = 0;
        for (int m = 0; m < matches; m++) {
            if (first.get(m).equals(second.get(m))) {
                identical++;
            } else {
                different++;
                System.out.println("match " + m + " differs: " + first.get(m).size() + " vs " + second.get(m).size() + " games");
            }
        }
        System.out.printf("RESULT matches=%d identical=%d different=%d%n", matches, identical, different);
        System.exit(different == 0 ? 0 : 1);
    }

    private static List<List<String>> run(CardKeys keys, Deck deck0, Deck deck1, int matches) throws Exception {
        RandomDecisionClient client = new RandomDecisionClient();
        ForgeMatchRunner runner = new ForgeMatchRunner(client, keys, Mode.MODE_TRAINING, "forge@87cb532", true);
        List<List<String>> out = new ArrayList<>();
        for (int m = 0; m < matches; m++) {
            out.add(runner.playMatch("m" + m, deck0, deck1, Set.of(0), 7000 + m, null).logDigests());
        }
        return out;
    }
}
