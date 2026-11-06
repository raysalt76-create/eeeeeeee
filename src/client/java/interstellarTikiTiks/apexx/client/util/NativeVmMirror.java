package interstellarTikiTiks.apexx.client.util;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Locale;

public final class NativeVmMirror {
    private static final String LIB_BASE = "apexx_vm";
    private static final String RESOURCE_ROOT = "/apexx/native";
    private static volatile boolean loaded = tryLoad();

    private NativeVmMirror() {
    }

    private static native int nativeMirrorVmInt(int opcode, int a, int b, int c);

    private static native long nativeMirrorVmLong(int opcode, long a, long b, long c);

    public static boolean isLoaded() {
        return loaded;
    }

    public static int vmInt(int opcode, int a, int b, int c) {
        if (!loaded) {
            return 0;
        }
        try {
            return nativeMirrorVmInt(opcode, a, b, c);
        } catch (Throwable ignored) {
            loaded = false;
            return 0;
        }
    }

    public static long vmLong(int opcode, long a, long b, long c) {
        if (!loaded) {
            return 0L;
        }
        try {
            return nativeMirrorVmLong(opcode, a, b, c);
        } catch (Throwable ignored) {
            loaded = false;
            return 0L;
        }
    }

    private static boolean tryLoad() {
        String platform = detectPlatform();
        if ("unknown".equals(platform)) {
            return false;
        }
        String mapped = System.mapLibraryName(LIB_BASE);
        String[] names = mapped.endsWith(".dylib")
                ? new String[]{mapped, mapped.substring(0, mapped.length() - ".dylib".length()) + ".jnilib"}
                : new String[]{mapped};
        for (String arch : archCandidates()) {
            for (String fileName : names) {
                String resource = RESOURCE_ROOT + "/" + platform + "/" + arch + "/" + fileName;
                if (extractAndLoad(resource, fileName)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean extractAndLoad(String resourcePath, String fileName) {
        try (InputStream in = NativeVmMirror.class.getResourceAsStream(resourcePath)) {
            if (in == null) {
                return false;
            }
            Path baseDir;
            try {
                baseDir = net.fabricmc.loader.api.FabricLoader.getInstance().getConfigDir().resolve("apexx").resolve("natives");
            } catch (Throwable t) {
                baseDir = Path.of(System.getProperty("user.home", ".")).resolve(".minecraft").resolve("config").resolve("apexx").resolve("natives");
            }
            Files.createDirectories(baseDir);
            Path libPath = baseDir.resolve(fileName);
            Files.copy(in, libPath, StandardCopyOption.REPLACE_EXISTING);
            System.load(libPath.toAbsolutePath().toString());
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static String detectPlatform() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
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

    private static String[] archCandidates() {
        String arch = normalizeArch(System.getProperty("os.arch", ""));
        return switch (arch) {
            case "arm64" -> new String[]{"arm64", "aarch64"};
            case "x86_64" -> new String[]{"x86_64", "amd64"};
            default -> new String[]{arch};
        };
    }

    private static String normalizeArch(String arch) {
        String normalized = arch == null ? "" : arch.toLowerCase(Locale.ROOT);
        if (normalized.contains("aarch64") || normalized.contains("arm64")) {
            return "arm64";
        }
        if (normalized.contains("64")) {
            return "x86_64";
        }
        if (normalized.contains("86")) {
            return "x86";
        }
        return normalized.isBlank() ? "unknown" : normalized;
    }
}
