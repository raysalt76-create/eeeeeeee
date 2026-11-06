package interstellarTikiTiks.apexx.client.hud;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;

/**
 * "In combat" for the HUD's per-widget Only In Combat option: the local player attacked something
 * (vanilla attack callback) or was hurt (hurt animation started) within the widget's timeout.
 */
public final class CombatState {
    private static volatile long lastAttackMs;
    private static volatile long lastHurtMs;
    private static int lastHurtTime;

    private CombatState() { }

    /** Called from the vanilla attack seam (client thread). */
    public static void onLocalAttack() {
        lastAttackMs = System.currentTimeMillis();
    }

    /** Client thread, once per tick. */
    public static void tick(MinecraftClient client) {
        ClientPlayerEntity player = client == null ? null : client.player;
        if (player == null) {
            lastHurtTime = 0;
            return;
        }
        int hurt = player.hurtTime;
        // A fresh hit restarts hurtTime at its maximum; rising (or first non-zero) marks a new hurt.
        if (hurt > 0 && hurt > lastHurtTime) lastHurtMs = System.currentTimeMillis();
        lastHurtTime = hurt;
    }

    public static boolean inCombat(long windowMs) {
        long last = Math.max(lastAttackMs, lastHurtMs);
        return last > 0L && System.currentTimeMillis() - last <= Math.max(0L, windowMs);
    }

    public static void reset() {
        lastAttackMs = 0L;
        lastHurtMs = 0L;
        lastHurtTime = 0;
    }
}
