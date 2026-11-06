package interstellarTikiTiks.apexx.client.module.implementation.movement;

import interstellarTikiTiks.apexx.client.util.combat.ModuleDelays;
import interstellarTikiTiks.apexx.client.event.EventBus;
import interstellarTikiTiks.apexx.client.event.EventTarget;
import interstellarTikiTiks.apexx.client.event.impl.CombatSwapLockEvent;
import interstellarTikiTiks.apexx.client.mixin.KeyBindingAccessor;
import interstellarTikiTiks.apexx.client.mixin.MinecraftClientAccessor;
import interstellarTikiTiks.apexx.client.module.BooleanSetting;
import interstellarTikiTiks.apexx.client.module.Category;
import interstellarTikiTiks.apexx.client.module.Module;
import interstellarTikiTiks.apexx.client.module.SliderSetting;
import interstellarTikiTiks.apexx.client.mixin.LivingEntityAccessor;
import interstellarTikiTiks.apexx.client.friend.FriendManager;
import interstellarTikiTiks.apexx.client.module.implementation.player.BotFilterProvider;
import interstellarTikiTiks.apexx.client.util.AttackValidator;
import interstellarTikiTiks.apexx.client.util.ExecutionMode;
import interstellarTikiTiks.apexx.client.util.ModuleAccess;
import interstellarTikiTiks.apexx.client.util.ModuleExecutionContext;
import interstellarTikiTiks.apexx.client.util.RotationManager;
import interstellarTikiTiks.apexx.client.util.RotationRequest;
import interstellarTikiTiks.apexx.client.util.SystemInputSimulator;
import interstellarTikiTiks.apexx.client.util.combat.SubTickSequencer;
import interstellarTikiTiks.apexx.client.util.combat.CombatItemUtil;
import interstellarTikiTiks.apexx.client.util.combat.SlotSync;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.ItemEnchantmentsComponent;
import net.minecraft.component.type.PiercingWeaponComponent;
import net.minecraft.enchantment.Enchantment;
import net.minecraft.enchantment.EnchantmentHelper;
import net.minecraft.enchantment.Enchantments;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.registry.Registries;
import net.minecraft.registry.entry.RegistryEntry;
import interstellarTikiTiks.apexx.client.util.combat.TickActionOrder;
import net.minecraft.registry.tag.ItemTags;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

import java.util.Optional;

/**
 * Lunge macro: on the trigger key the module picks the hotbar spear with Lunge, swaps to it, attacks
 * (the module's own client click, same tick), then swaps back on a following tick. LMB Lunge starts
 * the same cycle from a left click made with a non-weapon in hand.
 *
 * <p>Aim Assist: when a player is inside Aim Range and Aim FOV as the lunge starts, the view (or, with
 * Silent Aim, the server-side rotation only) turns onto them first and the swap and attack follow in the
 * tick the turn lands. Stop Lunge: no lunge starts while the nearest opponent's horizontal distance is
 * inside the Stop Min to Stop Max band.
 *
 * <p>Hold to Repeat keeps one session alive while the trigger is held: the cycle repeats at Repeat Speed
 * and the turn onto the target happens once, at the first lunge of the hold, never again per repeat.
 * With Silent Aim the session holds the same server-side angles until the trigger is released.
 *
 * <p>AutoLunge is legit-only: a lunge waits for full weapon charge. The instant, any-charge variant is the
 * separate Blatant module {@code InstantLunge}, which reuses this machinery with {@link #instant} set.
 *
 * <p>The rotation, aim-gate and repeat timing are UNVERIFIED in game; see docs/modules/MODULE_CATALOG.md.
 */
public class AutoLunge extends Module implements interstellarTikiTiks.apexx.client.module.hooks.ClickSimulating {
    /** True for the Blatant InstantLunge variant: lunges at any charge with near-zero swap gaps. */
    protected final boolean instant;

    // ---- Delays ----
    private final SliderSetting profileDelay = new SliderSetting("Swap Back Delay (ms)", 30, 5, 400, 0)
            .withJitter(8)
            .setDescription("Wait after the lunge before your previous slot is reselected. The swap-back is always deferred to a later moment than the attack itself.")
            .setSection("Delays")
            .setDependsOn("Swap Back");
    private final SliderSetting attackDelay = new SliderSetting("Attack Delay (ms)", 3, 1, 40, 0)
            .withJitter(1)
            .setDescription("Gap between selecting the lunge spear and attacking with it; both happen inside the same game tick.")
            .setSection("Delays");
    private final SliderSetting lockTimeoutTicks = new SliderSetting("Lock Wait (ticks)", 10, 1, 40, 0)
            .setDescription("How many ticks a lunge waits for another module's hotbar lock or a pending swap-back before giving up.")
            .setSection("Delays");


