package interstellarTikiTiks.apexx.client.util.combat;

import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Terrain fixture using vanilla Box.raycast; does not bootstrap a Minecraft World or emulate a server. */
class PearlGrappleWorldTraceTest {
    @Test
    void spreadRollsClipAnObstacleEvenWhenTheMeanPathClearsIt() {
        // The straight nominal path at x=0.5 clears this wall; positive launch spread clips it.
        Box obstacle = new Box(0.55, 60, 10, 3, 75, 10.2);
        var thrower = new PearlGrappleSolver.Thrower(new Vec3d(0.5, 65.52, 0.5), Vec3d.ZERO, true);
        Box[] boxes = new Box[64];
        Arrays.fill(boxes, new Box(0.2, 64, 20.2, 0.8, 65.8, 20.8));
        var forecast = new PearlGrappleSolver.Target(List.of(new PlayerMotionSimulation.Forecast("still", 1, boxes)),
                List.of(new PearlGrappleSolver.Lead(0, 1)));
        var shot = PearlGrappleSolver.solve(null, null, null, thrower, forecast,
                new PearlGrappleSolver.Lattice(0, 0, 0.0703125), 40, 56);
        assertNotNull(shot);
        var traced = PearlGrappleSolver.evaluateTraced(thrower, forecast, shot.yaw(), shot.pitch(), 56,
                (spread, ages) -> {
                    Vec3d launch = ThrowPhysics.launchVelocity(shot.yaw(), shot.pitch(), 1.5f,
                            spread, thrower.movement(), thrower.onGround());
                    var flight = PearlSimulation.ballistic(thrower.spawn(), launch, 0).trace(ages);
                    var segments = new ArrayList<PearlSimulation.Segment>();
                    for (var segment : flight.segments()) {
                        var hit = obstacle.raycast(segment.from(), segment.sweepEnd());
                        if (hit.isPresent()) {
                            Vec3d point = hit.get();
                            segments.add(new PearlSimulation.Segment(segment.age(), segment.from(), point,
                                    segment.velocity(), segment.tolerance(),
                                    new BlockHitResult(point, Direction.NORTH, BlockPos.ofFloored(point), false), true));
                            break;
                        }
                        segments.add(segment);
                    }
                    return PearlGrappleSolver.Flight.fromPath(new PearlSimulation.Path(0, thrower.spawn(), segments), ages);
                });
        assertNotNull(traced);
        assertTrue(shot.chance() > 0.98);
        assertTrue(traced.chance() > 0.4 && traced.chance() < 0.9,
                "A clear mean ray must not imply clear spread paths: " + traced.chance());
        assertTrue(traced.hitAge() > 0);
        assertTrue(traced.landing().z > 15);
    }
}
