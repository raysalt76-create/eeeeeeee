package interstellarTikiTiks.apexx.client.mixin;

import interstellarTikiTiks.apexx.client.module.hooks.InventoryOverlayProvider;
import interstellarTikiTiks.apexx.client.util.ModuleAccess;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.input.KeyInput;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.client.gui.screen.ingame.InventoryScreen;
import net.minecraft.screen.slot.Slot;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(HandledScreen.class)
public class HandledScreenMixin {
    @Inject(method = "drawSlot", at = @At("HEAD"), cancellable = true)
    private void onDrawSlot(DrawContext context, Slot slot, int x, int y, CallbackInfo ci) {
        if (interstellarTikiTiks.apexx.client.Apexx.isTornDown()) return;
        HandledScreen<?> screen = (HandledScreen<?>) (Object) this;

        InventoryOverlayProvider inventoryOverlayProvider = ModuleAccess.getHook(InventoryOverlayProvider.class);
        if (screen instanceof InventoryScreen
                && inventoryOverlayProvider != null
                && inventoryOverlayProvider.shouldCancelVanillaInventory()) {
            ci.cancel();
        }
    }

    @Inject(method = "drawSlotHighlightBack", at = @At("HEAD"), cancellable = true)
    private void onDrawSlotHighlightBack(DrawContext context, CallbackInfo ci) {
        if (interstellarTikiTiks.apexx.client.Apexx.isTornDown()) return;
        HandledScreen<?> screen = (HandledScreen<?>) (Object) this;

        InventoryOverlayProvider inventoryOverlayProvider = ModuleAccess.getHook(InventoryOverlayProvider.class);
        if (screen instanceof InventoryScreen
                && inventoryOverlayProvider != null
                && inventoryOverlayProvider.shouldCancelVanillaInventory()) {
            ci.cancel();
        }
    }

    @Inject(method = "drawSlotHighlightFront", at = @At("HEAD"), cancellable = true)
    private void onDrawSlotHighlightFront(DrawContext context, CallbackInfo ci) {
        if (interstellarTikiTiks.apexx.client.Apexx.isTornDown()) return;
        HandledScreen<?> screen = (HandledScreen<?>) (Object) this;

        InventoryOverlayProvider inventoryOverlayProvider = ModuleAccess.getHook(InventoryOverlayProvider.class);
        if (screen instanceof InventoryScreen
                && inventoryOverlayProvider != null
                && inventoryOverlayProvider.shouldCancelVanillaInventory()) {
            ci.cancel();
        }
    }

    @Inject(method = "keyPressed", at = @At("HEAD"), cancellable = true)
    private void onKeyPressed(KeyInput keyInput, CallbackInfoReturnable<Boolean> cir) {
        if (interstellarTikiTiks.apexx.client.Apexx.isTornDown()) return;
        HandledScreen<?> screen = (HandledScreen<?>) (Object) this;

        InventoryOverlayProvider inventoryOverlayProvider = ModuleAccess.getHook(InventoryOverlayProvider.class);
        if (screen instanceof InventoryScreen inventoryScreen
                && inventoryOverlayProvider != null
                && inventoryOverlayProvider.shouldCancelVanillaInventory()) {
            boolean handled = inventoryOverlayProvider.handleKeyPress(inventoryScreen, keyInput);
            if (handled) {
                cir.setReturnValue(true);
            }
        }
    }
}
