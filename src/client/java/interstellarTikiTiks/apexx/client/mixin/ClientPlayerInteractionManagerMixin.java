package interstellarTikiTiks.apexx.client.mixin;

import interstellarTikiTiks.apexx.client.Apexx;
import interstellarTikiTiks.apexx.client.event.ItemUseListener;
import interstellarTikiTiks.apexx.client.hud.HUDManager;
import interstellarTikiTiks.apexx.client.module.hooks.AttackAware;
import interstellarTikiTiks.apexx.client.module.hooks.AttackTargetAware;
import interstellarTikiTiks.apexx.client.util.ModuleAccess;
import interstellarTikiTiks.apexx.client.util.SystemInputSimulator;
import interstellarTikiTiks.apexx.client.util.ModuleCallbackGuard;
import interstellarTikiTiks.apexx.client.util.RotationManager;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.network.ClientPlayerInteractionManager;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.BlockItem;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import interstellarTikiTiks.apexx.client.util.combat.MeleeMechanics;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ClientPlayerInteractionManager.class)
public class ClientPlayerInteractionManagerMixin {

    @Unique
    private static final org.slf4j.Logger APEXX_LOGGER = org.slf4j.helpers.NOPLogger.NOP_LOGGER;

    @Inject(method = "attackEntity", at = @At("HEAD"), cancellable = true)
    private void onAttackEntity(PlayerEntity player, Entity target, CallbackInfo ci) {
        if (Apexx.isTornDown()) return;
        SystemInputSimulator.showModuleClick(org.lwjgl.glfw.GLFW.GLFW_MOUSE_BUTTON_LEFT);
        interstellarTikiTiks.apexx.client.util.combat.TickActionOrder.recordAttack();

        if (player != null && target != null) {
            Vec3d eyePos = player.getEyePos();
            Box box = target.getBoundingBox();
            double cx = MathHelper.clamp(eyePos.x, box.minX, box.maxX);
            double cy = MathHelper.clamp(eyePos.y, box.minY, box.maxY);
            double cz = MathHelper.clamp(eyePos.z, box.minZ, box.maxZ);

            double reach = eyePos.distanceTo(new Vec3d(cx, cy, cz));
            try {
                HUDManager.updateLastHitReach(reach);
                HUDManager.updateTarget(target);
            } catch (Throwable failure) {
                APEXX_LOGGER.warn("Hit HUD update failed", failure);
            }
        }

        // Record the hit (strong / crit / sprint knockback) before the hooks read it.
        if (player != null && player == MinecraftClient.getInstance().player) {
            MeleeMechanics.onAttackEntity(player, target);
            try {
                interstellarTikiTiks.apexx.client.util.combat.StickyTargets.onLocalAttack(target);
            } catch (Throwable failure) {
                APEXX_LOGGER.warn("Sticky target record failed", failure);
            }
        }

        for (AttackTargetAware attackTargetAware : ModuleAccess.getEnabledHooks(AttackTargetAware.class)) {
            ModuleCallbackGuard.run(attackTargetAware, "attack target hook",
                    () -> attackTargetAware.onAttack(target));
        }

        AttackAware attackAware = ModuleAccess.getEnabledHook(AttackAware.class);
        if (attackAware != null) {
            ModuleCallbackGuard.run(attackAware, "attack hook", attackAware::onAttack);
        }

        if (player != null && player.getMainHandStack().isOf(net.minecraft.item.Items.MACE)) {
            try {
                interstellarTikiTiks.apexx.client.event.EventBus.post(new interstellarTikiTiks.apexx.client.event.impl.MaceHitEvent());
            } catch (Throwable failure) {
                APEXX_LOGGER.warn("Mace hit event dispatch failed", failure);
            }
        }
    }