    /** Ticks the turn onto the target may take before the lunge fires anyway (half a second). */
    private static final int AIM_TIMEOUT_TICKS = 10;
    /** Per-axis angular error, degrees, at which the turn counts as landed. */
    private static final float AIM_LOCK_DEG = 2.0f;
    /** Range slack a target already being turned onto keeps, so a marginal edge does not drop the turn. */
    private static final double AIM_RANGE_SLACK = 1.0;
    private static final double MAX_AIM_DEG_PER_SEC = 1450.0;
    private static final double AIM_RESPONSE = 0.95;
    /** Ticks a soft rotation release is waited for before the module disables regardless. */
    private static final int RELEASE_WAIT_TICKS = 10;

    private enum Begin { STARTED, WAIT, FAIL }

    private final BooleanSetting lmbLunge = new BooleanSetting("LMB Lunge", true)
            .setDescription("While you hold something that is not a weapon, your left click starts a lunge instead of a normal click.");
    private final BooleanSetting swapBack = new BooleanSetting("Swap Back", true)
            .setDescription("Reselects the slot you were holding after the lunge.");
    private final BooleanSetting swapBackToSlot = new BooleanSetting("Swap Back To Slot", false)
            .setDescription("Swaps back to a fixed hotbar slot instead of the one you were holding.");
    private final SliderSetting swapBackSlot = new SliderSetting("Swap Back Slot (1-9)", 1, 1, 9, 0)
            .setDescription("The fixed hotbar slot used by Swap Back To Slot.");
    private final SliderSetting repeatSpeed = new SliderSetting("Repeat Speed (lunges/s)", 10, 1, 20, 0)
            .setDescription("How fast the lunge repeats while the key is held. Only used with Hold to Repeat.");

    private final BooleanSetting aimAssist = new BooleanSetting("Aim Assist", false)
            .setDescription("When a player is inside Aim Range and Aim FOV as the lunge starts, turns onto them first and lunges in the tick the turn lands (half a second at most, then it lunges anyway). Nothing in range and FOV: the lunge fires where you look. With Hold to Repeat the turn is done once per hold, not for every repeat.");
    private final SliderSetting aimRange = new SliderSetting("Aim Range", 8.0, 2.0, 16.0, 1)
            .setDescription("Blocks, eye to the target's hitbox, inside which a player is picked as the aim target.");
    private final SliderSetting aimFov = new SliderSetting("Aim FOV", 90.0, 10.0, 360.0, 0)
            .setDescription("Full width, in degrees, of the cone around your view a player must be inside to be picked.");
    private final SliderSetting aimSpeed = new SliderSetting("Aim Speed", 60.0, 1.0, 100.0, 0)
            .setDescription("How fast the view turns onto the target. 100 is as fast as the rotation controller allows.");
    private final BooleanSetting silentAim = new BooleanSetting("Silent Aim", false)
            .setDescription("Turns the server-side rotation only, so the camera never moves. With Hold to Repeat the same angles are held until the key is released.");

    private final BooleanSetting stopLunge = new BooleanSetting("Stop Lunge", false)
            .setDescription("Does not lunge while the nearest opponent's horizontal (X/Z) distance is between Stop Min Distance and Stop Max Distance, whatever the height difference. Your click is left alone then.");
    private final SliderSetting stopMinDistance = new SliderSetting("Stop Min Distance", 0.0, 0.0, 12.0, 1)
            .setDescription("Lower end of the horizontal distance band, in blocks, in which lunging is stopped.");
    private final SliderSetting stopMaxDistance = new SliderSetting("Stop Max Distance", 2.5, 0.0, 12.0, 1)
            .setDescription("Upper end of the horizontal distance band, in blocks. The two values may be set in either order.");

    private boolean cycleActive = false;
    private boolean swapBackPending = false;
    private boolean reentrantAttack = false;
    private int lungeSlot = -1;
    private int originalSlot = -1;
    private int lockWaitTicks = 0;
    private long restoreAt = 0L;
    private Object boundPlayer;
    private Object boundWorld;
    private long lastCycleStart = 0L;

