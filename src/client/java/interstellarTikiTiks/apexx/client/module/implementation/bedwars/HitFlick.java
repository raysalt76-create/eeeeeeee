package interstellarTikiTiks.apexx.client.module.implementation.bedwars;

import interstellarTikiTiks.apexx.client.friend.FriendManager;
import interstellarTikiTiks.apexx.client.module.BooleanSetting;
import interstellarTikiTiks.apexx.client.module.Category;
import interstellarTikiTiks.apexx.client.module.ModeSetting;
import interstellarTikiTiks.apexx.client.module.Module;
import interstellarTikiTiks.apexx.client.module.SliderSetting;
import interstellarTikiTiks.apexx.client.module.hooks.AttackTargetAware;
import interstellarTikiTiks.apexx.client.util.RotationManager;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.hit.EntityHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Turns your facing a few degrees to one side just before a hit, so the knockback the hit deals is
 * pushed off-line (for example towards a bridge edge).
 *
 * <p>Knockback direction follows the attacker's yaw as the server last saw it, and the attack packet is
 * sent before that tick's movement packet, so a flick made after the click never affects that click.
 * The flick therefore starts a short lead before the weapon is recharged, turns through the shared
 * rotation engine at a capped speed (no snap), and is limited to angles at which your ray still hits the
 * target's hitbox within reach. After the hit the angle is held for the rest of that tick and released on
 * the next one.
 */
public class HitFlick extends Module implements AttackTargetAware {

    private enum State { IDLE, FLICKING, RELEASE }

    private final ModeSetting direction = new ModeSetting("Direction", "Right", "Right", "Left", "Random")
            .setDescription("Which side the hit is turned to. Random picks a side per engagement.");
    private final SliderSetting maxAngle = new SliderSetting("Max Angle", 6, 1, 20, 1)
            .setDescription("Largest turn, in degrees. It is always reduced so your ray still lands on the target's hitbox (about 5-10 degrees at close range).");
    private final SliderSetting turnSpeed = new SliderSetting("Turn Speed", 9, 2, 30, 1)
            .setDescription("Most degrees turned per tick while flicking. Lower looks smoother; higher reaches the angle sooner.");
    private final SliderSetting chargeThreshold = new SliderSetting("Charge Threshold %", 90, 50, 100, 0)
            .setDescription("The flick only starts when the weapon will be at least this recharged once the lead has passed, so it lines up with a real hit.");
    private final BooleanSetting weaponOnly = new BooleanSetting("Weapon Only", true)
            .setDescription("Only flick while holding a sword, axe, mace, spear or trident.");
    private final BooleanSetting playersOnly = new BooleanSetting("Players Only", true)
            .setDescription("Only flick for hits on players.");
    private final BooleanSetting ignoreFriends = new BooleanSetting("Ignore Friends", true)
            .setDescription("Never flick on a player from your friend list.");
    private final BooleanSetting silent = new BooleanSetting("Silent", false)
            .setDescription("Turn only the rotation the server sees, leaving your camera where it is. Off moves your view too, which reads as an ordinary small mouse movement.");

    private final SliderSetting lead = new SliderSetting("Lead (ms)", 70, 50, 300, 0).withJitter(15)
            .setSection("Delays")
            .setDescription("How long before the expected hit the turn begins. At least one tick (50 ms) is needed so the server has the new facing before the attack arrives.");
    private final SliderSetting holdLimit = new SliderSetting("Hold Limit (ms)", 300, 100, 1500, 0).withJitter(50)
            .setSection("Delays")
            .setDescription("Give up and turn back if no hit happens within this long after the flick began.");
    private final SliderSetting cooldown = new SliderSetting("Re-Flick Cooldown (ms)", 150, 0, 2000, 0).withJitter(40)
            .setSection("Delays")
            .setDescription("Least time between the end of one flick and the start of the next.");

    private State state = State.IDLE;
    private Entity target;
    private float offset;
    private long deadlineMs;
    private long nextAllowedMs;
    private int sign = 1;

    public HitFlick() {
        super("HitFlick", "Turns your facing a few degrees to one side just before a hit, so the knockback is pushed off-line (e.g. towards a bridge edge). Starts before the hit, turns smoothly, and never leaves the target's hitbox.", Category.BEDWARS);
        addSetting(direction);
        addSetting(maxAngle);
        addSetting(turnSpeed);
        addSetting(chargeThreshold);
        addSetting(weaponOnly);
        addSetting(playersOnly);
        addSetting(ignoreFriends);
        addSetting(silent);
        addSetting(lead);
        addSetting(holdLimit);
        addSetting(cooldown);
    }

    @Override
    public void onEnable() {
        reset();
    }

    @Override
    public void onDisable() {
        RotationManager.clearTarget(this);
        reset();
    }

