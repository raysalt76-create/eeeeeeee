package interstellarTikiTiks.apexx.client.util;

import interstellarTikiTiks.apexx.client.event.EventBus;
import interstellarTikiTiks.apexx.client.event.impl.AttackEvent;
import interstellarTikiTiks.apexx.client.event.impl.CombatSwapLockEvent;
import interstellarTikiTiks.apexx.client.mixin.MinecraftClientAccessor;
import interstellarTikiTiks.apexx.client.util.combat.SlotSync;
import interstellarTikiTiks.apexx.client.util.combat.WireRay;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.Entity;
import net.minecraft.util.hit.EntityHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.item.ItemStack;
import net.minecraft.util.Hand;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.GameMode;
import net.minecraft.component.type.PiercingWeaponComponent;

public class AttackValidator {
    public static boolean tryAttack(MinecraftClient mc) {
        return tryAttack(mc, "combat.attack.generic");
    }

    public static boolean tryAttack(MinecraftClient mc, String actionKey) {
        if (!hasAttackContext(mc)) {
            return false;
        }

        if (mc.attackCooldown > 0) {
            return false;
        }

        if (mc.crosshairTarget == null) {
            return false;
        }

        ItemStack itemStack = mc.player.getStackInHand(Hand.MAIN_HAND);
        if (!itemStack.isEmpty() && !itemStack.isItemEnabled(mc.world.getEnabledFeatures())) {
            return false;
        }

        if (mc.interactionManager.getCurrentGameMode() == GameMode.SPECTATOR) {
            return false;
        }

        if (mc.crosshairTarget.getType() == HitResult.Type.ENTITY) {
            EntityHitResult entityHit = (EntityHitResult) mc.crosshairTarget;
            Entity target = entityHit.getEntity();
            if (!target.isAlive()) {
                return false;
            }
            // Rule 3: the entity under the crosshair must also be on the ray that goes on the wire.
            if (requiresWireRay(isBlatantOwner())
                    && !WireRay.onEntity(mc, target, mc.player.getEntityInteractionRange())) {
                return false;
            }
        }

        AttackEvent event = new AttackEvent();
        EventBus.post(event);
        if (event.isCancelled()) {
            return false;
        }

        CombatSwapLockEvent swapLockEvent = new CombatSwapLockEvent(ModuleExecutionContext.currentOwner());
        EventBus.post(swapLockEvent);
        if (swapLockEvent.isAutoAttackLocked()) {
            return false;
        }

        // Route through the simulator (real GLFW mouse callback) instead of the
        // direct mc.attack() shortcut, so the swing reads as genuine input.
        return dispatchClickAttack(mc);
    }



    public static boolean tryAttackInRange(MinecraftClient mc, Entity target,
                                           double swingRange, String actionKey) {
        if (!hasAttackContext(mc)) {
            return false;
        }
        if (target == null || !target.isAlive()) {
            return false;
        }
        if (mc.attackCooldown > 0) {
            return false;
        }
        if (mc.interactionManager.getCurrentGameMode() == GameMode.SPECTATOR) {
            return false;
        }

        

        

        

        if (requiresWireRay(isBlatantOwner())) {
            // Rule 3 (wire-authoritative): the rotation that goes to the server must put the target's
            // hitbox on the ray within range, not merely within a distance of the eye.
            if (!WireRay.onEntity(mc, target, swingRange)) {
                return false;
            }
        } else {
            Vec3d eyePos = mc.player.getEyePos();
            Box box = target.getBoundingBox();
            double clampedX = MathHelper.clamp(eyePos.x, box.minX, box.maxX);
            double clampedY = MathHelper.clamp(eyePos.y, box.minY, box.maxY);
            double clampedZ = MathHelper.clamp(eyePos.z, box.minZ, box.maxZ);
            if (eyePos.squaredDistanceTo(clampedX, clampedY, clampedZ) > swingRange * swingRange) {
                return false;
            }
        }

        ItemStack itemStack = mc.player.getStackInHand(Hand.MAIN_HAND);
        if (!itemStack.isEmpty() && !itemStack.isItemEnabled(mc.world.getEnabledFeatures())) {
            return false;
        }

        AttackEvent event = new AttackEvent();
        EventBus.post(event);
        if (event.isCancelled()) {
            return false;
        }

        CombatSwapLockEvent swapLockEvent = new CombatSwapLockEvent(ModuleExecutionContext.currentOwner());
        EventBus.post(swapLockEvent);
        if (swapLockEvent.isAutoAttackLocked()) {
            return false;
        }

        // Route through the simulator (real GLFW mouse callback) instead of the
        // direct mc.attack() shortcut, so the swing reads as genuine input.
        return dispatchClickAttack(mc);
    }

