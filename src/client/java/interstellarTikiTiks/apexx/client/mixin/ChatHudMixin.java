package interstellarTikiTiks.apexx.client.mixin;

import interstellarTikiTiks.apexx.client.Apexx;
import interstellarTikiTiks.apexx.client.module.hooks.NicknameProvider;
import interstellarTikiTiks.apexx.client.util.ModuleAccess;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.hud.ChatHud;
import net.minecraft.registry.RegistryWrapper;
import net.minecraft.registry.RegistryOps;
import net.minecraft.text.Text;
import net.minecraft.text.TextCodecs;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ChatHud.class)
public class ChatHudMixin {

    @ModifyVariable(
            method = "addMessage(Lnet/minecraft/text/Text;Lnet/minecraft/network/message/MessageSignatureData;Lnet/minecraft/client/gui/hud/MessageIndicator;)V",
            at = @At(value = "HEAD"),
            argsOnly = true
    )
    private Text onAddMessage(Text message) {
        if (Apexx.isTornDown()) return message;

        NicknameProvider nicknameProvider = ModuleAccess.getEnabledHook(NicknameProvider.class);
        MinecraftClient mc = MinecraftClient.getInstance();

        if (nicknameProvider == null
                || mc.player == null
                || mc.world == null) {
            return message;
        }

        String originalName = mc.player.getName().getString();
        String nickname = nicknameProvider.getSafeNickname(originalName);

        if (originalName.equals(nickname)) {
            return message;
        }

        try {

            RegistryWrapper.WrapperLookup wrapperLookup = mc.world.getRegistryManager();

            RegistryOps<JsonElement> ops = RegistryOps.of(JsonOps.INSTANCE, wrapperLookup);

            JsonElement encoded = TextCodecs.CODEC.encodeStart(ops, message).result().orElse(null);
            if (encoded == null) {
                return message;
            }

            String messageJson = encoded.toString();

            String modifiedJson = messageJson.replace(originalName, nickname);

            JsonElement modifiedElement = JsonParser.parseString(modifiedJson);
            Text modifiedText = TextCodecs.CODEC.parse(ops, modifiedElement).result().orElse(null);

            return modifiedText != null ? modifiedText : message;
        } catch (Exception e) {

            return message;
        }
    }

    @Inject(method = "render", at = @At("HEAD"), cancellable = true, require = 0)
    private void onRenderChat(CallbackInfo ci) {
        if (Apexx.isTornDown()) return;
        if (ModuleAccess.isEnabled("StreamerModeModule")) {
            ci.cancel();
        }
    }
}
