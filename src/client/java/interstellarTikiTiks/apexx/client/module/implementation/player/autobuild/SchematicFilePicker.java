package interstellarTikiTiks.apexx.client.module.implementation.player.autobuild;

import interstellarTikiTiks.apexx.client.util.MobilePlatform;
import net.minecraft.client.MinecraftClient;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.util.tinyfd.TinyFileDialogs;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Native "open file" dialog for schematics. Minecraft runs with {@code java.awt.headless=true}, so a
 * Swing chooser cannot open inside the game; the LWJGL tinyfd binding that ships with the game is used
 * instead. On Windows and Linux the dialog runs on a daemon thread so the game keeps rendering; on
 * macOS native windows must be opened from the main thread, so it runs inline there. Results are
 * always delivered on the client thread. Files are only read where they are, never copied.
 */
public final class SchematicFilePicker {

    private static final AtomicBoolean OPEN = new AtomicBoolean();
    /** When the current dialog opened; a dialog stuck longer than PICKER_TIMEOUT_MS no longer blocks a new one. */
    private static volatile long openedAt;
    private static final long PICKER_TIMEOUT_MS = 120_000L;
    private static final String[] PATTERNS = {"*.litematic", "*.schem", "*.schematic", "*.nbt"};

    private SchematicFilePicker() {
    }

    /**
     * Opens the picker. {@code onPicked} receives the chosen existing files (possibly several) on the
     * client thread; {@code onUnavailable} receives a user-facing reason when no dialog can be shown.
     * Nothing happens while a picker is already open.
     */
    public static void open(MinecraftClient mc, Path startDir, Consumer<List<Path>> onPicked,
                            Consumer<String> onUnavailable) {
        if (mc == null) return;
        if (MobilePlatform.isMobile()) {
            onUnavailable.accept("No file picker on this platform: drop the file onto the ClickGUI, "
                    + "or type its full path into Schematic Path.");
            return;
        }
        if (!OPEN.compareAndSet(false, true)) {
            // A native dialog that never returns (no zenity/kdialog, a hung portal) must not lock the picker forever.
            if (System.currentTimeMillis() - openedAt < PICKER_TIMEOUT_MS) return;
        }
        openedAt = System.currentTimeMillis();
        String start = startDir == null ? "" : startDir.toAbsolutePath() + java.io.File.separator;
        Runnable job = () -> {
            List<Path> picked = List.of();
            String failure = null;
            try {
                picked = show(start);
            } catch (Throwable t) {
                failure = "The file picker could not open (" + t.getClass().getSimpleName()
                        + "). Drop the file onto the ClickGUI or type its path into Schematic Path.";
            } finally {
                OPEN.set(false);
            }
            List<Path> result = picked;
            String reason = failure;
            mc.execute(() -> {
                if (reason != null) onUnavailable.accept(reason);
                else if (!result.isEmpty()) onPicked.accept(result);
            });
        };
        if (isMac()) {
            job.run();
            return;
        }
        try {
            Thread worker = new Thread(job, "Apexx-AutoBuild-Picker");
            worker.setDaemon(true);
            worker.start();
        } catch (Throwable t) {
            OPEN.set(false);
            onUnavailable.accept("The file picker could not start; type the path into Schematic Path.");
        }
    }

    private static List<Path> show(String start) {
        String raw;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            PointerBuffer filters = stack.mallocPointer(PATTERNS.length);
            for (String pattern : PATTERNS) {
                filters.put(stack.UTF8(pattern));
            }
            filters.flip();
            raw = TinyFileDialogs.tinyfd_openFileDialog("Select a schematic", start, filters,
                    "Schematics (*.litematic, *.schem, *.schematic, *.nbt)", true);
        }
        return parse(raw);
    }

    /** Splits tinyfd's {@code |}-separated multi-selection into existing schematic files. */
    static List<Path> parse(String raw) {
        if (raw == null || raw.isBlank()) return List.of();
        List<Path> out = new ArrayList<>();
        for (String part : raw.split("\\|")) {
            String trimmed = part.trim();
            if (trimmed.isEmpty()) continue;
            try {
                Path p = Path.of(trimmed).toAbsolutePath().normalize();
                if (Files.isRegularFile(p) && SchematicLibrary.isSchematic(p) && !out.contains(p)) out.add(p);
            } catch (RuntimeException ignored) {
                // an unparsable entry is skipped
            }
        }
        return out;
    }

    private static boolean isMac() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("mac");
    }
}