    // Aim phase
    private boolean aiming = false;
    private PlayerEntity aimTarget;
    private int aimTicks = 0;
    private boolean aimRequested = false;
    /** The tick age from which the aim request may be released (never in the tick that carried the lunge). */
    private int aimReleaseAge = -1;
    private boolean aimReleasePending = false;

    // Hold session: one enabled stretch while the trigger stays held with Hold to Repeat on.
    private boolean sessionAimed = false;
    private float sessionYaw;
    private float sessionPitch;
    private boolean repeatWait = false;
    private long nextCycleAt = 0L;
    private boolean lmbTriggered = false;
    /** Set by {@link #handleAttack} for the enable that follows it; consumed in onEnable. */
    private boolean pendingLmbTrigger = false;
    private boolean ending = false;
    private int endTicks = 0;

    public AutoLunge() {
        this("AutoLunge", "One key (or a left click with a non-weapon) lunges with your Lunge spear: selects it, attacks once it is fully charged and swaps back, with optional aim assist onto a nearby player and a distance band where it holds off.", Category.MOVEMENT, false);
    }

    protected AutoLunge(String name, String description, Category category, boolean instant) {
        super(name, description, category);
        this.instant = instant;
        // A one-shot blatant module must never act from a plain left click until the user opts in.
        if (instant) lmbLunge.setEnabled(false);
        addSetting(lmbLunge);
        addSetting(swapBack);
        addSetting(swapBackToSlot);
        addSetting(swapBackSlot);
        addSetting(repeatSpeed);

        aimAssist.setSection("Aim Assist");
        aimRange.setSection("Aim Assist");
        aimFov.setSection("Aim Assist");
        aimSpeed.setSection("Aim Assist");
        silentAim.setSection("Aim Assist");
        addSetting(aimAssist);
        addSetting(aimRange);
        addSetting(aimFov);
        addSetting(aimSpeed);
        addSetting(silentAim);

        stopLunge.setSection("Stop Lunge");
        stopMinDistance.setSection("Stop Lunge");
        stopMaxDistance.setSection("Stop Lunge");
        addSetting(stopLunge);
        addSetting(stopMinDistance);
        addSetting(stopMaxDistance);

        // Delays last, below the aim and stop settings.
        addSetting(profileDelay);
        addSetting(attackDelay);
        addSetting(lockTimeoutTicks);

        swapBackToSlot.setVisibility(swapBack::isEnabled);
        swapBackSlot.setVisibility(swapBackToSlot::isEnabled);
        aimRange.setVisibility(aimAssist::isEnabled);
        aimFov.setVisibility(aimAssist::isEnabled);
        aimSpeed.setVisibility(aimAssist::isEnabled);
        silentAim.setVisibility(aimAssist::isEnabled);
        stopMinDistance.setVisibility(stopLunge::isEnabled);
        stopMaxDistance.setVisibility(stopLunge::isEnabled);
    }

    @Override
    public boolean isOneShot() {
        return true;
    }

    @EventTarget
    public void onCombatSwapLock(CombatSwapLockEvent event) {
        if (cycleActive || swapBackPending) {
            event.setItemSwapLocked(true);
        }
    }

    @Override
    public void onEnable() {
        resetState();
        lmbTriggered = pendingLmbTrigger;
        pendingLmbTrigger = false;
        EventBus.register(this);

        if (mc.player == null || mc.world == null || mc.getNetworkHandler() == null) {
            toggle();
            return;
        }

        long now = System.currentTimeMillis();
        if (isOneShotRepeat() && now - lastCycleStart < repeatIntervalMs()) {
            toggle();
            return;
        }

        if (beginCycle() != Begin.STARTED) {
            toggle();
            return;
        }
        lastCycleStart = now;
        fireOrAim();
    }

    @Override
    protected void onRuntimeStateReleased() {
        EventBus.unregister(this);
        if (swapBackPending && swapBack.isEnabled()) {
            returnToSwapBackSlot();
        }
        resetState();
    }

    private void resetState() {
        cycleActive = false;
        swapBackPending = false;
        reentrantAttack = false;
        lungeSlot = -1;
        originalSlot = -1;
        lockWaitTicks = 0;
        restoreAt = 0L;
        boundPlayer = boundWorld = null;
        aiming = false;
        aimTarget = null;
        aimTicks = 0;
        aimRequested = false;
        aimReleaseAge = -1;
        aimReleasePending = false;
        sessionAimed = false;
        repeatWait = false;
        nextCycleAt = 0L;
        lmbTriggered = false;
        ending = false;
        endTicks = 0;
    }

