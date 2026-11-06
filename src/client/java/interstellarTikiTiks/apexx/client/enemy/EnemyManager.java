package interstellarTikiTiks.apexx.client.enemy;

import com.google.gson.*;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class EnemyManager {
    private static final EnemyManager INSTANCE = new EnemyManager();
    private final Map<UUID, Enemy> enemies = new ConcurrentHashMap<>();

    private EnemyManager() {
    }

    public static EnemyManager getInstance() { return INSTANCE; }

    public void addEnemy(String name, UUID uuid) {
        if (uuid == null || name == null || name.isEmpty()) return;
        // A player is a friend or an enemy, never both: marking an enemy clears the friend entry.
        interstellarTikiTiks.apexx.client.friend.FriendManager.getInstance().removeFriend(uuid);
        enemies.put(uuid, new Enemy(name, uuid));
        save();
    }

    public void removeEnemy(UUID uuid) {
        if (enemies.remove(uuid) != null) save();
    }

    public void removeEnemy(String name) {
        Enemy toRemove = enemies.values().stream()
                .filter(e -> e.getName().equalsIgnoreCase(name))
                .findFirst().orElse(null);
        if (toRemove != null) {
            enemies.remove(toRemove.getUuid());
            save();
        }
    }

    public boolean isEnemy(UUID uuid) {
        return uuid != null && enemies.containsKey(uuid);
    }

    public List<Enemy> getEnemies() {
        return new ArrayList<>(enemies.values());
    }



    /** Persisted inside the user's cloud prefs blob (see ClientPrefs); nothing is written elsewhere. */
    private void save() {
        interstellarTikiTiks.apexx.client.config.ClientPrefs.get().onListsChanged();
    }

    public JsonArray toJson() {
        JsonArray arr = new JsonArray();
        for (Enemy e : enemies.values()) {
            JsonObject o = new JsonObject();
            o.addProperty("name", e.getName());
            o.addProperty("uuid", e.getUuid().toString());
            o.addProperty("addedTime", e.getAddedTime());
            arr.add(o);
        }
        return arr;
    }

    /** Replace the list from the blob; malformed entries are skipped individually. */
    public void restore(JsonElement element) {
        if (element == null || !element.isJsonArray()) return;
        Map<UUID, Enemy> next = new LinkedHashMap<>();
        for (JsonElement e : element.getAsJsonArray()) {
            try {
                JsonObject o = e.getAsJsonObject();
                String name = o.get("name").getAsString();
                UUID uuid = UUID.fromString(o.get("uuid").getAsString());
                if (name.isEmpty()) continue;
                long added = o.has("addedTime") ? o.get("addedTime").getAsLong() : System.currentTimeMillis();
                next.put(uuid, new Enemy(name, uuid, added));
            } catch (RuntimeException malformed) {
                // skip this entry only
            }
        }
        enemies.clear();
        enemies.putAll(next);
    }
}
