package magicdosa.agent.shadow;

import magicdosa.protocol.v1.DecisionRecord;
import magicdosa.protocol.v1.Event;
import magicdosa.protocol.v1.LogHeader;
import magicdosa.protocol.v1.LogRecord;
import magicdosa.protocol.v1.PrivilegedRecord;
import magicdosa.protocol.v1.TurnStartSnapshot;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;
import java.util.zip.GZIPInputStream;

/**
 * One game's log seen from one agent seat (design: 학습 데이터 경로; Q11): that seat's event stream, turn-start
 * snapshots and decisions, and the hidden state the log keeps for it. Records keep their log order: {@code at} is the
 * position of each record in the log.
 */
public final class GameRecord {
    public record Decision(int at, DecisionRecord record) {}

    public record Snapshot(int at, TurnStartSnapshot snapshot) {}

    public final LogHeader header;
    public final int viewer;
    /** Events of the viewer's stream by sequence number. */
    public final TreeMap<Long, Event> events = new TreeMap<>();
    public final List<Snapshot> snapshots = new ArrayList<>();
    /**
     * Decisions agent-core answered for the viewer, in the order they were asked (decision ID). The log writes a
     * priority decision only once its action was played, after the decisions asked while playing it.
     */
    public final List<Decision> decisions = new ArrayList<>();
    /** The viewer's hidden-state records in log order. */
    public final List<PrivilegedRecord> privileged = new ArrayList<>();
    /** The game end of the log (winner, reason, turns), or null when the log has none. */
    public magicdosa.protocol.v1.GameOver gameOver;
    public final Path file;

    private GameRecord(Path file, LogHeader header, int viewer) {
        this.file = file;
        this.header = header;
        this.viewer = viewer;
    }

    public static List<LogRecord> read(Path file) throws IOException {
        List<LogRecord> out = new ArrayList<>();
        try (InputStream in = new GZIPInputStream(new BufferedInputStream(Files.newInputStream(file)))) {
            LogRecord r;
            while ((r = LogRecord.parseDelimitedFrom(in)) != null) {
                out.add(r);
            }
        }
        return out;
    }

    /** The game from the point of view of {@code viewer} (an agent seat of the log). */
    public static GameRecord of(Path file, List<LogRecord> records, int viewer) {
        GameRecord g = new GameRecord(file, records.get(0).getHeader(), viewer);
        for (int i = 0; i < records.size(); i++) {
            LogRecord r = records.get(i);
            switch (r.getRecordCase()) {
                case EVENT -> {
                    if (r.getEvent().getViewerSeat() == viewer) {
                        g.events.put(r.getEvent().getSeq(), r.getEvent());
                    }
                }
                case SNAPSHOT -> {
                    if (r.getSnapshot().getSeat() == viewer) {
                        g.snapshots.add(new Snapshot(i, r.getSnapshot()));
                    }
                }
                case DECISION -> {
                    DecisionRecord d = r.getDecision();
                    if (d.hasRequest() && d.hasResponse() && d.getRequest().getSeat() == viewer) {
                        g.decisions.add(new Decision(i, d));
                    }
                }
                case PRIVILEGED -> {
                    if (r.getPrivileged().getSeat() == viewer) {
                        g.privileged.add(r.getPrivileged());
                    }
                }
                case GAME_OVER -> g.gameOver = r.getGameOver().toBuilder().clearPrivileged().build();
                default -> { }
            }
        }
        g.decisions.sort(java.util.Comparator.comparingLong(d -> d.record().getRequest().getDecisionId()));
        return g;
    }

    /** The hidden state recorded at {@code seq} (the last one with that sequence number), or null. */
    public PrivilegedRecord privilegedAt(long seq) {
        PrivilegedRecord out = null;
        for (PrivilegedRecord p : privileged) {
            if (p.getSeq() == seq) {
                out = p;
            }
        }
        return out;
    }
}
