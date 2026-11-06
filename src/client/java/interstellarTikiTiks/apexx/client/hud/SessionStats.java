package interstellarTikiTiks.apexx.client.hud;

import interstellarTikiTiks.apexx.client.friend.FriendManager;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerEntity;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * Per-session (one server join) combat tally for the Session Stats widget. Kill credit follows the
 * Kill Effects approach: a living target you hit within the credit window that then dies (death status,
 * or zero health on servers that skip the status) counts as your kill. Friends never count.
 * Everything here runs on the client thread (vanilla attack seam, entity-status handler, client tick).
 */
public final class SessionStats {
    private static final byte STATUS_DEATH = 3;
    private static final byte STATUS_TOTEM = 35;
    private static final int MAX_TRACKED = 64;

    /** entity id -> last hit time (ms). */
    private static final Map<Integer, Long> recentHits = new HashMap<>();
    private static Object hitsWorld;
    private static int kills;
    private static int deaths;
    private static int selfPops;
    private static int enemyPops;
    private static boolean wasDead;
    private static volatile long sessionStartMs = System.currentTimeMillis();

    /** Credit window / mob counting come from the widget; defaults apply while it is absent. */
    static volatile SessionStatsHudElement config;

    private SessionStats() { }

    public static void onLocalAttack(Entity target) {
        CombatState.onLocalAttack();
        if (!(target instanceof LivingEntity living)) return;
        // Hitting a corpse during its death animation must not credit a second kill.
        if (living.isDead() || living.getHealth() <= 0.0f || !eligible(living)) return;
        syncWorld();
        long now = System.currentTimeMillis();
        recentHits.put(living.getId(), now);
        if (recentHits.size() > MAX_TRACKED) prune(now);
    }

    public static void onEntityStatus(Entity entity, byte status) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (entity == null || mc.player == null) return;
        syncWorld();
        if (status == STATUS_TOTEM) {
            if (entity == mc.player) {
                selfPops++;
            } else if (entity instanceof PlayerEntity && recentlyHit(entity.getId())) {
                enemyPops++;
            }
        } else if (status == STATUS_DEATH && entity != mc.player && entity instanceof LivingEntity living) {
            Long hitAt = recentHits.remove(living.getId());
            if (hitAt != null && System.currentTimeMillis() - hitAt <= windowMs() && eligible(living)) kills++;
        }
    }

    public static void tick(MinecraftClient mc) {
        if (mc == null || mc.player == null || mc.world == null) {
            recentHits.clear();
            wasDead = false;
            return;
        }
        syncWorld();
        boolean dead = mc.player.isDead() || mc.player.getHealth() <= 0.0f;
        if (dead && !wasDead) deaths++;
        wasDead = dead;

        if (recentHits.isEmpty()) return;
        long now = System.currentTimeMillis();
        prune(now);
        // Fallback for servers that remove dead players without a death status.
        Iterator<Map.Entry<Integer, Long>> it = recentHits.entrySet().iterator();
        while (it.hasNext()) {
            Entity e = mc.world.getEntityById(it.next().getKey());
            if (e instanceof LivingEntity living && (living.isDead() || living.getHealth() <= 0.0f)) {
                it.remove();
                if (eligible(living)) kills++;
            }
        }
    }

    /** New session: called on server join. */
    public static void reset() {
        recentHits.clear();
        hitsWorld = null;
        kills = deaths = selfPops = enemyPops = 0;
        wasDead = false;
        sessionStartMs = System.currentTimeMillis();
    }

    public static int kills() { return kills; }
    public static int deaths() { return deaths; }
    public static int selfPops() { return selfPops; }
    public static int enemyPops() { return enemyPops; }
    public static long sessionStartMs() { return sessionStartMs; }
    public static long sessionMillis() { return Math.max(0L, System.currentTimeMillis() - sessionStartMs); }

    private static boolean recentlyHit(int id) {
        Long hitAt = recentHits.get(id);
        return hitAt != null && System.currentTimeMillis() - hitAt <= windowMs();
    }

    private static boolean eligible(LivingEntity living) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null || living == mc.player) return false;
        SessionStatsHudElement cfg = config;
        boolean countMobs = cfg != null && cfg.countMobs();
        if (!countMobs && !(living instanceof PlayerEntity)) return false;
        return !(living instanceof PlayerEntity player && FriendManager.getInstance().isFriend(player.getUuid()));
    }

    private static long windowMs() {
        SessionStatsHudElement cfg = config;
        return cfg == null ? 3000L : cfg.creditWindowMs();
    }

    private static void prune(long now) {
        long limit = windowMs();
        recentHits.values().removeIf(t -> now - t > limit);
    }

    private static void syncWorld() {
        Object world = MinecraftClient.getInstance().world;
        if (world != hitsWorld) {
            hitsWorld = world;
            recentHits.clear();
        }
    }

    /** "1h 04m", "12m 09s" or "45s". */
    public static String formatDuration(long millis) {
        long s = Math.max(0L, millis / 1000L);
        long h = s / 3600L, m = (s % 3600L) / 60L, sec = s % 60L;
        if (h > 0) return h + "h " + (m < 10 ? "0" : "") + m + "m";
        if (m > 0) return m + "m " + (sec < 10 ? "0" : "") + sec + "s";
        return sec + "s";
    }
}
