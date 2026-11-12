package magicdosa.agent.shadow;

import magicdosa.protocol.v1.PrivilegedRecord;

import java.nio.file.Path;
import java.util.Map;
import java.util.TreeMap;

/**
 * Debugging: the objects a seat owns in a turn-start snapshot, by zone (tokens and command zone apart), and the real
 * library size from the hidden-state record.
 *
 * <pre>CountProbe &lt;log.pb.gz&gt; &lt;viewer seat&gt; &lt;turn&gt; &lt;owner seat&gt;</pre>
 */
public final class CountProbe {
    private CountProbe() {
    }

    public static void main(String[] args) throws Exception {
        Path log = Path.of(args[0]);
        GameRecord rec = GameRecord.of(log, GameRecord.read(log), Integer.parseInt(args[1]));
        int turn = Integer.parseInt(args[2]);
        int owner = Integer.parseInt(args[3]);
        for (GameRecord.Snapshot snap : rec.snapshots) {
            var s = snap.snapshot();
            if (s.getObservation().getGame().getTurn() != turn) {
                continue;
            }
            Map<String, Integer> byZone = new TreeMap<>();
            for (var g : s.getObservation().getObjectsList()) {
                if (g.getOwnerSeat() == owner) {
                    byZone.merge(g.getZone() + (g.getToken() ? "(token)" : "") + (g.getCopy() ? "(copy)" : ""), 1, Integer::sum);
                    if (g.getZone() != magicdosa.protocol.v1.Zone.ZONE_LIBRARY) {
                        System.out.println("  " + g.getId() + " " + g.getZone() + " " + g.getOracleId() + (g.getToken() ? " token" : ""));
                    }
                }
            }
            System.out.println("turn " + turn + " seq " + s.getSeq() + " owner " + owner + ": " + byZone);
            PrivilegedRecord hidden = rec.privilegedAt(s.getSeq());
            if (hidden != null) {
                for (var lib : hidden.getPrivileged().getLibrariesList()) {
                    System.out.println("  real library of seat " + lib.getSeat() + ": " + lib.getIdsCount());
                }
                for (var d : hidden.getPrivileged().getCurrentDecksList()) {
                    System.out.println("  current deck of seat " + d.getSeat() + ": "
                        + d.getCardsList().stream().mapToInt(c -> c.getCount()).sum());
                }
            }
            System.out.println("  match player " + owner + " current deck listed: "
                + s.getObservation().getMatch().getPlayers(owner).getCurrentDeckList().stream().mapToInt(c -> c.getCount()).sum()
                + ", main " + s.getObservation().getMatch().getPlayers(owner).getMainList().stream().mapToInt(c -> c.getCount()).sum());
            return;
        }
    }
}
