package magicdosa.agent.shadow;

import com.google.protobuf.TextFormat;
import magicdosa.protocol.v1.LogRecord;

import java.nio.file.Path;
import java.util.List;

/**
 * Prints the turn-start snapshots of one seat of a game log (effects, stack, command zone, and the objects of a
 * zone if asked): {@code SnapshotDump <log> <seat> <turn> [zone]}.
 */
public final class SnapshotDump {
    public static void main(String[] args) throws Exception {
        List<LogRecord> records = GameRecord.read(Path.of(args[0]));
        GameRecord g = GameRecord.of(Path.of(args[0]), records, Integer.parseInt(args[1]));
        int turn = Integer.parseInt(args[2]);
        String zone = args.length > 3 ? args[3] : "ZONE_COMMAND";
        for (GameRecord.Snapshot s : g.snapshots) {
            var o = s.snapshot().getObservation();
            if (o.getGame().getTurn() != turn) {
                continue;
            }
            System.out.println("snapshot seq " + s.snapshot().getSeq() + " at " + s.at());
            o.getEffectsList().forEach(e -> System.out.println("  effect " + TextFormat.shortDebugString(e)));
            o.getStackList().forEach(e -> System.out.println("  stack " + TextFormat.shortDebugString(e)));
            o.getObjectsList().stream().filter(x -> x.getZone().name().equals(zone))
                .forEach(x -> System.out.println("  " + TextFormat.shortDebugString(x)));
        }
    }
}
