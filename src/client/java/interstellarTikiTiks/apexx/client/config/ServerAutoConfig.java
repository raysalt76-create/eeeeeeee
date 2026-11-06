package interstellarTikiTiks.apexx.client.config;

import interstellarTikiTiks.apexx.client.config.hub.PrivateConfigManager;
import interstellarTikiTiks.apexx.client.util.Notifier;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ServerInfo;

/**
 * Loads the config the user linked to a server (Configs page -> edit a config -> "Auto-load on this
 * server") when they join it. Uses the same cloud flow as the Configs page "Load" button, then makes
 * that config the active cloud profile. Singleplayer and LAN worlds are never matched.
 */
public final class ServerAutoConfig {
    private ServerAutoConfig() {}

    /** The address of the multiplayer server the client is on, or null (singleplayer, LAN, Realms, menus). */
    public static String currentServerAddress(MinecraftClient client) {
        if (client == null) return null;
        ServerInfo info = client.getCurrentServerEntry();
        if (info == null || info.isLocal() || info.isRealm() || info.address == null || info.address.isBlank()) return null;
        return info.address;
    }

    /** Client thread, from ClientPlayConnectionEvents.JOIN. */
    public static void onJoin(MinecraftClient client) {
        try {
            String address = currentServerAddress(client);
            if (address == null) return;
            String configName = ClientPrefs.get().serverConfigFor(address);
            if (configName == null || configName.isBlank()) return;
            if (!CloudConfigs.signedIn()) {
                Notifier.warn("Configs", "Sign in to auto-load \"" + configName + "\" on this server");
                return;
            }
            // Already active this session (e.g. rejoining the same server): nothing to do.
            String active = ConfigManager.getInstance().getLastLoadedConfigName();
            if (active != null && active.equalsIgnoreCase(sanitized(configName))) return;
            PrivateConfigManager.getInstance().loadConfig(configName, true, loaded -> {
                if (!loaded) {
                    Notifier.warn("Configs", "Could not auto-load \"" + configName + "\"");
                    return;
                }
                Notifier.success("Configs", "Loaded \"" + configName + "\" for " + ClientPrefs.normalizeAddress(address));
                PrivateConfigManager.getInstance().activateConfig(configName, () -> { },
                        error -> Notifier.warn("Configs", "Loaded \"" + configName + "\", but could not make it the active profile"));
            }, error -> Notifier.warn("Configs", "Could not auto-load \"" + configName + "\": "
                    + (error == null || error.isBlank() ? "config not found" : error)));
        } catch (Throwable ignored) {
            // Joining a server must never fail because of an auto-load problem.
        }
    }

    /** Mirrors ConfigManager's on-load name sanitising so the "already active" check compares like with like. */
    static String sanitized(String name) {
        if (name == null || name.isBlank()) return "default";
        String normalized = name.trim().replaceAll("[^A-Za-z0-9_.-]", "_");
        if (normalized.isBlank()) return "default";
        return normalized.length() > 64 ? normalized.substring(0, 64) : normalized;
    }
}
