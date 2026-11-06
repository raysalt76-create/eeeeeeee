package interstellarTikiTiks.apexx.client.mixin;

import interstellarTikiTiks.apexx.client.module.hooks.OutgoingPacketInterceptor;
import interstellarTikiTiks.apexx.client.network.PingEqualizer;
import interstellarTikiTiks.apexx.client.util.ModuleAccess;
import interstellarTikiTiks.apexx.client.util.ModuleCallbackGuard;
import io.netty.channel.ChannelHandlerContext;
import net.minecraft.client.MinecraftClient;
import net.minecraft.network.ClientConnection;
import net.minecraft.network.packet.Packet;
import net.minecraft.network.packet.c2s.query.QueryPingC2SPacket;
import net.minecraft.network.packet.s2c.query.PingResultS2CPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientConnection.class)
public abstract class ClientConnectionMixin {

    @Inject(method = "send(Lnet/minecraft/network/packet/Packet;)V", at = @At("HEAD"), cancellable = true)
    private void onSendPacket(Packet<?> packet, CallbackInfo ci) {
        if (interstellarTikiTiks.apexx.client.Apexx.isTornDown()) return;
        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null || client.player == null || client.world == null) {
            return;
        }

        if (packet instanceof QueryPingC2SPacket pingPacket) {
            PingEqualizer.getInstance().onPingQuerySent(pingPacket.getStartTime());
        }

        if (packet instanceof net.minecraft.network.packet.c2s.play.PlayerInteractItemC2SPacket interactPacket) {
            if (client.player != null) {
                net.minecraft.item.ItemStack held = client.player.getStackInHand(interactPacket.getHand());
                if (held != null && held.isOf(net.minecraft.item.Items.ENDER_PEARL)) {
                    interstellarTikiTiks.apexx.client.event.EventBus.post(new interstellarTikiTiks.apexx.client.event.impl.PearlThrowEvent());
                }
            }
            if (interstellarTikiTiks.apexx.client.util.RotationManager.isSilentRotationActive()) {
                float sYaw = interstellarTikiTiks.apexx.client.util.RotationManager.getFinalYaw();
                float sPitch = interstellarTikiTiks.apexx.client.util.RotationManager.getFinalPitch();
                if (Float.isFinite(sYaw) && Float.isFinite(sPitch)) {
                    ((PlayerInteractItemC2SPacketAccessor) interactPacket).setYaw(sYaw);
                    ((PlayerInteractItemC2SPacketAccessor) interactPacket).setPitch(sPitch);
                }
            }
        }

        for (OutgoingPacketInterceptor interceptor : ModuleAccess.getEnabledHooks(OutgoingPacketInterceptor.class)) {
            if (ModuleCallbackGuard.call(interceptor, "outgoing packet hook",
                    () -> interceptor.handleOutgoingPacket(packet), false)) {
                ci.cancel();
                return;
            }
        }
    }

    @Inject(method = "channelRead0(Lio/netty/channel/ChannelHandlerContext;Lnet/minecraft/network/packet/Packet;)V", at = @At("HEAD"), cancellable = true)
    private void onChannelRead0(ChannelHandlerContext channelHandlerContext, Packet<?> packet, CallbackInfo ci) {
        if (interstellarTikiTiks.apexx.client.Apexx.isTornDown()) return;
        if (packet instanceof PingResultS2CPacket pingResult) {
            PingEqualizer.getInstance().onPingResult(pingResult);
        }

        for (interstellarTikiTiks.apexx.client.module.hooks.IncomingPacketInterceptor interceptor : ModuleAccess.getEnabledHooks(interstellarTikiTiks.apexx.client.module.hooks.IncomingPacketInterceptor.class)) {
            if (ModuleCallbackGuard.call(interceptor, "incoming packet hook",
                    () -> interceptor.handleIncomingPacket(packet), false)) {
                ci.cancel();
                return;
            }
        }

        interstellarTikiTiks.apexx.client.event.impl.PacketReceiveEvent event = new interstellarTikiTiks.apexx.client.event.impl.PacketReceiveEvent(packet);
        interstellarTikiTiks.apexx.client.event.EventBus.post(event);
    }
}
