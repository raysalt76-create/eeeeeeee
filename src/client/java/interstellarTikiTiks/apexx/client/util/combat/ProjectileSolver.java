package interstellarTikiTiks.apexx.client.util.combat;

import net.minecraft.util.math.Vec3d;

public class ProjectileSolver {

    public static float solvePitch(Vec3d origin, Vec3d target, float velocity, float gravity, float drag) {
        double dx = target.x - origin.x;
        double dy = target.y - origin.y;
        double dz = target.z - origin.z;
        double dist = Math.sqrt(dx * dx + dz * dz);

        float minPitch = -90.0f;
        float maxPitch = 90.0f;
        float bestPitch = 0.0f;
        double minDiff = Double.MAX_VALUE;

        for (int i = 0; i < 20; i++) {
            float pitch = (minPitch + maxPitch) / 2.0f;
            double landingY = simulateY(dist, pitch, velocity, gravity, drag);

            double diff = landingY - dy;

            if (Math.abs(diff) < minDiff) {
                minDiff = Math.abs(diff);
                bestPitch = pitch;
            }

            if (diff > 0) {
                minPitch = pitch;
            } else {
                maxPitch = pitch;
            }
        }

        return bestPitch;
    }

    private static double simulateY(double distance, float pitch, float velocity, float gravity, float drag) {
        double vy = Math.sin(Math.toRadians(-pitch)) * velocity;
        double vH = Math.cos(Math.toRadians(-pitch)) * velocity;

        double y = 0;
        double x = 0;

        for (int i = 0; i < 300; i++) {
            x += vH;
            y += vy;

            if (x >= distance) {
                double remaining = x - distance;

                return y - (vy * (remaining / vH));
            }

            vy *= drag;
            vy -= gravity;
            vH *= drag;
        }

        return Double.NEGATIVE_INFINITY;
    }
}
