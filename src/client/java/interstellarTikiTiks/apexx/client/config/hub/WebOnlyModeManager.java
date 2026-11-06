package interstellarTikiTiks.apexx.client.config.hub;

import net.minecraft.client.MinecraftClient;

public final class WebOnlyModeManager {

    private WebOnlyModeManager() {
    }

    public static boolean isWebOnlyActive() {
        return false;
    }

    public static void onClientTick(MinecraftClient client) {
    }
}
