package interstellarTikiTiks.apexx.client.module.implementation.player;

import interstellarTikiTiks.apexx.client.module.BooleanSetting;
import interstellarTikiTiks.apexx.client.module.Category;
import interstellarTikiTiks.apexx.client.module.Module;
import net.minecraft.client.network.PlayerListEntry;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * Server bot entity filter.
 * Filters out server-spawned bot entities using tablist verification,
 * entity lifetime analysis, and unnatural trajectory heuristics.
 * Implements the decoupled BotFilterProvider hook for combat routines.
 */
public class AntiBot extends Module implements BotFilterProvider {

    private final BooleanSetting tablistCheck = new BooleanSetting("Tablist Check", true)
            .setDescription("Treats a player as a bot when it is missing from the server's tab list. Real players are always listed; some servers hide staff or use NPCs, which this also skips.");
    private final BooleanSetting invisibleCheck = new BooleanSetting("Invisible Check", true)
            .setDescription("Treats an invisible player within 6 blocks of you as a bot, the usual shape of a hidden anticheat test entity.");
    private final BooleanSetting entityAgeCheck = new BooleanSetting("Entity Age Check", true)
            .setDescription("Treats a player that appeared less than 25 ticks (1.25 s) ago within 5 blocks of you, at least a block above or below you, as a bot spawned to catch combat modules.");
    private final BooleanSetting nameCheck = new BooleanSetting("Invalid Name Check", true)
            .setDescription("Treats a player whose name is empty, contains a space or is longer than 16 characters as a bot, since no real account can have such a name.");

    private final Set<Integer> detectedBotIds = new HashSet<>();
    private final Set<UUID> detectedBotUuids = new HashSet<>();

    public AntiBot() {
        super("AntiBot", "Stops combat and targeting modules from aiming at fake players: server NPCs and anticheat test bots are recognised by tab-list presence, invisibility, spawn age and name, and are skipped by every module that picks a target.", Category.PLAYER);
        addSetting(tablistCheck);
        addSetting(invisibleCheck);
        addSetting(entityAgeCheck);
        addSetting(nameCheck);
    }

    @Override
    public void onDisable() {
        detectedBotIds.clear();
        detectedBotUuids.clear();
    }

    @Override
    protected void onRuntimeStateReleased() {
        detectedBotIds.clear();
        detectedBotUuids.clear();
    }

    @Override
    public void onTick() {
        if (!isEnabled() || mc.world == null || mc.player == null) {
            detectedBotIds.clear();
            detectedBotUuids.clear();
            return;
        }

        for (Entity entity : mc.world.getPlayers()) {
            if (entity == mc.player) continue;

            if (entity instanceof PlayerEntity other) {
                if (evaluateBot(other)) {
                    detectedBotIds.add(other.getId());
                    detectedBotUuids.add(other.getUuid());
                } else {
                    detectedBotIds.remove(other.getId());
                    detectedBotUuids.remove(other.getUuid());
                }
            }
        }
    }

    private boolean evaluateBot(PlayerEntity player) {
        if (player == null || mc.player == null) return false;

        // 1. Tablist Verification
        if (tablistCheck.isEnabled() && mc.getNetworkHandler() != null) {
            PlayerListEntry entry = mc.getNetworkHandler().getPlayerListEntry(player.getUuid());
            if (entry == null) {
                return true;
            }
        }

        // 2. Invisible Bot Check
        if (invisibleCheck.isEnabled() && player.isInvisible()) {
            double dist = mc.player.distanceTo(player);
            if (dist <= 6.0) {
                return true;
            }
        }

        // 3. Entity Age Check (sudden bot spawned to catch KillAura/TriggerBot)
        if (entityAgeCheck.isEnabled()) {
            if (player.age < 25) {
                double dist = mc.player.distanceTo(player);
                if (dist <= 5.0 && Math.abs(player.getY() - mc.player.getY()) >= 1.0) {
                    return true;
                }
            }
        }

        // 4. Invalid Name Check
        if (nameCheck.isEnabled()) {
            String name = player.getName().getString();
            if (name == null || name.isEmpty() || name.contains(" ") || name.length() > 16) {
                return true;
            }
        }

        return false;
    }

    @Override
    public boolean isBot(Entity entity) {
        if (!isEnabled() || entity == null || mc.player == null) {
            return false;
        }
        if (entity.getId() == mc.player.getId()) {
            return false;
        }
        return detectedBotIds.contains(entity.getId()) || detectedBotUuids.contains(entity.getUuid());
    }
}
