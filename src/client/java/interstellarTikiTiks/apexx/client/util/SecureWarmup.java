package interstellarTikiTiks.apexx.client.util;

import interstellarTikiTiks.apexx.client.auth.AuthConstants;
import java.io.IOException;
import java.io.InputStream;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.LockSupport;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

public final class SecureWarmup {
    private static final AtomicBoolean STARTED = new AtomicBoolean(false);
    private static final String[] CLASS_NAMES = new String[] {
            "interstellarTikiTiks.apexx.client.auth.AuthNetworking",
            "interstellarTikiTiks.apexx.client.auth.PayloadLoader",
            "interstellarTikiTiks.apexx.client.auth.SecureProofSupport",
            "interstellarTikiTiks.apexx.client.auth.decoy.AuthShadowLedger",
            "interstellarTikiTiks.apexx.client.auth.decoy.ModelRouteDecoy",
            "interstellarTikiTiks.apexx.client.config.hub.ConfigHubManager",
            "interstellarTikiTiks.apexx.client.util.SensitiveStrings",
            "interstellarTikiTiks.apexx.client.util.SecureRuntimeNames",
            "interstellarTikiTiks.apexx.client.util.SecureMetadataVault",
            "interstellarTikiTiks.apexx.client.util.ApexxClassLoader",
            "interstellarTikiTiks.apexx.client.module.implementation.combat.TriggerBot",
            "interstellarTikiTiks.apexx.client.module.implementation.combat.AutoShieldBreak",
            "interstellarTikiTiks.apexx.client.module.implementation.combat.AutoMace",
            "interstellarTikiTiks.apexx.client.rendering.font.MSDFFontRenderer",
            "interstellarTikiTiks.apexx.client.shader.CoreShaderManager",
            "interstellarTikiTiks.apexx.client.shader.PostShaderManager"
    };
    private static final int[] METADATA_IDS = new int[] {
            1, 4, 5, 6, 8, 9, 10, 16, 36, 45, 46, 47,
            82, 85, 88, 89, 112, 114, 115, 118, 119,
            121, 122, 127, 134, 139, 185, 205, 212,
            227, 231, 500, 501
    };
    private static final String[] STATIC_RESOURCE_NAMES = new String[] {
            "META-INF/apexx/secure-meta.bin",
            "META-INF/apexx/wm.bin",
            "META-INF/apexx/runtime-extra/wm.bin",
            "fabric.mod.json"
    };
    private static final String[] SKIP_CLASS_PREFIXES = new String[] {
            "META-INF/",
            "module-info",
            "net/",
            "org/",
            "com/google/",
            "com/mojang/",
            "io/",
            "javax/",
            "jakarta/",
            "kotlin/",
            "it/unimi/",
            "war/"
    };
    private static final String[] SKIP_CLASS_EXACT = new String[] {
            "interstellarTikiTiks/apexx/client/util/NativeBridge.class",
            "interstellarTikiTiks/apexx/client/util/ObfRuntime.class"
    };

    private SecureWarmup() {
    }

    public static void startBackgroundWarmup() {
        if (!STARTED.compareAndSet(false, true)) {
            return;
        }
        Thread worker = new Thread(SecureWarmup::runWarmup, "apexx-secure-warmup");
        worker.setDaemon(true);
        worker.setPriority(Thread.MIN_PRIORITY);
        worker.start();
    }

    private static void runWarmup() {
        try {
            

            Thread.sleep(700L);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }

        SecureMetadataVault.prefetch();
        AuthConstants.prewarmMetadata(METADATA_IDS);
        touchSecureRoutes();
        warmStaticResources();
        preloadClasses();
        preloadDiscoveredEntries();
    }

    private static void touchSecureRoutes() {
        try {
            SecureRuntimeNames.autoswordFeature();
            SecureRuntimeNames.modelGetPath();
            SecureRuntimeNames.modelManifestPath();
            SecureRuntimeNames.modelChunkPathPrefix();
            SecureRuntimeNames.headerAuthorization();
            SecureRuntimeNames.headerAuthProof();
            SecureRuntimeNames.headerDeviceId();
            SecureRuntimeNames.headerRequestNonce();
            SecureRuntimeNames.headerNextRequestNonce();
            SecureRuntimeNames.jsonSessionToken();
            SecureRuntimeNames.jsonAuthProof();
            SecureRuntimeNames.jsonRequestNonce();
            SecureRuntimeNames.jsonCapabilityFeature();
            SecureRuntimeNames.jsonCapabilityToken();
            SecureRuntimeNames.jsonChunks();
            SecureRuntimeNames.jsonKey();
        } catch (Throwable ignored) {
        }
    }

