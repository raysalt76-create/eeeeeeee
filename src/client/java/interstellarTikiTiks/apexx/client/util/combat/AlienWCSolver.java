package interstellarTikiTiks.apexx.client.util.combat;

import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;

/**
 * Forward solver for the AlienWC dive-boost: when to start the look-down and which tick to throw
 * the wind charge so the blast pushes the glider back up before the feet touch the floor. It rolls
 * the live state forward up to {@link #HORIZON} ticks with the vanilla 1.21.11 glide step
 * ({@link ElytraGlidePhysics#glideVelocity}), block collision, the wind-charge flight and the
 * blast knockback, once per recovery pitch bound.
 *
 * <p>The look-down is modelled as a constant {@code aimSpeed * 5} degrees per tick toward 90, the
 * throw as happening on the first tick the pitch reaches the lock pitch (or a fixed tick), and the
 * pull-up as starting two movements after the throw. The wind-charge flight and blast follow the
 * same constants as {@link ElytraGlidePhysics}.
 *
 * <p>COMPILED only. UNVERIFIED in-game: the real rotation speed of the {@code RotationManager}
 * against the constant-speed look-down model, and the throw-to-knockback latency.
 */
public final class AlienWCSolver {
    private static final MinecraftClient mc = MinecraftClient.getInstance();

    /** Projectile speed of the wind charge. */
    public static final double CHARGE_SPEED = 1.5;
    /** Explosion push radius: power 1.2 times 2. */
    public static final double EXPLOSION_REACH = 2.4;
    /** Knockback multiplier of a player-thrown wind charge. */
    public static final double KNOCKBACK_MODIFIER = 1.22;
    /** Distance the blast centre sits off the face it hit. */
    public static final double CENTRE_OFFSET = 0.25;
    /** Smallest upward push (blocks per tick) that counts as a good launch. */
    public static final double MIN_USEFUL_PUSH = 0.3;
    /** Forward simulation length in ticks. */
    public static final int HORIZON = 80;
    /** Longest wind-charge flight that is simulated. */
    public static final int MAX_CHARGE_TICKS = 6;
    /** Eye height of the gliding pose. */
    public static final double GLIDE_EYE_HEIGHT = 0.4;
    /** Pull-up pitch bounds the plan must be safe against (shallow, steep). */
    private static final float[] RECOVERY_PITCHES = {-26.0f, -45.0f};

    private AlienWCSolver() {}

    public enum Outcome { GOOD, OUT_OF_REACH, LANDS }

    public enum ThrowCall { WAIT, THROW, FALLBACK }

    private record Seed(Vec3d pos, Vec3d vel, Box box, float pitch, float yaw,
                         float pitchStep, float lockPitch, double clearance, double pushScale) {}

    private static Seed seed(double aimSpeed, float lockPitch, double clearance) {
        if (mc.player == null || mc.world == null) return null;
        double resistance = 0.0;
        try {
            resistance = mc.player.getAttributeValue(EntityAttributes.EXPLOSION_KNOCKBACK_RESISTANCE);
        } catch (Exception ignored) {
        }
        return new Seed(
                new Vec3d(mc.player.getX(), mc.player.getY(), mc.player.getZ()),
                mc.player.getVelocity(),
                mc.player.getBoundingBox(),
                MathHelper.clamp(interstellarTikiTiks.apexx.client.util.RotationManager.getFinalPitch(), -90.0f, 90.0f),
                mc.player.getYaw(),
                (float) Math.max(0.5, aimSpeed * 100.0 * 0.05),
                lockPitch,
                clearance,
                MathHelper.clamp(1.0 - resistance, 0.0, 1.0));
    }

    // ── Decisions ───────────────────────────────────────────────────────────

    /**
     * Whether the look-down must start this tick. Waiting one more tick is fine as long as that
     * still avoids landing; start now when that would crash and starting now is a good plan.
     */
    public static boolean shouldStartLookDown(double aimSpeed, float lockPitch, double clearance,
                                              double fallbackThreshold) {
        Seed seed = seed(aimSpeed, lockPitch, clearance);
        if (seed == null) return false;
        if (outcome(seed, 1, -1) != Outcome.LANDS) return false;
        if (outcome(seed, 0, -1) == Outcome.GOOD) return true;
        return !locksBeforeWindow(seed, 1, fallbackThreshold);
    }

