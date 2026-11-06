package interstellarTikiTiks.apexx.client.module.implementation.combat;

import interstellarTikiTiks.apexx.client.event.EventBus;
import interstellarTikiTiks.apexx.client.event.EventTarget;
import interstellarTikiTiks.apexx.client.event.impl.TickEvent;
import interstellarTikiTiks.apexx.client.mixin.EntityS2CPacketAccessor;
import interstellarTikiTiks.apexx.client.mixin.PlayerInteractEntityC2SPacketAccessor;
import interstellarTikiTiks.apexx.client.module.BooleanSetting;
import interstellarTikiTiks.apexx.client.module.Category;
import interstellarTikiTiks.apexx.client.module.ModeSetting;
import interstellarTikiTiks.apexx.client.module.Module;
import interstellarTikiTiks.apexx.client.module.SliderSetting;
import interstellarTikiTiks.apexx.client.module.hooks.AttackHandleAware;
import interstellarTikiTiks.apexx.client.module.hooks.AttackTargetAware;
import interstellarTikiTiks.apexx.client.module.hooks.IncomingPacketInterceptor;
import interstellarTikiTiks.apexx.client.module.hooks.OutgoingPacketInterceptor;
import interstellarTikiTiks.apexx.client.module.hooks.PacketQueueFlusher;
import interstellarTikiTiks.apexx.client.module.hooks.RenderFrameAware;
import interstellarTikiTiks.apexx.client.module.implementation.player.BotFilterProvider;
import interstellarTikiTiks.apexx.client.network.PingEqualizer;
import interstellarTikiTiks.apexx.client.theme.AccentState;
import interstellarTikiTiks.apexx.client.util.ModuleAccess;
import interstellarTikiTiks.apexx.client.util.render.ProjectionUtil;
import interstellarTikiTiks.apexx.client.util.render.RenderUtils;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.render.Camera;
import net.minecraft.client.render.RenderTickCounter;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.util.BufferAllocator;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityPosition;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.PositionInterpolator;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.network.packet.BundlePacket;
import net.minecraft.network.packet.Packet;
import net.minecraft.network.packet.c2s.play.PlayerInteractEntityC2SPacket;
import net.minecraft.network.packet.s2c.common.DisconnectS2CPacket;
import net.minecraft.network.packet.s2c.login.LoginDisconnectS2CPacket;
import net.minecraft.network.packet.s2c.play.EntitiesDestroyS2CPacket;
import net.minecraft.network.packet.s2c.play.EntityPositionS2CPacket;
import net.minecraft.network.packet.s2c.play.EntityPositionSyncS2CPacket;
import net.minecraft.network.packet.s2c.play.EntityS2CPacket;
import net.minecraft.network.packet.s2c.play.EntitySpawnS2CPacket;
import net.minecraft.network.packet.s2c.play.EntityStatusS2CPacket;
import net.minecraft.network.packet.s2c.play.GameJoinS2CPacket;
import net.minecraft.network.packet.s2c.play.PlayerPositionLookS2CPacket;
import net.minecraft.network.packet.s2c.play.PlayerRespawnS2CPacket;
import net.minecraft.network.packet.s2c.play.PositionFlag;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.EntityHitResult;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import org.joml.Matrix3x2fStack;

import java.awt.Color;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * PlayerFreeze (v5) — Holds inbound S2C movement packets for a targeted player client-side,
 * creating an anchored freeze window for follow-up hits with full anti-cheat range gating,
 * and a smoothed live server bounding box.
 *
 * <p>Features:
 * <ul>
 *   <li>Trigger: Strike (on attack), Proximity (auto-trigger) or Both</li>
 *   <li>Max Reach: freeze drops when the real box is past the configured distance</li>
 *   <li>Inbound Packet Holding: Selective S2C interception with sub-packet bundle unwrapping</li>
 *   <li>Delta Reconstruction: Exact 1/4096th fixed-point axis resolution and position flag math</li>
 *   <li>Active Watchdogs: Target death, despawn, firework boosts, elytra gliding, water, disconnect</li>
 *   <li>3D Live Server Box: Exponentially smoothed world-space box rendering with 0.18 fill scale</li>
 *   <li>Auto-Attack Gating: Immediate release when retargeting a new player</li>
 * </ul>
 */
