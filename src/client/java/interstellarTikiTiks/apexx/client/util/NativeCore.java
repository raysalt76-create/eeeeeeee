package interstellarTikiTiks.apexx.client.util;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Locale;

/**
 * Binding for the native protection core ({@code native/rust/apexx_core}), with a verified load and a
 * Java fallback.
 *
 * <h2>What moving this out of Java buys</h2>
 * A pure-Java scheme has the key schedule in the same artifact as the ciphertext, so an analyst who
 * reads the decryptor can reproduce every key without ever running the client. Here the per-site key
 * comes from {@link #keyWord(int, int)}, whose master secret is compiled into a symbol-stripped,
 * LTO'd shared object and appears in no Java class. Reproducing every key statically now means
 * reversing the library; getting plaintext at all means running the client under instrumentation.
 *
 * <h2>The load is verified, and failure falls back rather than breaking the client</h2>
 * A native layer that cannot be trusted is worse than none, so the library is only used after it
 * passes four checks at class-init:
 * <ol>
 *   <li>the ABI version is one this build understands;</li>
 *   <li>the library's own {@code apexx_selfcheck} passes;</li>
 *   <li>{@link #keyWordSeeded} agrees with {@link #mirrorKeyWord} — the Java mirror of the schedule —
 *       over 256 fixed samples, so the two implementations cannot have drifted;</li>
 *   <li>{@code apexx_secret_matches} confirms the library was built with this build's master, which
 *       catches a stale or swapped shared object instead of decrypting every string to garbage.</li>
 * </ol>
 * Any failure leaves {@link #available()} false, records the reason in {@link #status()}, and routes
 * callers to the Java fallback. That fallback is the weaker path by construction — the master is then
 * in Java — which is exactly why a platform that ships without a native core should be treated as a
 * gap to close, not as equivalent protection. {@link #platformKey()} plus {@link #status()} is what a
 * release check reads to notice the gap.
 */
public final class NativeCore {

    private static final String LIBRARY_BASE_NAME = "apexx_core";
    private static final String RESOURCE_ROOT = "/apexx/native";
    /** Must match {@code ABI_VERSION} in {@code native/rust/apexx_core/src/lib.rs}. */
    private static final int EXPECTED_ABI = 1;
    /** Sample count for the load-time agreement test; more than enough to catch a drifted mirror. */
    private static final int MIRROR_SAMPLES = 256;

    private static final boolean AVAILABLE;
    private static final String STATUS;

    static {
        boolean available = false;
        String status = "not loaded";
        try {
            if (!load()) {
                status = "library not found for " + platformKey();
            } else {
                status = verify();
                available = status == null;
                if (available) {
                    status = "active (abi " + abiVersion() + ", " + platformKey() + ")";
                }
            }
        } catch (Throwable failure) {
            // A broken native core must never take the client down with it.
            status = "disabled: " + failure.getClass().getSimpleName()
                    + (failure.getMessage() == null ? "" : " " + failure.getMessage());
        }
        AVAILABLE = available;
        STATUS = status;
        // A native layer that quietly falls back is indistinguishable from no native layer, which is
        // exactly what happened the first time this class was exercised: five entry points had a
        // name mismatch, the load gate caught it, and every caller silently used the Java path. The
        // fallback stays (the client must run), but it announces itself in the log so a release with
        // a missing or stale native core is visible instead of looking protected.
        if (!available) {
            ClientInternalLog.warn("NativeCore", "native protection core unavailable (" + status
                    + "); string keys fall back to the Java schedule for " + platformKey());
        } else {
            ClientInternalLog.info("NativeCore", status);
        }
    }

    private NativeCore() {
    }

    /** True when the native core loaded and passed every load-time check. */
    public static boolean available() {
        return AVAILABLE;
    }

    /** Human-readable state, for diagnostics and for the release audit. */
    public static String status() {
        return STATUS;
    }

    /** The platform/arch directory this build would load from, e.g. {@code macos/arm64}. */
    public static String platformKey() {
        return detectPlatform() + "/" + detectArch();
    }

    // ── API ─────────────────────────────────────────────────────────────────────────────────────

