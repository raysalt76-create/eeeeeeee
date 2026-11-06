package interstellarTikiTiks.apexx.client.util.world;

import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.ItemEnchantmentsComponent;
import net.minecraft.enchantment.Enchantment;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.Difficulty;
import net.minecraft.world.World;
import net.minecraft.world.explosion.ExplosionImpl;

public class CrystalUtils {

    public static float getExposure(Vec3d source, Entity entity, World world) {
        if (entity == null || source == null || world == null) return 0.0F;
        // Use the exact 1.21.11 vanilla sampling routine instead of maintaining a copied formula
        // that can silently drift when Mojang changes bounding-box exposure behavior.
        return ExplosionImpl.calculateReceivedDamage(source, entity);
    }

    /**
     * Calculates authentic explosion damage to an entity matching Minecraft 1.21 explosion mechanics.
     * Incorporates exposure, difficulty scaling, armor, toughness, EPF (Protection/Blast Protection), and status effects.
     */
    public static float calculateDamage(World world, Vec3d explosionPos, LivingEntity target) {
        return calculateExplosionDamage(world, explosionPos, target, 6.0f); // Default End Crystal power = 6.0
    }

    /**
     * Calculates authentic respawn anchor explosion damage to an entity (power = 5.0).
     */
    public static float calculateAnchorDamage(World world, Vec3d explosionPos, LivingEntity target) {
        return calculateExplosionDamage(world, explosionPos, target, 5.0f); // Respawn Anchor power = 5.0
    }

    /**
     * General explosion damage computation for arbitrary blast power.
     */
    public static float calculateExplosionDamage(World world, Vec3d explosionPos, LivingEntity target, float power) {
        if (target == null || explosionPos == null || world == null) return 0f;

        double maxDist = power * 2.0;
        double distance = Math.sqrt(target.squaredDistanceTo(explosionPos));
        if (distance > maxDist) return 0f;

        double exposure = getExposure(explosionPos, target, world);
        if (exposure <= 0.0) return 0f;

        double impact = (1.0 - (distance / maxDist)) * exposure;
        float rawDamage = (float) ((impact * impact + impact) / 2.0 * 7.0 * maxDist + 1.0);

        // 1. Difficulty Scaling
        float damage = applyDifficultyScaling(rawDamage, world.getDifficulty());
        if (damage <= 0.0f) return 0.0f;

        // 2. Armor & Armor Toughness Reduction
        damage = applyArmorReduction(target, damage);

        // 3. Enchantment Protection Factor (EPF)
        int epf = getExplosionEpf(target);
        if (epf > 0) {
            float enchantReduction = Math.min(0.8f, epf / 25.0f);
            damage *= (1.0f - enchantReduction);
        }

        // 4. Status Effects (Resistance)
        if (target.hasStatusEffect(StatusEffects.RESISTANCE)) {
            int amp = target.getStatusEffect(StatusEffects.RESISTANCE).getAmplifier() + 1;
            float resReduction = Math.min(1.0f, amp * 0.2f);
            damage *= (1.0f - resReduction);
        }

        return Math.max(0.0f, damage);
    }

    public static float applyDifficultyScaling(float damage, Difficulty difficulty) {
        if (difficulty == null) return damage;
        return switch (difficulty) {
            case PEACEFUL -> 0.0f;
            case EASY -> Math.min(damage / 2.0f + 1.0f, damage);
            case NORMAL -> damage;
            case HARD -> damage * 1.5f;
        };
    }

    public static float applyArmorReduction(LivingEntity target, float damage) {
        float armor = target.getArmor();
        float toughness = 0.0f;
        if (target.getAttributeInstance(EntityAttributes.ARMOR_TOUGHNESS) != null) {
            toughness = (float) target.getAttributeValue(EntityAttributes.ARMOR_TOUGHNESS);
        }
        float armorReduction = 2.0f + toughness / 4.0f;
        float clampedArmor = MathHelper.clamp(armor - damage / armorReduction, armor * 0.2f, 20.0f);
        return damage * (1.0f - clampedArmor / 25.0f);
    }

    public static int getExplosionEpf(LivingEntity entity) {
        if (entity == null) return 0;
        int totalEpf = 0;
        for (net.minecraft.entity.EquipmentSlot slot : new net.minecraft.entity.EquipmentSlot[]{
                net.minecraft.entity.EquipmentSlot.FEET, net.minecraft.entity.EquipmentSlot.LEGS,
                net.minecraft.entity.EquipmentSlot.CHEST, net.minecraft.entity.EquipmentSlot.HEAD}) {
            ItemStack stack = entity.getEquippedStack(slot);
            if (stack == null || stack.isEmpty()) continue;
            ItemEnchantmentsComponent enchants = stack.get(DataComponentTypes.ENCHANTMENTS);
            if (enchants == null) continue;
            for (RegistryEntry<Enchantment> entry : enchants.getEnchantments()) {
                if (entry.getKey().isPresent()) {
                    String path = entry.getKey().get().getValue().getPath();
                    int level = enchants.getLevel(entry);
                    if ("blast_protection".equalsIgnoreCase(path)) {
                        totalEpf += level * 2;
                    } else if ("protection".equalsIgnoreCase(path)) {
                        totalEpf += level;
                    }
                }
            }
        }
        return Math.min(20, totalEpf);
    }

    /**
     * Get damage for placing a crystal on a specific obsidian block.
     * The crystal spawns at blockPos.up(), centered.
     */
    public static float calculateDamageOnBlock(World world, BlockPos blockPos, LivingEntity target) {
        Vec3d crystalPos = new Vec3d(blockPos.getX() + 0.5, blockPos.getY() + 1.0, blockPos.getZ() + 0.5);
        return calculateDamage(world, crystalPos, target);
    }
}
