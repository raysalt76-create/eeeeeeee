package interstellarTikiTiks.apexx.client.module.implementation.player.autobuild;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Stream;

/** Lists schematic files from the usual folders (Litematica's {@code schematics/}, a config copy). */
public final class SchematicLibrary {

    public static final List<String> EXTENSIONS = List.of(".litematic", ".schem", ".schematic", ".nbt");
    private static final int MAX_DEPTH = 4;
    private static final int MAX_FILES = 2000;

    private SchematicLibrary() {
    }

    /** Folders searched under a game directory, in priority order. */
    public static List<Path> defaultFolders(Path runDir) {
        List<Path> out = new ArrayList<>();
        if (runDir != null) {
            out.add(runDir.resolve("schematics"));
            out.add(runDir.resolve("config").resolve("schematics"));
        }
        return out;
    }

    public static boolean isSchematic(Path file) {
        if (file == null || file.getFileName() == null) return false;
        String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
        for (String ext : EXTENSIONS) {
            if (name.endsWith(ext)) return true;
        }
        return false;
    }

    /** Every schematic file under the given folders (missing folders are skipped), sorted by name. */
    public static List<Path> list(List<Path> folders) {
        Set<Path> found = new LinkedHashSet<>();
        for (Path folder : folders) {
            if (folder == null || !Files.isDirectory(folder)) continue;
            try (Stream<Path> walk = Files.walk(folder, MAX_DEPTH)) {
                walk.filter(Files::isRegularFile).filter(SchematicLibrary::isSchematic)
                        .limit(MAX_FILES)
                        .forEach(p -> found.add(p.toAbsolutePath().normalize()));
            } catch (IOException | RuntimeException ignored) {
                // An unreadable folder only hides its own files.
            }
        }
        List<Path> sorted = new ArrayList<>(found);
        sorted.sort(Comparator.comparing((Path p) -> p.getFileName().toString().toLowerCase(Locale.ROOT))
                .thenComparing(Path::toString));
        return sorted;
    }
}
