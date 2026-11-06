package interstellarTikiTiks.apexx.client.gui.gpuui.render;

import interstellarTikiTiks.apexx.client.gui.gpuui.framework.UiMaterial;
import interstellarTikiTiks.apexx.client.gui.gpuui.render.effects.GpuUiEffectPasses;
import com.mojang.blaze3d.textures.GpuTextureView;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.texture.AbstractTexture;
import net.minecraft.client.texture.TextureSetup;

/** Renders custom fragment materials to a GPU target for ordered UI composition. */
public final class GpuUiMaterialRenderer {
    private static final long START_NANOS = System.nanoTime();
    private static long lastNanos = START_NANOS;

    private GpuUiMaterialRenderer() { }

    public static TextureSetup prepare(UiMaterial material, int pixelWidth, int pixelHeight) {
        if (material == null || pixelWidth <= 0 || pixelHeight <= 0) return null;
        // A material whose shader failed to compile on this driver is skipped (the panel underneath
        // stays a flat fill) instead of compositing an uninitialised target.
        if (!interstellarTikiTiks.apexx.client.util.render.SafePipelines.usable(
                GpuUiEffectPasses.materialPipeline(material.shader(), material.texture() != null))) return null;
        var target = GpuUiEffectPasses.createBucketedTarget(pixelWidth, pixelHeight);
        if (target == null) return null;

        GpuTextureView input = null;
        if (material.texture() != null) {
            AbstractTexture source = MinecraftClient.getInstance().getTextureManager().getTexture(material.texture());
            input = source.getGlTextureView();
            if (input == null) return null;
        }

        long now = System.nanoTime();
        float timeSeconds = (now - START_NANOS) * 1.0e-9f;
        float deltaSeconds = Math.max(0.0f, Math.min(0.1f, (now - lastNanos) * 1.0e-9f));
        lastNanos = now;
        float[][] parameters = new float[15][4];
        for (int slot = 0; slot < 15; slot++) {
            for (int component = 0; component < 4; component++) {
                parameters[slot][component] = material.parameter(slot * 4 + component);
            }
        }
        var uniforms = GpuUiEffectPasses.writeParameters(pixelWidth, pixelHeight,
                timeSeconds, deltaSeconds, parameters);
        GpuUiEffectPasses.queueFullscreen(
                GpuUiEffectPasses.materialPipeline(material.shader(), input != null), input, target, uniforms);
        return GpuUiEffectPasses.texture(target);
    }
}
