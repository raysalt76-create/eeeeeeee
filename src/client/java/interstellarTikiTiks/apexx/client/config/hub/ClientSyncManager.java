package interstellarTikiTiks.apexx.client.config.hub;

import interstellarTikiTiks.apexx.client.config.ConfigManager;
import net.minecraft.client.MinecraftClient;

/**
 * ClientSyncManager — NEUTERED (Apexx).
 * All web-sync / polling to remote servers removed.
 * Only the local hot-reload check (for Apexx GUI config bridge) is kept.
 */
public final class ClientSyncManager {

    private ClientSyncManager() {}

    /**
     * Called every client tick. Only does local hot-reload check.
     */
    public static void onClientTick(MinecraftClient client) {
        if (client == null) return;

        // Hot-reload: check if Apexx GUI wrote to the config file
        ConfigManager.getInstance().checkForExternalChanges();

        // All web-sync polling removed (Apexx)
    }

    public static void requestSync() {
        // No-op: web sync disabled
    }

    public static boolean isAutoSyncEnabled() {
        return false;
    }

    public static void setAutoSyncEnabled(boolean enabled) {
        // No-op
    }

    public static void setSyncCode(String code) {
        // No-op: web sync disabled (Apexx)
    }

    public static String getSyncCode() {
        return "";
    }
}