    @Override
    public void onTick() {
        if (mc.player == null || mc.world == null || mc.getNetworkHandler() == null || mc.currentScreen != null
                || mc.player != boundPlayer || mc.world != boundWorld) {
            if (isEnabled()) {
                toggle();
            } else {
                resetState();
            }
            return;
        }

        if (ending) {
            if (++endTicks > RELEASE_WAIT_TICKS || RotationManager.getCurrentOwner() != this
                    || !RotationManager.isActive()) {
                toggle();
            }
            return;
        }

        // The aim request outlives the tick that carried the lunge and is released in a later one.
        if (aimReleasePending && mc.player.age >= aimReleaseAge) {
            aimReleasePending = false;
            if (silentAim.isEnabled() && sessionAimed && isOneShotRepeat() && triggerHeld()) {
                holdSessionAim();
            } else {
                releaseAim();
            }
        }

        if (cycleActive) {
            if (aiming) {
                if (!tickAim()) {
                    return;
                }
            }
            executeCycle();
            return;
        }

        if (swapBackPending) {
            if ((restoreAt != 0L && System.currentTimeMillis() >= restoreAt) || ++lockWaitTicks > (int) lockTimeoutTicks.getValue()) {
                finishCycle();
            }
            return;
        }

        if (repeatWait) {
            tickRepeatWait();
        }
    }

    /** Starts the swap and attack, or the turn onto the target that comes first. */
    private void fireOrAim() {
        if (aiming && !tickAim()) {
            return;
        }
        if (cycleActive) {
            executeCycle();
        }
    }

    /**
     * Moves the turn onto the target one step.
     *
     * @return true when the lunge may fire this tick: the turn has landed, or it cannot or need not
     *         continue (target gone, channel held by another owner, half a second spent)
     */
    private boolean tickAim() {
        PlayerEntity target = aimTarget;
        if (target == null || !target.isAlive() || target.isRemoved() || mc.player == null) {
            aiming = false;
            return true;
        }
        if (isStopBandActive()) {
            // The opponent stepped into the stop band while the view was turning: no lunge at all.
            abortCycle();
            return false;
        }
        if (eyeToBoxDistance(target) > aimRange.getValue() + AIM_RANGE_SLACK) {
            aiming = false;
            return true;
        }
        if (RotationManager.getCurrentOwner() != this || !RotationManager.isActive()) {
            submitAim();
        }
        if (RotationManager.getCurrentOwner() != this) {
            // Another module holds the rotation channel at a higher priority: lunge where we look.
            aimRequested = false;
            aiming = false;
            return true;
        }
        aimTicks++;
        float[] want = anglesTo(aimPoint(target));
        float yaw = RotationManager.getFinalYaw();
        float pitch = RotationManager.getFinalPitch();
        boolean landed = Math.abs(MathHelper.wrapDegrees(yaw - want[0])) <= AIM_LOCK_DEG
                && Math.abs(pitch - want[1]) <= AIM_LOCK_DEG;
        if (landed || aimTicks >= AIM_TIMEOUT_TICKS) {
            aiming = false;
            sessionAimed = true;
            return true;
        }
        return false;
    }

    private void submitAim() {
        boolean silent = silentAim.isEnabled();
        double speed = RotationManager.engineStrength(aimSpeed.getValue(), RotationManager.STRENGTH_SCALE_TRACK) * 60.0;
        if (silent) {
            speed *= 1.5;
        }
        speed = Math.min(speed, MAX_AIM_DEG_PER_SEC);
        RotationRequest request = RotationRequest.create()
                .speed(speed)
                .response(AIM_RESPONSE)
                .prediction(0.0)
                .targetSmoothing(false)
                .silent(silent)
                .yawOnly(false);
        aimRequested = true;
        RotationManager.setRotationSupplier(this, RotationManager.Priority.HIGH,
                () -> aimTarget != null && aimTarget.isAlive() ? aimPoint(aimTarget) : null, request);
    }

    /**
     * Keeps the silent angles the first lunge of a hold was fired with for the rest of the hold, so the
     * repeats go out along the same wire angles instead of turning onto the target again.
     */
    private void holdSessionAim() {
        if (mc.player == null) {
            return;
        }
        if (RotationManager.getCurrentOwner() == this && RotationManager.isActive()) {
            sessionYaw = RotationManager.getFinalYaw();
            sessionPitch = RotationManager.getFinalPitch();
        }
        submitLockedAim();
    }

