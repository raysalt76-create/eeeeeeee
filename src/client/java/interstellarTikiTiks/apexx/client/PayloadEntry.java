package interstellarTikiTiks.apexx.client;

import net.fabricmc.api.ClientModInitializer;

/**
 * Hand-off point when the client is delivered as a runtime payload rather than shipped in the
 * mod jar.
 *
 * <h2>Two ways in, one implementation</h2>
 * Apexx can run in either of two arrangements:
 *
 * <ol>
 *   <li><b>Shipped jar</b> (today): Fabric reads {@code fabric.mod.json} and calls
 *       {@link Apexx#onInitializeClient()} directly. This class is not involved.</li>
 *   <li><b>Loader payload</b>: the public jar is only the stub {@code apexx-loader}, which
 *       downloads the client at pre-launch, defines it from memory, and calls
 *       {@link #bootstrap()} here. Fabric never sees the client's entrypoints because the
 *       client is not on the classpath at manifest-scan time.</li>
 * </ol>
 *
 * Both paths converge on the same {@link Apexx#onInitializeClient()}, so there is exactly one
 * initialization routine and no risk of the two arrangements drifting.
 *
 * <p>The stub locates this class by the fully-qualified name hardcoded in
 * {@code ApexxPreLauncher.CLIENT_INIT_CLASS}; keep the two in step if either moves.
 */
public final class PayloadEntry {

    private static volatile boolean started = false;

    /** Invoked by the loader stub once the payload's classes are defined. Idempotent. */
    public void bootstrap() {
        if (started) {
            return;
        }
        started = true;
        try {
            new Apexx().onInitializeClient();
            new ApexxClient().onInitializeClient();
        } catch (Throwable t) {
            // The loader catches this at Fabric's client-init boundary and keeps vanilla alive.
            // Letting it escape is important: otherwise the loader reports a successful bootstrap
            // even though auth, payload registration, or client initialization failed.
            if (t instanceof Error error) throw error;
            if (t instanceof RuntimeException runtimeException) throw runtimeException;
            throw new RuntimeException("Apexx client bootstrap failed", t);
        }
    }
}
