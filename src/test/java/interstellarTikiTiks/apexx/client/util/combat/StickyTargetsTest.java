package interstellarTikiTiks.apexx.client.util.combat;

import interstellarTikiTiks.apexx.client.module.BooleanSetting;
import interstellarTikiTiks.apexx.client.module.Category;
import interstellarTikiTiks.apexx.client.module.Module;
import interstellarTikiTiks.apexx.client.module.SliderSetting;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class StickyTargetsTest {

    /** A combat module whose enabled state is set directly (no client lifecycle). */
    private static final class FakeModule extends Module {
        boolean on = true;

        FakeModule(String name) {
            super(name, "test", Category.COMBAT);
        }

        @Override
        public boolean isEnabled() {
            return on;
        }
    }

    /** Players are plain strings; distances and friends are set per test. */
    private static final class FakeEnv implements StickyTargets.Env {
        Object world = new Object();
        final Map<Object, Double> distance = new HashMap<>();
        final Set<Object> friends = new HashSet<>();
        final Set<Object> gone = new HashSet<>();

        @Override public Object world() { return world; }
        @Override public boolean isSelf(Object player) { return "self".equals(player); }
        @Override public boolean isFriend(Object player) { return friends.contains(player); }
        @Override public boolean isPresent(Object player, Object w) { return w == world && !gone.contains(player); }
        @Override public double distanceSq(Object player) {
            double d = distance.getOrDefault(player, 2.0);
            return d * d;
        }
        @Override public boolean samePlayer(Object a, Object b) { return a.equals(b); }
    }

    private FakeEnv env;
    private FakeModule a;
    private FakeModule b;

    @BeforeEach
    void setUp() {
        StickyTargets.resetForTests();
        env = new FakeEnv();
        StickyTargets.setEnvForTests(env);
        a = new FakeModule("Sticky A");
        b = new FakeModule("Sticky B");
        StickyTargets.attach(a);
        StickyTargets.attach(b);
    }

    @AfterEach
    void tearDown() {
        StickyTargets.resetForTests();
    }

    private static BooleanSetting sticky(Module m) {
        return (BooleanSetting) m.getSetting(StickyTargets.STICKY_SETTING);
    }

    private static SliderSetting release(Module m) {
        return (SliderSetting) m.getSetting(StickyTargets.RELEASE_SETTING);
    }

    @Test
    void settingsAttachedAndOffByDefault() {
        assertNotNull(sticky(a));
        assertNotNull(release(a));
        assertFalse(sticky(a).isEnabled());
        assertEquals(8.0, release(a).getValue(), 1e-9);
    }

    @Test
    void noLockWhenEveryModuleHasStickyOff() {
        StickyTargets.onLocalAttackPlayer("enemy1");
        assertNull(StickyTargets.liveTarget());
        assertTrue(StickyTargets.allowsPlayer(a, "enemy2"));
        assertTrue(StickyTargets.allowsPlayer(b, "enemy2"));
    }

    @Test
    void attackAcquiresTheSharedLockAndItIsSharedAcrossModules() {
        sticky(a).setEnabled(true);
        sticky(b).setEnabled(true);

        StickyTargets.onLocalAttackPlayer("enemy1");
        assertEquals("enemy1", StickyTargets.liveTarget());

        // Both sticky modules are held on the same player.
        assertTrue(StickyTargets.allowsPlayer(a, "enemy1"));
        assertTrue(StickyTargets.allowsPlayer(b, "enemy1"));
        assertFalse(StickyTargets.allowsPlayer(a, "enemy2"));
        assertFalse(StickyTargets.allowsPlayer(b, "enemy2"));

        // A later attack on someone else does not move a live lock.
        StickyTargets.onLocalAttackPlayer("enemy2");
        assertEquals("enemy1", StickyTargets.liveTarget());

        // Manual input (no owner) and a module without Sticky Target are never restricted.
        assertTrue(StickyTargets.allowsPlayer(null, "enemy2"));
        FakeModule c = new FakeModule("Plain C");
        StickyTargets.attach(c);
        assertTrue(StickyTargets.allowsPlayer(c, "enemy2"));
    }

    @Test
    void lockTakenByOneModuleAppliesToAnotherThatTurnsStickyOnLater() {
        sticky(a).setEnabled(true);
        StickyTargets.onLocalAttackPlayer("enemy1");
        assertTrue(StickyTargets.allowsPlayer(b, "enemy2"), "B has Sticky Target off");
        sticky(b).setEnabled(true);
        assertFalse(StickyTargets.allowsPlayer(b, "enemy2"), "B now follows the shared lock");
    }

    @Test
    void releaseDistanceUsesTheLargestAmongEnabledStickyModules() {
        sticky(a).setEnabled(true);
        sticky(b).setEnabled(true);
        release(a).setValue(5.0);
        release(b).setValue(12.0);
        assertEquals(12.0, StickyTargets.effectiveReleaseDistance(), 1e-9);

        StickyTargets.onLocalAttackPlayer("enemy1");
        env.distance.put("enemy1", 10.0); // beyond A's 5 but within B's 12
        StickyTargets.tick();
        assertEquals("enemy1", StickyTargets.liveTarget());
        assertFalse(StickyTargets.allowsPlayer(a, "enemy2"), "A is still held by the shared lock");

        // Disabling B leaves only A's 5-block release: the lock lets go.
        b.on = false;
        assertEquals(5.0, StickyTargets.effectiveReleaseDistance(), 1e-9);
        StickyTargets.tick();
        assertNull(StickyTargets.liveTarget());
        assertTrue(StickyTargets.allowsPlayer(a, "enemy2"));
    }

    @Test
    void attackBeyondReleaseDistanceTakesNoLock() {
        sticky(a).setEnabled(true);
        env.distance.put("enemy1", 20.0);
        StickyTargets.onLocalAttackPlayer("enemy1");
        assertNull(StickyTargets.liveTarget());
    }

    @Test
    void lockReleasedWhenTheLastStickyModuleTurnsOff() {
        sticky(a).setEnabled(true);
        sticky(b).setEnabled(true);
        StickyTargets.onLocalAttackPlayer("enemy1");

        sticky(a).setEnabled(false);
        assertEquals("enemy1", StickyTargets.liveTarget(), "B still has Sticky Target on");
        assertTrue(StickyTargets.allowsPlayer(a, "enemy2"), "A ignores the lock once its setting is off");
        assertFalse(StickyTargets.allowsPlayer(b, "enemy2"));

        sticky(b).setEnabled(false);
        assertNull(StickyTargets.liveTarget());

        // Turning it back on does not resurrect the old lock.
        sticky(b).setEnabled(true);
        assertNull(StickyTargets.liveTarget());
        assertTrue(StickyTargets.allowsPlayer(b, "enemy2"));
    }

    @Test
    void lockReleasedWhenTheLastStickyModuleIsDisabled() {
        sticky(a).setEnabled(true);
        StickyTargets.onLocalAttackPlayer("enemy1");
        a.on = false;
        assertNull(StickyTargets.liveTarget());
        a.on = true;
        assertNull(StickyTargets.liveTarget(), "a released lock stays released");
    }

    @Test
    void friendsAreNeverLockedAndBecomingAFriendReleases() {
        sticky(a).setEnabled(true);
        env.friends.add("buddy");
        StickyTargets.onLocalAttackPlayer("buddy");
        assertNull(StickyTargets.liveTarget(), "a friend is never locked");

        StickyTargets.onLocalAttackPlayer("enemy1");
        assertEquals("enemy1", StickyTargets.liveTarget());
        env.friends.add("enemy1");
        assertNull(StickyTargets.liveTarget(), "becoming a friend releases the lock");
        assertTrue(StickyTargets.allowsPlayer(a, "enemy2"));
    }

    @Test
    void deathRemovalAndWorldChangeRelease() {
        sticky(a).setEnabled(true);
        StickyTargets.onLocalAttackPlayer("enemy1");
        env.gone.add("enemy1");
        assertNull(StickyTargets.liveTarget());

        StickyTargets.onLocalAttackPlayer("enemy2");
        assertEquals("enemy2", StickyTargets.liveTarget());
        env.world = new Object();
        assertNull(StickyTargets.liveTarget());

        StickyTargets.onLocalAttackPlayer("self");
        assertNull(StickyTargets.liveTarget(), "the local player is never locked");
    }
}