    private void submitLockedAim() {
        if (mc.player == null) {
            return;
        }
        final float yaw = sessionYaw;
        final float pitch = sessionPitch;
        RotationRequest request = RotationRequest.create()
                .speed(MAX_AIM_DEG_PER_SEC)
                .response(1.0)
                .prediction(0.0)
                .targetSmoothing(false)
                .silent(true)
                .yawOnly(false);
        aimRequested = true;
        RotationManager.setRotationSupplier(this, RotationManager.Priority.HIGH,
                () -> RotationManager.pointAlongRotation(yaw, pitch, 64.0), request);
    }

    private void releaseAim() {
        if (aimRequested) {
            aimRequested = false;
            RotationManager.clearTarget(this);
        }
    }

    private Vec3d aimPoint(PlayerEntity target) {
        return target.getBoundingBox().getCenter();
    }

    private float[] anglesTo(Vec3d point) {
        Vec3d eye = mc.player.getEyePos();
        double dx = point.x - eye.x;
        double dy = point.y - eye.y;
        double dz = point.z - eye.z;
        double horizontal = Math.sqrt(dx * dx + dz * dz);
        float yaw = (float) Math.toDegrees(Math.atan2(dz, dx)) - 90.0f;
        float pitch = (float) -Math.toDegrees(Math.atan2(dy, horizontal));
        return new float[] {yaw, MathHelper.clamp(pitch, -90.0f, 90.0f)};
    }

    /** Ends the cycle that is waiting on its turn without a lunge, keeping the hold session if one runs. */
    private void abortCycle() {
        cycleActive = false;
        aiming = false;
        releaseAim();
        finishSession();
    }

    /** The lunge and its swap back are done: wait for the next repeat while the trigger is held, else end. */
    private void finishCycle() {
        swapBackPending = false;
        if (swapBack.isEnabled()) {
            returnToSwapBackSlot();
        }
        finishSession();
    }

    private void finishSession() {
        if (!isEnabled()) {
            return;
        }
        if (isOneShotRepeat() && triggerHeld()) {
            repeatWait = true;
            nextCycleAt = lastCycleStart + repeatIntervalMs();
            return;
        }
        endSession();
    }

    /** Disables the module, letting a held silent rotation decay first instead of snapping back. */
    private void endSession() {
        repeatWait = false;
        if (aimRequested && RotationManager.getCurrentOwner() == this && RotationManager.isActive()) {
            releaseAim();
            ending = true;
            endTicks = 0;
            return;
        }
        if (isEnabled()) {
            toggle();
        }
    }

    private void tickRepeatWait() {
        if (!triggerHeld()) {
            endSession();
            return;
        }
        if (System.currentTimeMillis() < nextCycleAt) {
            keepSessionAim();
            return;
        }
        Begin begin = beginCycle();
        if (begin == Begin.FAIL) {
            endSession();
            return;
        }
        if (begin == Begin.WAIT) {
            keepSessionAim();
            return;
        }
        repeatWait = false;
        lastCycleStart = System.currentTimeMillis();
        fireOrAim();
    }

    /** Re-asserts the held silent angles if another owner took and released the channel meanwhile. */
    private void keepSessionAim() {
        if (aimRequested && silentAim.isEnabled() && sessionAimed
                && (RotationManager.getCurrentOwner() != this || !RotationManager.isActive())) {
            submitLockedAim();
        }
    }

    /** Whether the key or click that started this cycle is still physically down. */
    private boolean triggerHeld() {
        if (lmbTriggered) {
            return mc.options != null && mc.currentScreen == null
                    && SystemInputSimulator.isPhysicallyPressed(mc.options.attackKey);
        }
        return isBindHeld();
    }

