package interstellarTikiTiks.apexx.client.module.implementation.combat;

import interstellarTikiTiks.apexx.client.event.EventBus;
import interstellarTikiTiks.apexx.client.event.EventTarget;
import interstellarTikiTiks.apexx.client.event.impl.CombatSwapLockEvent;
import interstellarTikiTiks.apexx.client.event.impl.ShieldDrainEvent;
import interstellarTikiTiks.apexx.client.friend.FriendManager;
import interstellarTikiTiks.apexx.client.mixin.MinecraftClientAccessor;
import interstellarTikiTiks.apexx.client.module.BooleanSetting;
import interstellarTikiTiks.apexx.client.module.Category;
import interstellarTikiTiks.apexx.client.module.ModeSetting;
import interstellarTikiTiks.apexx.client.module.Module;
import interstellarTikiTiks.apexx.client.module.SliderSetting;
import interstellarTikiTiks.apexx.client.module.SubCategory;
import interstellarTikiTiks.apexx.client.util.AttackValidator;
import interstellarTikiTiks.apexx.client.util.ModuleExecutionContext;
import interstellarTikiTiks.apexx.client.util.RotationManager;
import interstellarTikiTiks.apexx.client.util.combat.CombatItemUtil;
import interstellarTikiTiks.apexx.client.util.combat.SlotSync;
import interstellarTikiTiks.apexx.client.util.combat.ModuleDelays;
import interstellarTikiTiks.apexx.client.util.combat.TickActionOrder;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.util.hit.EntityHitResult;
import net.minecraft.util.hit.HitResult;
import org.lwjgl.glfw.GLFW;

import java.util.concurrent.ThreadLocalRandom;

/**
 * MaceShieldDrain — Hold keybind while falling to obliterate enemy shield durability with high-CPS mace attacks.
 *
 * <p>Uses the event pipeline, SlotSync state management, and hook interfaces.
 *
 * <p><b>Explicit exception to the vanilla-cadence rules</b>, approved by the project owner and shown
 * in the ClickGUI with a BLATANT tag ({@link #isBlatantFlagged()}). Every attack in both modes goes
 * through Minecraft's own entity-attack call ({@link AttackValidator#dispatchEntityAttack}); this
 * module never uses ClickSim/GLFW input and owns no rotation: AutoMace (or the player) does the
 * aiming. Like Rappture's one-tap, it only clicks once the live ray from the current authoritative
 * angles reaches the target; the click itself is vanilla {@code doAttack}
 * ({@code MinecraftClientAccessor.attack()}), so the crosshair decides what is hit.
 */
public class MaceShieldDrain extends Module implements interstellarTikiTiks.apexx.client.module.hooks.ClickSimulating {

    // Legit is listed first so it is the default; Fast stays available as the explicitly blatant choice.
    private final ModeSetting mode = new ModeSetting("Mode", "Legit", "Fast", "Legit")
            .setDescription("Click rate while draining. Legit: up to 30 clicks per second, still far above a human's usual rate. Fast (blatant): bursts of up to 1000 clicks per second that any server checking click rate will flag. Both use Minecraft's own attack call.");
    private final SliderSetting range = new SliderSetting("Range", 4.0, 1.0, 6.0, 1)
            .setDescription("Farthest blocking opponent, in blocks, that gets drained. Anything above vanilla's 3.0 is extended reach.");
    private final SliderSetting minCps = new SliderSetting("Min CPS", 900.0, 100.0, 1000.0, 0)
            .setDescription("Lowest clicks per second used in Fast mode; each burst picks a rate between Min and Max CPS.")
            .setDependsOn("Mode:Fast");
    private final SliderSetting maxCps = new SliderSetting("Max CPS", 975.0, 100.0, 1000.0, 0)
            .setDescription("Highest clicks per second used in Fast mode.")
            .setDependsOn("Mode:Fast");
    private final SliderSetting legitMinCps = new SliderSetting("Legit Min CPS", 16.0, 1.0, 30.0, 0)
            .setDescription("Lowest clicks per second used in Legit mode; each burst picks a rate between Legit Min and Legit Max CPS.")
            .setDependsOn("Mode:Legit");
    private final SliderSetting legitMaxCps = new SliderSetting("Legit Max CPS", 22.0, 1.0, 30.0, 0)
            .setDescription("Highest clicks per second used in Legit mode (capped at 30).")
            .setDependsOn("Mode:Legit");
    private final BooleanSetting requireFalling = new BooleanSetting("Require Falling", true)
            .setDescription("Only drain while you are falling toward the blocking target, i.e. as the opener of a mace dive.");
    private final SliderSetting minFallDist = new SliderSetting("Min Fall Dist", 2.0, 0.0, 20.0, 1)
            .setDescription("How far you must already have fallen, in blocks, before draining starts.")
            .setDependsOn("Require Falling");
    private final BooleanSetting autoSwitch = new BooleanSetting("Auto Switch", true)
            .setDescription("Switch to the mace in your hotbar automatically when draining starts, and back to your previous item afterwards.");
    private final BooleanSetting ignoreFriends = new BooleanSetting("Ignore Friends", true)
            .setDescription("Never drain the shield of a player on your friends list.");
    private final BooleanSetting noStunSlam = new BooleanSetting("No StunSlam", false)
            .setDescription("Pause AutoMace's stun slam while a drain is running so the two do not fight over the mace and the shield.");

