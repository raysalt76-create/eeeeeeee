package interstellarTikiTiks.apexx.client.module.implementation.combat;

import interstellarTikiTiks.apexx.client.friend.FriendManager;
import interstellarTikiTiks.apexx.client.module.BooleanSetting;
import interstellarTikiTiks.apexx.client.module.Category;
import interstellarTikiTiks.apexx.client.module.ModeSetting;
import interstellarTikiTiks.apexx.client.module.Module;
import interstellarTikiTiks.apexx.client.module.SliderSetting;
import interstellarTikiTiks.apexx.client.module.hooks.AttackHandleAware;
import interstellarTikiTiks.apexx.client.module.hooks.OutgoingPacketInterceptor;
import interstellarTikiTiks.apexx.client.util.RotationManager;
import interstellarTikiTiks.apexx.client.util.combat.ModuleDelays;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.network.packet.Packet;
import net.minecraft.network.packet.c2s.play.PlayerInteractEntityC2SPacket;
import net.minecraft.util.hit.EntityHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

import java.util.concurrent.ThreadLocalRandom;

/**
 * KnockbackDisplacement - steers the knockback of your sprint hits. Vanilla takes the push direction from the
 * attacker's yaw at the instant of the hit, so turning a little before (or on) the swing sends the target toward
 * the chosen heading: away, sideways, toward you, upward, a custom offset, or toward a nearby hazard (void, lava,
 * cobweb, a drop). Turns go through the shared rotation engine at a human speed after a reaction delay, with a
 * per-engagement heading error, an occasional skipped engagement and a cooldown between steers.
 */
public class KnockbackDisplacement extends Module implements AttackHandleAware, OutgoingPacketInterceptor {

    private final ModeSetting direction = new ModeSetting("Vector Mode", "Away", "Towards", "Left", "Right", "Upward", "Custom")
            .setDescription("Where your hits push the target. Away: straight back (vanilla). Towards: back toward you. Left/Right: sideways off your line. Upward: a steep look so the push carries more lift. Custom: your own yaw/pitch offset.");
    private final SliderSetting customYawOffset = new SliderSetting("Custom Yaw Offset", 0.0, -180.0, 180.0, 1)
            .setDependsOn("Vector Mode:Custom")
            .setDescription("Custom mode: horizontal angle added to your current yaw, in degrees.");
    private final SliderSetting customPitchOffset = new SliderSetting("Custom Pitch Offset", 0.0, -90.0, 90.0, 1)
            .setDependsOn("Vector Mode:Custom")
            .setDescription("Custom mode: vertical angle added to your current pitch, in degrees.");
    private final SliderSetting actionCooldownMs = new SliderSetting("Cooldown (ms)", 750, 100, 1000, 0)
            .withJitter(150)
            .setSection("Delays")
            .setDescription("Minimum time between two steered hits. Steering every single hit is a pattern; a gap keeps it occasional.");
    private final SliderSetting reactionMs = new SliderSetting("Reaction Delay (ms)", 90, 0, 400, 0)
            .withJitter(30)
            .setSection("Delays")
            .setDescription("How long a target must sit under your crosshair before the pre-hit turn begins, like a player deciding where to push.");
    private final SliderSetting aimSpeed = new SliderSetting("Aim Speed", 8, 1, 15, 1)
            .setSection("Humanization")
            .setDescription("How quickly the turn toward the knockback heading is made (1 slow - 15 fast). Around 6-10 looks like a deliberate flick.");
    private final SliderSetting aimHumanization = new SliderSetting("Aim Randomness", 0.45, 0.0, 1.0, 2)
            .setSection("Humanization")
            .setDescription("Human imperfection in the turn path: curve, overshoot and settling. 0 = perfectly smooth.");
    private final SliderSetting headingSpread = new SliderSetting("Heading Spread", 6.0, 0.0, 20.0, 1)
            .setSection("Humanization")
            .setDescription("Random error, in degrees, added to the steered heading each engagement so pushes never land on the exact same angle.");
    private final SliderSetting skipChance = new SliderSetting("Skip Chance %", 12, 0, 50, 0)
            .setSection("Humanization")
            .setDescription("Chance to leave an engagement unsteered, the way a player does not redirect every hit.");
    private final BooleanSetting snapAim = new BooleanSetting("Snap Aim", false)
            .setBlatant(true)
            .setDescription("BLATANT: turns at maximum speed with no imperfection. Instant turns on the hit tick are easy for rotation checks to flag.");
    private final BooleanSetting hazardOnly = new BooleanSetting("Hazard Only", false)
            .setDescription("Only steers when a hazard (void, lava, cobweb or a big drop) lies along the push within Scan Distance.");
    private final SliderSetting hazardScanRange = new SliderSetting("Scan Distance", 5.0, 1.0, 8.0, 1)
            .setDependsOn("Hazard Only")
            .setDescription("How far, in blocks, the push path is scanned for hazards.");
    private final SliderSetting minFallDrop = new SliderSetting("Minimum Drop", 4.0, 2.0, 12.0, 1)
            .setDependsOn("Hazard Only")
            .setDescription("Smallest drop, in blocks, that counts as a fall hazard.");
    private final BooleanSetting avoidVoid = new BooleanSetting("Detect Void", true)
            .setDependsOn("Hazard Only")
            .setDescription("Counts the void (no floor below) as a hazard.");
    private final BooleanSetting avoidLava = new BooleanSetting("Detect Lava", true)
            .setDependsOn("Hazard Only")
            .setDescription("Counts lava as a hazard.");
    private final BooleanSetting avoidCobweb = new BooleanSetting("Detect Cobweb", true)
            .setDependsOn("Hazard Only")
            .setDescription("Counts cobwebs as a hazard.");
    private final BooleanSetting silentAim = new BooleanSetting("Silent Aim", false)
            .setDescription("Turns only the rotation sent to the server toward the heading and leaves your camera where it is. Off: your camera turns too, which is what a player would see.");