    /** Throw now, wait for a lower (stronger) throw, or fall back to the manual ground window. */
    public static ThrowCall throwCall(double aimSpeed, float lockPitch, double clearance) {
        Seed seed = seed(aimSpeed, lockPitch, clearance);
        if (seed == null) return ThrowCall.FALLBACK;
        Outcome now = outcome(seed, 0, 0);
        Outcome next = outcome(seed, 0, 1);
        if (now == Outcome.GOOD) {
            return next == Outcome.GOOD ? ThrowCall.WAIT : ThrowCall.THROW;
        }
        if (next != Outcome.LANDS) {
            return ThrowCall.WAIT;
        }
        return ThrowCall.FALLBACK;
    }

    /** Worst outcome over both pull-up pitch bounds. */
    private static Outcome outcome(Seed s, int lookStart, int fixedThrow) {
        Outcome worst = Outcome.GOOD;
        for (float recoveryPitch : RECOVERY_PITCHES) {
            Outcome o = simulate(s, lookStart, fixedThrow, recoveryPitch);
            if (o.ordinal() > worst.ordinal()) worst = o;
        }
        return worst;
    }

    // ── Simulation ──────────────────────────────────────────────────────────

    /**
     * @param lookStart     tick the look-down is (or was) registered; 0 when it already runs
     * @param fixedThrow    tick of the throw, or -1 for the first tick the aim is locked
     * @param recoveryPitch pitch targeted during the post-throw pull-up
     */
    private static Outcome simulate(Seed s, int lookStart, int fixedThrow, float recoveryPitch) {
        Vec3d pos = s.pos();
        Vec3d vel = s.vel();
        Box box = s.box();

        int throwAt = fixedThrow;
        float topPitch = 90.0f;
        int explodeAfter = -1;
        Vec3d centre = null;
        int pushAt = -1;
        Vec3d push = Vec3d.ZERO;

        for (int t = 0; t < HORIZON; t++) {
            float pitch;
            if (throwAt >= 0 && t >= throwAt + 2) {
                pitch = Math.max(recoveryPitch, topPitch - (t - throwAt - 1) * s.pitchStep());
            } else {
                pitch = downPitch(s, lookStart, t);
            }

            if (throwAt < 0 && t >= lookStart && pitch >= s.lockPitch()) {
                throwAt = t;
            }

            if (t == throwAt) {
                // The look-down keeps holding through the next frame batch; the look-up only
                // moves the pitch used from movement throwAt + 2 on.
                topPitch = downPitch(s, lookStart, t + 1);
                Vec3d[] hit = new Vec3d[1];
                int flight = chargeFlight(pos, vel, pitch, s.yaw(), GLIDE_EYE_HEIGHT, hit);
                if (flight > 0) {
                    centre = hit[0];
                    explodeAfter = t + flight - 1;
                    pushAt = t + flight;
                } else {
                    // Nothing below to burst on: no push ever comes.
                    pushAt = t + 1;
                }
            }

            if (t == pushAt) {
                vel = vel.add(push);
            }

            Vec3d desired = ElytraGlidePhysics.glideVelocity(vel, s.yaw(), pitch);
            Vec3d moved = ElytraGlidePhysics.collide(mc.world, box, desired);
            pos = pos.add(moved);
            box = box.offset(moved);

            // Touched down: the glide is over.
            if (desired.y < 0.0 && Math.abs(moved.y - desired.y) > 1.0E-6) return Outcome.LANDS;

            vel = desired;
            if (Math.abs(moved.y - desired.y) > 1.0E-6) vel = new Vec3d(vel.x, 0.0, vel.z);
            if (Math.abs(moved.x - desired.x) > 1.0E-5) vel = new Vec3d(0.0, vel.y, vel.z);
            if (Math.abs(moved.z - desired.z) > 1.0E-5) vel = new Vec3d(vel.x, vel.y, 0.0);

            if (throwAt >= 0 && t >= throwAt && s.clearance() > 0.0
                    && !mc.world.isSpaceEmpty(mc.player, box.offset(0.0, -s.clearance(), 0.0))) {
                return Outcome.LANDS;
            }

            if (t == explodeAfter && centre != null) {
                push = pushFrom(pos, centre, s);
            }

            if (pushAt >= 0 && t >= pushAt) {
                return push.y >= MIN_USEFUL_PUSH ? Outcome.GOOD : Outcome.OUT_OF_REACH;
            }
        }
        return Outcome.OUT_OF_REACH;
    }

