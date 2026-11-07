package magicdosa.agent.encoder;

import magicdosa.agent.AgentCore;
import magicdosa.protocol.Versions;
import magicdosa.protocol.v1.DecisionRequest;
import magicdosa.protocol.v1.DecisionResponse;
import magicdosa.protocol.v1.DecisionStep;
import magicdosa.protocol.v1.GameObject;
import magicdosa.protocol.v1.GameOver;
import magicdosa.protocol.v1.HiddenIdentity;
import magicdosa.protocol.v1.Hello;
import magicdosa.protocol.v1.Privileged;
import magicdosa.protocol.v1.PrivilegedRecord;
import magicdosa.protocol.v1.StepCandidate;
import magicdosa.protocol.v1.Zone;
import org.apache.arrow.memory.BufferAllocator;
import org.apache.arrow.memory.RootAllocator;
import org.apache.arrow.vector.BitVector;
import org.apache.arrow.vector.FieldVector;
import org.apache.arrow.vector.Float4Vector;
import org.apache.arrow.vector.IntVector;
import org.apache.arrow.vector.BigIntVector;
import org.apache.arrow.vector.VarCharVector;
import org.apache.arrow.vector.VectorSchemaRoot;
import org.apache.arrow.vector.complex.ListVector;
import org.apache.arrow.vector.complex.impl.UnionListWriter;
import org.apache.arrow.vector.ipc.ArrowFileWriter;
import org.apache.arrow.vector.types.FloatingPointPrecision;
import org.apache.arrow.vector.types.pojo.ArrowType;
import org.apache.arrow.vector.types.pojo.Field;
import org.apache.arrow.vector.types.pojo.FieldType;
import org.apache.arrow.vector.types.pojo.Schema;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One game's training samples as an Arrow IPC file (encoder E5, E6; design: 학습 전략과 보상 "학습 데이터 경로"):
 * {@code <game_id>.arrow}, one row per decision step, written when the game is over (after GameOver brought the
 * privileged records). Variable-length values are flat lists; widths and feature names, and the protocol, encoder,
 * vocabulary, model and engine versions, are in the schema metadata. Rows of steps that agent-core did not decide by
 * itself are kept with their decider, so training can leave them out.
 */
public final class ShardWriter {
    private static final ArrowType I32 = new ArrowType.Int(32, true);
    private static final ArrowType I64 = new ArrowType.Int(64, true);
    private static final ArrowType F32 = new ArrowType.FloatingPoint(FloatingPointPrecision.SINGLE);

    private static final List<String> INT_COLS = List.of("game_number", "seat", "acting_seat", "decision_type", "step",
        "steps", "decider", "prior", "search_skip_reason", "chosen", "end_reason", "turns", "oov");
    private static final List<String> FLOAT_LISTS = List.of("global", "token_feat", "cand_feat", "visit_ratio");
    private static final List<String> INT_LISTS = List.of("token_kind", "token_card", "token_key", "rel_src", "rel_dst",
        "rel_kind", "deck_card", "deck_count", "deck_group", "cand_kind", "cand_ref0", "cand_ref1", "cand_card", "cand_key",
        "cand_string", "priv_opp_hand", "priv_my_library", "priv_opp_library");

    private final Encoder encoder;
    private final Path dir;
    private final String modelVersion;

    public Vocab vocabulary() {
        return encoder.vocab();
    }

    public ShardWriter(Encoder encoder, Path dir, String modelVersion) {
        this.encoder = encoder;
        this.dir = dir;
        this.modelVersion = modelVersion;
    }

    /** A decision as agent-core answered it. */
    public record Decided(DecisionRequest request, DecisionResponse response) {}

    /** Writes the game's shard; returns its path. */
    public Path write(Hello hello, List<Decided> decided, GameOver over) throws IOException {
        Map<String, PrivilegedRecord> privileged = new HashMap<>();
        for (PrivilegedRecord p : over.getPrivilegedList()) {
            privileged.put(p.getDecisionId() + "/" + p.getSeat(), p);
        }
        List<Map<String, Object>> rows = new ArrayList<>();
        long oovBefore = encoder.vocab().oovCount();
        for (Decided d : decided) {
            long oovStart = encoder.vocab().oovCount();
            Encoder.Encoded e = encoder.encode(d.request());
            int oov = (int) (encoder.vocab().oovCount() - oovStart);
            PrivilegedRecord priv = privileged.get(d.request().getDecisionId() + "/" + d.request().getSeat());
            List<Integer> selectedTokens = new ArrayList<>();
            List<DecisionStep> steps = d.response().getStepsList();
            for (int s = 0; s < steps.size(); s++) {
                rows.add(row(hello, d, e, steps.get(s), s, steps.size(), selectedTokens, priv, over, oov));
                StepCandidate picked = steps.get(s).getCandidates(steps.get(s).getChosen());
                if (picked.hasIndex() && e.candRef0()[picked.getIndex()] >= 0) {
                    selectedTokens.add(e.candRef0()[picked.getIndex()]);
                }
            }
        }
        Files.createDirectories(dir);
        Path file = dir.resolve(hello.getGameId() + ".arrow");
        Map<String, String> meta = new LinkedHashMap<>();
        meta.put("protocol_version", String.valueOf(Versions.PROTOCOL));
        meta.put("encoder_version", String.valueOf(AgentCore.ENCODER_VERSION));
        meta.put("vocab_version", String.valueOf(encoder.vocab().version()));
        meta.put("model_version", modelVersion);
        meta.put("engine", hello.getAdapter() + " " + hello.getEngineVersion());
        meta.put("global_features", String.join(",", Encoder.GLOBAL));
        meta.put("token_features", String.join(",", Encoder.TOKEN));
        meta.put("candidate_features", String.join(",", Encoder.CANDIDATE));
        meta.put("oov", String.valueOf(encoder.vocab().oovCount() - oovBefore));
        write(file, rows, meta);
        return file;
    }

