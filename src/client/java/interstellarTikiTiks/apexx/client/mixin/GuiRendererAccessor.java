package interstellarTikiTiks.apexx.client.mixin;

import com.mojang.blaze3d.buffers.GpuBufferSlice;
import net.minecraft.client.gui.render.GuiRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * Exposes {@code GuiRenderer.render(GpuBufferSlice)} so Apexx can force an extra flush of whatever is
 * currently queued, before vanilla's own single end-of-frame call. Used to flush the client's own HUD
 * elements into the overlay framebuffer early, before vanilla's hotbar/hunger queue into the same
 * shared render state a moment later in the same {@code InGameHud.render} call.
 */
@Mixin(GuiRenderer.class)
public interface GuiRendererAccessor {
    @Invoker("render")
    void apexx$invokeRender(GpuBufferSlice fog);
}
