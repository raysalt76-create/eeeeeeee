package interstellarTikiTiks.apexx.client.util.combat;

import interstellarTikiTiks.apexx.client.mixin.ClientPlayerEntityAccessor;
import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.registry.tag.ItemTags;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;

import java.lang.ref.WeakReference;

/**
 * Vanilla 1.21.11 melee rules, shared by the sword modules (TriggerBot, HitSelect, the sprint-reset
 * modules, AutoJumpReset) so each one stops re-deriving them slightly differently.
 *
 * <p>Rules read from {@code PlayerEntity.attack} / {@code isCriticalHit} / {@code knockbackTarget}:
 * <ul>
 *   <li>A hit is <b>strong</b> when the attack cooldown progress is {@code > 0.9}. Only a strong hit
 *       can be a sprint-knockback hit or a critical.</li>
 *   <li><b>Sprint knockback</b>: strong and sprinting. The hit then multiplies the attacker's own
 *       horizontal velocity by 0.6 and clears the attacker's sprint flag (this is what hit-select
 *       relies on, and why sprint must be reset afterwards).</li>
 *   <li><b>Critical</b>: strong, {@code fallDistance > 0}, not on ground, not climbing, not touching
 *       water, not blind, no vehicle, living target, and <i>not sprinting</i>.</li>
 * </ul>
 *
 * <p><b>Server sprint model.</b> The server decides sprint knockback and crits from its own copy of
 * the sprint flag, which only changes on the client's START/STOP sprint commands and on a sprint hit
 * (the server clears it in {@code knockbackTarget}). The client clears its local flag on the same hit
 * but, with the sprint key held, re-sprints in the same tick before {@code sendSprintingPacket} runs,
 * so no packet is sent and the two copies disagree until a STOP/START pair goes out. This class
 * tracks that: {@code lastSprinting} is what the server was last told, and a sprint hit marks it
 * spent until a STOP is observed. STATICALLY VERIFIED against the 1.21.11 bytecode; the model is an
 * approximation of server state and is UNVERIFIED in-game (latency, server-side rule changes).
 *
 * <p>All state is touched on the client thread only (tick head, attack, main-thread packet handling).
 */
public final class MeleeMechanics {

    /** {@code PlayerEntity.attack}: {@code cooldownProgress > 0.9f}. */
    public static final float STRONG_ATTACK_THRESHOLD = 0.9f;
    /** {@code LivingEntity.damage}: a hit is absorbed while {@code timeUntilRegen > 10}. */
    public static final int DAMAGE_IMMUNITY_THRESHOLD = 10;
    /** Vertical drag applied after gravity each airborne tick ({@code LivingEntity.travel}). */
    public static final double VERTICAL_DRAG = 0.98;
    /** Default player gravity; {@link Entity#getFinalGravity()} is used where an entity is at hand. */
    public static final double DEFAULT_GRAVITY = 0.08;
    /** Horizontal air drag ({@code 0.91}) applied to airborne motion. */
    public static final double AIR_DRAG = 0.91;

    /** A tick count that means "never" / "not within the search window". */
    public static final int NONE = -1;

    /** One local attack, recorded at {@code attackEntity} HEAD before vanilla applies it. */
    public record AttackRecord(int targetId, int age, boolean strong, boolean crit,
                               boolean sprintKnockback, boolean clientSprintingBefore) {
    }

    /** The player instance the state belongs to (respawn keeps the entity id, so compare instances). */
    private static WeakReference<ClientPlayerEntity> trackedPlayer = new WeakReference<>(null);
    private static boolean sprintSpent = false;
    private static int lastKnockbackAge = Integer.MIN_VALUE;
    private static AttackRecord lastAttack = null;

    private MeleeMechanics() {
    }

    // ── Lifecycle (called from mixins on the client thread) ─────────────────────────

