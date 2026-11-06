package interstellarTikiTiks.apexx.client.module.implementation.smp;

import interstellarTikiTiks.apexx.client.module.implementation.smp.BaseFinderCore.Candidate;
import interstellarTikiTiks.apexx.client.module.implementation.smp.BaseFinderCore.Filter;
import interstellarTikiTiks.apexx.client.module.implementation.smp.BaseFinderCore.Kind;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BaseFinderCoreTest {

    private static Filter storage(boolean enabled, double range, boolean shulkers) {
        return new Filter(enabled, range, true, true, true, shulkers, true, false);
    }

    private static Filter spawner(boolean enabled, double range, boolean trial) {
        return new Filter(enabled, range, false, false, false, false, false, trial);
    }

    @Test
    void keepsOnlyWantedKindsInRangeNearestFirst() {
        List<Candidate> in = List.of(
                new Candidate(20, 0, 0, Kind.CHEST),
                new Candidate(5, 0, 0, Kind.CHEST),
                new Candidate(3, 0, 0, Kind.SHULKER),
                new Candidate(200, 0, 0, Kind.CHEST),
                new Candidate(1, 0, 0, Kind.TRIAL_SPAWNER),
                new Candidate(2, 0, 0, Kind.SPAWNER));
        List<Candidate> out = BaseFinderCore.select(in, 0.5, 0.5, 0.5, storage(true, 96, false), spawner(true, 64, false));
        assertEquals(List.of(Kind.SPAWNER, Kind.CHEST, Kind.CHEST), out.stream().map(Candidate::kind).toList());
        assertEquals(5, out.get(1).x());
    }

    @Test
    void disabledGroupContributesNothing() {
        List<Candidate> in = List.of(new Candidate(1, 0, 0, Kind.CHEST), new Candidate(2, 0, 0, Kind.SPAWNER));
        List<Candidate> out = BaseFinderCore.select(in, 0, 0, 0, storage(false, 96, true), spawner(true, 96, true));
        assertEquals(1, out.size());
        assertEquals(Kind.SPAWNER, out.get(0).kind());
        assertEquals(96.0, BaseFinderCore.scanRange(storage(false, 200, true), spawner(true, 96, true)));
        assertEquals(0.0, BaseFinderCore.scanRange(storage(false, 200, true), spawner(false, 96, true)));
    }

    @Test
    void capsAtMaxTargets() {
        List<Candidate> in = new ArrayList<>();
        for (int i = 0; i < BaseFinderCore.MAX_TARGETS + 50; i++) in.add(new Candidate(i % 50, i / 50, 0, Kind.CHEST));
        assertEquals(BaseFinderCore.MAX_TARGETS,
                BaseFinderCore.select(in, 0, 0, 0, storage(true, 256, true), spawner(false, 64, true)).size());
    }

    @Test
    void fadeIsFullInsideHalfRangeAndZeroAtRange() {
        assertEquals(1.0, BaseFinderCore.fade(10, 96), 1e-9);
        assertEquals(1.0, BaseFinderCore.fade(48, 96), 1e-9);
        assertEquals(0.0, BaseFinderCore.fade(96, 96), 1e-9);
        double mid = BaseFinderCore.fade(72, 96);
        assertTrue(mid > 0.0 && mid < 1.0);
    }
}
