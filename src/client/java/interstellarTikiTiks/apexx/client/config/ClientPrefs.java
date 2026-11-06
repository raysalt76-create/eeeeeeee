package interstellarTikiTiks.apexx.client.config;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import interstellarTikiTiks.apexx.client.cosmetics.CosmeticPrefs;
import interstellarTikiTiks.apexx.client.enemy.EnemyKeybindManager;
import interstellarTikiTiks.apexx.client.friend.FriendKeybindManager;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Per-user client preferences that belong to the player, not to a config: the server -> config
 * auto-load map, the onboarding-tour flag, the last devlog entry seen, named HUD layouts and the
 * Friend/Enemy keys. They ride in the user's own cloud blob under {@link #KEY} and are never applied
 * from anything else (named-config swaps, imports, community configs, share codes), so swapping
 * configs keeps them, exactly like the menu theme. Nothing here touches the disk.
 */
public final class ClientPrefs {
    public static final String KEY = "clientPrefs";
    static final int MAX_SERVERS = 64;
    public static final int MAX_HUD_LAYOUTS = 16;
    static final int MAX_NAME = 32;

    private static final ClientPrefs INSTANCE = new ClientPrefs();

    public static ClientPrefs get() {
        return INSTANCE;
    }

    private final Map<String, String> serverConfigs = new LinkedHashMap<>();
    private final Map<String, JsonObject> hudLayouts = new LinkedHashMap<>();
    private boolean onboardingSeen;
    private String lastSeenDevlog = "";
    /**
     * True once the live values are authoritative: restored from the user's own blob, the cloud slot
     * turned out empty, or the user changed something. Until then a save carries the previously saved
     * prefs over instead of overwriting them with defaults.
     */
    private volatile boolean owned;
    private boolean restoring;
    /** ClickGUI layout: module order, custom categories, Quick Settings pins, last view (under "gui"). */
    private final GuiPrefs gui = new GuiPrefs(this::changed);
    /** Cosmetics tab: what the player wears and the cosmetics options (under "cosmetics"). */
    private final CosmeticPrefs cosmetics = new CosmeticPrefs(this::changed);

    ClientPrefs() {}

    public GuiPrefs gui() {
        return gui;
    }

    public CosmeticPrefs cosmetics() {
        return cosmetics;
    }

    public boolean isOwned() {
        return owned;
    }

    /** The user's cloud slot had no config to restore: the defaults are the real state. */
    public void markSynced() {
        owned = true;
    }

    // ---------------------------------------------------------------- server auto-load

    /** Lower-case host[:port], without the default port or a trailing dot, so "Play.Foo.net:25565" matches "play.foo.net". */
    public static String normalizeAddress(String address) {
        if (address == null) return "";
        String a = address.trim().toLowerCase(Locale.ROOT);
        if (a.endsWith(":25565")) a = a.substring(0, a.length() - 6);
        while (a.endsWith(".")) a = a.substring(0, a.length() - 1);
        return a;
    }

    public synchronized String serverConfigFor(String address) {
        String key = normalizeAddress(address);
        return key.isEmpty() ? null : serverConfigs.get(key);
    }

    /** Map a server to a config; a null/blank config removes the mapping. */
    public void setServerConfig(String address, String configName) {
        String key = normalizeAddress(address);
        if (key.isEmpty()) return;
        synchronized (this) {
            if (configName == null || configName.isBlank()) {
                if (serverConfigs.remove(key) == null) return;
            } else {
                String value = configName.trim();
                if (value.equals(serverConfigs.get(key))) return;
                if (!serverConfigs.containsKey(key) && serverConfigs.size() >= MAX_SERVERS) return;
                serverConfigs.put(key, value);
            }
        }
        changed();
    }

    /** Servers that auto-load {@code configName} (case-insensitive). */
    public synchronized List<String> serversFor(String configName) {
        List<String> out = new ArrayList<>();
        if (configName == null) return out;
        for (Map.Entry<String, String> e : serverConfigs.entrySet()) {
            if (e.getValue().equalsIgnoreCase(configName)) out.add(e.getKey());
        }
        return out;
    }