    /** Pitch used by movement {@code t} while the look-down registered at {@code lookStart} runs. */
    private static float downPitch(Seed s, int lookStart, int t) {
        return Math.min(90.0f, s.pitch() + Math.max(0, t - lookStart) * s.pitchStep());
    }

    /** True when a look-down registered at {@code lookStart} locks before the manual window opens. */
    private static boolean locksBeforeWindow(Seed s, int lookStart, double threshold) {
        Vec3d vel = s.vel();
        Box box = s.box();
        for (int t = 0; t < HORIZON; t++) {
            double window = Math.max(threshold, Math.max(0.0, -vel.y));
            if (!mc.world.isSpaceEmpty(mc.player, box.offset(0.0, -window, 0.0))) return false;

            float pitch = downPitch(s, lookStart, t);
            if (t >= lookStart && pitch >= s.lockPitch()) return true;

            Vec3d desired = ElytraGlidePhysics.glideVelocity(vel, s.yaw(), pitch);
            Vec3d moved = ElytraGlidePhysics.collide(mc.world, box, desired);
            box = box.offset(moved);
            if (desired.y < 0.0 && Math.abs(moved.y - desired.y) > 1.0E-6) return false;
            vel = desired;
        }
        return true;
    }

    /**
     * Flies the charge from the gliding eye along the look plus the player's velocity for up to
     * {@link #MAX_CHARGE_TICKS} ticks.
     *
     * @return ticks until it hits a block (the centre is written to {@code centreOut}), or 0
     */
    private static int chargeFlight(Vec3d feet, Vec3d ownVel, float pitch, float yaw, double eyeHeight,
                                    Vec3d[] centreOut) {
        double pr = Math.toRadians(pitch);
        double yr = Math.toRadians(yaw);
        Vec3d look = new Vec3d(-Math.sin(yr) * Math.cos(pr), -Math.sin(pr), Math.cos(yr) * Math.cos(pr));
        Vec3d v = look.multiply(CHARGE_SPEED).add(ownVel);
        Vec3d at = new Vec3d(feet.x, feet.y + eyeHeight, feet.z);

        for (int i = 1; i <= MAX_CHARGE_TICKS; i++) {
            Vec3d next = at.add(v);
            BlockHitResult hit = mc.world.raycast(new RaycastContext(
                    at, next, RaycastContext.ShapeType.COLLIDER, RaycastContext.FluidHandling.NONE, mc.player));
            if (hit.getType() == HitResult.Type.BLOCK) {
                Vec3d normal = Vec3d.of(hit.getSide().getVector());
                centreOut[0] = hit.getPos().add(normal.multiply(CENTRE_OFFSET));
                return i;
            }
            at = next;
        }
        return 0;
    }

    /** Explosion knockback on a glider at {@code feet} from a blast at {@code centre}. */
    private static Vec3d pushFrom(Vec3d feet, Vec3d centre, Seed s) {
        double dist = feet.distanceTo(centre) / EXPLOSION_REACH;
        if (dist > 1.0) return Vec3d.ZERO;
        Vec3d eye = feet.add(0.0, GLIDE_EYE_HEIGHT, 0.0);
        Vec3d away = eye.subtract(centre);
        if (away.lengthSquared() < 1.0E-12) return Vec3d.ZERO;
        return away.normalize().multiply((1.0 - dist) * KNOCKBACK_MODIFIER * s.pushScale());
    }
}
