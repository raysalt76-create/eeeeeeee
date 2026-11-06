package interstellarTikiTiks.apexx.client.module.implementation.combat;

import interstellarTikiTiks.apexx.client.util.combat.BlastModel;
import interstellarTikiTiks.apexx.client.util.combat.CrystalPlacement;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.Entity;
import net.minecraft.entity.decoration.EndCrystalEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;
import net.minecraft.world.World;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Predicate;

/**
 * AutoHitCrystal's Auto mode planner: picks the player to fight and the crystal action that hurts them
 * most within the user's limits. Reads only; the module performs every turn, swap, place and hit
 * through the shared seams.
 *
 * <p>Ranking: every reachable crystal already standing, every reachable obsidian or bedrock base with
 * room for a crystal and, with Auto Place, every air cell obsidian could go into, is scored by the
 * damage {@link BlastModel} estimates on the target's predicted box (a few ticks ahead; after one of our
 * hits, with vanilla's knockback added). "Efficiency" is that damage over the most a crystal could do to
 * them right now (point blank, fully exposed), so it is independent of their armour. Anything under Min
 * Efficiency, or whose estimated self damage is over Max Self Damage (or lethal with Anti Suicide), is
 * dropped. A breakable crystal is preferred over a new place, and an existing base over new obsidian.
 * {@link BlastModel} is an estimate (UNVERIFIED against the game's own numbers).</p>
 */
final class HitCrystalAura {

    static final float CRYSTAL_POWER = 6.0f;
    /** Ticks of target movement the damage is evaluated on. */
    private static final int PREDICT_TICKS = 3;
    private static final double BLAST_RADIUS = CRYSTAL_POWER * 2.0;

    enum Kind { BREAK, PLACE, OBSIDIAN }

    /** One chosen action: the base it concerns, the crystal for a break, and its estimated numbers. */
    record Plan(Kind kind, BlockPos base, EndCrystalEntity crystal, double targetDamage, double selfDamage,
                double efficiency) {
    }

    /** The limits a plan must respect. */
    record Limits(double minEfficiency, double maxSelfDamage, boolean antiSuicide, boolean predict,
                  boolean knocked, boolean obsidian) {
    }

    /** Why nothing was planned, for the status shown to the user. */
    enum Miss { NONE, NO_CANDIDATE, LOW_EFFICIENCY, UNSAFE }

    private Miss lastMiss = Miss.NONE;
    private boolean sawLow;
    private boolean sawUnsafe;

    private HitCrystalAura() {
    }

    static HitCrystalAura create() {
        return new HitCrystalAura();
    }

    Miss lastMiss() {
        return lastMiss;
    }

    // ------------------------------------------------------------------ targets

    /**
     * The player to fight: valid per {@code valid}, within {@code range} of us and within half of
     * {@code maxAngle} of the camera yaw, ordered by {@code mode} (Distance, Angle, Armor, Health).
     * {@code preferred} (the player last sword-hit) wins while it is still valid.
     */
    static PlayerEntity pickTarget(MinecraftClient mc, Predicate<PlayerEntity> valid, double range, double maxAngle,
                                   String mode, PlayerEntity preferred) {
        PlayerEntity self = mc.player;
        if (self == null || mc.world == null) return null;
        List<PlayerEntity> candidates = new ArrayList<>();
        for (PlayerEntity player : mc.world.getPlayers()) {
            if (player == self || !valid.test(player)) continue;
            if (self.distanceTo(player) > range) continue;
            if (yawOffset(self, player) > maxAngle / 2.0) continue;
            candidates.add(player);
        }
        if (candidates.isEmpty()) return null;
        if (preferred != null && candidates.contains(preferred)) return preferred;
        Comparator<PlayerEntity> order = switch (mode == null ? "" : mode) {
            case "Angle" -> Comparator.comparingDouble(p -> yawOffset(self, p));
            case "Armor" -> Comparator.<PlayerEntity>comparingInt(PlayerEntity::getArmor)
                    .thenComparingDouble(self::distanceTo);
            case "Health" -> Comparator.<PlayerEntity>comparingDouble(p -> p.getHealth() + p.getAbsorptionAmount())
                    .thenComparingDouble(self::distanceTo);
            default -> Comparator.comparingDouble(self::distanceTo);
        };
        candidates.sort(order);
        return candidates.get(0);
    }

