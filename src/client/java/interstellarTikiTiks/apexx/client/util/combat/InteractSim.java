package interstellarTikiTiks.apexx.client.util.combat;

import interstellarTikiTiks.apexx.client.mixin.MinecraftClientAccessor;
import interstellarTikiTiks.apexx.client.util.AttackValidator;
import interstellarTikiTiks.apexx.client.util.ClickSimController;
import interstellarTikiTiks.apexx.client.util.InputPulseManager;
import interstellarTikiTiks.apexx.client.util.RotationManager;
import interstellarTikiTiks.apexx.client.util.SystemInputSimulator;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.Entity;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.EntityHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

/**
 * One place-and-attack primitive for every module, so the packet-vs-input decision is made
 * once instead of being re-improvised per module.
 *
 * <h2>The bug this replaces</h2>
 * HitCrystal, AutoCrystal and ClickCrystal each carried a private {@code interactBlock} that
 * did all three of these for a single logical placement:
 *
 * <pre>
 *     interactionManager.interactBlock(...);   // a placement packet
 *     player.swingHand(...);                   // an extra HandSwingC2SPacket
 *     SystemInputSimulator.tapUse();           // a SECOND, independent placement
 * </pre>
 *
 * So every "place one crystal" emitted two placements plus a bare swing. The same shape
 * existed on the attack side, where HitCrystal had three separate attack paths in one method
 * and returned {@code true} unconditionally, making its own retry branch unreachable.
 *
 * <p>Block interactions use vanilla's interaction manager after live-ray validation and
 * preserve the four-tick use interval. Aim progression and ownership belong to RotationManager;
 * these dispatch helpers never change the requested or authoritative angles.
 */
public final class InteractSim {

    private InteractSim() {
    }

    /**
     * Interacts with a block.
     *
     * @param silent true to send the interaction directly via packet; false to spoof the
     *               crosshair and trigger vanilla's own doItemUse so it looks identical to a
     *               physical click
     */
    public static boolean place(MinecraftClient mc, BlockHitResult hit, boolean silent) {
        return interactBlock(mc, hit, silent);
    }

    /**
     * One block use for a module that sequences several uses on consecutive ticks (AutoCart's rail,
     * cart, cover). Vanilla's item-use cooldown only throttles a held use key; separate clicks may go
     * out once per tick, so this neither gates on nor re-arms it. The caller supplies a hit it has
     * already validated against the live ray this tick, and owns the one-use-per-tick limit.
     */
    public static boolean interactBlockOncePerTick(MinecraftClient mc, BlockHitResult liveHit, boolean silent) {
        if (mc == null || mc.player == null || mc.world == null || mc.currentScreen != null
                || mc.interactionManager == null || liveHit == null) {
            return false;
        }
        SlotSync.paceAfterSwap();
        ActionResult result = mc.interactionManager.interactBlock(mc.player, Hand.MAIN_HAND, liveHit);
        if (!result.isAccepted()) return false;
        if (result == ActionResult.SUCCESS) {
            mc.player.swingHand(Hand.MAIN_HAND);
        }
        if (!silent) InputPulseManager.recordUsePulse();
        markDispatched();
        return true;
    }

    /** Rule 4: hold the rotation this action went out on for the rest of the tick (owner-scoped). */
    private static void markDispatched() {
        RotationManager.markActionDispatched(interstellarTikiTiks.apexx.client.util.ModuleExecutionContext.currentOwner());
    }

