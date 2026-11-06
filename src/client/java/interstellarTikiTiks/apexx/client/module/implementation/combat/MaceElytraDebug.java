
package interstellarTikiTiks.apexx.client.module.implementation.combat;

/**
 * Formerly a shared on-disk debug logger for the AutoMace + AutoElytra divebomb combo. The client keeps
 * no trace on disk, so this is now a no-op sink: the call sites stay, nothing is written anywhere.
 */
public final class MaceElytraDebug {

    private MaceElytraDebug() {
    }

    /** No file is kept any more; an empty path signals that. */
    public static String path() {
        return "";
    }

    public static void start(String header) {
        // Intentionally empty: no debug file is created.
    }

    public static void line(String s) {
        // Intentionally empty: nothing is written to disk.
    }

    public static void stop() {
        // Intentionally empty.
    }
}
