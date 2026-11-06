package interstellarTikiTiks.apexx.client.module.implementation.studio.script;

/**
 * Thrown for any lex / parse / compile error. Carries the source line so the
 * GUI can surface a "parse error on line N" message. A bad script is quarantined
 * (its error string stored), never crashing the client.
 *
 * <p>Runtime (evaluation) faults do NOT throw this — they are caught at the
 * handler boundary and skip the tick. This type is purely for compile-time
 * problems that prevent a {@code ScriptModule} from being built at all.</p>
 */
public class ScriptException extends RuntimeException {
    public final int line;

    public ScriptException(String message, int line) {
        super(line > 0 ? ("line " + line + ": " + message) : message);
        this.line = line;
    }

    public ScriptException(String message) {
        this(message, -1);
    }
}
