package magicdosa.agent.model;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OnnxValue;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;
import magicdosa.agent.AgentCore;
import magicdosa.agent.encoder.Encoder;
import magicdosa.agent.encoder.Vocab;
import magicdosa.protocol.v1.DecisionRequest;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The play network run by ONNX Runtime on the CPU (design: 학습·평가 "추론 위치"; the contract is
 * docs/python-training.md "ONNX contract"). The model's metadata must name agent-core's own model structure, encoder
 * and vocabulary versions, and the encoder's feature names; any difference refuses the model (CLAUDE.md: versions).
 * One request at a time (batch 1), one thread, so the same request always gives the same answer.
 */
public final class OnnxEvaluator implements Evaluator {
    /** Model inputs in the contract's order (Python {@code shards.MODEL_INPUTS}). */
    public static final List<String> INPUTS = List.of("global", "token_kind", "token_card", "token_key", "token_feat",
        "token_mask", "rel_src", "rel_dst", "rel_kind", "rel_mask", "deck_card", "deck_count", "deck_group", "deck_mask",
        "decision_type", "cand_kind", "cand_ref0", "cand_ref1", "cand_card", "cand_key", "cand_string", "cand_feat",
        "cand_mask");

    /** Thrown when a model does not fit this agent-core. */
    public static final class ModelMismatch extends RuntimeException {
        ModelMismatch(String why) {
            super(why);
        }
    }

    private final OrtEnvironment env;
    private final OrtSession session;
    private final Encoder encoder;
    private final Map<String, String> metadata;

    public OnnxEvaluator(Path model, Encoder encoder) {
        this.encoder = encoder;
        try {
            env = OrtEnvironment.getEnvironment();
            OrtSession.SessionOptions o = new OrtSession.SessionOptions();
            o.setIntraOpNumThreads(1); // parallelism is by process (2026-11-11), and one thread keeps answers fixed
            o.setInterOpNumThreads(1);
            o.setExecutionMode(OrtSession.SessionOptions.ExecutionMode.SEQUENTIAL);
            session = env.createSession(model.toString(), o);
            metadata = Map.copyOf(session.getMetadata().getCustomMetadata());
        } catch (OrtException e) {
            throw new IllegalArgumentException("cannot load model " + model + ": " + e.getMessage(), e);
        }
        try {
            check(metadata, encoder.vocab());
            if (!List.copyOf(session.getInputNames()).equals(INPUTS)) {
                throw new ModelMismatch("model inputs " + session.getInputNames() + ", expected " + INPUTS);
            }
            if (!session.getOutputNames().containsAll(List.of("policy_logits", "value"))) {
                throw new ModelMismatch("model outputs " + session.getOutputNames() + ", expected policy_logits, value");
            }
        } catch (RuntimeException e) {
            close();
            throw e;
        }
    }

    /** Refuses metadata whose model structure, encoder, vocabulary or feature names differ from this agent-core's. */
    static void check(Map<String, String> meta, Vocab vocab) {
        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("model_version", String.valueOf(AgentCore.MODEL_VERSION));
        expected.put("encoder_version", String.valueOf(AgentCore.ENCODER_VERSION));
        expected.put("vocab_version", String.valueOf(vocab.version()));
        expected.put("inputs", String.join(",", INPUTS));
        // feature names are optional in the metadata; when present they must be the encoder's
        Map<String, String> features = Map.of("global_features", String.join(",", Encoder.GLOBAL),
            "token_features", String.join(",", Encoder.TOKEN), "candidate_features", String.join(",", Encoder.CANDIDATE));
        List<String> wrong = new ArrayList<>();
        expected.forEach((k, v) -> {
            if (!v.equals(meta.get(k))) {
                wrong.add(k + " " + meta.get(k) + " (agent-core " + v + ")");
            }
        });
        features.forEach((k, v) -> {
            if (meta.containsKey(k) && !v.equals(meta.get(k))) {
                wrong.add(k + " differ");
            }
        });
        if (!wrong.isEmpty()) {
            throw new ModelMismatch("model refused: " + String.join(", ", wrong));
        }
    }

    /** The model's metadata (versions, model name). */
    public Map<String, String> metadata() {
        return metadata;
    }

    /** The trained instance's name ({@code model_name}, e.g. "gen0-run1"). */
    public String modelName() {
        return metadata.getOrDefault("model_name", "");
    }

