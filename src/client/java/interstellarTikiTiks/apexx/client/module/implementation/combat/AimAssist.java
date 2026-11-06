package interstellarTikiTiks.apexx.client.module.implementation.combat;

import interstellarTikiTiks.apexx.client.util.combat.ModuleDelays;
import interstellarTikiTiks.apexx.client.friend.FriendManager;
import interstellarTikiTiks.apexx.client.module.BooleanSetting;
import interstellarTikiTiks.apexx.client.module.Category;
import interstellarTikiTiks.apexx.client.module.ModeSetting;
import interstellarTikiTiks.apexx.client.module.Module;
import interstellarTikiTiks.apexx.client.module.SliderSetting;
import interstellarTikiTiks.apexx.client.module.hooks.MouseDeltaShaper;
import interstellarTikiTiks.apexx.client.module.hooks.RenderFrameAware;
import interstellarTikiTiks.apexx.client.util.AssistMotion;
import interstellarTikiTiks.apexx.client.util.RotationManager;
import interstellarTikiTiks.apexx.client.util.SensitivityAssist;
import interstellarTikiTiks.apexx.client.module.implementation.player.BotFilterProvider;
import interstellarTikiTiks.apexx.client.util.ModuleAccess;
import interstellarTikiTiks.apexx.client.util.combat.CombatItemUtil;
import interstellarTikiTiks.apexx.client.util.combat.StickyTargets;
import interstellarTikiTiks.apexx.client.util.combat.TargetPriority;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

import java.util.function.Supplier;

/**
 * AimAssist — three ways to help the crosshair onto a target, each with only its core knobs.
 *
 * <ul>
 *   <li><b>Human</b> turns the camera through {@link RotationManager}'s ASSIST controller
 *       ({@link AssistMotion}) with Wind on: the approach curves like a hand sweep and lands cleanly
 *       on a zone of the hitbox, only supplies the motion your own mouse is not already supplying,
 *       and follows most of the target's movement.</li>
 *   <li><b>Smart Lock</b> is the same controller set aggressive: faster, crisper, no wind, a tight
 *       zone around the body, the target's full movement fed forward and more lead, so the crosshair
 *       stays glued to a strafing target. It takes a firmer pull to let go.</li>
 *   <li><b>Sensitivity</b> never turns the camera: it lowers your mouse sensitivity smoothly and
 *       deeply as the crosshair nears the target ({@link SensitivityAssist}), so it settles and
 *       stays on it. Following the target and real flicks pass at full speed.</li>
 * </ul>
 *
 * <p>Both camera modes detach smoothly: deliberate pull-away first yields the help, then lets go,
 * and what is left eases out instead of stopping dead; help returns when you swing back onto the
 * target or rest near it. Friends, line of sight (with a short grace), target choice and the
 * crystal pause are fixed behavior. Selection runs on the client tick; the motion itself runs every
 * render frame (the ASSIST controller in the rotation engine, the input shaper in {@code MouseMixin}).
 */
public class AimAssist extends Module implements MouseDeltaShaper, RenderFrameAware {
    // ---- Delays ----
    private final SliderSetting targetSwitch = new SliderSetting("Target Switch (ms)", 150, 0, 1000, 0)
            .withJitter(30)
            .setDescription("Shortest time, in milliseconds, between moving the help from one player to another. "
                    + "A human takes a moment to pick a new target; 0 lets a better target take over at once.")
            .setSection("Delays");
    private final SliderSetting profileDelay = new SliderSetting("Weapon Settle (ms)", 30, 0, 300, 0)
            .withJitter(8)
            .setDescription("Pause after you switch to a weapon before the assist starts helping again, in milliseconds. "
                    + "A short settle looks like a real hand re-gripping; 0 resumes instantly.")
            .setSection("Delays");