    private long cooldownUntilMs = 0L;
    private int seenTargetId = -1;
    private long reactionReadyAtMs = 0L;
    private int decidedTargetId = -1;
    private boolean skipThisTarget = false;
    private float headingError = 0f;
    /** The steer request releases itself (supplier returns null) once this passes. */
    private long steerHoldUntilMs = 0L;
    private static final long STEER_HOLD_MS = 250L;

    public KnockbackDisplacement() {
        super("KnockbackDisplacement", "Steers the knockback of your sprint hits toward a chosen heading (away, sideways, toward you, upward, custom) or into a nearby hazard like void, lava or cobwebs, turning at a human speed just before the hit.", Category.COMBAT);
        addSetting(direction);
        addSetting(customYawOffset);
        addSetting(customPitchOffset);
        addSetting(actionCooldownMs);
        addSetting(reactionMs);
        addSetting(aimSpeed);
        addSetting(aimHumanization);
        addSetting(headingSpread);
        addSetting(skipChance);
        addSetting(snapAim);
        addSetting(hazardOnly);
        addSetting(hazardScanRange);
        addSetting(minFallDrop);
        addSetting(avoidVoid);
        addSetting(avoidLava);
        addSetting(avoidCobweb);
        addSetting(silentAim);
    }

    @Override
    public void onTick() {
        // LEAD: knockback direction is taken from the attacker's yaw at the
        // instant of a sprint hit, so the turn can be made before the hit instead of on it. Pre-steer while the
        // target is under the crosshair and in reach, once the reaction delay has passed.
        if (!isEnabled() || mc.player == null || mc.world == null) return;
        long now = System.currentTimeMillis();
        if (steerHoldUntilMs != 0L && now > steerHoldUntilMs) {
            steerHoldUntilMs = 0L;
            RotationManager.clearTarget(this);
        }
        if (!(mc.crosshairTarget instanceof EntityHitResult ehr) || !(ehr.getEntity() instanceof PlayerEntity target)
                || mc.player.distanceTo(target) > 3.0
                || FriendManager.getInstance().isFriend(target)
                || (hazardOnly.isEnabled() && !isHazardNearby(target))) {
            seenTargetId = -1;
            return;
        }
        if (target.getId() != seenTargetId) {
            seenTargetId = target.getId();
            reactionReadyAtMs = now + (snapAim.isEnabled() ? 0L : ModuleDelays.clampMs(reactionMs.sample()));
        }
        if (now < cooldownUntilMs || now < reactionReadyAtMs || !decide(target)) return;
        steerToward(target);
    }

    @Override
    public void onDisable() {
        RotationManager.stop(this);
        resetState();
    }

    @Override
    protected void onRuntimeStateReleased() {
        RotationManager.stop(this);
        resetState();
    }

    private void resetState() {
        cooldownUntilMs = 0L;
        seenTargetId = -1;
        decidedTargetId = -1;
        skipThisTarget = false;
        steerHoldUntilMs = 0L;
    }

    @Override
    public boolean handleAttack() {
        if (!isEnabled() || mc.player == null || mc.world == null) return false;
        if (mc.crosshairTarget instanceof EntityHitResult ehr && ehr.getEntity() instanceof LivingEntity target) {
            processDisplacement(target);
        }
        return false;
    }

    @Override
    public boolean handleOutgoingPacket(Packet<?> packet) {
        if (!isEnabled() || mc.player == null || mc.world == null) return false;
        if (packet instanceof PlayerInteractEntityC2SPacket
                && mc.crosshairTarget instanceof EntityHitResult ehr && ehr.getEntity() instanceof LivingEntity target) {
            processDisplacement(target);
        }
        return false;
    }

    private void processDisplacement(LivingEntity target) {
        if (target == null || target == mc.player || !target.isAlive()) return;
        if (target instanceof PlayerEntity && FriendManager.getInstance().isFriend(target)) return;
        long now = System.currentTimeMillis();
        if (now < cooldownUntilMs || !decide(target)) return;
        if (hazardOnly.isEnabled() && !isHazardNearby(target)) return;
        if (steerToward(target)) {
            cooldownUntilMs = now + ModuleDelays.clampMs(actionCooldownMs.sample());
            decidedTargetId = -1;
        }
    }