    private void executeCycle() {
        CombatSwapLockEvent lock = new CombatSwapLockEvent(this);
        EventBus.post(lock);
        if (lock.isAutoAttackLocked()) {
            if (++lockWaitTicks > (int) lockTimeoutTicks.getValue()) {
                setEnabled(false);
            }
            return;
        }

        if (!TickActionOrder.canSwitchSlot()) {
            return;
        }

        if (mc.player.getInventory().getSelectedSlot() != originalSlot
                || !hasLunge(mc.player.getInventory().getStack(lungeSlot))
                || !SlotSync.select(mc, lungeSlot)) {
            setEnabled(false);
            return;
        }

        cycleActive = false;
        swapBackPending = true;
        restoreAt = 0L;

        boolean blatant = instant || ExecutionMode.isDirect();
        double delayMs = blatant ? 0.0 : Math.min(40L, ModuleDelays.clampMs(attackDelay.sample()));

        Runnable attackAction = () -> {
            if (mc.player == null || mc.world == null || mc.currentScreen != null) {
                return;
            }
            ItemStack held = mc.player.getMainHandStack();
            if (!hasLunge(held)) {
                return;
            }
            // Fast-forward client-side attack charge for spear MINIMUM_ATTACK_CHARGE gate
            if (mc.player.getAttackCooldownProgress(0.0f) < 1.0f && mc.player instanceof LivingEntityAccessor lea) {
                int fullChargeTicks = (int) Math.ceil(mc.player.getAttackCooldownProgressPerTick());
                lea.apexx$setTicksSinceLastAttack(fullChargeTicks);
            }
            if (mc instanceof MinecraftClientAccessor accessor) {
                accessor.setAttackCooldown(0);
            }

            reentrantAttack = true;
            try {
                boolean attacked = false;
                if (AttackValidator.emitAttackClick(mc)) {
                    if (mc instanceof MinecraftClientAccessor accessor) {
                        ExecutionMode.relaxAttackCooldown(accessor);
                        attacked = AttackValidator.invokeVanillaAttack(mc);
                    }
                    if (mc.options != null && mc.options.attackKey instanceof KeyBindingAccessor attackKey) {
                        attackKey.setTimesPressed(0);
                    }
                }
                // Fallback to direct piercing weapon dispatch if vanilla doAttack did not consume it
                if (!attacked) {
                    PiercingWeaponComponent comp = held.get(DataComponentTypes.PIERCING_WEAPON);
                    if (comp != null) {
                        AttackValidator.dispatchPiercingWeaponAttack(mc, comp, true);
                    }
                }
            } finally {
                reentrantAttack = false;
            }

            if (aimRequested && RotationManager.getCurrentOwner() == this && mc.player != null) {
                // The rotation that aimed the lunge stays for the rest of this tick and is released from
                // the next one, so the movement packet that follows carries it.
                RotationManager.setPostAttackHold(this, 1, RotationManager.getFinalYaw(),
                        RotationManager.getFinalPitch());
                aimReleaseAge = mc.player.age + 1;
                aimReleasePending = true;
            }

            long swapDelay = blatant ? Math.min(10L, ModuleDelays.clampMs(profileDelay.sample())) : ModuleDelays.clampMs(profileDelay.sample());
            restoreAt = System.currentTimeMillis() + swapDelay;
        };

        if (delayMs <= 0.0) {
            ModuleExecutionContext.run(this, attackAction);
        } else {
            SubTickSequencer.queueAction(this, attackAction, delayMs);
        }
    }

    private Begin beginCycle() {
        boolean blatant = instant || ExecutionMode.isDirect();
        if (mc.currentScreen != null) {
            return Begin.FAIL;
        }
        // In Legit mode, refuse to start on a partial swing charge: swapping early would only
        // produce a held-item change with no valid swing behind it.
        // Blatant mode allows starting at any charge level.
        if (!blatant && mc.player.getAttackCooldownProgress(0.0f) < 1.0f) {
            return Begin.WAIT;
        }

        CombatSwapLockEvent lock = new CombatSwapLockEvent(this);
        EventBus.post(lock);
        if (lock.isItemSwapLocked() || lock.isAutoAttackLocked()) {
            return Begin.WAIT;
        }

        int slot = findLungeSlot();
        if (slot == -1) {
            return Begin.FAIL;
        }
        if (isStopBandActive()) {
            return Begin.WAIT;
        }

        aimTarget = null;
        aiming = false;
        aimTicks = 0;
        // One turn per hold: once the session has turned onto a target the repeats just fire.
        if (aimAssist.isEnabled() && !sessionAimed) {
            PlayerEntity target = pickAimTarget();
            if (target != null) {
                aimTarget = target;
                aiming = true;
            }
        }

        boundPlayer = mc.player;
        boundWorld = mc.world;
        originalSlot = mc.player.getInventory().getSelectedSlot();
        lungeSlot = slot;
        cycleActive = true;
        restoreAt = 0L;
        lockWaitTicks = 0;
        return Begin.STARTED;
    }

    // ── Targets ──────────────────────────────────────────────────────────────

