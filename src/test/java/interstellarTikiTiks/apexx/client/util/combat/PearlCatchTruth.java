package interstellarTikiTiks.apexx.client.util.combat;

import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;

import java.util.SplittableRandom;

/**
 * Independent replay of a pearl catch for the accuracy sweeps: open air, the server's launch spread
 * rolled per projectile, a one-tick timing slip of the later throw. It does not call the solver's
 * physics: pearl gravity and float drag, the wind charge's constant flight, the age tolerance and
 * vanilla {@link Box#raycast} are written out again here, in server order (the older projectile
 * moves first each tick).
 */
final class PearlCatchTruth {
    /** Chance each way that the later use is handled one server tick early or late. */
    static final double SLIP_EACH_WAY = 0.1;
    /** Ticks of the later projectile's flight a catch may still happen in. */
    static final int FLIGHT_CAP = 80;

    private static final double SPREAD = 0.0172275;
    private static final double GRAVITY = 0.03;
    private static final double DRAG = (double) 0.99f;

    private PearlCatchTruth() {}

    /** nextTriangular(0, SPREAD) per axis: a unit offset that the power scales. */
    static Vec3d roll(SplittableRandom random) {
        return new Vec3d(SPREAD * (random.nextDouble() - random.nextDouble()),
                SPREAD * (random.nextDouble() - random.nextDouble()),
                SPREAD * (random.nextDouble() - random.nextDouble()));
    }

    /** The later throw's gap after a slip: one less or one more tick with {@link #SLIP_EACH_WAY} each. */
    static int slipped(int gap, SplittableRandom random) {
        double u = random.nextDouble();
        if (u < SLIP_EACH_WAY && gap > 0) return gap - 1;
        if (u > 1.0 - SLIP_EACH_WAY) return gap + 1;
        return gap;
    }

    /**
     * True when a pearl launched from {@code pearlSpawn} with {@code pearlVelocity} runs into a wind
     * charge launched from {@code chargeSpawn} with {@code chargeVelocity}. {@code chargeFirst}: the
     * charge is the older entity, thrown {@code gap} ticks before the pearl; otherwise it is thrown
     * {@code gap} ticks after the pearl.
     */
    static boolean catches(Vec3d pearlSpawn, Vec3d pearlVelocity, Vec3d chargeSpawn, Vec3d chargeVelocity,
                           int gap, boolean chargeFirst) {
        return catchAge(pearlSpawn, pearlVelocity, chargeSpawn, chargeVelocity, gap, chargeFirst) >= 0;
    }

    /** The pearl's age on the catch, or -1. */
    static int catchAge(Vec3d pearlSpawn, Vec3d pearlVelocity, Vec3d chargeSpawn, Vec3d chargeVelocity,
                        int gap, boolean chargeFirst) {
        double px = pearlSpawn.x, py = pearlSpawn.y, pz = pearlSpawn.z;
        double vx = pearlVelocity.x, vy = pearlVelocity.y, vz = pearlVelocity.z;
        int lastAge = chargeFirst ? FLIGHT_CAP : gap + FLIGHT_CAP;
        for (int age = 1; age <= lastAge; ++age) {
            vy -= GRAVITY;
            vx *= DRAG;
            vy *= DRAG;
            vz *= DRAG;
            int moves = chargeFirst ? age + gap : age - gap - 1;
            if (moves >= 0) {
                double cx = chargeSpawn.x + chargeVelocity.x * moves;
                double cy = chargeSpawn.y + chargeVelocity.y * moves;
                double cz = chargeSpawn.z + chargeVelocity.z * moves;
                double tolerance = Math.max(0.0f, Math.min(0.3f, (float) (age - 2) / 20.0f));
                double half = 0.3125 / 2.0;
                double bottom = cy - (double) 0.15f;
                Box box = new Box(cx - half, bottom, cz - half, cx + half, bottom + 0.3125, cz + half).expand(tolerance);
                Vec3d from = new Vec3d(px, py, pz);
                if (box.raycast(from, new Vec3d(px + vx, py + vy, pz + vz)).isPresent()) return age;
            }
            px += vx;
            py += vy;
            pz += vz;
        }
        return -1;
    }
}
