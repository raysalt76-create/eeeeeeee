package interstellarTikiTiks.apexx.client.module.implementation.combat;

import net.minecraft.util.math.Box;
import interstellarTikiTiks.apexx.client.friend.FriendManager;
import interstellarTikiTiks.apexx.client.module.BooleanSetting;
import interstellarTikiTiks.apexx.client.module.Category;
import interstellarTikiTiks.apexx.client.module.SubCategory;
import interstellarTikiTiks.apexx.client.module.ModeSetting;
import interstellarTikiTiks.apexx.client.module.Module;
import interstellarTikiTiks.apexx.client.module.SliderSetting;
import interstellarTikiTiks.apexx.client.module.implementation.player.BotFilterProvider;
import interstellarTikiTiks.apexx.client.util.ModuleAccess;
import interstellarTikiTiks.apexx.client.util.combat.CombatPrediction;
import interstellarTikiTiks.apexx.client.util.combat.ModuleDelays;
import interstellarTikiTiks.apexx.client.util.RotationManager;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.BowItem;
import net.minecraft.item.CrossbowItem;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

import java.util.function.Supplier;

public class BowAimbot extends Module {
    private static final double ARROW_GRAVITY = 0.05;
    private static final float ARROW_DRAG = 0.99F;
    private static final int MAX_SIMULATION_TICKS = 1200;
    private static final double EPSILON = 0.0001;

    private final SliderSetting range = new SliderSetting("Range", 60.0, 10.0, 100.0, 0)
            .setDescription("Farthest target, in blocks, that the bow aim locks onto.");
    private final SliderSetting fov = new SliderSetting("FOV", 100, 10, 360, 0)
            .setDescription("Only targets within this many degrees of where you are looking are considered. Lower values make it help only on shots you are already lining up.");

    private final ModeSetting priority = new ModeSetting("Priority", "Distance", "Angle", "Health")
            .setDescription("Which valid target is chosen: nearest, closest to your crosshair, or lowest health.");
    private final ModeSetting targetPart = new ModeSetting("Aim At", "Auto", "Chest", "Head", "Body", "Legs")
            .setDescription("Which part of the target to aim at. Auto picks based on distance and height difference; Chest is the most forgiving; Head, Body and Legs are fixed points.");
    private final SliderSetting autoHeightThreshold = new SliderSetting("Auto Height Diff", 3.0, 0.0, 50.0, 0)
            .setDescription("For Auto: height difference in blocks above which the aim point changes (e.g. aim at the legs of someone far above you).");
    private final SliderSetting autoDistanceThreshold = new SliderSetting("Auto Distance", 12.0, 0.0, 50.0, 0)
            .setDescription("For Auto: distance in blocks beyond which the aim point changes to account for longer flight time.");

    private final BooleanSetting visibleOnly = new BooleanSetting("Visible Only", true)
            .setDescription("Only aim at targets you can actually see, never through walls or terrain.");
    private final BooleanSetting ignoreFriends = new BooleanSetting("Ignore Friends", true)
            .setDescription("Never aim at players on your friends list.");
    private final BooleanSetting ignoreTeammates = new BooleanSetting("Ignore Teammates", false)
            .setDescription("Never aim at players on your scoreboard team (Bedwars team colours).");
    private final BooleanSetting ignoreBots = new BooleanSetting("Ignore Bots", true)
            .setDescription("Skip entities the AntiBot module marks as bots.");
    private final BooleanSetting playersOnly = new BooleanSetting("Players Only", false)
            .setDescription("Only aim at players.");

    private final BooleanSetting prediction = new BooleanSetting("Prediction", false)
            .setDescription("Lead the target to where it will be when the arrow arrives (its movement plus a falling arc while airborne), and aim against your own momentum.");
    private final SliderSetting minCharge = new SliderSetting("Min Charge %", 0, 0, 100, 0)
            .setDescription("Bow only: start aiming once the draw has reached this charge. 0 aims from the first moment of the draw.");
    private final BooleanSetting tridents = new BooleanSetting("Tridents", false)
            .setDescription("Also aim while charging a trident throw.");
    private final BooleanSetting crossbows = new BooleanSetting("Crossbows", true)
            .setDescription("Also aim while holding a loaded crossbow.");
    private final BooleanSetting silent = new BooleanSetting("Silent", false)
            .setDescription("Turn only the rotation the server sees, not your camera. Off keeps the aim visible like a normal mouse movement.");
    private final BooleanSetting autoRelease = new BooleanSetting("Auto Release", false)
            .setDescription("Bow only: release automatically at full charge once the aim has settled on the target.");