    // ── Settings ────────────────────────────────────────────────────────────────
    private final ModeSetting mode = new ModeSetting("Mode", "Human", "Human", "Smart Lock", "Sensitivity")
            .setDescription("Human: curved, hand-like help that lands cleanly. Smart Lock: aggressive lock that sticks to strafing targets. Sensitivity: never moves the camera; your sensitivity drops smoothly and deeply near the target.");
    private final SliderSetting strength = new SliderSetting("Strength", 55, 1, 100, 0)
            .setDescription("How hard the camera is pulled onto the target and kept on it while they move (1-100). Higher sticks better but looks less natural; 40-60 reads as a steady human hand.")
            .setDependsOn("Mode:Human|Smart Lock");
    private final SliderSetting pullawayStrength = new SliderSetting("Pullaway Strength", 80, 0, 100, 0)
            .setDescription("Resistance to pulling your aim off the target. Higher values hold harder and need a stronger deliberate pull to release; Smart Lock applies the strongest resistance.")
            .setDependsOn("Mode:Human|Smart Lock");
    private final SliderSetting slowdown = new SliderSetting("Slowdown", 75, 0, 100, 0)
            .setDescription("How much your sensitivity drops on the target (100 keeps about a tenth). Quadratic slowdown begins well before the hitbox and deepens as you approach.")
            .setDependsOn("Mode:Sensitivity");
    private final SliderSetting range = new SliderSetting("Range", 4.0, 1.0, 8.0, 1)
            .setDescription("Farthest a target can be, in blocks, to be picked and kept. Helping beyond your real reach only lines you up early; it never extends reach.");
    private final SliderSetting fov = new SliderSetting("FOV", 100, 10, 360, 0)
            .setDescription("Width of the cone in front of you, in degrees, where targets are considered. A narrow cone only helps when you are already roughly on target; 360 helps from any angle.");
    private final BooleanSetting onlyWeapons = new BooleanSetting("Only Weapons", true)
            .setDescription("Only help while you hold a sword, axe, mace, spear or trident, so building and mining are never touched.");

    // ── Fixed behavior ──────────────────────────────────────────────────────────
    private static final long LOS_GRACE_MS = 350L;
    private static final double SWITCH_SCORE_MARGIN = 400.0;

    /** Per-mode controller presets: Human (wind, soft) and Smart Lock (aggressive, glued). */
    private static final double HUMAN_SMOOTHNESS = 0.6;
    private static final double HUMAN_TRACKING = 0.75;
    private static final double HUMAN_DETACH = 0.55;
    private static final double HUMAN_WIND = 0.5;
    private static final double HUMAN_PRECISION = 0.5;
    private static final double HUMAN_LEAD = 0.35;
    private static final double LOCK_SMOOTHNESS = 0.12;
    private static final double LOCK_TRACKING = 1.0;
    private static final double LOCK_DETACH = 0.3;
    private static final double LOCK_PRECISION = 0.85;
    private static final double LOCK_LEAD = 0.55;
    private static final double REENGAGE_MS = 250.0;
    private static final double SENSITIVITY_PRECISION = 0.5;

    // ── Runtime state ───────────────────────────────────────────────────────────
    private Entity finalTarget = null;
    private long lastTargetSwitchTime = 0L;
    /** Target Switch sample for the current switch window, ms; re-sampled on every switch. */
    private long switchDelayMs = 150L;
    private long externalPauseUntilMs = 0L;
    private int lastSelectedSlot = -1;
    private boolean lastHoldingWeapon = false;
    private long weaponSettleUntilMs = 0L;
    /** When the current target first became occluded, or 0 while it is visible. */
    private long losLostAtMs = 0L;

    // Per-tick world velocity of the target, for the lead, and its render-frame smoothed copy.
    private int velocityTargetId = Integer.MIN_VALUE;
    private Vec3d targetVelocity = Vec3d.ZERO;
    private Vec3d renderTargetVelocity = Vec3d.ZERO;

    // Sensitivity mode: per-frame zone and target bearing velocity, sampled in onRenderFrame()
    // before Mouse.updateMouse runs in the same frame.
    private final SensitivityAssist sensitivity = new SensitivityAssist();
    private AssistMotion.Zone sensZone = null;
    private double sensDtSeconds = 1.0 / 60.0;
    private long sensLastFrameNanos = 0L;
    private double sensLastYaw;
    private double sensLastPitch;
    private double sensVelYaw;
    private double sensVelPitch;
    private int sensTargetId = Integer.MIN_VALUE;

