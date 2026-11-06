package interstellarTikiTiks.apexx.client.util.combat;

/**
 * Picks the refill route for AutoTotem from a snapshot of the client state. Pure (no Minecraft types), so
 * the choice is unit tested.
 *
 * <p>Routes, most to least vanilla-like:
 * <ol>
 *   <li>{@link Route#SWAP_KEY}: a hotbar totem and the player's own swap-hands key (offhand goal only).</li>
 *   <li>{@link Route#IN_INVENTORY}: the player's inventory is already open; act there (sim cursor or hover).</li>
 *   <li>{@link Route#OPEN_SIM_CURSOR} / {@link Route#OPEN_VISIBLE}: open the inventory like pressing E.</li>
 *   <li>{@link Route#SILENT}: a window click with no screen open. Blatant; only with the opt-in.</li>
 * </ol>
 */
public final class TotemRoutePlanner {

    public enum Route { NONE, SWAP_KEY, IN_INVENTORY, OPEN_SIM_CURSOR, OPEN_VISIBLE, SILENT }

    /**
     * @param mode             the Refill Route setting: Auto, Swap Key, Sim Cursor, Visible or Silent
     * @param offhandGoal      the totem goes to the offhand (the swap key only reaches the offhand)
     * @param sourceAvailable  a totem exists that can be moved to the goal
     * @param hotbarTotem      a hotbar slot holds a totem usable by the swap key
     * @param swapKeyUsable    swap-hands key bound and the game window focused
     * @param ownInventoryOpen the player's own inventory screen is open
     * @param otherScreenOpen  some other screen (chest, chat, menu) is open
     * @param canOpenInventory the module may open the inventory itself (Manage Inventory)
     * @param simCursorAvailable desktop GUI where the simulated cursor can be drawn
     * @param allowSilent      the Blatant "Allow Silent" opt-in
     * @param silentExhausted  the server kept refusing silent clicks this session
     * @param legitFallback    Fall Back To Visible
     */
    public record State(String mode, boolean offhandGoal, boolean sourceAvailable, boolean hotbarTotem,
                        boolean swapKeyUsable, boolean ownInventoryOpen, boolean otherScreenOpen,
                        boolean canOpenInventory, boolean simCursorAvailable, boolean allowSilent,
                        boolean silentExhausted, boolean legitFallback) {
    }

    private TotemRoutePlanner() {
    }

    public static Route choose(State s) {
        if (s == null || !s.sourceAvailable() || s.otherScreenOpen()) return Route.NONE;
        String mode = s.mode() == null ? "Auto" : s.mode().trim();
        boolean keyRoute = s.offhandGoal() && s.hotbarTotem() && s.swapKeyUsable();
        if (s.ownInventoryOpen()) {
            // Pressing F with no hovered slot does nothing inside a screen, so an open inventory is
            // always worked through the cursor.
            return Route.IN_INVENTORY;
        }
        switch (mode.toLowerCase(java.util.Locale.ROOT)) {
            case "swap key": {
                if (keyRoute) return Route.SWAP_KEY;
                Route silent = silentOrFallback(s);
                if (silent != Route.NONE) return silent;
                return s.canOpenInventory() ? Route.OPEN_VISIBLE : Route.NONE;
            }
            case "silent": {
                Route silent = silentOrFallback(s);
                if (silent != Route.NONE) return silent;
                return s.canOpenInventory() ? Route.OPEN_VISIBLE : Route.NONE;
            }
            case "visible":
                return s.canOpenInventory() ? Route.OPEN_VISIBLE : Route.NONE;
            case "sim cursor":
                if (!s.canOpenInventory()) return Route.NONE;
                return s.simCursorAvailable() ? Route.OPEN_SIM_CURSOR : Route.OPEN_VISIBLE;
            case "auto":
            default: {
                if (keyRoute) return Route.SWAP_KEY;
                if (s.canOpenInventory()) {
                    return s.simCursorAvailable() ? Route.OPEN_SIM_CURSOR : Route.OPEN_VISIBLE;
                }
                return s.allowSilent() && !s.silentExhausted() ? Route.SILENT : Route.NONE;
            }
        }
    }

    /** Silent when opted in and still accepted; after repeated refusals, the visible fallback (or nothing). */
    private static Route silentOrFallback(State s) {
        if (!s.allowSilent()) return Route.NONE;
        if (!s.silentExhausted()) return Route.SILENT;
        return s.legitFallback() ? Route.OPEN_VISIBLE : Route.NONE;
    }
}
