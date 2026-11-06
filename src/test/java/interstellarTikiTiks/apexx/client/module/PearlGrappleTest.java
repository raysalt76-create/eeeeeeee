package interstellarTikiTiks.apexx.client.module;

import interstellarTikiTiks.apexx.client.module.implementation.combat.PearlGrapple;
import interstellarTikiTiks.apexx.client.util.combat.PlayerTrajectoryEngine;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

public class PearlGrappleTest {

    @Test
    public void testPearlGrappleIsOneShotAndHasSettings() {
        PearlGrapple pg = new PearlGrapple();
        assertTrue(pg.isOneShot(), "PearlGrapple must be a one-shot module");

        List<Setting> settings = pg.getSettings();
        assertNotNull(settings);

        for (String name : List.of("Range", "FOV", "Pearl Outside FOV", "Mace Delay", "AutoBreach Pressure",
                "Elytra Unequip", "Silent Aim", "Swap Back", "Ignore Friends")) {
            assertTrue(settings.stream().anyMatch(s -> s.getName().equals(name)), "missing setting " + name);
        }
        for (String removed : List.of("Flick Speed", "Throw Deadline", "Regrapple Delay")) {
            assertTrue(settings.stream().noneMatch(s -> s.getName().equals(removed)), "setting should be gone: " + removed);
        }
        SliderSetting range = (SliderSetting) pg.getSetting("Range");
        assertEquals(50.0, range.getMax(), 1e-9, "the grapple searches at most 50 blocks");
        SliderSetting fov = (SliderSetting) pg.getSetting("FOV");
        assertEquals(90.0, fov.getValue(), 1e-9, "only players inside a 90 degree cone are grappled by default");
        BooleanSetting outside = (BooleanSetting) pg.getSetting("Pearl Outside FOV");
        assertTrue(outside.isEnabled(), "with nobody in the FOV the key throws a normal pearl");
        BooleanSetting silent = (BooleanSetting) pg.getSetting("Silent Aim");
        assertFalse(silent.isEnabled(), "silent aim is an explicit opt-in");
    }

    @Test
    public void testPlayerTrajectoryEngineHistoryCappedAt40() {
        PlayerTrajectoryEngine engine = PlayerTrajectoryEngine.getInstance();
        engine.clear();

        UUID dummyUuid = UUID.randomUUID();

        List<PlayerTrajectoryEngine.PlayerSnapshot> history = engine.getHistory(dummyUuid);
        assertTrue(history.isEmpty());

        assertEquals(40, PlayerTrajectoryEngine.MAX_HISTORY_TICKS, "Rolling history window must be exactly 40 ticks (2 seconds @ 20 Hz)");
    }

    @Test
    public void testBallisticDropSeriesConsistency() {
        // Verify closed-form drag series: S(t) = sum_{k=1}^t 0.99^k
        double d = 0.99;
        double sum = 0.0;
        for (int t = 1; t <= 30; t++) {
            sum += Math.pow(d, t);
            double expected = 99.0 * (1.0 - Math.pow(d, t));
            assertEquals(sum, expected, 1e-6, "Drag geometric series mismatch at tick " + t);
        }
    }
}
