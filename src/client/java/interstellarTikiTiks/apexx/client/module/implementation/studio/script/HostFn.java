package interstellarTikiTiks.apexx.client.module.implementation.studio.script;

/**
 * A host function — the ONLY callable surface exposed to Apexx scripts. Each
 * entry in {@link HostApi} wraps an existing client util (input / aim / query /
 * world / math). Scripts cannot define functions and cannot reach Java any other
 * way, so this interface is the entire bridge between the sandbox and the game.
 *
 * @param args evaluated argument values (already length-checked by the compiler
 *             only loosely; implementations must tolerate missing/short args)
 * @param self the owning {@code ScriptModule} (used as the aim-channel owner and
 *             for per-module rate limiting)
 */
@FunctionalInterface
public interface HostFn {
    Value call(Value[] args, ScriptModule self);
}
