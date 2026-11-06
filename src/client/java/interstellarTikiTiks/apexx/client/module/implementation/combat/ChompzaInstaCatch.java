package interstellarTikiTiks.apexx.client.module.implementation.combat;

import interstellarTikiTiks.apexx.client.event.EventBus;
import interstellarTikiTiks.apexx.client.event.EventTarget;
import interstellarTikiTiks.apexx.client.event.impl.CombatSwapLockEvent;
import interstellarTikiTiks.apexx.client.event.impl.PearlThrowEvent;
import interstellarTikiTiks.apexx.client.event.impl.ShieldDrainEvent;
import interstellarTikiTiks.apexx.client.module.BooleanSetting;
import interstellarTikiTiks.apexx.client.module.Category;
import interstellarTikiTiks.apexx.client.module.SubCategory;
import interstellarTikiTiks.apexx.client.module.ModeSetting;
import interstellarTikiTiks.apexx.client.module.Module;
import interstellarTikiTiks.apexx.client.module.SliderSetting;
import interstellarTikiTiks.apexx.client.util.ClickSimController;
import interstellarTikiTiks.apexx.client.util.ModuleAccess;
import interstellarTikiTiks.apexx.client.util.Notifier;
import interstellarTikiTiks.apexx.client.util.RotationManager;
import interstellarTikiTiks.apexx.client.util.combat.InteractSim;
import interstellarTikiTiks.apexx.client.util.combat.SlotSync;
import interstellarTikiTiks.apexx.client.util.combat.TickActionOrder;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.util.Hand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;


/**
 * Chompza Insta-Catch - one press throws an ender pearl almost straight up and catches it with a wind charge, so you
 * are launched and teleported in one motion. Offhand mode swaps the wind charge to your offhand with your own swap
 * key; Hotbar mode switches slots. Every gap (start, clicks, catch, swap-back) is a jittered human delay, the look-up
 * is a human-speed turn to a slightly varied pitch, and instant snaps are only available as a Blatant option.
 */
public class ChompzaInstaCatch extends Module implements interstellarTikiTiks.apexx.client.module.hooks.ClickSimulating {

    @Override
    public boolean isOneShot() {
        return true;
    }

    private final ModeSetting mode = new ModeSetting("Mode", "Offhand", "Hotbar")
            .setDescription("Offhand: the wind charge goes to your offhand with your own swap-hands key, the pearl is thrown from the main hand and the charge follows from the offhand, then everything is swapped back. Hotbar: the main hand switches from pearl to wind charge.");
    private final BooleanSetting autoAim = new BooleanSetting("Auto Aim", false)
            .setDescription("Turns your view up to the catch pitch before throwing. Off: the pearl and wind charge go wherever you are already looking.");
    private final SliderSetting startDelay = new SliderSetting("Start Delay (ms)", 40, 0, 300, 0)
            .withJitter(15)
            .setSection("Delays")
            .setDescription("Pause between your key press and the first action, so the sequence does not begin on the exact frame of the press.");
    private final SliderSetting clickDelay = new SliderSetting("Click Delay (ms)", 55, 0, 200, 0)
            .withJitter(15)
            .setSection("Delays")
            .setDescription("Gap between the rapid right clicks of the sequence. Below ~15 ms the clicks merge into one game tick.");
    private final SliderSetting catchDelay = new SliderSetting("Catch Delay (ms)", 60, 0, 250, 0)
            .withJitter(18)
            .setSection("Delays")
            .setDescription("Wait after the pearl leaves before the wind charge is used. Too long and the pearl lands before the catch; the vanilla item-use cooldown is always respected.");
    private final SliderSetting swapBackDelay = new SliderSetting("Swap Back Delay (ms)", 80, 0, 400, 0)
            .withJitter(25)
            .setSection("Delays")
            .setDescription("Wait after the last throw before your original items and slot are restored. Never in the tick of a throw.");
    private final SliderSetting catchRetryWindow = new SliderSetting("Catch Retry Window (ms)", 250, 0, 1000, 0)
            .setDescription("How long after the catch a wind charge use that did not take effect is retried (at vanilla's use cadence). 0 never retries.");
    private final BooleanSetting silent = new BooleanSetting("Silent Aim", false)
            .setDescription("Turns only the rotation sent to the server up to the catch pitch and keeps your camera still. Off: your camera visibly looks up, as a player's would.");
    private final SliderSetting pitchAngle = new SliderSetting("Pitch Angle", -85.0, -90.0, -45.0, 1)
            .setDescription("How far up (degrees) the throw is aimed. -90 is straight up; around -85 still catches cleanly and looks less mechanical.");
    private final SliderSetting pitchSpread = new SliderSetting("Pitch Spread", 3.0, 0.0, 10.0, 1)
            .setSection("Humanization")
            .setDescription("Random variation, in degrees, of the catch pitch each use so it is never the exact same angle.");
    private final SliderSetting aimSpeed = new SliderSetting("Aim Speed", 9, 1, 15, 1)
            .setSection("Humanization")
            .setDescription("How quickly Auto Aim looks up (1 slow - 15 fast). Around 8-11 is a quick but human flick.");
    private final SliderSetting aimHumanization = new SliderSetting("Aim Randomness", 0.35, 0.0, 1.0, 2)
            .setSection("Humanization")
            .setDescription("Human imperfection in the look-up path: curve, overshoot and settle. 0 = perfectly smooth.");
    private final BooleanSetting snapAim = new BooleanSetting("Snap Aim", false)
            .setBlatant(true)
            .setDescription("BLATANT: looks up at maximum speed with no imperfection and no start delay. Instant pitch snaps are easy for rotation checks to flag.");
    private final BooleanSetting doubleTap = new BooleanSetting("Double Tap", false)
            .setDescription("Uses a second wind charge right after the catch for extra height.");
    private final BooleanSetting pauseCombat = new BooleanSetting("Pause Aim/Mace", true)
            .setDescription("Pauses AimAssist and AutoMace while the catch runs so they do not fight over your aim or hotbar.");