    private final SliderSetting swapDelay = new SliderSetting("Swap Delay (ms)", 25, 0, 200, 0).withJitter(8)
            .setSection("Delays")
            .setDescription("Wait after switching to the mace before the first hit, in milliseconds.");
    private final SliderSetting swapBackDelay = new SliderSetting("Swap Back Delay (ms)", 60, 0, 500, 0).withJitter(15)
            .setSection("Delays")
            .setDescription("Wait after the drain stops before your previous hotbar item is reselected, in milliseconds.");

    private int previousSlot = -1;
    private long lastNanoTime = 0L;
    private double clickAccumulator = 0.0;
    private boolean isDraining = false;
    private long clickReadyAtMs = 0L;
    private long restoreAtMs = -1L;

    public MaceShieldDrain() {
        // BLATANT, not COMBAT: at its own defaults this fires up to 100 ATTACK packets in a single
        // tick (900-975 CPS) at 4-6 blocks, and findTarget() picks the closest blocking player with
        // no crosshair, no raycast, no line-of-sight and no FOV check. That is four independent
        // physical impossibilities stacked in one module -- no amount of timing humanisation makes
        // it subtle, so it belongs where it is obvious.
        super("MaceShieldDrain", "Blatant: hold the keybind while falling onto a shielding opponent and it spams mace hits into their shield until it breaks, with extended reach and a very high click rate. It only clicks once your aim (yours or AutoMace's) is on the target. Defaults to the slower Legit rate and is off by default; this is obvious to any server checking click rate or reach.", Category.BLATANT);
        setSubCategory(SubCategory.MACE);
        addSetting(mode);
        addSetting(range);
        addSetting(minCps);
        addSetting(maxCps);
        addSetting(legitMinCps);
        addSetting(legitMaxCps);
        addSetting(requireFalling);
        addSetting(minFallDist);
        addSetting(autoSwitch);
        addSetting(ignoreFriends);
        addSetting(noStunSlam);
        addSetting(swapDelay);
        addSetting(swapBackDelay);
    }

    @Override
    public boolean isBlatantFlagged() {
        return true;
    }

    @Override
    public void onEnable() {
        EventBus.register(this);
        resetState();
    }

    @Override
    public void onDisable() {
        EventBus.unregister(this);
        recoverSlot();
        resetState();
    }

    @Override
    protected void onRuntimeStateReleased() {
        TickActionOrder.cancelDeferredSlot();
        recoverSlot();
        resetState();
        SlotSync.releaseOwner(this);
    }

    @Override
    public void onTick() {
        processDrain();
    }

    @EventTarget
    public void onShieldDrain(ShieldDrainEvent event) {
        if (isEnabled() && isDraining && noStunSlam.isEnabled()) {
            event.setActive(true);
        }
    }

    @EventTarget
    public void onCombatSwapLock(CombatSwapLockEvent event) {
        // Our own attacks post this event through AttackValidator; never lock ourselves out.
        if (event.isRequestedBy(this)) return;
        if (isEnabled() && isDraining) {
            event.setItemSwapLocked(true);
            event.setAutoAttackLocked(true);
        }
    }