    /**
     * Per-site key for the string layer. Native when verified; otherwise the Java mirror of the same
     * schedule driven by {@link CryptoVault#nativeMaster()}.
     * Note: StringEncryptor generates call sites by pushing nonce then classId onto the stack,
     * so parameter 1 is nonce and parameter 2 is classId.
     */
    public static int keyWord(int nonce, int classId) {
        if (AVAILABLE) {
            return keyWord0(classId, nonce);
        }
        return mirrorKeyWord(CryptoVault.nativeMaster(), classId, nonce);
    }

    /**
     * Always true, never statically provable. The native value comes from a mutable state this
     * library owns, so a simplifier has nothing to evaluate; the fallback uses the airtight
     * {@code n*(n+1) even} identity over a runtime-mixed value (see {@code ObfRuntime.opaqueTrue}).
     */
    public static boolean opaqueTrue(int seed) {
        if (AVAILABLE) {
            return (opaque0(seed) & 1) == 0;
        }
        return ObfRuntime.opaqueTrue(seed);
    }

    /** Per-shard secret for the spread integrity checks; zero when native is unavailable. */
    public static long macSeed(int shard) {
        return AVAILABLE ? macSeed0(shard) : 0L;
    }

    /** Debug/instrumentation probe score; 0 when native is unavailable. */
    public static int antiDebug() {
        return AVAILABLE ? antiDebug0() : 0;
    }

    /** Accumulated probe strikes; 0 when native is unavailable. */
    public static int debugStrikes() {
        return AVAILABLE ? debugStrikes0() : 0;
    }

    // ── Java mirror of the native schedule (see native/rust/apexx_core/src/lib.rs) ───────────────

    /**
     * Mirrors {@code key_word} in the Rust core byte for byte. Used as the fallback key schedule and
     * as the reference the load-time test compares the library against, which is what makes the two
     * implementations' agreement a build-and-load guarantee instead of a comment.
     */
    public static int mirrorKeyWord(long master, int classId, int nonce) {
        long h = master ^ (((long) classId & 0xFFFFFFFFL) << 32)
                ^ ((long) nonce & 0xFFFFFFFFL) ^ 0x9E3779B97F4A7C15L;
        return (int) (mix64(h) >>> 32);
    }

    /** splitmix64 finaliser; mirrors {@code mix64} in the Rust core. */
    static long mix64(long z) {
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }

    // ── Native declarations (symbols exported by the Rust cdylib) ────────────────────────────────

    private static native int abiVersion();

    private static native int selfCheck();

    private static native int keyWord0(int classId, int nonce);

    private static native int keyWordSeeded(long master, int classId, int nonce);

    private static native int secretMatches(long master);

    private static native int opaque0(int seed);

    private static native long macSeed0(int shard);

    private static native int antiDebug0();

    private static native int debugStrikes0();

    // ── Loading ──────────────────────────────────────────────────────────────────────────────────

    private static boolean load() {
        String mapped = System.mapLibraryName(LIBRARY_BASE_NAME);
        String[] fileNames = mapped.endsWith(".dylib")
                ? new String[] { mapped, mapped.substring(0, mapped.length() - ".dylib".length()) + ".jnilib" }
                : new String[] { mapped };
        // A developer drop next to the game (or -Djava.library.path) wins, then the resource the
        // build staged for this platform/arch pair.
        if (loadFromLibraryPath()) {
            return true;
        }
        for (String fileName : fileNames) {
            if (loadFromResource(RESOURCE_ROOT + "/" + platformKey() + "/" + fileName, fileName)) {
                return true;
            }
        }
        return false;
    }