    /**
     * Dispatches the normal client {@code doAttack} path. Feature policy such as click simulation
     * and cooldown handling stays with the caller; the returned value is vanilla dispatch feedback,
     * not proof that the server accepted or applied the hit.
     */
    public static boolean invokeVanillaAttack(MinecraftClient mc) {
        if (!hasAttackContext(mc) || !(mc instanceof MinecraftClientAccessor accessor)) {
            return false;
        }
        SlotSync.paceAfterSwap();
        boolean attacked = accessor.attack();
        if (attacked) {
            RotationManager.markActionDispatched(ModuleExecutionContext.currentOwner());
        }
        return attacked;
    }

    /** Emits the module's ordinary GLFW attack input without deciding whether to attack. */
    public static boolean emitAttackClick(MinecraftClient mc) {
        if (!hasAttackContext(mc) || mc.currentScreen != null) {
            return false;
        }
        return !ClickSimController.isEnabled() || SystemInputSimulator.tapAttack();
    }

    /** Emits a silent-path click when the running module's Click Sim toggle is on. */
    public static boolean emitSilentAttackClick(MinecraftClient mc) {
        if (!hasAttackContext(mc) || mc.currentScreen != null) {
            return false;
        }
        return !ClickSimController.isEnabled() || SystemInputSimulator.tapAttackSilent();
    }

    /** Runs the immediate click-simulation path used by CPS-driven modules. */
    public static boolean dispatchImmediateClickAttack(MinecraftClient mc) {
        if (!hasAttackContext(mc) || mc.currentScreen != null) {
            return false;
        }
        ClickSimController.attackNow(mc);
        return true;
    }

    /**
     * Runs the ordinary click-simulation path for a crosshair-validated entity attack.
     * The click controller retains ownership of its timing and global click-simulation settings.
     */
    public static boolean dispatchClickAttack(MinecraftClient mc) {
        if (!hasAttackContext(mc)) {
            return false;
        }
        ClickSimController.attack(mc);
        InputPulseManager.recordAttackPulse();
        return true;
    }

    /**
     * Dispatches an exact-target interaction through Minecraft's interaction manager.
     * This is the shared endpoint for target-bound/silent actions that cannot use the current
     * vanilla crosshair target. Swing timing is explicit so existing module behavior is retained.
     * The result only means that Minecraft's interaction method was invoked.
     *
     * <p>Vanilla 1.9+ ({@code MinecraftClient.doAttack}) sends the attack, then the arm swing: use
     * {@code swingAfter}. {@code swingBefore} is the 1.8 order; Grim's PacketOrderB flags every attack whose
     * swing does not follow it before the next packet. Call {@link #canDispatchEntityAttack} before any
     * hotbar swap that this attack depends on.</p>
     */
    public static boolean dispatchEntityAttack(MinecraftClient mc, Entity target,
                                               boolean swingBefore, boolean swingAfter) {
        if (!entityAttackPreconditions(mc, target, hasAttackContext(mc) ? mc.player.getMainHandStack() : null, false)) {
            return false;
        }

        AttackEvent event = new AttackEvent();
        EventBus.post(event);
        if (event.isCancelled()) {
            return false;
        }
        CombatSwapLockEvent swapLockEvent = new CombatSwapLockEvent(ModuleExecutionContext.currentOwner());
        EventBus.post(swapLockEvent);
        if (swapLockEvent.isAutoAttackLocked()) {
            return false;
        }

        // Rule 2: no-op unless a hotbar swap happened this tick and its 1-3 ms gap has not elapsed.
        SlotSync.paceAfterSwap();
        if (swingBefore) {
            mc.player.swingHand(Hand.MAIN_HAND);
        }
        mc.interactionManager.attackEntity(mc.player, target);
        if (swingAfter) {
            mc.player.swingHand(Hand.MAIN_HAND);
        }
        RotationManager.markActionDispatched(ModuleExecutionContext.currentOwner());
        return true;
    }

    /**
     * Pre-swap check for {@link #dispatchEntityAttack}: true when that dispatch would pass its own gates
     * (context, target, reach / wire ray, cooldown) with {@code weapon} in the main hand, and no other
     * module holds the auto-attack lock. It sends nothing and posts no {@link AttackEvent}.
     *
     * <p>A module that swaps to a weapon and then attacks must ask this BEFORE the hotbar packet leaves.
     * Every gate in {@link #dispatchEntityAttack} runs after the swap; a refusal there leaves a slot
     * change with no attack behind it, and the swap-back that follows is a slot -> slot pair the server
     * never saw a hit between. Nothing a gate reads changes between this check and the dispatch in the
     * same client-thread call (the world, the wire rotation and the cooldown only move on later ticks or
     * frames), so a pass here is a pass there unless an {@link AttackEvent} listener cancels.</p>
     *
     * @param cooldownRelaxed the caller clears the miss cooldown right before dispatching (Legit
     *                        {@link ExecutionMode#relaxAttackCooldown}); a pending cooldown then does not refuse
     */
    public static boolean canDispatchEntityAttack(MinecraftClient mc, Entity target, ItemStack weapon,
                                                  boolean cooldownRelaxed) {
        if (!entityAttackPreconditions(mc, target, weapon, cooldownRelaxed)) {
            return false;
        }
        CombatSwapLockEvent swapLockEvent = new CombatSwapLockEvent(ModuleExecutionContext.currentOwner());
        EventBus.post(swapLockEvent);
        return !swapLockEvent.isAutoAttackLocked();
    }