    private float catchPitch = -85.0f;
    private long startReadyAtMs = 0L;
    private long clickGapMs = 55L;
    private int stage = 0;
    private long lastTime = 0;
    private int originalSlot = -1;
    private boolean swappedOffhand = false;
    private int offhandSourceSlot = -1;
    private long randomizedWindChargeDelay = 0;
    private long randomizedSwapBackDelay = 0;
    private long retryWindowMs = 250L;
    private long pearlThrownAt = 0;
    private float initialPitch = 0.0f;
    private float initialYaw = 0.0f;

    private static final long ASSIST_PAUSE_TTL_MS = 220L;
    // Live pitch error allowed before the pearl goes, and how long Auto Aim may take to get there.
    private static final float AIM_PITCH_TOLERANCE = 2.0f;
    private static final int MAX_AIM_TICKS = 20;
    private int aimWaitTicks = 0;
    // Waiting for a packet interval that allows the wind charge's slot to be selected for the swap-back.
    private static final int STAGE_FINISHING = 6;
    private static final int MAX_FINISH_WAIT_TICKS = 20;
    private int finishWaitTicks = 0;

    public ChompzaInstaCatch() {
        super("ChompzaInstaCatch", "One press throws an ender pearl almost straight up and catches it with a wind charge, launching you and teleporting you in one motion. Human delays and a varied look-up angle keep the sequence natural.", Category.COMBAT);
        setSubCategory(SubCategory.MACE);
        addSetting(mode);
        addSetting(autoAim);
        addSetting(startDelay);
        addSetting(clickDelay);
        addSetting(catchDelay);
        addSetting(swapBackDelay);
        addSetting(catchRetryWindow);
        addSetting(silent);
        addSetting(pitchAngle);
        addSetting(pitchSpread);
        addSetting(aimSpeed);
        addSetting(aimHumanization);
        addSetting(snapAim);
        addSetting(doubleTap);
        addSetting(pauseCombat);
    }

