package me.sootysplash.box;

import net.fabricmc.api.ModInitializer;
import net.minecraft.client.MinecraftClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Main implements ModInitializer {
    public static MinecraftClient mc = MinecraftClient.getInstance();
    public static final Logger LOGGER = LoggerFactory.getLogger("CombatHitboxes");

    @Override
    public void onInitialize() {
        if (mc == null) {
            mc = MinecraftClient.getInstance();
        }
        LOGGER.info("CombatHitboxes | Sootysplash was here!");
    }
}
