package interstellarTikiTiks.apexx.client.util.combat;

import interstellarTikiTiks.apexx.client.friend.FriendManager;
import interstellarTikiTiks.apexx.client.util.combat.WebTrapPlanner.Motion;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.PlayerListEntry;
import net.minecraft.enchantment.EnchantmentHelper;
import net.minecraft.enchantment.Enchantments;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.attribute.EntityAttributeModifier;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

import java.util.UUID;

/**
 * Latency and knockback inputs for webbing a player that was just hit, plus the cell and click
 * checks shared by modules that place that web. {@link WebTrapPlanner} does the path planning; this
 * class supplies what it needs around a hit: ping in ticks, the launch a hit is expected to cause
 * before it shows up in the target's position updates, and whether a cell or face is fit to click.
 *
 * <p>The knockback model follows vanilla ({@code LivingEntity.damage} applies
 * {@code takeKnockback(0.4)} away from the attacker; {@code PlayerEntity.attack} then applies
 * {@code takeKnockback(0.5 * (sprint + Knockback level + attack knockback))} along the attacker's
 * yaw) and is UNVERIFIED in-game on servers that alter knockback.
 */
public final class WebHitModel {

    private WebHitModel() {
    }

    // ── latency ───────────────────────────────────────────────────────────────────

    /** Our own round trip in ticks (tab-list latency). */
    public static double rttTicks(MinecraftClient mc) {
        return mc == null || mc.player == null ? 0.0 : pingTicks(mc, mc.player.getUuid());
    }

    /** Tab-list latency in ticks; unknown entries fall back to our own round trip, then 2 ticks. */
    public static double pingTicks(MinecraftClient mc, UUID uuid) {
        if (mc != null && mc.getNetworkHandler() != null && uuid != null) {
            PlayerListEntry entry = mc.getNetworkHandler().getPlayerListEntry(uuid);
            if (entry != null && entry.getLatency() > 0) {
                return MathHelper.clamp(entry.getLatency() / 50.0, 0.0, 20.0);
            }
            if (mc.player != null && !uuid.equals(mc.player.getUuid())) {
                double own = rttTicks(mc);
                return own > 0.0 ? own : 2.0;
            }
        }
        return 0.0;
    }

    // ── knockback ─────────────────────────────────────────────────────────────────

    /** Knockback resistance from the target's worn armor (client copies of other players carry no attribute modifiers). */
    public static double knockbackResistance(PlayerEntity t) {
        double r = 0.0;
        for (EquipmentSlot slot : new EquipmentSlot[]{EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET}) {
            ItemStack stack = t.getEquippedStack(slot);
            if (stack.isEmpty()) continue;
            double[] add = {0.0};
            try {
                stack.applyAttributeModifiers(slot, (attribute, modifier) -> {
                    if (attribute.value() == EntityAttributes.KNOCKBACK_RESISTANCE.value()
                            && modifier.operation() == EntityAttributeModifier.Operation.ADD_VALUE) {
                        add[0] += modifier.value();
                    }
                });
            } catch (Throwable ignored) {
            }
            r += add[0];
        }
        return MathHelper.clamp(r, 0.0, 1.0);
    }

    /** Attack knockback attribute plus the Knockback enchantment level of the held weapon. */
    public static double weaponKnockback(MinecraftClient mc) {
        if (mc == null || mc.player == null || mc.world == null) return 0.0;
        double k = 0.0;
        try {
            k += mc.player.getAttributeValue(EntityAttributes.ATTACK_KNOCKBACK);
        } catch (Throwable ignored) {
        }
        try {
            k += EnchantmentHelper.getLevel(
                    mc.world.getRegistryManager().getOrThrow(RegistryKeys.ENCHANTMENT).getOrThrow(Enchantments.KNOCKBACK),
                    mc.player.getMainHandStack());
        } catch (Throwable ignored) {
        }
        return Math.max(0.0, k);
    }

