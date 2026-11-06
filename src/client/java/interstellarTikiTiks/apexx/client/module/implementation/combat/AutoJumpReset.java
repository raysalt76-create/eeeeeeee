package interstellarTikiTiks.apexx.client.module.implementation.combat;

import interstellarTikiTiks.apexx.client.friend.FriendManager;
import interstellarTikiTiks.apexx.client.module.BooleanSetting;
import interstellarTikiTiks.apexx.client.module.implementation.player.BotFilterProvider;
import interstellarTikiTiks.apexx.client.util.ModuleAccess;
import interstellarTikiTiks.apexx.client.module.Category;
import interstellarTikiTiks.apexx.client.module.Module;
import interstellarTikiTiks.apexx.client.module.hooks.JumpInputProvider;
import interstellarTikiTiks.apexx.client.module.hooks.VelocityPacketAware;
import interstellarTikiTiks.apexx.client.module.SliderSetting;
import interstellarTikiTiks.apexx.client.util.RotationManager;
import interstellarTikiTiks.apexx.client.util.combat.MeleeMechanics;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.hit.EntityHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

import java.util.concurrent.ThreadLocalRandom;

/**
 * AutoJumpReset - jumps on the first movement tick after taking knockback from a hit.
 *
 * <p>Why that tick and no other: knockback on the ground sets the vertical velocity to about 0.4, so
 * the very next move lifts the player off the ground and a later jump press does nothing. Vanilla's
 * jump keeps {@code max(0.42, vy)} and, while sprinting, adds 0.2 along the facing direction; facing
 * the attacker, that push cancels part of the horizontal knockback. The jump is forced through
 * {@link JumpInputProvider} for exactly that movement tick. It has no delay slider on purpose: any
 * delay of a tick or more turns the reset into a jump that never happens; Miss Chance is the
 * humanisation for this action.
 */
public class AutoJumpReset extends Module implements VelocityPacketAware, JumpInputProvider {

    private final SliderSetting jumpResetRange    = new SliderSetting("Max Jump Range", 4.0, 0.5, 6.0, 1)
            .setDescription("Only jump-reset when the nearest enemy is within this many blocks, so knockback from far away (arrows, explosions) is ignored.");
    private final SliderSetting minJumpResetRange = new SliderSetting("Min Jump Range", 0.0, 0.0, 4.0, 1)
            .setDescription("Skip the jump when the enemy is closer than this, in blocks. 0 jump-resets at any distance.");
    private final SliderSetting missChance        = new SliderSetting("Miss Chance %", 13, 0, 100, 0)
            .setDescription("Chance to deliberately skip a jump reset, in percent, so you do not reset every single hit like a machine. 10-15% looks human.");
    private final BooleanSetting onlySprint       = new BooleanSetting("Only Sprint", true)
            .setDescription("Only jump-resets while sprinting; the sprint-jump push toward your facing is what cuts the knockback.");
    private final BooleanSetting requireTarget    = new BooleanSetting("Require Target", false)
            .setDescription("Only jump-reset while your crosshair is on another player within reach, so knockback taken while you are not facing anyone is ignored. Friends and AntiBot-flagged players do not count. Off: any enemy within Max Jump Range is enough.");
    private final BooleanSetting disableInLiquids = new BooleanSetting("Disable In Liquids", false)
            .setDescription("Never jump-reset while you are in water or lava. Off keeps the current behaviour.");

    private boolean jumpArmed = false;
    private int armedAge = Integer.MIN_VALUE;

    public AutoJumpReset() {
        super("AutoJumpReset", "Jumps on the exact movement tick you take melee knockback, which cuts the horizontal knockback and keeps you in the fight. Skips hits from behind where a jump would push you further.", Category.COMBAT);
        addSetting(jumpResetRange);
        addSetting(minJumpResetRange);
        addSetting(missChance);
        addSetting(onlySprint);
        addSetting(requireTarget);
        addSetting(disableInLiquids);
    }