    /** A player worth turning onto or measuring: not self, friend, bot, spectator or creative. */
    private boolean isOpponent(PlayerEntity player) {
        if (player == null || player == mc.player || !player.isAlive() || player.isSpectator()
                || player.isCreative()) {
            return false;
        }
        if (FriendManager.getInstance().isFriend(player.getUuid())) {
            return false;
        }
        for (BotFilterProvider filter : ModuleAccess.getEnabledHooks(BotFilterProvider.class)) {
            if (filter.isBot(player)) {
                return false;
            }
        }
        return true;
    }

    /** The opponent inside Aim Range and Aim FOV that is nearest the view centre, in line of sight. */
    private PlayerEntity pickAimTarget() {
        if (mc.player == null || mc.world == null) {
            return null;
        }
        double range = aimRange.getValue();
        PlayerEntity best = null;
        double bestAngle = Double.POSITIVE_INFINITY;
        for (PlayerEntity candidate : mc.world.getPlayers()) {
            if (!isOpponent(candidate) || eyeToBoxDistance(candidate) > range) {
                continue;
            }
            double angle = angleToView(candidate);
            if (!withinCone(angle, aimFov.getValue()) || !mc.player.canSee(candidate)) {
                continue;
            }
            if (angle < bestAngle) {
                bestAngle = angle;
                best = candidate;
            }
        }
        return best;
    }

    /** True 3D angle, degrees, between the view and the centre of the player's box. */
    private double angleToView(PlayerEntity target) {
        Vec3d look = mc.player.getRotationVec(1.0f).normalize();
        Vec3d direction = aimPoint(target).subtract(mc.player.getEyePos());
        double length = direction.length();
        if (length < 1.0e-6) {
            return 0.0;
        }
        return Math.toDegrees(Math.acos(MathHelper.clamp(look.dotProduct(direction) / length, -1.0, 1.0)));
    }

    private double eyeToBoxDistance(PlayerEntity target) {
        Vec3d eye = mc.player.getEyePos();
        Box box = target.getBoundingBox();
        double x = MathHelper.clamp(eye.x, box.minX, box.maxX);
        double y = MathHelper.clamp(eye.y, box.minY, box.maxY);
        double z = MathHelper.clamp(eye.z, box.minZ, box.maxZ);
        return eye.distanceTo(new Vec3d(x, y, z));
    }

    /** Stop Lunge: the nearest opponent's horizontal distance is inside the configured band. */
    private boolean isStopBandActive() {
        if (!stopLunge.isEnabled() || mc.player == null || mc.world == null) {
            return false;
        }
        double nearest = Double.POSITIVE_INFINITY;
        for (PlayerEntity candidate : mc.world.getPlayers()) {
            if (!isOpponent(candidate)) {
                continue;
            }
            nearest = Math.min(nearest, horizontalDistance(mc.player.getX(), mc.player.getZ(),
                    candidate.getX(), candidate.getZ()));
        }
        return inStopBand(nearest, stopMinDistance.getValue(), stopMaxDistance.getValue());
    }

    /** Distance on the X/Z plane; the height difference never counts. */
    public static double horizontalDistance(double x1, double z1, double x2, double z2) {
        return Math.hypot(x1 - x2, z1 - z2);
    }

    /** Whether {@code distance} lies in the closed band between the two values, given in either order. */
    public static boolean inStopBand(double distance, double a, double b) {
        if (!Double.isFinite(distance)) {
            return false;
        }
        return distance >= Math.min(a, b) && distance <= Math.max(a, b);
    }

    /** Whether an angle off the view centre is inside a cone of the given full width. */
    public static boolean withinCone(double angleDeg, double fovDeg) {
        return angleDeg <= fovDeg * 0.5;
    }

    private void resetHandAnimation() {
        if (mc.player == null) return;
        mc.player.handSwinging = false;
        mc.player.handSwingTicks = 0;
        mc.player.handSwingProgress = 0.0f;
        mc.player.lastHandSwingProgress = 0.0f;
    }