    /** Absolute horizontal angle between the camera yaw and the direction to {@code target}, degrees. */
    static double yawOffset(PlayerEntity self, Entity target) {
        double dx = target.getX() - self.getX();
        double dz = target.getZ() - self.getZ();
        if (dx * dx + dz * dz < 1.0e-6) return 0.0;
        float toward = (float) (MathHelper.atan2(dz, dx) * (180.0 / Math.PI)) - 90.0f;
        return Math.abs(MathHelper.wrapDegrees(toward - self.getYaw()));
    }

    // ------------------------------------------------------------------ prediction

    /**
     * The target's box {@link #PREDICT_TICKS} ahead from its observed motion (position minus last
     * position, as players' velocity is not sent to other clients). With {@code knocked} (one of our hits
     * just landed), vanilla's 0.4-strength knockback away from us is added first. Gravity and drag as
     * vanilla's airborne step; a step that would go into blocks keeps the last free box.
     */
    static Box predictBox(World world, PlayerEntity target, PlayerEntity attacker, boolean predict, boolean knocked) {
        Box box = target.getBoundingBox();
        if (!predict) return box;
        double vx = target.getX() - target.lastX;
        double vy = target.getY() - target.lastY;
        double vz = target.getZ() - target.lastZ;
        boolean ground = target.isOnGround();
        if (knocked && attacker != null) {
            double dx = attacker.getX() - target.getX();
            double dz = attacker.getZ() - target.getZ();
            double length = Math.sqrt(dx * dx + dz * dz);
            if (length > 1.0e-4) {
                vx = vx / 2.0 - dx / length * 0.4;
                vz = vz / 2.0 - dz / length * 0.4;
            }
            vy = ground ? Math.min(0.4, vy / 2.0 + 0.4) : vy;
            ground = false;
        }
        for (int tick = 0; tick < PREDICT_TICKS; tick++) {
            Box next = box.offset(vx, vy, vz);
            if (!world.isSpaceEmpty(target, next)) {
                Box flat = box.offset(vx, 0.0, vz);
                if (world.isSpaceEmpty(target, flat)) {
                    next = flat;
                } else {
                    break;
                }
                vy = 0.0;
                ground = true;
            }
            box = next;
            if (!ground) vy = (vy - 0.08) * 0.98;
            double drag = ground ? 0.546 : 0.91;
            vx *= drag;
            vz *= drag;
        }
        return box;
    }

    // ------------------------------------------------------------------ planning