    public AimAssist() {
        super("AimAssist", "Guides your crosshair onto nearby players while you aim. Human mode curves the camera like a hand, Smart Lock sticks to strafing targets, and Sensitivity never moves the camera but lowers your mouse sensitivity over the target so your own aim settles on it.", Category.COMBAT);
        addSetting(targetSwitch);
        addSetting(profileDelay);
        addSetting(mode);
        addSetting(strength);
        addSetting(pullawayStrength);
        addSetting(slowdown);
        addSetting(range);
        addSetting(fov);
        addSetting(onlyWeapons);
    }

    // ── Lifecycle ───────────────────────────────────────────────────────────────

    @Override
    public void onEnable() {
        resetState();
    }

    @Override
    public void onDisable() {
        resetState();
        RotationManager.stop(this);
    }

    @Override
    protected void onRuntimeStateReleased() {
        resetState();
        RotationManager.stop(this);
    }

    private void resetState() {
        finalTarget = null;
        lastSelectedSlot = -1;
        lastHoldingWeapon = false;
        weaponSettleUntilMs = 0L;
        losLostAtMs = 0L;
        velocityTargetId = Integer.MIN_VALUE;
        targetVelocity = Vec3d.ZERO;
        renderTargetVelocity = Vec3d.ZERO;
        resetSensitivity();
    }

    private void resetSensitivity() {
        sensitivity.reset();
        sensZone = null;
        sensLastFrameNanos = 0L;
        sensVelYaw = 0.0;
        sensVelPitch = 0.0;
        sensTargetId = Integer.MIN_VALUE;
    }

    public void externalPause(long ttlMs) {
        externalPauseUntilMs = Math.max(externalPauseUntilMs, System.currentTimeMillis() + ttlMs);
    }

    public void externalResume() {
        externalPauseUntilMs = 0L;
    }

    private boolean isExternallyPaused() {
        return System.currentTimeMillis() < externalPauseUntilMs;
    }

    /** API for external combat modules to borrow AimAssist's rotation strength. */
    public void aimExternally(Object owner, RotationManager.Priority priority,
                              Supplier<Vec3d> aimPointSupplier, boolean silent, boolean yawOnly) {
        double engineStrength = RotationManager.engineStrength(strength.getValue(), RotationManager.STRENGTH_SCALE_TRACK);
        RotationManager.setRotationSupplier(
                owner, priority, aimPointSupplier, engineStrength,
                RotationManager.RotationMode.SMOOTH, 0.1, silent, yawOnly);
    }

    // ── Tick: target selection and channel requests ─────────────────────────────

    @Override
    public void onTick() {
        if (!isEnabled()) return;

        if (isExternallyPaused() || hasObsidianAndCrystals()) {
            dropTarget();
            return;
        }
        if (mc.world == null || mc.player == null || mc.currentScreen != null) {
            finalTarget = null;
            RotationManager.stop(this);
            return;
        }
        if (!isWeaponReadyForAim()) {
            finalTarget = null;
            return;
        }

        findBestTarget();
        if (finalTarget == null || !finalTarget.isAlive()) {
            dropTarget();
            return;
        }
        updateTargetVelocity(finalTarget);

        if (mode.is("Sensitivity")) {
            // Sensitivity mode never owns the rotation channel; the shaper reads finalTarget.
            RotationManager.clearTarget(this);
            return;
        }

        Entity locked = finalTarget;
        boolean lock = mode.is("Smart Lock");
        double s = strength.getValue() / 100.0;
        double pullaway = pullawayStrength.getValue() / 100.0;
        AssistMotion.Config config = lock
                ? new AssistMotion.Config(MathHelper.lerp(s, 0.85, 1.0), LOCK_SMOOTHNESS, LOCK_TRACKING,
                        true, LOCK_DETACH, REENGAGE_MS, true, 0.0, pullaway)
                : new AssistMotion.Config(s, HUMAN_SMOOTHNESS, HUMAN_TRACKING,
                        true, HUMAN_DETACH, REENGAGE_MS, true, HUMAN_WIND, pullaway * 0.35);
        RotationManager.setAssistSupplier(this, RotationManager.Priority.NORMAL,
                () -> locked.isAlive() ? aimZone(locked) : null, config);
    }

    private void dropTarget() {
        finalTarget = null;
        sensZone = null;
        RotationManager.clearTarget(this);
    }

