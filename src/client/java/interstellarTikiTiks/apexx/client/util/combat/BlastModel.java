package interstellarTikiTiks.apexx.client.util.combat;

import net.minecraft.enchantment.Enchantment;
import net.minecraft.enchantment.Enchantments;
import net.minecraft.component.type.ItemEnchantmentsComponent;
import net.minecraft.block.BlockState;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.fluid.FluidState;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;
import net.minecraft.world.World;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Client-side estimate of the damage an explosion at a point deals to an entity, for choosing where to
 * put an anchor. It follows {@code ExplosionImpl.calculateReceivedDamage} (distance falloff times the
 * fraction of the box a clear collider ray reaches, {@code (i*i + i) / 2 * 7 * 2P + 1}), then difficulty,
 * armour and toughness, protection and blast protection, Resistance and absorption.
 *
 * <p>The numbers are an estimate: the constants are from memory of the vanilla code and have not been
 * checked against the 1.21.11 sources (UNVERIFIED), the result is not rounded the way the game rounds
 * it, and totems, shields on the caster's side and the random block loss of the blast are not modelled.
 * Use it to rank candidate cells, not as a promise of a kill. The exposure rays are the same ones
 * {@link AutoCartCover} uses for carts.</p>
 */
public final class BlastModel {

    /** Explosion power of a charged respawn anchor. */
    public static final float ANCHOR_POWER = 5.0f;

    private BlastModel() {
    }

    /** Fraction of the entity's box with a clear ray to the blast, 0 to 1. */
    public static double exposure(World world, Entity entity, Vec3d blast) {
        return AutoCartCover.exposure(world, entity, blast, entity.getBoundingBox());
    }

    /**
     * Health the blast would take from {@code entity}, after reductions and absorption. Zero beyond the
     * blast radius or with no exposed point.
     */
    public static double damage(World world, LivingEntity entity, Vec3d blast, float power) {
        double radius = power * 2.0;
        double distance = entity.getEntityPos().distanceTo(blast) / radius;
        if (distance > 1.0) return 0.0;
        double exposure = exposure(world, entity, blast);
        if (exposure <= 0.0) return 0.0;
        double amount = rawDamage(distance, exposure, power);
        if (entity instanceof PlayerEntity) {
            switch (world.getDifficulty()) {
                case PEACEFUL -> amount = 0.0;
                case EASY -> amount = Math.min(amount / 2.0 + 1.0, amount);
                case HARD -> amount = amount * 3.0 / 2.0;
                default -> { }
            }
        }
        amount = afterArmor(entity, amount);
        amount = afterProtection(entity, amount);
        amount = afterResistance(entity, amount);
        return Math.max(0.0, amount - entity.getAbsorptionAmount());
    }

    /**
     * Damage from a blast whose source block is still standing in the world: a respawn anchor removes
     * itself before it explodes, so its own cell must not block the exposure rays (with the anchor
     * in the world every ray ends inside it and the plain {@link #damage} reads 0). {@code box} is the
     * entity's box to test (its current box, or a predicted one); {@code cover} are extra solid boxes
     * (a shield block sent but not yet in the world). Reductions as in {@link #damage}.
     */
    public static double damage(World world, LivingEntity entity, Vec3d blast, float power, Box box,
                                BlockPos sourceCell, List<Box> cover) {
        double radius = power * 2.0;
        Vec3d feet = new Vec3d((box.minX + box.maxX) / 2.0, box.minY, (box.minZ + box.maxZ) / 2.0);
        double distance = feet.distanceTo(blast) / radius;
        if (distance > 1.0) return 0.0;
        double exposure = exposure(world, entity, blast, box, sourceCell, cover);
        if (exposure <= 0.0) return 0.0;
        return reduce(world, entity, rawDamage(distance, exposure, power));
    }

    /** Health {@code entity} loses from a blast of the given distance share and exposure, after every reduction and absorption. */
    public static double damageAt(World world, LivingEntity entity, double distanceShare, double exposure, float power) {
        if (distanceShare > 1.0 || exposure <= 0.0) return 0.0;
        return reduce(world, entity, rawDamage(distanceShare, exposure, power));
    }

    private static double reduce(World world, LivingEntity entity, double amount) {
        if (entity instanceof PlayerEntity) {
            switch (world.getDifficulty()) {
                case PEACEFUL -> amount = 0.0;
                case EASY -> amount = Math.min(amount / 2.0 + 1.0, amount);
                case HARD -> amount = amount * 3.0 / 2.0;
                default -> { }
            }
        }
        amount = afterArmor(entity, amount);
        amount = afterProtection(entity, amount);
        amount = afterResistance(entity, amount);
        return Math.max(0.0, amount - entity.getAbsorptionAmount());
    }