    private final ModeSetting rotationMode = new ModeSetting("Rotation", "Smooth", "Sine", "Linear")
            .setDescription("Shape of the camera turn onto the solved shot angle: Smooth eases in and out, Sine follows a sine curve, Linear turns at a constant rate.");
    private final SliderSetting rotationSpeed = new SliderSetting("Rotation Speed", 15.5, 1.0, 20.0, 1)
            .setDescription("How fast your camera tracks the solved angle. Higher keeps up with fast-moving targets; lower looks more like hand aim.");
    private final SliderSetting randomness = new SliderSetting("Aim Randomness", 0.25, 0.0, 1.0, 2)
            .setDescription("Small random offset added to the solved angle so the aim is not pixel-perfect every shot. 0 is exact; higher is more human but less accurate.");

    private final SliderSetting reactionDelay = new SliderSetting("Reaction Delay (ms)", 80, 0, 400, 0).withJitter(25)
            .setSection("Delays")
            .setDescription("Wait after a new target is picked before the aim starts turning toward it, in milliseconds. 0 = start turning at once.");
    private final SliderSetting retargetDelay = new SliderSetting("Retarget Delay (ms)", 250, 0, 1500, 0).withJitter(60)
            .setSection("Delays")
            .setDescription("Wait before switching to a different target while the current one is still alive but out-scored or out of view, in milliseconds.");
    private final SliderSetting releaseDelay = new SliderSetting("Release Delay (ms)", 90, 0, 600, 0).withJitter(30)
            .setDependsOn("Auto Release")
            .setSection("Delays")
            .setDescription("Auto Release: wait after full charge and a settled aim before letting go, in milliseconds.");

    private static final double MAX_FLIGHT_TICKS = 120.0;

    /** Target search interval: the scan (with a line-of-sight raycast per entity) does not need to run every frame. */
    private static final long SCAN_INTERVAL_MS = 50L;

    private Entity currentTarget;
    private Entity acquiredTarget;
    private long aimReadyAtMs;
    private long lastScanMs;
    // Solved pitch cache: the charge and the target only change per game tick, so one solve per tick suffices.
    private Entity pitchTarget;
    private long pitchTick = Long.MIN_VALUE;
    private int pitchUse = -1;
    private float cachedPitch;
    private Vec3d cachedLead = Vec3d.ZERO;
    private long retargetAtMs = -1L;
    private long releaseAtMs = -1L;
    private boolean aimingActive;

    public BowAimbot() {
        super("BowAimbot", "While you draw a bow (or charge a trident, or hold a loaded crossbow), BowAimbot picks the best visible target in range, simulates the projectile's flight (gravity, drag and, with Prediction, the target's movement) and smoothly turns your camera to the angle that lands the shot. You still release yourself unless Auto Release is on.", Category.COMBAT);
        setSubCategory(SubCategory.SMP);
        addSetting(range);
        addSetting(fov);
        addSetting(priority);
        addSetting(targetPart);
        addSetting(autoHeightThreshold);
        addSetting(autoDistanceThreshold);
        addSetting(visibleOnly);
        addSetting(ignoreFriends);
        addSetting(ignoreTeammates);
        addSetting(ignoreBots);
        addSetting(playersOnly);
        addSetting(prediction);
        addSetting(minCharge);
        addSetting(tridents);
        addSetting(crossbows);
        addSetting(silent);
        addSetting(autoRelease);
        addSetting(rotationMode);
        addSetting(rotationSpeed);
        addSetting(randomness);
        addSetting(reactionDelay);
        addSetting(retargetDelay);
        addSetting(releaseDelay);
    }

    @Override
    public void onDisable() {
        stopAim();
        resetTargeting();
    }

