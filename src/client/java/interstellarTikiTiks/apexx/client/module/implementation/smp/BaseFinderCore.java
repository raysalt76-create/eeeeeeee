package interstellarTikiTiks.apexx.client.module.implementation.smp;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Minecraft-free scan filtering and fade maths for {@link BaseFinder}, so it can be unit tested. */
public final class BaseFinderCore {

    public static final int MAX_TARGETS = 4096;
    public static final int SCAN_INTERVAL_TICKS = 10;

    public enum Kind {
        CHEST, TRAPPED_CHEST, ENDER_CHEST, SHULKER, DROPPER, SPAWNER, TRIAL_SPAWNER;

        public boolean isSpawner() {
            return this == SPAWNER || this == TRIAL_SPAWNER;
        }
    }

    /** One group's switches: the storage group or the spawner group. */
    public record Filter(boolean enabled, double range, boolean chests, boolean trappedChests, boolean enderChests,
                         boolean shulkers, boolean droppers, boolean trialSpawners) {

        public boolean includes(Kind kind) {
            return switch (kind) {
                case CHEST -> chests;
                case TRAPPED_CHEST -> trappedChests;
                case ENDER_CHEST -> enderChests;
                case SHULKER -> shulkers;
                case DROPPER -> droppers;
                case SPAWNER -> true;
                case TRIAL_SPAWNER -> trialSpawners;
            };
        }
    }

    public record Candidate(int x, int y, int z, Kind kind) {
        double distanceSq(double ox, double oy, double oz) {
            double dx = x + 0.5 - ox, dy = y + 0.5 - oy, dz = z + 0.5 - oz;
            return dx * dx + dy * dy + dz * dz;
        }
    }

    private BaseFinderCore() {
    }

    /** The largest range of the enabled groups, or 0 when neither is on. */
    public static double scanRange(Filter storage, Filter spawner) {
        return Math.max(storage.enabled() ? storage.range() : 0.0, spawner.enabled() ? spawner.range() : 0.0);
    }

    /** Keeps candidates their group wants and can reach, nearest first, capped at {@link #MAX_TARGETS}. */
    public static List<Candidate> select(List<Candidate> candidates, double ox, double oy, double oz,
                                         Filter storage, Filter spawner) {
        List<Candidate> found = new ArrayList<>();
        for (Candidate c : candidates) {
            Filter filter = c.kind().isSpawner() ? spawner : storage;
            if (!filter.enabled() || !filter.includes(c.kind())) continue;
            if (c.distanceSq(ox, oy, oz) <= filter.range() * filter.range()) found.add(c);
        }
        found.sort(Comparator.comparingDouble(c -> c.distanceSq(ox, oy, oz)));
        return List.copyOf(found.subList(0, Math.min(found.size(), MAX_TARGETS)));
    }

    /** 1 up to half the range, then a smoothstep down to 0 at the range. */
    public static double fade(double distance, double range) {
        double progress = Math.clamp((distance / Math.max(1.0, range) - 0.5) * 2.0, 0.0, 1.0);
        return 1.0 - progress * progress * (3.0 - 2.0 * progress);
    }
}