    @Override
    public void onVelocityPacket() {
        if (!this.isEnabled() || mc.player == null || mc.world == null) {
            return;
        }

        // Knockback from a hit only (MeleeMechanics checks the damage flash that precedes it).
        if (MeleeMechanics.ticksSinceSelfKnockback(mc.player) != 0) {
            return;
        }

        // A jump needs ground under the player on the next movement tick.
        if (!mc.player.isOnGround()) {
            return;
        }

        if (onlySprint.isEnabled() && !mc.player.isSprinting()) {
            return;
        }

        if (isGated(mc)) {
            return;
        }

        if (!isEnemyWithinRange()) {
            return;
        }

        if (shouldMiss()) {
            return;
        }

        jumpArmed = true;
        armedAge = mc.player.age;
    }

    @Override
    public boolean shouldJump(MinecraftClient client) {
        if (!jumpArmed) {
            return false;
        }
        // Consumed by the first movement tick after the knockback, whatever the outcome.
        jumpArmed = false;
        if (!isEnabled() || client.player == null || client.currentScreen != null) {
            return false;
        }
        if (client.player.age - armedAge > 1 || !client.player.isOnGround()) {
            return false;
        }
        // Re-checked at the jump itself: the gates may have changed since the knockback armed it.
        if (isGated(client)) {
            return false;
        }
        // While sprinting the jump pushes 0.2 along the facing. If the knockback already points that
        // way (hit from behind) the push adds to it, so skip.
        if (client.player.isSprinting() && pushesAlongFacing(client.player.getVelocity(), client.player.getYaw())) {
            return false;
        }
        return true;
    }

    static boolean pushesAlongFacing(Vec3d velocity, float yaw) {
        double fx = -MathHelper.sin(yaw * 0.017453292f);
        double fz = MathHelper.cos(yaw * 0.017453292f);
        return velocity.x * fx + velocity.z * fz > 0.0;
    }

    private boolean shouldMiss() {
        double missPercent = missChance.getValue();
        if (missPercent <= 0.0) {
            return false;
        }

        return ThreadLocalRandom.current().nextDouble(0.0, 100.0) < missPercent;
    }

    private boolean isEnemyWithinRange() {
        if (mc.player == null || mc.world == null) {
            return false;
        }

        double maxRange = jumpResetRange.getValue() + 0.3;
        double minRange = minJumpResetRange.getValue();
        for (PlayerEntity entity : mc.world.getPlayers()) {
            if (entity == mc.player || !entity.isAlive()) {
                continue;
            }
            // Only an enemy's hit is reset: friends and bots never count as the nearby attacker.
            if (FriendManager.getInstance().isFriend(entity.getUuid()) || isBot(entity)) {
                continue;
            }
            double dist = mc.player.distanceTo(entity);
            if (dist >= minRange && dist <= maxRange) {
                return true;
            }
        }
        return false;
    }

    /** Require Target / Disable In Liquids: checked when the knockback arms the jump and again at the jump. */
    private boolean isGated(MinecraftClient client) {
        if (client.player == null) {
            return true;
        }
        if (disableInLiquids.isEnabled() && MeleeMechanics.isInLiquid(client.player)) {
            return true;
        }
        return requireTarget.isEnabled() && !isCrosshairOnPlayer(client);
    }

    /** The live crosshair ray (the silent rotation's while one is active) is on an enemy player within reach. */
    private boolean isCrosshairOnPlayer(MinecraftClient client) {
        double reach = client.player.getEntityInteractionRange();
        HitResult crosshair = RotationManager.isSilentRotationActive()
                ? RotationManager.getCrosshairTarget(RotationManager.getFinalYaw(), RotationManager.getFinalPitch(), reach)
                : client.crosshairTarget;
        if (!(crosshair instanceof EntityHitResult ehr) || !(ehr.getEntity() instanceof PlayerEntity target)) {
            return false;
        }
        if (target == client.player || !target.isAlive()
                || FriendManager.getInstance().isFriend(target.getUuid()) || isBot(target)) {
            return false;
        }
        return client.player.getEyePos().squaredDistanceTo(ehr.getPos()) <= reach * reach;
    }

    private static boolean isBot(PlayerEntity entity) {
        for (BotFilterProvider botFilter : ModuleAccess.getEnabledHooks(BotFilterProvider.class)) {
            if (botFilter.isBot(entity)) return true;
        }
        return false;
    }

    @Override
    public void onDisable() {
        jumpArmed = false;
        armedAge = Integer.MIN_VALUE;
    }

    @Override
    protected void onRuntimeStateReleased() {
        jumpArmed = false;
        armedAge = Integer.MIN_VALUE;
    }
}
