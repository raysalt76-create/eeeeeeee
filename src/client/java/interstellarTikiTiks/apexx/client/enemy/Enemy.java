package interstellarTikiTiks.apexx.client.enemy;

import java.util.UUID;

public class Enemy {
    private final String name;
    private final UUID uuid;
    private final long addedTime;

    public Enemy(String name, UUID uuid) {
        this.name = name;
        this.uuid = uuid;
        this.addedTime = System.currentTimeMillis();
    }

    public Enemy(String name, UUID uuid, long addedTime) {
        this.name = name;
        this.uuid = uuid;
        this.addedTime = addedTime;
    }

    public String getName() { return name; }
    public UUID getUuid() { return uuid; }
    public long getAddedTime() { return addedTime; }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (!(obj instanceof Enemy)) return false;
        return uuid.equals(((Enemy) obj).uuid);
    }

    @Override
    public int hashCode() { return uuid.hashCode(); }
}
