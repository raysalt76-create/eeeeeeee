package interstellarTikiTiks.apexx.client.module.hooks;

import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.item.ItemStack;
import net.minecraft.util.Hand;

public interface HeldItemAnimationProvider {

    double getSwingSpeed();

    boolean isInstantEquipEnabled();

    boolean onRenderFirstPerson(MatrixStack matrices, float swingProgress, ItemStack itemStack, Hand hand);

    boolean applyCustomSwingOffset(MatrixStack matrices, net.minecraft.util.Arm arm, float swingProgress);

    /**
     * Whether {@link #getSwingSpeed()} drives the local swing duration (and allows re-swinging).
     * Providers that only change how the swing looks return false so they never override another
     * provider's swing speed or vanilla's.
     */
    default boolean controlsSwingDuration() {
        return true;
    }
}