    public static boolean interactBlock(MinecraftClient mc, BlockHitResult hit, boolean silent) {
        if (mc == null || mc.player == null || mc.world == null || mc.currentScreen != null
                || mc.interactionManager == null || hit == null || !useReady(mc)) {
            return false;
        }

        if (silent) {
            // Treat the caller's hit as intent only. Recast against the final rotation and
            // dispatch the live vanilla hit, so a stale or obstructed target cannot be used.
            BlockHitResult liveHit = BlockPlacementValidator.validateHit(mc, hit, true);
            if (liveHit == null) {
                return false;
            }

            SlotSync.paceAfterSwap();
            ActionResult result = mc.interactionManager.interactBlock(mc.player, Hand.MAIN_HAND, liveHit);
            if (result.isAccepted()) {
                if (result == ActionResult.SUCCESS) {
                    mc.player.swingHand(Hand.MAIN_HAND);
                }
                if (mc instanceof MinecraftClientAccessor acc) {
                    acc.setItemUseCooldown(4);
                }
                markDispatched();
                return true;
            }
            return false;
        }

        BlockHitResult liveHit = BlockPlacementValidator.validateHit(mc, hit, false);
        if (liveHit == null) {
            return false;
        }
        SlotSync.paceAfterSwap();
        ActionResult result = mc.interactionManager.interactBlock(mc.player, Hand.MAIN_HAND, liveHit);
        if (result.isAccepted()) {
            if (result == ActionResult.SUCCESS) {
                mc.player.swingHand(Hand.MAIN_HAND);
            }
            if (mc instanceof MinecraftClientAccessor acc) {
                acc.setItemUseCooldown(4);
            }
            InputPulseManager.recordUsePulse();
            markDispatched();
            return true;
        }
        return false;
    }

    /**
     * Attacks an entity.
     *
     * @param silent true to send the interaction directly; false to deliver a real click
     */
    public static boolean attack(MinecraftClient mc, Entity target, boolean silent) {
        if (mc == null || mc.player == null || mc.interactionManager == null || target == null) {
            return false;
        }
        if (silent) {
            if (!AttackValidator.dispatchEntityAttack(mc, target, false, true)) {
                return false;
            }
            if (mc instanceof MinecraftClientAccessor acc) {
                interstellarTikiTiks.apexx.client.util.ExecutionMode.relaxAttackCooldown(acc);
            }
            AttackValidator.emitSilentAttackClick(mc);
            return true;
        }

        // In 100% legit mode, actions execute only when attacking the entity is physically possible:
        // the crosshair physically reaches the target entity hitbox and target is within vanilla reach.
        double maxReach = mc.player != null ? mc.player.getEntityInteractionRange() : (isHoldingSpear(mc.player) ? 4.5 : 3.0);
        Vec3d eye = mc.player != null ? mc.player.getEyePos() : null;
        if (eye != null && target.getBoundingBox().expand(target.getTargetingMargin()).squaredMagnitude(eye) > maxReach * maxReach) {
            return false;
        }
        if (!isCrosshairOn(mc, target)) {
            return false;
        }
        return AttackValidator.dispatchClickAttack(mc);
    }

    private static boolean isHoldingSpear(net.minecraft.entity.player.PlayerEntity player) {
        if (player == null) return false;
        net.minecraft.item.ItemStack stack = player.getMainHandStack();
        if (stack == null || stack.isEmpty()) return false;
        return net.minecraft.registry.Registries.ITEM.getId(stack.getItem()).getPath().contains("spear");
    }

    public static boolean isCrosshairOn(MinecraftClient mc, Entity target) {
        if (mc == null || target == null || mc.player == null) return false;
        if (mc.targetedEntity == target) return true;
        if (mc.crosshairTarget instanceof EntityHitResult ehr && ehr.getEntity() == target) return true;
        Vec3d eye = mc.player.getEyePos();
        Vec3d look = mc.player.getRotationVec(1.0f);
        double range = mc.player.getEntityInteractionRange();
        Vec3d end = eye.add(look.multiply(range));
        Box box = target.getBoundingBox();
        return box.raycast(eye, end).isPresent();
    }