    private void resetTargeting() {
        currentTarget = null;
        acquiredTarget = null;
        lastScanMs = 0L;
        pitchTarget = null;
        pitchTick = Long.MIN_VALUE;
        cachedLead = Vec3d.ZERO;
        retargetAtMs = -1L;
        releaseAtMs = -1L;
        aimingActive = false;
    }

    private void stopAim() {
        RotationManager.stop(this);
        aimingActive = false;
    }

    @Override
    protected void onRuntimeStateReleased() {
        stopAim();
        resetTargeting();
    }

    /** Auto Release runs on the client tick: it lets go of a fully drawn bow once the aim has settled. */
    @Override
    public void onTick() {
        if (!isEnabled() || !autoRelease.isEnabled() || mc.player == null || mc.interactionManager == null
                || mc.currentScreen != null) {
            releaseAtMs = -1L;
            return;
        }
        boolean drawingBow = mc.player.isUsingItem() && mc.player.getActiveItem().getItem() instanceof BowItem;
        if (!drawingBow || !aimingActive || currentTarget == null || !currentTarget.isAlive()
                || BowItem.getPullProgress(mc.player.getItemUseTime()) < 1.0f
                || !RotationManager.isRotationComplete(1.5f)) {
            releaseAtMs = -1L;
            return;
        }
        long now = System.currentTimeMillis();
        if (releaseAtMs < 0L) releaseAtMs = now + ModuleDelays.clampMs(releaseDelay.sample());
        if (now >= releaseAtMs) {
            mc.interactionManager.stopUsingItem(mc.player);
            releaseAtMs = -1L;
        }
    }

    @Override
    public void onRender() {
        if (!isEnabled()) return;
        evaluateBowAimbot();
    }

    private void evaluateBowAimbot() {
        if (mc.player == null || mc.world == null) {
            stopAim();
            currentTarget = null;
            return;
        }

        if (launchSpeed() <= 0.0) {
            stopAim();
            currentTarget = null;
            retargetAtMs = -1L;
            return;
        }

        long now = System.currentTimeMillis();
        boolean targetGone = currentTarget == null || !currentTarget.isAlive();
        if (targetGone || now - lastScanMs >= SCAN_INTERVAL_MS) {
            Entity best = findTarget();
            if (!targetGone && best != currentTarget && isValidTarget(currentTarget)) {
                // Switching away from a still-valid target waits out the retarget delay first.
                if (retargetAtMs < 0L) retargetAtMs = now + ModuleDelays.clampMs(retargetDelay.sample());
                if (now < retargetAtMs) best = currentTarget;
                else retargetAtMs = -1L;
            } else {
                retargetAtMs = -1L;
            }
            currentTarget = best;
            lastScanMs = now;
        }

        if (currentTarget != acquiredTarget) {
            // A newly picked target: wait the reaction delay before turning toward it.
            acquiredTarget = currentTarget;
            aimReadyAtMs = now + ModuleDelays.clampMs(reactionDelay.sample());
        }

        if (currentTarget != null && now >= aimReadyAtMs) {
            performAiming();
        } else {
            stopAim();
        }
    }

    /**
     * Launch speed of what is being aimed right now in blocks per tick, or 0 when nothing aim-worthy is held:
     * a drawn bow (past Min Charge), a charging trident (Tridents) or a loaded crossbow (Crossbows).
     */
    private double launchSpeed() {
        if (mc.player == null) return 0.0;
        if (mc.player.isUsingItem()) {
            ItemStack active = mc.player.getActiveItem();
            if (active.getItem() instanceof BowItem) {
                float pull = BowItem.getPullProgress(mc.player.getItemUseTime());
                if (pull * 100.0f < minCharge.getValue()) return 0.0;
                return Math.max(0.1f, pull) * 3.0;
            }
            if (active.isOf(Items.TRIDENT)) return tridents.isEnabled() ? 2.5 : 0.0;
        }
        ItemStack main = mc.player.getMainHandStack();
        if (crossbows.isEnabled() && main.getItem() instanceof CrossbowItem && CrossbowItem.isCharged(main)) {
            return 3.15;
        }
        return 0.0;
    }

