package interstellarTikiTiks.apexx.client.util;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * Thread-safe, in-memory circular buffer logger for internal client diagnostics.
 *
 * <p>Writes to memory only: no console output and no disk file. Entries are kept for the
 * running session only, for the client's own error handling and the in-game diagnostics
 * display, and are discarded when the game exits. Nothing here is written to disk or sent
 * anywhere on its own.
 */
public final class ClientInternalLog {

    public enum Level {
        INFO,
        WARN,
        ERROR
    }

    public static final class Entry {
        public final long timestamp;
        public final String timeFormatted;
        public final Level level;
        public final String source;
        public final String message;
        public final String stackTrace;

        public Entry(Level level, String source, String message, Throwable throwable) {
            this.timestamp = System.currentTimeMillis();
            this.timeFormatted = TIME_FORMAT.format(Instant.ofEpochMilli(this.timestamp));
            this.level = level;
            this.source = source != null ? source : "Client";
            this.message = message != null ? message : "";
            if (throwable != null) {
                StringWriter sw = new StringWriter();
                PrintWriter pw = new PrintWriter(sw);
                throwable.printStackTrace(pw);
                this.stackTrace = sw.toString();
            } else {
                this.stackTrace = null;
            }
        }

        public String toJson() {
            StringBuilder sb = new StringBuilder(256);
            sb.append("{");
            sb.append("\"time\":").append(timestamp).append(",");
            sb.append("\"timeStr\":\"").append(escape(timeFormatted)).append("\",");
            sb.append("\"level\":\"").append(level.name()).append("\",");
            sb.append("\"source\":\"").append(escape(source)).append("\",");
            sb.append("\"message\":\"").append(escape(message)).append("\"");
            if (stackTrace != null) {
                sb.append(",\"stack\":\"").append(escape(stackTrace)).append("\"");
            }
            sb.append("}");
            return sb.toString();
        }

        private static String escape(String s) {
            if (s == null) return "";
            return s.replace("\\", "\\\\")
                    .replace("\"", "\\\"")
                    .replace("\b", "\\b")
                    .replace("\f", "\\f")
                    .replace("\n", "\\n")
                    .replace("\r", "\\r")
                    .replace("\t", "\\t");
        }
    }

    private static final int MAX_ENTRIES = 1000;
    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm:ss.SSS")
            .withZone(ZoneId.systemDefault());
    private static final ConcurrentLinkedDeque<Entry> buffer = new ConcurrentLinkedDeque<>();
    // ConcurrentLinkedDeque.size() walks the list; keep a running count instead.
    private static final java.util.concurrent.atomic.AtomicInteger count = new java.util.concurrent.atomic.AtomicInteger();

    private ClientInternalLog() {
    }

    public static void info(String source, String message) {
        log(Level.INFO, source, message, null);
    }

    public static void warn(String source, String message) {
        log(Level.WARN, source, message, null);
    }

    public static void warn(String source, String message, Throwable t) {
        log(Level.WARN, source, message, t);
    }

    public static void error(String source, String message) {
        log(Level.ERROR, source, message, null);
    }

    public static void error(String source, String message, Throwable t) {
        log(Level.ERROR, source, message, t);
    }

    private static void log(Level level, String source, String message, Throwable t) {
        Entry entry = new Entry(level, source, message, t);
        buffer.addLast(entry);
        count.incrementAndGet();
        while (count.get() > MAX_ENTRIES) {
            if (buffer.pollFirst() == null) {
                break;
            }
            count.decrementAndGet();
        }
    }

    public static List<Entry> getRecent(int limit) {
        List<Entry> all = new ArrayList<>(buffer);
        if (all.size() <= limit) {
            return all;
        }
        return all.subList(all.size() - limit, all.size());
    }

    public static void clear() {
        buffer.clear();
        count.set(0);
    }

    public static String toJson(int limit) {
        List<Entry> entries = getRecent(limit);
        StringBuilder sb = new StringBuilder(entries.size() * 128 + 16);
        sb.append("[");
        for (int i = 0; i < entries.size(); i++) {
            if (i > 0) sb.append(",");
            sb.append(entries.get(i).toJson());
        }
        sb.append("]");
        return sb.toString();
    }
}
