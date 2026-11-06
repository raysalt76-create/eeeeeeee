package interstellarTikiTiks.apexx.client.module.implementation.smp;

import interstellarTikiTiks.apexx.client.module.BooleanSetting;
import interstellarTikiTiks.apexx.client.module.Category;
import interstellarTikiTiks.apexx.client.module.Module;
import interstellarTikiTiks.apexx.client.module.SliderSetting;
import interstellarTikiTiks.apexx.client.module.StringSetting;
import interstellarTikiTiks.apexx.client.util.Notifier;
import net.minecraft.client.network.AbstractClientPlayerEntity;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.client.network.PlayerListEntry;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.world.GameMode;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Staff Detector - warns you (toast and a sound) when server staff are online or nearby. A player counts
 * as staff when their name is on your list, their tab-list name carries a staff rank (Owner, Admin, Mod,
 * Helper, ...), their tab entry shows them in spectator or creative, or (optional heuristic) they are in
 * the world next to you while hidden from the tab list, which is how most vanish plugins look. Each
 * player alerts once, then again only after the per-player cooldown, so a staff member sitting in tab
 * does not spam you. It only reads the tab list and the loaded players; it sends nothing.
 */
public final class StaffDetector extends Module {

    private final StringSetting staffNames = new StringSetting("Staff Names", "")
            .setDescription("Comma-separated player names to always treat as staff (case does not matter). "
                    + "Example: Notch, jeb_");
    private final BooleanSetting checkRanks = new BooleanSetting("Check Ranks", true)
            .setDescription("Treat a player as staff when their tab-list name contains one of the Rank Keywords "
                    + "as a whole word (so \"[Mod]\" matches but \"Modern\" does not).");
    private final StringSetting rankKeywords = new StringSetting("Rank Keywords",
            "Owner, Admin, Administrator, Mod, Moderator, Helper, Staff, Dev, Developer, Manager, Support, Trainee, SrMod, JrMod")
            .setDescription("Comma-separated rank words looked for in tab-list names when Check Ranks is on.")
            .setDependsOn("Check Ranks");
    private final BooleanSetting checkGamemode = new BooleanSetting("Check Gamemode", true)
            .setDescription("Treat other players listed in spectator or creative mode as staff. Turn off on "
                    + "creative or minigame servers where that is normal.");
    private final BooleanSetting vanishCheck = new BooleanSetting("Vanish Heuristic", false)
            .setDescription("Alert when a player is loaded in the world next to you but missing from the tab "
                    + "list, which is how most vanish plugins look. NPCs are skipped, but some lobbies still "
                    + "trigger it, so it is off by default.");
    private final BooleanSetting playSound = new BooleanSetting("Sound", true)
            .setDescription("Play a short ping with each alert, so you notice it while fighting.");
    private final SliderSetting cooldown = new SliderSetting("Alert Cooldown (s)", 120, 10, 900, 0)
            .setDescription("How long before the same player can alert again. Leaving and rejoining the "
                    + "server resets it.");
    private final SliderSetting scanDelay = new SliderSetting("Scan Delay (ms)", 1000, 0, 5000, 0)
            .setDescription("How often the tab list and nearby players are checked. 0 checks every tick.")
            .setSection("Delays");

    private final Map<UUID, Long> lastAlert = new HashMap<>();
    private long nextScanAt;
    private String cachedNamesRaw, cachedRanksRaw;
    private Set<String> cachedNames = Set.of();
    private List<Pattern> cachedRanks = List.of();
    private List<String> cachedRankWords = List.of();

    public StaffDetector() {
        super("StaffDetector", "Warns you with a toast and a sound when server staff are online or near you: "
                + "names you list, staff ranks in the tab list, players in spectator or creative, and (optional) "
                + "players hidden from tab who are standing near you. Only reads the tab list; sends nothing.",
                Category.SMP);
        addSetting(staffNames);
        addSetting(checkRanks);
        addSetting(rankKeywords);
        addSetting(checkGamemode);
        addSetting(vanishCheck);
        addSetting(playSound);
        addSetting(cooldown);
        addSetting(scanDelay);
    }

    @Override
    public void onEnable() {
        lastAlert.clear();
        nextScanAt = 0L;
    }

    @Override
    protected void onRuntimeStateReleased() {
        // Called on disable and on world change/disconnect: a new server starts with fresh cooldowns.
        lastAlert.clear();
        nextScanAt = 0L;
    }

