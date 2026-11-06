package interstellarTikiTiks.apexx.client.module.hooks;

import net.minecraft.util.hit.EntityHitResult;

/** Lets a melee module (TriggerBot) hit from spear reach while a sword is the held item. */
public interface SpearReachProvider {

    /** The reach a hit can have right now by swapping to a hotbar spear, or 0 when no swap is possible. */
    double spearReach();

    /**
     * Swaps to the spear, attacks the entity on {@code hit} and queues the swap back to the held slot.
     * Returns true when the attack was dispatched; false leaves everything untouched.
     */
    boolean attackFromSpearReach(EntityHitResult hit);
}