    private Map<String, Object> row(Hello hello, Decided d, Encoder.Encoded e, DecisionStep step, int s, int steps,
                                    List<Integer> selected, PrivilegedRecord priv, GameOver over, int oov) {
        DecisionRequest q = d.request();
        Map<String, Object> r = new HashMap<>();
        r.put("game_id", hello.getGameId());
        r.put("match_id", hello.getMatchId());
        r.put("decision_id", q.getDecisionId());
        r.put("game_number", hello.getGameNumber());
        r.put("seat", q.getSeat());
        r.put("acting_seat", q.hasActingSeat() ? q.getActingSeat() : q.getSeat());
        r.put("decision_type", q.getTypeValue());
        r.put("step", s);
        r.put("steps", steps);
        r.put("decider", step.getDeciderValue());
        r.put("prior", step.getMethod().getPriorValue());
        r.put("search_ran", step.getMethod().hasRan());
        r.put("search_skip_reason", step.getMethod().getSkippedValue());
        r.put("search_nodes", step.getMethod().hasRan() ? step.getMethod().getRan().getNodes() : 0L);
        r.put("chosen", step.getChosen());
        r.put("visit_ratio", toList(step.getVisitRatioList()));
        boolean draw = !over.hasWinnerSeat();
        r.put("is_draw", draw);
        r.put("result", draw ? 0f : over.getWinnerSeat() == q.getSeat() ? 1f : 0f);
        r.put("end_reason", over.getReasonValue());
        r.put("turns", over.getTurns());
        r.put("oov", oov);
        r.put("global", toList(e.global()));
        float[] feat = e.tokenFeat().clone();
        int width = Encoder.TOKEN.size();
        for (int t : selected) {
            feat[t * width + Encoder.tokenColumn("selected")] = 1;
        }
        r.put("token_feat", toList(feat));
        r.put("token_kind", toList(e.tokenKind()));
        r.put("token_card", toList(e.tokenCard()));
        r.put("token_key", toList(e.tokenKey()));
        r.put("rel_src", toList(e.relSrc()));
        r.put("rel_dst", toList(e.relDst()));
        r.put("rel_kind", toList(e.relKind()));
        r.put("deck_card", toList(e.deckCard()));
        r.put("deck_count", toList(e.deckCount()));
        r.put("deck_group", toList(e.deckGroup()));
        // this step's candidates: the request's candidates still open, plus "done"
        List<Integer> kind = new ArrayList<>(), ref0 = new ArrayList<>(), ref1 = new ArrayList<>(), card = new ArrayList<>(),
            key = new ArrayList<>(), str = new ArrayList<>();
        List<Float> cf = new ArrayList<>();
        int cw = Encoder.CANDIDATE.size();
        for (StepCandidate c : step.getCandidatesList()) {
            if (c.hasIndex()) {
                int i = c.getIndex();
                kind.add(e.candKind()[i]);
                ref0.add(e.candRef0()[i]);
                ref1.add(e.candRef1()[i]);
                card.add(e.candCard()[i]);
                key.add(e.candKey()[i]);
                str.add(e.candString()[i]);
                for (int k = 0; k < cw; k++) {
                    cf.add(e.candFeat()[i * cw + k]);
                }
            } else {
                kind.add(Encoder.DONE);
                ref0.add(-1);
                ref1.add(-1);
                card.add(Vocab.PAD);
                key.add(Vocab.PAD);
                str.add(Vocab.PAD);
                for (int k = 0; k < cw; k++) {
                    cf.add(k == Encoder.candidateColumn("done") ? 1f : 0f);
                }
            }
        }
        r.put("cand_kind", kind);
        r.put("cand_ref0", ref0);
        r.put("cand_ref1", ref1);
        r.put("cand_card", card);
        r.put("cand_key", key);
        r.put("cand_string", str);
        r.put("cand_feat", cf);
        // critic and belief labels (Q11): hidden cards of this decision's moment, by vocabulary index
        List<Integer> oppHand = new ArrayList<>(), myLib = new ArrayList<>(), oppLib = new ArrayList<>();
        if (priv != null) {
            Privileged p = priv.getPrivileged();
            Map<Long, String> identity = new HashMap<>();
            for (HiddenIdentity h : p.getIdentitiesList()) {
                identity.put(h.getId(), h.getOracleId());
            }
            for (GameObject g : q.getObservation().getObjectsList()) {
                if (g.getZone() == Zone.ZONE_HAND && g.getOwnerSeat() != q.getSeat()) {
                    oppHand.add(encoder.vocab().card(identity.getOrDefault(g.getId(), g.getOracleId())));
                }
            }
            for (var lib : p.getLibrariesList()) {
                List<Integer> out = lib.getSeat() == q.getSeat() ? myLib : oppLib;
                for (long id : lib.getIdsList()) {
                    out.add(encoder.vocab().card(identity.getOrDefault(id, "")));
                }
            }
        }
        r.put("priv_opp_hand", oppHand);
        r.put("priv_my_library", myLib);
        r.put("priv_opp_library", oppLib);
        return r;
    }

