package interstellarTikiTiks.apexx.client.module.hooks;

/**
 * Consulted only for the vanilla mouse-wheel hotbar scroll (no screen open, not spectating).
 * Number keys, module slot swaps and GUI scrolling never reach it.
 */
public interface HotbarScrollBlocker {
    /** Client thread. True drops this wheel step instead of changing the selected hotbar slot. */
    boolean blockHotbarScroll();
}