    // ── Sensitivity mode ────────────────────────────────────────────────────────

    @Override
    public void onRenderFrame() {
        long now = System.nanoTime();
        double dt = sensLastFrameNanos == 0L ? 1.0 / 60.0 : (now - sensLastFrameNanos) / 1.0e9;
        sensLastFrameNanos = now;
        sensDtSeconds = MathHelper.clamp(dt, 1.0e-4, 0.1);

        // Smooth the 20Hz per-tick target velocity at render cadence so the lead never steps on
        // client tick boundaries.
        double velAlpha = 1.0 - Math.exp(-sensDtSeconds / 0.045);
        renderTargetVelocity = renderTargetVelocity.lerp(targetVelocity, velAlpha);

        Entity target = finalTarget;
        if (!mode.is("Sensitivity") || target == null || !target.isAlive() || mc.player == null) {
            sensZone = null;
            sensTargetId = Integer.MIN_VALUE;
            return;
        }

        AssistMotion.Zone zone = RotationManager.assistZoneFor(aimZone(target));
        if (zone == null) {
            sensZone = null;
            return;
        }
        if (target.getId() != sensTargetId || dt > 0.1) {
            sensTargetId = target.getId();
            sensVelYaw = 0.0;
            sensVelPitch = 0.0;
        } else {
            double rawYaw = MathHelper.wrapDegrees(zone.yaw() - sensLastYaw) / sensDtSeconds;
            double rawPitch = (zone.centerPitch() - sensLastPitch) / sensDtSeconds;
            if (Math.hypot(rawYaw, rawPitch) > 900.0) {
                sensVelYaw = 0.0;
                sensVelPitch = 0.0;
            } else {
                double a = 1.0 - Math.exp(-sensDtSeconds / 0.07);
                sensVelYaw += (rawYaw - sensVelYaw) * a;
                sensVelPitch += (rawPitch - sensVelPitch) * a;
            }
        }
        sensLastYaw = zone.yaw();
        sensLastPitch = zone.centerPitch();
        sensZone = zone;
    }

    @Override
    public void shapeLookDelta(double[] lookDelta) {
        AssistMotion.Zone zone = sensZone;
        if (!isEnabled() || zone == null || !mode.is("Sensitivity") || finalTarget == null
                || mc.player == null || mc.currentScreen != null || isExternallyPaused()) {
            return;
        }
        try {
            double[] err = zone.nearestError(RotationManager.getVisualYaw(), RotationManager.getVisualPitch());
            SensitivityAssist.Geometry geometry = new SensitivityAssist.Geometry(
                    err[0], err[1], zone.radius(), sensVelYaw, sensVelPitch);
            // The slowdown fades in over a band that grows with the target's apparent size.
            double band = MathHelper.clamp(zone.radius() * 2.0 + 12.0, 12.0, 30.0);
            SensitivityAssist.Config config = new SensitivityAssist.Config(slowdown.getValue() / 100.0, band);
            double[] out = sensitivity.shape(lookDelta[0], lookDelta[1], geometry, config, sensDtSeconds);
            lookDelta[0] = out[0];
            lookDelta[1] = out[1];
        } catch (RuntimeException ignored) {
            // Leave the player's input untouched rather than break mouse look.
        }
    }

    // ── Target selection ────────────────────────────────────────────────────────

    private void findBestTarget() {
        boolean haveCurrent = finalTarget != null && isValid(finalTarget, true);
        if (!haveCurrent) {
            finalTarget = null;
            losLostAtMs = 0L;
        }

        Entity bestMatch = finalTarget;
        // Smart Lock keeps its target: a challenger only wins once the current one is gone.
        boolean sticky = mode.is("Smart Lock") && haveCurrent;

        if (!sticky && System.currentTimeMillis() - lastTargetSwitchTime > switchDelayMs) {
            Entity bestChallenger = null;
            double bestChallengerScore = -Double.MAX_VALUE;
            for (Entity entity : candidates()) {
                if (entity == finalTarget || !isValid(entity, false)) continue;
                double sc = scoreOf(entity);
                if (sc > bestChallengerScore) {
                    bestChallenger = entity;
                    bestChallengerScore = sc;
                }
            }
            if (bestChallenger != null
                    && (!haveCurrent || bestChallengerScore > scoreOf(finalTarget) + SWITCH_SCORE_MARGIN)) {
                bestMatch = bestChallenger;
            }
        }

        if (bestMatch != finalTarget) {
            lastTargetSwitchTime = System.currentTimeMillis();
            switchDelayMs = ModuleDelays.clampMs(targetSwitch.sample());
            finalTarget = bestMatch;
            losLostAtMs = 0L;
            RotationManager.retargetAssist(this);
            sensitivity.reset();
        }
    }