    private void performAiming() {
        Supplier<Vec3d> targetSupplier = () -> {
            Entity target = currentTarget;
            if (target == null || !target.isAlive()) return null;
            float solvedPitch = cachedPitchFor(target);
            return getAimPointForPitch(target, solvedPitch);
        };

        RotationManager.RotationMode mode;
        try {
            mode = RotationManager.RotationMode.valueOf(rotationMode.getCurrentMode().toUpperCase());
        } catch (Exception e) {
            mode = RotationManager.RotationMode.SMOOTH;
        }

        RotationManager.setRotationSupplier(
                this,
                RotationManager.Priority.HIGH,
                targetSupplier,
                rotationSpeed.getValue(),
                mode,
                randomness.getValue(),
                silent.isEnabled(),
                false);
        aimingActive = true;
    }

    private Entity findTarget() {
        Entity bestEntity = null;
        double bestScore = Double.MAX_VALUE;
        double maxAngle = fov.getValue() / 2.0;

        // Bounded scan, sized from the Range setting rather than iterating the whole
        // world and rejecting almost everything on the distance check two lines down.
        Box searchBox = mc.player.getBoundingBox().expand(range.getValue() + 1.0);
        for (Entity entity : mc.world.getOtherEntities(mc.player, searchBox)) {
            if (!isValidTarget(entity))
                continue;

            double distance = mc.player.distanceTo(entity);
            if (distance > range.getValue())
                continue;

            if (visibleOnly.isEnabled() && !mc.player.canSee(entity))
                continue;

            double angle = getAngleToEntity(entity);
            if (angle > maxAngle)
                continue;

            double score = switch (priority.getCurrentMode()) {
                case "Angle" -> angle;
                case "Health" -> ((LivingEntity) entity).getHealth() + ((LivingEntity) entity).getAbsorptionAmount();
                default -> distance;
            };
            if (score < bestScore) {
                bestScore = score;
                bestEntity = entity;
            }
        }
        return bestEntity;
    }

    private boolean isValidTarget(Entity entity) {
        if (!(entity instanceof LivingEntity) || entity == mc.player || !entity.isAlive()) {
            return false;
        }

        if (!interstellarTikiTiks.apexx.client.util.combat.StickyTargets.allows(this, entity)) {
            return false;
        }
        if (playersOnly.isEnabled() && !(entity instanceof PlayerEntity)) {
            return false;
        }
        if (entity instanceof PlayerEntity player) {
            if (player.isSpectator()) return false;
            if (ignoreFriends.isEnabled() && FriendManager.getInstance().isFriend(player.getUuid())) return false;
        }
        if (ignoreTeammates.isEnabled() && mc.player.isTeammate(entity)) {
            return false;
        }
        if (ignoreBots.isEnabled()) {
            BotFilterProvider bots = ModuleAccess.getEnabledHook(BotFilterProvider.class);
            if (bots != null && bots.isBot(entity)) return false;
        }

        return true;
    }

    private Vec3d getAimPointForPitch(Entity target, float pitch) {
        Vec3d eyePos = mc.player.getEyePos();
        Vec3d predictedPos = getPredpos(target);

        double dx = predictedPos.x - eyePos.x;
        double dz = predictedPos.z - eyePos.z;
        double horizontalDist = Math.sqrt(dx * dx + dz * dz);
        double yOffset = Math.tan(Math.toRadians(-pitch)) * horizontalDist;

        return new Vec3d(predictedPos.x, eyePos.y + yOffset, predictedPos.z);
    }

    private Vec3d getPredpos(Entity target) {
        double t = mc.getRenderTickCounter().getTickProgress(false);
        double x = MathHelper.lerp(t, target.lastRenderX, target.getX());
        double y = MathHelper.lerp(t, target.lastRenderY, target.getY());
        double z = MathHelper.lerp(t, target.lastRenderZ, target.getZ());
        Vec3d pos = new Vec3d(x, y, z);
        return target == pitchTarget ? pos.add(cachedLead) : pos;
    }

