package magicdosa.agent.shadow;

import forge.ai.AiAttackController;
import forge.game.Game;
import forge.game.card.Card;
import forge.game.combat.Combat;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.zone.ZoneType;
import magicdosa.adapter.forge.CardKeys;
import magicdosa.adapter.forge.SnapshotBuilder;
import magicdosa.protocol.v1.LogRecord;
import magicdosa.protocol.v1.PrivilegedRecord;

import java.nio.file.Path;
import java.util.List;

/**
 * Diagnosis tool (phase-2 open risk, the big-board shadow hang): rebuilds a game log's turn-start state and times Forge
 * AI's attack declaration there (AiAttackController), printing the active seat's creatures and, every 60 s while it
 * runs, the thinking thread's stack. {@code AttackTiming <log> <seat whose stream> <turn> [more turns...]}; working
 * directory forge-gui.
 */
public final class AttackTiming {
    public static void main(String[] args) throws Exception {
        CardKeys keys = Scenario.keys();
        List<LogRecord> records = GameRecord.read(Path.of(args[0]));
        GameRecord rec = GameRecord.of(Path.of(args[0]), records, Integer.parseInt(args[1]));
        for (int k = 2; k < args.length; k++) {
            int turn = Integer.parseInt(args[k]);
            for (GameRecord.Snapshot s : rec.snapshots) {
                if (s.snapshot().getObservation().getGame().getTurn() != turn) {
                    continue;
                }
                PrivilegedRecord hidden = rec.privilegedAt(s.snapshot().getSeq());
                Thread t = new Thread(() -> time(keys, s, hidden), "Game-attack-timing-" + turn);
                Thread watch = new Thread(() -> {
                    try {
                        while (t.isAlive()) {
                            t.join(60_000);
                            if (t.isAlive()) {
                                System.out.println("ATTACK still thinking after another 60 s:");
                                for (StackTraceElement e : t.getStackTrace()) {
                                    System.out.println("    at " + e);
                                }
                            }
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                });
                t.start();
                watch.start();
                t.join();
                watch.join();
                break;
            }
        }
        System.exit(0);
    }

    private static void time(CardKeys keys, GameRecord.Snapshot s, PrivilegedRecord hidden) {
        var o = s.snapshot().getObservation();
        SnapshotBuilder.Result built = new SnapshotBuilder(keys).build(s.snapshot(), hidden.getPrivileged(), 1_000_000L, id -> id);
        Game game = built.game();
        Player active = game.getPlayers().get(o.getGame().getActiveSeat());
        game.getPhaseHandler().devModeSet(PhaseType.COMBAT_DECLARE_ATTACKERS, active, o.getGame().getTurn());
        long creatures = active.getCardsIn(ZoneType.Battlefield).stream().filter(Card::isCreature).count();
        long blockers = active.getWeakestOpponent().getCardsIn(ZoneType.Battlefield).stream().filter(Card::isCreature).count();
        Combat combat = new Combat(active);
        game.getPhaseHandler().setCombat(combat);
        long t0 = System.nanoTime(); // measurement only
        new AiAttackController(active).declareAttackers(combat);
        long ms = (System.nanoTime() - t0) / 1_000_000;
        System.out.printf("ATTACK turn %d seat %d: %d creatures vs %d possible blockers, Forge AI declared %d attackers in %d ms (gaps %d)%n",
            o.getGame().getTurn(), o.getGame().getActiveSeat(), creatures, blockers, combat.getAttackers().size(), ms,
            built.gaps().size());
    }
}
