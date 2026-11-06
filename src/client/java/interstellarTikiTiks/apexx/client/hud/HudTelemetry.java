package interstellarTikiTiks.apexx.client.hud;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;

import java.util.ArrayDeque;
import java.util.Deque;

/** Tick-sampled local movement speed and tick rate for the GPU performance pill. */
public final class HudTelemetry {
    private static final double TELEPORT_STEP = 10.0;
    private static final long WINDOW_NANOS = 1_500_000_000L;
    private static final Deque<Sample> samples = new ArrayDeque<>();
    private static volatile double blocksPerSecond;
    private static volatile double ticksPerSecond = 20.0;

    private HudTelemetry() { }

    public static synchronized void tick(MinecraftClient client) {
        ClientPlayerEntity player = client == null ? null : client.player;
        if (player == null || !player.isAlive()) {
            reset();
            return;
        }

        long now = System.nanoTime();
        samples.addLast(new Sample(now, player.getX(), player.getY(), player.getZ()));
        while (samples.size() > 2 && now - samples.peekFirst().time > WINDOW_NANOS) {
            samples.removeFirst();
        }
        if (samples.size() < 2) return;

        Sample first = samples.peekFirst();
        Sample previous = first;
        double travelled = 0.0;
        for (Sample sample : samples) {
            double dx = sample.x - previous.x;
            double dy = sample.y - previous.y;
            double dz = sample.z - previous.z;
            double step = Math.sqrt(dx * dx + dy * dy + dz * dz);
            // A jump of this size in one tick is a teleport or respawn, not running speed.
            if (step <= TELEPORT_STEP) travelled += step;
            previous = sample;
        }
        double seconds = Math.max(0.001, (now - first.time) / 1_000_000_000.0);
        blocksPerSecond = Math.min(100.0, travelled / seconds);
        ticksPerSecond = Math.max(0.0, Math.min(20.0, (samples.size() - 1) / seconds));
    }

    public static double blocksPerSecond() { return blocksPerSecond; }
    public static double ticksPerSecond() { return ticksPerSecond; }

    public static synchronized void reset() {
        samples.clear();
        blocksPerSecond = 0.0;
        ticksPerSecond = 20.0;
    }

    private record Sample(long time, double x, double y, double z) { }
}
