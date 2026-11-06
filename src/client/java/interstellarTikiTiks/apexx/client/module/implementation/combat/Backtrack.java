package interstellarTikiTiks.apexx.client.module.implementation.combat;

import interstellarTikiTiks.apexx.client.mixin.EntityS2CPacketAccessor;
import interstellarTikiTiks.apexx.client.module.BooleanSetting;
import interstellarTikiTiks.apexx.client.module.Category;
import interstellarTikiTiks.apexx.client.module.ModeSetting;
import interstellarTikiTiks.apexx.client.module.Module;
import interstellarTikiTiks.apexx.client.module.SliderSetting;
import interstellarTikiTiks.apexx.client.module.hooks.AttackTargetAware;
import interstellarTikiTiks.apexx.client.module.hooks.IncomingPacketInterceptor;
import interstellarTikiTiks.apexx.client.module.hooks.PacketQueueFlusher;
import interstellarTikiTiks.apexx.client.theme.AccentState;
import interstellarTikiTiks.apexx.client.util.render.RenderUtils;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.util.BufferAllocator;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityPosition;
import net.minecraft.entity.LivingEntity;
import interstellarTikiTiks.apexx.client.util.ClientInternalLog;
import interstellarTikiTiks.apexx.client.mixin.EntityStatusS2CPacketAccessor;
import net.minecraft.network.packet.BundlePacket;
import net.minecraft.network.packet.Packet;
import net.minecraft.network.packet.s2c.play.EntitiesDestroyS2CPacket;
import net.minecraft.network.packet.s2c.play.EntityPositionS2CPacket;
import net.minecraft.network.packet.s2c.play.EntityPositionSyncS2CPacket;
import net.minecraft.network.packet.s2c.play.EntityS2CPacket;
import net.minecraft.network.packet.s2c.play.EntityStatusS2CPacket;
import net.minecraft.network.packet.s2c.play.EntityVelocityUpdateS2CPacket;
import net.minecraft.network.packet.s2c.play.PlayerPositionLookS2CPacket;
import net.minecraft.util.hit.EntityHitResult;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;

import java.awt.Color;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Backtrack — buffers inbound server position packets for an attacked target, holding
 * their past position and hitbox reachable while they attempt to retreat or space out.
 *
 * <h2>Quadruple-Verification Architecture:</h2>
 * <ol>
 *   <li><b>Thread Isolation:</b> Packets are intercepted on Netty IO threads, but packet
 *       dispatching to {@code ClientPlayNetworkHandler} executes strictly on the Minecraft
 *       main thread to prevent {@code ConcurrentModificationException}.</li>
 *   <li><b>Vape Directional Ray-Distance Heuristic:</b> {@code shouldDelaySnapshot} evaluates
 *       the 3D ray distance from the player's look vector to the entity bounding box. Packets
 *       are ONLY delayed if the target is moving away; closing targets pass through instantly.</li>
 *   <li><b>Fail-Safe Emergency Flushes:</b> Immediate flushes trigger on local velocity packets,
 *       server teleports, target death, entity despawns, or queue cap/timeout expiry.</li>
 *   <li><b>Bundle & Sync Awareness:</b> Fully unpacks modern 1.21 {@link BundlePacket},
 *       {@link EntityPositionSyncS2CPacket}, and {@link EntityPositionS2CPacket}.</li>
 * </ol>
 */
public class Backtrack extends Module implements IncomingPacketInterceptor, AttackTargetAware, PacketQueueFlusher {

    private final ModeSetting targetMode = new ModeSetting("Target Mode", "Attack", "Attack", "Crosshair", "Both")
            .setDescription("Who gets backtracked. Attack: the player you last hit. Crosshair: whoever your crosshair is on. Both: either of them.");

    private final SliderSetting minLatency = new SliderSetting("Min Latency (ms)", 50, 0, 500, 0)
            .setDescription("Shortest time, in ms, the target's incoming position updates are held back. Each hold picks a random time between Min and Max Latency.");

    private final SliderSetting maxLatency = new SliderSetting("Max Latency (ms)", 100, 0, 500, 0)
            .setDescription("Longest time, in ms, the target's position updates are held back. Higher keeps their old hitbox around longer but makes them visibly stutter and is easier to detect.");

    private final SliderSetting maxRange = new SliderSetting("Max Range", 6.0, 3.0, 12.0, 1)
            .setDescription("If the target gets farther than this many blocks away, everything held back is released at once so you never fight a stale position across the map.");

    private final SliderSetting maxQueued = new SliderSetting("Max Queued", 40, 5, 100, 0)
            .setDescription("Safety cap on held packets. If more than this many pile up, they are all released immediately to avoid desyncing the world.");