    private float cachedPitchFor(Entity target) {
        long tick = mc.world == null ? 0L : mc.world.getTime();
        int use = mc.player == null ? -1 : mc.player.getItemUseTime();
        if (target == pitchTarget && tick == pitchTick && use == pitchUse) return cachedPitch;
        float velocity = (float) Math.max(0.1, launchSpeed());
        pitchTarget = null; // getPredpos below must not add a stale lead from the previous solve
        cachedLead = prediction.isEnabled() ? computeLead(target, velocity) : Vec3d.ZERO;
        pitchTarget = target;
        cachedPitch = solvePitchForTarget(target, velocity);
        pitchTarget = target;
        pitchTick = tick;
        pitchUse = use;
        return cachedPitch;
    }

    /**
     * Prediction: offset from the target's current position to where it will be when the projectile arrives
     * (its per-tick velocity times the flight time, plus a falling arc while airborne), minus the momentum the
     * projectile inherits from you. Iterated a few times because the flight time depends on the lead.
     */
    private Vec3d computeLead(Entity target, float speed) {
        Vec3d eye = mc.player.getEyePos();
        Vec3d base = getPredpos(target).add(0.0, getYOffset(target), 0.0);
        Vec3d targetVel = target instanceof PlayerEntity p ? CombatPrediction.perTickVelocityVec(p) : target.getVelocity();
        Vec3d ownVel = mc.player.getVelocity();
        double ownY = mc.player.isOnGround() ? 0.0 : ownVel.y;
        Vec3d lead = Vec3d.ZERO;
        for (int i = 0; i < 3; i++) {
            double ticks = flightTicks(eye, base.add(lead), speed);
            if (!Double.isFinite(ticks)) break;
            double fall = 0.0;
            if (!target.isOnGround()) {
                double vy = targetVel.y;
                int whole = (int) Math.min(MAX_FLIGHT_TICKS, Math.ceil(ticks));
                for (int t = 0; t < whole; t++) {
                    fall += vy;
                    vy = (vy - 0.08) * 0.98;
                }
                fall = Math.max(fall, -24.0);
            }
            lead = new Vec3d((targetVel.x - ownVel.x) * ticks, fall - ownY * ticks, (targetVel.z - ownVel.z) * ticks);
        }
        return lead;
    }

    /** Ticks for the projectile to cover the horizontal distance, from v_h * (1 - d^t) / (1 - d) = x. */
    private double flightTicks(Vec3d origin, Vec3d point, double speed) {
        double dx = point.x - origin.x;
        double dz = point.z - origin.z;
        double dist = Math.sqrt(dx * dx + dz * dz);
        double pitch = Math.atan2(point.y - origin.y, Math.max(1.0e-4, dist));
        double vh = speed * Math.cos(pitch);
        if (vh <= 1.0e-4) return Double.NaN;
        double arg = 1.0 - dist * (1.0 - ARROW_DRAG) / vh;
        if (arg <= 0.0) return Double.NaN;
        double t = Math.log(arg) / Math.log(ARROW_DRAG);
        return Math.min(MAX_FLIGHT_TICKS, Math.max(0.0, t));
    }

    private float solvePitchForTarget(Entity target, float velocity) {
        Vec3d eyePos = mc.player.getEyePos();
        Vec3d predictedPos = getPredpos(target);

        double targetY = predictedPos.y + getYOffset(target);
        double dx = predictedPos.x - eyePos.x;
        double dz = predictedPos.z - eyePos.z;
        double dy = targetY - eyePos.y;
        double horizontalDist = Math.sqrt(dx * dx + dz * dz);

        float directPitch = (float) Math.toDegrees(Math.atan2(dy, horizontalDist));
        double directHitY = simulateArrow(velocity, directPitch, horizontalDist);

        if (Math.abs(directHitY - dy) < target.getHeight() * 0.6) {
            return -directPitch;
        }

        return -solveLowArc(velocity, horizontalDist, dy, directPitch);
    }

