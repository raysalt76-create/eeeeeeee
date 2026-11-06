package interstellarTikiTiks.apexx.client.mixin;

import interstellarTikiTiks.apexx.client.Apexx;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import interstellarTikiTiks.apexx.client.hud.TotemPopManager;
import interstellarTikiTiks.apexx.client.module.hooks.EntityStatusAware;
import interstellarTikiTiks.apexx.client.module.hooks.ExplosionVisualProvider;
import net.minecraft.particle.BlockParticleEffect;
import net.minecraft.util.collection.Pool;
import net.minecraft.util.math.Vec3d;
import interstellarTikiTiks.apexx.client.module.hooks.PacketQueueFlusher;
import interstellarTikiTiks.apexx.client.module.hooks.TotemPopAware;
import interstellarTikiTiks.apexx.client.module.hooks.TotemPopVisualProvider;
import net.minecraft.client.particle.ParticleManager;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.particle.ParticleEffect;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvent;
import interstellarTikiTiks.apexx.client.module.hooks.ShieldBreakConfirmationHandler;
import interstellarTikiTiks.apexx.client.module.hooks.VelocityPacketAware;
import interstellarTikiTiks.apexx.client.util.ModuleAccess;
import interstellarTikiTiks.apexx.client.util.ModuleCallbackGuard;
import interstellarTikiTiks.apexx.client.util.combat.MeleeMechanics;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.network.packet.s2c.play.EntityStatusS2CPacket;
import net.minecraft.network.packet.s2c.play.EntityVelocityUpdateS2CPacket;
import net.minecraft.network.packet.s2c.play.ExplosionS2CPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientPlayNetworkHandler.class)
public abstract class ClientPlayNetworkHandlerMixin {

