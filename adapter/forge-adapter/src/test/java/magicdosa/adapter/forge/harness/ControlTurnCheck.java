package magicdosa.adapter.forge.harness;

import com.google.common.eventbus.Subscribe;
import forge.GuiDesktop;
import forge.deck.Deck;
import forge.game.Game;
import forge.game.event.GameEvent;
import forge.game.event.GameEventTurnBegan;
import forge.game.player.Player;
import forge.gui.GuiBase;
import forge.model.FModel;
import magicdosa.adapter.forge.CardKeys;
import magicdosa.adapter.forge.DecisionStats;
import magicdosa.adapter.forge.ForgeMatchRunner;
import magicdosa.agent.DeckFiles;
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
 * Deck item 4 (2026-11-06): an agent seat that controls its opponent's turn (Emrakul, the Promised End) decides the
 * opponent's choices through agent-core. The opponent's third turn of every game is handed to the agent seat (as
 * Emrakul's effect does: {@code Player.addController}); the requests made for it must carry {@code acting_seat}, every
 * game must finish, and there must be no unintended fallback, protocol error or log problem.
 *
 * <pre>mvn -pl adapter/forge-adapter exec:exec "-Dexec.mainClass=magicdosa.adapter.forge.harness.ControlTurnCheck"
 *     "-Dexec.args=&lt;abs decks dir&gt; Broodscale.dck EsperBlink.dck 10 &lt;abs empty log dir&gt; [spawn|local|host:port]"</pre>
 */
public final class ControlTurnCheck {
    private ControlTurnCheck() {}

    /** Gives the opponent's third turn to the agent seat. */
    public static final class Handover {
        private final Game game;
        private final Player agent;
        private final Player opponent;
        private Long timestamp;
        private int opponentTurns;

        Handover(Game game) {
            this.game = game;
            this.agent = game.getRegisteredPlayers().get(0);
            this.opponent = game.getRegisteredPlayers().get(1);
        }

        @Subscribe
        public void onEvent(GameEvent event) {
            if (!(event instanceof GameEventTurnBegan e)) {
                return;
            }
            if (timestamp != null) {
                opponent.removeController(timestamp);
                timestamp = null;
            }
            if (game.getPlayer(e.turnOwner()) == opponent && ++opponentTurns == 3 && !opponent.hasLost()) {
                timestamp = game.getNextTimestamp();
                opponent.addController(timestamp, agent);
            }
        }
    }

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
            ForgeMatchRunner runner = new ForgeMatchRunner(agent.client(), keys, Mode.MODE_TRAINING, "forge@87cb532", false)
                .onGame((game, session) -> game.subscribeToEvents(new Handover(game)));
            for (int m = 0; m < matches; m++) {
                try {
                    total.add(runner.playMatch("c" + m, deck0, deck1, Set.of(0), 5000 + m, logDir).stats());
                } catch (Exception | Error e) {
                    errors++;
                    System.out.println("MATCH " + m + " FAILED: " + e);
                    e.printStackTrace(System.out);
                }
            }
        }
        long acting = 0;
        try (Stream<Path> files = Files.list(logDir)) {
            for (Path f : files.filter(p -> p.toString().endsWith(".pb.gz")).toList()) {
                try (InputStream in = new GZIPInputStream(new BufferedInputStream(Files.newInputStream(f)))) {
                    LogRecord r;
                    while ((r = LogRecord.parseDelimitedFrom(in)) != null) {
                        if (r.hasDecision() && r.getDecision().getRequest().hasActingSeat()) {
                            acting++;
                        }
                    }
                }
            }
        }
        List<String> logProblems = LogCheck.check(logDir);
        System.out.println("decisions made for the controlled opponent (acting_seat set): " + acting);
        System.out.println("decided by agent: " + total.decided());
        System.out.println("unintended fallbacks: " + total.fallbacks().size() + " " + total.fallbacks());
        System.out.println("log problems: " + logProblems.size() + " " + logProblems.subList(0, Math.min(10, logProblems.size())));
        boolean pass = errors == 0 && acting > 0 && total.fallbacks().isEmpty() && logProblems.isEmpty() && total.errors() == 0;
        System.out.println("RESULT " + (pass ? "PASS" : "FAIL"));
        System.exit(pass ? 0 : 1);
    }
}
