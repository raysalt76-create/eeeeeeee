package interstellarTikiTiks.apexx.client.mixin;

import com.mojang.authlib.GameProfile;
import interstellarTikiTiks.apexx.client.module.hooks.NicknameProvider;
import interstellarTikiTiks.apexx.client.util.GameProfileCompat;
import interstellarTikiTiks.apexx.client.util.ModuleAccess;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.PlayerListEntry;
import net.minecraft.text.Text;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(PlayerListEntry.class)
public class PlayerListEntryMixin {

    @Shadow @Final private GameProfile profile;

    @Inject(method = "getDisplayName", at = @At("HEAD"), cancellable = true)
    private void onGetDisplayName(CallbackInfoReturnable<Text> cir) {

        NicknameProvider nicknameProvider = ModuleAccess.getEnabledHook(NicknameProvider.class);
        if (nicknameProvider == null || MinecraftClient.getInstance().player == null) {
            return;
        }

        if (MinecraftClient.getInstance().player.getUuid().equals(GameProfileCompat.getId(this.profile))) {
            String originalName = GameProfileCompat.getName(this.profile);
            if (originalName == null) {
                originalName = "";
            }
            String nickname = nicknameProvider.getSafeNickname(originalName);
            cir.setReturnValue(Text.of(nickname));
        }
    }
}