    @Inject(method = "onEntityStatus", at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/network/packet/s2c/play/EntityStatusS2CPacket;getEntity(Lnet/minecraft/world/World;)Lnet/minecraft/entity/Entity;"
    ))
    private void onEntityStatus(EntityStatusS2CPacket packet, CallbackInfo ci) {

        MinecraftClient client = MinecraftClient.getInstance();

        if (client.world == null) {
            return;
        }

        Entity entity = packet.getEntity(client.world);

        

        

        if (packet.getStatus() == 30 && entity instanceof PlayerEntity player) {
            for (ShieldBreakConfirmationHandler handler : ModuleAccess.getEnabledHooks(ShieldBreakConfirmationHandler.class)) {
                ModuleCallbackGuard.run(handler, "shield-break hook", () -> handler.breakConfirmed(player));
            }
        }

        byte status = packet.getStatus();
        apexx$statusEntity = entity;
        if (entity == null || Apexx.isTornDown() || (status != 3 && status != 35)) return;

        // Runs on the client thread: this point is after forceMainThread in vanilla's handler, and
        // before vanilla's own pop particles/sound and death handling for this status.
        if (status == 35 && entity instanceof PlayerEntity player) {
            TotemPopManager.getInstance().onTotemPop(player);
            for (TotemPopAware hook : ModuleAccess.getEnabledHooks(TotemPopAware.class)) {
                ModuleCallbackGuard.run(hook, "totem-pop hook", () -> hook.onTotemPop(player));
            }
        } else if (status == 3 && entity instanceof PlayerEntity player) {
            TotemPopManager.getInstance().resetPopCount(player);
        }
        try {
            interstellarTikiTiks.apexx.client.hud.SessionStats.onEntityStatus(entity, status);
        } catch (Throwable ignored) {
            // HUD tally only; never disturb vanilla's status handling.
        }
        for (EntityStatusAware hook : ModuleAccess.getEnabledHooks(EntityStatusAware.class)) {
            ModuleCallbackGuard.run(hook, "entity-status hook", () -> hook.onEntityStatus(entity, status));
        }
    }

    /** Entity of the status packet being handled; read by the totem-pop wrappers below. Client thread only. */
    private static Entity apexx$statusEntity;

    @Inject(method = "onEntityStatus", at = @At("RETURN"))
    private void apexx$clearStatusEntity(EntityStatusS2CPacket packet, CallbackInfo ci) {
        apexx$statusEntity = null;
    }

    /** Totem Pop Customizer: particle amount (emitter lifetime) of the totem-pop burst; 0 skips it. */
    @WrapOperation(method = "onEntityStatus", require = 0, at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/particle/ParticleManager;addEmitter(Lnet/minecraft/entity/Entity;Lnet/minecraft/particle/ParticleEffect;I)V"))
    private void apexx$totemPopParticles(ParticleManager manager, Entity entity, ParticleEffect effect, int ticks,
                                        Operation<Void> original) {
        int resolved = ticks;
        if (!Apexx.isTornDown()) {
            TotemPopVisualProvider provider = ModuleAccess.getEnabledHook(TotemPopVisualProvider.class);
            if (provider != null) {
                resolved = ModuleCallbackGuard.call(provider, "totem-pop particles",
                        () -> provider.totemParticleTicks(entity, ticks), ticks);
            }
        }
        if (resolved <= 0) return;
        original.call(manager, entity, effect, Math.min(resolved, 200));
    }

    /** Totem Pop Customizer: lets the module mute the vanilla totem sound (it may play its own). */
    @WrapOperation(method = "onEntityStatus", require = 0, at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/world/ClientWorld;playSoundClient(DDDLnet/minecraft/sound/SoundEvent;Lnet/minecraft/sound/SoundCategory;FFZ)V"))
    private void apexx$totemPopSound(ClientWorld world, double x, double y, double z, SoundEvent sound,
                                     SoundCategory category, float volume, float pitch, boolean useDistance,
                                     Operation<Void> original) {
        if (!Apexx.isTornDown()) {
            Entity entity = apexx$statusEntity;
            TotemPopVisualProvider provider = ModuleAccess.getEnabledHook(TotemPopVisualProvider.class);
            if (provider != null && ModuleCallbackGuard.call(provider, "totem-pop sound",
                    () -> provider.suppressTotemSound(entity), false)) {
                return;
            }
        }
        original.call(world, x, y, z, sound, category, volume, pitch, useDistance);
    }

    @Inject(method = "onExplosion", at = @At("HEAD"))
    private void onExplosion(ExplosionS2CPacket packet, CallbackInfo ci) {
        

        PacketQueueFlusher packetQueueFlusher = ModuleAccess.getEnabledHook(PacketQueueFlusher.class);
        if (packetQueueFlusher != null) {
            ModuleCallbackGuard.run(packetQueueFlusher, "packet-queue flush",
                    packetQueueFlusher::sendQueuedPackets);
        }
    }

    /** True while the explosion being applied has its vanilla particles replaced. Client thread only. */
    private static boolean apexx$explosionReplaced;

    /** Crystal FX: decided once per explosion, after forceMainThread so it runs on the client thread only. */
    @Inject(method = "onExplosion", require = 0, at = @At(value = "INVOKE",
            target = "Lnet/minecraft/network/NetworkThreadUtils;forceMainThread(Lnet/minecraft/network/packet/Packet;Lnet/minecraft/network/listener/PacketListener;Lnet/minecraft/network/PacketApplyBatcher;)V",
            shift = At.Shift.AFTER))
    private void apexx$decideExplosionVisuals(ExplosionS2CPacket packet, CallbackInfo ci) {
        apexx$explosionReplaced = false;
        if (Apexx.isTornDown() || packet == null || packet.center() == null) return;
        ExplosionVisualProvider provider = ModuleAccess.getEnabledHook(ExplosionVisualProvider.class);
        if (provider == null) return;
        apexx$explosionReplaced = ModuleCallbackGuard.call(provider, "explosion visuals",
                () -> provider.replaceExplosionVisuals(packet.center(), packet.radius()), false);
    }

    @WrapOperation(method = "onExplosion", require = 0, at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/world/ClientWorld;addParticleClient(Lnet/minecraft/particle/ParticleEffect;DDDDDD)V"))
    private void apexx$explosionParticle(ClientWorld world, ParticleEffect effect, double x, double y, double z,
                                         double vx, double vy, double vz, Operation<Void> original) {
        if (apexx$explosionReplaced) return;
        original.call(world, effect, x, y, z, vx, vy, vz);
    }

    @WrapOperation(method = "onExplosion", require = 0, at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/world/ClientWorld;addBlockParticleEffects(Lnet/minecraft/util/math/Vec3d;FILnet/minecraft/util/collection/Pool;)V"))
    private void apexx$explosionBlockParticles(ClientWorld world, Vec3d center, float radius, int blockCount,
                                               Pool<BlockParticleEffect> particles, Operation<Void> original) {
        if (apexx$explosionReplaced) return;
        original.call(world, center, radius, blockCount, particles);
    }

    @Inject(method = "onExplosion", at = @At("RETURN"), require = 0)
    private void apexx$clearExplosionReplaced(ExplosionS2CPacket packet, CallbackInfo ci) {
        apexx$explosionReplaced = false;
    }

    // After forceMainThread, not at HEAD: the handler is first entered on the network thread, where
    // forceMainThread re-queues it and throws. A HEAD hook ran there too, so every velocity packet
    // reached the hooks twice, once off the client thread.
    @Inject(method = "onEntityVelocityUpdate", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/network/NetworkThreadUtils;forceMainThread(Lnet/minecraft/network/packet/Packet;Lnet/minecraft/network/listener/PacketListener;Lnet/minecraft/network/PacketApplyBatcher;)V",
            shift = At.Shift.AFTER))
    private void onEntityVelocityUpdate(EntityVelocityUpdateS2CPacket packet, CallbackInfo ci) {

        if (MinecraftClient.getInstance().player == null || MinecraftClient.getInstance().world == null) {
            return;
        }

        if (packet.getEntityId() == MinecraftClient.getInstance().player.getId()) {

            MeleeMechanics.onSelfVelocity(MinecraftClient.getInstance().player);

            for (VelocityPacketAware velocityPacketAware : ModuleAccess.getEnabledHooks(VelocityPacketAware.class)) {
                if (velocityPacketAware != null) {
                    ModuleCallbackGuard.run(velocityPacketAware, "velocity hook",
                            velocityPacketAware::onVelocityPacket);
                }
            }
        }
    }

}
