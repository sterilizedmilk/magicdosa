package magicdosa.agent.shadow;

import forge.GuiDesktop;
import forge.game.card.Card;
import forge.gui.GuiBase;
import forge.model.FModel;
import magicdosa.adapter.forge.CardKeys;
import magicdosa.adapter.forge.SnapshotBuilder;
import magicdosa.protocol.v1.PrivilegedRecord;

import java.nio.file.Path;

/**
 * Debugging: rebuilds the seat's turn-start snapshot of one turn from a log and prints the tokens the snapshot has and
 * the tokens the rebuilt game has, with the builder's gaps.
 *
 * <pre>TokenProbe &lt;log.pb.gz&gt; &lt;seat&gt; &lt;turn&gt;</pre>
 */
public final class TokenProbe {
    private TokenProbe() {
    }

    public static void main(String[] args) throws Exception {
        System.setProperty("java.awt.headless", "true");
        System.setProperty("java.util.Arrays.useLegacyMergeSort", "true");
        GuiBase.setInterface(new GuiDesktop());
        FModel.initialize(null, null);
        CardKeys keys = CardKeys.load();
        Path log = Path.of(args[0]);
        GameRecord rec = GameRecord.of(log, GameRecord.read(log), Integer.parseInt(args[1]));
        int turn = Integer.parseInt(args[2]);
        for (GameRecord.Snapshot snap : rec.snapshots) {
            var s = snap.snapshot();
            if (s.getObservation().getGame().getTurn() != turn) {
                continue;
            }
            PrivilegedRecord hidden = rec.privilegedAt(s.getSeq());
            for (var g : s.getObservation().getObjectsList()) {
                if (g.getToken()) {
                    System.out.println("snapshot token " + g.getId() + " " + g.getZone() + " " + g.getOracleId()
                        + " script=" + keys.tokenScriptFor(g.getOracleId()));
                }
            }
            Thread t = new Thread(() -> {
                SnapshotBuilder.Result r = new SnapshotBuilder(keys).build(s, hidden.getPrivileged(), 100000, id -> id);
                r.ids().forEach((c, id) -> {
                    if (c.isToken()) {
                        System.out.println("built token " + id + " " + c + " in " + c.getZone());
                    }
                });
                for (Card c : r.game().getCardsIn(forge.game.zone.ZoneType.Battlefield)) {
                    if (c.isToken() && !r.ids().containsKey(c)) {
                        System.out.println("built token without ID " + c);
                    }
                }
                r.gaps().forEach(g -> System.out.println("gap " + g));
            }, "Game-probe");
            t.start();
            t.join();
            return;
        }
        System.out.println("no snapshot for turn " + turn);
    }
}