    /** Tick head: drop state on respawn/world change and clear a spent sprint once a STOP went out. */
    public static void onClientTick(MinecraftClient mc) {
        ClientPlayerEntity player = mc == null ? null : mc.player;
        if (player == null) {
            reset();
            return;
        }
        if (trackedPlayer.get() != player) {
            reset();
            trackedPlayer = new WeakReference<>(player);
        }
        // lastSprinting changes only inside sendSprintingPacket, at most once per tick, so every
        // value it takes is visible at some tick head. Once the server was told STOP, a later START
        // is what sets it sprinting again; the spent mark is no longer needed.
        if (!((ClientPlayerEntityAccessor) player).getLastSprinting()) {
            sprintSpent = false;
        }
    }

    /** {@code ClientPlayerInteractionManager.attackEntity} HEAD for the local player. */
    public static void onAttackEntity(PlayerEntity player, Entity target) {
        if (!(player instanceof ClientPlayerEntity self) || target == null) {
            return;
        }
        boolean strong = isStrong(self);
        boolean serverSprinting = isServerSprinting(self);
        boolean living = target instanceof LivingEntity;
        boolean damages = living && !isInDamageImmunity((LivingEntity) target);
        boolean sprintKnockback = strong && serverSprinting && living;
        boolean crit = strong && living && isCritState(self) && !serverSprinting;
        lastAttack = new AttackRecord(target.getId(), self.age, strong, crit, sprintKnockback, self.isSprinting());
        // The server clears its sprint flag only when the hit actually lands (sidedDamage == true).
        if (sprintKnockback && damages) {
            sprintSpent = true;
        }
    }

    /** Main-thread velocity packet for the local player. Only knockback from a hit counts. */
    public static void onSelfVelocity(ClientPlayerEntity player) {
        // The damage packet is sent before the knockback velocity on the same connection, so
        // hurtTime is already set when this runs. Without it the velocity is not a hit (wind,
        // pistons, rods, server teleports).
        if (player != null && player.hurtTime > 0) {
            lastKnockbackAge = player.age;
        }
    }

    public static void reset() {
        trackedPlayer = new WeakReference<>(null);
        sprintSpent = false;
        lastKnockbackAge = Integer.MIN_VALUE;
        lastAttack = null;
    }

    // ── Queries ─────────────────────────────────────────────────────────────────────

    public static boolean isStrong(PlayerEntity player) {
        return player != null && player.getAttackCooldownProgress(0.5f) > STRONG_ATTACK_THRESHOLD;
    }

    /** Whether the server currently counts the local player as sprinting (see class doc). */
    public static boolean isServerSprinting(ClientPlayerEntity player) {
        return player != null && ((ClientPlayerEntityAccessor) player).getLastSprinting() && !sprintSpent;
    }

    /**
     * True after a hit cleared the server's sprint flag while the player still means to sprint, i.e.
     * the next ground hit will not carry sprint knockback unless a STOP/START pair is sent.
     */
    public static boolean needsSprintReset(ClientPlayerEntity player) {
        return player != null && sprintSpent;
    }

    /** {@code isCriticalHit} movement conditions, except the sprint check and the target type. */
    public static boolean isCritState(PlayerEntity player) {
        return player != null
                && player.fallDistance > 0.0
                && !player.isOnGround()
                && !player.isClimbing()
                && !player.isTouchingWater()
                && !player.hasStatusEffect(StatusEffects.BLINDNESS)
                && !player.hasVehicle();
    }

    /** Shared "Disable In Liquids" predicate: the player is touching water or lava. */
    public static boolean isInLiquid(Entity player) {
        return player != null && (player.isTouchingWater() || player.isInLava());
    }

    /** Shared "Disable In Air" predicate: the player has no ground under them. */
    public static boolean isInAir(Entity player) {
        return player != null && !player.isOnGround();
    }

    /** Whether a swing right now would be a vanilla critical against {@code target}. */
    public static boolean wouldCrit(ClientPlayerEntity player, Entity target) {
        return target instanceof LivingEntity && isStrong(player) && isCritState(player)
                && !isServerSprinting(player);
    }

    /** Conditions a crit can never satisfy in the near future, independent of timing. */
    public static boolean canEverCrit(PlayerEntity player) {
        return player != null
                && !player.isClimbing()
                && !player.isTouchingWater()
                && !player.hasStatusEffect(StatusEffects.BLINDNESS)
                && !player.hasVehicle()
                && !player.hasNoGravity()
                && !player.getAbilities().flying
                && !player.hasStatusEffect(StatusEffects.LEVITATION)
                && !player.hasStatusEffect(StatusEffects.SLOW_FALLING)
                && !isInCobweb(player);
    }

