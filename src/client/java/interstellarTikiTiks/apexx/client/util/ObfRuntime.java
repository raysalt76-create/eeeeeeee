package interstellarTikiTiks.apexx.client.util;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayDeque;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;
import org.jetbrains.annotations.Nullable;

public final class ObfRuntime {
    private static final int MAX_EXPECTED_DEPTH = 256;
    private static final String NATIVE_LIBRARY_BASE_NAME = "core_engine";
    private static final String NATIVE_RESOURCE_ROOT = "/apexx/native";
    private static final long DEBUG_PROBE_INTERVAL_NS = 8_000_000_000L;
    private static final int DEBUG_PROBE_STRIKE_LIMIT = 64;
    private static final String[] DEBUG_MARKERS = {
            "jdwp", "-xdebug", "-javaagent", "debugger", "async-profiler", "jfr", "flightrecorder"
    };
    private static final ThreadLocal<ArrayDeque<Integer>> EXPECTED_CALLS = ThreadLocal.withInitial(ArrayDeque::new);
    private static final AtomicInteger INTEGRITY_MISMATCHES = new AtomicInteger();
    private static final boolean STRICT_INTEGRITY = true;
    private static final boolean HARD_FAIL_INTEGRITY = true;

    // Disabled: the bundled native anti-tamper/watchdog lib (libcore_engine.dylib)
    // corrupts process memory on macOS 26+, crashing the game on launch (SIGBUS in a
    // native watchdog thread). The native VM is currently disabled, so all callers fall back to
    // the pure-Java path while the client keeps its authentication and integrity checks active.
    private static final boolean DISABLE_NATIVE_VM = true;

    private static volatile int rawSink = 0x13579BDF;
    private static volatile boolean nativeVmLoaded = false;
    static {
        tryLoadNativeVm();
    }
    private static volatile long lastDebugProbeNs = 0L;

    private ObfRuntime() {
    }

    public static void link(int callerId, int calleeId) {
        if (!STRICT_INTEGRITY) {
            rawSink ^= (callerId * 31) ^ calleeId;
            return;
        }
        ArrayDeque<Integer> queue = EXPECTED_CALLS.get();
        if (queue.size() >= MAX_EXPECTED_DEPTH) {
            queue.removeFirst();
        }
        queue.addLast(calleeId);
        rawSink ^= vmInt(6, callerId, calleeId, rawSink);
    }

    public static void verifyEntry(int methodId) {
        if (!STRICT_INTEGRITY) {
            rawSink ^= methodId;
            return;
        }
        ArrayDeque<Integer> queue = EXPECTED_CALLS.get();
        if (queue.isEmpty()) {
            flowNoise(methodId ^ 0x7F4A7C15);
            return;
        }

        int expected = queue.removeLast();
        if (expected != methodId) {
            queue.clear();
            int mismatches = INTEGRITY_MISMATCHES.incrementAndGet();
            rawNoise(methodId, expected);

            if (HARD_FAIL_INTEGRITY && mismatches > 12) {
                throw new IllegalStateException("Call graph integrity violation");
            }
            if (mismatches > 512) {
                INTEGRITY_MISMATCHES.set(64);
            }
        } else if ((rawSink & 0xFF) == 0x5A) {
            rawSink ^= vmInt(4, methodId, rawSink, 0x6A09E667);
        }
    }

    public static int decodeInt(int encoded, int key) {
        return vmInt(1, encoded, key, 0);
    }

    public static long decodeLong(long encoded, long key) {
        return vmLong(1, encoded, key, 0L);
    }

    public static float decodeFloat(int encodedBits, int key) {
        return Float.intBitsToFloat(vmInt(1, encodedBits, key, 0));
    }

    public static double decodeDouble(long encodedBits, long key) {
        return Double.longBitsToDouble(vmLong(1, encodedBits, key, 0L));
    }

    public static void flowNoise(int seed) {
        int a = vmInt(6, seed, rawSink, 0x9E3779B9);
        int b = vmInt(4, a, seed, 0x3C6EF372);
        rawSink ^= (b + 0x165667B1);
    }