    /**
     * Fraction of {@code box}'s explosion samples (vanilla's lattice) with a clear collider ray to
     * {@code blast}, treating {@code sourceCell} as already gone and every box of {@code cover} as solid.
     */
    public static double exposure(World world, Entity entity, Vec3d blast, Box box, BlockPos sourceCell, List<Box> cover) {
        List<Vec3d> samples = AutoCartCover.exposureSamples(box);
        if (samples.isEmpty()) return 0.0;
        int clear = 0;
        for (Vec3d sample : samples) {
            if (clearRay(world, entity, sample, blast, sourceCell, cover)) ++clear;
        }
        return (double) clear / samples.size();
    }

    /** The samples of {@code box} with a clear ray to the blast (source cell ignored), for re-testing against candidate covers. */
    public static List<Vec3d> visibleSamples(World world, Entity entity, Vec3d blast, Box box, BlockPos sourceCell) {
        List<Vec3d> visible = new ArrayList<>();
        for (Vec3d sample : AutoCartCover.exposureSamples(box)) {
            if (clearRay(world, entity, sample, blast, sourceCell, List.of())) visible.add(sample);
        }
        return visible;
    }

    /** Number of samples vanilla takes over {@code box}. */
    public static int sampleCount(Box box) {
        return AutoCartCover.exposureSamples(box).size();
    }

    /** A clear collider ray from {@code from} to the blast, with the source cell ignored and {@code cover} solid. */
    public static boolean clearRay(World world, Entity entity, Vec3d from, Vec3d blast, BlockPos sourceCell, List<Box> cover) {
        if (cover != null) {
            for (Box solid : cover) {
                if (solid.contains(from) || solid.raycast(from, blast).isPresent()) return false;
            }
        }
        Vec3d end = blast;
        if (sourceCell != null) {
            Box cell = new Box(sourceCell);
            if (cell.contains(from)) return true;
            Optional<Vec3d> entry = cell.raycast(from, blast);
            if (entry.isPresent()) {
                Vec3d direction = blast.subtract(from);
                double length = direction.length();
                if (length < 1.0e-9) return true;
                end = entry.get().subtract(direction.multiply(1.0e-4 / length));
            }
        }
        if (from.squaredDistanceTo(end) < 1.0e-12) return true;
        return world.raycast(new RaycastContext(from, end, RaycastContext.ShapeType.COLLIDER,
                RaycastContext.FluidHandling.NONE, entity)).getType() == HitResult.Type.MISS;
    }

    /**
     * Damage before difficulty and reductions: with {@code i = (1 - distance) * exposure} (distance as a
     * share of the blast radius, 2 x power), {@code (i * i + i) / 2 * 7 * 2P + 1}.
     */
    static double rawDamage(double distanceShare, double exposure, float power) {
        double impact = (1.0 - distanceShare) * exposure;
        return (impact * impact + impact) / 2.0 * 7.0 * (power * 2.0) + 1.0;
    }

    /** {@code DamageUtil.getDamageLeft}: armour and toughness. */
    private static double afterArmor(LivingEntity entity, double amount) {
        double armor = entity.getAttributeValue(EntityAttributes.ARMOR);
        double toughness = entity.getAttributeValue(EntityAttributes.ARMOR_TOUGHNESS);
        double divisor = 2.0 + toughness / 4.0;
        double effective = MathHelper.clamp(armor - amount / divisor, armor * 0.2, 20.0);
        return amount * (1.0 - effective / 25.0);
    }

    /** Protection (1 per level) and blast protection (2 per level) on the four armour pieces, capped at 20. */
    private static double afterProtection(LivingEntity entity, double amount) {
        int points = 0;
        for (EquipmentSlot slot : new EquipmentSlot[]{EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET}) {
            ItemStack stack = entity.getEquippedStack(slot);
            if (stack.isEmpty()) continue;
            ItemEnchantmentsComponent enchantments = stack.getEnchantments();
            for (RegistryEntry<Enchantment> entry : enchantments.getEnchantments()) {
                int level = enchantments.getLevel(entry);
                if (entry.matchesKey(Enchantments.PROTECTION)) points += level;
                else if (entry.matchesKey(Enchantments.BLAST_PROTECTION)) points += level * 2;
            }
        }
        return amount * (1.0 - Math.min(20, points) / 25.0);
    }

    private static double afterResistance(LivingEntity entity, double amount) {
        StatusEffectInstance resistance = entity.getStatusEffect(StatusEffects.RESISTANCE);
        if (resistance == null) return amount;
        int reduction = (resistance.getAmplifier() + 1) * 5;
        return Math.max(0.0, amount * (25 - reduction) / 25.0);
    }

