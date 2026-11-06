package interstellarTikiTiks.apexx.client.util.combat;

import net.minecraft.util.math.MathHelper;

/**
 * Tick arithmetic for the AutoCart sequence, free of Minecraft state.
 *
 * <p>Offsets count client ticks from the bow release (offset 0). The server spawns the arrow while
 * handling the release and moves it once per tick from then on; every later interaction is
 * processed before the arrow's next move. A use sent at offset {@code x} therefore exists before
 * flight segment {@code x + 1}, so it is in time for an intercept at segment {@code i} exactly when
 * {@code i >= x + 1}. Latency cancels out: the release and the uses share one connection and one
 * cadence.</p>
 *
 * <p>Placement rotation advances once per tick through {@code RotationManager.stepAim}, which commits
 * the step immediately, so a use can follow the step that reaches its face inside the same tick.</p>
 */
public final class AutoCartTiming {
    private AutoCartTiming() {
    }

    /**
     * Ticks of placement rotation needed to travel between two looks when each step may turn at most
     * {@code maxStepDeg} along the straight turn and {@code axisCeiling} degrees on either axis.
     */
    public static int stepsNeeded(float fromYaw, float fromPitch, float toYaw, float toPitch,
                                  double maxStepDeg, double axisCeiling) {
        double yaw = Math.abs(MathHelper.wrapDegrees(toYaw - fromYaw));
        double pitch = Math.abs(toPitch - fromPitch);
        double distance = Math.sqrt(yaw * yaw + pitch * pitch);
        if (distance < 0.05) return 0;
        double step = Math.max(0.5, maxStepDeg);
        double ceiling = Math.max(0.5, axisCeiling);
        int steps = (int) Math.ceil(distance / step - 1.0e-9);
        steps = Math.max(steps, (int) Math.ceil(yaw / ceiling - 1.0e-9));
        return Math.max(steps, (int) Math.ceil(pitch / ceiling - 1.0e-9));
    }

    /**
     * Earliest offset at which a use can be sent.
     *
     * @param now          offset of the tick being planned, whose rotation step has not run yet
     * @param firstStep    first offset allowed to rotate toward this target
     * @param earliest     first offset allowed to use (one interaction per tick, none in the release tick)
     * @param steps        rotation ticks still needed from the current look
     */
    public static int actionOffset(int now, int firstStep, int earliest, int steps) {
        int start = Math.max(now, firstStep);
        return Math.max(earliest, start + Math.max(steps, 1) - 1);
    }

    /** The rest of the sequence after a use at {@code previous}: a fresh target starts rotating next tick. */
    public static int nextOffset(int previous, int steps) {
        return previous + Math.max(steps, 1);
    }

    /** True when a use sent at {@code actionOffset} exists before the arrow's intercept segment. */
    public static boolean inTime(int actionOffset, int intercept, int margin) {
        return intercept > 0 && intercept >= actionOffset + 1 + Math.max(0, margin);
    }

    /** Offset at which an exact spawn packet can be expected, from the connection's round trip. */
    public static int expectedSpawnOffset(int latencyMs) {
        return 1 + (int) Math.ceil(Math.max(0, latencyMs) / 50.0);
    }
}
