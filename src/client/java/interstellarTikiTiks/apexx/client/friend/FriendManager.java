package interstellarTikiTiks.apexx.client.friend;

import com.google.gson.*;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class FriendManager {
    private static final FriendManager INSTANCE = new FriendManager();
    private final Map<UUID, Friend> friends = new ConcurrentHashMap<>();

    private FriendManager() {
    }

    public static FriendManager getInstance() {
        return INSTANCE;
    }

    public void addFriend(String name, UUID uuid) {

        if (uuid == null) {

            return;
        }
        if (name == null || name.isEmpty()) {

            return;
        }

        // A player is a friend or an enemy, never both: friending someone clears the enemy entry.
        interstellarTikiTiks.apexx.client.enemy.EnemyManager.getInstance().removeEnemy(uuid);

        Friend friend = new Friend(name, uuid);
        friends.put(uuid, friend);

        save();

    }

    public void removeFriend(UUID uuid) {
        if (friends.remove(uuid) != null) {
            save();
        }
    }

    public void removeFriend(String name) {
        Friend toRemove = friends.values().stream()
                .filter(f -> f.getName().equalsIgnoreCase(name))
                .findFirst()
                .orElse(null);
        if (toRemove != null) {
            friends.remove(toRemove.getUuid());
            save();
        }
    }

    // Enemy wins over a stale friend entry, so a conflicting saved list can never shield a target.
    public boolean isFriend(UUID uuid) {
        return uuid != null && friends.containsKey(uuid)
                && !interstellarTikiTiks.apexx.client.enemy.EnemyManager.getInstance().isEnemy(uuid);
    }

    public boolean isFriend(String name) {
        if (name == null || name.isEmpty()) return false;
        return friends.values().stream()
                .anyMatch(f -> f.getName().equalsIgnoreCase(name)
                        && !interstellarTikiTiks.apexx.client.enemy.EnemyManager.getInstance().isEnemy(f.getUuid()));
    }

    public Friend getFriend(UUID uuid) {
        return friends.get(uuid);
    }

    public Friend getFriend(String name) {
        if (name == null || name.isEmpty()) return null;
        return friends.values().stream()
                .filter(f -> f.getName().equalsIgnoreCase(name))
                .findFirst()
                .orElse(null);
    }

    public List<Friend> getFriends() {
        return new ArrayList<>(friends.values());
    }

    public void clearFriends() {
        friends.clear();
        save();
    }

    public void updateFriendNote(UUID uuid, String note) {
        Friend friend = friends.get(uuid);
        if (friend != null) {
            friend.setNote(note);
            save();
        }
    }



    /** Persisted inside the user's cloud prefs blob (see ClientPrefs); nothing is written elsewhere. */
    private void save() {
        interstellarTikiTiks.apexx.client.config.ClientPrefs.get().onListsChanged();
    }

    public JsonArray toJson() {
        JsonArray arr = new JsonArray();
        for (Friend friend : friends.values()) {
            JsonObject o = new JsonObject();
            o.addProperty("name", friend.getName());
            o.addProperty("uuid", friend.getUuid().toString());
            o.addProperty("addedTime", friend.getAddedTime());
            o.addProperty("note", friend.getNote());
            arr.add(o);
        }
        return arr;
    }

    /** Replace the list from the blob; malformed entries are skipped individually. */
    public void restore(JsonElement element) {
        if (element == null || !element.isJsonArray()) return;
        Map<UUID, Friend> next = new LinkedHashMap<>();
        for (JsonElement e : element.getAsJsonArray()) {
            try {
                JsonObject o = e.getAsJsonObject();
                String name = o.get("name").getAsString();
                UUID uuid = UUID.fromString(o.get("uuid").getAsString());
                if (name.isEmpty()) continue;
                long added = o.has("addedTime") ? o.get("addedTime").getAsLong() : System.currentTimeMillis();
                String note = o.has("note") && !o.get("note").isJsonNull() ? o.get("note").getAsString() : "";
                next.put(uuid, new Friend(name, uuid, added, note));
            } catch (RuntimeException malformed) {
                // skip this entry only
            }
        }
        friends.clear();
        friends.putAll(next);
    }

    /** Single uuid-or-name friend check for any entity (enemy wins). Callers should use this. */
    public boolean isFriend(net.minecraft.entity.Entity entity) {
        if (!(entity instanceof net.minecraft.entity.player.PlayerEntity p)) return false;
        return isFriend(p.getUuid()) || isFriend(p.getGameProfile().name());
    }
}
