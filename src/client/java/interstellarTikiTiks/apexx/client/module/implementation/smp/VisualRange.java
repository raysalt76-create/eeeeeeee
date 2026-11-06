package interstellarTikiTiks.apexx.client.module.implementation.smp;

import interstellarTikiTiks.apexx.client.friend.FriendManager;
import interstellarTikiTiks.apexx.client.module.BooleanSetting;
import interstellarTikiTiks.apexx.client.module.Category;
import interstellarTikiTiks.apexx.client.module.Module;
import interstellarTikiTiks.apexx.client.module.SliderSetting;
import interstellarTikiTiks.apexx.client.module.implementation.player.BotFilterProvider;
import interstellarTikiTiks.apexx.client.util.ModuleAccess;
import interstellarTikiTiks.apexx.client.util.Notifier;
import net.minecraft.client.sound.PositionedSoundInstance;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.sound.SoundEvents;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Visual Range: a toast (and optional sound) when another player is loaded into, or drops out of, your
 * render distance. Friends and bots are never reported. Everything is kept in memory for the session.
 */
public final class VisualRange extends Module {

    private final BooleanSetting onEnter = new BooleanSetting("Alert On Enter", true)
            .setDescription("Notify when a player comes into your render distance.");
    private final BooleanSetting onLeave = new BooleanSetting("Alert On Leave", true)
            .setDescription("Notify when a player leaves your render distance (or logs out nearby).");
    private final BooleanSetting sound = new BooleanSetting("Sound", true)
            .setDescription("Play a short note with each alert (only you hear it).");
    private final BooleanSetting ignoreFriends = new BooleanSetting("Ignore Friends", true)
            .setDescription("Players on your friends list never trigger an alert.");
    private final BooleanSetting alertOnJoin = new BooleanSetting("Alert On World Join", false)
            .setDescription("Off: players already around you when you join or change dimension are taken in silently. On: they are reported like anyone else.");
    private final SliderSetting cooldown = new SliderSetting("Cooldown (s)", 30, 0, 300, 0)
            .setDescription("The same player is reported at most once per this many seconds (enter and leave counted separately), so someone hovering at the edge of your render distance does not spam alerts. 0 = no cooldown.");

    private final Set<UUID> present = new HashSet<>();
    private final Map<UUID, String> names = new HashMap<>();
    private final Map<String, Long> lastAlert = new HashMap<>();
    private ClientWorld seenWorld;

    public VisualRange() {
        super("VisualRange", "Alerts you (toast and optional sound) when another player enters or leaves your render distance. Friends are ignored and a per-player cooldown stops repeats.", Category.SMP);
        addSetting(onEnter);
        addSetting(onLeave);
        addSetting(sound);
        addSetting(ignoreFriends);
        addSetting(alertOnJoin);
        addSetting(cooldown);
    }

    @Override
    public void onEnable() {
        reset();
    }

    @Override
    public void onDisable() {
        reset();
    }

    @Override
    protected void onRuntimeStateReleased() {
        reset();
    }

    private void reset() {
        present.clear();
        names.clear();
        lastAlert.clear();
        seenWorld = null;
    }

    @Override
    public void onTick() {
        if (mc.player == null || mc.world == null) {
            present.clear();
            names.clear();
            seenWorld = null;
            return;
        }
        boolean freshWorld = mc.world != seenWorld;
        if (freshWorld) {
            present.clear();
            names.clear();
            seenWorld = mc.world;
        }
        boolean silent = freshWorld && !alertOnJoin.isEnabled();

        Set<UUID> now = new HashSet<>();
        for (PlayerEntity other : mc.world.getPlayers()) {
            if (other == mc.player || other.getUuid() == null || other.getUuid().equals(mc.player.getUuid())) continue;
            if (isBot(other)) continue;
            UUID id = other.getUuid();
            String name = other.getName().getString();
            now.add(id);
            names.put(id, name);
            if (!present.contains(id) && !silent && onEnter.isEnabled() && reportable(id, name)) {
                alert("enter:" + name, name + " entered visual range", true);
            }
        }
        for (UUID gone : present) {
            if (now.contains(gone)) continue;
            String name = names.getOrDefault(gone, "A player");
            if (onLeave.isEnabled() && reportable(gone, name)) {
                alert("leave:" + name, name + " left visual range", false);
            }
        }
        present.retainAll(now);
        present.addAll(now);
        names.keySet().retainAll(now);
    }

    private boolean reportable(UUID id, String name) {
        if (!ignoreFriends.isEnabled()) return true;
        FriendManager friends = FriendManager.getInstance();
        return !friends.isFriend(id) && !friends.isFriend(name);
    }

    private boolean isBot(PlayerEntity player) {
        for (BotFilterProvider bots : ModuleAccess.getEnabledHooks(BotFilterProvider.class)) {
            if (bots.isBot(player)) return true;
        }
        return false;
    }

    private void alert(String key, String message, boolean entered) {
        long nowMs = System.currentTimeMillis();
        long cooldownMs = Math.round(cooldown.getValue() * 1000.0);
        Long last = lastAlert.get(key);
        if (last != null && cooldownMs > 0L && nowMs - last < cooldownMs) return;
        lastAlert.put(key, nowMs);
        if (lastAlert.size() > 512) lastAlert.entrySet().removeIf(e -> nowMs - e.getValue() > Math.max(cooldownMs, 1000L));
        if (entered) {
            Notifier.warn("VisualRange", message);
        } else {
            Notifier.info("VisualRange", message);
        }
        if (sound.isEnabled() && mc.getSoundManager() != null) {
            mc.getSoundManager().play(PositionedSoundInstance.ui(SoundEvents.BLOCK_NOTE_BLOCK_PLING, entered ? 1.4f : 0.8f));
        }
    }
}
