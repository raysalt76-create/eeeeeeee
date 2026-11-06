package interstellarTikiTiks.apexx.client.util.combat;

import net.minecraft.util.math.Vec3d;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

public class InterceptPredictorTest {

    private static final UUID ID = new UUID(1L, 2L);

    private static InterceptPredictor.Inputs scenario(Vec3d targetPos, Vec3d targetVel,
                                                      InterceptPredictor.Mode mode, double fall, int lag) {
        return new InterceptPredictor.Inputs(new Vec3d(0, 100, 0), new Vec3d(0, -1.5, 0), 1.62, fall,
                InterceptPredictor.NO_FLOOR, targetPos, targetVel, Vec3d.ZERO, 0.3, 1.8, mode,
                0.05, 0.06, lag, InterceptPredictor.NO_FLOOR);
    }

    @Test
    public void fastDiveOntoRisingTargetIsPredictedWithHighConfidence() {
        // Falling 1.5 blocks/tick and speeding up, target 12 blocks below rising at 0.3 blocks/tick.
        var in = scenario(new Vec3d(0.2, 88, 0), new Vec3d(0, 0.3, 0), InterceptPredictor.Mode.AIR, 6.0, 2);
        var r = InterceptPredictor.evaluate(ID, 1, in, true, 42L);
        assertTrue(r.deep(), "contact inside 500 ms must start the ensemble");
        assertTrue(r.confidence() >= 0.6, "confidence was " + r.confidence());
        assertTrue(r.interceptTick() >= 1 && r.interceptTick() <= InterceptPredictor.DEEP_WINDOW_TICKS);
        assertNotNull(r.aimDirection());
        assertEquals(1.0, r.aimDirection().length(), 1e-9);
        assertTrue(r.aimDirection().y < -0.8, "aim must point down at the target: " + r.aimDirection());
    }

    @Test
    public void approachWithinShrinksAsTheDiveClosesOnAStationaryTarget() {
        // Eye 5.8 blocks above a standing target's head, falling 1.5 blocks/tick: the forecast
        // approach must fall tick by tick and be reported as the minimum over the look-ahead.
        var in = scenario(new Vec3d(0, 94, 0), Vec3d.ZERO, InterceptPredictor.Mode.GROUND, 6.0, 0);
        double now = InterceptPredictor.approachWithin(in, 0);
        double next = InterceptPredictor.approachWithin(in, 1);
        double soon = InterceptPredictor.approachWithin(in, 2);
        assertEquals(5.82 + InterceptPredictor.SERVER_MARGIN, now, 0.01);
        assertTrue(next < now && soon < next, now + " > " + next + " > " + soon);
        assertTrue(soon <= 4.5, "inside spear reach within 100 ms: " + soon);
        assertTrue(now > 4.5, "not yet inside spear reach: " + now);
    }

    @Test
    public void targetFarToTheSideIsNotEngaged() {
        var in = scenario(new Vec3d(15, 88, 0), Vec3d.ZERO, InterceptPredictor.Mode.GROUND, 6.0, 0);
        var r = InterceptPredictor.evaluate(ID, 1, in, true, 42L);
        assertFalse(r.deep());
        assertEquals(0.0, r.confidence(), 0.0);
    }

    @Test
    public void contactBeyondTheWindowOnlyRunsTheCheapForecast() {
        var in = scenario(new Vec3d(0, 40, 0), Vec3d.ZERO, InterceptPredictor.Mode.GROUND, 6.0, 0);
        var r = InterceptPredictor.evaluate(ID, 1, in, true, 42L);
        assertFalse(r.deep(), "60 blocks below is more than 500 ms away");
        assertTrue(r.closestTick() > InterceptPredictor.DEEP_WINDOW_TICKS);
    }

    @Test
    public void smashIsNotForecastBeforeTheFallIsDeepEnough() {
        // Barely moving diver with 0.2 blocks fallen and a target already in reach: the forecast hit
        // must wait until the accumulated fall passes 1.5, not fire on the first tick.
        var in = new InterceptPredictor.Inputs(new Vec3d(0, 100, 0), new Vec3d(0, -0.1, 0), 1.62, 0.2,
                InterceptPredictor.NO_FLOOR, new Vec3d(0, 98, 0), Vec3d.ZERO, Vec3d.ZERO, 0.3, 1.8,
                InterceptPredictor.Mode.GROUND, 0.0, 0.0, 0, InterceptPredictor.NO_FLOOR);
        var r = InterceptPredictor.evaluate(ID, 1, in, true, 42L);
        assertTrue(r.deep());
        assertTrue(r.interceptTick() >= 3, "first hit at tick " + r.interceptTick());
    }

    @Test
    public void latencyShiftedServerBoxCanRejectAMarginalHit() {
        // Target runs away sideways at 0.6 blocks/tick; at 6 ticks of lag the server holds it 3.6 blocks
        // further along than drawn, so the same geometry that hits at zero lag cannot register.
        var near = scenario(new Vec3d(1.5, 95, 0), new Vec3d(0.6, 0, 0), InterceptPredictor.Mode.GROUND, 6.0, 0);
        var lagged = scenario(new Vec3d(1.5, 95, 0), new Vec3d(0.6, 0, 0), InterceptPredictor.Mode.GROUND, 6.0, 6);
        double withoutLag = InterceptPredictor.evaluate(ID, 1, near, true, 7L).confidence();
        double withLag = InterceptPredictor.evaluate(ID, 1, lagged, true, 7L).confidence();
        assertTrue(withoutLag > withLag, "lag " + withLag + " vs none " + withoutLag);
    }

    @Test
    public void sameSeedGivesTheSameForecast() {
        var in = scenario(new Vec3d(0.4, 90, 0.2), new Vec3d(0.05, 0.2, 0), InterceptPredictor.Mode.AIR, 6.0, 1);
        var a = InterceptPredictor.evaluate(ID, 1, in, true, 99L);
        var b = InterceptPredictor.evaluate(ID, 1, in, true, 99L);
        assertEquals(a.confidence(), b.confidence(), 0.0);
        assertEquals(a.interceptTick(), b.interceptTick());
    }

    @Test
    public void landingEndsTheSmashWindow() {
        // Ground 3 blocks below the diver: it lands before reaching a target 20 blocks down.
        var in = new InterceptPredictor.Inputs(new Vec3d(0, 100, 0), new Vec3d(0, -1.5, 0), 1.62, 6.0,
                97.0, new Vec3d(0, 80, 0), Vec3d.ZERO, Vec3d.ZERO, 0.3, 1.8,
                InterceptPredictor.Mode.GROUND, 0.05, 0.06, 0, InterceptPredictor.NO_FLOOR);
        var r = InterceptPredictor.evaluate(ID, 1, in, true, 42L);
        assertEquals(0.0, r.confidence(), 0.0);
    }
}
