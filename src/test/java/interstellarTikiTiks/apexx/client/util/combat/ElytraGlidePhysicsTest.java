package interstellarTikiTiks.apexx.client.util.combat;

import net.minecraft.util.math.Vec3d;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ElytraGlidePhysicsTest {
    /** Steady glide: the velocity the update settles on for a held pitch (per tick, drag included). */
    private static Vec3d settle(float pitch) {
        Vec3d v = new Vec3d(0.0, 0.0, 1.0);
        for (int i = 0; i < 2000; ++i) v = ElytraGlidePhysics.glideVelocity(v, 0.0f, pitch);
        return v;
    }

    @Test
    void levelGlideRatioMatchesWikiTenToOne() {
        Vec3d v = settle(0.0f);
        double ratio = v.horizontalLength() / -v.y;
        assertEquals(10.06, ratio, 0.5);
    }

    @Test
    void lowestSinkIsNearTwelveToFifteenDegreesUp() {
        float best = 0.0f;
        double lowest = Double.MAX_VALUE;
        for (float pitch = 0.0f; pitch >= -40.0f; pitch -= 1.0f) {
            double sink = -settle(pitch).y;
            if (sink < lowest) { lowest = sink; best = pitch; }
        }
        assertTrue(best <= -8.0f && best >= -20.0f, "lowest sink at pitch " + best);
        assertEquals(1.5, lowest * 20.0, 0.6);
    }

    @Test
    void pitchingUpTradesHorizontalSpeedForHeight() {
        Vec3d fast = new Vec3d(0.0, 0.0, 2.5);
        Vec3d v = ElytraGlidePhysics.glideVelocity(fast, 0.0f, -60.0f);
        assertTrue(v.y > 0.0, "vy " + v.y);
        assertTrue(v.z < 2.5);
    }
}