    @Override
    public void onEnable() {
        if (mc.player == null || mc.player.isUsingItem()) {
            toggle();
            return;
        }

        CombatSwapLockEvent lockEvent = new CombatSwapLockEvent();
        EventBus.post(lockEvent);
        if (lockEvent.isItemSwapLocked()) {
            toggle();
            return;
        }

        int pearlSlot = findItemSlot(Items.ENDER_PEARL);
        int windSlot = findItemSlot(Items.WIND_CHARGE);
        boolean isOffhandMode = "Offhand".equalsIgnoreCase(mode.getCurrentMode());

        if (!interstellarTikiTiks.apexx.client.util.combat.PearlLimit.canThrow(mc)) {
            Notifier.warn(getName(), "Pearl Limit reached");
            toggle();
            return;
        }
        if (pearlSlot == -1 || (windSlot == -1 && mc.player.getOffHandStack().getItem() != Items.WIND_CHARGE)) {
            Notifier.warn(getName(), "Missing Ender Pearl or Wind Charge");
            toggle();
            return;
        }

        EventBus.register(this);
        EventBus.post(new interstellarTikiTiks.apexx.client.event.impl.PearlModuleExecuteEvent("ChompzaInstaCatch"));
        stage = 0;
        lastTime = System.currentTimeMillis();
        originalSlot = -1;
        swappedOffhand = false;
        offhandSourceSlot = -1;
        finishWaitTicks = 0;
        initialPitch = mc.player.getPitch();
        initialYaw = mc.player.getYaw();
        double spread = snapAim.isEnabled() ? 0.0 : pitchSpread.getValue();
        catchPitch = (float) MathHelper.clamp(pitchAngle.getValue()
                + (java.util.concurrent.ThreadLocalRandom.current().nextDouble() * 2.0 - 1.0) * spread, -90.0, -45.0);

        if (autoAim.isEnabled()) {
            if (!silent.isEnabled()) {
                RotationManager.acquireExclusiveLock(this);
            }
            aimTowards(catchPitch);
        }

        startReadyAtMs = System.currentTimeMillis() + (snapAim.isEnabled() ? 0L : clampMs(startDelay.sample()));
        randomizedWindChargeDelay = clampMs(catchDelay.sample());
        randomizedSwapBackDelay = clampMs(swapBackDelay.sample());
        clickGapMs = clampMs(clickDelay.sample());
        retryWindowMs = clampMs(catchRetryWindow.getValue());

        pauseAssists();
    }

