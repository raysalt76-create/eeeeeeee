package interstellarTikiTiks.apexx.client.module.implementation.bedwars;

import interstellarTikiTiks.apexx.client.module.ModeSetting;
import interstellarTikiTiks.apexx.client.module.Setting;
import interstellarTikiTiks.apexx.client.module.SliderSetting;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ScaffoldTest {

    private static Setting setting(Scaffold module, String name) {
        return module.getSettings().stream().filter(s -> name.equals(s.getName())).findFirst()
                .orElseThrow(() -> new AssertionError("missing setting " + name));
    }

    @Test
    void modesAndConfigCompatibleNames() {
        Scaffold module = new Scaffold();
        ModeSetting mode = (ModeSetting) setting(module, "Mode");
        assertEquals(List.of("Legit", "Normal", "Telly", "Blatant"), mode.getModes());
        assertEquals("Normal", mode.getCurrentMode());
        // Names older configs and the config maker already reference.
        for (String name : new String[]{"Silent Rotation", "Keep Y", "Expand", "Reach", "Rotation", "Movement Fix",
                "Eagle", "Safe Walk", "Tower", "Block Selection", "Swap Back", "Block Counter"}) {
            setting(module, name);
        }
    }

    @Test
    void blatantOnlyBehavioursAreFlaggedAndDescribed() {
        Scaffold module = new Scaffold();
        for (String name : new String[]{"Snap Rotation", "Ignore Place Rate"}) {
            Setting s = setting(module, name);
            assertTrue(s.isBlatant(), name);
            assertNotNull(s.getDescription(), name);
            assertEquals("Mode:Blatant", s.getDependsOn(), name);
        }
        assertFalse(setting(module, "Mode").isBlatant());
    }

    @Test
    void delaySlidersAllowZeroButDoNotDefaultToZero() {
        Scaffold module = new Scaffold();
        for (String name : new String[]{"Place Delay (ms)", "Swap Delay (ms)", "Swap Back Delay (ms)",
                "Tower Place Delay (ms)", "Tower Re-Jump Delay (ms)", "Sneak Release (ms)",
                "Short Turn Delay (ms)", "Long Turn Delay (ms)"}) {
            SliderSetting delay = (SliderSetting) setting(module, name);
            assertEquals("Delays", delay.getSection(), name);
            assertEquals(0.0, delay.getMin(), name + " must allow 0");
            assertTrue(delay.getValue() > 0.0, name + " must not default to 0");
            assertNotNull(delay.getJitter(), name);
            assertTrue(module.getSettings().contains(delay.getJitter()), name);
        }
        assertTrue(module.getSettings().stream().noneMatch(s -> s.getName().contains("Timing Profile")));
    }

    @Test
    void staircaseStepsOneAxisAtATime() {
        List<int[]> cells = ScaffoldMath.staircase(0.5, 0.5, 3.5, 3.5, 32);
        assertArrayEquals(new int[]{0, 0}, cells.get(0));
        assertArrayEquals(new int[]{3, 3}, cells.get(cells.size() - 1));
        for (int i = 1; i < cells.size(); i++) {
            int manhattan = Math.abs(cells.get(i)[0] - cells.get(i - 1)[0]) + Math.abs(cells.get(i)[1] - cells.get(i - 1)[1]);
            assertEquals(1, manhattan, "cell " + i + " must share a side with the previous one");
        }
        assertEquals(7, cells.size());
    }

    @Test
    void staircaseStraightAndNegative() {
        List<int[]> cells = ScaffoldMath.staircase(0.5, 0.5, -2.2, 0.5, 32);
        assertEquals(4, cells.size());
        assertArrayEquals(new int[]{-3, 0}, cells.get(3));
        assertEquals(1, ScaffoldMath.staircase(0.2, 0.2, 0.8, 0.9, 32).size());
        assertEquals(3, ScaffoldMath.staircase(0.5, 0.5, 10.5, 0.5, 3).size());
    }

    @Test
    void jumpPhysics() {
        // A vanilla jump (0.42) from the ground lands back on the same height after about 12 ticks.
        int land = ScaffoldMath.ticksToLand(64.0, 0.42, 64.0, 40);
        assertTrue(land >= 11 && land <= 13, "landed after " + land);
        assertEquals(-1, ScaffoldMath.ticksToLand(64.0, 0.42, 64.0, 5));
        // Sprint-jump air travel over a flight is roughly 4-5 blocks (the model errs slightly long).
        double d = ScaffoldMath.airDistance(0.45, 12);
        assertTrue(d > 3.5 && d < 5.5, "distance " + d);
        assertEquals(0.0, ScaffoldMath.airDistance(0.45, 0), 1e-12);
    }

    @Test
    void yawMatchesVanillaConvention() {
        assertEquals(0.0f, ScaffoldMath.yawOf(0, 1), 1e-4);    // +Z = south = yaw 0
        assertEquals(90.0f, ScaffoldMath.yawOf(-1, 0), 1e-4);  // -X = west = yaw 90
        assertEquals(-20.0f, ScaffoldMath.yawDelta(170f, 150f), 1e-4);
        assertEquals(20.0f, ScaffoldMath.yawDelta(170f, -170f), 1e-4);
    }

    @Test
    void facePointStaysInsideTheFaceNearTheLook() {
        BlockPos against = new BlockPos(0, 63, 0);
        Vec3d eye = new Vec3d(0.5, 65.62, -0.8);
        // Looking straight down from beyond the north edge: the point clamps inside the top face.
        Vec3d p = ScaffoldMath.facePoint(against, Direction.UP, eye, new Vec3d(0, -1, 0), 0.2);
        assertEquals(0.5, p.x, 1e-9);
        assertEquals(0.2, p.z, 1e-9);
        assertTrue(p.y < 64.0 && p.y > 63.98);
        // Side face facing north, look pointing at it: the hit lies on the plane z = 0, inside margins.
        Vec3d q = ScaffoldMath.facePoint(against, Direction.NORTH, new Vec3d(0.5, 64.6, -1.5),
                new Vec3d(0, -0.6, 1).normalize(), 0.2);
        assertTrue(q.z > 0.0 && q.z < 0.02);
        assertTrue(q.y >= 63.2 && q.y <= 63.8);
    }
}
