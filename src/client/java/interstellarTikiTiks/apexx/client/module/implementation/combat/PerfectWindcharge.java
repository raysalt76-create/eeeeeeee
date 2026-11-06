package interstellarTikiTiks.apexx.client.module.implementation.combat;

import interstellarTikiTiks.apexx.client.mixin.LivingEntityAccessor;
import interstellarTikiTiks.apexx.client.module.Category;
import interstellarTikiTiks.apexx.client.module.SubCategory;
import interstellarTikiTiks.apexx.client.module.Module;
import interstellarTikiTiks.apexx.client.module.SliderSetting;
import interstellarTikiTiks.apexx.client.module.hooks.JumpInputProvider;
import interstellarTikiTiks.apexx.client.util.SystemInputSimulator;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.Entity;
import net.minecraft.entity.projectile.WindChargeEntity;
import net.minecraft.util.math.Vec3d;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * Automatically jumps when a wind charge is thrown at the player's feet.
 *
 *
 * <h2>Algorithm</h2>
 * Each tick (when on the ground and the module is enabled):
 * <ol>
 *   <li>Clears expired entity IDs from the cooldown map ({@code cooldownMap}).</li>
 *   <li>Checks if the world changed and resets state if so.</li>
 *   <li>If {@code ticksRemaining > 0}, decrements it and returns {@code true} to keep the
 *       jump held — giving the hold a 2-tick window.</li>
 *   <li>Otherwise, only while vanilla's {@code jumpingCooldown} is 0 (never cleared here),
 *       scans all {@link WindChargeEntity} instances in the world:
 *       <ul>
 *         <li>Skips removed/dead/already-triggered entities.</li>
 *         <li>Skips wind charges whose Y-trajectory band falls outside
 *             {@code [player.y - 0.65, player.y + 1.35]}.</li>
 *         <li>Computes the 3-D squared distance from the player's feet
 *             ({@code player.y + 0.05}) to the wind charge's velocity segment
 *             {@code [pos, pos + velocity]}.</li>
 *         <li>Selects the closest one within {@code triggerRange²}.</li>
 *       </ul>
 *   </li>
 *   <li>On a match: registers the entity ID in the cooldown map, sets
 *       {@code ticksRemaining = 1}, returns {@code true}; the hook seam presses jump.</li>
 * </ol>
 *
 * <p>Implements {@link JumpInputProvider} so {@code ClientPlayerEntityMixin} can
 * query via the decoupled hook protocol without importing this class directly.
 */
public final class PerfectWindcharge extends Module implements JumpInputProvider {

    // ── Constants ────────────────────────────────────────────────────────────
    /** Cooldown duration in ticks before a triggered entity ID is removed. */
    private static final int COOLDOWN_TICKS = 30;
    /** Number of additional ticks the jump is held after the initial trigger tick. */
    private static final int HOLD_TICKS = 1;
    /** Maximum Y above player.getY() that the wind charge trajectory may reach. */
    private static final double MAX_Y_ABOVE = 1.35;
    /** Maximum Y below player.getY() that the wind charge trajectory may reach. */
    private static final double MAX_Y_BELOW = 0.65;

    // ── Settings ─────────────────────────────────────────────────────────────
    private final SliderSetting triggerRange = new SliderSetting(
            "Trigger Range", 1.15, 0.5, 2.5, 2
    ).setDescription("How close, in blocks, a wind charge's path must come to your feet for the jump to fire. Larger catches more charges but may jump for ones that would miss you.");

    private final SliderSetting retriggerCooldown = new SliderSetting(
            "Retrigger Cooldown (ticks)", COOLDOWN_TICKS, 0, 100, 0
    ).withJitter(5).setDescription("Ticks before the same wind charge can trigger another jump, so one charge gives one jump.").setSection("Delays");
    private final SliderSetting jumpHold = new SliderSetting(
            "Jump Hold (ticks)", HOLD_TICKS, 0, 10, 0
    ).withJitter(1).setDescription("Extra ticks the jump key stays held after the trigger tick. 1 is enough for a full jump; 0 is a one-tick tap.").setSection("Delays");

    // ── State ─────────────────────────────────────────────────────────────────
    /** Maps entity ID → player.age expiry at which the cooldown expires. */
    private final Map<Integer, Integer> cooldownMap = new HashMap<>();
    /** Cached world reference used to detect dimension changes. */
    private Object lastWorld;
    /** Remaining hold ticks: when > 0, force-jump without re-scanning. */
    private int ticksRemaining;

    // ──────────────────────────────────────────────────────────────────────────

    public PerfectWindcharge() {
        super("PerfectWindcharge", "Times your jump for wind-charge launches: when a wind charge (yours or anyone's) is about to burst at your feet, it presses jump in exactly the right tick so the burst and your jump stack into the highest possible launch, the setup for a mace smash.", Category.COMBAT);
        setSubCategory(SubCategory.MACE);
        addSetting(triggerRange);
        addSetting(retriggerCooldown);
        addSetting(jumpHold);
    }

    // ── Module lifecycle ──────────────────────────────────────────────────────

    @Override
    public void onEnable() {
        resetState();
    }

    @Override
    public void onDisable() {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (!isClientReady(mc)) {
            resetState();
            return;
        }
        checkWorld(mc);
        cleanupExpired(mc.player.age);
        ticksRemaining = 0;
        if (mc.options != null && SystemInputSimulator.isSimulatedPressed(mc.options.jumpKey)) {
            SystemInputSimulator.releaseJump();
        }
    }

