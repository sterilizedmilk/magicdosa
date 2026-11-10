package magicdosa.adapter.forge;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * A checkpoint of one of our own objects (shadow game, phase 2): the translator state that Forge's
 * {@link forge.game.undo.GameMemento} does not walk (entity IDs, what each seat has seen, effect IDs, a controller's
 * pending choice). Fields are copied one level into collections (a map of sets is copied as a map of copied sets);
 * references to Forge objects are kept as they are, since the memento restores those objects in place. Restoring puts
 * the field values back and refills final collections in place.
 */
public final class StateCopy {
    private final Object target;
    private final Map<Field, Object> values = new LinkedHashMap<>();

    private StateCopy(Object target) {
        this.target = target;
    }

    /** Copies the fields declared by {@code target}'s class and its superclasses up to (not including) {@code stopAt}. */
    public static StateCopy of(Object target, Class<?> stopAt) {
        StateCopy s = new StateCopy(target);
        for (Class<?> c = target.getClass(); c != null && c != stopAt && c != Object.class; c = c.getSuperclass()) {
            for (Field f : c.getDeclaredFields()) {
                if (Modifier.isStatic(f.getModifiers())) {
                    continue;
                }
                f.setAccessible(true);
                try {
                    s.values.put(f, copy(f.get(target)));
                } catch (IllegalAccessException e) {
                    throw new IllegalStateException(e);
                }
            }
        }
        return s;
    }

    public void restore() {
        for (Map.Entry<Field, Object> e : values.entrySet()) {
            Field f = e.getKey();
            try {
                Object saved = e.getValue();
                if (Modifier.isFinal(f.getModifiers())) {
                    refill(f.get(target), saved);
                } else {
                    f.set(target, copy(saved));
                }
            } catch (IllegalAccessException ex) {
                throw new IllegalStateException(ex);
            }
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void refill(Object live, Object saved) {
        if (live != null && live.getClass().getName().startsWith("java.util.ImmutableCollections")) {
            return; // an unmodifiable collection never changed
        }
        if (live instanceof Map m && saved instanceof Map s) {
            m.clear();
            ((Map<Object, Object>) s).forEach((k, v) -> m.put(k, copy(v)));
        } else if (live instanceof Collection c && saved instanceof Collection s) {
            c.clear();
            for (Object v : s) {
                c.add(copy(v));
            }
        }
        // other final fields (immutable values, the session, the game) are never reassigned
    }

    /** A copy of a collection or map, one level deep into nested collections; anything else as it is. */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Object copy(Object v) {
        if (v instanceof IdentityHashMap m) {
            IdentityHashMap out = new IdentityHashMap();
            m.forEach((k, x) -> out.put(k, copy(x)));
            return out;
        }
        if (v instanceof TreeMap m) {
            TreeMap out = new TreeMap(m.comparator());
            m.forEach((k, x) -> out.put(k, copy(x)));
            return out;
        }
        if (v instanceof LinkedHashMap m) {
            LinkedHashMap out = new LinkedHashMap();
            m.forEach((k, x) -> out.put(k, copy(x)));
            return out;
        }
        if (v instanceof HashMap m) {
            HashMap out = new HashMap();
            m.forEach((k, x) -> out.put(k, copy(x)));
            return out;
        }
        if (v instanceof java.util.TreeSet s) {
            return new java.util.TreeSet(s);
        }
        if (v instanceof java.util.LinkedHashSet s) {
            return new java.util.LinkedHashSet(s);
        }
        if (v instanceof java.util.HashSet s) {
            return new java.util.HashSet(s);
        }
        if (v instanceof java.util.Set s && s.getClass().getName().contains("SetFromMap")) {
            java.util.Set out = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
            out.addAll(s);
            return out;
        }
        if (v instanceof ArrayDeque d) {
            return new ArrayDeque(d);
        }
        if (v instanceof ArrayList l) {
            List out = new ArrayList();
            for (Object x : l) {
                out.add(x instanceof com.google.protobuf.Message.Builder b ? b.clone() : x);
            }
            return out;
        }
        return v;
    }
}
