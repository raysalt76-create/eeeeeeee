package interstellarTikiTiks.apexx.client.module.hooks;

import net.minecraft.util.math.BlockPos;

/**
 * Notified when the local player places a rail block with their own, real input.
 *
 * <p>Never fired for a rail a module placed itself (the dispatch checks that no module's execution
 * context owns the interaction, via {@code ModuleExecutionContext.currentOwner()}), so a listener never
 * reacts to its own placement or another module's.
 */
public interface RailPlacedAware {

    void onRailPlaced(BlockPos pos);
}
