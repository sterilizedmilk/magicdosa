package magicdosa.agent.shadow;

import com.google.protobuf.Descriptors.FieldDescriptor;
import com.google.protobuf.Message;
import magicdosa.protocol.v1.DecisionRequest;
import magicdosa.protocol.v1.Effect;
import magicdosa.protocol.v1.GameObject;
import magicdosa.protocol.v1.Observation;
import magicdosa.protocol.v1.TurnStartSnapshot;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Public-information fingerprint of a decision point (design: 그림자·탐색 "지문 정의"). L1: turn, step, active and
 * priority player, life and player counters, zone sizes, stack size. L2: every object the deciding seat can see
 * (identity, tapped, counters, damage, power / toughness, controller, attachment, summoning sickness, graveyard order,
 * exile, stack items with keys and targets), combat, effects. L3: the legal actions (candidates, constraints, the
 * decision's context). Objects are normalised by entity ID and the whole is hashed once; only when the hashes differ
 * are the fields compared one by one. Hidden objects carry no identity in an observation, so they count only by
 * number. Request bookkeeping (decision ID, seed, applied sequence number, bundle ID) is left out.
 */
public final class Fingerprint {
    /** Fields an engine may show differently (design: "경미"); everything else is critical. */
    private static final Set<String> MINOR = Set.of("summoning_sick", "power", "toughness", "can_attack",
        "potential_casts", "characteristics", "description", "engine_prompt", "label");

    public enum Level { L1, L2, L3 }

    /** One field that differs: where, both values, which level, and whether it is critical. */
    public record Diff(Level level, String path, String real, String shadow, boolean critical) {
        @Override
        public String toString() {
            return level + (critical ? "!" : "~") + " " + path + ": real=" + real + " shadow=" + shadow;
        }
    }

    private Fingerprint() {
    }

    /** The request with bookkeeping fields cleared and objects in entity ID order. */
    public static DecisionRequest normalise(DecisionRequest r) {
        DecisionRequest.Builder b = r.toBuilder().clearDecisionId().clearSeed().clearAppliedSeq().clearGameId()
            .clearProtocolVersion().clearRejections();
        if (r.hasBundle()) {
            b.setBundle(r.getBundle().toBuilder().clearId());
        }
        b.setObservation(normalise(r.getObservation()));
        if (r.hasContext() && !r.getContext().getEnginePrompt().isEmpty()) {
            // engine text for debugging; Forge's own card numbers in it ("No More Lies (118)") are not game state
            b.setContext(r.getContext().toBuilder().setEnginePrompt(r.getContext().getEnginePrompt().replaceAll("\\(\\d+\\)", "(#)")));
        }
        if (b.getContext().hasDamage() && b.getContext().getDamage().getEngineDefaultCount() > 1) {
            // Forge AI's default split comes from a hash map keyed by Forge's own card numbers (logs written before
            // the adapter put it in entity-ID order): compared as a set
            List<magicdosa.protocol.v1.DamageShare> shares = new ArrayList<>(b.getContext().getDamage().getEngineDefaultList());
            shares.sort(Comparator.comparing(com.google.protobuf.TextFormat::shortDebugString));
            b.setContext(b.getContext().toBuilder().setDamage(b.getContext().getDamage().toBuilder().clearEngineDefault()
                .addAllEngineDefault(shares)));
        }
        return b.build();
    }

    public static Observation normalise(Observation o) {
        List<GameObject> objects = new ArrayList<>(o.getObjectsList());
        objects.sort(Comparator.comparingLong(GameObject::getId));
        List<Effect> effects = new ArrayList<>();
        for (Effect e : o.getEffectsList()) {
            effects.add(e.toBuilder().clearDescription().build()); // engine text for debugging (names Forge's card numbers)
        }
        effects.sort(Comparator.comparingLong(Effect::getId));
        return o.toBuilder().clearObjects().addAllObjects(objects).clearEffects().addAllEffects(effects).build();
    }

    public static TurnStartSnapshot normalise(TurnStartSnapshot s) {
        return s.toBuilder().clearGameId().clearSeq().setObservation(normalise(s.getObservation())).build();
    }

    public static String hash(Message normalised) {
        try {
            MessageDigest d = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(d.digest(normalised.toByteArray()));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Field differences between two normalised messages (empty when equal). */
    public static List<Diff> diff(Message real, Message shadow) {
        List<Diff> out = new ArrayList<>();
        compare("", real, shadow, out);
        return out;
    }

    private static Level level(String path) {
        if (path.startsWith("candidates") || path.startsWith("constraints") || path.startsWith("type")
                || path.startsWith("context") || path.startsWith("acting_seat") || path.startsWith("bundle")) {
            return Level.L3;
        }
        if (path.contains("objects") || path.contains("stack") || path.contains("combat") || path.contains("effects")) {
            return Level.L2;
        }
        return Level.L1;
    }

    private static boolean critical(String path) {
        for (String part : path.split("[.\\[\\]#]")) {
            if (MINOR.contains(part)) {
                return false;
            }
        }
        return true;
    }

    private static void add(List<Diff> out, String path, Object real, Object shadow) {
        out.add(new Diff(level(path), path, String.valueOf(real), String.valueOf(shadow), critical(path)));
    }

    private static void compare(String prefix, Message a, Message b, List<Diff> out) {
        for (FieldDescriptor f : a.getDescriptorForType().getFields()) {
            String path = prefix.isEmpty() ? f.getName() : prefix + "." + f.getName();
            if (f.isRepeated()) {
                compareRepeated(path, f, a, b, out);
            } else if (f.getJavaType() == FieldDescriptor.JavaType.MESSAGE) {
                boolean ha = a.hasField(f), hb = b.hasField(f);
                if (ha != hb) {
                    add(out, path, ha ? short_(a.getField(f)) : "unset", hb ? short_(b.getField(f)) : "unset");
                } else if (ha) {
                    compare(path, (Message) a.getField(f), (Message) b.getField(f), out);
                }
            } else {
                Object va = a.getField(f), vb = b.getField(f);
                boolean ha = !f.hasPresence() || a.hasField(f), hb = !f.hasPresence() || b.hasField(f);
                if (ha != hb || !va.equals(vb)) {
                    add(out, path, ha ? va : "unset", hb ? vb : "unset");
                }
            }
        }
    }

    private static void compareRepeated(String path, FieldDescriptor f, Message a, Message b, List<Diff> out) {
        int na = a.getRepeatedFieldCount(f), nb = b.getRepeatedFieldCount(f);
        boolean byId = f.getJavaType() == FieldDescriptor.JavaType.MESSAGE
            && f.getMessageType().findFieldByName("id") != null
            && f.getMessageType().findFieldByName("id").getJavaType() == FieldDescriptor.JavaType.LONG;
        if (byId) {
            Map<Long, Message> ma = byId(a, f), mb = byId(b, f);
            for (Map.Entry<Long, Message> e : ma.entrySet()) {
                Message other = mb.get(e.getKey());
                if (other == null) {
                    add(out, path + "#" + e.getKey(), short_(e.getValue()), "missing");
                } else {
                    compare(path + "#" + e.getKey(), e.getValue(), other, out);
                }
            }
            for (Map.Entry<Long, Message> e : mb.entrySet()) {
                if (!ma.containsKey(e.getKey())) {
                    add(out, path + "#" + e.getKey(), "missing", short_(e.getValue()));
                }
            }
            return;
        }
        if (na != nb) {
            add(out, path + ".size", na, nb);
        }
        for (int i = 0; i < Math.min(na, nb); i++) {
            Object va = a.getRepeatedField(f, i), vb = b.getRepeatedField(f, i);
            if (va instanceof Message ma && vb instanceof Message mb) {
                compare(path + "[" + i + "]", ma, mb, out);
            } else if (!va.equals(vb)) {
                add(out, path + "[" + i + "]", va, vb);
            }
        }
        for (int i = Math.min(na, nb); i < Math.max(na, nb); i++) {
            Object v = i < na ? a.getRepeatedField(f, i) : b.getRepeatedField(f, i);
            add(out, path + "[" + i + "]", i < na ? short_(v) : "missing", i < nb ? short_(v) : "missing");
        }
    }

    private static Map<Long, Message> byId(Message m, FieldDescriptor f) {
        FieldDescriptor id = f.getMessageType().findFieldByName("id");
        Map<Long, Message> out = new LinkedHashMap<>();
        for (int i = 0; i < m.getRepeatedFieldCount(f); i++) {
            Message e = (Message) m.getRepeatedField(f, i);
            out.put((Long) e.getField(id), e);
        }
        return out;
    }

    private static String short_(Object v) {
        String s = v instanceof Message m ? com.google.protobuf.TextFormat.shortDebugString(m) : String.valueOf(v);
        return s.length() > 160 ? s.substring(0, 160) + "..." : s;
    }
}
