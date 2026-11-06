package interstellarTikiTiks.apexx.client.util;

import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.player.PlayerEntity;
import java.util.Comparator;

public class EntityUtil {

    public static final EntityUtil INSTANCE = new EntityUtil();
    private static final MinecraftClient mc = MinecraftClient.getInstance();

    public PlayerEntity getPlayer() {
        if (mc.world == null || mc.player == null) {
            return null;
        }

        return mc.world.getPlayers().stream()
                .filter(player -> player != mc.player)
                .min(Comparator.comparing(player -> mc.player.distanceTo(player)))
                .orElse(null);
    }
}
