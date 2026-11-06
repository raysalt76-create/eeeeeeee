package interstellarTikiTiks.apexx.client.manager;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class AuthBackstopTest {
    private static final long S = TimeUnit.SECONDS.toNanos(1);

    private final AuthBackstop<String> backstop = new AuthBackstop<>();
    private final Set<String> enabled = new HashSet<>();
    private final List<String> all = new ArrayList<>(List.of("AutoMace", "TriggerBot", "Sprint"));

    private void tick(boolean authorized, long seconds) {
        backstop.onTick(authorized, seconds * S, all, enabled::contains, enabled::remove, enabled::add);
    }

    @Test
    void aShortLapseSwitchesNothingOff() {
        enabled.addAll(all);
        tick(true, 0);
        tick(false, 1);
        tick(false, 3);
        assertEquals(3, enabled.size(), "a few seconds without a session is inside the grace period");
        tick(true, 4);
        assertEquals(3, enabled.size());
    }

    @Test
    void aLongLapseSwitchesEverythingOffAndTheSessionComingBackRestoresIt() {
        enabled.addAll(List.of("AutoMace", "Sprint"));
        tick(true, 0);
        tick(false, 1);
        tick(false, 25);
        assertTrue(enabled.isEmpty(), "past the grace period every module is off");
        tick(false, 50);
        assertTrue(enabled.isEmpty());
        tick(true, 51);
        assertEquals(Set.of("AutoMace", "Sprint"), enabled, "exactly what was on comes back");
    }

    @Test
    void aModuleThePlayerHadOffStaysOff() {
        enabled.add("AutoMace");
        tick(false, 0);
        tick(false, 25);
        tick(true, 26);
        assertEquals(Set.of("AutoMace"), enabled);
        assertFalse(enabled.contains("TriggerBot"));
    }

    @Test
    void aSessionThatNeverReturnsKeepsModulesOff() {
        enabled.addAll(all);
        tick(false, 0);
        for (int t = 6; t < 600; t += 6) tick(false, t);
        assertTrue(enabled.isEmpty());
        assertEquals(3, backstop.forcedOffCount());
    }

    @Test
    void oneModuleThatWillNotComeBackDoesNotKeepTheOthersOff() {
        enabled.addAll(all);
        tick(false, 0);
        tick(false, 25);
        AuthBackstop<String> local = backstop;
        local.onTick(true, 11 * S, all, enabled::contains, enabled::remove, name -> {
            if (name.equals("AutoMace")) throw new IllegalStateException("cannot enable");
            enabled.add(name);
        });
        assertEquals(Set.of("TriggerBot", "Sprint"), enabled);
    }

    @Test
    void thePolicyDoesNothingWhileAuthorised() {
        enabled.addAll(all);
        for (int t = 0; t < 100; t++) tick(true, t);
        assertEquals(3, enabled.size());
        assertEquals(0, backstop.forcedOffCount());
    }
}
