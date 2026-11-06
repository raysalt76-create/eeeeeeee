package interstellarTikiTiks.apexx.client.mixin;

import interstellarTikiTiks.apexx.client.module.hooks.InventoryOverlayProvider;
import interstellarTikiTiks.apexx.client.util.ModuleAccess;
import net.minecraft.client.gui.screen.ingame.InventoryScreen;
import net.minecraft.client.gui.DrawContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(InventoryScreen.class)
public class InventoryScreenMixin {

    @Inject(method = "drawBackground", at = @At("HEAD"), cancellable = true)
    private void onDrawBackground(DrawContext context, float delta, int mouseX, int mouseY, CallbackInfo ci) {
        

        InventoryOverlayProvider inventoryOverlayProvider = ModuleAccess.getHook(InventoryOverlayProvider.class);
        if (inventoryOverlayProvider != null && inventoryOverlayProvider.shouldCancelVanillaInventory()) {
            ci.cancel();
        }
    }

    @Inject(method = "render", at = @At("HEAD"), cancellable = true)
    private void onRender(DrawContext context, int mouseX, int mouseY, float delta, CallbackInfo ci) {
        InventoryScreen self = (InventoryScreen) (Object) this;
        

        InventoryOverlayProvider inventoryOverlayProvider = ModuleAccess.getHook(InventoryOverlayProvider.class);
        if (inventoryOverlayProvider != null && inventoryOverlayProvider.shouldCancelVanillaInventory()) {
            inventoryOverlayProvider.renderInventory(context, mouseX, mouseY, delta, self);
            ci.cancel();
        }
    }

    /** Lets modules draw over the finished inventory screen (AutoTotem's simulated cursor). */
    @Inject(method = "render", at = @At("TAIL"), require = 0)
    private void apexx$renderOverlay(DrawContext context, int mouseX, int mouseY, float delta, CallbackInfo ci) {
        if (interstellarTikiTiks.apexx.client.Apexx.isTornDown()) return;
        InventoryScreen self = (InventoryScreen) (Object) this;
        for (interstellarTikiTiks.apexx.client.module.hooks.ScreenOverlayAware overlay
                : ModuleAccess.getEnabledHooks(interstellarTikiTiks.apexx.client.module.hooks.ScreenOverlayAware.class)) {
            try {
                overlay.onInventoryOverlay(context, self, delta);
            } catch (RuntimeException ignored) {
                // An overlay must never break the inventory screen.
            }
        }
    }
}