    /**
     * The target's state when the server-side path starts reacting to a hit: the pre-hit motion
     * carried forward to that moment, then vanilla knockback. Each knockback call halves the previous
     * velocity and, on the ground, sets {@code vy = min(0.4, vy/2 + s)}. The server's own copy of a
     * player's velocity is taken as zero horizontally.
     *
     * @param nowClock      the planner clock now
     * @param hitClock      the planner clock when the damaging hit was sent
     * @param bonus         knockback beyond the base 0.4: sprint, Knockback level, attack knockback
     */
    public static Motion knockbackMotion(MinecraftClient mc, WebTrapPlanner planner, PlayerEntity target,
                                         int nowClock, int hitClock, Vec3d attackerPos, float attackerYaw,
                                         double bonus, double rtt, double targetPing) {
        Motion pre = planner.observed(target);
        int sampleClock = nowClock - planner.ticksSinceSample(target);
        int preTicks = MathHelper.clamp((int) Math.round((hitClock - sampleClock) + rtt + targetPing + 1.0), 0, 12);
        Motion at = planner.advance(target, pre, preTicks);

        double resist = knockbackResistance(target);
        float yaw = attackerYaw * MathHelper.RADIANS_PER_DEGREE;
        double dx = at.pos().x - attackerPos.x;
        double dz = at.pos().z - attackerPos.z;
        double len = Math.sqrt(dx * dx + dz * dz);
        if (len < 1.0e-4) {
            dx = -MathHelper.sin(yaw);
            dz = MathHelper.cos(yaw);
            len = 1.0;
        }
        double vx = 0.0;
        double vy = at.velocity().y;
        double vz = 0.0;
        double s1 = 0.4 * (1.0 - resist);
        if (s1 > 0.0) {
            vx = dx / len * s1;
            vz = dz / len * s1;
            if (at.onGround()) vy = Math.min(0.4, s1);
        }
        double s2 = 0.5 * bonus * (1.0 - resist);
        if (s2 > 0.0) {
            vx = vx / 2.0 - MathHelper.sin(yaw) * s2;
            vz = vz / 2.0 + MathHelper.cos(yaw) * s2;
            if (at.onGround()) vy = Math.min(0.4, vy / 2.0 + s2);
        }
        return new Motion(at.pos(), new Vec3d(vx, vy, vz), at.onGround(), pre.intent(), false);
    }

    // ── cells and clicks ──────────────────────────────────────────────────────────

    /** A cell a web can be placed into, that is not where we are (or are heading) and holds no friend. */
    public static boolean placeable(MinecraftClient mc, BlockPos cell, double reach) {
        if (mc == null || mc.world == null || mc.player == null || cell == null) return false;
        BlockState state = mc.world.getBlockState(cell);
        if (state.isOf(Blocks.COBWEB) || !(state.isAir() || state.isReplaceable())) return false;
        return isSafeCell(mc, cell)
                && mc.player.getEyePos().squaredDistanceTo(Vec3d.ofCenter(cell)) <= Math.pow(reach + 0.9, 2);
    }

    /** Never web yourself (now or where you are heading) or a friend. */
    public static boolean isSafeCell(MinecraftClient mc, BlockPos cell) {
        Box cb = new Box(cell);
        Box self = mc.player.getBoundingBox().expand(0.15, 0.0, 0.15);
        Vec3d v = mc.player.getVelocity();
        if (cb.intersects(self) || cb.intersects(self.offset(v.x * 2.0, Math.max(v.y, 0.0) * 2.0, v.z * 2.0))) return false;
        for (PlayerEntity p : mc.world.getPlayers()) {
            if (p != mc.player && cb.intersects(p.getBoundingBox()) && FriendManager.getInstance().isFriend(p.getUuid())) {
                return false;
            }
        }
        return true;
    }

    /** In reach, not an interactive block, and no hitbox in front of the face (vanilla's crosshair would pick it). */
    public static boolean clickable(MinecraftClient mc, BlockHitResult live, double reach) {
        Vec3d eye = mc.player.getEyePos();
        if (eye.squaredDistanceTo(live.getPos()) > reach * reach) return false;
        BlockState s = mc.world.getBlockState(live.getBlockPos());
        if (!mc.player.isSneaking() && !s.isReplaceable() && WebTrapPlanner.isInteractive(mc.world, live.getBlockPos(), s)) {
            return false;
        }
        return !WebTrapPlanner.entityOccludes(mc, eye, live.getPos(), 0.0);
    }
}