    /** One skip roll and heading error per engagement with a given target. */
    private boolean decide(LivingEntity target) {
        if (target.getId() != decidedTargetId) {
            decidedTargetId = target.getId();
            ThreadLocalRandom rnd = ThreadLocalRandom.current();
            skipThisTarget = !snapAim.isEnabled() && rnd.nextDouble() * 100.0 < skipChance.getValue();
            double spread = snapAim.isEnabled() ? 0.0 : headingSpread.getValue();
            headingError = (float) ((rnd.nextDouble() * 2.0 - 1.0) * spread);
        }
        return !skipThisTarget;
    }

    /**
     * Point the player at this engagement's displacement heading. Vanilla takes the sprint-knockback vector
     * from the attacker's yaw, so the heading is projected out from the eye into a look-at point for the
     * rotation engine.
     */
    private boolean steerToward(LivingEntity target) {
        float[] angles = calculateDisplacementAngles(target);
        if (angles == null || mc.player == null) return false;

        double yawRad = Math.toRadians(angles[0]);
        double pitchRad = Math.toRadians(angles[1]);
        double cosPitch = Math.cos(pitchRad);
        Vec3d eye = mc.player.getEyePos();
        final Vec3d look = eye.add(
                -Math.sin(yawRad) * cosPitch * 4.0,
                -Math.sin(pitchRad) * 4.0,
                Math.cos(yawRad) * cosPitch * 4.0);

        double strength = snapAim.isEnabled() ? 25.0 : aimSpeed.getValue();
        double imperfection = snapAim.isEnabled() ? 0.0 : aimHumanization.getValue();
        steerHoldUntilMs = System.currentTimeMillis() + STEER_HOLD_MS;
        RotationManager.aimAt(this, RotationManager.Priority.HIGH,
                () -> System.currentTimeMillis() > steerHoldUntilMs ? null : look, strength, imperfection, silentAim.isEnabled());
        return true;
    }

    private float[] calculateDisplacementAngles(Entity target) {
        if (mc.player == null) return null;

        double dx = target.getX() - mc.player.getX();
        double dz = target.getZ() - mc.player.getZ();
        float baseYaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0);

        float yaw;
        float pitch = mc.player.getPitch();
        switch (direction.getCurrentMode().toUpperCase()) {
            case "LEFT" -> yaw = baseYaw - 90.0f;
            case "RIGHT" -> yaw = baseYaw + 90.0f;
            case "UPWARD" -> {
                yaw = baseYaw;
                pitch = -70.0f;
            }
            case "TOWARDS" -> yaw = baseYaw + 180.0f;
            case "CUSTOM" -> {
                yaw = mc.player.getYaw() + (float) customYawOffset.getValue();
                pitch = mc.player.getPitch() + (float) customPitchOffset.getValue();
            }
            default -> yaw = baseYaw;
        }
        yaw = MathHelper.wrapDegrees(yaw + headingError);
        pitch = MathHelper.clamp(pitch, -90.0f, 90.0f);
        return new float[]{yaw, pitch};
    }

    private boolean isHazardNearby(Entity target) {
        if (mc.world == null) return false;
        double maxDist = hazardScanRange.getValue();
        int steps = Math.max(1, (int) Math.floor(maxDist / 0.5));
        Vec3d lookVec = mc.player.getRotationVec(1.0f);
        for (int i = 1; i <= steps; i++) {
            double stepDist = i * 0.5;
            if (checkHazardAt(target.getX() + lookVec.x * stepDist, target.getY(), target.getZ() + lookVec.z * stepDist)) {
                return true;
            }
        }
        return false;
    }

    private boolean checkHazardAt(double x, double y, double z) {
        if (mc.world == null) return false;
        int blockX = MathHelper.floor(x);
        int blockZ = MathHelper.floor(z);
        int startY = MathHelper.floor(y + 1.0);
        int bottom = mc.world.getBottomY();
        int minY = Math.max(bottom, MathHelper.floor(y) - 64);

        BlockPos.Mutable mutable = new BlockPos.Mutable();
        for (int checkY = startY; checkY >= minY; checkY--) {
            mutable.set(blockX, checkY, blockZ);
            BlockState state = mc.world.getBlockState(mutable);
            if (avoidLava.isEnabled() && state.isOf(Blocks.LAVA)) return true;
            if (avoidCobweb.isEnabled() && state.isOf(Blocks.COBWEB)) return true;
            if (!state.getCollisionShape(mc.world, mutable).isEmpty()) {
                // Floor found: its top is checkY + 1, so the drop is the distance from the target's feet to it.
                return y - (checkY + 1) >= minFallDrop.getValue();
            }
        }
        // No floor in the scanned column: void when the scan reached the world bottom, else a deep drop.
        if (minY == bottom) return avoidVoid.isEnabled();
        return y - minY >= minFallDrop.getValue();
    }
}