    /** Keep the server map pointing at a renamed config, or drop it for a deleted one (newName null). */
    public void renameConfig(String oldName, String newName) {
        if (oldName == null) return;
        boolean any = false;
        synchronized (this) {
            var it = serverConfigs.entrySet().iterator();
            while (it.hasNext()) {
                var e = it.next();
                if (!e.getValue().equalsIgnoreCase(oldName)) continue;
                any = true;
                if (newName == null || newName.isBlank()) it.remove();
                else e.setValue(newName.trim());
            }
        }
        if (any) changed();
    }

    // ---------------------------------------------------------------- onboarding / devlog

    public synchronized boolean isOnboardingSeen() {
        return onboardingSeen;
    }

    public void setOnboardingSeen(boolean seen) {
        synchronized (this) {
            if (onboardingSeen == seen) return;
            onboardingSeen = seen;
        }
        changed();
    }

    public synchronized String getLastSeenDevlog() {
        return lastSeenDevlog;
    }

    public void setLastSeenDevlog(String id) {
        String value = id == null ? "" : id;
        synchronized (this) {
            if (value.equals(lastSeenDevlog)) return;
            lastSeenDevlog = value;
        }
        changed();
    }

    // ---------------------------------------------------------------- HUD layouts

    public synchronized List<String> hudLayoutNames() {
        return new ArrayList<>(hudLayouts.keySet());
    }

    /** A copy of the stored layout ({element name: {x, y, enabled, scale}}), or null. */
    public synchronized JsonObject hudLayout(String name) {
        JsonObject layout = name == null ? null : hudLayouts.get(name);
        return layout == null ? null : layout.deepCopy();
    }

    /** Store (or replace) a layout. Returns false when the name is blank or the layout limit is reached. */
    public boolean putHudLayout(String name, JsonObject layout) {
        if (name == null || name.isBlank() || layout == null) return false;
        String key = clip(name.trim());
        synchronized (this) {
            if (!hudLayouts.containsKey(key) && hudLayouts.size() >= MAX_HUD_LAYOUTS) return false;
            hudLayouts.put(key, layout.deepCopy());
        }
        changed();
        return true;
    }

    public void removeHudLayout(String name) {
        synchronized (this) {
            if (name == null || hudLayouts.remove(name) == null) return;
        }
        changed();
    }

    // ---------------------------------------------------------------- friend / enemy keys

    /** Called by the key managers when the user rebinds; restores from the blob do not come through here. */
    public void onKeybindChanged() {
        if (restoring) return;
        changed();
    }

    /** Called by FriendManager/EnemyManager when their lists change; restores do not come through here. */
    public void onListsChanged() {
        if (restoring) return;
        changed();
    }

    // ---------------------------------------------------------------- blob

    public synchronized JsonObject toJson() {
        JsonObject root = new JsonObject();
        JsonObject servers = new JsonObject();
        serverConfigs.forEach(servers::addProperty);
        root.add("servers", servers);
        root.addProperty("onboardingSeen", onboardingSeen);
        root.addProperty("devlogSeen", lastSeenDevlog);
        JsonObject layouts = new JsonObject();
        hudLayouts.forEach((name, layout) -> layouts.add(name, layout.deepCopy()));
        root.add("hudLayouts", layouts);
        root.addProperty("friendKey", FriendKeybindManager.getInstance().getKeyCode());
        root.addProperty("enemyKey", EnemyKeybindManager.getInstance().getKeyCode());
        root.add("friends", interstellarTikiTiks.apexx.client.friend.FriendManager.getInstance().toJson());
        root.add("enemies", interstellarTikiTiks.apexx.client.enemy.EnemyManager.getInstance().toJson());
        root.add("gui", gui.toJson());
        root.add(CosmeticPrefs.KEY, cosmetics.toJson());
        return root;
    }