    /** Cobweb movement resets fall distance every tick, so a crit cannot happen inside one. */
    private static boolean isInCobweb(Entity entity) {
        if (entity.getEntityWorld() == null) {
            return false;
        }
        Box box = entity.getBoundingBox();
        BlockPos min = BlockPos.ofFloored(box.minX, box.minY, box.minZ);
        BlockPos max = BlockPos.ofFloored(box.maxX - 1.0E-7, box.maxY - 1.0E-7, box.maxZ - 1.0E-7);
        for (BlockPos pos : BlockPos.iterate(min, max)) {
            if (entity.getEntityWorld().getBlockState(pos).isOf(Blocks.COBWEB)) {
                return true;
            }
        }
        return false;
    }

    /** {@code canUseSweepAttack}: strong, not a crit, not a sprint hit, grounded, slow, holding a sword. */
    public static boolean wouldSweep(ClientPlayerEntity player, Entity target) {
        if (player == null || !isStrong(player) || !player.isOnGround()) {
            return false;
        }
        if (isServerSprinting(player) || wouldCrit(player, target)) {
            return false;
        }
        double max = player.getMovementSpeed() * 2.5;
        Vec3d motion = CombatPrediction.perTickVelocityVec(player);
        return motion.horizontalLengthSquared() < max * max
                && player.getMainHandStack().isIn(ItemTags.SWORDS);
    }

    /** A swing into the target's damage immunity deals no damage, no knockback and no sprint reset. */
    public static boolean isInDamageImmunity(LivingEntity target) {
        return target != null && target.timeUntilRegen > DAMAGE_IMMUNITY_THRESHOLD;
    }

    /** Ticks since knockback from a hit was applied to the local player, or {@link Integer#MAX_VALUE}. */
    public static int ticksSinceSelfKnockback(ClientPlayerEntity player) {
        if (player == null || lastKnockbackAge == Integer.MIN_VALUE) {
            return Integer.MAX_VALUE;
        }
        return Math.max(0, player.age - lastKnockbackAge);
    }

    public static AttackRecord lastAttack() {
        return lastAttack;
    }

    // ── Prediction (pure vertical physics) ─────────────────────────────────────────

    /**
     * Ticks until the player satisfies the falling part of a crit, from the vertical velocity that
     * the NEXT move will use. 0 means the crit state already holds. A move with negative velocity
     * makes {@code fallDistance > 0} for the tick after it, so the answer is (index of the first
     * downward move) + 1. Returns {@link #NONE} if no downward move happens within {@code maxTicks}.
     */
    public static int ticksUntilFalling(boolean critStateNow, double velocityY, double gravity, int maxTicks) {
        if (critStateNow) {
            return 0;
        }
        double vy = velocityY;
        for (int move = 0; move < maxTicks; move++) {
            if (vy < 0.0) {
                return move + 1;
            }
            vy = (vy - gravity) * VERTICAL_DRAG;
        }
        return NONE;
    }

    /** Vertical displacement after {@code ticks} airborne moves starting with {@code velocityY}. */
    public static double verticalOffset(double velocityY, double gravity, int ticks) {
        double y = 0.0;
        double vy = velocityY;
        for (int i = 0; i < ticks; i++) {
            y += vy;
            vy = (vy - gravity) * VERTICAL_DRAG;
        }
        return y;
    }

    /** Horizontal displacement factor for {@code ticks} airborne moves: {@code sum(0.91^i)}. */
    public static double airDriftFactor(int ticks) {
        double sum = 0.0;
        double f = 1.0;
        for (int i = 0; i < ticks; i++) {
            sum += f;
            f *= AIR_DRAG;
        }
        return sum;
    }

