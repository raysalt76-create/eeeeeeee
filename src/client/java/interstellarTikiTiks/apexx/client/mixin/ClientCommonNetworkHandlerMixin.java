package interstellarTikiTiks.apexx.client.mixin;

import net.minecraft.client.network.ClientCommonNetworkHandler;
import net.minecraft.network.packet.Packet;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientCommonNetworkHandler.class)
public abstract class ClientCommonNetworkHandlerMixin {

    /** Holds action packets sent outside the tick action window until the next tick head. */
    @Inject(method = "sendPacket", at = @At("HEAD"), cancellable = true)
    private void apexx$holdOutOfWindowActions(Packet<?> packet, CallbackInfo ci) {
        if (interstellarTikiTiks.apexx.client.util.combat.TickActionOrder.hold(packet)) {
            ci.cancel();
        }
    }
}