    /**
     * LMB Lunge: with a non-weapon in hand and a Lunge spear in the hotbar, cancels the click
     * and starts a lunge cycle. Called directly from the attack mixin because this one-shot
     * module is normally disabled, so the enabled-hook dispatch never reaches it.
     */
    public boolean handleAttack() {
        if (!lmbLunge.isEnabled() || mc.player == null || mc.world == null || mc.getNetworkHandler() == null) {
            return false;
        }
        // The module's own lunge click must pass through; only the user's clicks are barred
        if (reentrantAttack || ModuleExecutionContext.currentOwner() == this) {
            return false;
        }
        if (cycleActive || swapBackPending || ending) {
            return true;
        }
        // Stop Lunge: the click is the user's own then, not a lunge trigger.
        if (isStopBandActive()) {
            return false;
        }
        // Holding the lunge spear itself with the swing still charging: vanilla would play the
        // lunge/jab animation with nothing behind it, so swallow the click until it is ready.
        if (ModuleExecutionContext.currentOwner() == null
                && hasLunge(mc.player.getMainHandStack())
                && !instant && !ExecutionMode.isDirect()
                && mc.player.getAttackCooldownProgress(0.0f) < 1.0f) {
            return true;
        }
        if (!isNonWeapon(mc.player.getMainHandStack())
                || mc.player.isUsingItem()
                || (mc.interactionManager != null && mc.interactionManager.isBreakingBlock())
                || isEnabled()
                || findLungeSlot() == -1) {
            return false;
        }
        boolean blatant = instant || ExecutionMode.isDirect();
        if (!blatant && mc.player.getAttackCooldownProgress(0.0f) < 1.0f) {
            return false;
        }
        pendingLmbTrigger = true;
        setEnabled(true);
        pendingLmbTrigger = false;
        return true;
    }

    public static boolean isNonWeapon(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return true;
        }
        return !CombatItemUtil.isWeapon(stack) && !isHoldingSpear(stack);
    }

    public static boolean isHoldingSpear(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return false;
        }
        return stack.isIn(ItemTags.SPEARS)
                || Registries.ITEM.getId(stack.getItem()).getPath().contains("spear");
    }

    /** Slot this module still owes a swap back to while its lunge is in flight, else -1. */
    public int getPendingReturnSlot() {
        if (!(cycleActive || swapBackPending) || !swapBack.isEnabled()) {
            return -1;
        }
        return swapBackToSlot.isEnabled()
                ? MathHelper.clamp((int) swapBackSlot.getValue() - 1, 0, 8) : originalSlot;
    }

    private void returnToSwapBackSlot() {
        if (mc.player == null || mc.player != boundPlayer || mc.world != boundWorld
                || mc.player.getInventory().getSelectedSlot() != lungeSlot) return;
        if (swapBackToSlot.isEnabled()) {
            SlotSync.restore(mc, MathHelper.clamp((int) swapBackSlot.getValue() - 1, 0, 8));
        } else if (originalSlot != -1) {
            SlotSync.restore(mc, originalSlot);
        }
    }

    private long repeatIntervalMs() {
        return (long) (1000.0 / Math.max(1.0, repeatSpeed.getValue()));
    }


    public int findLungeSlot() {
        if (mc.player == null) return -1;
        for (int i = 0; i < 9; i++) {
            if (hasLunge(mc.player.getInventory().getStack(i))) {
                return i;
            }
        }
        return -1;
    }

    public static boolean hasLunge(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return false;
        }
        net.minecraft.client.MinecraftClient client = net.minecraft.client.MinecraftClient.getInstance();
        if (client.world != null) {
            try {
                var reg = client.world.getRegistryManager().getOrThrow(RegistryKeys.ENCHANTMENT);
                if (EnchantmentHelper.getLevel(reg.getOrThrow(Enchantments.LUNGE), stack) > 0) {
                    return true;
                }
            } catch (Exception ignored) {}
        }
        try {
            var enchants = stack.getOrDefault(DataComponentTypes.ENCHANTMENTS, ItemEnchantmentsComponent.DEFAULT);
            for (RegistryEntry<Enchantment> entry : enchants.getEnchantments()) {
                Optional<RegistryKey<Enchantment>> keyOpt = entry.getKey();
                if (keyOpt.isPresent() && keyOpt.get().equals(Enchantments.LUNGE)) {
                    if (enchants.getLevel(entry) > 0) return true;
                }
            }
            for (var entry : enchants.getEnchantmentEntries()) {
                RegistryEntry<Enchantment> enchantment = entry.getKey();
                String id = enchantment.getKey().map(k -> k.getValue().getPath()).orElse("").toLowerCase();
                String name = enchantment.value().description().getString().toLowerCase();
                if (id.contains("lunge") || name.contains("lunge")) {
                    return true;
                }
            }
        } catch (Exception ignored) {}
        return false;
    }
}
