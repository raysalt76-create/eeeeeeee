package interstellarTikiTiks.apexx.client.checker;

public record WireFrame(
        int tick,
        long nanoTime,
        WireAction action,
        int slot,
        float yaw,
        float pitch,
        double x,
        double y,
        double z,
        boolean onGround,
        double targetDistance,
        int targetEntityId,
        int keyForward,
        int keyStrafe
) {
    public static WireFrame of(int tick, long nanoTime, WireAction action) {
        return new WireFrame(tick, nanoTime, action, 0, 0f, 0f, 0, 0, 0, true, 0.0, -1, 0, 0);
    }
}