    @Override
    public void onTick() {
        if (mc.player == null || mc.world == null) {
            toggle();
            return;
        }
        ShieldDrainEvent drainEvent = new ShieldDrainEvent();
        EventBus.post(drainEvent);
        if (drainEvent.isActive()) {
            return;
        }

        pauseAssists();
        if (stage == STAGE_FINISHING) {
            finish();
            return;
        }
        aimTowards(catchPitch);

        boolean isOffhandMode = "Offhand".equalsIgnoreCase(mode.getCurrentMode());

        if (stage == 0) {
            if (System.currentTimeMillis() < startReadyAtMs) {
                return;
            }
            if (mc.player.isUsingItem()) {
                finish();
                return;
            }

            if (originalSlot == -1) {
                originalSlot = mc.player.getInventory().getSelectedSlot();
            }

            int pearlSlot = findItemSlot(Items.ENDER_PEARL);
            int windSlot = findItemSlot(Items.WIND_CHARGE);

            if (pearlSlot == -1 || (windSlot == -1 && mc.player.getOffHandStack().getItem() != Items.WIND_CHARGE)) {
                Notifier.warn(getName(), "Missing Ender Pearl or Wind Charge");
                finish();
                return;
            }

            if (isOffhandMode) {
                if (mc.player.getOffHandStack().getItem() != Items.WIND_CHARGE) {
                    if (!SlotSync.select(mc, windSlot)) {
                        return; // hotbar change not allowed yet this packet interval; retry next tick
                    }
                    offhandSourceSlot = windSlot;
                    interstellarTikiTiks.apexx.client.util.SystemInputSimulator.tapSwapHands();
                    swappedOffhand = true;
                    // The swap-hands key is handled later this tick with the wind charge still
                    // selected; the pearl slot is selected from the next tick on.
                    return;
                }
            }

            lastTime = System.currentTimeMillis();
            clickGapMs = clampMs(clickDelay.sample());
            aimWaitTicks = 0;
            stage = 1;
            return;
        }

        if (stage == 1) {
            if (System.currentTimeMillis() - lastTime < clickGapMs) {
                return;
            }
            // The pearl goes once the live rotation is on the catch pitch, with its slot selected in
            // the same tick as the throw.
            if (!aimReached()) {
                if (++aimWaitTicks > MAX_AIM_TICKS) {
                    finish();
                }
                return;
            }
            int pearlSlot = findItemSlot(Items.ENDER_PEARL);
            if (pearlSlot == -1) {
                finish();
                return;
            }
            if (!InteractSim.useReady(mc)) {
                return;
            }
            if (mc.player.getInventory().getSelectedSlot() != pearlSlot
                    && (!TickActionOrder.canSwitchSlot() || !SlotSync.select(mc, pearlSlot))) {
                return; // hotbar change not allowed yet this packet interval; retry next tick
            }

            EventBus.post(new PearlThrowEvent());

            doClick(Hand.MAIN_HAND);
            pearlThrownAt = System.currentTimeMillis();
            lastTime = pearlThrownAt;
            stage = 2;
            return;
        }

        if (stage == 2) {
            // The wind charge waits for its delay and for vanilla's use cooldown after the pearl. In
            // Hotbar mode its slot is selected in the same tick as the use; that selection is refused
            // (and retried) while the pearl throw still shares the packet interval.
            if (System.currentTimeMillis() - pearlThrownAt < randomizedWindChargeDelay || !InteractSim.useReady(mc)) {
                return;
            }
            if (!isOffhandMode) {
                int windSlot = findItemSlot(Items.WIND_CHARGE);
                if (windSlot == -1) {
                    finish();
                    return;
                }
                if (mc.player.getInventory().getSelectedSlot() != windSlot
                        && (!TickActionOrder.canSwitchSlot() || !SlotSync.select(mc, windSlot))) {
                    return;
                }
            }

            doClick(isOffhandMode ? Hand.OFF_HAND : Hand.MAIN_HAND);
            lastTime = System.currentTimeMillis();
            clickGapMs = clampMs(clickDelay.sample());
            stage = doubleTap.isEnabled() ? 4 : 5;
            return;
        }

        if (stage == 4) {
            // The second charge waits for the click gap and for vanilla's use cadence.
            if (System.currentTimeMillis() - lastTime >= clickGapMs && InteractSim.useReady(mc)) {
                doClick(isOffhandMode ? Hand.OFF_HAND : Hand.MAIN_HAND);
                lastTime = System.currentTimeMillis();
                clickGapMs = clampMs(clickDelay.sample());
                stage = 5;
            }
            return;
        }

        if (stage == 5) {
            // A catch the client has not seen take effect (no wind charge cooldown yet) is retried at
            // vanilla's use cadence, only inside the Catch Retry Window.
            boolean clicked = false;
            if (mc.player != null && !mc.player.getItemCooldownManager().isCoolingDown(Items.WIND_CHARGE.getDefaultStack())
                    && System.currentTimeMillis() - lastTime < retryWindowMs
                    && InteractSim.useReady(mc)) {
                doClick(isOffhandMode ? Hand.OFF_HAND : Hand.MAIN_HAND);
                clicked = true;
            }
            // Never released in a tick with a throw: the aim stays up through that tick.
            if (!clicked && System.currentTimeMillis() - lastTime >= randomizedSwapBackDelay) {
                finish();
            }
        }
    }

    @EventTarget
    public void onCombatSwapLock(CombatSwapLockEvent event) {
        if (isEnabled() && stage > 0) {
            event.setItemSwapLocked(true);
            event.setAutoAttackLocked(true);
        }
    }

    private void aimTowards(float targetPitch) {
        if (!autoAim.isEnabled() || mc.player == null) return;
        float yaw = mc.player != null ? mc.player.getYaw() : initialYaw;
        // Both modes step toward the catch pitch through the shared rotation engine; Silent Aim only
        // keeps the camera still while the server-side angles turn.
        double str = snapAim.isEnabled() ? 25.0 : aimSpeed.getValue();
        double imperfection = snapAim.isEnabled() ? 0.0 : aimHumanization.getValue();
        RotationManager.aimAt(this, RotationManager.Priority.HIGHEST, () -> getLookPoint(targetPitch, yaw), str, imperfection,
                silent.isEnabled());
    }

    /** The live rotation is on the catch pitch; always true without Auto Aim. */
    private boolean aimReached() {
        if (!autoAim.isEnabled()) return true;
        return Math.abs(RotationManager.getFinalPitch() - catchPitch) <= AIM_PITCH_TOLERANCE;
    }

