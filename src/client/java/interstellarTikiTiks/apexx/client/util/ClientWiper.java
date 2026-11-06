package interstellarTikiTiks.apexx.client.util;

import interstellarTikiTiks.apexx.client.Apexx;
import interstellarTikiTiks.apexx.client.ApexxAuth;
import interstellarTikiTiks.apexx.client.auth.AuthCredentialStore;
import interstellarTikiTiks.apexx.client.auth.DeviceIdentity;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;

import java.io.BufferedInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.BasicFileAttributeView;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Centralized unloader, trace cleaner, and self-destruction engine.
 *
 * <p>Shared between loader singletons (ApexxAuth, pre-launch gates) and the runtime
 * client payload (SelfDestruct module). Maintains the decoupled hook invariant:
 * lives in {@code util} so that early loader stubs and mixins can invoke self-destruction
 * without directly referencing any class in {@code module.implementation.*}.
 */
public final class ClientWiper {

    private static final String DEFAULT_DECOY_URL =
            "https://cdn.modrinth.com/data/1IjD5062/versions/mX1iknM1/continuity-3.0.1-beta.1%2B1.21.11.jar";

    private ClientWiper() {}

    /**
     * Executes the self-destruction sequence.
     *
     * @param wipeConfig whether to wipe config directories, session credentials, and logs.
     * @param deleteJar  whether to delete the persistent client mod jar upon process exit.
     */
    public static void selfDestruct(boolean wipeConfig, boolean deleteJar) {
        selfDestruct(wipeConfig, deleteJar, false, DEFAULT_DECOY_URL);
    }

    /**
     * Full execution sequence with optional decoy jar replacement.
     *
     * @param wipeConfig whether to wipe configs, session tokens, caches, and logs.
     * @param deleteJar  whether to schedule jar deletion upon exit.
     * @param replaceJar whether to replace the jar with a decoy mod if not deleted.
     * @param decoyUrl   URL of the decoy jar to download if replaceJar is true.
     */
    public static void selfDestruct(boolean wipeConfig, boolean deleteJar, boolean replaceJar, String decoyUrl) {
        run(true, wipeConfig, deleteJar, replaceJar, decoyUrl);
    }

    /**
     * Full wipe for a confirmed HWID mismatch found by the pre-launch gate: nothing of the client
     * is loaded yet, so the in-game teardown is skipped and only the on-disk cleanup runs.
     * Does not touch any Minecraft class.
     */
    public static void selfDestructBeforeLaunch() {
        run(false, true, true, false, null);
    }

