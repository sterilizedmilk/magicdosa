package magicdosa.runner.forge.harness;

import com.google.common.eventbus.Subscribe;
import forge.deck.Deck;
import forge.game.Game;
import forge.game.card.Card;
import forge.game.event.GameEvent;
import forge.game.event.GameEventTurnBegan;
import forge.game.player.Player;
import forge.game.zone.ZoneType;
import magicdosa.adapter.forge.CardKeys;
import magicdosa.adapter.forge.DecisionStats;
import magicdosa.runner.forge.ForgeMatchRunner;
import magicdosa.agent.DeckFiles;
import magicdosa.protocol.v1.DecisionType;
import magicdosa.protocol.v1.Mode;

import java.io.File;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

/**
 * Deck item 3 (2026-11-06): combat damage an agent seat divides among several recipients goes to agent-core as
 * DAMAGE_ASSIGNMENT. The random policy seldom gets there (no request in the 16 x 60 all-pairings run of 2026-11-07), so
 * at the start of the agent seat's third turn this harness puts the agent deck's tramplers (Emrakul, the Promised End;
 * Sire of Seven Deaths) from its library, hand or graveyard onto the battlefield, ready to attack. There must be
 * DAMAGE_ASSIGNMENT requests, and no unintended fallback, protocol error or log problem.
 *
 * <pre>mvn -pl runner/forge exec:exec "-Dexec.mainClass=magicdosa.runner.forge.harness.DamageAssignmentCheck"
 *     "-Dexec.args=&lt;abs decks dir&gt; Broodscale.dck EsperBlink.dck 20 &lt;abs empty log dir&gt; [spawn|local|host:port]"</pre>
 */
public final class DamageAssignmentCheck {
    private DamageAssignmentCheck() {}

    private static final List<String> TRAMPLERS = List.of("Emrakul, the Promised End", "Sire of Seven Deaths");

    /** Puts the tramplers onto the agent seat's battlefield at the start of its third turn. */
    public static final class Tramplers {
        private final Game game;
        private final Player agent;
        private int agentTurns;

        Tramplers(Game game) {
            this.game = game;
            this.agent = game.getRegisteredPlayers().get(0);
        }

        @Subscribe
        public void onEvent(GameEvent event) {
            if (!(event instanceof GameEventTurnBegan e) || game.getPlayer(e.turnOwner()) != agent || ++agentTurns != 3
                    || agent.hasLost()) {
                return;
            }
            for (String name : TRAMPLERS) {
                for (Card c : agent.getCardsIn(List.of(ZoneType.Library, ZoneType.Hand, ZoneType.Graveyard))) {
                    if (c.getName().equals(name)) {
                        Card moved = game.getAction().moveToPlay(c, null, null);
                        if (moved != null) {
                            moved.setSickness(false);
                        }
                        break;
                    }
                }
            }
        }
    }

    public static void main(String[] args) throws Exception {
        String deckDir = args[0];
        int matches = Integer.parseInt(args[3]);
        Path logDir = Path.of(args[4]);
        String agentSpec = args.length > 5 ? args[5] : "spawn";
        RandomGames.initForge();
        Deck deck0 = DeckFiles.load(new File(deckDir, args[1]));
        Deck deck1 = DeckFiles.load(new File(deckDir, args[2]));
        CardKeys keys = CardKeys.load();
        DecisionStats total = new DecisionStats();
        int errors = 0;
        int protocolErrors;
        try (AgentConnection agent = AgentConnection.open(agentSpec)) {
            ForgeMatchRunner runner = new ForgeMatchRunner(agent.client(), keys, Mode.MODE_TRAINING, "forge@87cb532", false)
                .onGame((game, session) -> game.subscribeToEvents(new Tramplers(game)));
            for (int m = 0; m < matches; m++) {
                try {
                    total.add(runner.playMatch("d" + m, deck0, deck1, Set.of(0), 7000 + m, logDir).stats());
                } catch (Exception | Error e) {
                    errors++;
                    System.out.println("MATCH " + m + " FAILED: " + e);
                    e.printStackTrace(System.out);
                }
            }
            agent.close();
            protocolErrors = agent.protocolErrorCount();
        }
        int assignments = total.decided().getOrDefault(DecisionType.DECISION_TYPE_DAMAGE_ASSIGNMENT, 0);
        List<String> logProblems = LogCheck.check(logDir);
        System.out.println("DAMAGE_ASSIGNMENT decisions: " + assignments);
        System.out.println("decided by agent: " + total.decided());
        System.out.println("unintended fallbacks: " + total.fallbacks().size() + " " + total.fallbacks());
        System.out.println("unclassified: " + total.unclassified() + "; unmapped: " + keys.unmapped());
        System.out.println("protocol errors seen by agent-core: " + protocolErrors);
        System.out.println("log problems: " + logProblems.size() + " " + logProblems.subList(0, Math.min(10, logProblems.size())));
        boolean pass = errors == 0 && assignments > 0 && total.fallbacks().isEmpty() && logProblems.isEmpty()
            && total.errors() == 0 && protocolErrors == 0 && total.unclassified().isEmpty();
        System.out.println("RESULT " + (pass ? "PASS" : "FAIL"));
        System.exit(pass ? 0 : 1);
    }
}
