package interstellarTikiTiks.apexx.client.util;

import net.fabricmc.loader.api.FabricLoader;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipFile;

/**
 * File operations on the loader jar itself: finding it, and changing it after this JVM has exited (the running
 * process keeps its own jar open, which Windows does not let anyone delete or replace).
 *
 * <p>Touches only the JDK and Fabric Loader — never a Minecraft class — so it is safe from {@code preLaunch},
 * before mixins are applied. {@link ClientWiper} and the forced loader update both use it.
 */
public final class LoaderFiles {
    /**
     * Mod ids the loader itself has shipped under, used only when the code source is not a plain jar. Real mods'
     * ids ("dynamic_fps", "clumps", ...) must never appear here. Every candidate is still confirmed to be an Apexx
     * jar by {@link #isApexxJar} before it is returned.
     */
    private static final String[] OWN_MOD_IDS = {"combat-hitboxes", "ferritecore_boot", "dynamic_fps_boot"};
    /** Present in every Apexx loader build ({@code BuildInfo.RESOURCE}). */
    private static final String BUILD_RESOURCE = "META-INF/apexx/build.json";
    /** The pre-launch entrypoint; never renamed because fabric.mod.json names it. */
    private static final String LAUNCH_GATE_ENTRY = "interstellarTikiTiks/apexx/client/auth/LaunchGate.class";
    /** How long the helper keeps retrying a delete after the JVM is gone (antivirus / indexers can hold a file briefly). */
    private static final int DELETE_RETRY_SECONDS = 30;

    private LoaderFiles() {}

    /**
     * The persistent on-disk loader jar (in .minecraft/mods/), or null when it is not a plain file (a dev runtime)
     * or cannot be confirmed to be an Apexx jar. The jar this class was loaded from is asked first; the loader's own
     * mod container is only a fallback. A non-null result has always passed {@link #isApexxJar}, so a caller that
     * deletes or replaces it can never touch another mod's file.
     */
    public static Path resolveOwnJar() {
        try {
            var cs = LoaderFiles.class.getProtectionDomain().getCodeSource();
            if (cs != null && cs.getLocation() != null && "file".equalsIgnoreCase(cs.getLocation().getProtocol())) {
                Path p = Path.of(cs.getLocation().toURI());
                if (Files.isRegularFile(p) && isApexxJar(p)) return p;
            }
        } catch (Throwable ignored) {
            // Not a file URL (dev classes, nested jar): fall back to the mod container.
        }
        for (String id : OWN_MOD_IDS) {
            try {
                Path p = FabricLoader.getInstance().getModContainer(id)
                        .map(mc -> mc.getOrigin().getPaths())
                        .filter(paths -> !paths.isEmpty())
                        .map(paths -> paths.get(0))
                        .filter(Files::isRegularFile)
                        .orElse(null);
                if (p != null && isApexxJar(p)) return p;
            } catch (Throwable ignored) {
                // A mod container whose origin is not a path (nested jar, dev classes): try the next id.
            }
        }
        return null;
    }