    @Inject(method = "interactBlock", at = @At("HEAD"), cancellable = true)
    private void onInteractBlock(ClientPlayerEntity player, Hand hand, BlockHitResult hitResult,
            CallbackInfoReturnable<ActionResult> cir) {
        if (Apexx.isTornDown()) return;
        SystemInputSimulator.showModuleClick(org.lwjgl.glfw.GLFW.GLFW_MOUSE_BUTTON_RIGHT);
        interstellarTikiTiks.apexx.client.util.combat.TickActionOrder.recordUse();
        if (ItemUseListener.onItemUse(hitResult)) {
            // A cancelled block interaction needs a concrete result. Cancelling the callback
            // without setting one returns null from interactBlock(), and Minecraft's caller then
            // dereferences that result. CartCore's initiating crossbow use returns FAIL here so the
            // charged bolt is preserved for the final shot.
            cir.setReturnValue(ActionResult.FAIL);
            return;
        }
        String clickedBlockId = "";
        String usedBlockId = "";

        if (hitResult != null && MinecraftClient.getInstance().world != null) {
            try {
                clickedBlockId = Registries.BLOCK
                        .getId(MinecraftClient.getInstance().world.getBlockState(hitResult.getBlockPos()).getBlock())
                        .toString();
            } catch (Throwable ignored) {
                // Ignore exception during block ID resolution; fallback to empty string
            }
        }

        if (player != null) {
            try {
                ItemStack usedStack = player.getStackInHand(hand);
                if (!usedStack.isEmpty() && usedStack.getItem() instanceof BlockItem blockItem) {
                    usedBlockId = Registries.BLOCK.getId(blockItem.getBlock()).toString();
                }
            } catch (Throwable ignored) {
                // Ignore exception during block ID resolution; fallback to empty string
            }
        }

        interstellarTikiTiks.apexx.client.module.hooks.StudioProvider studio = interstellarTikiTiks.apexx.client.util.ModuleAccess.getHook(interstellarTikiTiks.apexx.client.module.hooks.StudioProvider.class);
        if (studio != null) {
            try { studio.notifyBlockInteract(clickedBlockId, usedBlockId); } catch (Throwable ignored) { }
        }
    }

    // Item use, entity use and use-release also close the hotbar window for this packet interval
    // (vanilla handles hotbar keys before clicks), so TickActionOrder must see them too.
    @Inject(method = "interactItem", at = @At("HEAD"))
    private void onInteractItem(CallbackInfoReturnable<ActionResult> cir) {
        if (Apexx.isTornDown()) return;
        SystemInputSimulator.showModuleClick(org.lwjgl.glfw.GLFW.GLFW_MOUSE_BUTTON_RIGHT);
        interstellarTikiTiks.apexx.client.util.combat.TickActionOrder.recordUse();
    }

    @Inject(method = "interactEntity", at = @At("HEAD"))
    private void onInteractEntity(CallbackInfoReturnable<ActionResult> cir) {
        if (Apexx.isTornDown()) return;
        SystemInputSimulator.showModuleClick(org.lwjgl.glfw.GLFW.GLFW_MOUSE_BUTTON_RIGHT);
        interstellarTikiTiks.apexx.client.util.combat.TickActionOrder.recordUse();
    }

    @Inject(method = "stopUsingItem", at = @At("HEAD"))
    private void onStopUsingItem(CallbackInfo ci) {
        if (Apexx.isTornDown()) return;
        interstellarTikiTiks.apexx.client.util.combat.TickActionOrder.recordUse();
    }

    @Inject(method = "interactBlock", at = @At("RETURN"))
    private void onPostInteractBlock(ClientPlayerEntity player, Hand hand, BlockHitResult hitResult,
            CallbackInfoReturnable<ActionResult> cir) {
        if (Apexx.isTornDown()) return;
        ItemUseListener.onPostItemUse(hand, hitResult, cir.getReturnValue());
        notifyRailPlaced(player, hand, hitResult, cir.getReturnValue());
    }

