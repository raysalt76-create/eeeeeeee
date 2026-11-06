package interstellarTikiTiks.apexx.client.util.combat;

import net.minecraft.util.math.Box;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PearlGrappleBestEffortTest {
    private static final PearlGrappleSolver.Thrower THROWER =
            new PearlGrappleSolver.Thrower(new Vec3d(0.5, 65.52, 0.5), Vec3d.ZERO, true);

    private static Box[] boxes(double x, double y, double z) {
        Box[] boxes = new Box[80];
        Arrays.fill(boxes, new Box(x - 0.3, y, z - 0.3, x + 0.3, y + 1.8, z + 0.3));
        return boxes;
    }

    @Test
    void unreachableTargetStillGetsAnImprovedFiniteThrow() {
        var target = new PearlGrappleSolver.Target(
                List.of(new PlayerMotionSimulation.Forecast("above range", 1, boxes(0.5, 164, 20.5))),
                List.of(new PearlGrappleSolver.Lead(0, 1)));
        var lattice = new PearlGrappleSolver.Lattice(0, 0, 0.0703125);
        assertNull(PearlGrappleSolver.solve(null, null, null, THROWER, target, lattice, 90, 56));
        var shot = PearlGrappleSolver.bestEffort(null, null, null, THROWER, target, lattice, 90, 56);
        assertNotNull(shot);
        assertEquals(0, shot.chance());
        assertEquals(-1, shot.hitAge(), "A closest approach is not a confirmed predicted collision");
        double initial = PearlGrappleSolver.missDistance(PearlGrappleSolver.Flight.ballistic(THROWER, 0, 0, 56), target);
        double improved = PearlGrappleSolver.missDistance(
                PearlGrappleSolver.Flight.ballistic(THROWER, shot.yaw(), shot.pitch(), 56), target);
        assertTrue(improved < initial * 0.7, "Fallback must improve the real flight, not add arbitrary lift");
    }

    @Test
    void unfinishedTurnStillHasAThrowOnTheCurrentSensitivityLattice() {
        var target = new PearlGrappleSolver.Target(
                List.of(new PlayerMotionSimulation.Forecast("behind", 1, boxes(0.5, 64, -20.5))),
                List.of(new PearlGrappleSolver.Lead(0, 1)));
        var lattice = new PearlGrappleSolver.Lattice(17, 0, 0.15);
        assertNull(PearlGrappleSolver.solve(null, null, null, THROWER, target, lattice, 5, 56));
        var shot = PearlGrappleSolver.bestEffort(null, null, null, THROWER, target, lattice, 5, 56);
        assertNotNull(shot);
        double yaw = MathHelper.wrapDegrees(shot.yaw() - lattice.yaw());
        double pitch = shot.pitch() - lattice.pitch();
        assertTrue(Math.hypot(yaw, pitch) <= 5.0001);
        assertEquals(Math.round(yaw / lattice.step()), yaw / lattice.step(), 1e-3);
        assertEquals(Math.round(pitch / lattice.step()), pitch / lattice.step(), 1e-3);
    }

    @Test
    void lowChanceInterceptRemainsAvailableInsteadOfBeingRejected() {
        var target = new PearlGrappleSolver.Target(List.of(
                new PlayerMotionSimulation.Forecast("possible", 0.05, boxes(0.5, 64, 20.5)),
                new PlayerMotionSimulation.Forecast("unreachable", 0.95, boxes(0.5, 164, 20.5))),
                List.of(new PearlGrappleSolver.Lead(0, 1)));
        var shot = PearlGrappleSolver.solve(null, null, null, THROWER, target,
                new PearlGrappleSolver.Lattice(0, 0, 0.0703125), 90, 56);
        assertNotNull(shot);
        assertTrue(shot.chance() > 0 && shot.chance() <= 0.05 + 1e-9);
    }

    @Test
    void absentForecastStillSuppliesTheCurrentLook() {
        var target = new PearlGrappleSolver.Target(List.of(), List.of());
        var shot = PearlGrappleSolver.bestEffort(null, null, null, THROWER, target,
                new PearlGrappleSolver.Lattice(23, 7, 0.15), 34, 56);
        assertNotNull(shot);
        assertEquals(23, shot.yaw());
        assertEquals(7, shot.pitch());
    }
}
