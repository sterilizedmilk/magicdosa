package magicdosa.agent.search;

import com.google.protobuf.Descriptors.FieldDescriptor;
import com.google.protobuf.Message;
import magicdosa.protocol.v1.Candidate;
import magicdosa.protocol.v1.DecisionRequest;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Tree reuse ({@code -Dmagicdosa.search.treeReuse}): a decision's information key and its candidates' names with the
 * entity IDs replaced by an order of the objects' contents, so the same information set has the same key in the real
 * game's session and in a search copy's (a copy's session numbers its objects from 1 at the root, the real one over the
 * whole game). An object's place in the order is the sorted position of its content with every ID cleared (ties: by
 * ID); an ID that names no object of the key (a hidden card) becomes 0. A rare tie between objects of equal content
 * but different references may give the two sessions different keys: then a tree is not reused, never a wrong node.
 */
public final class CanonicalKey {
    /** The key of the request's information set and its candidates' canonical names (candidate order). */
    public record Canon(String key, List<String> candidates) {
    }

    private CanonicalKey() {
    }

    public static Canon of(DecisionRequest r) {
        return of(r, null);
    }

    /** Diagnosis ({@code -Dmagicdosa.search.reuseDebug}): the renumbered message the key hashes. */
    public static Message message(DecisionRequest r) {
        Message[] out = new Message[1];
        of(r, out);
        return out[0];
    }

    private static Canon of(DecisionRequest r, Message[] keep) {
        Message info = sessionFree(ForgeSearchGame.infoMessage(r));
        Map<Long, String> content = new HashMap<>();
        collect(info, content);
        List<Map.Entry<Long, String>> order = new ArrayList<>(content.entrySet());
        order.sort(Map.Entry.<Long, String>comparingByValue().thenComparing(Map.Entry.comparingByKey()));
        Map<Long, Long> ordinal = new HashMap<>();
        for (int i = 0; i < order.size(); i++) {
            ordinal.put(order.get(i).getKey(), (long) i + 1);
        }
        Message canonical = renumber(info, ordinal);
        if (keep != null) {
            keep[0] = canonical;
        }
        String key = magicdosa.agent.shadow.Fingerprint.hash(canonical);
        List<String> names = new ArrayList<>();
        for (Candidate c : r.getCandidatesList()) {
            names.add(com.google.protobuf.TextFormat.shortDebugString(renumber(c, ordinal)));
        }
        return new Canon(key, names);
    }

    /**
     * Without what only a session that saw the whole game knows: the mulligans taken, and library cards the seat knows
     * (the cards a mulligan put on the bottom, a scry's); a copy's session starts at the search root and knows neither.
     * The library's size stays in the player states.
     */
    private static DecisionRequest sessionFree(DecisionRequest r) {
        magicdosa.protocol.v1.Observation.Builder o = r.getObservation().toBuilder().clearObjects();
        for (magicdosa.protocol.v1.GameObject g : r.getObservation().getObjectsList()) {
            if (g.getZone() != magicdosa.protocol.v1.Zone.ZONE_LIBRARY) {
                o.addObjects(g);
            }
        }
        for (int i = 0; i < o.getPlayersCount(); i++) {
            o.setPlayers(i, o.getPlayers(i).toBuilder().clearMulligans());
        }
        return r.toBuilder().setObservation(o).build();
    }

    /** The objects of the key (game objects, stack items, effects) by entity ID: their contents without IDs. */
    private static void collect(Message m, Map<Long, String> content) {
        String type = m.getDescriptorForType().getName();
        if (type.equals("GameObject") || type.equals("StackItem") || type.equals("Effect")) {
            FieldDescriptor id = m.getDescriptorForType().findFieldByName("id");
            long v = (Long) m.getField(id);
            String c = com.google.protobuf.TextFormat.shortDebugString(renumber(m, Map.of()));
            content.merge(v, type + ":" + c, (a, b) -> a.compareTo(b) <= 0 ? a + "&" + b : b + "&" + a);
        }
        for (Map.Entry<FieldDescriptor, Object> e : m.getAllFields().entrySet()) {
            if (e.getKey().getJavaType() != FieldDescriptor.JavaType.MESSAGE) {
                continue;
            }
            if (e.getKey().isRepeated()) {
                for (Object o : (List<?>) e.getValue()) {
                    collect((Message) o, content);
                }
            } else {
                collect((Message) e.getValue(), content);
            }
        }
    }

    private static boolean isId(FieldDescriptor f) {
        if (f.getJavaType() != FieldDescriptor.JavaType.LONG) {
            return false;
        }
        String n = f.getName();
        return n.equals("id") || n.equals("ids") || n.endsWith("_id") || n.endsWith("_ids");
    }

    /** {@code m} with every entity ID replaced by its ordinal (0 when it has none). */
    @SuppressWarnings("unchecked")
    static Message renumber(Message m, Map<Long, Long> ordinal) {
        Message.Builder b = m.toBuilder();
        for (Map.Entry<FieldDescriptor, Object> e : new TreeMap<>(m.getAllFields()).entrySet()) {
            FieldDescriptor f = e.getKey();
            if (isId(f)) {
                if (f.isRepeated()) {
                    b.clearField(f);
                    for (Object o : (List<?>) e.getValue()) {
                        b.addRepeatedField(f, ordinal.getOrDefault((Long) o, 0L));
                    }
                } else {
                    b.setField(f, ordinal.getOrDefault((Long) e.getValue(), 0L));
                }
            } else if (f.getJavaType() == FieldDescriptor.JavaType.MESSAGE) {
                if (f.isRepeated()) {
                    b.clearField(f);
                    List<Message> items = new ArrayList<>();
                    for (Object o : (List<?>) e.getValue()) {
                        items.add(renumber((Message) o, ordinal));
                    }
                    String t = f.getMessageType().getName();
                    if (t.equals("GameObject") || t.equals("Effect")) {
                        // in entity-ID order in a normalised observation: in the new IDs' order here
                        FieldDescriptor id = f.getMessageType().findFieldByName("id");
                        items.sort(java.util.Comparator.<Message>comparingLong(x -> (Long) x.getField(id))
                            .thenComparing(com.google.protobuf.TextFormat::shortDebugString));
                    }
                    items.forEach(x -> b.addRepeatedField(f, x));
                } else {
                    b.setField(f, renumber((Message) e.getValue(), ordinal));
                }
            }
        }
        return b.build();
    }
}