    /**
     * Fires {@link interstellarTikiTiks.apexx.client.module.hooks.RailPlacedAware} for a rail the real
     * player just placed. {@code currentOwner() == null} is the whole guard: every module's own placement
     * (AutoCart, AutoXBowCart, ...) runs its tick inside {@code ModuleExecutionContext.run(module, ...)},
     * so this is skipped for those and only ever reaches a placement that came from the player's own input.
     */
    @Unique
    private void notifyRailPlaced(ClientPlayerEntity player, Hand hand, BlockHitResult hitResult, ActionResult result) {
        if (player == null || hitResult == null || result == null || !result.isAccepted()
                || interstellarTikiTiks.apexx.client.util.ModuleExecutionContext.currentOwner() != null) return;
        ItemStack used = player.getStackInHand(hand);
        if (!(used.getItem() instanceof BlockItem blockItem) || !(blockItem.getBlock() instanceof net.minecraft.block.AbstractRailBlock)) return;
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.world == null) return;
        net.minecraft.util.math.BlockPos placedAt = interstellarTikiTiks.apexx.client.util.combat.BlockPlacementValidator.placementCell(mc, hitResult);
        if (placedAt == null || !(mc.world.getBlockState(placedAt).getBlock() instanceof net.minecraft.block.AbstractRailBlock)) return;
        interstellarTikiTiks.apexx.client.module.hooks.RailPlacedAware listener =
                interstellarTikiTiks.apexx.client.util.ModuleAccess.getHook(interstellarTikiTiks.apexx.client.module.hooks.RailPlacedAware.class);
        if (listener == null) return;
        interstellarTikiTiks.apexx.client.util.ModuleCallbackGuard.run(listener, "rail placed hook",
                () -> listener.onRailPlaced(placedAt));
    }

    @Inject(method = "interactItem", at = @At("HEAD"))
    private void onInteractItem(PlayerEntity player, Hand hand, CallbackInfoReturnable<ActionResult> cir) {
        if (Apexx.isTornDown()) return;
        SystemInputSimulator.showModuleClick(org.lwjgl.glfw.GLFW.GLFW_MOUSE_BUTTON_RIGHT);
        interstellarTikiTiks.apexx.client.util.combat.TickActionOrder.recordUse();
    }

    @Inject(method = "interactEntityAtLocation", at = @At("HEAD"))
    private void apexx$showInteractAtClick(CallbackInfoReturnable<ActionResult> cir) {
        if (Apexx.isTornDown()) return;
        SystemInputSimulator.showModuleClick(org.lwjgl.glfw.GLFW.GLFW_MOUSE_BUTTON_RIGHT);
    }

    @Inject(method = "attackWithPiercingWeapon", at = @At("HEAD"))
    private void apexx$showPiercingClick(CallbackInfo ci) {
        if (Apexx.isTornDown()) return;
        SystemInputSimulator.showModuleClick(org.lwjgl.glfw.GLFW.GLFW_MOUSE_BUTTON_LEFT);
    }

    @Inject(method = "attackBlock", at = @At("HEAD"))
    private void apexx$showBlockAttackClick(CallbackInfoReturnable<Boolean> cir) {
        if (Apexx.isTornDown()) return;
        SystemInputSimulator.showModuleClick(org.lwjgl.glfw.GLFW.GLFW_MOUSE_BUTTON_LEFT);
    }

    @Inject(method = "clickSlot", at = @At("HEAD"))
    private void apexx$showInventoryClick(int syncId, int slotId, int button,
            net.minecraft.screen.slot.SlotActionType type, PlayerEntity player, CallbackInfo ci) {
        if (Apexx.isTornDown()) return;
        if (type == net.minecraft.screen.slot.SlotActionType.PICKUP
                || type == net.minecraft.screen.slot.SlotActionType.QUICK_MOVE
                || type == net.minecraft.screen.slot.SlotActionType.PICKUP_ALL
                || type == net.minecraft.screen.slot.SlotActionType.CLONE) {
            SystemInputSimulator.showModuleClick(button);
        }
    }

    @Mixin(ClientPlayerInteractionManager.class)
    public interface ClientPlayerInteractionManagerAccessor {
        @Mutable
        @Accessor("blockBreakingCooldown")
        void setBlockBreakingCooldown(int value);

        @Accessor("lastSelectedSlot")
        int getLastSelectedSlot();

        @Mutable
        @Accessor("lastSelectedSlot")
        void setLastSelectedSlot(int value);

        @Invoker("syncSelectedSlot")
        void invokeSyncSelectedSlot();
    }
}