    private static boolean loadFromLibraryPath() {
        try {
            System.loadLibrary(LIBRARY_BASE_NAME);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static boolean loadFromResource(String resourcePath, String fileName) {
        try (InputStream in = NativeCore.class.getResourceAsStream(resourcePath)) {
            if (in == null) {
                return false;
            }
            if (!System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win")) {
                // POSIX: deleteOnExit removes a loaded library fine, and a predictable name in a shared /tmp could
                // be pre-created by another user, so keep the private per-launch directory.
                Path tempDir = Files.createTempDirectory("apexx_core_");
                Path library = tempDir.resolve(fileName);
                Files.copy(in, library, StandardCopyOption.REPLACE_EXISTING);
                library.toFile().deleteOnExit();
                tempDir.toFile().deleteOnExit();
                System.load(library.toAbsolutePath().toString());
                return true;
            }
            byte[] bytes = in.readAllBytes();
            String hash = sha256Hex(bytes).substring(0, 16);
            Path baseDir;
            try {
                baseDir = net.fabricmc.loader.api.FabricLoader.getInstance().getConfigDir().resolve("apexx").resolve("natives");
            } catch (Throwable t) {
                baseDir = Path.of(System.getProperty("user.home", ".")).resolve(".minecraft").resolve("config").resolve("apexx").resolve("natives");
            }
            Path tempDir = baseDir.resolve("lib_core_" + hash);
            Path library = tempDir.resolve(fileName);
            if (!(Files.isRegularFile(library) && hash.equals(sha256Hex(Files.readAllBytes(library)).substring(0, 16)))) {
                Files.createDirectories(tempDir);
                Path staging = Files.createTempFile(tempDir, "lib", ".tmp");
                try {
                    Files.write(staging, bytes);
                    try {
                        Files.move(staging, library, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                    } catch (java.nio.file.AtomicMoveNotSupportedException notAtomic) {
                        Files.move(staging, library, StandardCopyOption.REPLACE_EXISTING);
                    }
                } finally {
                    Files.deleteIfExists(staging);
                }
            }
            library.toFile().deleteOnExit();
            tempDir.toFile().deleteOnExit();
            System.load(library.toAbsolutePath().toString());
            return true;
        } catch (Throwable failure) {
            return false;
        }
    }

    private static String sha256Hex(byte[] data) throws java.security.NoSuchAlgorithmException {
        return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(data));
    }

    /**
     * @return null when the library is trustworthy, otherwise the reason it must not be used
     */
    private static String verify() {
        if (abiVersion() != EXPECTED_ABI) {
            return "disabled: abi mismatch (" + abiVersion() + " != " + EXPECTED_ABI + ")";
        }
        if (selfCheck() != 1) {
            return "disabled: native self-check failed";
        }
        long[] masters = { 0L, 1L, -1L, 0x0123456789ABCDEFL, CryptoVault.nativeMaster() };
        for (long master : masters) {
            for (int i = 0; i < MIRROR_SAMPLES; i++) {
                int classId = (int) mix64(master ^ i);
                int nonce = (int) mix64(master + i * 31L);
                if (keyWordSeeded(master, classId, nonce) != mirrorKeyWord(master, classId, nonce)) {
                    return "disabled: java mirror disagrees with the native schedule (sample " + i + ")";
                }
            }
        }
        if (secretMatches(CryptoVault.nativeMaster()) != 1) {
            return "disabled: native core was built with a different master";
        }
        for (int seed = -64; seed < 64; seed++) {
            if ((opaque0(seed) & 1) != 0) {
                return "disabled: native opaque predicate is not even";
            }
        }
        return null;
    }

    // ── Platform detection ──────────────────────────────────────────────────────────────────────

    private static String detectPlatform() {
        String os = System.getProperty("os.name", "unknown").toLowerCase(Locale.ROOT);
        if (isAndroid()) {
            return "android";
        }
        if (os.contains("mac") || os.contains("darwin")) {
            return "macos";
        }
        if (os.contains("win")) {
            return "windows";
        }
        if (os.contains("linux")) {
            return "linux";
        }
        return "unknown";
    }

    private static String detectArch() {
        String arch = System.getProperty("os.arch", "unknown").toLowerCase(Locale.ROOT);
        return switch (arch) {
            case "aarch64", "arm64", "arm64e" -> "arm64";
            case "x86_64", "amd64" -> "x86_64";
            case "x86", "i386", "i486", "i586", "i686" -> "x86";
            case "arm", "armv7", "armv7l", "armeabi-v7a" -> "armv7";
            default -> arch;
        };
    }

    private static boolean isAndroid() {
        String vmName = System.getProperty("java.vm.name", "").toLowerCase(Locale.ROOT);
        String osName = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        // MobilePlatform owns Android detection; a bare vmName.contains("art") also matched desktop VM names.
        if (MobilePlatform.isAndroidRuntime() || vmName.contains("dalvik") || osName.contains("android")) {
            return true;
        }
        try {
            return new java.io.File("/system/build.prop").exists();
        } catch (Throwable ignored) {
            return false;
        }
    }
}