    public static void rawNoise(int a, int b) {
        int mixed = vmInt(7, a, b, rawSink);
        mixed = vmInt(5, mixed, a ^ b, 0x7F4A7C15);
        rawSink = mixed ^ vmInt(3, rawSink, 0x5F356495, b);
    }

    public static int mixOpaque(int seed, int salt) {
        int m = vmInt(7, seed ^ 0x9E3779B9, salt ^ 0x3C6EF372, rawSink);
        m = vmInt(4, m, salt, 0x7F4A7C15);
        rawSink ^= m;
        return m ^ Integer.rotateLeft(seed, salt & 31);
    }

    public static void checkOpaque(int seed, int tag) {
        maybeProbeDebugState(seed ^ tag);
        if (!STRICT_INTEGRITY) {
            rawSink ^= seed ^ tag;
            return;
        }
        int m = vmInt(6, seed, tag, rawSink);
        rawSink ^= Integer.rotateLeft(m, 5);
        if (STRICT_INTEGRITY && m == 0x42F00D13) {
            int mismatches = INTEGRITY_MISMATCHES.incrementAndGet();
            if (HARD_FAIL_INTEGRITY && mismatches > 32) {
                throw new IllegalStateException("Opaque flow integrity violation");
            }
        }
    }

    /**
     * Opaque predicate for the control-flow pass: always {@code true}, never statically provable.
     *
     * <p>Two properties have to hold at once, and they pull in opposite directions:
     * <ul>
     *   <li><b>Never statically decidable.</b> The value is derived from {@link #rawSink}, a mutable
     *       accumulator that many call sites across the client perturb, so a simplifier cannot
     *       evaluate the branch and delete the dead side. A predicate over a literal
     *       ({@code (seed * (seed + 1)) & 1 == 0}) is airtight but trivially foldable — which is
     *       exactly what Recaf's opaque-predicate simplification does to it.</li>
     *   <li><b>Always true at run time.</b> A product of consecutive integers is even for every
     *       {@code int}, including at the wrap-around extremes (verified over the full 16-bit range
     *       of both operands plus 2·10^7 random samples), and {@code mod 2^32} preserves the low bit,
     *       so the branch always takes the real path. A predicate that could be false would execute
     *       the dead side and change behaviour — not acceptable, whatever it buys.</li>
     * </ul>
     *
     * <p>This method has no failure path on purpose: unlike {@link #checkOpaque} it must be safe to
     * call from any method, on any thread, in any state.
     */
    public static boolean opaqueTrue(int seed) {
        int s = rawSink ^ seed;
        rawSink ^= Integer.rotateLeft(s, 5);
        return ((s * (s + 1)) & 1) == 0;
    }

    public static int vmInt(int opcode, int a, int b, int c) {
        if (nativeVmLoaded) {
            try {
                return nativeVmInt(opcode, a, b, c);
            } catch (Throwable ignored) {
                nativeVmLoaded = false;
            }
        }
        return javaVmInt(opcode, a, b, c);
    }

    public static long vmLong(int opcode, long a, long b, long c) {
        if (nativeVmLoaded) {
            try {
                return nativeVmLong(opcode, a, b, c);
            } catch (Throwable ignored) {
                nativeVmLoaded = false;
            }
        }
        return javaVmLong(opcode, a, b, c);
    }

    public static int getRawSink() {
        int sink = rawSink;
        if ((sink & 0xFF) == 0x5A) {

        }
        return sink;
    }

    public static boolean isNativeVmActive() {
        return nativeVmLoaded;
    }

    public static long verifyDualHash(@Nullable byte[] payload, int salt) {
        if (payload == null || payload.length == 0) {
            return 0L;
        }

        int javaDigest = digestWithJavaVm(payload, salt);
        int runtimeDigest = digestWithRuntimeVm(payload, salt);
        rawSink ^= runtimeDigest;

        if (nativeVmLoaded && javaDigest != runtimeDigest) {
            int mismatches = INTEGRITY_MISMATCHES.incrementAndGet();
            rawNoise(javaDigest, runtimeDigest);
            if (HARD_FAIL_INTEGRITY || mismatches > 8) {
                throw new IllegalStateException("Native/Java hash divergence");
            }
        }

        return (((long) javaDigest) << 32) ^ (runtimeDigest & 0xffffffffL);
    }

