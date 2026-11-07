package magicdosa.adapter.forge.harness;

import forge.GuiDesktop;
import forge.deck.Deck;
import forge.gui.GuiBase;
import forge.model.FModel;
import magicdosa.adapter.forge.CardKeys;
import magicdosa.adapter.forge.DecisionStats;
import magicdosa.adapter.forge.ForgeMatchRunner;
import magicdosa.agent.DeckFiles;
import magicdosa.protocol.v1.CardRevealed;
import magicdosa.protocol.v1.LogRecord;
import magicdosa.protocol.v1.Mode;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import java.util.zip.GZIPInputStream;

/**
 * Q10 reveal targets: in self-play (both seats agent seats), a card one seat shows only to the other (Thoughtseize,
 * Thought-Knot Seer looking at a hand) must reach the owner's stream as the fact that the opponent saw it, and a
 * stream must never name a card its seat did not see ({@link LogCheck}). PASS needs at least one such fact.
 *
 * <pre>mvn -pl adapter/forge-adapter exec:exec "-Dexec.mainClass=magicdosa.adapter.forge.harness.RevealCheck"
 *     "-Dexec.args=&lt;abs decks dir&gt; EsperBlink.dck EsperBlink.dck 20 &lt;abs empty log dir&gt; [spawn|local|host:port]"</pre>
 */
public final class RevealCheck {
    private RevealCheck() {}

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
        DecisionStats total = new DecisionStats();
        int errors = 0;
        try (AgentConnection agent = AgentConnection.open(agentSpec)) {
            ForgeMatchRunner runner = new ForgeMatchRunner(agent.client(), keys, Mode.MODE_TRAINING, "forge@87cb532", false);
            for (int m = 0; m < matches; m++) {
                try {
                    total.add(runner.playMatch("v" + m, deck0, deck1, Set.of(0, 1), 8000 + m, logDir).stats());
                } catch (Exception | Error e) {
                    errors++;
                    System.out.println("MATCH " + m + " FAILED: " + e);
                    e.printStackTrace(System.out);
                }
            }
        }
        long seenByOpponent = 0;
        try (Stream<Path> files = Files.list(logDir)) {
            for (Path f : files.filter(p -> p.toString().endsWith(".pb.gz")).toList()) {
                try (InputStream in = new GZIPInputStream(new BufferedInputStream(Files.newInputStream(f)))) {
                    LogRecord r;
                    while ((r = LogRecord.parseDelimitedFrom(in)) != null) {
                        if (r.hasEvent() && r.getEvent().hasCardRevealed()) {
                            CardRevealed c = r.getEvent().getCardRevealed();
                            int viewer = r.getEvent().getViewerSeat();
                            if (c.getRevealedToSeatsList().contains(1 - viewer) && c.getZone().name().endsWith("HAND")
                                    && !c.getOracleId().isEmpty()) {
                                seenByOpponent++; // the owner's stream: "the opponent saw this card"
                            }
                        }
                    }
                }
            }
        }
        List<String> logProblems = LogCheck.check(logDir);
        System.out.println("own hand cards reported as seen by the opponent: " + seenByOpponent);
        System.out.println("unintended fallbacks: " + total.fallbacks().size() + " " + total.fallbacks());
        System.out.println("log problems: " + logProblems.size() + " " + logProblems.subList(0, Math.min(10, logProblems.size())));
        boolean pass = errors == 0 && seenByOpponent > 0 && total.fallbacks().isEmpty() && logProblems.isEmpty();
        System.out.println("RESULT " + (pass ? "PASS" : "FAIL"));
        System.exit(pass ? 0 : 1);
    }
}
