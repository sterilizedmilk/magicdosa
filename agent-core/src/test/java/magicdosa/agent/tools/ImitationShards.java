package magicdosa.agent.tools;

import magicdosa.agent.encoder.Encoder;
import magicdosa.agent.encoder.ShardWriter;
import magicdosa.agent.encoder.Vocab;
import magicdosa.protocol.v1.Decider;
import magicdosa.protocol.v1.DecisionRecord;
import magicdosa.protocol.v1.DecisionRequest;
import magicdosa.protocol.v1.DecisionResponse;
import magicdosa.protocol.v1.DecisionStep;
import magicdosa.protocol.v1.GameOver;
import magicdosa.protocol.v1.Hello;
import magicdosa.protocol.v1.LogHeader;
import magicdosa.protocol.v1.LogRecord;
import magicdosa.protocol.v1.PrivilegedRecord;
import magicdosa.protocol.v1.SelectionConstraints;
import magicdosa.protocol.v1.StepCandidate;

import java.io.BufferedInputStream;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Collectors;
import java.util.zip.GZIPInputStream;

/**
 * Offline converter (2026-11-25 planning decision, phase-4 generation-0 initialisation only): game logs of the decision
 * layer gates (Forge AI answering the agent seat's requests: fin3-layer, fin4-layer) to Arrow shards in the encoder's
 * current schema, tagged {@code source=imitation:forge-ai} so they can be kept apart from search data. No game is
 * played again: a log holds every request (observation and candidates), every answer, the privileged records and the
 * game end, which is what {@link ShardWriter} takes. An answer the log recorded as Forge AI's (an intended delegation)
 * carries no steps of its own; its steps are rebuilt from the chosen candidates (one per pick, then "done" where the
 * selection may stop, the order the random policy uses), with the delegation as the decider, so a loader takes the
 * label from it and never mistakes it for a search target. Requests without a request body (delegations noted inside
 * another answer) are left out.
 *
 * <pre>ImitationShards &lt;log root&gt; &lt;out dir&gt; [processes-in-parallel, default 1]</pre>
 */
public final class ImitationShards {
    public static final String SOURCE = "imitation:forge-ai";

    private ImitationShards() {
    }

    public static void main(String[] args) throws Exception {
        Path root = Path.of(args[0]);
        Path out = Path.of(args[1]);
        int threads = args.length > 2 ? Integer.parseInt(args[2]) : 1;
        List<Path> files;
        try (var s = Files.walk(root)) {
            files = s.filter(p -> p.toString().endsWith(".pb.gz")).sorted().collect(Collectors.toList());
        }
        Vocab vocab = Vocab.load();
        long t0 = System.nanoTime(); // measurement only
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        List<Future<long[]>> results = new ArrayList<>();
        for (Path f : files) {
            results.add(pool.submit(() -> convert(f, out, new ShardWriter(new Encoder(vocab), out, ShardWriter.NO_MODEL,
                "forge-ai (decision layer)"))));
        }
        long games = 0, decisions = 0, rebuilt = 0, skipped = 0, failed = 0;
        Map<String, Integer> errors = new TreeMap<>();
        for (int i = 0; i < results.size(); i++) {
            try {
                long[] r = results.get(i).get();
                games++;
                decisions += r[0];
                rebuilt += r[1];
                skipped += r[2];
            } catch (Exception e) {
                failed++;
                errors.merge(String.valueOf(e.getCause()), 1, Integer::sum);
            }
        }
        pool.shutdown();
        System.out.printf("IMITATION shards: games %d, failed %d, decisions %d (steps rebuilt for %d), skipped %d, %.1f s, oov %d%n",
            games, failed, decisions, rebuilt, skipped, (System.nanoTime() - t0) / 1e9, vocab.oovCount());
        errors.forEach((k, v) -> System.out.println("  error " + v + "x " + k));
    }

    /** One game: {decisions, decisions whose steps were rebuilt, records left out}. */
    static long[] convert(Path log, Path out, ShardWriter writer) throws Exception {
        LogHeader header = null;
        List<ShardWriter.Decided> decided = new ArrayList<>();
        List<PrivilegedRecord> privileged = new ArrayList<>();
        GameOver over = null;
        long rebuilt = 0, skipped = 0;
        try (InputStream in = new GZIPInputStream(new BufferedInputStream(Files.newInputStream(log)))) {
            LogRecord r;
            while ((r = LogRecord.parseDelimitedFrom(in)) != null) {
                switch (r.getRecordCase()) {
                    case HEADER -> header = r.getHeader();
                    case DECISION -> {
                        DecisionRecord d = r.getDecision();
                        if (!d.hasRequest() || d.getRequest().getCandidatesCount() == 0 || !d.hasResponse()) {
                            skipped++;
                            continue;
                        }
                        DecisionResponse resp = d.getResponse();
                        if (needsSteps(resp)) {
                            resp = withSteps(d.getRequest(), resp, d.getDecider());
                            rebuilt++;
                        }
                        decided.add(new ShardWriter.Decided(d.getRequest(), resp));
                    }
                    case PRIVILEGED -> privileged.add(r.getPrivileged());
                    case GAME_OVER -> over = r.getGameOver();
                    default -> { }
                }
            }
        }
        if (header == null || over == null) {
            throw new IllegalStateException(log + ": no header or no game end");
        }
        Hello hello = Hello.newBuilder().setProtocolVersion(header.getProtocolVersion()).setAdapter(header.getAdapter())
            .setEngineVersion(header.getEngineVersion()).setGameId(header.getGameId()).setMode(header.getMode())
            .addAllAgentSeats(header.getAgentSeatsList()).setMatchId(header.getMatchId())
            .setGameNumber(header.getGameNumber()).build();
        GameOver withPrivileged = over.toBuilder().clearPrivileged().addAllPrivileged(privileged).build();
        writer.write(hello, decided, withPrivileged, Map.of("source", SOURCE, "teacher", header.getModelVersion(),
            "log_protocol_version", String.valueOf(header.getProtocolVersion()),
            "log_encoder_version", String.valueOf(header.getEncoderVersion()), "log", log.getFileName().toString()));
        return new long[] {decided.size(), rebuilt, skipped};
    }

    /** {@link ShardWriter#needsSteps}. */
    static boolean needsSteps(DecisionResponse r) {
        return ShardWriter.needsSteps(r);
    }

    /** {@link ShardWriter#withSteps}, with the log record's decider when the response names none. */
    static DecisionResponse withSteps(DecisionRequest q, DecisionResponse r, Decider decider) {
        DecisionResponse in = r;
        if ((r.getStepsCount() == 0 || r.getSteps(0).getDecider() == Decider.DECIDER_UNSPECIFIED)
                && decider != Decider.DECIDER_UNSPECIFIED) {
            in = r.toBuilder().clearSteps().addSteps(DecisionStep.newBuilder().setDecider(decider)).build();
        }
        return ShardWriter.withSteps(q, in);
    }
}