    private final BooleanSetting onlyMovingAway = new BooleanSetting("Only Moving Away", true)
            .setDescription("Only hold an update when it would move the target away from your aim, so their hitbox lingers where you can hit it. Updates that bring them closer pass through immediately.");

    private final BooleanSetting flushOnVelocity = new BooleanSetting("Flush On Velocity", true)
            .setDescription("Release everything held the moment you take knockback, so your own movement and the target's position never desync during a trade.");

    private final BooleanSetting flushOnAttack = new BooleanSetting("Flush On Attack", false)
            .setDescription("Release everything held right after each of your hits lands, so every hit is followed by an up-to-date position.");

    private final BooleanSetting renderServerPos = new BooleanSetting("Render Server Pos", true)
            .setDescription("Draw a box where the server really has the target, so you can see how far the held-back hitbox is from their true position.");

    private static final class QueuedPacket {
        final Packet<?> packet;
        final long releaseAtMs;

        QueuedPacket(Packet<?> packet, long releaseAtMs) {
            this.packet = packet;
            this.releaseAtMs = releaseAtMs;
        }
    }

    private final ConcurrentLinkedDeque<QueuedPacket> packetQueue = new ConcurrentLinkedDeque<>();
    private volatile int activeTargetId = -1;
    private volatile Entity activeTarget = null;
    private volatile Vec3d trackedServerPos = null;
    private volatile boolean serverPosValid = false;
    // Client-thread snapshots read by the Netty-thread packet handler (no live entity access off-thread).
    private volatile Vec3d snapEye = null;
    private volatile Vec3d snapTargetPos = null;
    private volatile Box snapTargetBox = null;

    public Backtrack() {
        super("Backtrack", "Blatant: holds back a target's incoming position updates for a short random time, so their hitbox stays where it was a moment ago and you can still hit them as they move away. Releases everything on range loss, knockback or queue overflow. Holding packets is detectable on servers with latency checks. Off by default.", Category.BLATANT);
        addSetting(targetMode);
        addSetting(minLatency);
        addSetting(maxLatency);
        addSetting(maxRange);
        addSetting(maxQueued);
        addSetting(onlyMovingAway);
        addSetting(flushOnVelocity);
        addSetting(flushOnAttack);
        addSetting(renderServerPos);
    }

    @Override
    public void onEnable() {
        resetState();
    }

    @Override
    public void onDisable() {
        flushQueuedPackets();
        resetState();
    }

    @Override
    protected void onRuntimeStateReleased() {
        flushQueuedPackets();
        resetState();
    }

    private void resetState() {
        activeTargetId = -1;
        activeTarget = null;
        trackedServerPos = null;
        serverPosValid = false;
        packetQueue.clear();
    }

    private void resetTarget() {
        activeTargetId = -1;
        activeTarget = null;
        trackedServerPos = null;
        serverPosValid = false;
    }

    @Override
    public void onTick() {
        if (!isEnabled()) return;
        processQueue();
        updateCrosshairTarget();
        refreshSnapshot();
    }

    @Override
    public void onRender() {
        if (!isEnabled()) return;
        processQueue();
        refreshSnapshot();
    }

    private void refreshSnapshot() {
        Entity t = activeTarget;
        if (mc.player == null || t == null) {
            snapEye = null;
            snapTargetPos = null;
            snapTargetBox = null;
            return;
        }
        snapEye = mc.player.getEyePos();
        snapTargetPos = entityPos(t);
        snapTargetBox = t.getBoundingBox();
    }

    private static Vec3d entityPos(Entity e) {
        return new Vec3d(e.getX(), e.getY(), e.getZ());
    }

    private void updateCrosshairTarget() {
        if (mc.player == null || mc.world == null) return;
        if (targetMode.is("Attack")) return;

        if (activeTarget == null || !activeTarget.isAlive() || activeTarget.isRemoved()) {
            if (mc.crosshairTarget instanceof EntityHitResult ehr && ehr.getEntity() instanceof LivingEntity living) {
                if (living != mc.player && living.isAlive() && mc.player.distanceTo(living) <= maxRange.getValue()) {
                    activeTarget = living;
                    activeTargetId = living.getId();
                    trackedServerPos = entityPos(living);
                    serverPosValid = true;
                }
            }
        }
    }

    @Override
    public void onAttack(Entity target) {
        if (!isEnabled() || target == null || mc.player == null) return;
        if (targetMode.is("Crosshair")) return;

        if (target instanceof LivingEntity living && living != mc.player && living.isAlive()) {
            activeTarget = living;
            activeTargetId = living.getId();
            trackedServerPos = entityPos(living);
            serverPosValid = true;

            if (flushOnAttack.isEnabled()) {
                flushQueuedPackets();
            }
        }
    }

