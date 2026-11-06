package interstellarTikiTiks.apexx.client.rendering;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import interstellarTikiTiks.apexx.client.shader.CoreShaderManager;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.render.state.TexturedQuadGuiElementRenderState;
import net.minecraft.client.texture.TextureSetup;
import net.minecraft.util.Identifier;
import org.joml.Matrix3x2f;

public class LiquidMetalBackground {

    public void render(DrawContext context, int screenWidth, int screenHeight, float delta) {
        context.fill(0, 0, screenWidth, screenHeight, 0xFF000000);

    }
}
