package interstellarTikiTiks.apexx.client.module.hooks;

import interstellarTikiTiks.apexx.client.render.visuals.ShaderEspParams;
import net.minecraft.entity.Entity;

/**
 * Payload-to-loader contract for the shader ESP. The module decides which entities are masked and
 * what they look like; the loader's {@code ShaderEspEngine} owns the mask framebuffer and composite.
 */
public interface ShaderEspProvider {
    /** Cheap per-frame gate: false leaves the vanilla entity-outline path completely untouched. */
    boolean shaderEspActive();

    /** True when this entity's full render should be written to the shader mask. */
    boolean shaderEspWantsMask(Entity entity);

    /** 0xRRGGBB tint written into the mask for this entity. */
    int shaderEspMaskColor(Entity entity, long nowMillis);

    /** Fills the composite parameters for this frame. */
    void shaderEspParameters(ShaderEspParams out, long nowMillis);
}
