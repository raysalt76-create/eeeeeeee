package interstellarTikiTiks.apexx.client.module.implementation.player;

import net.minecraft.entity.Entity;

/**
 * Decoupled hook interface implemented by AntiBot modules.
 * Allows combat and target-selection routines to query whether an entity is a server-spawned bot.
 */
public interface BotFilterProvider {

    boolean isBot(Entity entity);
}