    private boolean isHoldingSpear() {
        if (mc.player == null) return false;
        ItemStack stack = mc.player.getMainHandStack();
        if (stack == null || stack.isEmpty()) return false;
        return Registries.ITEM.getId(stack.getItem()).getPath().contains("spear");
    }

    public double getEffectiveRange() {
        return isHoldingSpear() ? Math.max(4.5, range.getValue()) : range.getValue();
    }

    private double scoreOf(Entity entity) {
        return TargetPriority.score((LivingEntity) entity, mc.player, getEffectiveRange());
    }

    /** Entities worth testing this tick — a box around the player, not every loaded entity. */
    private Iterable<Entity> candidates() {
        Box search = mc.player.getBoundingBox().expand(getEffectiveRange() + 1.0);
        return mc.world.getOtherEntities(mc.player, search);
    }

    private boolean isValid(Entity entity, boolean isCurrent) {
        if (!(entity instanceof PlayerEntity player) || entity == mc.player || !entity.isAlive()) return false;
        if (player.isSpectator() || player.isCreative()) return false;
        FriendManager friends = FriendManager.getInstance();
        if (friends.isFriend(player.getUuid()) || friends.isFriend(player.getName().getString())) return false;
        for (BotFilterProvider filter : ModuleAccess.getEnabledHooks(BotFilterProvider.class)) {
            if (filter.isBot(player)) return false;
        }
        if (!StickyTargets.allows(this, player)) return false;
        if (eyeToBoxDistance(entity) > getEffectiveRange()) return false;

        // True 3D cone; `fov` is the FULL cone width, hence the halving.
        double slack = isCurrent ? 10.0 : 0.0;
        if (angleToEntity(entity) > fov.getValue() * 0.5 + slack) return false;

        if (!mc.player.canSee(entity)) {
            // Tolerate brief occlusion on the CURRENT target instead of cutting instantly.
            if (!isCurrent) return false;
            long now = System.currentTimeMillis();
            if (losLostAtMs == 0L) losLostAtMs = now;
            return now - losLostAtMs <= LOS_GRACE_MS;
        }
        if (isCurrent) losLostAtMs = 0L;
        return true;
    }

    /** True 3D angle, in degrees, between where the player is looking and the target's centre. */
    private double angleToEntity(Entity entity) {
        Vec3d look = mc.player.getRotationVec(1.0f).normalize();
        Vec3d dir = entity.getBoundingBox().getCenter().subtract(mc.player.getEyePos());
        double len = dir.length();
        if (len < 1.0e-6) return 0.0;
        double cos = MathHelper.clamp(look.dotProduct(dir) / len, -1.0, 1.0);
        return Math.toDegrees(Math.acos(cos));
    }

    private double eyeToBoxDistance(Entity entity) {
        Vec3d eye = mc.player.getEyePos();
        Box box = entity.getBoundingBox();
        double cx = MathHelper.clamp(eye.x, box.minX, box.maxX);
        double cy = MathHelper.clamp(eye.y, box.minY, box.maxY);
        double cz = MathHelper.clamp(eye.z, box.minZ, box.maxZ);
        return eye.distanceTo(new Vec3d(cx, cy, cz));
    }

    // ── Aim zone ────────────────────────────────────────────────────────────────