public class PlayerFreeze extends Module implements
        IncomingPacketInterceptor,
        OutgoingPacketInterceptor,
        AttackHandleAware,
        AttackTargetAware,
        RenderFrameAware,
        PacketQueueFlusher {

    private static final double LIVE_TARGET_BOX_SMOOTHING_PER_FRAME = 0.45;
    private static final double LIVE_TARGET_BOX_OPACITY = 215.0;
    private static final double LIVE_TARGET_BOX_FILL_ALPHA_SCALE = 0.18;
    private static final double LIVE_TARGET_BOX_SNAP_DISTANCE_SQ = 64.0;

    // ── Settings ─────────────────────────────────────────────────────────────
    private final ModeSetting trigger = new ModeSetting("Trigger", "Strike", "Proximity", "Both")
            .setDescription("What starts a freeze. Strike: your own hit on a player. Proximity: a player coming within Proximity blocks. Both: whichever happens first.");
    private final SliderSetting proximity = new SliderSetting("Proximity", 3.0, 2.0, 4.0, 1)
            .setDescription("Distance in blocks at which an approaching player starts a freeze (Proximity and Both triggers).");
    private final SliderSetting maxReach = new SliderSetting("Max Reach", 6.0, 3.0, 6.0, 1)
            .setDescription("The freeze is released as soon as the target's real (server) position is farther than this many blocks from you.");
    private final SliderSetting freezeTime = new SliderSetting("Freeze Time (s)", 1.0, 1.0, 10.0, 1)
            .setDescription("Longest a single freeze may last, in seconds. Longer freezes are more noticeable to the target and to latency checks.");
    private final SliderSetting recharge = new SliderSetting("Recharge (s)", 0.0, 0.0, 10.0, 1)
            .setDescription("Cooldown in seconds after a freeze ends before a new one may start. 0 allows back-to-back freezes.");
    private final BooleanSetting pauseInWater = new BooleanSetting("Pause In Water", true)
            .setDescription("Never freeze, and release any active freeze, while you are in water, where held positions desync badly.");

    // ── Runtime State ────────────────────────────────────────────────────────
    private volatile boolean holding = false;
    private volatile Entity trackedTarget = null;
    private volatile Vec3d lockedPosition = null;
    private volatile Vec3d lastKnownServerPos = null;
    private Vec3d renderedServerPos = null;
    private long holdUntilMs = 0;
    private long cooldownUntilMs = 0;
    private long lastLiveBoxRenderNanos = 0;

    private volatile int pendingSnapEntityId = Integer.MIN_VALUE;

    private final ConcurrentLinkedQueue<Packet<?>> packetQueue = new ConcurrentLinkedQueue<>();

    public PlayerFreeze() {
        super("PlayerFreeze", "Blatant: after you hit a player (or one comes close), holds back their incoming position updates so they appear frozen in place on your screen while you keep hitting them, then releases everything at once. Ends on range loss, the time limit or water. Holding packets like this is easy for latency checks to detect. Off by default.", Category.BLATANT);
        addSetting(trigger);
        addSetting(proximity.setDependsOn("Trigger:Proximity|Both"));
        addSetting(maxReach);
        addSetting(freezeTime);
        addSetting(recharge);
        addSetting(pauseInWater);
    }

    public String getInfo() {
        return trigger.getCurrentMode();
    }

    @Override
    public void onEnable() {
        EventBus.register(this);
        releaseHold(false);
    }

    @Override
    public void onDisable() {
        EventBus.unregister(this);
        releaseHold(true);
    }

    @Override
    protected void onRuntimeStateReleased() {
        // World change / disconnect: held packets belong to the old world and must not reach the new one.
        if (holding) {
            holding = false;
            holdUntilMs = 0L;
            PingEqualizer.getInstance().setFreezeHoldActive(false);
        }
        packetQueue.clear();
        pendingSnapEntityId = Integer.MIN_VALUE;
        trackedTarget = null;
        lastKnownServerPos = null;
        lockedPosition = null;
        renderedServerPos = null;
        lastLiveBoxRenderNanos = 0L;
    }

    public boolean isFreezing() {
        return holding && hasActivePacketHold(mc, System.currentTimeMillis());
    }

    public Entity getFrozenTarget() {
        return trackedTarget;
    }

    // ── Update Loops ─────────────────────────────────────────────────────────

    @EventTarget
    public void onTick(TickEvent event) {
        runUpdateLoop();
    }

    @Override
    public void onRenderFrame() {
        runUpdateLoop();
    }

    private void runUpdateLoop() {
        processPendingSnap(mc);

        if (!canRun(mc)) {
            if (holding) {
                releaseHold(false);
            }
            return;
        }

        if (!holding) {
            tryStartApproachHold(mc);
            return;
        }

        long now = System.currentTimeMillis();
        boolean mmcOutOfRange = isBoxOutsideRange(mc.player.getEyePos(), getLiveTargetBox(), reachCap());
        boolean notRenderable = !isRenderableTarget(mc, trackedTarget);

        if (isGlidingTarget(trackedTarget) || mmcOutOfRange || shouldSkipForWater(mc) || notRenderable || now >= holdUntilMs) {
            releaseHold(true);
            return;
        }

        // Anchor target entity locally so follow-up hits connect without interpolation drift
        if (lockedPosition != null && trackedTarget != null) {
            PositionInterpolator interpolator = trackedTarget.getInterpolator();
            if (interpolator != null) {
                interpolator.clear();
            }
            trackedTarget.setPosition(lockedPosition.x, lockedPosition.y, lockedPosition.z);
        }
    }

    private void processPendingSnap(MinecraftClient client) {
        if (this.pendingSnapEntityId == Integer.MIN_VALUE) {
            return;
        }
        int id = this.pendingSnapEntityId;
        this.pendingSnapEntityId = Integer.MIN_VALUE;
        if (client == null || client.world == null) {
            return;
        }
        Entity entity = client.world.getEntityById(id);
        if (!(entity instanceof PlayerEntity) || entity == client.player) {
            return;
        }
        Vec3d pos = (entity.getTrackedPosition() != null && entity.getTrackedPosition().getPos() != null)
                ? entity.getTrackedPosition().getPos()
                : (lastKnownServerPos != null ? lastKnownServerPos : entity.getEntityPos());
        if (pos == null) {
            return;
        }
        PositionInterpolator interpolator = entity.getInterpolator();
        if (interpolator != null) {
            interpolator.clear();
        }
        entity.setPosition(pos.x, pos.y, pos.z);
    }

    private void tryStartApproachHold(MinecraftClient client) {
        if (!onProximity() || shouldSkipForWater(client)) {
            return;
        }
        long now = System.currentTimeMillis();
        if (!isCooldownReady(now)) {
            return;
        }
        PlayerEntity approachTarget = findApproachTarget(client, proximity.getValue());
        if (approachTarget != null) {
            startHold(approachTarget, now);
        }
    }

    private PlayerEntity findApproachTarget(MinecraftClient client, double maxRange) {
        if (client == null || client.player == null || client.world == null) {
            return null;
        }
        double maxDistSq = maxRange * maxRange;
        PlayerEntity best = null;
        double bestDistSq = maxDistSq;

        for (PlayerEntity player : client.world.getPlayers()) {
            if (player == client.player || !isRenderableTarget(client, player) || player.isSpectator()
                    || isGlidingTarget(player)) {
                continue;
            }
            if (isBot(player)) {
                continue;
            }
            double distSq = client.player.squaredDistanceTo(player);
            if (distSq <= bestDistSq) {
                best = player;
                bestDistSq = distSq;
            }
        }
        return best;
    }

    private boolean isBot(PlayerEntity player) {
        for (BotFilterProvider botFilter : ModuleAccess.getEnabledHooks(BotFilterProvider.class)) {
            if (botFilter.isBot(player)) {
                return true;
            }
        }
        return false;
    }

    // ── Attack & Interaction Handling ────────────────────────────────────────

    @Override
    public boolean handleAttack() {
        if (!canRun(mc) || shouldSkipForWater(mc)) {
            return false;
        }
        if (!onStrike()) {
            return false;
        }
        if (mc.crosshairTarget instanceof EntityHitResult ehr && ehr.getEntity() instanceof PlayerEntity targetPlayer) {
            if (targetPlayer != mc.player && isRenderableTarget(mc, targetPlayer)) {
                if (!isTargetVisibleWithin(mc, targetPlayer, reachCap())) {
                    return false;
                }
                long now = System.currentTimeMillis();
                if (!holding && isCooldownReady(now)) {
                    startHold(targetPlayer, now);
                }
            }
        }
        return false;
    }

    @Override
    public boolean handleOutgoingPacket(Packet<?> packet) {
        if (!canRun(mc) || shouldSkipForWater(mc)) {
            return false;
        }

        if (packet instanceof PlayerInteractEntityC2SPacket interactPacket) {
            if (holding) {
                int entityId = ((PlayerInteractEntityC2SPacketAccessor) interactPacket).getEntityId();
                if (trackedTarget != null && entityId != trackedTarget.getId()) {
                    releaseHold(true);
                }
                return false;
            }

            if (!onStrike()) {
                return false;
            }

            long now = System.currentTimeMillis();
            if (!isCooldownReady(now)) {
                return false;
            }

            boolean[] isAttack = new boolean[1];
            interactPacket.handle(new PlayerInteractEntityC2SPacket.Handler() {
                @Override
                public void interact(Hand hand) {}

                @Override
                public void interactAt(Hand hand, Vec3d pos) {}

                @Override
                public void attack() {
                    isAttack[0] = true;
                }
            });

            if (isAttack[0]) {
                int entityId = ((PlayerInteractEntityC2SPacketAccessor) interactPacket).getEntityId();
                Entity entity = mc.world != null ? mc.world.getEntityById(entityId) : null;
                if (!(entity instanceof PlayerEntity)) {
                    if (mc.crosshairTarget instanceof EntityHitResult ehr) {
                        entity = ehr.getEntity();
                    }
                }
                if (entity instanceof PlayerEntity player && player != mc.player && isRenderableTarget(mc, player)) {
                    if (!isTargetVisibleWithin(mc, player, reachCap())) {
                        return false;
                    }
                    startHold(player, now);
                }
            }
        }
        return false;
    }

    @Override
    public void onAttack(Entity target) {
        if (!isEnabled()) return;
        if (holding) {
            if (target != null && trackedTarget != null && target != trackedTarget) {
                releaseHold(true);
            }
            return;
        }

        if (onStrike() && target instanceof PlayerEntity player && player != mc.player) {
            if (canRun(mc) && !shouldSkipForWater(mc) && isRenderableTarget(mc, player)) {
                if (!isTargetVisibleWithin(mc, player, reachCap())) {
                    return;
                }
                long now = System.currentTimeMillis();
                if (isCooldownReady(now)) {
                    startHold(player, now);
                }
            }
        }
    }

    public boolean releaseBeforeAutoAttack(MinecraftClient client, Entity entity) {
        if (!isEnabled() || !holding) {
            return false;
        }
        if (entity == null || trackedTarget == null || entity == trackedTarget) {
            return false;
        }
        releaseHold(true);
        return true;
    }

    // ── Inbound Packet Interception ──────────────────────────────────────────

    @Override
    public boolean handleIncomingPacket(Packet<?> packet) {
        if (!holding || trackedTarget == null || packet == null) {
            return false;
        }

        int targetId = trackedTarget.getId();

        if (packet instanceof BundlePacket<?> bundle) {
            boolean anyHeld = false;
            for (Packet<?> subPacket : bundle.getPackets()) {
                if (shouldHoldInboundPacket(subPacket, targetId)) {
                    anyHeld = true;
                    break;
                }
            }
            if (!anyHeld) {
                return false;
            }
            for (Packet<?> subPacket : bundle.getPackets()) {
                if (shouldHoldInboundPacket(subPacket, targetId)) {
                    processHeldPacket(subPacket, targetId);
                } else {
                    dispatchImmediatePacket(subPacket);
                }
            }
            return true;
        }

        // Auto-release watchdog 1: Entity death status
        if (packet instanceof EntityStatusS2CPacket statusPacket && statusPacket.getStatus() == 3) {
            if (mc.world != null && statusPacket.getEntity(mc.world) == trackedTarget) {
                releaseHold(true);
                return false;
            }
        }

        // Auto-release watchdog 2: Entity destroy / despawn
        if (packet instanceof EntitiesDestroyS2CPacket destroyPacket) {
            if (destroyPacket.getEntityIds().contains(targetId)) {
                releaseHold(false);
                return false;
            }
        }

        // Auto-release watchdog 3: Firework targeting player
        if (packet instanceof EntitySpawnS2CPacket spawnPacket
                && spawnPacket.getEntityType() == EntityType.FIREWORK_ROCKET
                && spawnPacket.getEntityData() == targetId) {
            releaseHold(true);
            return false;
        }

        // Auto-release watchdog 4: Disconnect / respawn / join
        if (packet instanceof PlayerPositionLookS2CPacket
                || packet instanceof PlayerRespawnS2CPacket
                || packet instanceof DisconnectS2CPacket
                || packet instanceof LoginDisconnectS2CPacket
                || packet instanceof GameJoinS2CPacket) {
            releaseHold(false);
            return false;
        }

        if (shouldHoldInboundPacket(packet, targetId)) {
            processHeldPacket(packet, targetId);
            return true;
        }

        return false;
    }

    private boolean shouldHoldInboundPacket(Packet<?> packet, int targetId) {
        if (packet instanceof EntityPositionSyncS2CPacket sync) {
            return sync.id() == targetId;
        }
        if (packet instanceof EntityPositionS2CPacket pos) {
            return pos.entityId() == targetId;
        }
        if (packet instanceof EntityS2CPacket move) {
            return ((EntityS2CPacketAccessor) move).getId() == targetId;
        }
        return false;
    }

    private void processHeldPacket(Packet<?> packet, int targetId) {
        if (packet instanceof EntityPositionSyncS2CPacket sync) {
            if (sync.id() == targetId) {
                if (sync.values() != null && sync.values().position() != null) {
                    this.lastKnownServerPos = sync.values().position();
                }
                queuePacket(packet);
                checkMMCRange();
            }
        } else if (packet instanceof EntityPositionS2CPacket pos) {
            if (pos.entityId() == targetId) {
                if (pos.change() != null && pos.change().position() != null) {
                    if (this.lastKnownServerPos != null && pos.relatives() != null) {
                        this.lastKnownServerPos = resolveTrackedPosition(
                                this.lastKnownServerPos,
                                pos.change().position(),
                                pos.relatives()
                        );
                    } else {
                        this.lastKnownServerPos = pos.change().position();
                    }
                }
                queuePacket(packet);
                checkMMCRange();
            }
        } else if (packet instanceof EntityS2CPacket move) {
            int moveId = ((EntityS2CPacketAccessor) move).getId();
            if (moveId == targetId) {
                if (this.lastKnownServerPos == null) {
                    this.lastKnownServerPos = getTrackedServerPosition(trackedTarget);
                }
                if (this.lastKnownServerPos != null && move.isPositionChanged()) {
                    this.lastKnownServerPos = resolveTrackedDelta(
                            this.lastKnownServerPos,
                            move.getDeltaX(),
                            move.getDeltaY(),
                            move.getDeltaZ()
                    );
                }
                queuePacket(packet);
                checkMMCRange();
            }
        }
    }

    private void dispatchImmediatePacket(Packet<?> subPacket) {
        if (mc == null) return;
        Runnable action = () -> {
            if (mc.getNetworkHandler() != null) {
                try {
                    @SuppressWarnings({"rawtypes", "unchecked"})
                    Packet raw = subPacket;
                    raw.apply(mc.getNetworkHandler());
                } catch (Throwable ignored) {
                }
            }
        };
        if (mc.isOnThread()) {
            action.run();
        } else {
            mc.send(action);
        }
    }

    private void checkMMCRange() {
        if (!holding || mc.player == null) {
            return;
        }
        if (isBoxOutsideRange(mc.player.getEyePos(), getLiveTargetBox(), reachCap())) {
            releaseHold(true);
        }
    }

    // ── Queue & Flush Management ─────────────────────────────────────────────

    private void queuePacket(Packet<?> packet) {
        packetQueue.add(packet);
    }

    public void flushQueuedPackets() {
        if (packetQueue.isEmpty()) return;

        List<Packet<?>> toFlush = new ArrayList<>();
        Packet<?> p;
        while ((p = packetQueue.poll()) != null) {
            toFlush.add(p);
        }
        if (toFlush.isEmpty()) return;

        Runnable dispatchAll = () -> {
            if (mc.getNetworkHandler() == null) return;
            for (Packet<?> pkt : toFlush) {
                try {
                    @SuppressWarnings({"rawtypes", "unchecked"})
                    Packet raw = pkt;
                    raw.apply(mc.getNetworkHandler());
                } catch (Throwable ignored) {
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

    // ── State Transitions ────────────────────────────────────────────────────

    private void startHold(Entity entity, long now) {
        if (!interstellarTikiTiks.apexx.client.util.combat.StickyTargets.allows(this, entity)) {
            return;
        }
        if (isGlidingTarget(entity)) {
            return;
        }
        if (entity instanceof PlayerEntity friendCheck
                && interstellarTikiTiks.apexx.client.friend.FriendManager.getInstance().isFriend(friendCheck.getUuid())) {
            return;
        }
        packetQueue.clear();
        this.holding = true;
        this.trackedTarget = entity;
        this.lockedPosition = new Vec3d(entity.getX(), entity.getY(), entity.getZ());
        this.lastKnownServerPos = getTrackedServerPosition(entity);
        this.renderedServerPos = this.lastKnownServerPos;
        this.lastLiveBoxRenderNanos = 0L;
        this.holdUntilMs = now + getDurationMs();

        PingEqualizer.getInstance().setFlags(false, true);
        PingEqualizer.getInstance().setFreezeHoldActive(true);
    }

    public void releaseHold(boolean applyServerPosition) {
        if (!holding) {
            packetQueue.clear();
            renderedServerPos = null;
            lastLiveBoxRenderNanos = 0L;
            return;
        }

        holding = false;
        holdUntilMs = 0L;
        long now = System.currentTimeMillis();
        cooldownUntilMs = getCooldownMs() > 0L ? now + getCooldownMs() : 0L;

        PingEqualizer.getInstance().setFreezeHoldActive(false);
        PingEqualizer.getInstance().markFlushNeeded();

        if (applyServerPosition && trackedTarget != null) {
            refreshServerPos();
            this.pendingSnapEntityId = trackedTarget.getId();
            // Packet-thread releases leave the snap to processPendingSnap on the client thread.
            if (lastKnownServerPos != null && mc.isOnThread()) {
                PositionInterpolator interpolator = trackedTarget.getInterpolator();
                if (interpolator != null) {
                    interpolator.clear();
                }
                trackedTarget.setPosition(lastKnownServerPos.x, lastKnownServerPos.y, lastKnownServerPos.z);
            }
        }

        flushQueuedPackets();

        trackedTarget = null;
        lastKnownServerPos = null;
        lockedPosition = null;
        renderedServerPos = null;
        lastLiveBoxRenderNanos = 0L;
    }

    // ── Mathematical Helpers ─────────────────────────────────────────────────

    public static Vec3d getTrackedServerPosition(Entity entity) {
        if (entity == null) {
            return null;
        }
        if (entity.getTrackedPosition() != null) {
            Vec3d pos = entity.getTrackedPosition().getPos();
            if (pos != null) return pos;
        }
        return entity.getEntityPos();
    }

    /**
     * Rebuilds the target's real server position as the entity's applied tracked position plus every
     * packet still held in the queue, in arrival order. Packets that were already in flight when the
     * hold began are applied by the game on its own and show up in the tracked position, so the box
     * cannot miss them the way an incrementally accumulated position could.
     */
    private Vec3d refreshServerPos() {
        Entity entity = this.trackedTarget;
        if (entity == null) {
            return this.lastKnownServerPos;
        }
        Vec3d pos = getTrackedServerPosition(entity);
        int targetId = entity.getId();
        for (Packet<?> packet : packetQueue) {
            if (pos == null) {
                break;
            }
            if (packet instanceof EntityPositionSyncS2CPacket sync) {
                if (sync.id() == targetId && sync.values() != null && sync.values().position() != null) {
                    pos = sync.values().position();
                }
            } else if (packet instanceof EntityPositionS2CPacket abs) {
                if (abs.entityId() == targetId && abs.change() != null && abs.change().position() != null) {
                    pos = resolveTrackedPosition(pos, abs.change().position(), abs.relatives());
                }
            } else if (packet instanceof EntityS2CPacket move) {
                if (((EntityS2CPacketAccessor) move).getId() == targetId && move.isPositionChanged()) {
                    pos = resolveTrackedDelta(pos, move.getDeltaX(), move.getDeltaY(), move.getDeltaZ());
                }
            }
        }
        if (pos != null) {
            this.lastKnownServerPos = pos;
        }
        return this.lastKnownServerPos;
    }

    private Box getLiveTargetBox() {
        Entity entity = this.trackedTarget;
        Vec3d pos = refreshServerPos();
        if (entity == null || pos == null) {
            return null;
        }
        return entity.getDimensions(entity.getPose()).getBoxAt(pos);
    }

    private Box getRenderedLiveTargetBox() {
        Entity entity = this.trackedTarget;
        Vec3d pos = refreshServerPos();
        if (entity == null || pos == null) {
            return null;
        }
        long nowNanos = System.nanoTime();
        long lastNanos = this.lastLiveBoxRenderNanos;
        this.lastLiveBoxRenderNanos = nowNanos;
        double dt = lastNanos == 0L ? (1.0 / 60.0) : Math.max(0.0, (double) (nowNanos - lastNanos) / 1.0E9);
        if (this.renderedServerPos == null) {
            this.renderedServerPos = pos;
        }
        double factor = smoothFactor(LIVE_TARGET_BOX_SMOOTHING_PER_FRAME, dt);
        this.renderedServerPos = interpolateLiveTargetPosition(this.renderedServerPos, pos, factor);
        return entity.getDimensions(entity.getPose()).getBoxAt(this.renderedServerPos);
    }

    public static Vec3d interpolateLiveTargetPosition(Vec3d current, Vec3d target, double factor) {
        if (target == null) {
            return null;
        }
        if (current == null || current.squaredDistanceTo(target) > LIVE_TARGET_BOX_SNAP_DISTANCE_SQ) {
            return target;
        }
        return current.lerp(target, Math.max(0.0, Math.min(1.0, factor)));
    }

    private static double smoothFactor(double baseSmoothing, double dtSeconds) {
        double clampedBase = Math.max(0.0, Math.min(1.0, baseSmoothing));
        double frames = Math.max(0.0, Math.min(8.0, dtSeconds * 60.0));
        return 1.0 - Math.pow(1.0 - clampedBase, frames);
    }

    public static Vec3d resolveTrackedDelta(Vec3d base, long deltaX, long deltaY, long deltaZ) {
        if (base == null) return null;
        return new Vec3d(
                resolveTrackedAxis(base.x, deltaX),
                resolveTrackedAxis(base.y, deltaY),
                resolveTrackedAxis(base.z, deltaZ)
        );
    }

    private static double resolveTrackedAxis(double current, long delta) {
        return delta == 0L ? current : (double) (Math.round(current * 4096.0) + delta) / 4096.0;
    }

    public static Vec3d resolveTrackedPosition(Vec3d base, Vec3d change, Set<PositionFlag> flags) {
        if (change == null) {
            return base;
        }
        if (base == null || flags == null || flags.isEmpty()) {
            return change;
        }
        return new Vec3d(
                flags.contains(PositionFlag.X) ? base.x + change.x : change.x,
                flags.contains(PositionFlag.Y) ? base.y + change.y : change.y,
                flags.contains(PositionFlag.Z) ? base.z + change.z : change.z
        );
    }

    public static boolean isBoxOutsideRange(Vec3d eyePos, Box box, double maxDistance) {
        if (eyePos == null || box == null) {
            return false;
        }
        double clampedX = Math.max(box.minX, Math.min(eyePos.x, box.maxX));
        double clampedY = Math.max(box.minY, Math.min(eyePos.y, box.maxY));
        double clampedZ = Math.max(box.minZ, Math.min(eyePos.z, box.maxZ));
        double dx = eyePos.x - clampedX;
        double dy = eyePos.y - clampedY;
        double dz = eyePos.z - clampedZ;
        return (dx * dx + dy * dy + dz * dz) > (maxDistance * maxDistance);
    }

    private boolean isTargetVisibleWithin(MinecraftClient client, Entity entity, double range) {
        return isRenderableTarget(client, entity) && client.player.squaredDistanceTo(entity) <= range * range;
    }

    private static Vec3d centerPoint(Box box) {
        if (box == null) return Vec3d.ZERO;
        return new Vec3d((box.minX + box.maxX) * 0.5, (box.minY + box.maxY) * 0.5, (box.minZ + box.maxZ) * 0.5);
    }

    private boolean onStrike() {
        return !trigger.is("Proximity");
    }

    private boolean onProximity() {
        return !trigger.is("Strike");
    }

    private double reachCap() {
        return maxReach.getValue();
    }

    private boolean canRun(MinecraftClient client) {
        return isEnabled() && client != null && client.player != null && client.world != null && client.getNetworkHandler() != null;
    }

    private boolean shouldSkipForWater(MinecraftClient client) {
        return pauseInWater.isEnabled() && client != null && client.player != null && (client.player.isTouchingWater() || client.player.isSubmergedInWater());
    }

    private boolean isRenderableTarget(MinecraftClient client, Entity entity) {
        return client != null && client.player != null && client.world != null && entity != null
                && entity.isAlive() && !entity.isRemoved() && client.world.getEntityById(entity.getId()) == entity;
    }

    private boolean isGlidingTarget(Entity entity) {
        return entity instanceof PlayerEntity player && player.isGliding();
    }

    private boolean isCooldownReady(long now) {
        return recharge.getValue() <= 0.0 || now >= cooldownUntilMs;
    }

    private boolean hasActivePacketHold(MinecraftClient client, long now) {
        return holding && now < holdUntilMs && trackedTarget != null && isRenderableTarget(client, trackedTarget);
    }

    private long getDurationMs() {
        return Math.max(1000L, Math.min(10000L, Math.round(freezeTime.getValue() * 1000.0)));
    }

    private long getCooldownMs() {
        return Math.max(0L, Math.min(10000L, Math.round(recharge.getValue() * 1000.0)));
    }

    // ── Visual Rendering (3D World & 2D HUD) ──────────────────────────────────

    @Override
    public boolean rendersInWorld() {
        return isEnabled() && holding && trackedTarget != null
                && isRenderableTarget(mc, trackedTarget);
    }

    @Override
    public void onWorldRender(MatrixStack matrices, float tickDelta) {
        if (!rendersInWorld() || mc.player == null || mc.world == null) {
            return;
        }

        Box liveBox = getRenderedLiveTargetBox();
        if (liveBox != null) {
            renderLiveTargetBox(matrices, liveBox);
        }
    }

    private void renderLiveTargetBox(MatrixStack matrices, Box worldBox) {
        Vec3d cameraPos = mc.gameRenderer.getCamera().getCameraPos();
        Box localBox = worldBox.offset(-cameraPos.x, -cameraPos.y, -cameraPos.z);
        Color color = AccentState.accentColor();
        double opacity = LIVE_TARGET_BOX_OPACITY;
        float alpha = (float) (opacity / 255.0);
        float fillAlpha = alpha * (float) LIVE_TARGET_BOX_FILL_ALPHA_SCALE;

        try (BufferAllocator allocator = interstellarTikiTiks.apexx.client.util.render.PooledBufferAllocator.acquire(1024)) {
            VertexConsumerProvider.Immediate immediate = VertexConsumerProvider.immediate(allocator);
            RenderUtils.drawFilledBox(matrices, immediate, localBox, color, fillAlpha);
            RenderUtils.drawBoxOutlineEsp(matrices, immediate, localBox, color, alpha, true);
            immediate.draw();
        } catch (Throwable ignored) {
        }
    }
}
