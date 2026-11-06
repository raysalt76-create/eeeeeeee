package interstellarTikiTiks.apexx.client.util.combat;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Random;

/**
 * Pure humanization helpers for AutoTotem: fatigue after a burst of pops, which totem slot a hand reaches
 * for, and a per-cent roll. No Minecraft types.
 */
public final class TotemHumanizer {

    private TotemHumanizer() {
    }

    /**
     * Delay multiplier after {@code pops} pops inside the fatigue window: {@code 1 + min(max, perPop * pops) / 100}.
     * Never below 1.
     */
    public static double fatigueFactor(int pops, double perPopPercent, double maxPercent) {
        if (pops <= 0) return 1.0;
        double pct = Math.min(Math.max(0.0, maxPercent), Math.max(0.0, perPopPercent) * pops);
        return 1.0 + pct / 100.0;
    }

    /** True with probability {@code percent} / 100. */
    public static boolean roll(double percent, Random random) {
        if (!(percent > 0.0)) return false;
        if (percent >= 100.0) return true;
        return (random == null ? new Random() : random).nextDouble() * 100.0 < percent;
    }

    /**
     * Index into {@code distances} of the slot a hand reaches for: the nearest one, except that with
     * {@code randomPercent} chance another candidate is chosen, weighted toward nearer slots (1 / (1 + d)).
     * Returns -1 for an empty list.
     */
    public static int pickSlot(List<Double> distances, double randomPercent, Random random) {
        if (distances == null || distances.isEmpty()) return -1;
        int nearest = 0;
        for (int i = 1; i < distances.size(); i++) {
            if (distances.get(i) < distances.get(nearest)) nearest = i;
        }
        Random r = random == null ? new Random() : random;
        if (distances.size() == 1 || !roll(randomPercent, r)) return nearest;
        double total = 0.0;
        double[] weights = new double[distances.size()];
        for (int i = 0; i < weights.length; i++) {
            weights[i] = 1.0 / (1.0 + Math.max(0.0, distances.get(i)));
            total += weights[i];
        }
        double pick = r.nextDouble() * total;
        for (int i = 0; i < weights.length; i++) {
            pick -= weights[i];
            if (pick <= 0.0) return i;
        }
        return weights.length - 1;
    }

    /** Exponential retry backoff: {@code base * 2^attempt}, capped. */
    public static long backoffMs(long baseMs, int attempt, long capMs) {
        long base = Math.max(0L, baseMs);
        int shift = Math.max(0, Math.min(attempt, 10));
        return Math.min(Math.max(base, capMs), base << shift);
    }

    /** Timestamps of recent own totem pops, for the fatigue window. Not thread-safe; feed it on one thread. */
    public static final class PopHistory {
        private final Deque<Long> pops = new ArrayDeque<>();

        public void record(long nowMs) {
            pops.addLast(nowMs);
            while (pops.size() > 64) pops.removeFirst();
        }

        public int countWithin(long nowMs, long windowMs) {
            while (!pops.isEmpty() && nowMs - pops.peekFirst() > windowMs) pops.removeFirst();
            return pops.size();
        }

        public void clear() {
            pops.clear();
        }
    }
}
