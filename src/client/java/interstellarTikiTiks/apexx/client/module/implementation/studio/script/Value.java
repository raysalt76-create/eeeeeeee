package interstellarTikiTiks.apexx.client.module.implementation.studio.script;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A tagged dynamic value for the Apexx evaluator: {@code number} (double),
 * {@code bool}, {@code string}, {@code entity} (opaque handle), {@code list},
 * {@code record} (read-only named fields, e.g. an event) or {@code null}.
 *
 * <p>Numbers and bools flow through the primitive {@code num} field (no boxing);
 * everything else rides {@code obj}. Common constants are interned so steady-state
 * evaluation allocates almost nothing.</p>
 *
 * <p>{@code entity} values are opaque: scripts read them only through the fixed
 * field set the host exposes ({@code e.health}, {@code e.name}, ...) and host
 * functions — never through reflection.</p>
 */
public final class Value {
    public enum Type { NUMBER, BOOL, STRING, ENTITY, LIST, RECORD, NULL }

    /** Hard cap on list length so a script loop cannot grow memory without bound. */
    public static final int MAX_LIST = 4096;

    public final Type type;
    public final double num;     // NUMBER value, or BOOL as 1.0/0.0
    public final Object obj;     // STRING / ENTITY / LIST (List<Value>) / RECORD (Map<String,Value>)

    private Value(Type type, double num, Object obj) {
        this.type = type;
        this.num = num;
        this.obj = obj;
    }

    // Interned constants
    public static final Value NULL = new Value(Type.NULL, 0, null);
    public static final Value TRUE = new Value(Type.BOOL, 1.0, null);
    public static final Value FALSE = new Value(Type.BOOL, 0.0, null);
    private static final Value ZERO = new Value(Type.NUMBER, 0.0, null);
    private static final Value ONE = new Value(Type.NUMBER, 1.0, null);
    public static final Value EMPTY_STRING = new Value(Type.STRING, 0, "");

    public static Value number(double n) {
        if (n == 0.0) return ZERO;
        if (n == 1.0) return ONE;
        return new Value(Type.NUMBER, n, null);
    }

    public static Value bool(boolean b) { return b ? TRUE : FALSE; }

    public static Value string(String s) {
        if (s == null) return NULL;
        if (s.isEmpty()) return EMPTY_STRING;
        return new Value(Type.STRING, 0, s);
    }

    /** Wrap an opaque host object (e.g. a Minecraft entity) or NULL if absent. */
    public static Value entity(Object e) {
        return e == null ? NULL : new Value(Type.ENTITY, 0, e);
    }

    /** A new mutable list (capped at {@link #MAX_LIST}). */
    public static Value list(List<Value> items) {
        ArrayList<Value> copy = new ArrayList<>(Math.min(items == null ? 0 : items.size(), MAX_LIST));
        if (items != null) {
            for (Value v : items) {
                if (copy.size() >= MAX_LIST) break;
                copy.add(v == null ? NULL : v);
            }
        }
        return new Value(Type.LIST, 0, copy);
    }

    /** A read-only record of named fields (insertion order kept for printing). */
    public static Value record(Map<String, Value> fields) {
        return new Value(Type.RECORD, 0, Collections.unmodifiableMap(new LinkedHashMap<>(fields)));
    }

    /** Fluent builder for records, used by the event dispatcher. */
    public static RecordBuilder rec() { return new RecordBuilder(); }

    public static final class RecordBuilder {
        private final LinkedHashMap<String, Value> fields = new LinkedHashMap<>();
        public RecordBuilder put(String k, Value v) { fields.put(k, v == null ? NULL : v); return this; }
        public RecordBuilder num(String k, double v) { return put(k, number(v)); }
        public RecordBuilder bool(String k, boolean v) { return put(k, Value.bool(v)); }
        public RecordBuilder str(String k, String v) { return put(k, string(v == null ? "" : v)); }
        public RecordBuilder ent(String k, Object v) { return put(k, entity(v)); }
        public Value build() { return record(fields); }
    }

    // ── Coercions used by the evaluator / host boundary ─────────────────────

    public boolean isNull() { return type == Type.NULL; }

    /** Numeric view: NUMBER as-is, BOOL as 1/0, everything else 0. */
    public double asNumber() {
        return (type == Type.NUMBER || type == Type.BOOL) ? num : 0.0;
    }

    /**
     * Truthiness: bool is itself; number is non-zero (and non-NaN); a string or list
     * is truthy iff non-empty; an entity handle and a record are truthy; null is false.
     */
    public boolean asBool() {
        switch (type) {
            case BOOL: return num != 0.0;
            case NUMBER: return num != 0.0 && !Double.isNaN(num);
            case STRING: return obj != null && !((String) obj).isEmpty();
            case ENTITY: return obj != null;
            case LIST: return !asList().isEmpty();
            case RECORD: return true;
            case NULL:
            default: return false;
        }
    }

    public String asString() {
        switch (type) {
            case STRING: return (String) obj;
            case NUMBER: {
                // print integers without a trailing ".0"
                if (num == Math.floor(num) && !Double.isInfinite(num) && Math.abs(num) < 1e15) {
                    return Long.toString((long) num);
                }
                return Double.toString(num);
            }
            case BOOL: return num != 0.0 ? "true" : "false";
            case ENTITY: return "<entity>";
            case LIST: {
                StringBuilder sb = new StringBuilder("[");
                List<Value> l = asList();
                for (int i = 0; i < l.size() && sb.length() < 512; i++) {
                    if (i > 0) sb.append(", ");
                    Value v = l.get(i);
                    sb.append(v.type == Type.STRING ? "\"" + v.asString() + "\"" : v.asString());
                }
                return sb.append(']').toString();
            }
            case RECORD: {
                StringBuilder sb = new StringBuilder("{");
                boolean first = true;
                for (Map.Entry<String, Value> e : asRecord().entrySet()) {
                    if (sb.length() > 512) break;
                    if (!first) sb.append(", ");
                    first = false;
                    sb.append(e.getKey()).append(": ").append(e.getValue().asString());
                }
                return sb.append('}').toString();
            }
            case NULL:
            default: return "null";
        }
    }

    /** The raw opaque entity object, or null. */
    public Object asEntity() {
        return type == Type.ENTITY ? obj : null;
    }

    /** The backing list (mutable), or an immutable empty list for non-lists. */
    @SuppressWarnings("unchecked")
    public List<Value> asList() {
        return type == Type.LIST ? (List<Value>) obj : List.of();
    }

    /** The record's fields, or an empty map for non-records. */
    @SuppressWarnings("unchecked")
    public Map<String, Value> asRecord() {
        return type == Type.RECORD ? (Map<String, Value>) obj : Map.of();
    }

    @Override
    public String toString() { return type + ":" + asString(); }
}
