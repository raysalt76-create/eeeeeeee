package interstellarTikiTiks.apexx.client.module.implementation.smp;

import interstellarTikiTiks.apexx.client.util.render.RenderUtils;
import interstellarTikiTiks.apexx.client.util.render.types.ShaderRenderLayers;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import org.joml.Matrix4f;

import java.awt.Color;

/** Shared drawing for the SMP ESP modules: see-through boxes and tracers in camera-relative space. */
final class SmpRender {

    static final String OUTLINE = "Outline";
    static final String FILLED = "Filled";
    static final String BOTH = "Both";

    private SmpRender() {
    }

    /** A world-space box drawn through walls, in the camera-relative space the world render hook uses. */
    static void box(MatrixStack matrices, VertexConsumerProvider consumers, Box world, Vec3d camera, Color color,
                    String style, float fillAlpha) {
        Box local = world.offset(-camera.x, -camera.y, -camera.z);
        if (!OUTLINE.equals(style)) {
            RenderUtils.drawBoxEsp(matrices, consumers, local, color, fillAlpha, true);
        }
        if (!FILLED.equals(style)) {
            RenderUtils.drawBoxOutlineEsp(matrices, consumers, local, color, 0.95f, true);
        }
    }

    /** A line from just in front of the camera to {@code target}, through walls. */
    static void tracer(MatrixStack matrices, VertexConsumerProvider consumers, Vec3d camera, Vec3d target,
                       Color color, float tickDelta) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null) return;
        Vec3d look = mc.player.getRotationVec(tickDelta);
        Vec3d from = look.multiply(0.4);
        Vec3d to = target.subtract(camera);
        VertexConsumer buffer = consumers.getBuffer(ShaderRenderLayers.getEspLineLayer(true));
        Matrix4f matrix = matrices.peek().getPositionMatrix();
        float r = color.getRed() / 255f;
        float g = color.getGreen() / 255f;
        float b = color.getBlue() / 255f;
        buffer.vertex(matrix, (float) from.x, (float) from.y, (float) from.z).color(r, g, b, 0.9f);
        buffer.vertex(matrix, (float) to.x, (float) to.y, (float) to.z).color(r, g, b, 0.9f);
    }
}
