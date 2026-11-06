package interstellarTikiTiks.apexx.client.util;

import net.minecraft.block.Block;
import net.minecraft.block.BlockState;

import java.util.Set;

/**
 * What the Xray module publishes for the chunk-mesh mixins. They run on the chunk builder threads for
 * every block of a section, so this is a few volatile reads and a set lookup, never a module search.
 *
 * <p>A block is one of three kinds while Xray is on: listed (drawn in full), background (not listed,
 * drawn translucent at {@link #backgroundOpacity()}), or hidden (not listed, opacity 0).</p>
 */
public final class XrayState {

    private static volatile boolean active;
    private static volatile int backgroundOpacity;
    private static volatile Set<Block> visible = Set.of();
    /** The opacity of the block the Sodium builder is on right now, per builder thread; -1 for none. */
    private static final ThreadLocal<int[]> SCOPE = ThreadLocal.withInitial(() -> new int[]{-1});

    private XrayState() {
    }

    /** Called by the Xray module when it is switched on or off or its settings change. */
    public static void publish(boolean on, Set<Block> blocks, int opacity) {
        visible = blocks == null ? Set.of() : Set.copyOf(blocks);
        backgroundOpacity = Math.max(0, Math.min(255, opacity));
        active = on;
    }

    public static boolean isActive() {
        return active;
    }

    /** True while Xray is on and {@code block} is one of the blocks to show. */
    public static boolean isVisible(Block block) {
        return active && visible.contains(block);
    }

    public static int backgroundOpacity() {
        return backgroundOpacity;
    }

    /** True for a block that is drawn translucent: Xray on, opacity above 0, not listed, not air. */
    public static boolean isBackground(BlockState state) {
        return active && backgroundOpacity > 0 && !state.isAir() && !visible.contains(state.getBlock());
    }

    /** Marks the block the Sodium builder starts on: its quads get the background opacity or none. */
    public static void beginBlock(BlockState state) {
        SCOPE.get()[0] = isBackground(state) ? backgroundOpacity : -1;
    }

    public static void endBlock() {
        SCOPE.get()[0] = -1;
    }

    /** {@code argb} (or ABGR: the alpha is the top byte in both) with the scoped block's opacity applied. */
    public static int applyScopeAlpha(int color) {
        int opacity = SCOPE.get()[0];
        if (opacity < 0 || opacity >= 255) return color;
        int alpha = (color >>> 24) * opacity / 255;
        return (alpha << 24) | (color & 0x00FFFFFF);
    }
}
