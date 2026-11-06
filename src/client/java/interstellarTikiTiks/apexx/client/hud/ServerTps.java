package interstellarTikiTiks.apexx.client.hud;

import interstellarTikiTiks.apexx.client.event.EventTarget;
import interstellarTikiTiks.apexx.client.event.impl.PacketReceiveEvent;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.PlayerListEntry;
import net.minecraft.network.packet.s2c.play.WorldTimeUpdateS2CPacket;

/**
 * Server tick-rate estimate and ping/TPS history for the Ping &amp; TPS widget.
 *
 * <p>TPS: the server sends a world-time update about once a second carrying its game time; the game-time
 * advance divided by the real time between two arrivals is the server's tick rate. Arrivals are recorded
 * on the netty thread (only a timestamp and the packet's own number are touched there, no client state);
 * history sampling and ping reads happen on the client thread.
 */
public final class ServerTps {
    public static final ServerTps INSTANCE = new ServerTps();
    public static final int HISTORY = 60;
    private static final int SMOOTHING = 5;
    private static final long SAMPLE_INTERVAL_MS = 1000L;
    /** No update for this long: the server is stalling, so the estimate decays toward what really arrived. */
    private static final long STALL_NANOS = 3_000_000_000L;

    private long lastArrivalNanos;
    private long lastGameTime = Long.MIN_VALUE;
    private final double[] recent = new double[SMOOTHING];
    private int recentCount;
    private int recentIndex;

    private final float[] pingHistory = new float[HISTORY];
    private final float[] tpsHistory = new float[HISTORY];
    private int historyCount;
    private int historyHead;
    private long lastSampleMs;

    private ServerTps() { }

    @EventTarget
    public void onPacket(PacketReceiveEvent event) {
        if (event != null && event.getPacket() instanceof WorldTimeUpdateS2CPacket packet) {
            onTimeUpdate(packet.time(), System.nanoTime());
        }
    }

    synchronized void onTimeUpdate(long gameTime, long nowNanos) {
        if (lastGameTime != Long.MIN_VALUE && lastArrivalNanos != 0L) {
            double seconds = (nowNanos - lastArrivalNanos) / 1_000_000_000.0;
            long ticks = gameTime - lastGameTime;
            // Ignore bursts (two packets in one read) and resets (time set backwards, world switch).
            if (seconds >= 0.25 && ticks >= 0 && ticks <= 20L * 60L) {
                double rate = Math.max(0.0, Math.min(20.0, ticks / seconds));
                recent[recentIndex] = rate;
                recentIndex = (recentIndex + 1) % SMOOTHING;
                recentCount = Math.min(SMOOTHING, recentCount + 1);
            } else if (seconds < 0.25) {
                return; // keep the earlier reference point
            }
        }
        lastGameTime = gameTime;
        lastArrivalNanos = nowNanos;
    }

    /** Estimated server TPS, or -1 before two updates have arrived. */
    public synchronized double tps() {
        if (recentCount == 0) return -1.0;
        double sum = 0.0;
        for (int i = 0; i < recentCount; i++) sum += recent[i];
        double avg = sum / recentCount;
        long silent = System.nanoTime() - lastArrivalNanos;
        if (silent > STALL_NANOS) {
            // A stalled server sends nothing; scale by how late the next update is (~1 s expected).
            avg = Math.min(avg, 20.0 / (silent / 1_000_000_000.0));
        }
        return Math.max(0.0, Math.min(20.0, avg));
    }

    /** Your latency from the player list, or -1 when unknown. Client thread. */
    public static int ping(MinecraftClient mc) {
        if (mc == null || mc.player == null || mc.getNetworkHandler() == null) return -1;
        PlayerListEntry entry = mc.getNetworkHandler().getPlayerListEntry(mc.player.getUuid());
        return entry == null ? -1 : Math.max(0, entry.getLatency());
    }

    /** Client thread, every tick: pushes one history sample per second. */
    public void sample(MinecraftClient mc) {
        if (mc == null || mc.player == null) return;
        long now = System.currentTimeMillis();
        int ping = ping(mc);
        double tps = tps();
        synchronized (this) {
            if (now - lastSampleMs < SAMPLE_INTERVAL_MS) return;
            lastSampleMs = now;
            pingHistory[historyHead] = Math.max(0, ping);
            tpsHistory[historyHead] = (float) Math.max(0.0, tps < 0 ? 20.0 : tps);
            historyHead = (historyHead + 1) % HISTORY;
            historyCount = Math.min(HISTORY, historyCount + 1);
        }
    }

    public synchronized int historySize() { return historyCount; }

    /** i = 0 is the oldest retained sample. */
    public synchronized float pingAt(int i) { return pingHistory[index(i)]; }
    public synchronized float tpsAt(int i) { return tpsHistory[index(i)]; }

    private int index(int i) {
        int clamped = Math.max(0, Math.min(historyCount - 1, i));
        return Math.floorMod(historyHead - historyCount + clamped, HISTORY);
    }

    public synchronized void reset() {
        lastArrivalNanos = 0L;
        lastGameTime = Long.MIN_VALUE;
        recentCount = 0;
        recentIndex = 0;
        historyCount = 0;
        historyHead = 0;
        lastSampleMs = 0L;
    }
}
