package interstellarTikiTiks.apexx.client.mixin;

import interstellarTikiTiks.apexx.client.module.hooks.HeldItemAnimationProvider;
import interstellarTikiTiks.apexx.client.module.hooks.ViewTransformProvider;
import interstellarTikiTiks.apexx.client.util.ModuleAccess;
import interstellarTikiTiks.apexx.client.util.ModuleCallbackGuard;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.AbstractClientPlayerEntity;
import net.minecraft.client.render.command.OrderedRenderCommandQueue;
import net.minecraft.client.render.item.HeldItemRenderer;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.item.ItemStack;
import net.minecraft.util.Arm;
import net.minecraft.util.Hand;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(HeldItemRenderer.class)
public abstract class HeldItemRendererMixin {

    @Shadow private ItemStack mainHand;
    @Shadow private ItemStack offHand;
    @Shadow private float equipProgressMainHand;
    @Shadow private float equipProgressOffHand;

    // Several providers can be enabled at once (e.g. Swing Speed and ViewModel), so every hook here
    // walks all enabled providers instead of only the first one.
    @Inject(method = "applySwingOffset", at = @At("HEAD"), cancellable = true)
    private void apexx$onApplySwingOffset(MatrixStack matrices, Arm arm, float swingProgress, CallbackInfo ci) {
        if (interstellarTikiTiks.apexx.client.Apexx.isTornDown()) return;
        for (HeldItemAnimationProvider provider : ModuleAccess.getEnabledHooks(HeldItemAnimationProvider.class)) {
            if (ModuleCallbackGuard.call(provider, "swing offset",
                    () -> provider.applyCustomSwingOffset(matrices, arm, swingProgress), false)) {
                ci.cancel();
                return;
            }
        }
    }

    @Inject(method = "updateHeldItems", at = @At("TAIL"))
    private void apexx$onUpdateHeldItems(CallbackInfo ci) {
        if (interstellarTikiTiks.apexx.client.Apexx.isTornDown()) return;
        boolean instantEquip = false;
        for (HeldItemAnimationProvider provider : ModuleAccess.getEnabledHooks(HeldItemAnimationProvider.class)) {
            if (ModuleCallbackGuard.call(provider, "instant equip", provider::isInstantEquipEnabled, false)) {
                instantEquip = true;
                break;
            }
        }
        if (!instantEquip) return;
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null) return;

        if (this.equipProgressMainHand < 1.0f) {
            this.equipProgressMainHand = 1.0f;
            this.mainHand = mc.player.getMainHandStack();
        }
        if (this.equipProgressOffHand < 1.0f) {
            this.equipProgressOffHand = 1.0f;
            this.offHand = mc.player.getOffHandStack();
        }
    }

    @Inject(
            method = "renderFirstPersonItem",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/render/item/HeldItemRenderer;renderItem(Lnet/minecraft/entity/LivingEntity;Lnet/minecraft/item/ItemStack;Lnet/minecraft/item/ItemDisplayContext;Lnet/minecraft/client/util/math/MatrixStack;Lnet/minecraft/client/render/command/OrderedRenderCommandQueue;I)V"
            )
    )
    private void onBeforeRenderItem(AbstractClientPlayerEntity player, float tickDelta, float pitch, Hand hand, float swingProgress, ItemStack item, float equipProgress, MatrixStack matrices, OrderedRenderCommandQueue queue, int light, CallbackInfo ci) {
        if (interstellarTikiTiks.apexx.client.Apexx.isTornDown()) return;
        for (ViewTransformProvider provider : ModuleAccess.getEnabledHooks(ViewTransformProvider.class)) {
            if (hand == Hand.MAIN_HAND || provider.getApplyTarget() == ViewTransformProvider.Target.BOTH) {
                ModuleCallbackGuard.run(provider, "view transform", () -> provider.applyTransformations(matrices, hand));
            }
        }
    }
}
