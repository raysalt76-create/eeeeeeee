package interstellarTikiTiks.apexx.client.mixin;

import interstellarTikiTiks.apexx.client.Apexx;
import interstellarTikiTiks.apexx.client.module.hooks.NicknameProvider;
import interstellarTikiTiks.apexx.client.util.ModuleAccess;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.text.Text;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(PlayerEntity.class)
public class PlayerEntityMixin {

    @Inject(method = "getDisplayName", at = @At("HEAD"), cancellable = true)
    private void onGetDisplayName(CallbackInfoReturnable<Text> cir) {
        if (Apexx.isTornDown()) {
            return;
        }

        PlayerEntity self = (PlayerEntity) (Object) this;
        MinecraftClient mc = MinecraftClient.getInstance();

        if (mc.player == null || !mc.player.equals(self)) {
            return;
        }

        

        NicknameProvider nicknameProvider = ModuleAccess.getEnabledHook(NicknameProvider.class);
        if (nicknameProvider == null) {
            return;
        }

        String originalName = self.getName().getString();
        String finalName = nicknameProvider.getSafeNickname(originalName);

        cir.setReturnValue(Text.of(finalName));
    }
}
