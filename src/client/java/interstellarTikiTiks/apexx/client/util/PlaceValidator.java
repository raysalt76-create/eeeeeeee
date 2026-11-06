package interstellarTikiTiks.apexx.client.util;

import interstellarTikiTiks.apexx.client.mixin.MinecraftClientAccessor;
import net.minecraft.client.MinecraftClient;
import net.minecraft.item.ItemStack;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.world.GameMode;

public class PlaceValidator {

    public static boolean tryPlace(MinecraftClient mc) {
        return tryPlace(mc, "combat.use.place.generic");
    }

    public static boolean tryPlace(MinecraftClient mc, String actionKey) {

        if (mc.player == null || mc.world == null || mc.interactionManager == null) {
            return false;
        }

        if (mc.crosshairTarget == null || mc.crosshairTarget.getType() != HitResult.Type.BLOCK) {
            return false;
        }

        ItemStack itemStack = mc.player.getStackInHand(Hand.MAIN_HAND);
        if (itemStack.isEmpty() || !itemStack.isItemEnabled(mc.world.getEnabledFeatures())) {
            return false;
        }

        if (mc.interactionManager.getCurrentGameMode() == GameMode.SPECTATOR) {
            return false;
        }

        ((MinecraftClientAccessor) mc).useItem();
        return true;
    }

    public static boolean canPlace(MinecraftClient mc) {

        if (mc.player == null || mc.world == null || mc.interactionManager == null) {
            return false;
        }

        if (mc.crosshairTarget == null || mc.crosshairTarget.getType() != HitResult.Type.BLOCK) {
            return false;
        }

        if (mc.player.isUsingItem()) {
            return false;
        }

        ItemStack itemStack = mc.player.getStackInHand(Hand.MAIN_HAND);
        if (itemStack.isEmpty() || !itemStack.isItemEnabled(mc.world.getEnabledFeatures())) {
            return false;
        }

        if (mc.interactionManager.getCurrentGameMode() == GameMode.SPECTATOR) {
            return false;
        }

        return true;
    }

    public static BlockHitResult getBlockHitResult(MinecraftClient mc) {
        if (mc.crosshairTarget != null && mc.crosshairTarget.getType() == HitResult.Type.BLOCK) {
            return (BlockHitResult) mc.crosshairTarget;
        }
        return null;
    }
}