    /**
     * Ticks until the local player's next crit window opens (0 = open now), or {@link #NONE} if the
     * player lands first or the window is beyond {@code maxTicks}.
     */
    public static int ticksUntilCritWindow(ClientPlayerEntity player, int maxTicks) {
        if (player == null || !canEverCrit(player)) {
            return NONE;
        }
        return ticksUntilCritWindow(player, player.getVelocity().y, isCritState(player), 0, maxTicks);
    }

    /**
     * First tick {@code t >= minTicks} at which the player is falling and still airborne after
     * {@code t} moves that start with vertical velocity {@code startVy}; {@link #NONE} if the player
     * collides with the ground (or a ceiling) first. {@code minTicks = 1} leaves one movement tick
     * before the swing, e.g. for a sprint STOP to reach the server first.
     */
    public static int ticksUntilCritWindow(ClientPlayerEntity player, double startVy, boolean critStateNow,
                                           int minTicks, int maxTicks) {
        if (player == null || player.getEntityWorld() == null) {
            return NONE;
        }
        if (minTicks <= 0 && critStateNow) {
            return 0;
        }
        double gravity = player.getFinalGravity();
        Box box = player.getBoundingBox();
        double vy = startVy;
        double y = 0.0;
        for (int t = 1; t <= maxTicks; t++) {
            double move = vy;
            y += move;
            if (!player.getEntityWorld().isSpaceEmpty(player, box.offset(0.0, y, 0.0))) {
                return NONE;
            }
            // A downward move makes fallDistance > 0 for the swing that follows it.
            if (move < 0.0 && t >= Math.max(1, minTicks)) {
                return t;
            }
            vy = (vy - gravity) * VERTICAL_DRAG;
        }
        return NONE;
    }

    /**
     * Ticks until the planned crit swing of the current (or about to start) airtime: the first tick
     * {@code t >= minTicks} at which the player is in the crit state and has been falling for at least
     * {@code fallDelay} ticks (0 = the first falling tick). Falling ticks are counted from the first
     * downward move, so a repeated jump always puts the swing at the same point of the fall.
     *
     * <p>If the player lands before the delay is reached, the last falling tick before landing (still
     * {@code >= minTicks}) is returned, so a short drop still gets its crit; {@link #NONE} when no
     * falling tick is left, or the delay lies beyond {@code maxTicks}.
     *
     * @param startVy      vertical velocity of the next move (jump velocity when a jump starts this tick)
     * @param critStateNow whether {@link #isCritState} holds at this tick head
     * @param fallenTicks  ticks already spent in the crit state (0 on its first tick); ignored when not falling
     */
    public static int ticksUntilCritSwing(ClientPlayerEntity player, double startVy, boolean critStateNow,
                                          int fallenTicks, int fallDelay, int minTicks, int maxTicks) {
        if (player == null || player.getEntityWorld() == null) {
            return NONE;
        }
        Box box = player.getBoundingBox();
        return ticksUntilCritSwing(startVy, player.getFinalGravity(), critStateNow, fallenTicks, fallDelay,
                minTicks, maxTicks, y -> !player.getEntityWorld().isSpaceEmpty(player, box.offset(0.0, y, 0.0)));
    }

    /**
     * World-free core of {@link #ticksUntilCritSwing(ClientPlayerEntity, double, boolean, int, int, int, int)}:
     * {@code collidesAt} answers whether the player's box, moved vertically by the given offset,
     * hits a block (the ground or a ceiling).
     */
    public static int ticksUntilCritSwing(double startVy, double gravity, boolean critStateNow, int fallenTicks,
                                          int fallDelay, int minTicks, int maxTicks,
                                          java.util.function.DoublePredicate collidesAt) {
        int delay = Math.max(0, fallDelay);
        int fallen = Math.max(0, fallenTicks);
        if (critStateNow && minTicks <= 0 && fallen >= delay) {
            return 0;
        }
        int lastValid = critStateNow && minTicks <= 0 ? 0 : NONE;
        // Tick (relative to now) of the first falling tick head; negative while already falling.
        int firstFall = critStateNow ? -fallen : Integer.MIN_VALUE;
        double vy = startVy;
        double y = 0.0;
        for (int t = 1; t <= maxTicks; t++) {
            double move = vy;
            y += move;
            if (collidesAt.test(y)) {
                // Landed (or bumped a ceiling): the swing goes on the last falling tick before it.
                return lastValid;
            }
            if (move < 0.0) {
                if (firstFall == Integer.MIN_VALUE) {
                    firstFall = t;
                }
                if (t >= Math.max(1, minTicks)) {
                    if (t - firstFall >= delay) {
                        return t;
                    }
                    lastValid = t;
                }
            }
            vy = (vy - gravity) * VERTICAL_DRAG;
        }
        return NONE;
    }