    @Override
    protected void onRuntimeStateReleased() {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc != null && mc.options != null && SystemInputSimulator.isSimulatedPressed(mc.options.jumpKey)) {
            SystemInputSimulator.releaseJump();
        }
        resetState();
    }

    // ── JumpInputProvider ─────────────────────────────────────────────────────

    /**
     * Called every tick from {@code ClientPlayerEntityMixin}. Returns {@code true}
     * when the jump key should be forced pressed.
     */
    @Override
    public boolean shouldJump(MinecraftClient mc) {
        if (!this.isEnabled() || !isClientReady(mc) || !mc.player.isOnGround()) {
            ticksRemaining = 0;
            return false;
        }

        checkWorld(mc);
        cleanupExpired(mc.player.age);

        if (ticksRemaining > 0) {
            --ticksRemaining;
            return true;
        }

        // Vanilla only jumps while its jump cooldown is 0 (it is non-zero only while jump is held
        // through a landing); a jump it would not make is not forced. The charge is retried next tick.
        if (!vanillaJumpReady(mc.player)) {
            return false;
        }

        WindChargeEntity target = findTriggerWindCharge(mc);
        if (target == null) {
            return false;
        }

        cooldownMap.put(target.getId(), mc.player.age + (int) Math.round(retriggerCooldown.sample()));
        ticksRemaining = (int) Math.round(jumpHold.sample());
        // Single jump input path: the JumpInputProvider seam (ClientPlayerEntityMixin) presses the jump
        // key for this tick; no second press is made here.
        return true;
    }

    // ── Core detection ────────────────────────────────────────────────────────

    /**
     * Scans all wind charges in the world and returns the closest one whose
     * velocity segment passes within {@code triggerRange} of the player's feet,
     * or {@code null} if none qualify.
     */
    private WindChargeEntity findTriggerWindCharge(MinecraftClient mc) {
        double range = triggerRange.getValue();
        double rangeSquared = range * range;

        ClientPlayerEntity player = mc.player;
        // Use a point slightly above the player's feet.
        Vec3d feetPos = new Vec3d(player.getX(), player.getY() + 0.05, player.getZ());

        WindChargeEntity best = null;
        double bestDistSq = Double.MAX_VALUE;

        for (Entity entity : mc.world.getEntities()) {
            if (!(entity instanceof WindChargeEntity wce)) continue;
            if (wce.isRemoved() || !wce.isAlive()) continue;
            if (cooldownMap.containsKey(wce.getId())) continue;

            // Check whether the point is within the vertical band.
            double minTrajY = Math.min(wce.getY(), wce.getY() + wce.getVelocity().y);
            double maxTrajY = Math.max(wce.getY(), wce.getY() + wce.getVelocity().y);
            if (minTrajY > player.getY() + MAX_Y_ABOVE) continue;
            if (maxTrajY < player.getY() - MAX_Y_BELOW) continue;

            // Check distance to the wind-charge trajectory segment.
            Vec3d segStart = new Vec3d(wce.getX(), wce.getY(), wce.getZ());
            Vec3d segEnd   = segStart.add(wce.getVelocity());
            double distSq  = pointToSegmentDistanceSquared(feetPos, segStart, segEnd);

            if (distSq <= rangeSquared && distSq < bestDistSq) {
                bestDistSq = distSq;
                best = wce;
            }
        }
        return best;
    }

    /**
     * Computes the squared 3-D distance from point {@code p} to the closest
     * point on the line segment {@code [a, b]}.
     *
     */
    private double pointToSegmentDistanceSquared(Vec3d p, Vec3d a, Vec3d b) {
        Vec3d ab = b.subtract(a);
        double lenSq = ab.lengthSquared();
        if (lenSq <= 1.0e-8) {
            return p.squaredDistanceTo(a);
        }
        double t = p.subtract(a).dotProduct(ab) / lenSq;
        t = Math.max(0.0, Math.min(1.0, t));
        return p.squaredDistanceTo(a.add(ab.multiply(t)));
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /** True when vanilla's own {@code jumpingCooldown} would let a jump happen this tick. */
    private boolean vanillaJumpReady(ClientPlayerEntity player) {
        return ((LivingEntityAccessor) player).apexx$getJumpingCooldown() <= 0;
    }

    /**
     * Detects dimension / world changes and wipes all per-world state when they occur.
     * This prevents stale entity IDs from carrying across world transitions.
     */
    private void checkWorld(MinecraftClient mc) {
        if (lastWorld == mc.world) return;
        lastWorld = mc.world;
        cooldownMap.clear();
        ticksRemaining = 0;
    }

    /**
     * Removes all cooldown entries whose expiry age is ≤ {@code currentAge}.
     *
     * @param currentAge {@code mc.player.age} at the time of the tick
     */
    private void cleanupExpired(int currentAge) {
        Iterator<Map.Entry<Integer, Integer>> it = cooldownMap.entrySet().iterator();
        while (it.hasNext()) {
            if (it.next().getValue() <= currentAge) {
                it.remove();
            }
        }
    }

    /** Clears all transient state. Called on enable and when no valid client context exists. */
    private void resetState() {
        cooldownMap.clear();
        ticksRemaining = 0;
        lastWorld = null;
    }

    /**
     * Returns {@code true} when the client context is fully ready for module operation.
     *
     * <p>Checks player, world, options, no screen,
     * player is alive, not flying, not in water, not in lava, not climbing.
     */
    private boolean isClientReady(MinecraftClient mc) {
        return mc != null
                && mc.player != null
                && mc.world != null
                && mc.options != null
                && mc.currentScreen == null
                && mc.player.isAlive()
                && !mc.player.getAbilities().flying
                && !mc.player.isTouchingWater()
                && !mc.player.isSubmergedInWater()
                && !mc.player.isInLava()
                && !mc.player.isClimbing();
    }
}
