package interstellarTikiTiks.apexx.client.module.implementation.studio.script;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Snapshot debugger for user scripts. It never pauses anything: when a script reaches a line with a
 * breakpoint, the {@link Evaluator} records a {@link Snapshot} (time, event, line, locals, module vars and
 * settings, all already formatted as short strings) into a bounded ring buffer, and the IDE lets the player
 * step through those snapshots afterwards.
 *
 * <p>Costs and bounds: with debugging off a handler run pays one volatile read. With it on, a run whose script
 * has no breakpoints pays one map lookup; a run with breakpoints pays a bit-set test per statement. At most
 * {@link #PER_RUN_CAP} snapshots are taken per handler run, each value is formatted to at most
 * {@link #MAX_VALUE_CHARS} characters, each section holds at most {@link #MAX_VARS} entries, and the buffer keeps
 * the newest {@link #CAPACITY} snapshots. Breakpoints live in memory only.</p>
 *
 * <p>Threading: scripts and the IDE both run on the client thread; the buffer and breakpoint tables are still
 * guarded so a stray call from another thread cannot corrupt them.</p>
 */
public final class ScriptDebugger {

    public static final int CAPACITY = 200;
    public static final int PER_RUN_CAP = 16;
    public static final int MAX_VARS = 48;
    public static final int MAX_VALUE_CHARS = 120;
    private static final int MAX_BREAKPOINT_LINE = 100_000;

    private ScriptDebugger() {}

    /** One name = value pair, already formatted. */
    public record Var(String name, String value) { }

    /** Everything recorded when a breakpoint line was reached. */
    public record Snapshot(long seq, long timeMs, String script, String event, int line,
                           List<Var> locals, List<Var> vars, List<Var> settings) { }

    /** The breakpoint lines of one script, handed to an {@link Evaluator} for one handler run. Immutable. */
    public static final class Session {
        final String script;
        private final BitSet lines;
        Session(String script, BitSet lines) {
            this.script = script;
            this.lines = lines;
        }
        public boolean isBreak(int line) { return line > 0 && lines.get(line); }
    }

    private static volatile boolean enabled;
    /** Copy-on-write: a stored BitSet is never mutated after it is published. */
    private static final Map<String, BitSet> BREAKPOINTS = new ConcurrentHashMap<>();
    private static final Map<String, Session> SESSIONS = new ConcurrentHashMap<>();
    private static final ArrayDeque<Snapshot> BUFFER = new ArrayDeque<>(CAPACITY);
    private static long nextSeq = 1;

    // ── switches ─────────────────────────────────────────────────────────────

    public static boolean isEnabled() { return enabled; }

    public static void setEnabled(boolean on) { enabled = on; }

    // ── breakpoints ──────────────────────────────────────────────────────────

    /** Adds or removes a breakpoint; returns true if the line now has one. */
    public static synchronized boolean toggleBreakpoint(String script, int line) {
        if (script == null || line <= 0 || line > MAX_BREAKPOINT_LINE) return false;
        BitSet current = BREAKPOINTS.get(script);
        BitSet next = current == null ? new BitSet() : (BitSet) current.clone();
        boolean now = !next.get(line);
        next.set(line, now);
        publish(script, next);
        return now;
    }

    public static synchronized void setBreakpoint(String script, int line, boolean on) {
        if (script == null || line <= 0 || line > MAX_BREAKPOINT_LINE) return;
        BitSet current = BREAKPOINTS.get(script);
        if (current != null && current.get(line) == on) return;
        if (current == null && !on) return;
        BitSet next = current == null ? new BitSet() : (BitSet) current.clone();
        next.set(line, on);
        publish(script, next);
    }

    private static void publish(String script, BitSet lines) {
        if (lines.isEmpty()) {
            BREAKPOINTS.remove(script);
            SESSIONS.remove(script);
        } else {
            BREAKPOINTS.put(script, lines);
            SESSIONS.put(script, new Session(script, lines));
        }
    }

    public static boolean hasBreakpoint(String script, int line) {
        if (script == null || line <= 0) return false;
        BitSet b = BREAKPOINTS.get(script);
        return b != null && b.get(line);
    }

    /** Breakpoint lines of a script, ascending. */
    public static List<Integer> breakpoints(String script) {
        List<Integer> out = new ArrayList<>();
        BitSet b = script == null ? null : BREAKPOINTS.get(script);
        if (b == null) return out;
        for (int i = b.nextSetBit(0); i >= 0; i = b.nextSetBit(i + 1)) out.add(i);
        return out;
    }

    public static synchronized void clearBreakpoints(String script) {
        if (script == null) return;
        BREAKPOINTS.remove(script);
        SESSIONS.remove(script);
    }

    /** Drops every breakpoint and snapshot (sign-out / self-destruct). */
    public static synchronized void clearAll() {
        BREAKPOINTS.clear();
        SESSIONS.clear();
        clearSnapshots(null);
    }

    /** Moves a script's breakpoints when it is renamed (its module name changed). */
    public static synchronized void renameScript(String from, String to) {
        if (from == null || to == null || from.equals(to)) return;
        BitSet b = BREAKPOINTS.remove(from);
        SESSIONS.remove(from);
        if (b != null) publish(to, b);
    }

    /**
     * The session for one handler run of {@code script}, or null when debugging is off or the script has no
     * breakpoints (the evaluator then does no debugger work at all).
     */
    public static Session session(String script) {
        if (!enabled || script == null) return null;
        return SESSIONS.get(script);
    }

    // ── snapshots ────────────────────────────────────────────────────────────

    static void record(String script, String event, int line, List<Var> locals, List<Var> vars, List<Var> settings) {
        synchronized (BUFFER) {
            while (BUFFER.size() >= CAPACITY) BUFFER.pollFirst();
            BUFFER.addLast(new Snapshot(nextSeq++, System.currentTimeMillis(), script,
                    event == null ? "?" : event, line, locals, vars, settings));
        }
    }

    /** Snapshots of one script (or every script when null), oldest first. */
    public static List<Snapshot> snapshots(String script) {
        synchronized (BUFFER) {
            List<Snapshot> out = new ArrayList<>(BUFFER.size());
            for (Snapshot s : BUFFER) if (script == null || script.equals(s.script())) out.add(s);
            return out;
        }
    }

    /** Drops the snapshots of one script (or all when null). */
    public static void clearSnapshots(String script) {
        synchronized (BUFFER) {
            if (script == null) BUFFER.clear();
            else BUFFER.removeIf(s -> script.equals(s.script()));
        }
    }

    // ── formatting ───────────────────────────────────────────────────────────

    /** A short, bounded rendering of a script value for the debug panel. */
    public static String format(Value v) {
        StringBuilder sb = new StringBuilder();
        append(sb, v, 0);
        if (sb.length() > MAX_VALUE_CHARS) {
            sb.setLength(MAX_VALUE_CHARS - 3);
            sb.append("...");
        }
        return sb.toString();
    }

    private static void append(StringBuilder sb, Value v, int depth) {
        if (sb.length() > MAX_VALUE_CHARS) return;
        if (v == null) { sb.append("null"); return; }
        switch (v.type) {
            case STRING: {
                String s = v.asString();
                sb.append('"');
                int room = Math.max(0, MAX_VALUE_CHARS - sb.length());
                if (s.length() > room) sb.append(s, 0, room).append("...");
                else sb.append(s);
                sb.append('"');
                return;
            }
            case ENTITY: {
                Value name = Value.NULL;
                try { name = Evaluator.evalField(v, "name"); } catch (Throwable ignored) { }
                sb.append("<entity");
                if (name.type == Value.Type.STRING && !name.asString().isEmpty()) {
                    String n = name.asString();
                    sb.append(' ').append(n.length() > 32 ? n.substring(0, 32) : n);
                }
                sb.append('>');
                return;
            }
            case LIST: {
                List<Value> list = v.asList();
                if (depth >= 2) { sb.append("[").append(list.size()).append(" items]"); return; }
                sb.append('[');
                for (int i = 0; i < list.size(); i++) {
                    if (sb.length() > MAX_VALUE_CHARS) { sb.append("..."); break; }
                    if (i > 0) sb.append(", ");
                    append(sb, list.get(i), depth + 1);
                }
                sb.append(']');
                return;
            }
            case RECORD: {
                Map<String, Value> rec = v.asRecord();
                if (depth >= 2) { sb.append("{").append(rec.size()).append(" fields}"); return; }
                sb.append('{');
                boolean first = true;
                for (Map.Entry<String, Value> e : rec.entrySet()) {
                    if (sb.length() > MAX_VALUE_CHARS) { sb.append("..."); break; }
                    if (!first) sb.append(", ");
                    first = false;
                    sb.append(e.getKey()).append(": ");
                    append(sb, e.getValue(), depth + 1);
                }
                sb.append('}');
                return;
            }
            default:
                sb.append(v.asString());
        }
    }
}