    @Override
    public boolean handleIncomingPacket(Packet<?> packet) {
        if (!isEnabled() || packet == null || mc.player == null || mc.world == null) {
            return false;
        }

        // Emergency flush 1: Local player receives velocity
        if (packet instanceof EntityVelocityUpdateS2CPacket velocityPacket) {
            if (velocityPacket.getEntityId() == mc.player.getId()) {
                if (flushOnVelocity.isEnabled()) {
                    flushQueuedPackets();
                }
                return false;
            }
        }

        // Emergency flush 2: Local player server teleport / rubberband
        if (packet instanceof PlayerPositionLookS2CPacket) {
            flushQueuedPackets();
            return false;
        }

        // Netty thread: only the volatile id and the snapshots taken on the client thread are read here.
        int targetId = this.activeTargetId;
        if (targetId == -1) {
            return false;
        }

        // Emergency flush 3: Target entity destroyed / despawned
        if (packet instanceof EntitiesDestroyS2CPacket destroyPacket) {
            if (destroyPacket.getEntityIds().contains(targetId)) {
                flushQueuedPackets();
                resetTarget();
                return false;
            }
        }

        // Emergency flush 4: Target entity death status
        if (packet instanceof EntityStatusS2CPacket statusPacket && statusPacket.getStatus() == 3) {
            if (((EntityStatusS2CPacketAccessor) statusPacket).getEntityId() == targetId) {
                flushQueuedPackets();
                resetTarget();
                return false;
            }
        }

        // Handle BundlePacket
        if (packet instanceof BundlePacket<?> bundle) {
            for (Packet<?> subPacket : (Iterable<? extends Packet<?>>) bundle.getPackets()) {
                if (isTargetMovementPacket(subPacket, targetId)) {
                    return queueTargetPacket(packet, subPacket);
                }
            }
            return false;
        }

        if (isTargetMovementPacket(packet, targetId)) {
            return queueTargetPacket(packet, packet);
        }

        return false;
    }

    private boolean isTargetMovementPacket(Packet<?> packet, int targetId) {
        if (packet instanceof EntityPositionSyncS2CPacket sync && sync.id() == targetId) {
            return true;
        }
        if (packet instanceof EntityPositionS2CPacket pos && pos.entityId() == targetId) {
            return true;
        }
        if (packet instanceof EntityS2CPacket move && ((EntityS2CPacketAccessor) move).getId() == targetId) {
            return true;
        }
        return false;
    }

    private boolean queueTargetPacket(Packet<?> packetToCancel, Packet<?> movementPacket) {
        Vec3d newServerPos = resolveNewPosition(movementPacket);
        if (newServerPos == null) {
            return false;
        }

        // Vape directional raycast heuristic: only delay if target is moving away
        if (!shouldDelaySnapshot(newServerPos)) {
            trackedServerPos = newServerPos;
            serverPosValid = true;
            // Older held updates must not apply after this closing one and snap the target back.
            flushQueuedPackets();
            return false; // let packet through so closer target is tracked in real-time
        }

        trackedServerPos = newServerPos;
        serverPosValid = true;

        double min = Math.min(minLatency.getValue(), maxLatency.getValue());
        double max = Math.max(minLatency.getValue(), maxLatency.getValue());
        long delayMs = (long) (min >= max ? min : min + ThreadLocalRandom.current().nextDouble() * (max - min));
        long releaseAtMs = System.currentTimeMillis() + delayMs;

        packetQueue.add(new QueuedPacket(packetToCancel, releaseAtMs));
        return true;
    }

    private Vec3d resolveNewPosition(Packet<?> packet) {
        Vec3d base = trackedServerPos != null ? trackedServerPos : snapTargetPos;

        if (packet instanceof EntityPositionSyncS2CPacket sync) {
            if (sync.values() != null && sync.values().position() != null) {
                return sync.values().position();
            }
        }

        if (packet instanceof EntityPositionS2CPacket pos) {
            if (pos.change() != null && pos.change().position() != null) {
                if (trackedServerPos != null && pos.relatives() != null) {
                    EntityPosition updated = EntityPosition.apply(
                            new EntityPosition(trackedServerPos, Vec3d.ZERO, 0f, 0f),
                            pos.change(),
                            pos.relatives()
                    );
                    return updated.position();
                }
                return pos.change().position();
            }
        }

        if (packet instanceof EntityS2CPacket move) {
            if (move.isPositionChanged()) {
                double dx = move.getDeltaX() / 4096.0;
                double dy = move.getDeltaY() / 4096.0;
                double dz = move.getDeltaZ() / 4096.0;
                return base.add(dx, dy, dz);
            }
            return base;
        }

        return base;
    }