    private static void warmStaticResources() {
        for (String resourceName : STATIC_RESOURCE_NAMES) {
            warmResource(resourceName);
        }
    }

    private static void preloadClasses() {
        ClassLoader loader = SecureWarmup.class.getClassLoader();
        for (String className : CLASS_NAMES) {
            try {
                Class.forName(className, false, loader);
            } catch (Throwable ignored) {
            }
        }
    }

    private static void preloadDiscoveredEntries() {
        try {
            URL codeSource = SecureWarmup.class.getProtectionDomain().getCodeSource().getLocation();
            if (codeSource == null) {
                return;
            }
            Path sourcePath = Path.of(codeSource.toURI());
            if (Files.isDirectory(sourcePath)) {
                preloadDirectory(sourcePath);
            } else if (Files.isRegularFile(sourcePath)) {
                preloadJar(sourcePath);
            }
        } catch (IOException | URISyntaxException ignored) {
        }
    }

    private static void preloadDirectory(Path sourceRoot) throws IOException {
        List<Path> entries = new ArrayList<>();
        try (var stream = Files.walk(sourceRoot)) {
            stream.filter(Files::isRegularFile)
                    .sorted(Comparator.naturalOrder())
                    .forEach(entries::add);
        }
        int processed = 0;
        for (Path entry : entries) {
            String relative = sourceRoot.relativize(entry).toString().replace('\\', '/');
            if (relative.endsWith(".class") && shouldWarmClassEntry(relative)) {
                preloadClassEntry(relative);
                processed = throttle(processed);
            } else if (shouldWarmResourceEntry(relative)) {
                warmResource(relative);
                processed = throttle(processed);
            }
        }
    }

    private static void preloadJar(Path jarPath) throws IOException {
        try (JarFile jar = new JarFile(jarPath.toFile())) {
            Enumeration<JarEntry> entries = jar.entries();
            int processed = 0;
            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                if (entry.isDirectory()) {
                    continue;
                }
                String name = entry.getName();
                if (name.endsWith(".class") && shouldWarmClassEntry(name)) {
                    preloadClassEntry(name);
                    processed = throttle(processed);
                } else if (shouldWarmResourceEntry(name)) {
                    warmResource(name);
                    processed = throttle(processed);
                }
            }
        }
    }

    private static boolean shouldWarmClassEntry(String entryName) {
        if (!entryName.endsWith(".class")) {
            return false;
        }
        if (entryName.startsWith("interstellarTikiTiks/apexx/client/mixin/")) {
            return false;
        }
        for (String prefix : SKIP_CLASS_PREFIXES) {
            if (entryName.startsWith(prefix)) {
                return false;
            }
        }
        for (String exact : SKIP_CLASS_EXACT) {
            if (entryName.equals(exact) || entryName.startsWith(exact.substring(0, exact.length() - 6) + "$")) {
                return false;
            }
        }
        return true;
    }

    private static boolean shouldWarmResourceEntry(String entryName) {
        if (entryName == null || entryName.isBlank()) {
            return false;
        }
        if (entryName.startsWith("META-INF/apexx/")) {
            return true;
        }
        if (entryName.equals("fabric.mod.json")) {
            return true;
        }
        if (entryName.contains("/shaders/")) {
            return entryName.endsWith(".json")
                    || entryName.endsWith(".fsh")
                    || entryName.endsWith(".vsh")
                    || entryName.endsWith(".glsl")
                    || entryName.endsWith(".txt")
                    || entryName.endsWith(".bin");
        }
        return false;
    }

    private static void preloadClassEntry(String entryName) {
        String className = entryName.substring(0, entryName.length() - 6).replace('/', '.');
        try {
            Class.forName(className, false, SecureWarmup.class.getClassLoader());
        } catch (Throwable ignored) {
        }
    }

    private static void warmResource(String entryName) {
        try (InputStream in = SecureWarmup.class.getClassLoader().getResourceAsStream(entryName)) {
            if (in == null) {
                return;
            }
            byte[] buffer = new byte[4096];
            int remaining = 65536;
            while (remaining > 0) {
                int read = in.read(buffer, 0, Math.min(buffer.length, remaining));
                if (read < 0) {
                    break;
                }
                remaining -= read;
            }
        } catch (Throwable ignored) {
        }
    }

    private static int throttle(int processed) {
        int next = processed + 1;
        if ((next & 31) == 0) {
            LockSupport.parkNanos(2_000_000L);
        }
        return next;
    }
}
