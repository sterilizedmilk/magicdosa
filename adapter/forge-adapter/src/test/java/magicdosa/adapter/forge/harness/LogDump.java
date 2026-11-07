package magicdosa.adapter.forge.harness;

import com.google.protobuf.TextFormat;
import magicdosa.protocol.v1.LogRecord;

import java.io.BufferedInputStream;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.GZIPInputStream;

/**
 * Prints a game log as text, one record per line, for debugging:
 *
 * <pre>LogDump &lt;file.pb.gz&gt; [seat] [from seq] [to seq] [decisions]</pre>
 *
 * With a seat, events of other seats are left out; with a range, only events in it (and the records between them).
 * With {@code decisions}, decision records are printed too: the request without its observation, the deciding
 * player's mana pool, the response and what was applied.
 */
public final class LogDump {
    private LogDump() {}

    public static void main(String[] args) throws Exception {
        int seat = args.length > 1 ? Integer.parseInt(args[1]) : -1;
        long from = args.length > 2 ? Long.parseLong(args[2]) : 0;
        long to = args.length > 3 ? Long.parseLong(args[3]) : Long.MAX_VALUE;
        boolean decisions = args.length > 4 && args[4].equals("decisions");
        long current = 0;
        try (InputStream in = new GZIPInputStream(new BufferedInputStream(Files.newInputStream(Path.of(args[0]))))) {
            LogRecord r;
            while ((r = LogRecord.parseDelimitedFrom(in)) != null) {
                if (r.hasEvent()) {
                    if (seat >= 0 && r.getEvent().getViewerSeat() != seat) {
                        continue;
                    }
                    current = r.getEvent().getSeq();
                }
                if (current < from || current > to) {
                    continue;
                }
                if (decisions && r.hasDecision() && r.getDecision().hasRequest()) {
                    var d = r.getDecision();
                    var pool = d.getRequest().getObservation().getPlayers(d.getRequest().getSeat()).getManaPoolList();
                    System.out.println("DECISION " + TextFormat.shortDebugString(d.toBuilder()
                        .setRequest(d.getRequest().toBuilder().clearObservation()).build()) + " POOL "
                        + pool.stream().map(TextFormat::shortDebugString).toList());
                    continue;
                }
                if (r.hasDecision() || r.hasPrivileged() || r.hasSnapshot()) {
                    System.out.println("  [" + r.getRecordCase() + "]");
                    continue;
                }
                System.out.println(TextFormat.shortDebugString(r));
            }
        }
    }
}
