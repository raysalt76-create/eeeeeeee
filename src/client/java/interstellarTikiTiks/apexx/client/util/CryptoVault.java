package interstellarTikiTiks.apexx.client.util;

/**
 * Key material for the build's obfuscation layers, kept in the loader rather than inside the
 * classes that use it.
 *
 * <p>Every encrypted string in a payload class is stored against {@code key ^ mask()}, where the
 * class key is derived per class from the build's master key. The master key and the mask live
 * <em>here</em>, in the loader, and are injected as literal constants by
 * {@code apexx.obf.JarObfuscator} during {@code apexxAsmObfuscate}. A payload class therefore holds
 * no value it could decrypt itself with: recovering a literal requires the loader's constant as
 * well, and re-wrapping it in a way that defeats a static emulator requires the loader's code.
 *
 * <p>This is obfuscation cost, not a secrecy boundary. Both artifacts are delivered to the same
 * machine, so a determined analyst with a debugger or a class-dumping agent can read the value at
 * runtime. The design goal is that no <em>static</em> path — reading the class with a decompiler,
 * emulating the decoder, dumping the constant pool — yields plaintext, so the attacker has to run
 * the client under instrumentation and dump classes at the {@code defineClass} boundary. See
 * {@code docs/architecture/OBFUSCATION.md} for the threat model and for the server-derived key path
 * that removes even that assumption.
 *
 * <p>Contract with the build: the three literals below are replaced in place by the ASM pass, and
 * the pass uses the same three values to encrypt. If the pass cannot find them it fails the build
 * instead of shipping a jar that cannot decrypt itself. Do not make them {@code final} — a
 * compile-time constant would be inlined into callers and the injection would become a no-op.
 */
public final class CryptoVault {

    /** Replaced in place by {@code apexxAsmObfuscate}; see the class contract. */
    private static int stringMask = 0x5F356495;
    /** Replaced in place by {@code apexxAsmObfuscate}; see the class contract. */
    private static int bodySeed = 0x2B7E1516;
    /** Replaced in place by {@code apexxAsmObfuscate}; see the class contract. */
    private static int bodySalt = 0x6A09E667;

    /**
     * The master the native core must have been built with. {@code NativeCore} refuses to use a
     * library whose compiled-in secret does not match this value, which turns a stale or swapped
     * shared object into a fallback instead of strings that decode to garbage.
     *
     * <p>Replaced in place by {@code apexxAsmObfuscate} with the same master it writes into
     * {@code native/rust/apexx_core/src/secret.rs} before the native build, so the two artifacts can
     * only agree if they came from one build. The literal here is the from-source placeholder that
     * matches the placeholder in {@code secret.rs}.
     */
    private static long nativeMaster = 0x41504558584B4559L;

    /** The master the native core must have been built with; see the field contract. */
    public static long nativeMaster() {
        return nativeMaster;
    }

    /** Unwraps the per-class key stored at a string call site. */
    public static int mask() {
        return stringMask;
    }

    /** Seed for the payload class-body layer. */
    public static int classBodySeed() {
        return bodySeed;
    }

    /** Per-entry salt for the payload class-body layer. */
    public static int classBodySalt() {
        return bodySalt;
    }

    private CryptoVault() {
    }
}
