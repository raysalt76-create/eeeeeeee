package interstellarTikiTiks.apexx.client.module.hooks;

import interstellarTikiTiks.apexx.client.cosmetics.CapeDesign;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.render.command.OrderedRenderCommandQueue;
import net.minecraft.client.render.entity.model.PlayerEntityModel;
import net.minecraft.client.render.entity.state.PlayerEntityRenderState;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.entity.Entity;
import net.minecraft.util.Identifier;

import java.util.function.Consumer;

/**
 * Premium cosmetics, implemented by the payload-only Cosmetics module. The loader (Cosmetics tab, player-renderer
 * mixins) only calls through this; when no implementer is loaded (free payload) or the session is not premium,
 * the tab shows a locked state and nothing is drawn on players.
 */
public interface CosmeticsProvider {
    /** True when cosmetics may be used now (premium session). */
    boolean cosmeticsAvailable();

    /** End of the player renderer's render-state update. */
    void onPlayerRenderState(Entity entity, PlayerEntityRenderState state);

    /** From the cape feature: draws hats, wings and the cloth cape. Returns true to skip the vanilla cape. */
    boolean renderPlayerFeatures(MatrixStack matrices, OrderedRenderCommandQueue queue, int light,
                                 PlayerEntityRenderState state, PlayerEntityModel model);

    /** Texture of a cape design (for thumbnails), or null while it is being generated. */
    Identifier capeTexture(CapeDesign design);

    boolean previewAvailable();

    /** Draws the local player in a GUI-unit box, turned {@code yawDeg}. */
    void drawPreview(DrawContext context, int x1, int y1, int x2, int y2, float yawDeg, float tiltDeg);

    /**
     * Draws a Cosmetics tab thumbnail in a GUI-unit box: the local player wearing only {@code selection} (one hat
     * or one pair of wings), framed on the head or, for {@code wings}, on the back.
     */
    default void drawThumbnail(DrawContext context, int x1, int y1, int x2, int y2,
                               interstellarTikiTiks.apexx.client.cosmetics.CosmeticSelection selection, boolean wings) {
    }

    String syncStatus();

    /** Opens the image picker for the cape maker; one callback runs on the client thread unless cancelled. */
    void importCapeImage(Consumer<String> onEncoded, Consumer<String> onError);
}
