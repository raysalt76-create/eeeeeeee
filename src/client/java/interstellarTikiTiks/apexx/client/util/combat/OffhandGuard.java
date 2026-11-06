package interstellarTikiTiks.apexx.client.util.combat;

/**
 * Keeps other modules from taking a totem out of the offhand while AutoTotem protects it.
 *
 * <p>AutoTotem publishes whether protection is on ({@link #update}); the shared input seams
 * ({@code SystemInputSimulator.tapSwapHands}, {@code SystemInputSimulator.clickSlotNow},
 * {@code TotemInventory}) ask {@link #deniesSwapKey} / {@link #deniesWindowClick} with the current module
 * owner from {@code ModuleExecutionContext}. Actions with no module owner (the player, macros, scripts run
 * by the user) are never blocked, nor is the protecting module itself.
 */
public final class OffhandGuard {
    /** Window slot id of the offhand in the player's own screen handler, and the SWAP button for the offhand. */
    public static final int OFFHAND_HANDLER_SLOT = 45;
    public static final int OFFHAND_SWAP_BUTTON = 40;

    private static volatile Object protector;
    private static volatile boolean active;

    private OffhandGuard() {
    }

    /** Sets whether {@code owner} currently protects the offhand totem. */
    public static void update(Object owner, boolean protect) {
        if (owner == null) return;
        protector = owner;
        active = protect;
    }

    /** Drops protection held by {@code owner} (disable, world change, death). */
    public static void release(Object owner) {
        if (owner != null && owner == protector) {
            active = false;
            protector = null;
        }
    }

    public static boolean isActive() {
        return active;
    }

    /** True when {@code actor} is a module other than the protector while protection is on. */
    public static boolean blocksActor(Object actor) {
        return active && actor != null && actor != protector;
    }

    /**
     * Pure decision for a swap-hands key press: denied when it would put a non-totem main-hand item in place of
     * the offhand totem.
     */
    public static boolean deniesSwapKey(boolean blocksActor, boolean offhandTotem, boolean mainHandTotem) {
        return blocksActor && offhandTotem && !mainHandTotem;
    }

    /**
     * Pure decision for a window click: denied when it is an offhand SWAP (button 40) from a non-totem slot, or
     * any click on the offhand slot of the player's own handler, while a totem sits there.
     */
    public static boolean deniesWindowClick(boolean blocksActor, boolean offhandTotem, int slotId, int button,
                                            boolean swapAction, boolean sourceTotem, boolean playerHandler) {
        if (!blocksActor || !offhandTotem) return false;
        if (swapAction && button == OFFHAND_SWAP_BUTTON) return !sourceTotem;
        return playerHandler && slotId == OFFHAND_HANDLER_SLOT;
    }
}