    private void processDrain() {
        long nowMs = System.currentTimeMillis();
        // A swap-back scheduled when the last drain ended fires once its delay has passed.
        if (restoreAtMs >= 0L && !isDraining && nowMs >= restoreAtMs) {
            restoreAtMs = -1L;
            recoverSlot();
        }
        if (mc.player == null || mc.world == null || mc.currentScreen != null) {
            stopDraining();
            return;
        }

        // 1. Keybind / Hold check
        if (!isTriggerHeld()) {
            stopDraining();
            return;
        }

        // 2. Falling condition
        if (requireFalling.isEnabled()) {
            if (mc.player.getVelocity().y >= 0.0 || mc.player.fallDistance < minFallDist.getValue()) {
                stopDraining();
                return;
            }
        }

        // 3. Find valid blocking target within range
        PlayerEntity target = findTarget(range.getValue());
        if (target == null) {
            stopDraining();
            return;
        }

        // 4. Auto-switch to Mace
        if (autoSwitch.isEnabled()) {
            int maceSlot = findMaceSlot();
            if (maceSlot == -1) {
                stopDraining();
                return;
            }
            if (mc.player.getInventory().getSelectedSlot() != maceSlot) {
                if (!TickActionOrder.canSwitchSlot()) {
                    return;
                }
                if (previousSlot == -1) {
                    previousSlot = mc.player.getInventory().getSelectedSlot();
                }
                SlotSync.select(mc, maceSlot);
                clickReadyAtMs = nowMs + ModuleDelays.clampMs(swapDelay.sample());
            }
        }

        isDraining = true;
        restoreAtMs = -1L;
        double reach = range.getValue();
        if (nowMs < clickReadyAtMs) {
            return;
        }

        // 6. Click only when the live ray from the current authoritative angles reaches the target.
        if (!rayReaches(target, reach)) {
            return;
        }

        // 7. High-CPS nanosecond delta-time accumulator
        double targetCps = getTargetCps();
        int clicksToPerform = calculateClicks(targetCps);
        if (clicksToPerform <= 0 || !allowAttack()) {
            return;
        }

        boolean attacked = false;
        for (int i = 0; i < clicksToPerform; i++) {
            attacked |= performAttack(reach);
        }
        if (attacked) {
            TickActionOrder.recordAttack();
        }
    }

    /**
     * Live ray check against whatever aim is active (AutoMace's silent rotation, or the real view): the
     * ray must hit the target within {@code reach}. Without an active silent rotation, vanilla's crosshair
     * target is what {@code doAttack} uses, so it must match too.
     */
    private boolean rayReaches(PlayerEntity target, double reach) {
        HitResult ray = RotationManager.getCrosshairTarget(RotationManager.getFinalYaw(),
                RotationManager.getFinalPitch(), reach);
        if (!(ray instanceof EntityHitResult hit) || hit.getEntity() != target) {
            return false;
        }
        if (RotationManager.isSilentRotationActive()) {
            return true;
        }
        return mc.crosshairTarget instanceof EntityHitResult vanilla && vanilla.getEntity() == target;
    }

    private boolean allowAttack() {
        if (mc.player == null || mc.world == null || mc.interactionManager == null) return false;
        return ModuleExecutionContext.call(this, () -> {
            CombatSwapLockEvent lock = new CombatSwapLockEvent(this);
            EventBus.post(lock);
            return !lock.isAutoAttackLocked();
        });
    }

    private boolean isTriggerHeld() {
        if (isHoldMode() && getKeyCode() != -1 && mc.getWindow() != null) {
            long handle = mc.getWindow().getHandle();
            int key = getKeyCode();
            if (key >= GLFW.GLFW_KEY_SPACE && key <= GLFW.GLFW_KEY_LAST) {
                return GLFW.glfwGetKey(handle, key) == GLFW.GLFW_PRESS;
            } else if (key < 0) {
                int button = interstellarTikiTiks.apexx.client.module.Module.mouseButtonOf(key);
                return button >= 0 && button <= GLFW.GLFW_MOUSE_BUTTON_LAST
                        && GLFW.glfwGetMouseButton(handle, button) == GLFW.GLFW_PRESS;
            }
        }
        return isEnabled();
    }

    private double getTargetCps() {
        if ("Legit".equalsIgnoreCase(mode.getCurrentMode())) {
            double min = Math.min(legitMinCps.getValue(), legitMaxCps.getValue());
            double max = Math.max(legitMinCps.getValue(), legitMaxCps.getValue());
            min = Math.min(min, 30.0);
            max = Math.min(max, 30.0);
            if (Math.abs(max - min) < 0.001) {
                return min;
            }
            return min + ThreadLocalRandom.current().nextDouble() * (max - min);
        } else {
            double min = Math.min(minCps.getValue(), maxCps.getValue());
            double max = Math.max(minCps.getValue(), maxCps.getValue());
            min = Math.min(min, 1000.0);
            max = Math.min(max, 1000.0);
            if (Math.abs(max - min) < 0.001) {
                return min;
            }
            return min + ThreadLocalRandom.current().nextDouble() * (max - min);
        }
    }

