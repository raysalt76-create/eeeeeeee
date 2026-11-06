package interstellarTikiTiks.apexx.client.hud;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;

public class ClientEventHandler {

    private static boolean wasPlayerAlive = false;

    public static void init() {
        // World-time packets feed the Ping & TPS widget's server tick-rate estimate.
        interstellarTikiTiks.apexx.client.event.EventBus.register(ServerTps.INSTANCE);

        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            ServerTps.INSTANCE.reset();
            CombatState.reset();
            if (interstellarTikiTiks.apexx.client.Apexx.moduleManager != null) {
                for (interstellarTikiTiks.apexx.client.module.Module module
                        : interstellarTikiTiks.apexx.client.Apexx.moduleManager.getModules()) module.releaseRuntimeState();
                interstellarTikiTiks.apexx.client.Apexx.moduleManager.resetFaults();
            }
            interstellarTikiTiks.apexx.client.util.combat.PlayerTrajectoryEngine.getInstance().clear();
            interstellarTikiTiks.apexx.client.util.combat.SubTickSequencer.discardAll();
            interstellarTikiTiks.apexx.client.util.RotationManager.abortAll();
            interstellarTikiTiks.apexx.client.util.SystemInputSimulator.releaseAll();
            interstellarTikiTiks.apexx.client.util.combat.SlotSync.clearOwnership();
            interstellarTikiTiks.apexx.client.util.combat.TickActionOrder.reset();
            TargetHudElement.clearTarget();
            HudTelemetry.reset();
            wasPlayerAlive = false;
        });

        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
            if (interstellarTikiTiks.apexx.client.Apexx.moduleManager != null) {
                interstellarTikiTiks.apexx.client.Apexx.moduleManager.resetFaults();
            }
            interstellarTikiTiks.apexx.client.util.combat.PlayerTrajectoryEngine.getInstance().clear();
            interstellarTikiTiks.apexx.client.util.combat.SubTickSequencer.discardAll();
            interstellarTikiTiks.apexx.client.util.RotationManager.abortAll();
            interstellarTikiTiks.apexx.client.util.SystemInputSimulator.releaseAll();
            interstellarTikiTiks.apexx.client.util.combat.SlotSync.clearOwnership();
            interstellarTikiTiks.apexx.client.util.combat.TickActionOrder.reset();
            TargetHudElement.clearTarget();
            HudTelemetry.reset();
            // A join starts a new HUD session (Session Stats, Server Info uptime, Ping & TPS history).
            SessionStats.reset();
            ServerTps.INSTANCE.reset();
            CombatState.reset();
            wasPlayerAlive = false;
            // Per-server config auto-load, queued so it runs on the client thread after the join settles.
            client.execute(() -> interstellarTikiTiks.apexx.client.config.ServerAutoConfig.onJoin(client));
        });

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            HudTelemetry.tick(client);
            try {
                CombatState.tick(client);
                SessionStats.tick(client);
                ServerTps.INSTANCE.sample(client);
                SpeedHudElement.tick(client);
            } catch (Throwable hudFailure) {
                interstellarTikiTiks.apexx.client.util.ClientInternalLog.error(
                        "HUD", "HUD session tick failed", hudFailure);
            }
            interstellarTikiTiks.apexx.client.event.EventBus.post(new interstellarTikiTiks.apexx.client.event.impl.TickEvent());
            try {
                interstellarTikiTiks.apexx.client.util.combat.PlayerTrajectoryEngine.getInstance().onTick(client);
            } catch (Throwable trajectoryFailure) {
                interstellarTikiTiks.apexx.client.util.ClientInternalLog.error(
                        "PlayerTrajectoryEngine", "Trajectory update failed", trajectoryFailure);
            }

            ClientPlayerEntity player = client.player;
            if (player == null) {

                wasPlayerAlive = false;
                return;
            }

            boolean isPlayerAliveNow = player.isAlive();

            wasPlayerAlive = isPlayerAliveNow;
        });
    }
}