    /**
     * Whether the entity's raised shield takes this blast: it must be blocking and facing the blast. The
     * test is vanilla's (the horizontal direction from the blast to the entity, against its look, in the
     * 180-degree arc in front of it). The arc is the default one; whether 1.21.11 explosions are blocked
     * through the blocks-attacks component with this exact arc is UNVERIFIED.
     */
    public static boolean shieldBlocks(LivingEntity entity, Vec3d blast) {
        if (!entity.isBlocking()) return false;
        Vec3d away = entity.getEntityPos().subtract(blast);
        Vec3d horizontal = new Vec3d(away.x, 0.0, away.z);
        if (horizontal.lengthSquared() < 1.0e-9) return true;
        return horizontal.normalize().dotProduct(entity.getRotationVec(1.0f)) < 0.0;
    }

    /**
     * Blocks an explosion is predicted to remove. The real blast rolls every ray at 0.7 to 1.3 of the
     * power, and a ray reaches no further with less strength, so the roll can only grow or shrink the
     * crater between two bounds: {@code sure} (every ray at 0.7) is removed in every roll, and nothing
     * outside {@code possible} (every ray at 1.3) is ever removed. The ray walk is vanilla's: a 16 x 16 x 16 shell of directions, 0.3-block steps,
     * strength lost to 0.225 per step and to {@code (resistance + 0.3) * 0.3} per block (UNVERIFIED
     * against the 1.21.11 sources). The anchor's own cell is treated as already gone, as the anchor
     * removes itself before it explodes.
     */
    public record Crater(Set<BlockPos> sure, Set<BlockPos> possible) {
    }

    /** How the ray walk reads the world: the blast resistance at a position, NaN for air, and the world's height limits. */
    interface Terrain {
        /** Blast resistance of the block or fluid at {@code pos}; NaN when it is air with no fluid. */
        float resistance(BlockPos pos);

        boolean outOfBounds(BlockPos pos);
    }

    /** Strength a ray starts with, as a share of the power (vanilla rolls 0.7 to 1.3 per ray). */
    interface RayStrength {
        float next();
    }

    public static Crater predictCrater(World world, Vec3d center, float power) {
        Terrain terrain = new Terrain() {
            @Override
            public float resistance(BlockPos pos) {
                BlockState state = world.getBlockState(pos);
                FluidState fluid = world.getFluidState(pos);
                if (state.isAir() && fluid.isEmpty()) return Float.NaN;
                return Math.max(state.getBlock().getBlastResistance(), fluid.getBlastResistance());
            }

            @Override
            public boolean outOfBounds(BlockPos pos) {
                return world.isOutOfHeightLimit(pos);
            }
        };
        BlockPos origin = BlockPos.ofFloored(center);
        return new Crater(carve(terrain, center, origin, power, () -> 0.7f),
                carve(terrain, center, origin, power, () -> 1.3f));
    }

    /**
     * The ray walk of one explosion over {@code terrain}: every direction of the 16 x 16 x 16 shell, in
     * 0.3-block steps, losing 0.225 per step and {@code (resistance + 0.3) * 0.3} per block entered. A block
     * is removed when a ray still has strength left after entering it.
     */
    static Set<BlockPos> carve(Terrain terrain, Vec3d center, BlockPos origin, float power, RayStrength strength) {
        Set<BlockPos> out = new HashSet<>();
        for (int j = 0; j < 16; ++j) {
            for (int k = 0; k < 16; ++k) {
                for (int l = 0; l < 16; ++l) {
                    if (j != 0 && j != 15 && k != 0 && k != 15 && l != 0 && l != 15) continue;
                    double dx = j / 15.0 * 2.0 - 1.0;
                    double dy = k / 15.0 * 2.0 - 1.0;
                    double dz = l / 15.0 * 2.0 - 1.0;
                    double length = Math.sqrt(dx * dx + dy * dy + dz * dz);
                    dx = dx / length * 0.3;
                    dy = dy / length * 0.3;
                    dz = dz / length * 0.3;
                    double x = center.x;
                    double y = center.y;
                    double z = center.z;
                    for (float h = power * strength.next(); h > 0.0f; h -= 0.22500001f) {
                        BlockPos pos = BlockPos.ofFloored(x, y, z);
                        if (terrain.outOfBounds(pos)) break;
                        if (!pos.equals(origin)) {
                            float resistance = terrain.resistance(pos);
                            if (!Float.isNaN(resistance)) {
                                h -= (resistance + 0.3f) * 0.3f;
                                if (h > 0.0f) out.add(pos.toImmutable());
                            }
                        }
                        x += dx;
                        y += dy;
                        z += dz;
                    }
                }
            }
        }
        return out;
    }
}
