package magicdosa.agent.search;

import com.google.protobuf.Descriptors.FieldDescriptor;
import com.google.protobuf.Message;

import java.util.List;
import java.util.Map;
import java.util.function.LongFunction;

/**
 * A message as text with its entity IDs replaced by names (a copy of a game numbers its objects anew; Forge's own card
 * numbers survive the copy). Entity IDs are the integer fields named {@code id}, {@code ids} or ending in {@code _id}
 * or {@code _ids}, except {@code decision_id}; 0 stays 0 (no object).
 */
final class IdNames {
    private IdNames() {
    }

    static String text(Message m, LongFunction<String> name) {
        StringBuilder sb = new StringBuilder();
        append(m, name, sb);
        return sb.toString();
    }

    private static boolean isId(FieldDescriptor f) {
        String n = f.getName();
        boolean integer = f.getJavaType() == FieldDescriptor.JavaType.LONG;
        return integer && !n.equals("decision_id") && (n.equals("id") || n.equals("ids") || n.endsWith("_id") || n.endsWith("_ids"));
    }

    private static void append(Message m, LongFunction<String> name, StringBuilder sb) {
        sb.append('{');
        for (Map.Entry<FieldDescriptor, Object> e : m.getAllFields().entrySet()) {
            FieldDescriptor f = e.getKey();
            sb.append(f.getName()).append(':');
            if (f.isRepeated()) {
                sb.append('[');
                for (Object v : (List<?>) e.getValue()) {
                    value(f, v, name, sb);
                    sb.append(',');
                }
                sb.append(']');
            } else {
                value(f, e.getValue(), name, sb);
            }
            sb.append(';');
        }
        sb.append('}');
    }

    private static void value(FieldDescriptor f, Object v, LongFunction<String> name, StringBuilder sb) {
        if (v instanceof Message mm) {
            append(mm, name, sb);
        } else if (isId(f) && v instanceof Long id && id != 0) {
            sb.append(name.apply(id));
        } else {
            sb.append(v);
        }
    }
}
