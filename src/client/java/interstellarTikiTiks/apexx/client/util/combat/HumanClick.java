package interstellarTikiTiks.apexx.client.util.combat;

import interstellarTikiTiks.apexx.client.mixin.MinecraftClientAccessor;
import interstellarTikiTiks.apexx.client.util.ClickSimController;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.Entity;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.EntityHitResult;
import net.minecraft.util.hit.HitResult;

import java.util.concurrent.ThreadLocalRandom;

/**
 * Shared humanization helper for combat/action modules.
 *
 * <p>Delivers hits through the real input pipeline, aimed at making automated combat read like a real player without costing the
 * module its effectiveness:
 *
 * <ul>
 *   <li><b>True clicks.</b> Deliver a hit through the real GLFW input pipeline
 *       ({@link ClickSimController} → {@code SystemInputSimulator.tapAttack}) — the same path a
 *       hardware click takes and the same one that vanilla {@code doAttack} processes — instead of a
 *       synthetic {@code interactionManager.attackEntity()} packet. The packet form is the single
 *       loudest tell an anti-cheat sees: a swing with no matching input event. The true click carries
 *       a genuine input edge and resolves against whatever the crosshair is on, exactly like a person.</li>
 * </ul>
 */
public final class HumanClick {
    private HumanClick() {
    }

    // ── True clicks ──────────────────────────────────────────────────────────────────────────────

    /**
     * Fire a genuine GLFW attack click. Routes through the real mouse callback (via
     * {@link ClickSimController#attack(MinecraftClient, boolean)} with {@code forceSim = true}), so the
     * swing carries a real input edge and vanilla {@code doAttack} resolves it against the current
     * crosshair target on the next input poll — indistinguishable from a hardware click.
     */
    public static void trueAttack(MinecraftClient mc) {
        if (mc == null) {
            return;
        }
        ClickSimController.attack(mc, true);
    }

    /**
     * Deliver an attack on a specific entity in the most human way available.
     *
     * <p>If the crosshair is already on {@code target}, fire a {@link #trueAttack true GLFW click} — the
     * click resolves against the crosshair target, which is exactly {@code target}. Otherwise, and only
     * when {@code allowPacketFallback} is set, fall back to a synchronous {@code attackEntity} packet so a
     * reach-based / fast-dive hit is never dropped just because the humanized aim hasn't fully seated.
     *
     * @return {@code true} if a hit was delivered (either as a true click or a fallback packet),
     *         {@code false} if the crosshair was off-target and no fallback was permitted.
     */
    public static boolean attackEntityHuman(MinecraftClient mc, Entity target, boolean allowPacketFallback) {
        return InteractSim.attack(mc, target, false);
    }

    /**
     * Attack {@code target} with a genuine vanilla swing, requiring physical crosshair intersection
     * and reach validation without packet fallback or crosshair forging.
     */
    public static boolean attackEntityForced(MinecraftClient mc, Entity target) {
        return InteractSim.attack(mc, target, false);
    }
}
