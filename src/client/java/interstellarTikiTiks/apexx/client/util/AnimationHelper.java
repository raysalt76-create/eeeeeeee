package interstellarTikiTiks.apexx.client.util;

public final class AnimationHelper {

    private AnimationHelper() {}

    public static double smoothDamp(double current, double target, double speed, double dt) {
        return current + (target - current) * (1.0 - Math.exp(-speed * dt));
    }

    public static double clampDelta(double dt) {
        return Math.max(1.0 / 240.0, Math.min(0.05, dt > 0.0 ? dt : 1.0 / 240.0));
    }
}