    private int calculateClicks(double cps) {
        if (cps <= 0.0) return 0;
        long now = System.nanoTime();
        if (lastNanoTime == 0L) {
            lastNanoTime = now;
            return 0;
        }
        double dt = (now - lastNanoTime) * 1.0E-9;
        lastNanoTime = now;
        clickAccumulator += dt * cps;
        int clicks = Math.min((int) clickAccumulator, 100);
        clickAccumulator -= clicks;
        return clicks;
    }

    /**
     * Vanilla {@code doAttack} through {@code MinecraftClientAccessor.attack()}: it attacks whatever the
     * (silent-rerouted) crosshair holds, extended to this module's range. No ClickSim, no GLFW input.
     */
    private boolean performAttack(double reach) {
        if (mc.player == null || mc.interactionManager == null) return false;
        boolean[] hit = {false};
        ModuleExecutionContext.run(this, () -> {
            if (mc instanceof MinecraftClientAccessor accessor) {
                // Keep the missed-attack cooldown clear between burst hits (Legit execution mode only).
                interstellarTikiTiks.apexx.client.util.ExecutionMode.relaxAttackCooldown(accessor);
            }
            hit[0] = RotationManager.withSilentReach(reach, () -> AttackValidator.invokeVanillaAttack(mc));
        });
        return hit[0];
    }

    private PlayerEntity findTarget(double reach) {
        if (mc.player == null || mc.world == null) return null;

        // 1. Prioritize entity directly under player's crosshair
        HitResult hit = mc.crosshairTarget;
        if (hit instanceof EntityHitResult entityHit && entityHit.getEntity() instanceof PlayerEntity player) {
            if (isValidTarget(player) && (double) mc.player.distanceTo(player) <= reach) {
                return player;
            }
        }

        // 2. Select closest valid blocking target within range
        PlayerEntity closest = null;
        double closestDist = Double.MAX_VALUE;
        for (PlayerEntity player : mc.world.getPlayers()) {
            if (!isValidTarget(player)) continue;
            double dist = mc.player.distanceTo(player);
            if (dist <= reach && dist < closestDist) {
                closest = player;
                closestDist = dist;
            }
        }
        return closest;
    }

    private boolean isValidTarget(PlayerEntity target) {
        if (target == null || target == mc.player || !target.isAlive() || !target.isBlocking()) {
            return false;
        }
        if (!interstellarTikiTiks.apexx.client.util.combat.StickyTargets.allows(this, target)) {
            return false;
        }
        if (ignoreFriends.isEnabled() && FriendManager.getInstance().isFriend(target.getUuid())) {
            return false;
        }
        return true;
    }

    private int findMaceSlot() {
        if (mc.player == null) return -1;
        for (int i = 0; i < 9; i++) {
            ItemStack stack = mc.player.getInventory().getStack(i);
            if (CombatItemUtil.isMace(stack)) {
                return i;
            }
        }
        return -1;
    }

    private void stopDraining() {
        if (isDraining) {
            isDraining = false;
            // Swap back after the configured delay rather than in the same instant the drain ended.
            restoreAtMs = System.currentTimeMillis() + ModuleDelays.clampMs(swapBackDelay.sample());
        }
        lastNanoTime = 0L;
        clickAccumulator = 0.0;
    }

    private void recoverSlot() {
        if (previousSlot != -1 && mc.player != null) {
            if (previousSlot >= 0 && previousSlot < 9) {
                int cur = mc.player.getInventory().getSelectedSlot();
                if (cur != previousSlot) {
                    if (TickActionOrder.canSwitchSlot()) {
                        SlotSync.select(mc, previousSlot);
                    } else {
                        TickActionOrder.deferSlot(previousSlot, cur);
                    }
                }
            }
        }
        previousSlot = -1;
    }

    private void resetState() {
        previousSlot = -1;
        lastNanoTime = 0L;
        clickAccumulator = 0.0;
        isDraining = false;
        clickReadyAtMs = 0L;
        restoreAtMs = -1L;
    }

    public boolean isDraining() {
        return isDraining;
    }
}
