package interstellarTikiTiks.apexx.client.module.hooks;

import net.minecraft.client.render.entity.model.EntityModel;
import net.minecraft.client.render.entity.state.LivingEntityRenderState;
import net.minecraft.client.util.math.MatrixStack;

/**
 * Observes a living entity's final model transform at the end of {@code LivingEntityRenderer.render}
 * (camera-relative pose matrix, before the renderer pops it). Render thread, once per rendered
 * living entity per frame: keep it allocation-free and never submit vanilla geometry from here.
 *
 * <p>The model's part angles are only applied lazily when the command queue draws, so an observer that
 * reads {@code ModelPart} transforms must call {@code model.setAngles(state)} first.</p>
 */
public interface EntityPoseAware {
    void onEntityPose(LivingEntityRenderState state, EntityModel<?> model, MatrixStack matrices);
}
