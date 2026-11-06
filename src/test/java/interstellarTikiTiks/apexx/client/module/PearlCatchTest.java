package interstellarTikiTiks.apexx.client.module;

import interstellarTikiTiks.apexx.client.module.implementation.combat.PearlCatch;
import net.minecraft.util.math.Vec3d;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class PearlCatchTest {

    @Test
    public void testSettingsRegistrationAndDefaults() {
        PearlCatch pc = new PearlCatch();
        List<Setting> settings = pc.getSettings();

        Setting silent = settings.stream()
                .filter(s -> s.getName().equals("Silent Aim"))
                .findFirst()
                .orElse(null);
        assertNotNull(silent, "Silent Aim must be a separate user-togglable setting");
        assertTrue(silent instanceof BooleanSetting);
        assertFalse(((BooleanSetting) silent).isEnabled(), "Silent Aim must default to off");

        // No global Timing Profile: PearlCatchEngine solves the throw gap itself, steered by Catch Time.
        assertTrue(settings.stream().noneMatch(setting -> setting.getName().equals("Use Timing Profile")));
        assertTrue(settings.stream().anyMatch(setting -> setting.getName().equals("Catch Time")),
                "The pearl -> wind charge gap is chosen through Catch Time");
        assertTrue(pc.isOneShot());
    }

    @Test
    public void testComputeClosestDistance() {
        // Test parallel segments
        Vec3d pStart = new Vec3d(0, 5, 0);
        Vec3d pEnd = new Vec3d(0, 5, 10);
        Vec3d wStart = new Vec3d(0, 7, 0);
        Vec3d wEnd = new Vec3d(0, 7, 10);

        double dist = PearlCatch.computeClosestDistance(pStart, pEnd, wStart, wEnd);
        assertEquals(2.0, dist, 0.001, "Distance between parallel line segments should be exactly 2.0m");

        // Test intersecting segments
        Vec3d pStart2 = new Vec3d(-5, 0, 0);
        Vec3d pEnd2 = new Vec3d(5, 0, 0);
        Vec3d wStart2 = new Vec3d(0, 0, -5);
        Vec3d wEnd2 = new Vec3d(0, 0, 5);

        double dist2 = PearlCatch.computeClosestDistance(pStart2, pEnd2, wStart2, wEnd2);
        assertEquals(0.0, dist2, 0.001, "Distance at intersection point should be 0.0m");
    }
}