    // Lowest-angle solution: gravity only ever pushes the required pitch above the direct line, so
    // scan upward from it and bisect the first crossing. A global bisect over -89..89 could land on
    // the high lob solution, which made the crosshair swing far up before the shot.
    private float solveLowArc(float velocity, double horizontalDist, double dy, float directPitch) {
        float lo = directPitch;
        float hi = Float.NaN;
        for (float p = directPitch + 1.0f; p <= 89.0f; p += 1.0f) {
            double hitY = simulateArrow(velocity, p, horizontalDist);
            if (!Double.isInfinite(hitY) && hitY >= dy) {
                hi = p;
                break;
            }
            lo = p;
        }
        if (Float.isNaN(hi)) {
            return binSearch(velocity, horizontalDist, dy, directPitch);
        }
        for (int i = 0; i < 20; i++) {
            float mid = (lo + hi) / 2.0f;
            double hitY = simulateArrow(velocity, mid, horizontalDist);
            if (!Double.isInfinite(hitY) && hitY >= dy) {
                hi = mid;
            } else {
                lo = mid;
            }
        }
        return hi;
    }

    private double getYOffset(Entity target) {
        String part = targetPart.getCurrentMode();

        if (part.equals("Auto")) {
            part = determinePart(target);
        }

        return switch (part) {
            case "Head" -> target.getHeight() * 0.85;
            case "Body" -> target.getHeight() * 0.45;
            case "Legs" -> target.getHeight() * 0.1;
            default -> target.getHeight() * 0.5;
        };
    }

    private String determinePart(Entity target) {
        if (mc.player == null || target == null) {
            return "Head";
        }
        // "Auto" aim-point: far OR sharply above/below -> Head (clean top hitbox, best for arced
        // shots); close and roughly level -> Body (largest, most forgiving area). The two sliders
        // are the crossover thresholds, which is what the Auto mode always claimed to use.
        double dist = mc.player.distanceTo(target);
        double heightDiff = Math.abs(target.getY() - mc.player.getY());
        if (dist >= autoDistanceThreshold.getValue() || heightDiff >= autoHeightThreshold.getValue()) {
            return "Head";
        }
        return "Chest";
    }

    private float binSearch(float velocity, double horizontalDist, double targetY, float directPitch) {
        float minPitch = -89.0f;
        float maxPitch = 89.0f;
        float bestPitch = directPitch;
        double minError = Double.MAX_VALUE;

        for (int i = 0; i < 50; i++) {
            float midPitch = (minPitch + maxPitch) / 2.0f;
            double hitY = simulateArrow(velocity, midPitch, horizontalDist);

            if (Double.isInfinite(hitY)) {
                maxPitch = midPitch;
                continue;
            }

            double error = Math.abs(hitY - targetY);
            if (error < minError) {
                minError = error;
                bestPitch = midPitch;
            }

            if (error < 0.01) {
                break;
            }

            if (hitY < targetY) {
                minPitch = midPitch;
            } else {
                maxPitch = midPitch;
            }
        }

        return bestPitch;
    }

    private double simulateArrow(float speed, float pitch, double targetDist) {
        double radPitch = Math.toRadians(pitch);
        double vx = speed * Math.cos(radPitch);
        double vy = speed * Math.sin(radPitch);

        double x = 0;
        double y = 0;

        for (int tick = 0; tick < MAX_SIMULATION_TICKS; tick++) {
            double prevX = x;
            double prevY = y;

            x += vx;
            y += vy;

            if (x >= targetDist) {
                double delta = x - prevX;
                if (Math.abs(delta) < EPSILON) {
                    return y;
                }

                double ratio = (targetDist - prevX) / delta;
                return prevY + (y - prevY) * ratio;
            }

            vx *= ARROW_DRAG;
            vy *= ARROW_DRAG;
            vy -= ARROW_GRAVITY;
        }

        return Double.NEGATIVE_INFINITY;
    }

    private double getAngleToEntity(Entity entity) {
        Vec3d playerPos = mc.player.getEyePos();
        Vec3d entityPos = entity.getBoundingBox().getCenter();
        double deltaX = entityPos.x - playerPos.x;
        double deltaZ = entityPos.z - playerPos.z;
        float targetYaw = (float) Math.toDegrees(Math.atan2(deltaZ, deltaX)) - 90.0f;
        return Math.abs(MathHelper.wrapDegrees(mc.player.getYaw() - targetYaw));
    }
}
