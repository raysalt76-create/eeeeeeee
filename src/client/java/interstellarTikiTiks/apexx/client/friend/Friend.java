package interstellarTikiTiks.apexx.client.friend;

import java.util.UUID;

public class Friend {
    private final String name;
    private final UUID uuid;
    private long addedTime;
    private String note;

    public Friend(String name, UUID uuid) {
        this.name = name;
        this.uuid = uuid;
        this.addedTime = System.currentTimeMillis();
        this.note = "";
    }

    public Friend(String name, UUID uuid, long addedTime, String note) {
        this.name = name;
        this.uuid = uuid;
        this.addedTime = addedTime;
        this.note = note != null ? note : "";
    }

    public String getName() {
        return name;
    }

    public UUID getUuid() {
        return uuid;
    }

    public long getAddedTime() {
        return addedTime;
    }

    public String getNote() {
        return note;
    }

    public void setNote(String note) {
        this.note = note != null ? note : "";
    }

    public String getDisplayName() {
        return note.isEmpty() ? name : note + " (" + name + ")";
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (!(obj instanceof Friend)) return false;
        Friend other = (Friend) obj;
        return uuid.equals(other.uuid);
    }

    @Override
    public int hashCode() {
        return uuid.hashCode();
    }
}
