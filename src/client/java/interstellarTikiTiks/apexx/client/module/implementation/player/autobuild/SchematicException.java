package interstellarTikiTiks.apexx.client.module.implementation.player.autobuild;

/**
 * Thrown when a schematic file cannot be read or parsed. The message is safe to show
 * to the user verbatim (unsupported format, too large, malformed, ...).
 */
public class SchematicException extends Exception {

    public SchematicException(String message) {
        super(message);
    }

    public SchematicException(String message, Throwable cause) {
        super(message, cause);
    }
}
