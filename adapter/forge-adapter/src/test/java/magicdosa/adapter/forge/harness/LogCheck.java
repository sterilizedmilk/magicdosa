package magicdosa.adapter.forge.harness;

import magicdosa.protocol.Versions;
import magicdosa.protocol.v1.LogRecord;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import java.util.zip.GZIPInputStream;

/**
 * Reads game logs back and checks their shape (design: 학습 데이터 경로 "프로토콜 로그가 원본"): header first with the
 * current protocol version, gapless event sequence per seat, a request in every agent decision record, game over last.
 */
public final class LogCheck {
    private LogCheck() {}

    /** Problems found in every {@code *.pb.gz} under {@code dir}; empty when all logs are well formed. */
    public static List<String> check(Path dir) throws IOException {
        List<String> problems = new ArrayList<>();
        try (Stream<Path> files = Files.list(dir)) {
            for (Path f : files.filter(p -> p.toString().endsWith(".pb.gz")).sorted().toList()) {
                problems.addAll(checkFile(f));
            }
        }
        return problems;
    }

    static List<String> checkFile(Path f) throws IOException {
        List<String> problems = new ArrayList<>();
        Map<Integer, Long> seq = new HashMap<>();
        int n = 0;
        LogRecord last = null;
        try (InputStream in = new GZIPInputStream(new BufferedInputStream(Files.newInputStream(f)))) {
            LogRecord r;
            while ((r = LogRecord.parseDelimitedFrom(in)) != null) {
                if (n == 0 && (!r.hasHeader() || r.getHeader().getProtocolVersion() != Versions.PROTOCOL)) {
                    problems.add(f.getFileName() + ": first record is not a current header");
                }
                if (r.hasEvent()) {
                    long expected = seq.getOrDefault(r.getEvent().getViewerSeat(), 0L) + 1;
                    if (r.getEvent().getSeq() != expected) {
                        problems.add(f.getFileName() + ": seat " + r.getEvent().getViewerSeat() + " seq " + r.getEvent().getSeq()
                            + " expected " + expected);
                    }
                    seq.put(r.getEvent().getViewerSeat(), r.getEvent().getSeq());
                }
                if (r.hasDecision() && r.getDecision().getEngineDecision().isEmpty()) {
                    problems.add(f.getFileName() + ": decision record without engine decision");
                }
                last = r;
                n++;
            }
        }
        if (last == null || !last.hasGameOver()) {
            problems.add(f.getFileName() + ": last record is not game over");
        }
        return problems;
    }
}