    /**
     * The best action against {@code target}, or null. {@code obsidianCell} tells whether obsidian could
     * really go into a cell from here (support face, live reach, room) and is only asked about the best
     * scoring cells.
     */
    Plan plan(MinecraftClient mc, PlayerEntity target, Limits limits, Predicate<EndCrystalEntity> crystalUsable,
              Predicate<BlockPos> obsidianCell, Predicate<BlockPos> baseFaceReachable) {
        lastMiss = Miss.NO_CANDIDATE;
        sawLow = false;
        sawUnsafe = false;
        PlayerEntity self = mc.player;
        World world = mc.world;
        if (self == null || world == null || target == null) return null;
        Box predicted = predictBox(world, target, self, limits.predict(), limits.knocked());
        double ceiling = BlastModel.damageAt(world, target, 0.0, 1.0, CRYSTAL_POWER);
        if (ceiling <= 0.0) return null;
        Vec3d eye = self.getEyePos();
        double entityReach = self.getEntityInteractionRange();
        double blockReach = self.getBlockInteractionRange();

        // 1. A crystal already standing that the live ray can be brought onto.
        Plan best = null;
        Box around = self.getBoundingBox().expand(entityReach + 1.0);
        for (EndCrystalEntity crystal : world.getEntitiesByClass(EndCrystalEntity.class, around, Entity::isAlive)) {
            if (crystal.isRemoved() || !crystalUsable.test(crystal) || crystal.getBoundingBox().squaredMagnitude(eye) > entityReach * entityReach) continue;
            if (!visible(world, self, eye, crystal.getBoundingBox().getCenter())) continue;
            Plan scored = score(world, self, target, predicted, ceiling, limits, Kind.BREAK,
                    crystal.getBlockPos().down(), crystal, crystal.getEntityPos());
            if (scored != null && (best == null || scored.targetDamage() > best.targetDamage())) best = scored;
        }
        if (best != null) return done(best);

        // 2. An obsidian or bedrock base with room for a crystal.
        int radius = (int) Math.ceil(blockReach);
        BlockPos center = BlockPos.ofFloored(eye);
        Vec3d feet = new Vec3d((predicted.minX + predicted.maxX) * 0.5, predicted.minY, (predicted.minZ + predicted.maxZ) * 0.5);
        List<Plan> bases = new ArrayList<>();
        List<BlockPos> air = new ArrayList<>();
        BlockPos.Mutable cursor = new BlockPos.Mutable();
        for (int x = -radius; x <= radius; x++) {
            for (int y = -radius; y <= radius; y++) {
                for (int z = -radius; z <= radius; z++) {
                    cursor.set(center.getX() + x, center.getY() + y, center.getZ() + z);
                    if (new Box(cursor).squaredMagnitude(eye) > blockReach * blockReach) continue;
                    Vec3d blast = new Vec3d(cursor.getX() + 0.5, cursor.getY() + 1.0, cursor.getZ() + 0.5);
                    if (upperBound(blast.distanceTo(feet)) < limits.minEfficiency()) continue;
                    if (!crystalReachable(self, eye, entityReach, cursor)) continue;
                    if (crystalSpace(cursor).intersects(predicted)) continue;
                    if (CrystalPlacement.isBase(world, cursor)) {
                        if (!CrystalPlacement.crystalFits(world, cursor)) continue;
                        Plan scored = score(world, self, target, predicted, ceiling, limits, Kind.PLACE,
                                cursor.toImmutable(), null, blast);
                        if (scored != null) bases.add(scored);
                    } else if (limits.obsidian() && world.getBlockState(cursor).isReplaceable()
                            && world.isAir(cursor.up()) && !new Box(cursor).intersects(predicted)) {
                        air.add(cursor.toImmutable());
                    }
                }
            }
        }
        bases.sort(Comparator.comparingDouble(Plan::targetDamage).reversed());
        for (Plan candidate : bases) {
            if (baseFaceReachable.test(candidate.base())) return done(candidate);
        }

        // 3. Auto Place: a new obsidian base.
        if (!air.isEmpty()) {
            List<Plan> cells = new ArrayList<>();
            for (BlockPos cell : air) {
                Vec3d blast = new Vec3d(cell.getX() + 0.5, cell.getY() + 1.0, cell.getZ() + 0.5);
                Plan scored = score(world, self, target, predicted, ceiling, limits, Kind.OBSIDIAN, cell, null, blast);
                if (scored != null) cells.add(scored);
            }
            cells.sort(Comparator.comparingDouble(Plan::targetDamage).reversed());
            int asked = 0;
            for (Plan candidate : cells) {
                if (++asked > 24) break;
                if (obsidianCell.test(candidate.base())) return done(candidate);
            }
        }
        lastMiss = sawUnsafe ? Miss.UNSAFE : sawLow ? Miss.LOW_EFFICIENCY : Miss.NO_CANDIDATE;
        return null;
    }

    /**
     * The committed {@code plan} scored again on the target's current forecast, or null when it no longer
     * stands: its crystal gone, unusable, out of reach or hidden; its base no longer a base, without room,
     * or out of reach; its obsidian cell no longer placeable; or its blast now outside the limits. The
     * module keeps acting on a plan while this returns one instead of planning afresh every tick.
     */
    Plan rescore(MinecraftClient mc, PlayerEntity target, Limits limits, Plan plan,
                 Predicate<EndCrystalEntity> crystalUsable, Predicate<BlockPos> obsidianCell) {
        PlayerEntity self = mc.player;
        World world = mc.world;
        if (self == null || world == null || target == null || plan == null) return null;
        Vec3d eye = self.getEyePos();
        double entityReach = self.getEntityInteractionRange();
        double blockReach = self.getBlockInteractionRange();
        Box predicted = predictBox(world, target, self, limits.predict(), limits.knocked());
        BlockPos base = plan.base();
        Vec3d blast;
        switch (plan.kind()) {
            case BREAK -> {
                EndCrystalEntity crystal = plan.crystal();
                if (crystal == null || !crystal.isAlive() || crystal.isRemoved() || !crystalUsable.test(crystal)
                        || crystal.getBoundingBox().squaredMagnitude(eye) > entityReach * entityReach
                        || !visible(world, self, eye, crystal.getBoundingBox().getCenter())) return null;
                blast = crystal.getEntityPos();
            }
            case PLACE -> {
                if (new Box(base).squaredMagnitude(eye) > blockReach * blockReach
                        || !crystalReachable(self, eye, entityReach, base) || crystalSpace(base).intersects(predicted)
                        || !CrystalPlacement.isBase(world, base) || !CrystalPlacement.crystalFits(world, base)) return null;
                blast = new Vec3d(base.getX() + 0.5, base.getY() + 1.0, base.getZ() + 0.5);
            }
            default -> {
                if (!limits.obsidian() || !world.getBlockState(base).isReplaceable() || !world.isAir(base.up())
                        || new Box(base).intersects(predicted) || crystalSpace(base).intersects(predicted)
                        || !obsidianCell.test(base)) return null;
                blast = new Vec3d(base.getX() + 0.5, base.getY() + 1.0, base.getZ() + 0.5);
            }
        }
        double ceiling = BlastModel.damageAt(world, target, 0.0, 1.0, CRYSTAL_POWER);
        if (ceiling <= 0.0) return null;
        return score(world, self, target, predicted, ceiling, limits, plan.kind(), base, plan.crystal(), blast);
    }

