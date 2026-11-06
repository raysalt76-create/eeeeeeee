package interstellarTikiTiks.apexx.client.util.combat;

import net.minecraft.client.MinecraftClient;
import net.minecraft.text.Text;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Pearl planning cost: a chat readout when one plan/solve blocks the game thread for a noticeable time, and a
 * one-time background run of the catch planner so its classes and JIT are warm before the first key press.
 */
public final class PearlTiming {

    /** A plan slower than this is reported; faster ones are not worth a line. */
    private static final double REPORT_MS = 25.0;
    private static final long REPORT_GAP_MS = 1000L;
    private static final AtomicBoolean WARMED = new AtomicBoolean();
    private static volatile long lastReportAt;

    private PearlTiming() {
    }

    public static void report(String module, String what, long startNanos) {
        double ms = (System.nanoTime() - startNanos) / 1.0e6;
        long now = System.currentTimeMillis();
        if (ms < REPORT_MS || now - lastReportAt < REPORT_GAP_MS) return;
        lastReportAt = now;
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc != null && mc.player != null) {
            mc.player.sendMessage(Text.literal(String.format("[%s] %s took %.0f ms", module, what, ms)), false);
        }
    }

    /** Plans a few open-air looks on a daemon thread, once. Pure math: no world, no Minecraft state. */
    public static void warmUpCatchPlanner() {
        if (!WARMED.compareAndSet(false, true)) return;
        Thread thread = new Thread(() -> {
            try {
                float[] pitches = {-60f, -30f, 0f, 30f};
                for (int round = 0; round < 2; round++) {
                    for (float pitch : pitches) {
                        new PearlCatchPlanner(new PearlCatchPlanner.Context(null, null,
                                new net.minecraft.util.math.Vec3d(0, 64, 0), net.minecraft.util.math.Vec3d.ZERO, 0.0,
                                true, Double.NaN, 1.62, 0f, pitch, 30.0f, 0.15, 5, 1, 24)).plan(0f, pitch);
                    }
                }
            } catch (Throwable ignored) {
                // A warm-up must never matter.
            }
        }, "Render-Prefetch");
        thread.setDaemon(true);
        thread.setPriority(Thread.MIN_PRIORITY);
        thread.start();
    }
}
