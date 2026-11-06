package interstellarTikiTiks.apexx.client.module;

import interstellarTikiTiks.apexx.client.module.implementation.combat.PearlCatch;
import interstellarTikiTiks.apexx.client.module.implementation.combat.PearlDisrupter;
import net.minecraft.util.math.Vec3d;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class PearlDisrupterTest {

    @Test
    public void testSettingsRegistrationAndDefaults() {
        PearlDisrupter pd = new PearlDisrupter();
        List<Setting> settings = pd.getSettings();

        assertNotNull(settings);
        assertEquals(Category.COMBAT, pd.getCategory());
        assertEquals(SubCategory.MACE, pd.getSubCategory());
        assertTrue(pd.isOneShot(), "PearlDisrupter must be configured as a one-shot keybind macro");

        // Silent aim is opt-in; off means the visible camera turns (legit aim).
        Setting silent = setting(settings, "Silent Aim");
        assertTrue(silent instanceof BooleanSetting);
        assertFalse(((BooleanSetting) silent).isEnabled(), "Silent Aim must default to off");

        // Legit aim turns as fast as Pearl Catch's fixed flick (60 degrees a tick).
        Setting flick = setting(settings, "Flick Speed");
        assertTrue(flick instanceof SliderSetting);
        assertEquals(60.0, ((SliderSetting) flick).getValue(), 0.001);

        assertEquals(32.0, ((SliderSetting) setting(settings, "Range")).getValue(), 0.001);
        assertEquals(2.0, ((SliderSetting) setting(settings, "Track Ticks")).getValue(), 0.001);
        assertTrue(setting(settings, "Max Wait") instanceof SliderSetting);
        assertTrue(setting(settings, "Max Flight Time") instanceof SliderSetting);
        assertEquals(2.0, ((SliderSetting) setting(settings, "Max Throws")).getValue(), 0.001);

        // Swap Back is always on and no longer a setting; its timing is the module's own jittered sliders.
        assertTrue(settings.stream().noneMatch(s -> s.getName().equals("Swap Back")));
        SliderSetting throwDelay = (SliderSetting) setting(settings, "Throw Delay (ms)");
        SliderSetting swapBackDelay = (SliderSetting) setting(settings, "Swap Back Delay (ms)");
        assertTrue(throwDelay.getValue() > 0 && throwDelay.getMin() == 0, "delay defaults above 0 but may be set to 0");
        assertTrue(swapBackDelay.getValue() > 0 && swapBackDelay.getMin() == 0, "delay defaults above 0 but may be set to 0");
        assertNotNull(throwDelay.getJitter(), "delays carry a StDev companion");
        assertNotNull(swapBackDelay.getJitter(), "delays carry a StDev companion");
    }

    private static Setting setting(List<Setting> settings, String name) {
        Setting found = settings.stream().filter(s -> s.getName().equals(name)).findFirst().orElse(null);
        assertNotNull(found, name + " must be a setting");
        return found;
    }

    @Test
    public void testSweptDistanceCalculation() {
        // Direct perpendicular crossing at midpoint
        Vec3d pearlStart = new Vec3d(0, 10, 0);
        Vec3d pearlEnd = new Vec3d(2, 10, 0);

        Vec3d windStart = new Vec3d(1, 10, -1);
        Vec3d windEnd = new Vec3d(1, 10, 1);

        double dist = PearlCatch.computeClosestDistance(pearlStart, pearlEnd, windStart, windEnd);
        assertEquals(0.0, dist, 1.0E-6, "Perpendicular crossing at midpoint should have 0 distance");

        // Non-intersecting parallel lines separated by 0.25 blocks
        Vec3d p0 = new Vec3d(0, 0, 0);
        Vec3d p1 = new Vec3d(0, 1, 0);
        Vec3d w0 = new Vec3d(0.25, 0, 0);
        Vec3d w1 = new Vec3d(0.25, 1, 0);

        double parallelDist = PearlCatch.computeClosestDistance(p0, p1, w0, w1);
        assertEquals(0.25, parallelDist, 1.0E-5, "Parallel segments should maintain exact separation");
    }
}