    @Override
    public void onTick() {
        if (mc.player == null || mc.world == null) return;
        ClientPlayNetworkHandler handler = mc.getNetworkHandler();
        if (handler == null) return;
        long now = System.currentTimeMillis();
        if (now < nextScanAt) return;
        // Small jitter so scans do not land on an exact period.
        long delay = Math.round(scanDelay.getValue());
        nextScanAt = now + (delay <= 0 ? 0 : delay + (long) (Math.random() * Math.min(250, delay * 0.2)));
        refreshLists();

        UUID self = mc.player.getUuid();
        long cooldownMs = Math.round(cooldown.getValue()) * 1000L;
        for (PlayerListEntry entry : new ArrayList<>(handler.getPlayerList())) {
            if (entry == null || entry.getProfile() == null) continue;
            UUID id = entry.getProfile().id();
            if (id == null || id.equals(self)) continue;
            String reason = staffReason(entry);
            if (reason != null) alert(id, entry.getProfile().name(), reason, now, cooldownMs);
        }

        if (vanishCheck.isEnabled()) {
            for (AbstractClientPlayerEntity player : new ArrayList<>(mc.world.getPlayers())) {
                if (player == mc.player || player.getUuid().equals(self)) continue;
                // Citizens-style NPCs use version 2 UUIDs; real (online and offline mode) players do not.
                if (player.getUuid().version() == 2) continue;
                PlayerListEntry entry = handler.getPlayerListEntry(player.getUuid());
                boolean listed = entry != null && handler.getListedPlayerListEntries().contains(entry);
                if (!listed) {
                    alert(player.getUuid(), player.getGameProfile().name(), "hidden from tab, " +
                            Math.round(player.distanceTo(mc.player)) + "m away (vanished?)", now, cooldownMs);
                }
            }
        }
    }

    /** Why this tab entry looks like staff, or null. */
    private String staffReason(PlayerListEntry entry) {
        String name = entry.getProfile().name();
        if (name != null && cachedNames.contains(name.toLowerCase(Locale.ROOT))) return "on your staff list";
        if (checkGamemode.isEnabled()) {
            GameMode mode = entry.getGameMode();
            if (mode == GameMode.SPECTATOR) return "in spectator mode";
            if (mode == GameMode.CREATIVE) return "in creative mode";
        }
        if (checkRanks.isEnabled() && !cachedRanks.isEmpty()) {
            Text display = entry.getDisplayName();
            StringBuilder shown = new StringBuilder();
            if (display != null) shown.append(Formatting.strip(display.getString()));
            // Many servers put the rank in the scoreboard-team prefix instead of a custom tab name.
            if (entry.getScoreboardTeam() != null) {
                shown.append(' ').append(Formatting.strip(entry.getScoreboardTeam().getPrefix().getString()));
            }
            String text = shown.toString();
            if (!text.isBlank()) {
                for (int i = 0; i < cachedRanks.size(); i++) {
                    if (cachedRanks.get(i).matcher(text).find()) return "rank \"" + cachedRankWords.get(i) + "\"";
                }
            }
        }
        return null;
    }

    private void alert(UUID id, String name, String reason, long now, long cooldownMs) {
        Long last = lastAlert.get(id);
        if (last != null && now - last < cooldownMs) return;
        lastAlert.put(id, now);
        String who = name == null || name.isBlank() ? "A player" : name;
        Notifier.warn("Staff Detector", who + " - " + reason);
        if (playSound.isEnabled() && mc.world != null && mc.player != null) {
            mc.world.playSoundClient(mc.player.getX(), mc.player.getY(), mc.player.getZ(),
                    SoundEvents.BLOCK_NOTE_BLOCK_PLING.value(), SoundCategory.MASTER, 0.9f, 1.6f, false);
        }
    }

    private void refreshLists() {
        String names = staffNames.getValue();
        if (!names.equals(cachedNamesRaw)) {
            cachedNamesRaw = names;
            Set<String> set = new HashSet<>();
            for (String part : names.split("[,;\\s]+")) {
                String trimmed = part.trim();
                if (!trimmed.isEmpty()) set.add(trimmed.toLowerCase(Locale.ROOT));
            }
            cachedNames = Set.copyOf(set);
        }
        String ranks = rankKeywords.getValue();
        if (!ranks.equals(cachedRanksRaw)) {
            cachedRanksRaw = ranks;
            List<Pattern> list = new ArrayList<>();
            List<String> words = new ArrayList<>();
            for (String part : ranks.split(",")) {
                String word = part.trim();
                if (word.isEmpty()) continue;
                list.add(Pattern.compile("(?<![A-Za-z0-9])" + Pattern.quote(word) + "(?![A-Za-z0-9])",
                        Pattern.CASE_INSENSITIVE));
                words.add(word);
            }
            cachedRanks = List.copyOf(list);
            cachedRankWords = List.copyOf(words);
        }
    }
}