    @Override
    public Evaluation evaluate(DecisionRequest request, List<Integer> candidates, List<Integer> chosen) {
        // a request has no privileged block (Q11: it is a separate log record), and the graph has no privileged input
        if (candidates.isEmpty()) {
            throw new IllegalArgumentException("a step without candidates");
        }
        Encoder.Encoded e = encoder.encode(request);
        List<Integer> selected = new ArrayList<>();
        for (int i : chosen) {
            if (e.candRef0()[i] >= 0) {
                selected.add(e.candRef0()[i]); // as the shards flag them (ShardWriter)
            }
        }
        Encoder.Step st = Encoder.step(e, candidates, selected);
        float[] logits;
        float value;
        Map<String, OnnxTensor> in = new HashMap<>();
        try {
            int gw = Encoder.GLOBAL.size(), tw = Encoder.TOKEN.size(), cw = Encoder.CANDIDATE.size();
            int tokens = e.tokenKind().length;
            in.put("global", OnnxTensor.createTensor(env, new float[][] {e.global()}));
            in.put("token_kind", longs(e.tokenKind(), 0));
            in.put("token_card", longs(e.tokenCard(), 0));
            in.put("token_key", longs(e.tokenKey(), 0));
            in.put("token_feat", OnnxTensor.createTensor(env, new float[][][] {rows(st.tokenFeat(), Math.max(tokens, 1), tw)}));
            in.put("token_mask", mask(tokens));
            in.put("rel_src", longs(e.relSrc(), -1));
            in.put("rel_dst", longs(e.relDst(), -1));
            in.put("rel_kind", longs(e.relKind(), 0));
            in.put("rel_mask", mask(e.relSrc().length));
            in.put("deck_card", longs(e.deckCard(), 0));
            in.put("deck_count", longs(e.deckCount(), 0));
            in.put("deck_group", longs(e.deckGroup(), 0));
            in.put("deck_mask", mask(e.deckCard().length));
            in.put("decision_type", OnnxTensor.createTensor(env, new long[] {request.getTypeValue()}));
            in.put("cand_kind", longs(st.candKind(), 0));
            in.put("cand_ref0", longs(st.candRef0(), -1));
            in.put("cand_ref1", longs(st.candRef1(), -1));
            in.put("cand_card", longs(st.candCard(), 0));
            in.put("cand_key", longs(st.candKey(), 0));
            in.put("cand_string", longs(st.candString(), 0));
            in.put("cand_feat", OnnxTensor.createTensor(env, new float[][][] {rows(st.candFeat(), candidates.size(), cw)}));
            in.put("cand_mask", mask(candidates.size()));
            if (e.global().length != gw) {
                throw new IllegalStateException("global width " + e.global().length);
            }
            try (OrtSession.Result r = session.run(in)) {
                logits = ((float[][]) value(r, "policy_logits"))[0];
                value = ((float[]) value(r, "value"))[0];
            }
        } catch (OrtException ex) {
            throw new IllegalStateException("inference failed: " + ex.getMessage(), ex);
        } finally {
            in.values().forEach(OnnxTensor::close);
        }
        if (logits.length != candidates.size()) {
            throw new IllegalStateException("policy over " + logits.length + " candidates, the step has " + candidates.size());
        }
        return new Evaluation(softmax(logits), Math.min(1, Math.max(0, value)));
    }

    private static Object value(OrtSession.Result r, String name) throws OrtException {
        OnnxValue v = r.get(name).orElseThrow(() -> new IllegalStateException("no output " + name));
        return v.getValue();
    }

    /** [1, max(n, 1)] int64; an empty list is one masked slot holding {@code fill}. */
    private OnnxTensor longs(int[] a, long fill) throws OrtException {
        long[] out = new long[Math.max(a.length, 1)];
        if (a.length == 0) {
            out[0] = fill;
        }
        for (int i = 0; i < a.length; i++) {
            out[i] = a[i];
        }
        return OnnxTensor.createTensor(env, new long[][] {out});
    }

    private OnnxTensor mask(int n) throws OrtException {
        boolean[] out = new boolean[Math.max(n, 1)];
        java.util.Arrays.fill(out, 0, n, true);
        return OnnxTensor.createTensor(env, new boolean[][] {out});
    }

    /** A flat row-major array as {@code n} rows of {@code width} (zero rows beyond the data: padding). */
    private static float[][] rows(float[] flat, int n, int width) {
        float[][] out = new float[n][width];
        for (int i = 0; i * width < flat.length && i < n; i++) {
            System.arraycopy(flat, i * width, out[i], 0, width);
        }
        return out;
    }

    static float[] softmax(float[] logits) {
        float max = Float.NEGATIVE_INFINITY;
        for (float l : logits) {
            max = Math.max(max, l);
        }
        double sum = 0;
        double[] ex = new double[logits.length];
        for (int i = 0; i < logits.length; i++) {
            ex[i] = Math.exp(logits[i] - max);
            sum += ex[i];
        }
        float[] out = new float[logits.length];
        for (int i = 0; i < logits.length; i++) {
            out[i] = (float) (ex[i] / sum);
        }
        return out;
    }

    @Override
    public void close() {
        try {
            if (session != null) {
                session.close();
            }
        } catch (OrtException e) {
            // closing: nothing left to do
        }
    }
}