    /** The gates {@link #dispatchEntityAttack} and {@link #canDispatchEntityAttack} share. */
    private static boolean entityAttackPreconditions(MinecraftClient mc, Entity target, ItemStack weapon,
                                                     boolean cooldownRelaxed) {
        if (!hasAttackContext(mc) || mc.currentScreen != null || target == null
                || !target.isAlive() || !target.isAttackable() || (mc.attackCooldown > 0 && !cooldownRelaxed)
                || mc.interactionManager.getCurrentGameMode() == GameMode.SPECTATOR) {
            return false;
        }
        if (weapon != null && !weapon.isEmpty() && !weapon.isItemEnabled(mc.world.getEnabledFeatures())) {
            return false;
        }
        double reach = mc.player.getEntityInteractionRange();
        if (requiresWireRay(isBlatantOwner())) {
            // Rule 3: dispatch only when the rotation on the wire raycasts into the hitbox within reach.
            // A distance test alone sends attacks the server sees as hitting nothing.
            return WireRay.onEntity(mc, target, reach);
        }
        return target.getBoundingBox().squaredMagnitude(mc.player.getEyePos()) <= reach * reach;
    }

    /** Dispatches Minecraft's dedicated piercing-weapon attack API for spear lunge actions. */
    public static boolean dispatchPiercingWeaponAttack(MinecraftClient mc,
                                                       PiercingWeaponComponent component,
                                                       boolean swingAfter) {
        if (!hasAttackContext(mc) || component == null || mc.currentScreen != null) {
            return false;
        }
        AttackEvent event = new AttackEvent();
        EventBus.post(event);
        if (event.isCancelled()) {
            return false;
        }
        CombatSwapLockEvent swapLockEvent = new CombatSwapLockEvent(ModuleExecutionContext.currentOwner());
        EventBus.post(swapLockEvent);
        if (swapLockEvent.isAutoAttackLocked()) {
            return false;
        }
        SlotSync.paceAfterSwap();
        interstellarTikiTiks.apexx.client.util.combat.TickActionOrder.recordAttack();
        mc.interactionManager.attackWithPiercingWeapon(component);
        if (swingAfter) {
            mc.player.swingHand(Hand.MAIN_HAND);
        }
        RotationManager.markActionDispatched(ModuleExecutionContext.currentOwner());
        return true;
    }

    /**
     * Rule 3 gate decision: every module-owned or manual entity attack is wire-ray gated except one owned
     * by a Blatant-category module (those make no compatibility attempt, by design), which keeps the plain
     * reach test instead.
     */
    static boolean requiresWireRay(boolean blatantOwner) {
        return !blatantOwner;
    }

    private static boolean isBlatantOwner() {
        // A script may declare itself Blatant in its header; the exemption is for built-in modules only.
        return ModuleExecutionContext.currentOwner() instanceof interstellarTikiTiks.apexx.client.module.Module module
                && !(module instanceof interstellarTikiTiks.apexx.client.module.implementation.studio.script.ScriptModule)
                && module.getCategory() == interstellarTikiTiks.apexx.client.module.Category.BLATANT;
    }

    private static boolean hasAttackContext(MinecraftClient mc) {
        return mc != null && mc.player != null && mc.world != null && mc.interactionManager != null;
    }

    public static boolean canAttack(MinecraftClient mc) {
        if (!hasAttackContext(mc)) {
            return false;
        }

        if (mc.attackCooldown > 0) {
            return false;
        }

        if (mc.crosshairTarget == null) {
            return false;
        }

        if (mc.player.isUsingItem()) {
            return false;
        }

        ItemStack itemStack = mc.player.getStackInHand(Hand.MAIN_HAND);
        if (!itemStack.isEmpty() && !itemStack.isItemEnabled(mc.world.getEnabledFeatures())) {
            return false;
        }

        if (mc.interactionManager.getCurrentGameMode() == GameMode.SPECTATOR) {
            return false;
        }

        if (mc.crosshairTarget.getType() == HitResult.Type.ENTITY) {
            EntityHitResult entityHit = (EntityHitResult) mc.crosshairTarget;
            Entity target = entityHit.getEntity();
            if (!target.isAlive()) {
                return false;
            }
        }

        CombatSwapLockEvent swapLockEvent = new CombatSwapLockEvent(ModuleExecutionContext.currentOwner());
        EventBus.post(swapLockEvent);
        if (swapLockEvent.isAutoAttackLocked()) {
            return false;
        }

        return true;
    }
}