    /**
     * Per-tick world velocity (blocks/s) from successive positions. Client-side velocity of other
     * players is not reliable, so it is derived from where the entity actually moved.
     */
    private void updateTargetVelocity(Entity entity) {
        Vec3d raw = new Vec3d(entity.getX() - entity.lastRenderX,
                entity.getY() - entity.lastRenderY,
                entity.getZ() - entity.lastRenderZ).multiply(20.0);
        if (entity.getId() != velocityTargetId || raw.lengthSquared() > 40.0 * 40.0) {
            velocityTargetId = entity.getId();
            targetVelocity = raw.lengthSquared() > 40.0 * 40.0 ? Vec3d.ZERO : raw;
            renderTargetVelocity = targetVelocity;
            return;
        }
        // A reversal (strafe change) is followed quickly; consistent motion is smoothed.
        double alpha = targetVelocity.dotProduct(raw) < 0.0 ? 0.75 : 0.45;
        targetVelocity = targetVelocity.lerp(raw, alpha);
    }

    /**
     * Render-interpolated world box the crosshair should settle inside, led along the target's
     * motion: the middle part of the body, tight for Smart Lock and roomier for Human/Sensitivity.
     */
    private Box aimZone(Entity entity) {
        float t = mc.getRenderTickCounter().getTickProgress(false);
        double x = MathHelper.lerp(t, entity.lastRenderX, entity.getX());
        double y = MathHelper.lerp(t, entity.lastRenderY, entity.getY());
        double z = MathHelper.lerp(t, entity.lastRenderZ, entity.getZ());

        boolean lock = mode.is("Smart Lock");
        if (entity.getId() == velocityTargetId) {
            double lead = (lock ? LOCK_LEAD : HUMAN_LEAD) * 0.15;
            Vec3d offset = new Vec3d(renderTargetVelocity.x, renderTargetVelocity.y * 0.5, renderTargetVelocity.z).multiply(lead);
            if (offset.lengthSquared() > 0.6 * 0.6) offset = offset.normalize().multiply(0.6);
            x += offset.x;
            y += offset.y;
            z += offset.z;
        }

        // Precision shrinks the zone toward the middle of the body (0.56 h).
        double p = lock ? LOCK_PRECISION : (mode.is("Sensitivity") ? SENSITIVITY_PRECISION : HUMAN_PRECISION);
        double w = entity.getWidth();
        double h = entity.getHeight();
        double half = w * 0.5 * MathHelper.lerp(p, 0.75, 0.2);
        double halfH = h * MathHelper.lerp(p, 0.38, 0.1);
        double mid = y + h * 0.56;
        return new Box(x - half, mid - halfH, z - half, x + half, mid + halfH, z + half);
    }

    // ── Weapon gating ───────────────────────────────────────────────────────────

    private boolean isWeaponReadyForAim() {
        if (mc.player == null) return false;

        long now = System.currentTimeMillis();
        int selectedSlot = mc.player.getInventory().getSelectedSlot();
        boolean holdingWeapon = CombatItemUtil.isWeapon(mc.player.getMainHandStack());
        boolean slotChanged = lastSelectedSlot != -1 && selectedSlot != lastSelectedSlot;
        boolean weaponJustEquipped = holdingWeapon && !lastHoldingWeapon;

        lastSelectedSlot = selectedSlot;
        lastHoldingWeapon = holdingWeapon;

        if (onlyWeapons.isEnabled() && !holdingWeapon) {
            finalTarget = null;
            weaponSettleUntilMs = 0L;
            RotationManager.stop(this);
            return false;
        }

        if (slotChanged || weaponJustEquipped) {
            finalTarget = null;
            lastTargetSwitchTime = now;
            weaponSettleUntilMs = now + ModuleDelays.clampMs(profileDelay.sample());
            RotationManager.stop(this);
            return false;
        }

        if (now < weaponSettleUntilMs) {
            RotationManager.stop(this);
            return false;
        }

        return true;
    }

    /** Crystal PvP: both obsidian and end crystals in the inventory pause the assist. */
    private boolean hasObsidianAndCrystals() {
        if (mc.player == null) return false;
        boolean hasObby = false;
        boolean hasCrystals = false;
        for (int i = 0; i < mc.player.getInventory().size(); i++) {
            ItemStack stack = mc.player.getInventory().getStack(i);
            if (!stack.isEmpty()) {
                if (stack.isOf(Items.OBSIDIAN)) hasObby = true;
                if (stack.isOf(Items.END_CRYSTAL)) hasCrystals = true;
                if (hasObby && hasCrystals) return true;
            }
        }
        return false;
    }
}
