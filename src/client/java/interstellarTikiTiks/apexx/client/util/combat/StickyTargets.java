package interstellarTikiTiks.apexx.client.util.combat;

import interstellarTikiTiks.apexx.client.friend.FriendManager;
import interstellarTikiTiks.apexx.client.manager.ModuleManager;
import interstellarTikiTiks.apexx.client.module.BooleanSetting;
import interstellarTikiTiks.apexx.client.module.Category;
import interstellarTikiTiks.apexx.client.module.Module;
import interstellarTikiTiks.apexx.client.module.SliderSetting;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Universal "Sticky Target" lock: ONE sticky target shared by every module that has Sticky Target on.
 *
 * <p>When the local player attacks a (non-friend) player while at least one enabled module has Sticky
 * Target on and there is no live lock, that player becomes THE sticky target. From then on every
 * enabled module with Sticky Target on may only select, aim at or attack that player; other players are
 * forbidden to them. Modules with Sticky Target off ignore the lock entirely, and non-player entities
 * are never affected. With every module's Sticky Target off nothing is ever locked, so behaviour is
 * unchanged.</p>
 *
 * <p>The lock is released when the target dies, is removed or is no longer the same entity in the current
 * world, becomes a friend, the world changes, no enabled module has Sticky Target on any more, or the
 * target is farther than the release distance. Each module keeps its own "Sticky Release Distance"
 * setting; because the lock is shared, the LARGEST value among the enabled modules with Sticky Target
 * on is the one that applies (a module with a shorter value never cuts the lock short for the others).</p>
 *
 * <p>Target policy stays in the modules; this class only answers {@link #allows(Object, Entity)} for their
 * candidate filters and for {@code AttackValidator}. The lock lives in memory only and never survives a
 * world change.</p>
 */
public final class StickyTargets {
    public static final String STICKY_SETTING = "Sticky Target";
    public static final String RELEASE_SETTING = "Sticky Release Distance";
    private static final String SECTION = "Targeting";
    private static final double DEFAULT_RELEASE = 8.0;

    /**
     * Combat-category modules that never pick, aim at or attack another entity (self-healing,
     * inventory refills, global tuning holders). They get no sticky settings, since the option
     * would do nothing there. Matched by display name: class names are renamed in obfuscated builds.
     */
    private static final Set<String> NO_TARGET_MODULES = Set.of(
            "AutoTotem", "AutoHeal", "PotRefill", "CartRefill",
            "AutoChorus");

    private record Options(BooleanSetting sticky, SliderSetting release) {}

    /** The shared lock: the target (a {@link PlayerEntity} in game) and the world it was taken in. */
    private record Lock(Object player, Object world) {}

    /**
     * The game state the lock is checked against. Players and worlds are opaque here so the lock rules
     * can be unit-tested without a running client; the in-game implementation is {@link ClientEnv}.
     */
    interface Env {
        /** The current client world, or null when there is no world or no local player. */
        Object world();

        boolean isSelf(Object player);

        boolean isFriend(Object player);

        /** Alive, not removed, and still the same entity in {@code world}. */
        boolean isPresent(Object player, Object world);

        /** Squared distance from the local player. */
        double distanceSq(Object player);

        /** Same player (identity or UUID). */
        boolean samePlayer(Object a, Object b);
    }

    private static final Env CLIENT_ENV = new ClientEnv();
    private static volatile Env env = CLIENT_ENV;

    private static final Map<Module, Options> OPTIONS = new ConcurrentHashMap<>();
    private static final AtomicReference<Lock> LOCK = new AtomicReference<>();

    private StickyTargets() {}

    /** Adds the two sticky settings to a combat module. Idempotent; other modules are left untouched. */
    public static void attach(Module module) {
        if (module == null || module.getCategory() != Category.COMBAT || OPTIONS.containsKey(module)) return;
        if (NO_TARGET_MODULES.contains(module.getName())) return;
        if (module.getSetting(STICKY_SETTING) != null || module.getSetting(RELEASE_SETTING) != null) return;

        BooleanSetting sticky = new BooleanSetting(STICKY_SETTING, false);
        sticky.setSection(SECTION);
        sticky.setDescription("After an attack lands on a player, ignore every other player until that one is"
                + " farther than the release distance, dies, leaves or becomes a friend. The lock is shared:"
                + " every module with Sticky Target on stays on the same player.");
        sticky.onChange(() -> {
            if (!sticky.isEnabled()) release(module);
        });

        SliderSetting release = new SliderSetting(RELEASE_SETTING, DEFAULT_RELEASE, 3.0, 32.0, 1);
        release.setSection(SECTION);
        release.setDescription("Distance in blocks at which the shared sticky lock lets go of the player."
                + " With several modules using Sticky Target, the largest value among the enabled ones applies.");
        release.setDependsOn(STICKY_SETTING);
        release.setVisibility(sticky::isEnabled);

        // getSettings() is the module's own list; captureDefault mirrors Module.addSetting so the
        // GUI's "modified" marker and reset-to-default behave like any other setting.
        sticky.captureDefault();
        release.captureDefault();
        module.getSettings().add(sticky);
        module.getSettings().add(release);
        OPTIONS.put(module, new Options(sticky, release));
    }

    /** True when {@code owner} is an enabled module with Sticky Target on (it follows the shared lock). */
    public static boolean isSticky(Object owner) {
        if (!(owner instanceof Module module)) return false;
        Options options = OPTIONS.get(module);
        return options != null && options.sticky().isEnabled() && module.isEnabled();
    }

    /**
     * The shared locked player when {@code owner} follows it (enabled, Sticky Target on), else null.
     * Applies every release rule (and drops a stale lock).
     */
    public static PlayerEntity lockedFor(Object owner) {
        if (!isSticky(owner)) return null;
        return (PlayerEntity) liveTarget();
    }

    /**
     * True unless the owner has Sticky Target on, a live shared lock exists, and {@code entity} is a
     * different player. Calls without a module owner (manual input) are always allowed.
     */
    public static boolean allows(Object owner, Entity entity) {
        if (!(entity instanceof PlayerEntity)) return true;
        return allowsPlayer(owner, entity);
    }

    /**
     * Called when the local player attacks {@code target} (any path: module dispatch, simulated click
     * or manual click). With no live lock and at least one enabled sticky module, that player becomes
     * the shared sticky target.
     */
    public static void onLocalAttack(Entity target) {
        if (!(target instanceof PlayerEntity)) return;
        onLocalAttackPlayer(target);
    }

    /**
     * {@code owner} no longer follows the lock (its Sticky Target was turned off). The shared lock is
     * dropped only when no other enabled module still has Sticky Target on.
     */
    public static void release(Object owner) {
        if (!anySticky()) LOCK.set(null);
    }

    public static void clearAll() {
        LOCK.set(null);
    }

    /** Drops the shared lock when a release condition holds. Cheap; called once per client tick. */
    public static void tick() {
        Lock lock = LOCK.get();
        if (lock != null && !isLive(lock)) LOCK.compareAndSet(lock, null);
    }

    /** Attaches the settings to every module currently registered (used after bulk discovery). */
    public static void attachAll(ModuleManager manager) {
        if (manager == null) return;
        for (Module module : manager.getModules()) attach(module);
    }

    // ---- core rules (players as opaque objects so tests can drive them) ------------------------------

    static boolean allowsPlayer(Object owner, Object player) {
        if (player == null || !isSticky(owner)) return true;
        Object locked = liveTarget();
        return locked == null || env.samePlayer(locked, player);
    }

    static void onLocalAttackPlayer(Object player) {
        Env e = env;
        Object world = e.world();
        if (player == null || world == null || e.isSelf(player) || e.isFriend(player)) return;
        if (!anySticky() || liveTarget() != null) return;
        Lock lock = new Lock(player, world);
        if (!isLive(lock)) return;
        Lock current = LOCK.get();
        // Another live lock may have been taken in between (network vs client thread); keep it.
        if (current != null && isLive(current)) return;
        LOCK.compareAndSet(current, lock);
    }

    /** The live shared target, or null. Drops a lock whose release condition holds. */
    static Object liveTarget() {
        Lock lock = LOCK.get();
        if (lock == null) return null;
        if (!isLive(lock)) {
            LOCK.compareAndSet(lock, null);
            return null;
        }
        return lock.player();
    }

    /**
     * The release distance that applies to the shared lock: the largest "Sticky Release Distance" among
     * enabled modules with Sticky Target on, or a negative value when there is no such module.
     */
    static double effectiveReleaseDistance() {
        double max = -1.0;
        for (Map.Entry<Module, Options> entry : OPTIONS.entrySet()) {
            Options options = entry.getValue();
            if (!options.sticky().isEnabled() || !entry.getKey().isEnabled()) continue;
            double value = options.release().getValue();
            if (Double.isFinite(value) && value > max) max = value;
        }
        return max;
    }

    private static boolean anySticky() {
        return effectiveReleaseDistance() >= 0.0;
    }

    private static boolean isLive(Lock lock) {
        double release = effectiveReleaseDistance();
        if (release < 0.0) return false;
        Env e = env;
        Object world = e.world();
        if (world == null || lock.world() != world) return false;
        Object player = lock.player();
        if (player == null || !e.isPresent(player, world) || e.isFriend(player)) return false;
        return e.distanceSq(player) <= release * release;
    }

    // ---- test hooks -----------------------------------------------------------------------------------

    /** Test-only: replaces the game-state source; null restores the client. */
    static void setEnvForTests(Env testEnv) {
        env = testEnv == null ? CLIENT_ENV : testEnv;
    }

    /** Test-only: forgets every attached module and the lock, and restores the client env. */
    static void resetForTests() {
        OPTIONS.clear();
        LOCK.set(null);
        env = CLIENT_ENV;
    }

    /** The running client's state. */
    private static final class ClientEnv implements Env {
        @Override
        public Object world() {
            MinecraftClient mc = MinecraftClient.getInstance();
            if (mc == null || mc.player == null) return null;
            return mc.world;
        }

        @Override
        public boolean isSelf(Object player) {
            MinecraftClient mc = MinecraftClient.getInstance();
            return mc != null && player == mc.player;
        }

        @Override
        public boolean isFriend(Object player) {
            return player instanceof PlayerEntity p && FriendManager.getInstance().isFriend(p.getUuid());
        }

        @Override
        public boolean isPresent(Object player, Object world) {
            if (!(player instanceof PlayerEntity p)) return false;
            MinecraftClient mc = MinecraftClient.getInstance();
            if (mc == null || mc.world == null || world != mc.world) return false;
            return !p.isRemoved() && p.isAlive() && p.getHealth() > 0.0f
                    && p.getEntityWorld() == mc.world && mc.world.getEntityById(p.getId()) == p;
        }

        @Override
        public double distanceSq(Object player) {
            MinecraftClient mc = MinecraftClient.getInstance();
            if (mc == null || mc.player == null || !(player instanceof PlayerEntity p)) return Double.MAX_VALUE;
            return mc.player.squaredDistanceTo(p);
        }

        @Override
        public boolean samePlayer(Object a, Object b) {
            if (a == b) return true;
            return a instanceof PlayerEntity pa && b instanceof PlayerEntity pb
                    && pa.getUuid().equals(pb.getUuid());
        }
    }
}