    /**
     * Restore from the user's own blob. A missing or malformed key keeps the live values; either way
     * the live values are authoritative afterwards.
     */
    public void applyFromBlob(JsonElement element) {
        owned = true;
        if (element == null || !element.isJsonObject()) return;
        JsonObject root = element.getAsJsonObject();
        synchronized (this) {
            restoring = true;
            try {
                JsonElement servers = root.get("servers");
                if (servers != null && servers.isJsonObject()) {
                    serverConfigs.clear();
                    for (Map.Entry<String, JsonElement> e : servers.getAsJsonObject().entrySet()) {
                        if (serverConfigs.size() >= MAX_SERVERS) break;
                        String key = normalizeAddress(e.getKey());
                        String value = string(e.getValue());
                        if (!key.isEmpty() && value != null && !value.isBlank()) serverConfigs.put(key, value.trim());
                    }
                }
                JsonElement seen = root.get("onboardingSeen");
                if (seen != null && seen.isJsonPrimitive()) {
                    try { onboardingSeen = seen.getAsBoolean(); } catch (RuntimeException ignored) { }
                }
                String devlog = string(root.get("devlogSeen"));
                if (devlog != null) lastSeenDevlog = devlog;
                JsonElement layouts = root.get("hudLayouts");
                if (layouts != null && layouts.isJsonObject()) {
                    hudLayouts.clear();
                    for (Map.Entry<String, JsonElement> e : layouts.getAsJsonObject().entrySet()) {
                        if (hudLayouts.size() >= MAX_HUD_LAYOUTS) break;
                        if (e.getValue().isJsonObject() && !e.getKey().isBlank()) {
                            hudLayouts.put(clip(e.getKey()), e.getValue().getAsJsonObject().deepCopy());
                        }
                    }
                }
                Integer friend = integer(root.get("friendKey"));
                if (friend != null) FriendKeybindManager.getInstance().setKeyCode(friend);
                Integer enemy = integer(root.get("enemyKey"));
                if (enemy != null) EnemyKeybindManager.getInstance().setKeyCode(enemy);
                var enemyMgr = interstellarTikiTiks.apexx.client.enemy.EnemyManager.getInstance();
                var friendMgr = interstellarTikiTiks.apexx.client.friend.FriendManager.getInstance();
                enemyMgr.restore(root.get("enemies"));
                friendMgr.restore(root.get("friends"));
                // A player is never both: enemy wins over a stale friend entry.
                for (var en : enemyMgr.getEnemies()) friendMgr.removeFriend(en.getUuid());
                gui.applyJson(root.get("gui"));
                cosmetics.applyJson(root.get(CosmeticPrefs.KEY));
            } finally {
                restoring = false;
            }
        }
    }

    /** Removes {@link #KEY}. Returns true if it was present. */
    public static boolean strip(JsonObject root) {
        return root != null && root.remove(KEY) != null;
    }

    /** The JSON text without {@link #KEY} (unchanged text when it has none or is malformed). */
    public static String stripJson(String json) {
        if (json == null || json.isBlank() || !json.contains("\"" + KEY + "\"")) return json;
        try {
            JsonElement parsed = JsonParser.parseString(json);
            if (!parsed.isJsonObject()) return json;
            JsonObject obj = parsed.getAsJsonObject();
            return strip(obj) ? obj.toString() : json;
        } catch (RuntimeException malformed) {
            return json;
        }
    }

    private void changed() {
        // Deliberately does not mark the prefs owned: a change made before the cloud prefs loaded (tour skip,
        // What's New, friend add) must not make the defaults authoritative and overwrite the saved blob.
        try {
            ConfigManager.getInstance().onClientPrefsChanged();
        } catch (Throwable ignored) {
            // Saved with the next config save instead.
        }
    }

    private static String clip(String name) {
        return name.length() > MAX_NAME ? name.substring(0, MAX_NAME) : name;
    }

    private static String string(JsonElement e) {
        try {
            return e != null && e.isJsonPrimitive() ? e.getAsString() : null;
        } catch (RuntimeException malformed) {
            return null;
        }
    }

    private static Integer integer(JsonElement e) {
        try {
            return e != null && e.isJsonPrimitive() && e.getAsJsonPrimitive().isNumber() ? e.getAsInt() : null;
        } catch (RuntimeException malformed) {
            return null;
        }
    }
}
