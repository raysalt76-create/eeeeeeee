package interstellarTikiTiks.apexx.client.util.combat;

/**
 * The obsidian hotbar slot the player pressed while an anchor combo was still running. The anchor
 * engine records it instead of dropping the combo; AutoCrystal reads it once the combo has let go of
 * the hotbar, selects the slot, places the obsidian and carries on with crystals. Client thread only.
 */
public final class ObsidianIntent {

    /** Kept this long after the last sign of the combo, so a stuck combo cannot arm it forever. */
    private static final long HOLD_MS = 4000L;

    private static int slot = -1;
    private static long expiresAt;

    private ObsidianIntent() {
    }

    /** The player pressed {@code hotbarSlot} (holding obsidian) while a combo was running. */
    public static void arm(int hotbarSlot) {
        if (hotbarSlot < 0 || hotbarSlot > 8) return;
        slot = hotbarSlot;
        expiresAt = System.currentTimeMillis() + HOLD_MS;
    }

    /** The combo is still running: the intent stays valid. */
    public static void keepAlive() {
        if (slot >= 0) expiresAt = System.currentTimeMillis() + HOLD_MS;
    }

    /** The armed slot, or -1 when nothing is armed or it ran out. */
    public static int slot() {
        if (slot >= 0 && System.currentTimeMillis() > expiresAt) slot = -1;
        return slot;
    }

    public static void clear() {
        slot = -1;
    }
}
