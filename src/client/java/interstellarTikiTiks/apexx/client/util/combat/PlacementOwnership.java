package interstellarTikiTiks.apexx.client.util.combat;

import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.Entity;
import net.minecraft.util.math.BlockPos;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * Tracks which crystals and respawn anchors <em>we</em> placed, so modules can refuse to detonate
 * someone else's.
 *
 * <h2>Why this matters in a fight</h2>
 * Blowing an opponent's crystal is usually a mistake, not a bonus. They placed it where it damages
 * <b>you</b>, not them — popping it on their timing does their work for you. It is also the standard
 * bait: place a crystal you have no intention of hitting, and let an auto-crystal detonate it into
 * its own face. The same holds for respawn anchors, where the blast is larger and the placer picked
 * the geometry.
 *
 * <h2>How ownership is decided</h2>
 * A crystal entity has no "who placed this" field, so ownership is inferred from the placement we
 * just performed: when a module places on a base block it calls {@link #markPlaced}, and any crystal
 * that appears on that exact base within {@link #CLAIM_WINDOW_TICKS} is claimed as ours and pinned
 * by UUID from then on. The short window is deliberate — a base we used ten seconds ago may well be
 * holding the opponent's crystal now.
 *
 * <p>Entries are pruned on every query, and the whole map is cleared on world change, so this
 * cannot grow without bound or leak claims across worlds.
 */
public final class PlacementOwnership {

    private PlacementOwnership() {
    }

    /**
     * How long after placing on a base we still claim a crystal appearing there. Placement and the
     * entity spawning are separated by the server round trip, so this has to cover latency — but
     * staying short is what stops us claiming an opponent's later crystal on the same block.
     */
    private static final int CLAIM_WINDOW_TICKS = 40;   // ~2s

    /** Bases we placed on recently: packed BlockPos -> client tick at placement. */
    private static final Map<Long, Integer> recentBases = new HashMap<>();

    /** Entities positively identified as ours: entity id -> client tick when claimed. */
    private static final Map<Integer, Integer> ownedEntities = new HashMap<>();

    /** Claims are only meaningful within one world. */
    private static Object worldKey = null;

    /** Record that we just placed something on {@code base}. */
    public static void markPlaced(BlockPos base) {
        if (base == null) return;
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null) return;
        checkWorld(mc);
        recentBases.put(base.asLong(), clock(mc));
    }

    /** Record an entity as definitively ours (e.g. one we just spawned and are tracking). */
    public static void markOwned(Entity entity) {
        if (entity == null) return;
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null) return;
        checkWorld(mc);
        ownedEntities.put(entity.getId(), clock(mc));
    }

    /**
     * Whether {@code entity} is one we placed.
     *
     * <p>A crystal sits on the block BELOW it, so the base is {@code blockPos().down()} — checking
     * the crystal's own position against our placement list would never match.
     */
    public static boolean isOwned(Entity entity) {
        if (entity == null) return false;
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null) return false;
        checkWorld(mc);
        int now = clock(mc);
        prune(now);

        if (ownedEntities.containsKey(entity.getId())) return true;

        BlockPos base = entity.getBlockPos().down();
        Integer placedAt = recentBases.get(base.asLong());
        if (placedAt != null && now - placedAt <= CLAIM_WINDOW_TICKS) {
            // Pin it by id so it stays ours even after the base claim expires — otherwise a
            // crystal we placed becomes "not ours" mid-cycle and we stop detonating our own work.
            ownedEntities.put(entity.getId(), now);
            return true;
        }
        return false;
    }

    /** Forget a specific entity (it died, or we deliberately released it). */
    public static void forget(Entity entity) {
        if (entity != null) ownedEntities.remove(entity.getId());
    }

    // ── Blocks (respawn anchors) ────────────────────────────────────────────────
    //
    // Anchors are tracked separately from crystals because their lifetime is completely
    // different. A crystal claim has to expire in seconds — the base may be reused by the
    // opponent almost immediately. A placed anchor, by contrast, stays exactly where it is and
    // stays OURS until it is detonated or broken, which can be a long time in a drawn-out fight.
    // Ageing anchor claims out on a timer would make our own anchor stop being ours mid-charge.

    private static final java.util.Set<Long> ownedBlocks = new java.util.HashSet<>();
    /** Bound so a long session cannot grow this without limit. */
    private static final int MAX_OWNED_BLOCKS = 512;

    /** Record that we placed a block (respawn anchor) at {@code pos}. */
    public static void markPlacedBlock(BlockPos pos) {
        if (pos == null) return;
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null) return;
        checkWorld(mc);
        if (ownedBlocks.size() >= MAX_OWNED_BLOCKS) ownedBlocks.clear();
        ownedBlocks.add(pos.asLong());
    }

    /** Whether we placed the block at {@code pos}. */
    public static boolean isOwnedBlock(BlockPos pos) {
        if (pos == null) return false;
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null) return false;
        checkWorld(mc);
        return ownedBlocks.contains(pos.asLong());
    }

    /** Release a block claim once it is gone (detonated or broken). */
    public static void forgetBlock(BlockPos pos) {
        if (pos != null) ownedBlocks.remove(pos.asLong());
    }

    public static void clear() {
        recentBases.clear();
        ownedEntities.clear();
        ownedBlocks.clear();
    }

    /**
     * Tick clock for claim ages: world time, which keeps counting across a respawn. The player's own
     * {@code age} restarts at 0 on respawn, which made every older claim look newer than it was.
     */
    private static int clock(MinecraftClient mc) {
        return mc.world != null ? (int) mc.world.getTime() : mc.player.age;
    }

    private static void checkWorld(MinecraftClient mc) {
        Object current = mc.world;
        if (current != worldKey) {
            worldKey = current;
            clear();
        }
    }

    private static void prune(int now) {
        // Entity ids are reused by the server, so a stale claim could mis-flag a NEW entity as
        // ours. Both maps therefore age out rather than persisting.
        int entityTtl = CLAIM_WINDOW_TICKS * 8;   // ~16s, comfortably longer than a crystal lives
        for (Iterator<Map.Entry<Long, Integer>> it = recentBases.entrySet().iterator(); it.hasNext(); ) {
            if (now - it.next().getValue() > CLAIM_WINDOW_TICKS) it.remove();
        }
        for (Iterator<Map.Entry<Integer, Integer>> it = ownedEntities.entrySet().iterator(); it.hasNext(); ) {
            if (now - it.next().getValue() > entityTtl) it.remove();
        }
    }
}
