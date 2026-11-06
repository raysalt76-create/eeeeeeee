package interstellarTikiTiks.apexx.client.module.hooks;

import net.minecraft.client.util.math.MatrixStack;

public interface ViewTransformProvider {

    enum Target {
        ITEM_ONLY,
        BOTH
    }

    Target getApplyTarget();

    void applyTransformations(MatrixStack matrices);

    /**
     * Hand-aware variant called by the held-item renderer just before the first-person item is drawn.
     * Defaults to the hand-less transform so existing providers keep their behaviour.
     */
    default void applyTransformations(MatrixStack matrices, net.minecraft.util.Hand hand) {
        applyTransformations(matrices);
    }
}