    /** True only for a jar that carries the Apexx build file or the Apexx launch gate, never for another mod. */
    public static boolean isApexxJar(Path jar) {
        if (jar == null) return false;
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            return zip.getEntry(BUILD_RESOURCE) != null || zip.getEntry(LAUNCH_GATE_ENTRY) != null;
        } catch (Throwable notAZip) {
            return false;
        }
    }

    /**
     * Spawns a detached helper that waits for this JVM to exit, then deletes {@code deletes} (files or directories,
     * each retried for a while) and — only if every one of them is really gone — performs {@code moves} in order
     * ({@code {from, to}}, replacing {@code to}). Finally the helper removes its own script. Never throws.
     *
     * <p>The all-deleted gate is what makes a loader swap safe: the new jar is staged under a name Fabric does not
     * load, and is only renamed into place once the old loader has gone, so two loaders never sit in mods/ together.
     *
     * @return true when the helper was started
     */
    public static boolean scheduleAfterExit(List<Path> deletes, List<Path[]> moves) {
        List<Path> del = deletes == null ? List.of() : deletes;
        List<Path[]> mv = moves == null ? List.of() : moves;
        if (del.isEmpty() && mv.isEmpty()) return false;
        // Android launchers can kill the game's whole process group and ship mksh/toybox instead of a desktop shell:
        // only the in-process file operations are used there.
        if (MobilePlatform.isAndroidRuntime()) return false;
        try {
            long pid = ProcessHandle.current().pid();
            boolean win = System.getProperty("os.name", "").toLowerCase().contains("win");
            Path script = Files.createTempFile("winupd", win ? ".bat" : ".sh");
            if (win) {
                Files.writeString(script, windowsScript(pid, del, mv));
                new ProcessBuilder("cmd", "/c", "start", "/min", "", "cmd", "/c", script.toAbsolutePath().toString()).start();
            } else {
                Files.writeString(script, posixScript(pid, del, mv));
                ProcessBuilder helper = new ProcessBuilder("sh", "-c",
                        "nohup sh " + sq(script.toAbsolutePath().toString()) + " >/dev/null 2>&1 &");
                // The game's native-library environment must not leak into sh/rm/mv: a preloaded or mismatched
                // library can crash the helper before it does anything.
                helper.environment().remove("LD_LIBRARY_PATH");
                helper.environment().remove("LD_PRELOAD");
                helper.start();
            }
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    static String windowsScript(long pid, List<Path> deletes, List<Path[]> moves) {
        StringBuilder sb = new StringBuilder();
        sb.append("@echo off\r\n");
        sb.append(":w\r\n");
        // tasklist pads the PID with spaces; matching " pid " avoids a false hit on a longer PID that contains it.
        sb.append("tasklist /FI \"PID eq ").append(pid).append("\" /NH 2>nul | find \" ").append(pid).append(" \" >nul\r\n");
        sb.append("if not errorlevel 1 ( ping -n 2 127.0.0.1 >nul & goto w )\r\n");
        sb.append("set f=0\r\n");
        for (int i = 0; i < deletes.size(); i++) {
            String s = bq(deletes.get(i));
            sb.append("set n=0\r\n");
            sb.append(":d").append(i).append("\r\n");
            sb.append("rd /s /q \"").append(s).append("\" 2>nul\r\n");
            sb.append("del /f /q \"").append(s).append("\" 2>nul\r\n");
            sb.append("if not exist \"").append(s).append("\" goto e").append(i).append("\r\n");
            sb.append("set /a n=n+1\r\n");
            sb.append("if %n% GEQ ").append(DELETE_RETRY_SECONDS).append(" ( set f=1 & goto e").append(i).append(" )\r\n");
            sb.append("ping -n 2 127.0.0.1 >nul\r\n");
            sb.append("goto d").append(i).append("\r\n");
            sb.append(":e").append(i).append("\r\n");
        }
        // A path that would not go away vetoes every move (see the method contract).
        sb.append("if \"%f%\"==\"1\" goto x\r\n");
        for (Path[] move : moves) {
            sb.append("move /y \"").append(bq(move[0])).append("\" \"").append(bq(move[1])).append("\" >nul 2>nul\r\n");
        }
        sb.append(":x\r\n");
        sb.append("del /f /q \"%~f0\"\r\n");
        return sb.toString();
    }

    static String posixScript(long pid, List<Path> deletes, List<Path[]> moves) {
        StringBuilder sb = new StringBuilder();
        sb.append("#!/bin/sh\n");
        sb.append("while kill -0 ").append(pid).append(" 2>/dev/null; do sleep 1; done\n");
        sb.append("ok=1\n");
        for (Path p : deletes) {
            String s = sq(p.toAbsolutePath().toString());
            sb.append("n=0; while [ -e ").append(s).append(" ] || [ -L ").append(s).append(" ]; do rm -rf ").append(s)
                    .append(" 2>/dev/null; [ -e ").append(s).append(" ] || break; n=$((n+1)); [ $n -ge ")
                    .append(DELETE_RETRY_SECONDS).append(" ] && { ok=0; break; }; sleep 1; done\n");
        }
        if (!moves.isEmpty()) {
            sb.append("if [ $ok = 1 ]; then\n");
            for (Path[] move : moves) {
                sb.append("  mv -f ").append(sq(move[0].toAbsolutePath().toString())).append(' ')
                        .append(sq(move[1].toAbsolutePath().toString())).append(" 2>/dev/null\n");
            }
            sb.append("fi\n");
        }
        sb.append("rm -f \"$0\"\n");
        return sb.toString();
    }

    /** A path for a double-quoted batch argument: {@code %} would otherwise expand as a variable. */
    private static String bq(Path p) {
        return p.toAbsolutePath().toString().replace("%", "%%");
    }

    /** POSIX single-quoted literal. */
    private static String sq(String s) {
        return "'" + s.replace("'", "'\\''") + "'";
    }
}
