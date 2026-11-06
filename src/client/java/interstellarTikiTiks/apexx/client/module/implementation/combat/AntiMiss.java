package interstellarTikiTiks.apexx.client.module.implementation.combat;

import interstellarTikiTiks.apexx.client.friend.FriendManager;
import interstellarTikiTiks.apexx.client.module.BooleanSetting;
import interstellarTikiTiks.apexx.client.module.Category;
import interstellarTikiTiks.apexx.client.module.SubCategory;
import interstellarTikiTiks.apexx.client.module.ModeSetting;
import interstellarTikiTiks.apexx.client.module.Module;
import interstellarTikiTiks.apexx.client.module.SliderSetting;
import interstellarTikiTiks.apexx.client.module.hooks.AttackHandleAware;
import interstellarTikiTiks.apexx.client.util.combat.CombatItemUtil;
import interstellarTikiTiks.apexx.client.util.combat.MeleeMechanics;
import interstellarTikiTiks.apexx.client.util.ModuleExecutionContext;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.decoration.EndCrystalEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.EntityHitResult;
import net.minecraft.util.hit.HitResult;

import java.util.concurrent.ThreadLocalRandom;

/**
 * AntiMiss — Suppresses attack swings when your crosshair is not aimed at a valid target.
 *
 * <p>Completely legit client-side input filter that prevents wasting weapon cooldowns and
 * resetting sprint momentum on air swings. All attacks when hovering over an entity execute
 * 100% through vanilla mechanics.
 */
public class AntiMiss extends Module implements AttackHandleAware {

    private final ModeSetting mode = new ModeSetting("Miss Mode", "All Misses", "All Misses", "Air Only")
            .setDescription("All Misses: cancels any swing that would not hit an entity (air or blocks). Air Only: cancels only swings into empty air, so clicking blocks still works normally.");
    private final ModeSetting targets = new ModeSetting("Targets", "All", "All", "Players", "Mobs & Players")
            .setDescription("Which entities count as a valid hit. A swing at anything outside this filter is treated as a miss.");
    private final BooleanSetting weaponOnly = new BooleanSetting("Weapon Only", true)
            .setDescription("Only cancels misses while you hold a weapon (sword, axe, mace, spear, trident); with any other item your clicks behave normally.");
    private final BooleanSetting allowMining = new BooleanSetting("Allow Mining", true)
            .setDescription("Lets left click break blocks when your crosshair is on a block, even while holding a weapon.");
    private final BooleanSetting ignoreFriends = new BooleanSetting("Ignore Friends", true)
            .setDescription("Counts swings at friends as misses and cancels them, so you never accidentally hit a teammate.");
    private final SliderSetting hitChance = new SliderSetting("Hit Chance %", 100, 50, 100, 0)
            .setDescription("Chance that a miss is cancelled, in percent. 100 never wastes a swing; lower values let some misses through so your click pattern looks more human.");
    private final BooleanSetting disableInLiquids = new BooleanSetting("Disable In Liquids", false)
            .setDescription("Let every swing through while you are in water or lava; no misses are cancelled there.");
    private final BooleanSetting disableInAir = new BooleanSetting("Disable In Air", false)
            .setDescription("Let every swing through while you are off the ground, e.g. during crit jumps.");

    public AntiMiss() {
        super("AntiMiss", "Cancels swings that would hit nothing, so a mistimed click never resets your attack cooldown and your next real hit stays fully charged.", Category.COMBAT);
        setSubCategory(SubCategory.GENERAL);
        addSetting(mode);
        addSetting(targets);
        addSetting(weaponOnly);
        addSetting(allowMining);
        addSetting(ignoreFriends);
        addSetting(hitChance);
        addSetting(disableInLiquids);
        addSetting(disableInAir);
    }

    @Override
    public boolean handleAttack() {
        if (mc.player == null || mc.world == null) {
            return false;
        }

        // Gates are read at the click itself: while they hold, the swing is simply left alone.
        if ((disableInLiquids.isEnabled() && MeleeMechanics.isInLiquid(mc.player))
                || (disableInAir.isEnabled() && MeleeMechanics.isInAir(mc.player))) {
            return false;
        }

        // Module-dispatched lunge attacks and piercing weapon (spear) air stabs must not be suppressed
        if (ModuleExecutionContext.currentOwner() != null && "AutoLunge".equals(ModuleExecutionContext.currentOwner().getClass().getSimpleName())) {
            return false;
        }
        ItemStack held = mc.player.getMainHandStack();
        if (held != null && !held.isEmpty() && held.contains(DataComponentTypes.PIERCING_WEAPON)) {
            return false;
        }

        // If weapon only is enabled, ensure the player is actually wielding a weapon
        if (weaponOnly.isEnabled()) {
            if (!CombatItemUtil.isWeapon(held)) {
                return false;
            }
        }

        HitResult hit = mc.crosshairTarget;
        if (hit == null) {
            return suppressMiss();
        }

        if (hit.getType() == HitResult.Type.ENTITY) {
            if (hit instanceof EntityHitResult entityHit) {
                Entity target = entityHit.getEntity();
                if (isValidTarget(target)) {
                    // Aiming at a valid entity — let vanilla attack proceed naturally
                    return false;
                }
            }
            // Aimed at an entity that failed the target/friend filter
            return suppressMiss();
        }

        if (hit.getType() == HitResult.Type.BLOCK) {
            if (allowMining.isEnabled() || "Air Only".equals(mode.getCurrentMode())) {
                // Crosshair on block and mining allowed — let vanilla block-breaking start
                return false;
            }
            return suppressMiss();
        }

        if (hit.getType() == HitResult.Type.MISS) {
            return suppressMiss();
        }

        return false;
    }

    private boolean isValidTarget(Entity entity) {
        if (entity == null || !entity.isAlive() || entity == mc.player) {
            return false;
        }
        if (!entity.isAttackable()) {
            return false;
        }

        // Always allow hitting end crystals if holding weapon in CPvP
        if (entity instanceof EndCrystalEntity) {
            return true;
        }

        if (entity instanceof PlayerEntity player) {
            if (!interstellarTikiTiks.apexx.client.util.combat.StickyTargets.allows(this, player)) {
                return false;
            }
            if (ignoreFriends.isEnabled() && FriendManager.getInstance().isFriend(player.getUuid())) {
                return false;
            }
            return true;
        }

        return switch (targets.getCurrentMode()) {
            case "Players" -> false;
            case "Mobs & Players" -> entity instanceof LivingEntity;
            case "All" -> true;
            default -> false;
        };
    }

    private boolean suppressMiss() {
        // Statistical human variance: if hitChance < 100, roll to allow an organic miss swing
        double chance = hitChance.getValue();
        if (chance < 100.0 && ThreadLocalRandom.current().nextDouble() * 100.0 >= chance) {
            return false;
        }

        // Drain the pending attack keypress so vanilla does not register the click
        if (mc.options != null && mc.options.attackKey != null) {
            while (mc.options.attackKey.wasPressed()) {}
        }
        return true;
    }
}
