package interstellarTikiTiks.apexx.client.util.combat;

import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Rolling per-player record (2 seconds, one entry per client tick) of what the server last sent
 * about every other player, for {@link PlayerMotionSimulation} to fit and forecast from.
 *
 * <p>It keeps the <em>tracked</em> position, not the rendered one. The server tests projectiles
 * against its own copy of a player, which the tracked position mirrors (to 1/4096 block) as of the
 * last update; the rendered position only interpolates toward it over three ticks and trails it.
 * The server sends a moving player every second tick (EntityType.PLAYER tracking interval 2) and a
 * still one not at all, so each distinct tracked position is stored with the client tick it was
 * first seen on. All calls run on the client thread.</p>
 */
public final class PlayerTrajectoryEngine {

    public static final int MAX_HISTORY_TICKS = 40;

    private static final PlayerTrajectoryEngine INSTANCE = new PlayerTrajectoryEngine();

    /** One client tick of a player's synced state; {@code tick} counts recorded client ticks. */
    public record PlayerSnapshot(long tick, Vec3d serverPos, boolean onGround, boolean sprinting,
                                 boolean sneaking, boolean inFluid, boolean gliding, float yaw, float pitch) {
        public PlayerMotionSimulation.Mode mode() {
            if (inFluid) return PlayerMotionSimulation.Mode.FLUID;
            if (gliding) return PlayerMotionSimulation.Mode.GLIDING;
            return onGround ? PlayerMotionSimulation.Mode.GROUND : PlayerMotionSimulation.Mode.AIR;
        }
    }

    /** A server position and the client tick it was first seen on. */
    public record PositionUpdate(long tick, Vec3d pos) {}

    private final Map<UUID, Deque<PlayerSnapshot>> playerHistories = new ConcurrentHashMap<>();
    private long tick;

    private PlayerTrajectoryEngine() {}

    public static PlayerTrajectoryEngine getInstance() {
        return INSTANCE;
    }

    /** Client tick end: records every other player in the world. */
    public void onTick(MinecraftClient mc) {
        if (mc == null || mc.world == null || mc.player == null) {
            return;
        }
        tick++;
        Set<UUID> active = new HashSet<>();
        for (PlayerEntity p : mc.world.getPlayers()) {
            if (p == mc.player || !p.isAlive() || p.isRemoved()) {
                continue;
            }
            active.add(p.getUuid());
            Deque<PlayerSnapshot> deque = playerHistories.computeIfAbsent(p.getUuid(),
                    k -> new ArrayDeque<>(MAX_HISTORY_TICKS + 1));
            deque.addLast(snapshot(p, tick));
            while (deque.size() > MAX_HISTORY_TICKS) {
                deque.pollFirst();
            }
        }
        // Players who left render distance or the world.
        playerHistories.keySet().removeIf(uuid -> !active.contains(uuid));
    }

    /** The client tick under way between two tick ends: the one after the last recorded tick. */
    public long currentTick() {
        return tick + 1;
    }

    /** Clears all tracking state (e.g. on server disconnect). */
    public void clear() {
        playerHistories.clear();
    }

    /** The recorded snapshots for a player, oldest first. */
    public List<PlayerSnapshot> getHistory(UUID uuid) {
        Deque<PlayerSnapshot> deque = playerHistories.get(uuid);
        return deque == null ? List.of() : new ArrayList<>(deque);
    }

    /**
     * Each distinct server position of {@code player}, oldest first, with the client tick it was
     * first seen on. The live tracked position counts as seen on {@link #currentTick()} when an
     * update arrived after the last tick end.
     */
    public List<PositionUpdate> updates(PlayerEntity player) {
        List<PositionUpdate> updates = new ArrayList<>();
        Vec3d last = null;
        for (PlayerSnapshot snapshot : getHistory(player.getUuid())) {
            if (!snapshot.serverPos().equals(last)) {
                updates.add(new PositionUpdate(snapshot.tick(), snapshot.serverPos()));
                last = snapshot.serverPos();
            }
        }
        Vec3d live = player.getTrackedPosition().getPos();
        if (!live.equals(last)) {
            updates.add(new PositionUpdate(currentTick(), live));
        }
        return updates;
    }

    private static PlayerSnapshot snapshot(PlayerEntity p, long tick) {
        return new PlayerSnapshot(tick, p.getTrackedPosition().getPos(), p.isOnGround(), p.isSprinting(),
                p.isSneaking(), p.isTouchingWater() || p.isInLava(), p.isGliding(), lookYaw(p), lookPitch(p));
    }

    /** The newest look the server sent: the interpolation target, not the rendered angle. */
    public static float lookYaw(PlayerEntity p) {
        return p.getInterpolator() != null ? p.getInterpolator().getLerpedYaw() : p.getYaw();
    }

    public static float lookPitch(PlayerEntity p) {
        return p.getInterpolator() != null ? p.getInterpolator().getLerpedPitch() : p.getPitch();
    }
}
