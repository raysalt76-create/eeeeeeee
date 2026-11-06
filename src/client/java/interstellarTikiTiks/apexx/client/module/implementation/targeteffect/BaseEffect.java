package interstellarTikiTiks.apexx.client.module.implementation.targeteffect;

import interstellarTikiTiks.apexx.client.module.implementation.targeteffect.EffectRenderContext;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.util.math.MatrixStack;

public abstract class BaseEffect {

    public abstract void render(MatrixStack matrices, VertexConsumer mainBuffer,
                                VertexConsumer glowBuffer, EffectRenderContext ctx);

    public void renderTextured(MatrixStack matrices, VertexConsumer mainBuffer,
                               VertexConsumer glowBuffer, EffectRenderContext ctx) {

        render(matrices, mainBuffer, glowBuffer, ctx);
    }
}