    /** Whether two plans are the same action: the same kind on the same base (and, for a break, the same crystal). */
    static boolean sameAction(Plan a, Plan b) {
        if (a == null || b == null) return a == b;
        if (a.kind() != b.kind() || !a.base().equals(b.base())) return false;
        return a.kind() != Kind.BREAK || a.crystal() == b.crystal();
    }

    private Plan done(Plan plan) {
        lastMiss = Miss.NONE;
        return plan;
    }

    /** Scores one blast; null (and the reason recorded) when it breaks a limit. */
    private Plan score(World world, PlayerEntity self, PlayerEntity target, Box predicted, double ceiling,
                       Limits limits, Kind kind, BlockPos base, EndCrystalEntity crystal, Vec3d blast) {
        double targetDamage;
        double selfDamage;
        try {
            targetDamage = BlastModel.damage(world, target, blast, CRYSTAL_POWER, predicted, null, List.of());
            selfDamage = self.isCreative() ? 0.0 : BlastModel.damage(world, self, blast, CRYSTAL_POWER);
        } catch (RuntimeException estimateFailed) {
            return null;
        }
        double efficiency = targetDamage / ceiling;
        if (efficiency < limits.minEfficiency() || targetDamage <= 0.0) {
            if (targetDamage > 0.0) sawLow = true;
            return null;
        }
        if (selfDamage > limits.maxSelfDamage() || (limits.antiSuicide() && selfDamage >= self.getHealth())) {
            sawUnsafe = true;
            return null;
        }
        return new Plan(kind, base, crystal, targetDamage, selfDamage, efficiency);
    }

    /**
     * The highest efficiency any blast this far from the target's feet can reach: full exposure at that
     * distance, as a share of point blank. Used to skip cells before the costly exposure rays.
     */
    static double upperBound(double distance) {
        double share = distance / BLAST_RADIUS;
        if (share >= 1.0) return 0.0;
        double impact = 1.0 - share;
        double scale = 7.0 * BLAST_RADIUS;
        return ((impact * impact + impact) / 2.0 * scale + 1.0) / (scale + 1.0);
    }

    /** The space a crystal standing on {@code base} takes. */
    static Box crystalSpace(BlockPos base) {
        return new Box(base.getX(), base.getY() + 1.0, base.getZ(), base.getX() + 1.0, base.getY() + 3.0, base.getZ() + 1.0);
    }

    /** Whether a crystal on {@code base} would be within entity reach (its 2x2x2 box centred on the base). */
    static boolean crystalReachable(PlayerEntity self, Vec3d eye, double reach, BlockPos base) {
        Box crystal = new Box(base.getX() - 0.5, base.getY() + 1.0, base.getZ() - 0.5,
                base.getX() + 1.5, base.getY() + 3.0, base.getZ() + 1.5);
        return crystal.squaredMagnitude(eye) <= reach * reach;
    }

    private static boolean visible(World world, PlayerEntity self, Vec3d eye, Vec3d point) {
        HitResult hit = world.raycast(new RaycastContext(eye, point, RaycastContext.ShapeType.COLLIDER,
                RaycastContext.FluidHandling.NONE, self));
        return hit == null || hit.getType() == HitResult.Type.MISS;
    }
}