    private static int digestWithJavaVm(byte[] payload, int salt) {
        int acc = 0x6A09E667 ^ salt;
        for (int i = 0; i < payload.length; i++) {
            int v = payload[i] & 0xFF;
            int mixSalt = Integer.rotateLeft(salt ^ i ^ 0x3C6EF372, i & 31);
            acc = javaVmInt(7, acc ^ v, mixSalt, 0x9E3779B9);
            acc = javaVmInt(4, acc, (v + i) & 31, 0x7F4A7C15);
        }
        return javaVmInt(6, acc, payload.length, salt ^ 0x94D049BB);
    }

    private static int digestWithRuntimeVm(byte[] payload, int salt) {
        int acc = 0x6A09E667 ^ salt;
        for (int i = 0; i < payload.length; i++) {
            int v = payload[i] & 0xFF;
            int mixSalt = Integer.rotateLeft(salt ^ i ^ 0x3C6EF372, i & 31);
            acc = vmInt(7, acc ^ v, mixSalt, 0x9E3779B9);
            acc = vmInt(4, acc, (v + i) & 31, 0x7F4A7C15);
        }
        return vmInt(6, acc, payload.length, salt ^ 0x94D049BB);
    }

    private static int javaVmInt(int opcode, int a, int b, int c) {
        switch (opcode) {
            case 1:
                return a ^ b;
            case 2:
                return a + b;
            case 3:
                return a - b;
            case 4:
                return Integer.rotateLeft(a ^ c, b & 31);
            case 5:
                return Integer.rotateRight(a + c, b & 31);
            case 6:
                return ((a * 33) ^ b) + c;
            case 7:
                int base = ((a + 0x9E3779B9) ^ (b * 17)) + Integer.rotateLeft(c, 3);
                return Integer.rotateLeft(base ^ c, 7) + 0x9E3779B9;
            default:
                return (a ^ b) + c;
        }
    }

    private static long javaVmLong(int opcode, long a, long b, long c) {
        switch (opcode) {
            case 1:
                return a ^ b;
            case 2:
                return a + b;
            case 3:
                return a - b;
            case 4:
                return Long.rotateLeft(a ^ c, (int) (b & 63));
            case 5:
                return Long.rotateRight(a + c, (int) (b & 63));
            case 6:
                return ((a * 1315423911L) ^ b) + c;
            default:
                return Long.rotateLeft((a ^ b) ^ c, 13) + 0x9E3779B97F4A7C15L;
        }
    }

    private static boolean tryLoadNativeVm() {
        if (DISABLE_NATIVE_VM) {
            return false;
        }
        boolean android = isAndroidRuntime();
        return loadBundledNativeVm(android);
    }

    public static boolean isNativeVmDisabled() {
        return DISABLE_NATIVE_VM;
    }

