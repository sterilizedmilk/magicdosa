package magicdosa.runner.forge;

import magicdosa.adapter.forge.DecisionClient;
import magicdosa.protocol.Versions;
import magicdosa.protocol.v1.AdapterMessage;
import magicdosa.protocol.v1.DecisionRecord;
import magicdosa.protocol.v1.DecisionRequest;
import magicdosa.protocol.v1.DecisionResponse;
import magicdosa.protocol.v1.Hello;
import magicdosa.protocol.v1.HelloReply;
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
import java.util.zip.GZIPInputStream;

/**
 * Replays a logged game (design: 결정론 수준 디버깅 "로그 재생"; CLAUDE.md 1단계 "로그로 한 판을 재연"): answers each
 * decision request with agent-core's response as the log recorded it, and checks that the request the adapter builds
 * now is the one it built then.
 */
public final class ReplayDecisionClient implements DecisionClient {
    private final Map<String, DecisionRecord> records = new HashMap<>();
    private final List<String> mismatches = new ArrayList<>();
    private int replayed;
    private String modelVersion = "";
    private int encoderVersion;

    /** Loads the decision records of every game log in {@code files}. */
    public ReplayDecisionClient(List<Path> files) throws IOException {
        for (Path f : files) {
            try (InputStream in = new GZIPInputStream(new BufferedInputStream(Files.newInputStream(f)))) {
                LogRecord r;
                while ((r = LogRecord.parseDelimitedFrom(in)) != null) {
                    if (r.hasHeader()) {
                        modelVersion = r.getHeader().getModelVersion(); // answer the handshake as the logged agent-core did
                        encoderVersion = r.getHeader().getEncoderVersion();
                    }
                    if (r.hasDecision() && r.getDecision().hasRequest() && r.getDecision().hasResponse()) {
                        DecisionRequest q = r.getDecision().getRequest();
                        records.put(q.getGameId() + "#" + q.getDecisionId(), r.getDecision());
                    }
                }
            }
        }
    }

    public List<String> mismatches() {
        return mismatches;
    }

    public int replayed() {
        return replayed;
    }

    public int recorded() {
        return records.size();
    }

    @Override
    public HelloReply hello(Hello hello) {
        return HelloReply.newBuilder().setAccepted(true).setProtocolVersion(Versions.PROTOCOL).setEncoderVersion(encoderVersion)
            .setModelVersion(modelVersion).build();
    }

    @Override
    public void send(AdapterMessage message) {
    }

    @Override
    public DecisionResponse decide(DecisionRequest request) {
        DecisionRecord logged = records.get(request.getGameId() + "#" + request.getDecisionId());
        if (logged == null) {
            mismatches.add(request.getGameId() + " decision " + request.getDecisionId() + ": not in the log");
            throw new IllegalStateException("decision not in the log");
        }
        if (!logged.getRequest().equals(request)) {
            mismatches.add(request.getGameId() + " decision " + request.getDecisionId() + ": request differs from the log");
        }
        replayed++;
        return logged.getResponse();
    }
}