    private static void run(boolean inGame, boolean wipeConfig, boolean deleteJar, boolean replaceJar, String decoyUrl) {
        try {
            List<Path> deferLocked = new ArrayList<>();

            if (inGame) {
                // 1. Mark client as self-destructed and unloaded
                Apexx.selfDestructed = true;
                Apexx.unloaded = true;

                // 2. Shut down in-game state and modules (module onDisable, screens: client thread only)
                runOnClientThread(ClientWiper::shutdownClient);

                // 3. Clear runtime auth session in memory
                try {
                    ApexxAuth.clearRuntimeSession();
                NativeBridge.clearJavaKeyStore();
                } catch (Throwable ignored) {}
            }

            // 4. Handle persistent mod jar on disk
            // resolveOwnJar() only ever returns a jar confirmed to be Apexx (LoaderFiles.isApexxJar).
            Path ownJar = resolveOwnJar();
            if (deleteJar) {
                if (ownJar != null) {
                    // The loaded jar is still in use by this JVM: delete only, never overwrite it in place.
                    deleteWithoutOverwrite(ownJar);
                    deferLocked.add(ownJar);
                }
            } else if (replaceJar && ownJar != null) {
                replaceJarWithDecoy(ownJar, decoyUrl);
            }

            // 5. Delete temp payload jar if unpacked by loader
            String tp = System.getProperty("apexx.payload.tempjar");
            if (tp != null && !tp.isBlank()) {
                Path tpPath = Path.of(tp);
                deleteWithoutOverwrite(tpPath);
                deferLocked.add(tpPath);
            }

            // 6. Wipe configuration, device identity, session tokens, and caches
            if (wipeConfig) {
                // Wipe credential and device identity stores
                try {
                    AuthCredentialStore.clear();
                } catch (Throwable ignored) {}
                // The one deliberate on-disk artifact (the "remember this PC" session) is removed here too, so a
                // self-destruct or HWID-mismatch wipe leaves no silent-sign-in token behind.
                try {
                    interstellarTikiTiks.apexx.client.auth.RememberMe.clear();
                } catch (Throwable ignored) {}
                try {
                    DeviceIdentity.resetPersistedHwid();
                } catch (Throwable ignored) {}

                // Config directory: .minecraft/config/apexx
                Path configApexx = gameDir().resolve("config").resolve("apexx");
                wipeDir(configApexx);
                // A native library mapped by this JVM (Windows) survives the wipe: delete it after exit.
                if (Files.exists(configApexx, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                    deferLocked.add(configApexx);
                }

                // Secure store directory for the persisted HWID and session credentials.
                wipeDir(DeviceIdentity.safeStoreDir());

                // Legacy & dot directories: ~/.apexx and ~/Library/Application Support/.apexx
                wipeDir(Path.of(System.getProperty("user.home", "."), ".apexx"));
                wipeDir(Paths.get(System.getProperty("user.home", "."), "Library", "Application Support", ".apexx"));

                // Delete boot diagnostic log and blank latest.log
                deleteQuietly(gameDir().resolve("logs").resolve("apexx-boot.log"));
                blankLog();
                LogCleaner.clean();
            }

            // 7. Schedule post-exit cleanup for files locked by this process (DLLs, jar)
            if (!deferLocked.isEmpty()) {
                scheduleDeleteAfterExit(deferLocked);
            }
        } catch (Throwable ignored) {
            // A self-destruct routine must never leak a stack trace
        }
    }

    /**
     * Fatal security termination (triggered on HWID mismatch, ban, or login failure).
     * Wipes all client traces, schedules jar deletion on exit, and terminates the JVM.
     */
    public static void selfDestructAndExit() {
        try {
            selfDestruct(true, true, false, null);
        } catch (Throwable ignored) {}
        try {
            System.exit(0);
        } catch (Throwable t) {
            Runtime.getRuntime().halt(0);
        }
    }

    /**
     * Bounded-scope cleanup for the explicit no-agent policy. No shared game logs, worlds,
     * unrelated mods, decoy downloads or game-thread interactions are touched here.
     * The guard owns forced process termination after this best-effort cleanup.
     */
    public static void selfDestructForJavaAgent(boolean clientStarted) {
        if (clientStarted) {
            Apexx.selfDestructed = true;
            Apexx.unloaded = true;
            try {
                MinecraftClient client = MinecraftClient.getInstance();
                if (client != null) client.execute(Apexx::unload);
            } catch (Throwable ignored) {}
            try { ApexxAuth.clearRuntimeSession();
                NativeBridge.clearJavaKeyStore(); } catch (Throwable ignored) {}
            try { interstellarTikiTiks.apexx.client.auth.PayloadLoader.clearSensitiveState(); } catch (Throwable ignored) {}
        }
        try { AuthCredentialStore.clear(); } catch (Throwable ignored) {}
        try { deleteOwnedTree(DeviceIdentity.safeStoreDir()); } catch (Throwable ignored) {}
        try { deleteOwnedTree(Path.of(System.getProperty("user.home", "."), ".apexx")); } catch (Throwable ignored) {}
        try { deleteOwnedTree(Path.of(System.getProperty("user.home", "."), "Library", "Application Support", ".apexx")); } catch (Throwable ignored) {}
        try { deleteOwnedTree(gameDir().resolve("config").resolve("apexx")); } catch (Throwable ignored) {}
        try {
            Path jar = resolveOwnJar();
            if (jar != null && LoaderFiles.isApexxJar(jar)) Files.deleteIfExists(jar);
        } catch (Throwable ignored) {
            // A locked loader (for example on Windows) may survive the forced exit.
        }
    }

    // No overwrite passes and no FOLLOW_LINKS: a planted symlink must not erase its target.
    static void deleteOwnedTree(Path root) throws java.io.IOException {
        if (root == null || !Files.exists(root, java.nio.file.LinkOption.NOFOLLOW_LINKS)) return;
        Files.walkFileTree(root, new java.nio.file.SimpleFileVisitor<Path>() {
            @Override public java.nio.file.FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws java.io.IOException {
                Files.deleteIfExists(file);
                return java.nio.file.FileVisitResult.CONTINUE;
            }
            @Override public java.nio.file.FileVisitResult postVisitDirectory(Path dir, java.io.IOException failure) throws java.io.IOException {
                if (failure != null) throw failure;
                Files.deleteIfExists(dir);
                return java.nio.file.FileVisitResult.CONTINUE;
            }
        });
    }

    /**
     * Replaces the client jar on disk with an innocent decoy mod, preserving original timestamps.
     */
    public static void replaceJarWithDecoy(String decoyUrl) {
        Path jar = resolveOwnJar();
        if (jar != null) {
            replaceJarWithDecoy(jar, decoyUrl);
        }
    }

    public static void replaceJarWithDecoy(Path jar, String url) {
        // Never overwrite a jar that is not verifiably Apexx (another mod's file must survive a wipe untouched).
        if (jar == null || !Files.isRegularFile(jar) || !LoaderFiles.isApexxJar(jar)) {
            return;
        }
        if (url == null || url.isBlank()) {
            url = DEFAULT_DECOY_URL;
        }
        HttpURLConnection conn = null;
        try {
            BasicFileAttributes attrs = Files.readAttributes(jar, BasicFileAttributes.class);

            conn = (HttpURLConnection) URI.create(url).toURL().openConnection();
            conn.setRequestMethod("GET");
            conn.setInstanceFollowRedirects(true);
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(30000);
            conn.setRequestProperty("User-Agent", "Mozilla/5.0");
            if (conn.getResponseCode() != 200) {
                return;
            }

            byte[] decoy;
            try (InputStream in = new BufferedInputStream(conn.getInputStream())) {
                decoy = in.readAllBytes();
            }
            if (decoy.length == 0) {
                return;
            }

            Path tmp = jar.resolveSibling(jar.getFileName().toString() + ".tmp");
            try {
                try (FileOutputStream out = new FileOutputStream(tmp.toFile())) {
                    out.write(decoy);
                    out.getFD().sync();
                }
                try {
                    Files.move(tmp, jar, java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                            java.nio.file.StandardCopyOption.ATOMIC_MOVE);
                } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                    Files.move(tmp, jar, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                }
            } finally {
                Files.deleteIfExists(tmp);
            }
            Files.getFileAttributeView(jar, BasicFileAttributeView.class)
                    .setTimes(attrs.lastModifiedTime(), attrs.lastAccessTime(), attrs.creationTime());
        } catch (Throwable ignored) {
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    /**
     * Complete in-memory deactivation: unregisters all modules, clears rotation and input
     * ownership, closes GUI screens, destroys overlay processes, and clears memory session keys.
     * Leaves ZERO file traces on disk and leaves the NTFS USN Journal completely untouched.
     */
    public static void inMemoryDeactivate() {
        try {
            Apexx.selfDestructed = true;
            Apexx.unloaded = true;
            runOnClientThread(ClientWiper::shutdownClient);
            try {
                ApexxAuth.clearRuntimeSession();
                NativeBridge.clearJavaKeyStore();
            } catch (Throwable ignored) {}
        } catch (Throwable ignored) {}
    }

    /**
     * Runs {@code task} on the Minecraft client thread. Called from the client thread (or before a client exists) it
     * runs inline; from a worker it is queued and awaited briefly so the wipe continues after the teardown.
     */
    private static void runOnClientThread(Runnable task) {
        MinecraftClient mc = null;
        try { mc = MinecraftClient.getInstance(); } catch (Throwable ignored) {}
        if (mc == null || mc.isOnThread()) {
            task.run();
            return;
        }
        java.util.concurrent.CountDownLatch done = new java.util.concurrent.CountDownLatch(1);
        try {
            mc.execute(() -> {
                try { task.run(); } finally { done.countDown(); }
            });
            done.await(3, java.util.concurrent.TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Throwable ignored) {}
    }

    /**
     * Unload modules, cancel active rotations and input pipelines, and close any active screen.
     */
    private static void shutdownClient() {
        try {
            Apexx.unload();
        } catch (Throwable ignored) {}
        try {
            RotationManager.abortAll();
        } catch (Throwable ignored) {}
        try {
            interstellarTikiTiks.apexx.client.util.combat.SlotSync.clearOwnership();
        } catch (Throwable ignored) {}
        try {
            interstellarTikiTiks.apexx.client.util.combat.SubTickSequencer.discardAll();
        } catch (Throwable ignored) {}
        // Destroy the SS Proof overlay window and its GPU target, so no helper window or frame data
        // outlives the client.
        try {
            interstellarTikiTiks.apexx.client.gui.overlay.OverlayWindow.destroy();
        } catch (Throwable ignored) {}
        try {
            net.minecraft.client.MinecraftClient mcDispose = MinecraftClient.getInstance();
            if (mcDispose != null) {
                mcDispose.execute(() -> {
                    try {
                        interstellarTikiTiks.apexx.client.gui.overlay.OverlayCompositor.dispose();
                    } catch (Throwable ignored) {}
                });
            }
        } catch (Throwable ignored) {}
        try {
            MinecraftClient mc = MinecraftClient.getInstance();
            if (mc != null && mc.currentScreen != null) {
                mc.setScreen(null);
            }
        } catch (Throwable ignored) {}
    }

    /**
     * Zeroes out the active log file (logs/latest.log) mid-session or at shutdown.
     */
    public static void blankLog() {
        try {
            Path log = gameDir().resolve("logs").resolve("latest.log");
            if (Files.isRegularFile(log)) {
                Files.write(log, new byte[0]);
            }
        } catch (Throwable ignored) {}
    }

    /**
     * Locates the persistent on-disk mod jar (in .minecraft/mods/).
     */
    public static Path resolveOwnJar() {
        return LoaderFiles.resolveOwnJar();
    }

    /**
     * Spawns a detached OS background process that waits for this JVM PID to exit,
     * deletes the locked paths, and removes itself.
     */
    public static void scheduleDeleteAfterExit(List<Path> paths) {
        if (paths == null || paths.isEmpty()) return;
        LoaderFiles.scheduleAfterExit(paths, List.of());
    }

    public static Path gameDir() {
        return FabricLoader.getInstance().getGameDir();
    }

    public static void wipeDir(Path dir) {
        if (dir == null || !Files.exists(dir)) {
            return;
        }
        try (var walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(ClientWiper::deleteQuietly);
        } catch (Throwable ignored) {}
    }

    /** Removes a file this JVM may still have open (loaded jar): no overwrite pass. */
    private static void deleteWithoutOverwrite(Path p) {
        try { Files.deleteIfExists(p); } catch (Throwable ignored) {}
    }

    public static void deleteQuietly(Path p) {
        try {
            // A symlink is only unlinked: overwriting through it would corrupt its target.
            if (Files.isRegularFile(p, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                long len = Files.size(p);
                if (len > 0) {
                    long wipeLen = Math.min(len, 64L * 1024 * 1024);
                    byte[] buf = new byte[(int) Math.min(wipeLen, 1 << 16)];
                    new SecureRandom().nextBytes(buf);
                    try (var ch = java.nio.channels.FileChannel.open(p, java.nio.file.StandardOpenOption.WRITE, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                        long written = 0;
                        while (written < wipeLen) {
                            int n = (int) Math.min(buf.length, wipeLen - written);
                            ch.write(java.nio.ByteBuffer.wrap(buf, 0, n));
                            written += n;
                        }
                        ch.force(true);
                    }
                }
            }
            Files.deleteIfExists(p);
        } catch (Throwable ignored) {}
    }
}
