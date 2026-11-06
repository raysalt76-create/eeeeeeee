package interstellarTikiTiks.apexx.client.module.hooks;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.render.RenderTickCounter;

public interface HudRenderAware {

    void onHudRender(DrawContext context, RenderTickCounter tickCounter);
}