    private static List<Float> toList(float[] a) {
        List<Float> out = new ArrayList<>(a.length);
        for (float f : a) {
            out.add(f);
        }
        return out;
    }

    private static List<Float> toList(List<Float> a) {
        return a;
    }

    private static List<Integer> toList(int[] a) {
        List<Integer> out = new ArrayList<>(a.length);
        for (int i : a) {
            out.add(i);
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private static void write(Path file, List<Map<String, Object>> rows, Map<String, String> meta) throws IOException {
        List<Field> fields = new ArrayList<>();
        fields.add(new Field("game_id", FieldType.notNullable(ArrowType.Utf8.INSTANCE), null));
        fields.add(new Field("match_id", FieldType.notNullable(ArrowType.Utf8.INSTANCE), null));
        fields.add(new Field("decision_id", FieldType.notNullable(I64), null));
        fields.add(new Field("search_nodes", FieldType.notNullable(I64), null));
        for (String c : INT_COLS) {
            fields.add(new Field(c, FieldType.notNullable(I32), null));
        }
        fields.add(new Field("search_ran", FieldType.notNullable(ArrowType.Bool.INSTANCE), null));
        fields.add(new Field("is_draw", FieldType.notNullable(ArrowType.Bool.INSTANCE), null));
        fields.add(new Field("result", FieldType.notNullable(F32), null));
        for (String c : FLOAT_LISTS) {
            fields.add(listOf(c, F32));
        }
        for (String c : INT_LISTS) {
            fields.add(listOf(c, I32));
        }
        Schema schema = new Schema(fields, meta);
        try (BufferAllocator alloc = new RootAllocator();
             VectorSchemaRoot root = VectorSchemaRoot.create(schema, alloc)) {
            root.allocateNew();
            int n = rows.size();
            for (int i = 0; i < n; i++) {
                Map<String, Object> r = rows.get(i);
                ((VarCharVector) root.getVector("game_id")).setSafe(i, ((String) r.get("game_id")).getBytes(StandardCharsets.UTF_8));
                ((VarCharVector) root.getVector("match_id")).setSafe(i, ((String) r.get("match_id")).getBytes(StandardCharsets.UTF_8));
                ((BigIntVector) root.getVector("decision_id")).setSafe(i, (Long) r.get("decision_id"));
                ((BigIntVector) root.getVector("search_nodes")).setSafe(i, (Long) r.get("search_nodes"));
                for (String c : INT_COLS) {
                    ((IntVector) root.getVector(c)).setSafe(i, ((Number) r.get(c)).intValue());
                }
                ((BitVector) root.getVector("search_ran")).setSafe(i, (Boolean) r.get("search_ran") ? 1 : 0);
                ((BitVector) root.getVector("is_draw")).setSafe(i, (Boolean) r.get("is_draw") ? 1 : 0);
                ((Float4Vector) root.getVector("result")).setSafe(i, (Float) r.get("result"));
            }
            for (String c : FLOAT_LISTS) {
                UnionListWriter w = ((ListVector) root.getVector(c)).getWriter();
                for (int i = 0; i < n; i++) {
                    w.setPosition(i);
                    w.startList();
                    for (Float f : (List<Float>) rows.get(i).get(c)) {
                        w.writeFloat4(f);
                    }
                    w.endList();
                }
                w.setValueCount(n);
            }
            for (String c : INT_LISTS) {
                UnionListWriter w = ((ListVector) root.getVector(c)).getWriter();
                for (int i = 0; i < n; i++) {
                    w.setPosition(i);
                    w.startList();
                    for (Integer v : (List<Integer>) rows.get(i).get(c)) {
                        w.writeInt(v);
                    }
                    w.endList();
                }
                w.setValueCount(n);
            }
            for (FieldVector v : root.getFieldVectors()) {
                v.setValueCount(n);
            }
            root.setRowCount(n);
            try (FileChannel ch = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE,
                    StandardOpenOption.TRUNCATE_EXISTING);
                 ArrowFileWriter writer = new ArrowFileWriter(root, null, ch)) {
                writer.start();
                writer.writeBatch();
                writer.end();
            }
        }
    }

    private static Field listOf(String name, ArrowType item) {
        return new Field(name, FieldType.notNullable(ArrowType.List.INSTANCE),
            List.of(new Field("item", FieldType.notNullable(item), null)));
    }
}