    /**
     * Vape v4 directional ray-distance check:
     * Calculates intersection distance from player's eye along the direction to target.
     * If the new server position is farther away, the target is spacing/retreating -> delay packet.
     */
    private boolean shouldDelaySnapshot(Vec3d newServerPos) {
        if (!onlyMovingAway.isEnabled()) {
            return true;
        }
        Vec3d eyePos = snapEye;
        Vec3d targetPos = snapTargetPos;
        Box currentBox = snapTargetBox;
        if (eyePos == null || targetPos == null || currentBox == null) {
            return false;
        }

        double currentDist = rayIntersectionDistance(eyePos, targetPos, currentBox);

        Box newBox = currentBox.offset(newServerPos.subtract(targetPos));
        double newDist = rayIntersectionDistance(eyePos, newServerPos, newBox);

        return newDist > currentDist + 0.001;
    }

    private double rayIntersectionDistance(Vec3d eyePos, Vec3d targetPos, Box box) {
        Vec3d targetCenter = box.getCenter();
        Vec3d dir = targetCenter.subtract(eyePos);
        double len = dir.length();
        if (len < 1e-4) return 0.0;

        Vec3d rayEnd = eyePos.add(dir.normalize().multiply(Math.max(len + 2.0, 10.0)));
        Box expanded = box.expand(0.1);
        Optional<Vec3d> hit = expanded.raycast(eyePos, rayEnd);
        return hit.map(eyePos::distanceTo).orElse(len);
    }

    private void processQueue() {
        if (mc.getNetworkHandler() == null || mc.player == null || mc.world == null) {
            flushQueuedPackets();
            resetTarget();
            return;
        }

        if (activeTarget != null) {
            if (!activeTarget.isAlive() || activeTarget.isRemoved() || mc.player.distanceTo(activeTarget) > maxRange.getValue()) {
                flushQueuedPackets();
                resetTarget();
                return;
            }
        }

        if (packetQueue.size() > (int) maxQueued.getValue()) {
            flushQueuedPackets();
            return;
        }

        long now = System.currentTimeMillis();
        while (!packetQueue.isEmpty()) {
            QueuedPacket head = packetQueue.peek();
            if (head == null || head.releaseAtMs > now) {
                break;
            }
            packetQueue.poll();
            dispatchPacket(head.packet);
        }
    }

    private void dispatchPacket(Packet<?> packet) {
        if (mc.getNetworkHandler() == null) return;
        try {
            @SuppressWarnings({"rawtypes", "unchecked"})
            Packet raw = packet;
            raw.apply(mc.getNetworkHandler());
        } catch (Throwable t) {
            ClientInternalLog.warn("Backtrack", "held packet apply failed", t);
        }
    }

    public void flushQueuedPackets() {
        if (packetQueue.isEmpty()) return;

        List<Packet<?>> toFlush = new ArrayList<>();
        QueuedPacket qp;
        while ((qp = packetQueue.poll()) != null) {
            toFlush.add(qp.packet);
        }
        if (toFlush.isEmpty()) return;

        Runnable dispatchAll = () -> {
            if (mc.getNetworkHandler() == null) return;
            for (Packet<?> p : toFlush) {
                try {
                    @SuppressWarnings({"rawtypes", "unchecked"})
                    Packet raw = p;
                    raw.apply(mc.getNetworkHandler());
                } catch (Throwable t) {
                    ClientInternalLog.warn("Backtrack", "held packet apply failed", t);
                }
            }
        };

        if (mc.isOnThread()) {
            dispatchAll.run();
        } else {
            mc.send(dispatchAll);
        }
    }

    @Override
    public void sendQueuedPackets() {
        flushQueuedPackets();
    }

    // ── Visual Bounding Box ───────────────────────────────────────────────────

    @Override
    public boolean rendersInWorld() {
        return isEnabled() && renderServerPos.isEnabled() && serverPosValid && trackedServerPos != null
                && activeTarget != null && activeTarget.isAlive() && !packetQueue.isEmpty();
    }

    @Override
    public void onWorldRender(MatrixStack matrices, float tickDelta) {
        if (!rendersInWorld() || mc.player == null || mc.world == null) return;

        Vec3d cameraPos = mc.gameRenderer.getCamera().getCameraPos();
        Box localBox = activeTarget.getBoundingBox().offset(-activeTarget.getX(), -activeTarget.getY(), -activeTarget.getZ());
        Color col = AccentState.accentColor();

        try (BufferAllocator allocator = interstellarTikiTiks.apexx.client.util.render.PooledBufferAllocator.acquire(1024)) {
            VertexConsumerProvider.Immediate immediate = VertexConsumerProvider.immediate(allocator);
            matrices.push();
            matrices.translate(trackedServerPos.x - cameraPos.x, trackedServerPos.y - cameraPos.y, trackedServerPos.z - cameraPos.z);
            RenderUtils.drawFilledBox(matrices, immediate, localBox, col, 0.18f);
            RenderUtils.drawBoxOutlineEsp(matrices, immediate, localBox, col, 0.9f, true);
            matrices.pop();
            immediate.draw();
        }
    }
}
