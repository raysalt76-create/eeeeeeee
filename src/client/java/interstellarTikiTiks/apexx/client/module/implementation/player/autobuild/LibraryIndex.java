package interstellarTikiTiks.apexx.client.module.implementation.player.autobuild;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Off-thread index of the schematic library: which files exist and, per file, its size, format,
 * dimensions and block count, so the ClickGUI picker can label entries without the client thread
 * ever touching the disk. Metadata is cached by (path, size, modified time) and refreshed in place;
 * a newer {@link #refresh} supersedes an older one still running. Nothing is written anywhere.
 */
public final class LibraryIndex {

    /** Cap on files parsed per refresh; the picker shows far fewer anyway. */
    private static final int METADATA_BUDGET = 64;
    /** Files above this size are labelled without being parsed. */
    private static final long MAX_PARSE_BYTES = 48L * 1024 * 1024;

    public record Entry(Path path, long size, long modified, String name, String format,
                        int sizeX, int sizeY, int sizeZ, long blocks, String error) {
        public boolean known() {
            return error == null && sizeX > 0;
        }

        /** "name · WxHxD · Nk" (or the reason it could not be read). */
        public String label() {
            if (error != null) return name + " · " + error;
            if (sizeX <= 0) return name + " · " + format;
            return name + " · " + sizeX + "x" + sizeY + "x" + sizeZ + " · " + compact(blocks);
        }
    }

    private final Map<Path, Entry> cache = new ConcurrentHashMap<>();
    private final AtomicLong generation = new AtomicLong();
    private volatile List<Path> files = List.of();

    /** Current file list (recents first, then folder files), as of the last finished scan. */
    public List<Path> files() {
        return files;
    }

    /** Metadata for a file, or null while it is not indexed yet. */
    public Entry entry(Path path) {
        return path == null ? null : cache.get(path);
    }

    /** Label for a file: indexed metadata when known, else its file name. */
    public String labelFor(Path path) {
        Entry e = entry(path);
        if (e != null) return e.label();
        return path == null || path.getFileName() == null ? "?" : path.getFileName().toString() + " · …";
    }

    /**
     * Rescans {@code folders} on a daemon thread, keeps {@code recents} (existing files only) in front,
     * and indexes metadata. {@code clientThread} runs {@code onUpdate} on the client thread after the
     * listing and again as metadata arrives.
     */
    public void refresh(List<Path> folders, List<Path> recents, Executor clientThread, Runnable onUpdate) {
        long gen = generation.incrementAndGet();
        List<Path> folderCopy = folders == null ? List.of() : List.copyOf(folders);
        List<Path> recentCopy = recents == null ? List.of() : List.copyOf(recents);
        Thread worker = new Thread(() -> run(gen, folderCopy, recentCopy, clientThread, onUpdate),
                "Apexx-AutoBuild-Library");
        worker.setDaemon(true);
        try {
            worker.start();
        } catch (Throwable ignored) {
            // no thread available: the library just stays as it was
        }
    }

    /** Cancels any running scan (its results are dropped). */
    public void cancel() {
        generation.incrementAndGet();
    }

    private void run(long gen, List<Path> folders, List<Path> recents, Executor clientThread, Runnable onUpdate) {
        try {
            Set<Path> all = new LinkedHashSet<>();
            for (Path p : recents) {
                try {
                    Path abs = p.toAbsolutePath().normalize();
                    if (Files.isRegularFile(abs) && SchematicLibrary.isSchematic(abs)) all.add(abs);
                } catch (RuntimeException ignored) {
                    // skip
                }
            }
            all.addAll(SchematicLibrary.list(folders));
            if (gen != generation.get()) return;
            files = List.copyOf(all);
            post(clientThread, onUpdate);

            int parsed = 0;
            int sincePost = 0;
            for (Path p : new ArrayList<>(all)) {
                if (gen != generation.get()) return;
                long size;
                long modified;
                try {
                    size = Files.size(p);
                    modified = Files.getLastModifiedTime(p).toMillis();
                } catch (Throwable t) {
                    cache.put(p, new Entry(p, -1, -1, fileName(p), format(p), 0, 0, 0, 0, "unreadable"));
                    continue;
                }
                Entry cached = cache.get(p);
                if (cached != null && cached.size() == size && cached.modified() == modified) continue;
                if (size > MAX_PARSE_BYTES || parsed >= METADATA_BUDGET) {
                    cache.put(p, new Entry(p, size, modified, fileName(p), format(p), 0, 0, 0, 0, null));
                    continue;
                }
                parsed++;
                Entry e;
                try {
                    Schematic s = SchematicIo.load(p);
                    e = new Entry(p, size, modified, fileName(p), format(p), s.sizeX(), s.sizeY(), s.sizeZ(),
                            s.totalSolidBlocks(), null);
                } catch (Throwable t) {
                    e = new Entry(p, size, modified, fileName(p), format(p), 0, 0, 0, 0, "unreadable");
                }
                cache.put(p, e);
                if (++sincePost >= 4) {
                    sincePost = 0;
                    post(clientThread, onUpdate);
                }
            }
            if (gen == generation.get()) post(clientThread, onUpdate);
        } catch (Throwable ignored) {
            // a failed scan leaves the previous library in place
        }
    }

    private static void post(Executor clientThread, Runnable onUpdate) {
        if (clientThread == null || onUpdate == null) return;
        try {
            clientThread.execute(onUpdate);
        } catch (Throwable ignored) {
            // client shutting down
        }
    }

    static String fileName(Path p) {
        String name = p.getFileName() == null ? p.toString() : p.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    static String format(Path p) {
        String name = p.getFileName() == null ? "" : p.getFileName().toString().toLowerCase(Locale.ROOT);
        int dot = name.lastIndexOf('.');
        return dot >= 0 ? name.substring(dot + 1) : "?";
    }

    static String compact(long n) {
        if (n >= 1_000_000) return String.format(Locale.ROOT, "%.1fM", n / 1_000_000.0);
        if (n >= 1_000) return String.format(Locale.ROOT, "%.1fk", n / 1_000.0);
        return Long.toString(n);
    }
}
