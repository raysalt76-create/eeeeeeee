package interstellarTikiTiks.apexx.client;

import interstellarTikiTiks.apexx.client.config.ConfigManager;
import interstellarTikiTiks.apexx.client.hud.ClientEventHandler;
import interstellarTikiTiks.apexx.client.hud.HUDManager;
import interstellarTikiTiks.apexx.client.manager.ModuleManager;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.api.ClientModInitializer;
import net.minecraft.client.MinecraftClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Timer;
import java.util.TimerTask;

public class Apexx implements ClientModInitializer {
    private static final Logger LOGGER = LoggerFactory.getLogger("Apexx");

    public static final String MOD_ID = "dynamic_fps";
    public static final Apexx INSTANCE = new Apexx();
    public static ModuleManager moduleManager;
    public static ConfigManager configManager;
    private static HUDManager hudManager;
    private static boolean moduleManagerInitializing = false;
    public final MinecraftClient mc = MinecraftClient.getInstance();
    public static boolean selfDestructed = false;
    /** Set by self-destruct / unload: the client stays down until Minecraft restarts. */
    public static volatile boolean unloaded = false;

    /**
     * True once the client has been torn down for the rest of this JVM. Every auxiliary loop that runs off its own
     * scheduler (macro polling, the ms timer, post-tick hooks) must check this and stop.
     */
    public static boolean isTornDown() {
        return unloaded || selfDestructed;
    }

    private static Timer msTimer;
    private static boolean bootstrapDone = false;
    private static boolean configReady;

    public Apexx() {
    }

    /** Fully unload the client from this session. Idempotent. */
    public static void unload() {
        unloaded = true;
        try {
            if (configManager != null) configManager.cancelPendingLoads();
        } catch (Throwable ignored) {}
        try {
            Timer timer = msTimer;
            if (timer != null) timer.cancel();
        } catch (Throwable ignored) {}
        try {
            interstellarTikiTiks.apexx.client.module.hooks.StudioProvider studio = moduleManager == null ? null
                    : moduleManager.getHook(interstellarTikiTiks.apexx.client.module.hooks.StudioProvider.class);
            if (studio != null) studio.shutdownRuntime();
        } catch (Throwable ignored) {}
        try {
            if (moduleManager != null) moduleManager.unregisterAll();
        } catch (Throwable ignored) {}
    }

    @Override
    public void onInitializeClient() {
        try {
            INSTANCE.finishBootstrap();
        } catch (Throwable failure) {
            // Minecraft still runs, just without Apexx.
            LOGGER.warn("[bootstrap] Apexx failed to start", failure);
        }
    }

    public static boolean isMenuReady() {
        if (!configReady || isTornDown() || moduleManager == null) return false;
        return moduleManager.getModules().stream()
                .anyMatch(module -> !(module instanceof interstellarTikiTiks.apexx.client.module.implementation.ClickGUIModule));
    }

    private void finishBootstrap() {
        if (isTornDown() || bootstrapDone) return;
        getModuleManager();
        getConfigManager();

        // A corrupt config must cost the saved settings, not the client: the menu opens with defaults.
        guarded("startup config", configManager::loadStartupConfig);
        configReady = true;

        guarded("shutdown hook", () -> ClientLifecycleEvents.CLIENT_STOPPING.register(client -> {
            if (!isTornDown() && configManager != null) configManager.prepareShutdownSnapshot();
        }));
        guarded("event handler", ClientEventHandler::init);
        guarded("macro/script runtime", () -> {
            interstellarTikiTiks.apexx.client.module.hooks.StudioProvider studio =
                    getModuleManager().getHook(interstellarTikiTiks.apexx.client.module.hooks.StudioProvider.class);
            if (studio != null) studio.startRuntime();
        });
        guarded("ms timer", this::startMsTimer);

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            if (msTimer != null) msTimer.cancel();
            if (configManager != null) configManager.saveShutdownSnapshot();
        }));
        bootstrapDone = true;
    }

    /** One setup step. A failure is logged and skipped so the steps after it, and the menu, still happen. */
    private static void guarded(String step, Runnable action) {
        try {
            action.run();
        } catch (Throwable failure) {
            LOGGER.warn("[bootstrap] {} failed", step, failure);
        }
    }

    private void startMsTimer() {
        msTimer = new Timer("msTimer", true);
        msTimer.scheduleAtFixedRate(new TimerTask() {
            @Override
            public void run() {
                // A Throwable escaping a TimerTask kills the timer thread for good, so nothing may leave this body.
                try {
                    if (isTornDown()) return;
                    interstellarTikiTiks.apexx.client.util.combat.SubTickSequencer.onMsTick(MinecraftClient.getInstance());
                    if (moduleManager != null) moduleManager.onMs();
                } catch (Throwable ignored) {
                    // Next millisecond runs again.
                }
            }
        }, 0, 1);
    }

    public static Apexx getInstance() {
        return INSTANCE;
    }

    public ModuleManager getModuleManager() {
        if (moduleManager == null) {
            if (moduleManagerInitializing) {
                return null;
            }
            moduleManagerInitializing = true;
            try {
                moduleManager = new ModuleManager();
            } finally {
                moduleManagerInitializing = false;
            }
        }
        return moduleManager;
    }

    public ConfigManager getConfigManager() {
        if (configManager == null) {
            configManager = ConfigManager.getInstance();
        }
        return configManager;
    }

    public HUDManager getHudManager() {
        if (hudManager == null) {
            hudManager = HUDManager.getInstance();
        }
        return hudManager;
    }
}
