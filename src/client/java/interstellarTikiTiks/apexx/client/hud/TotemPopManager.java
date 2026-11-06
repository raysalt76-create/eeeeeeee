package interstellarTikiTiks.apexx.client.hud;

import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class TotemPopManager {
    private static final TotemPopManager INSTANCE = new TotemPopManager();
    private final Map<UUID, Integer> popCounts = new ConcurrentHashMap<>();

    private TotemPopManager() {}

    public static TotemPopManager getInstance() {
        return INSTANCE;
    }

    /** World the counts belong to; a new world (server switch, dimension change, rejoin) starts fresh. */
    private volatile Object countsWorld;

    public void onTotemPop(PlayerEntity player) {
        if (player == null) return;
        syncWorld();
        popCounts.merge(player.getUuid(), 1, Integer::sum);
    }

    public int getPopCount(Entity entity) {
        if (!(entity instanceof PlayerEntity)) {
            return 0;
        }
        syncWorld();
        return popCounts.getOrDefault(entity.getUuid(), 0);
    }

    private void syncWorld() {
        Object world = MinecraftClient.getInstance().world;
        if (world != countsWorld) {
            countsWorld = world;
            popCounts.clear();
        }
    }

    public void resetPopCount(Entity entity) {
        if (entity != null) {
            popCounts.remove(entity.getUuid());
        }
    }

    public void clear() {
        popCounts.clear();
    }
}