    public static boolean isCrosshairOnBlock(MinecraftClient mc, net.minecraft.util.math.BlockPos pos) {
        if (mc == null || pos == null || mc.player == null) return false;
        if (mc.crosshairTarget instanceof BlockHitResult bhr && bhr.getBlockPos().equals(pos)) {
            return true;
        }
        Vec3d eye = mc.player.getEyePos();
        Vec3d look = mc.player.getRotationVec(1.0f);
        double reach = mc.player.getBlockInteractionRange();
        Vec3d end = eye.add(look.multiply(reach));
        Box box = new Box(pos);
        return box.raycast(eye, end).isPresent();
    }

    /** Exact block face check used before a normal placement click. */
    public static boolean isCrosshairOnHit(MinecraftClient mc, BlockHitResult intended) {
        if (mc == null || intended == null || mc.player == null) return false;
        if (!(mc.crosshairTarget instanceof BlockHitResult actual)
                || actual.getType() != HitResult.Type.BLOCK) {
            return false;
        }
        if (!actual.getBlockPos().equals(intended.getBlockPos()) || actual.getSide() != intended.getSide()) {
            return false;
        }
        double reach = mc.player.getBlockInteractionRange();
        return mc.player.getEyePos().squaredDistanceTo(actual.getPos()) <= reach * reach;
    }

    /**
     * Uses / interacts with the item in hand.
     *
     * @param silent true to submit the aim to RotationManager before the item interaction; false to route through normal input
     */
    public static boolean useItem(MinecraftClient mc, Hand hand, float yaw, float pitch, boolean silent) {
        if (mc == null || mc.player == null || mc.world == null || mc.currentScreen != null
                || mc.interactionManager == null || !useReady(mc)) {
            return false;
        }

        if (silent) {
            float currentYaw = RotationManager.isSilentRotationActive() ? RotationManager.getFinalYaw() : mc.player.getYaw();
            float currentPitch = RotationManager.isSilentRotationActive() ? RotationManager.getFinalPitch() : mc.player.getPitch();
            if (Math.abs(net.minecraft.util.math.MathHelper.wrapDegrees(currentYaw - yaw)) > 1.0f
                    || Math.abs(currentPitch - pitch) > 1.0f) return false;
            SlotSync.paceAfterSwap();
            ActionResult result = mc.interactionManager.interactItem(mc.player, hand);
            if (result.isAccepted()) {
                // Vanilla swings only when the result asks the client to (a CONSUME result does not).
                if (result instanceof ActionResult.Success success
                        && success.swingSource() == ActionResult.SwingSource.CLIENT) {
                    mc.player.swingHand(hand);
                }
                if (mc instanceof MinecraftClientAccessor acc) {
                    acc.setItemUseCooldown(4);
                }
                InputPulseManager.recordUsePulse();
                markDispatched();
                return true;
            }
            return false;
        }

        ClickSimController.use(mc);
        InputPulseManager.recordUsePulse();
        return true;
    }

    /**
     * Whether vanilla currently permits a use. Callers that were relying on
     * {@code setItemUseCooldown(0)} should gate on this instead of deleting the throttle.
     */
    public static boolean useReady(MinecraftClient mc) {
        return mc instanceof MinecraftClientAccessor accessor && accessor.getItemUseCooldown() <= 0;
    }

    /**
     * Sends a bare main-hand item interaction through the vanilla interaction manager, with no rotation gate and no
     * use-cooldown throttle. This is the 1.8 sword-block seam: vanilla {@code interactItem} always sends a use-item
     * packet for a non-spectator (even for a sword, which has no 1.21 use), and on a legacy server reached through
     * ViaVersion that held-sword use is translated into a 1.8 block. Not a raw packet: it is the same interaction
     * manager call the use key makes. Returns false only when no interaction could be attempted.
     */
    public static boolean useMainHandRaw(MinecraftClient mc) {
        if (mc == null || mc.player == null || mc.world == null || mc.currentScreen != null
                || mc.interactionManager == null) {
            return false;
        }
        mc.interactionManager.interactItem(mc.player, Hand.MAIN_HAND);
        return true;
    }
}