    private static long clampMs(double ms) {
        return interstellarTikiTiks.apexx.client.util.combat.ModuleDelays.clampMs(ms);
    }

    private Vec3d getLookPoint(float pitch, float yaw) {
        if (mc.player == null) return Vec3d.ZERO;
        Vec3d lookVec = Vec3d.fromPolar(MathHelper.clamp(pitch, -89.9f, 89.9f), yaw);
        return mc.player.getEyePos().add(lookVec.multiply(100.0));
    }

    private void finish() {
        RotationManager.releaseExclusiveLock(this);
        RotationManager.clearTarget(this);
        RotationManager.stop(this);

        if (mc.player != null) {
            // Swap the wind charge back only when the source slot is really held; otherwise the
            // swap-hands key would move the wrong item. While that selection has to wait for the
            // next packet interval, the finish waits with it (up to MAX_FINISH_WAIT_TICKS).
            boolean swapHandsPending = false;
            if (swappedOffhand && offhandSourceSlot != -1) {
                if (SlotSync.select(mc, offhandSourceSlot)) {
                    interstellarTikiTiks.apexx.client.util.SystemInputSimulator.tapSwapHands();
                    swappedOffhand = false;
                    swapHandsPending = true;
                } else if (++finishWaitTicks <= MAX_FINISH_WAIT_TICKS) {
                    stage = STAGE_FINISHING;
                    return;
                }
            }
            if (originalSlot != -1 && mc.player.getInventory().getSelectedSlot() != originalSlot) {
                if (swapHandsPending) {
                    // The swap-hands key is handled later this tick; restoring now would put the
                    // original slot's item in the offhand. Restore once this tick's movement packet is out.
                    TickActionOrder.deferSlot(originalSlot, offhandSourceSlot);
                } else {
                    SlotSync.restore(mc, originalSlot);
                }
            }
        }
        originalSlot = -1;
        offhandSourceSlot = -1;
        swappedOffhand = false;
        stage = 0;
        toggle();
    }

    @Override
    public void onDisable() {
        EventBus.unregister(this);
        RotationManager.releaseExclusiveLock(this);
        RotationManager.clearTarget(this);
        RotationManager.stop(this);

        if (mc.player != null) {
            // Swap the wind charge back only when the source slot is really held; otherwise the
            // swap-hands key would move the wrong item. A blocked selection leaves it in the offhand.
            if (swappedOffhand && offhandSourceSlot != -1 && SlotSync.select(mc, offhandSourceSlot)) {
                interstellarTikiTiks.apexx.client.util.SystemInputSimulator.tapSwapHands();
                swappedOffhand = false;
            }
            if (originalSlot != -1 && mc.player.getInventory().getSelectedSlot() != originalSlot) {
                SlotSync.restore(mc, originalSlot);
            }
        }

        originalSlot = -1;
        offhandSourceSlot = -1;
        swappedOffhand = false;
        stage = 0;

        AimAssist aim = ModuleAccess.getModule(AimAssist.class);
        if (aim != null) aim.externalResume();
        AutoMace mace = ModuleAccess.getModule(AutoMace.class);
        if (mace != null) mace.externalResume();
    }

    @Override
    protected void onRuntimeStateReleased() {
        onDisable();
    }

    private void pauseAssists() {
        if (!pauseCombat.isEnabled()) return;
        AimAssist aim = ModuleAccess.getModule(AimAssist.class);
        if (aim != null) aim.externalPause(ASSIST_PAUSE_TTL_MS);
        AutoMace mace = ModuleAccess.getModule(AutoMace.class);
        if (mace != null) mace.externalPause(ASSIST_PAUSE_TTL_MS);
    }

    private void doClick(Hand hand) {
        TickActionOrder.recordUse();
        interstellarTikiTiks.apexx.client.util.ClickSimController.use(mc, true);
    }

    private int findItemSlot(Item item) {
        if (mc.player == null) return -1;
        for (int i = 0; i < 9; i++) {
            ItemStack stack = mc.player.getInventory().getStack(i);
            if (stack != null && !stack.isEmpty() && stack.getItem() == item) {
                return i;
            }
        }
        return -1;
    }

}