    private static boolean loadLibraryByName(String name) {
        try {
            System.loadLibrary(name);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static boolean loadBundledNativeVm(boolean android) {
        String platform = detectPlatform(android);
        if ("unknown".equals(platform)) {
            return false;
        }

        String mappedName = System.mapLibraryName(NATIVE_LIBRARY_BASE_NAME);
        String[] mappedNames = mappedName.endsWith(".dylib")
                ? new String[] { mappedName,
                        mappedName.substring(0, mappedName.length() - ".dylib".length()) + ".jnilib" }
                : new String[] { mappedName };

        String[] archCandidates = archCandidates(android);
        for (String arch : archCandidates) {
            for (String fileName : mappedNames) {
                String resourcePath = NATIVE_RESOURCE_ROOT + "/" + platform + "/" + arch + "/" + fileName;
                if (extractAndLoad(resourcePath, fileName)) {
                    return true;
                }
            }
        }

        for (String fileName : mappedNames) {
            if (extractAndLoad("/" + fileName, fileName)) {
                return true;
            }
        }

        return false;
    }

    private static boolean extractAndLoad(String resourcePath, String fileName) {
        try (InputStream in = ObfRuntime.class.getResourceAsStream(resourcePath)) {
            if (in == null) {
                return false;
            }

            Path tempDir = Files.createTempDirectory("core_engine_");
            Path libPath = tempDir.resolve(fileName);
            Files.copy(in, libPath, StandardCopyOption.REPLACE_EXISTING);
            libPath.toFile().deleteOnExit();
            tempDir.toFile().deleteOnExit();
            System.load(libPath.toAbsolutePath().toString());
            nativeVmLoaded = true;
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    private static String detectPlatform(boolean android) {
        if (android) {
            return "android";
        }
        String os = System.getProperty("os.name", "unknown").toLowerCase(Locale.ROOT);
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

    private static String[] archCandidates(boolean android) {
        String normalized = normalizeArch(System.getProperty("os.arch", "unknown"));
        if (android) {
            return switch (normalized) {
                case "arm64" -> new String[] { "arm64-v8a", "arm64", "aarch64" };
                case "armv7" -> new String[] { "armeabi-v7a", "armv7", "arm" };
                case "x86_64" -> new String[] { "x86_64", "amd64" };
                case "x86" -> new String[] { "x86", "i686" };
                default -> new String[] { normalized };
            };
        }

        return switch (normalized) {
            case "arm64" -> new String[] { "arm64", "aarch64" };
            case "x86_64" -> new String[] { "x86_64", "amd64" };
            case "x86" -> new String[] { "x86", "i686" };
            case "armv7" -> new String[] { "armv7", "arm" };
            default -> new String[] { normalized };
        };
    }

    private static String normalizeArch(String arch) {
        String value = arch == null ? "unknown" : arch.toLowerCase(Locale.ROOT);
        return switch (value) {
            case "aarch64", "arm64", "arm64e" -> "arm64";
            case "armv7", "armv7l", "armeabi-v7a" -> "armv7";
            case "x86_64", "amd64" -> "x86_64";
            case "x86", "i386", "i486", "i586", "i686" -> "x86";
            default -> value;
        };
    }

    private static boolean isAndroidRuntime() {
        String vmName = System.getProperty("java.vm.name", "").toLowerCase(Locale.ROOT);
        String runtimeName = System.getProperty("java.runtime.name", "").toLowerCase(Locale.ROOT);
        String vendor = System.getProperty("java.vendor", "").toLowerCase(Locale.ROOT);
        String osName = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        // MobilePlatform owns Android detection (launcher variables, ART by exact name, storage paths). A bare
        // vmName.contains("art") also matched unrelated desktop VM names.
        if (MobilePlatform.isAndroidRuntime()
                || vmName.contains("dalvik")
                || runtimeName.contains("android")
                || vendor.contains("android")
                || osName.contains("android")) {
            return true;
        }
        // PojavLauncher runs OpenJDK (not Dalvik/ART) on Android devices.
        // /system/build.prop exists on every Android device but never on desktop Linux.
        try {
            if (new java.io.File("/system/build.prop").exists()) return true;
        } catch (Throwable ignored) {}
        return false;
    }

    private static void maybeProbeDebugState(int seed) {
        if (!STRICT_INTEGRITY) {
            return;
        }
        long now = System.nanoTime();
        long last = lastDebugProbeNs;
        if (last != 0L && (now - last) < DEBUG_PROBE_INTERVAL_NS) {
            return;
        }
        lastDebugProbeNs = now;

        int score = probeDebugSignals();
        lastProbeScore = score;
        if (score <= 0) {
            return;
        }

        int mismatches = INTEGRITY_MISMATCHES.addAndGet(score);
        rawSink ^= vmInt(7, seed, score, rawSink ^ 0x6A09E667);
        if (mismatches > DEBUG_PROBE_STRIKE_LIMIT) {
            throw new IllegalStateException("Debug/runtime instrumentation detected");
        }
    }

    private static int probeDebugSignals() {
        int score = 0;
        String toolOptions = (System.getenv("JAVA_TOOL_OPTIONS") + " " + System.getenv("_JAVA_OPTIONS"))
                .toLowerCase(Locale.ROOT);
        // The same filter as the JVM arguments: launchers inject their own -javaagent through these variables too.
        if (containsAny(toolOptions, markersForThisRuntime())) {
            score += 6;
        }

        String vmInfo = System.getProperty("java.vm.info", "").toLowerCase(Locale.ROOT);
        if (containsAny(vmInfo, DEBUG_MARKERS)) {
            score += 3;
        }

        String attachSelf = System.getProperty("jdk.attach.allowAttachSelf", "false");
        if ("true".equalsIgnoreCase(attachSelf)) {
            score += 2;
        }

        String runtimeArgs = getRuntimeArgumentsSafe();
        if (!runtimeArgs.isBlank() && containsAny(runtimeArgs, markersForThisRuntime())) {
            score += 6;
        }

        // A thread name is a one-off signal: counting it on every probe would walk a launcher's own
        // diagnostic thread (for example a JFR recorder) into the strike limit after a couple of minutes.
        if (!debugThreadStruck && hasDebugThreadName()) {
            debugThreadStruck = true;
            score += 4;
        }
        return score;
    }

    /** Java-agent arguments are handled by the fatal JvmAgentGuard, not this soft-score probe. */
    private static String[] markersForThisRuntime() {
        String[] filtered = new String[DEBUG_MARKERS.length - 1];
        int n = 0;
        for (String marker : DEBUG_MARKERS) {
            if (!marker.equals("-javaagent")) filtered[n++] = marker;
        }
        return filtered;
    }

    /** Counters only, for diagnostics: how close the integrity checks are to throwing. */
    public static String describeIntegrity() {
        return "integrity strikes=" + INTEGRITY_MISMATCHES.get() + " (hard-fail above 12 call-graph / 64 probe), "
                + "last probe score=" + lastProbeScore + ", nativeVm=" + nativeVmLoaded
                + ", javaagent policy=fatal JvmAgentGuard";
    }

    private static volatile int lastProbeScore = 0;

    private static String getRuntimeArgumentsSafe() {
        try {
            Class<?> managementFactory = Class.forName("java.lang.management.ManagementFactory");
            Object runtimeMxBean = managementFactory.getMethod("getRuntimeMXBean").invoke(null);
            Object args = runtimeMxBean.getClass().getMethod("getInputArguments").invoke(runtimeMxBean);
            return String.valueOf(args).toLowerCase(Locale.ROOT);
        } catch (Throwable ignored) {
            return "";
        }
    }

    private static volatile boolean debugThreadStruck = false;

    /** Thread names only, via the root ThreadGroup: no stack capture, so the probe does not hitch the game. */
    private static boolean hasDebugThreadName() {
        try {
            ThreadGroup root = Thread.currentThread().getThreadGroup();
            while (root != null && root.getParent() != null) {
                root = root.getParent();
            }
            if (root == null) {
                return false;
            }
            Thread[] threads = new Thread[root.activeCount() + 16];
            int count = root.enumerate(threads, true);
            for (int i = 0; i < count; i++) {
                Thread thread = threads[i];
                if (thread == null) {
                    continue;
                }
                String name = thread.getName();
                if (name != null && containsAny(name.toLowerCase(Locale.ROOT), DEBUG_MARKERS)) {
                    return true;
                }
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    private static boolean containsAny(String value, String[] markers) {
        if (value == null || value.isBlank() || markers == null || markers.length == 0) {
            return false;
        }
        for (String marker : markers) {
            if (marker != null && !marker.isBlank() && value.contains(marker)) {
                return true;
            }
        }
        return false;
    }

    private static native int nativeVmInt(int opcode, int a, int b, int c);

    private static native long nativeVmLong(int opcode, long a, long b, long c);
}