    /** Vertical velocity of a jump started now: jump strength plus Jump Boost (block factors ignored). */
    public static double jumpVelocity(PlayerEntity player) {
        double v = player.getAttributeValue(EntityAttributes.JUMP_STRENGTH);
        StatusEffectInstance boost = player.getStatusEffect(StatusEffects.JUMP_BOOST);
        if (boost != null) {
            v += 0.1 * (boost.getAmplifier() + 1);
        }
        return v;
    }

    /**
     * Ticks until {@code entity} lands, from its observed per-tick motion, or {@link #NONE} if it does
     * not land within {@code maxTicks}. 0 when it is already on the ground.
     */
    public static int ticksUntilLanding(Entity entity, int maxTicks) {
        if (entity == null || entity.getEntityWorld() == null) {
            return NONE;
        }
        if (entity.isOnGround()) {
            return 0;
        }
        // Observed motion (pos - lastPos): remote players' getVelocity() is not kept up to date.
        double gravity = entity.getFinalGravity();
        double vy = entity.getY() - entity.lastY;
        double y = 0.0;
        Box box = entity.getBoundingBox();
        for (int tick = 1; tick <= maxTicks; tick++) {
            vy = (vy - gravity) * VERTICAL_DRAG;
            double next = y + vy;
            if (vy < 0.0 && !entity.getEntityWorld().isSpaceEmpty(entity, box.offset(0.0, next, 0.0))) {
                return tick;
            }
            y = next;
        }
        return NONE;
    }

    /** Predicted eye position of the local player after {@code ticks} airborne moves. */
    public static Vec3d predictEye(ClientPlayerEntity player, int ticks) {
        if (ticks <= 0) {
            return player.getEyePos();
        }
        Vec3d v = player.getVelocity();
        double drift = airDriftFactor(ticks);
        return player.getEyePos().add(v.x * drift, verticalOffset(v.y, player.getFinalGravity(), ticks), v.z * drift);
    }

    /** Distance from {@code eye} to the nearest point of {@code box}. */
    public static double distanceToBox(Vec3d eye, Box box) {
        double cx = Math.max(box.minX, Math.min(eye.x, box.maxX));
        double cy = Math.max(box.minY, Math.min(eye.y, box.maxY));
        double cz = Math.max(box.minZ, Math.min(eye.z, box.maxZ));
        double dx = eye.x - cx;
        double dy = eye.y - cy;
        double dz = eye.z - cz;
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    /** Whether {@code target} is predicted inside {@code range} of the local player after {@code ticks}. */
    public static boolean inReachAfter(ClientPlayerEntity player, Entity target, int ticks, double range) {
        if (player == null || target == null) {
            return false;
        }
        Box box = CombatPrediction.predictBox(target, ticks);
        return box != null && distanceToBox(predictEye(player, ticks), box) <= range;
    }

    /**
     * {@link #inReachAfter(ClientPlayerEntity, Entity, int, double)} with the player's vertical
     * motion starting from {@code startVy} (a jump that starts this tick) instead of the current
     * velocity, which on the ground still reads as the resting step-down.
     */
    public static boolean inReachAfter(ClientPlayerEntity player, Entity target, int ticks, double range,
                                       double startVy) {
        if (player == null || target == null) {
            return false;
        }
        Box box = CombatPrediction.predictBox(target, ticks);
        if (box == null) {
            return false;
        }
        Vec3d v = player.getVelocity();
        double drift = airDriftFactor(ticks);
        Vec3d eye = player.getEyePos().add(v.x * drift,
                verticalOffset(startVy, player.getFinalGravity(), ticks), v.z * drift);
        return distanceToBox(eye, box) <= range;
    }
}
