package magicdosa.agent.model;

import magicdosa.agent.encoder.Encoder;
import magicdosa.agent.encoder.Vocab;
import magicdosa.agent.shadow.GameRecord;
import magicdosa.protocol.v1.DecisionRequest;
import magicdosa.protocol.v1.LogRecord;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Loads a play network and evaluates the recorded agent-seat requests of game logs (measurement only): prints the
 * model's metadata, a few answers, whether every answer repeats, and the inference time per request on this CPU.
 * <p>
 * {@code NetworkCheck <model.onnx> <log.pb.gz>...}
 */
public final class NetworkCheck {
    public static void main(String[] args) throws Exception {
        try (OnnxEvaluator net = new OnnxEvaluator(Path.of(args[0]), new Encoder(Vocab.load()))) {
            System.out.println("model " + net.modelName() + " " + new java.util.TreeMap<>(net.metadata()).entrySet().stream()
                .filter(e -> !e.getKey().endsWith("_features")).toList());
            List<DecisionRequest> requests = new ArrayList<>();
            List<Integer> logOf = new ArrayList<>();
            for (int i = 1; i < args.length; i++) {
                for (LogRecord r : GameRecord.read(Path.of(args[i]))) {
                    if (r.getRecordCase() == LogRecord.RecordCase.DECISION && r.getDecision().hasRequest()
                            && r.getDecision().getRequest().getCandidatesCount() > 0) {
                        requests.add(r.getDecision().getRequest());
                        logOf.add(i);
                    }
                }
            }
            // warm-up, then timed passes
            for (int i = 0; i < Math.min(50, requests.size()); i++) {
                net.evaluate(requests.get(i));
            }
            List<Evaluator.Evaluation> first = new ArrayList<>();
            long t0 = System.nanoTime();
            for (DecisionRequest q : requests) {
                first.add(net.evaluate(q));
            }
            long t1 = System.nanoTime();
            int differ = 0;
            double sum = 0, min = 1, max = 0;
            for (int i = 0; i < requests.size(); i++) {
                Evaluator.Evaluation a = first.get(i), b = net.evaluate(requests.get(i));
                if (a.value() != b.value() || !Arrays.equals(a.priors(), b.priors())) {
                    differ++;
                }
                sum += a.value();
                min = Math.min(min, a.value());
                max = Math.max(max, a.value());
            }
            for (int i = 0; i < Math.min(5, requests.size()); i++) {
                DecisionRequest q = requests.get(i);
                System.out.printf("decision %d %s seat %d candidates %d: value %.4f priors %s%n", q.getDecisionId(),
                    q.getType(), q.getSeat(), q.getCandidatesCount(), first.get(i).value(), Arrays.toString(first.get(i).priors()));
            }
            String dump = System.getProperty("dump");
            if (dump != null) {
                // log (1-based argument index), decision id, seat, value, priors: to compare with Python's ONNX Runtime on the shards of the same logs
                try (java.io.PrintWriter w = new java.io.PrintWriter(dump, java.nio.charset.StandardCharsets.UTF_8)) {
                    for (int i = 0; i < requests.size(); i++) {
                        w.println(logOf.get(i) + " " + requests.get(i).getDecisionId() + " " + requests.get(i).getSeat() + " " + first.get(i).value()
                            + " " + Arrays.toString(first.get(i).priors()).replace(" ", ""));
                    }
                }
            }
            System.out.printf("requests %d, value mean %.4f min %.4f max %.4f, answers differing on a second pass %d%n",
                requests.size(), sum / Math.max(1, requests.size()), min, max, differ);
            Encoder encoder = new Encoder(Vocab.load());
            long e0 = System.nanoTime();
            for (DecisionRequest q : requests) {
                encoder.encode(q);
            }
            long e1 = System.nanoTime();
            System.out.printf("inference (encode + run, batch 1, one thread): %.3f ms per request, of which encoding %.3f ms%n",
                (t1 - t0) / 1e6 / Math.max(1, requests.size()), (e1 - e0) / 1e6 / Math.max(1, requests.size()));
        }
    }
}
