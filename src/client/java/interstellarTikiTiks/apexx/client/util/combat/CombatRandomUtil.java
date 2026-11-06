package interstellarTikiTiks.apexx.client.util.combat;

import java.util.Random;

public final class CombatRandomUtil {
    private CombatRandomUtil() {
    }

    public static long randomDelayMs(Random random, double min, double max) {
        double low = Math.min(min, max);
        double high = Math.max(min, max);
        if (high <= low) {
            return (long) low;
        }
        return (long) (low + random.nextDouble() * (high - low));
    }
}