    @Override
    protected void onRuntimeStateReleased() {
        reset();
    }

    private void reset() {
        state = State.IDLE;
        target = null;
        offset = 0f;
        deadlineMs = 0L;
    }

    @Override
    public void onTick() {
        if (mc.player == null || mc.world == null) {
            reset();
            return;
        }
        long now = System.currentTimeMillis();
        if (state == State.RELEASE) {
            // The hit went out last tick with the flick held for the rest of that tick; a new aim may start now.
            endFlick(now);
            return;
        }
        if (state == State.FLICKING) {
            if (now > deadlineMs || !validTarget(target)) {
                endFlick(now);
                return;
            }
            stepTowardFlick();
            return;
        }
        if (now < nextAllowedMs || mc.currentScreen != null) return;
        if (weaponOnly.isEnabled() && !LeftClicker.isWeapon(mc.player.getMainHandStack())) return;
        HitResult hit = mc.crosshairTarget;
        if (hit == null || hit.getType() != HitResult.Type.ENTITY) return;
        Entity candidate = ((EntityHitResult) hit).getEntity();
        if (!validTarget(candidate)) return;
        float leadTicks = (float) (lead.sample() / 50.0);
        if (mc.player.getAttackCooldownProgress(leadTicks) < chargeThreshold.getValue() / 100.0) return;

        sign = switch (direction.getCurrentMode()) {
            case "Left" -> -1;
            case "Random" -> ThreadLocalRandom.current().nextBoolean() ? 1 : -1;
            default -> 1;
        };
        float base = baseYaw(candidate);
        float pitch = mc.player.getPitch();
        float reachable = largestReachableOffset(candidate, base, pitch, sign * (float) maxAngle.getValue());
        if (Math.abs(reachable) < 1.0f) return;
        target = candidate;
        offset = reachable;
        deadlineMs = now + Math.round(holdLimit.sample());
        state = State.FLICKING;
        stepTowardFlick();
    }

    @Override
    public void onAttack(Entity attacked) {
        if (state != State.FLICKING || attacked == null || attacked != target) return;
        // Keep the reached angle for the rest of this tick; the next tick releases it.
        RotationManager.setPostAttackHold(this, 1, RotationManager.getFinalYaw(), RotationManager.getFinalPitch());
        state = State.RELEASE;
    }

    private void stepTowardFlick() {
        float base = baseYaw(target);
        float pitch = mc.player.getPitch();
        // Re-clamp every tick: both players move, so the angle that still hits shrinks or grows.
        float reachable = largestReachableOffset(target, base, pitch, offset);
        if (Math.abs(reachable) < 1.0f) {
            endFlick(System.currentTimeMillis());
            return;
        }
        RotationManager.stepAim(this, RotationManager.Priority.NORMAL, base + reachable, pitch,
                (float) turnSpeed.getValue(), silent.isEnabled());
    }

    private void endFlick(long now) {
        RotationManager.clearTarget(this);
        nextAllowedMs = now + Math.round(cooldown.sample());
        reset();
    }

    private boolean validTarget(Entity e) {
        if (!(e instanceof LivingEntity living) || !living.isAlive() || e == mc.player) return false;
        if (playersOnly.isEnabled() && !(e instanceof PlayerEntity)) return false;
        if (e instanceof PlayerEntity p && ignoreFriends.isEnabled() && FriendManager.getInstance().isFriend(p.getUuid())) {
            return false;
        }
        return mc.player.squaredDistanceTo(e) <= 49.0;
    }

    private float baseYaw(Entity e) {
        Vec3d eye = mc.player.getEyePos();
        Vec3d center = e.getBoundingBox().getCenter();
        double dx = center.x - eye.x;
        double dz = center.z - eye.z;
        return (float) (MathHelper.atan2(dz, dx) * 180.0 / Math.PI) - 90.0f;
    }

    /** The largest offset from {@code base} towards {@code wanted} whose ray still hits the target in reach. */
    private float largestReachableOffset(Entity e, float base, float pitch, float wanted) {
        float step = 0.5f;
        float s = Math.signum(wanted);
        for (float off = Math.abs(wanted); off >= 0.5f; off -= step) {
            if (rayHits(e, base + s * off, pitch)) return s * off;
        }
        return 0f;
    }

    private boolean rayHits(Entity e, float yaw, float pitch) {
        double reach = mc.player.getEntityInteractionRange();
        Vec3d eye = mc.player.getEyePos();
        Vec3d end = eye.add(mc.player.getRotationVector(pitch, yaw).multiply(reach));
        Box box = e.getBoundingBox().expand(e.getTargetingMargin());
        if (box.contains(eye)) return true;
        Optional<Vec3d> hit = box.raycast(eye, end);
        return hit.isPresent() && eye.distanceTo(hit.get()) <= reach;
    }
}
