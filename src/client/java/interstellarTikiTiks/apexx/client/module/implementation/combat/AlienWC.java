package interstellarTikiTiks.apexx.client.module.implementation.combat;

import interstellarTikiTiks.apexx.client.module.BooleanSetting;
import interstellarTikiTiks.apexx.client.module.Category;
import interstellarTikiTiks.apexx.client.module.ModeSetting;
import interstellarTikiTiks.apexx.client.module.Module;
import interstellarTikiTiks.apexx.client.module.SliderSetting;
import interstellarTikiTiks.apexx.client.module.SubCategory;
import interstellarTikiTiks.apexx.client.util.ClickSimController;
import interstellarTikiTiks.apexx.client.util.RotationManager;
import interstellarTikiTiks.apexx.client.util.SystemInputSimulator;
import interstellarTikiTiks.apexx.client.util.combat.AlienWCController;
import interstellarTikiTiks.apexx.client.util.combat.AlienWCSolver;
import interstellarTikiTiks.apexx.client.util.combat.AlienWCSupport;
import interstellarTikiTiks.apexx.client.util.combat.InteractSim;
import interstellarTikiTiks.apexx.client.util.combat.ModuleDelays;
import interstellarTikiTiks.apexx.client.util.combat.SlotSync;
import interstellarTikiTiks.apexx.client.util.combat.SubTickSequencer;
import interstellarTikiTiks.apexx.client.util.combat.TickActionOrder;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.item.Items;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.Vec3d;

import java.util.function.Supplier;

/**
 * AlienHighCharge (elytra and wind-charge launches): the ground launch and the mid-glide rebound in one module.
 *
 * <p><b>Ground Launch</b> is a one-key combo: sprint, jump, put the elytra on, open the glide, dive at the floor,
 * throw a wind charge straight down and let the blast carry the glide up (the shared {@link AlienWCController}
 * dive-boost, Manual or Dynamic). <b>Air Rebound</b> is the continuous mid-glide relaunch: while you glide toward
 * the ground it looks down, fires a wind charge into the floor at the right height, then pulls up so the blast
 * launches you back into the air, over and over. <b>Both</b> runs the ground launch once and then keeps rebounding,
 * chaining into continuous bounces.
 *
 * <p><b>Activation: Auto</b> (formerly the separate AutoAlienWC module) keeps the module on and runs the dive-boost by
 * itself whenever an active glide heads for the floor: look down, throw a wind charge at the floor, pull back up and
 * hand the hotbar straight back. It never starts a glide, and any slot change that is not this module's ends the run
 * without a restore; no new run starts until that glide is over.
 */
public class AlienWC extends Module implements interstellarTikiTiks.apexx.client.module.hooks.ClickSimulating {

    private static final float LOCK_PITCH = 86.5f;
    private static final double CLEARANCE = 0.10;
    private static final int MAX_EQUIP_WAIT_TICKS = 10;
    private static final long RUN_TIMEOUT_MS = 12_000L;

    /** Auto activation's dive-boost lock pitch and clearance (the values the former AutoAlienWC used). */
    private static final float AUTO_LOCK_PITCH = 85.0f;
    private static final double AUTO_CLEARANCE = 0.2;
    /** Auto activation only consults the solver when a floor is within this many blocks below the glide. */
    private static final double AUTO_SOLVER_RANGE = 48.0;

    private enum Phase { INITIATE, SPRINTING, JUMPING, EQUIP_ELYTRA, WAIT_EQUIP, GLIDE_GROUND_CHECK }

    private final ModeSetting activation = new ModeSetting("Activation", "Key", "Key", "Auto")
            .setDescription("Key: the module runs the Behavior below when you turn it on (the ground launch ends by itself). "
                    + "Auto: stays on and, whenever you glide toward the floor with an elytra, looks down at the right moment, "
                    + "throws a wind charge into the ground and rides the blast back up, then hands your hotbar straight back. "
                    + "Any slot change you make yourself cancels an automatic run.");
    private final ModeSetting behavior = new ModeSetting("Behavior", "Both", "Ground Launch", "Air Rebound", "Both")
            .setDependsOn("Activation:Key")
            .setDescription("Ground Launch: the one-press take-off from the ground (sprint, jump, elytra, dive, wind charge). "
                    + "Air Rebound: while already gliding, keep firing a wind charge off the floor to relaunch. "
                    + "Both: launch from the ground once, then keep rebounding so you chain continuous bounces.");
    private final SliderSetting aimSpeed = new SliderSetting("Aim Speed", 20.0, 1.0, 30.0, 1)
            .setDescription("How fast the camera dives toward the floor and pulls back up on the ground launch (about 5 degrees per tick per point). "
                    + "Too slow and the dive misses the ground window; 18-24 is a natural, reliable flick.");
    private final ModeSetting mode = new ModeSetting("Mode", "Manual", "Dynamic")
            .setVisibility(this::usesDiveBoost)
            .setDescription("Manual: dives straight away and throws the wind charge once you are inside the ground window. "
                    + "Dynamic: simulates your glide and the blast to pick the exact tick to look down and the tick to throw, "
                    + "giving a higher, more consistent launch.");
    private final SliderSetting groundThreshold = new SliderSetting("Ground Threshold", 1.1, 0.8, 1.8, 2)
            .setVisibility(this::usesDiveBoost)
            .setDescription("Height above the floor, in blocks, at which the wind charge is thrown in Manual mode "
                    + "(the fallback window in Dynamic). Lower gives a stronger boost but less room for error.");
    private final BooleanSetting silentAim = new BooleanSetting("Silent Aim", false)
            .setDescription("Sends the ground-launch look-down and pull-up only to the server and leaves your screen steady. "
                    + "Off moves your real camera, which is the most natural-looking option.");

    // ---- Air Rebound ----
    private final BooleanSetting spamJump = new BooleanSetting("Spam Jump", true)
            .setDependsOn("Activation:Key,Behavior:Air Rebound|Both")
            .setDescription("Taps jump as you near the ground so the elytra keeps gliding through the wind charge blast instead of landing.");
    private final SliderSetting downAngle = new SliderSetting("Down Angle", -80.0, -90.0, 0.0, 1)
            .setDependsOn("Activation:Key,Behavior:Air Rebound|Both")
            .setDescription("How far down you look, in degrees, when the wind charge is fired at the floor. Steeper gives a more vertical boost.");
    private final SliderSetting aimDownSpeed = new SliderSetting("Aim Down Speed", 80.0, 1.0, 100.0, 1)
            .setDependsOn("Activation:Key,Behavior:Air Rebound|Both")
            .setDescription("How fast the camera turns down toward the floor (1-100). Too slow can miss the release window.");
    private final SliderSetting upAngle = new SliderSetting("Up Angle", 45.0, 0.0, 90.0, 1)
            .setDependsOn("Activation:Key,Behavior:Air Rebound|Both")
            .setDescription("Angle above the horizon, in degrees, you pull up to after the blast so the glide keeps climbing.");
    private final SliderSetting aimUpSpeed = new SliderSetting("Aim Up Speed", 90.0, 1.0, 100.0, 1)
            .setDependsOn("Activation:Key,Behavior:Air Rebound|Both")
            .setDescription("How fast the camera pulls back up after the blast (1-100).");
    private final SliderSetting armDistance = new SliderSetting("Arm Distance", 5.0, 1.0, 15.0, 1)
            .setDependsOn("Activation:Key,Behavior:Air Rebound|Both")
            .setDescription("Height above the ground, in blocks, at which the rebound arms and starts aiming down.");
    private final SliderSetting triggerDistance = new SliderSetting("Trigger Distance", 0.7, 0.1, 5.0, 2)
            .setDependsOn("Activation:Key,Behavior:Air Rebound|Both")
            .setDescription("Height above the ground, in blocks, at which the rebound wind charge is released. Lower is a stronger boost with less margin.");

    // ---- Delays ----
    private final SliderSetting sprintLeadIn = new SliderSetting("Sprint Lead-In (ms)", 30, 0, 300, 0)
            .withJitter(8)
            .setDependsOn("Activation:Key,Behavior:Ground Launch|Both")
            .setDescription("How long you sprint forward before the jump, in milliseconds. A short lead-in builds launch speed "
                    + "and looks like a real run-up; 0 jumps immediately.")
            .setSection("Delays");
    private final SliderSetting jumpSettle = new SliderSetting("Jump Settle (ms)", 100, 0, 400, 0)
            .withJitter(15)
            .setDependsOn("Activation:Key,Behavior:Ground Launch|Both")
            .setDescription("Wait after the jump before the elytra is equipped, in milliseconds. The elytra is never put on "
                    + "before you have actually left the ground, whatever this is set to; around 100 ms is reliable.")
            .setSection("Delays");
    private final SliderSetting triggerDelay = new SliderSetting("Trigger Delay (ms)", 30, 0, 120, 0)
            .withJitter(8)
            .setDependsOn("Activation:Key,Behavior:Air Rebound|Both")
            .setDescription("Wait between being aimed down at the rebound release height and swapping to the wind charge, in ms. "
                    + "You are falling fast at this point, so it is capped at 120 ms; longer would hit the ground first.")
            .setSection("Delays");
    private final SliderSetting pullUpDelay = new SliderSetting("Pull-Up Delay (ms)", 30, 0, 300, 0)
            .withJitter(8)
            .setDependsOn("Activation:Key,Behavior:Air Rebound|Both")
            .setDescription("Wait after the rebound wind charge fires before pulling the camera back up, in ms (rounded to ticks). "
                    + "A short pause lets the blast lift you before you climb.")
            .setSection("Delays");
    private final SliderSetting swapBack = new SliderSetting("Swap Back Delay (ms)", 300, 0, 1000, 0)
            .withJitter(40)
            .setDescription("How long the wind-charge slot stays held after the throw before the hotbar goes back to the slot "
                    + "you had, in ms (rounded to ticks). The swap back never happens in the same tick as the throw.")
            .setSection("Delays");
    private final SliderSetting reopenDelay = new SliderSetting("Re-open Delay (ms)", 50, 0, 500, 0)
            .withJitter(10)
            .setVisibility(this::usesDiveBoost)
            .setDescription("Wait after the dive-boost throw (ground launch or Auto) before a jump press re-opens wings the blast closed, in ms.")
            .setSection("Delays");
    private final SliderSetting reopenInterval = new SliderSetting("Re-open Interval (ms)", 250, 0, 1000, 0)
            .withJitter(30)
            .setVisibility(this::usesDiveBoost)
            .setDescription("Minimum time between two re-open jump presses after the dive-boost throw (ground launch or Auto), in ms.")
            .setSection("Delays");
    private final SliderSetting runCooldown = new SliderSetting("Run Cooldown (ms)", 500, 0, 3000, 0)
            .withJitter(80)
            .setDependsOn("Activation:Auto")
            .setDescription("Auto: minimum time between two automatic dive-boosts, in ms, so a bumpy landing does not trigger "
                    + "a second boost straight away.")
            .setSection("Delays");

    // Ground-launch state.
    private Phase phase = Phase.INITIATE;
    private AlienWCController dive;
    private Object boundPlayer;
    private Object boundWorld;
    private int elytraSlot = -1;
    private int wcSlot = -1;
    private int initialSlot = -1;
    private long stateTimestamp;
    private long startedAt;
    private long sprintDelayMs;
    private long jumpSettleMs;
    private int equipWaitCounter;
    private boolean equipQueued;
    /** True once the ground launch is done (or skipped in Air Rebound); the rebound watcher then runs. */
    private boolean groundDone;

    // Air-rebound state.
    private int stage = 0;
    /** True while the rebound's spam-jump is the one holding the jump key. */
    private boolean reboundJumpPressed = false;
    private static final int REBOUND_AIM_TIMEOUT_TICKS = 60;
    private int ticksInStage = 0;
    private int returnSlot = -1;
    private double targetGroundY = -1;
    private long executionReadyTime = -1;
    private long triggerDelayMs = 0L;
    private int pullUpTicks = 0;
    /** The wind-charge slot the rebound selected, the player age at its throw and the hold before the swap back. */
    private int reboundChargeSlot = -1;
    private int reboundThrowAge = 0;
    private int reboundHoldTicks = 0;

    // Auto-activation state.
    /** Whether this enable runs the Auto activation; a change of the setting while on restarts cleanly. */
    private boolean enabledAsAuto;
    private AlienWCController autoDive;
    private long autoLastRunAt = 0L;
    private long autoCooldownMs;
    /** The wind-charge slot the auto run selected, and the slot held at the end of the last tick. */
    private int autoChargeSlot = -1;
    private int autoHeldLastTick = -1;
    /** The player (or another module) took the hotbar mid-glide: stay out until the glide ends. */
    private boolean autoYielded;

    public AlienWC() {
        super("AlienHighCharge", "One module for elytra and wind-charge launches: Ground Launch takes off from the floor with one press (sprint, jump, "
                + "elytra, dive, wind charge), Air Rebound keeps firing a wind charge off the ground to relaunch while you glide, and "
                + "Both chains them into continuous bounces.",
                Category.COMBAT);
        setSubCategory(SubCategory.MACE);
        addSetting(activation);
        addSetting(behavior);
        addSetting(aimSpeed);
        addSetting(mode);
        addSetting(groundThreshold);
        addSetting(silentAim);
        addSetting(spamJump);
        addSetting(downAngle);
        addSetting(aimDownSpeed);
        addSetting(upAngle);
        addSetting(aimUpSpeed);
        addSetting(armDistance);
        addSetting(triggerDistance);
        addSetting(sprintLeadIn);
        addSetting(jumpSettle);
        addSetting(triggerDelay);
        addSetting(pullUpDelay);
        addSetting(swapBack);
        addSetting(reopenDelay);
        addSetting(reopenInterval);
        addSetting(runCooldown);
    }

    /** Mode, Ground Threshold and the re-open delays drive the shared dive-boost: the ground launch or Auto. */
    private boolean usesDiveBoost() {
        return activation.is("Auto") || behavior.is("Ground Launch") || behavior.is("Both");
    }

    @Override
    public boolean isOneShot() {
        // Only the pure ground launch ends on its own; Air Rebound, Both and Auto stay on until toggled off.
        // Module's constructor asks before this class's settings exist; the default (Key + Both) is not one-shot.
        if (activation == null || behavior == null) return false;
        return activation.is("Key") && behavior.is("Ground Launch");
    }

    @Override
    public void onEnable() {
        phase = Phase.INITIATE;
        dive = null;
        equipQueued = false;
        equipWaitCounter = 0;
        startedAt = System.currentTimeMillis();
        boundPlayer = mc.player;
        boundWorld = mc.world;
        stage = 0;
        ticksInStage = 0;
        returnSlot = -1;
        targetGroundY = -1;
        executionReadyTime = -1;
        enabledAsAuto = activation.is("Auto");
        autoYielded = false;

        if (enabledAsAuto) {
            // Auto: no take-off; the auto watcher runs each tick.
            groundDone = true;
        } else if (behavior.is("Air Rebound")) {
            // No take-off: go straight to the mid-glide rebound watcher.
            groundDone = true;
        } else {
            groundDone = false;
            if (mc.player == null || mc.world == null || mc.currentScreen != null || AlienWCController.isBusy()) {
                toggle();
            }
        }
    }

    @Override
    public void onDisable() {
        // Cleanup happens in onRuntimeStateReleased.
    }

    @Override
    public void onTick() {
        if (activation.is("Auto") != enabledAsAuto) {
            // Activation changed while on: drop whatever was running and start over in the new activation.
            releaseRuntimeState();
            onEnable();
            return;
        }
        if (enabledAsAuto) {
            autoTick();
            return;
        }
        if (mc.player == null || mc.world == null) {
            if (!groundDone) toggle();
            return;
        }

        if (!groundDone) {
            // Ground-launch state machine. The timeout only guards the take-off, not the open-ended rebound.
            if (mc.player != boundPlayer || mc.world != boundWorld
                    || System.currentTimeMillis() - startedAt > RUN_TIMEOUT_MS) {
                toggle();
                return;
            }
            switch (phase) {
                case INITIATE -> handleInitiate();
                case SPRINTING -> handleSprinting();
                case JUMPING -> handleJumping();
                case EQUIP_ELYTRA -> handleEquipElytra();
                case WAIT_EQUIP -> handleWaitEquip();
                case GLIDE_GROUND_CHECK -> handleGlideGroundCheck();
            }
            return;
        }

        // Air-rebound watcher.
        if (!(behavior.is("Air Rebound") || behavior.is("Both"))) {
            toggle();
            return;
        }
        reboundTick();
    }

    // ---- ground launch -----------------------------------------------------------------------------

    private boolean wearingElytra() {
        return mc.player.getEquippedStack(EquipmentSlot.CHEST).isOf(Items.ELYTRA);
    }

    private void handleInitiate() {
        boolean wearing = wearingElytra();
        elytraSlot = AlienWCSupport.findHotbarSlot(Items.ELYTRA);
        wcSlot = AlienWCSupport.findWindChargeSlot();

        if (wcSlot == -1 || (!wearing && elytraSlot == -1)) {
            toggle();
            return;
        }

        initialSlot = SlotSync.current(mc);

        // Already gliding in an elytra: no sprint, jump or equip.
        if (wearing && mc.player.isGliding()) {
            enterGlideGroundCheck();
            return;
        }

        sprintDelayMs = ModuleDelays.clampMs(sprintLeadIn.sample());
        jumpSettleMs = ModuleDelays.clampMs(jumpSettle.sample());
        holdForwardAndSprint();
        stateTimestamp = System.currentTimeMillis();
        phase = Phase.SPRINTING;
    }

    private void handleSprinting() {
        holdForwardAndSprint();
        if (System.currentTimeMillis() - stateTimestamp >= sprintDelayMs) {
            SystemInputSimulator.tapJump();
            stateTimestamp = System.currentTimeMillis();
            phase = Phase.JUMPING;
        }
    }

    private void handleJumping() {
        holdForwardAndSprint();
        // Leave the ground before the elytra goes on (a physics gate, separate from the settle delay).
        if (!mc.player.isOnGround() && System.currentTimeMillis() - stateTimestamp >= jumpSettleMs) {
            phase = Phase.EQUIP_ELYTRA;
        }
    }

    private void handleEquipElytra() {
        holdForwardAndSprint();

        if (wearingElytra()) {
            restoreFromElytraSlot();
            if (!mc.player.isGliding()) SystemInputSimulator.tapJump();
            enterGlideGroundCheck();
            return;
        }

        if (equipQueued) return;
        if (SlotSync.current(mc) != elytraSlot && !selectSlot(elytraSlot)) return;
        if (!InteractSim.useReady(mc)) return;

        // Right-click the elytra from the hotbar to wear it; the use follows the swap inside this tick.
        equipQueued = true;
        SubTickSequencer.queueAction(this, () -> {
            if (mc.player == null || SlotSync.current(mc) != elytraSlot || !InteractSim.useReady(mc)) {
                equipQueued = false;
                return;
            }
            ClickSimController.useNow(mc, false);
            TickActionOrder.recordUse();
            equipWaitCounter = 0;
            phase = Phase.WAIT_EQUIP;
        }, SubTickSequencer.sampleMicroDelayMs());
    }

    private void handleWaitEquip() {
        holdForwardAndSprint();
        equipWaitCounter++;

        if (wearingElytra()) {
            // The elytra slot goes back (deferred past this interval's use); the wind-charge slot is only
            // selected in the throw tick by the dive controller. The glide was not open yet; a jump opens it.
            restoreFromElytraSlot();
            SystemInputSimulator.tapJump();
            enterGlideGroundCheck();
        } else if (equipWaitCounter >= MAX_EQUIP_WAIT_TICKS) {
            toggle();
        }
    }

    /** Swaps back off the elytra slot this run selected; owner-checked, deferred by SlotSync when needed. */
    private void restoreFromElytraSlot() {
        if (initialSlot >= 0 && elytraSlot >= 0 && elytraSlot != initialSlot
                && SlotSync.current(mc) == elytraSlot) {
            SlotSync.restore(mc, initialSlot);
        }
    }

    private void enterGlideGroundCheck() {
        AlienWCController.Config cfg = new AlienWCController.Config(
                mode.is("Dynamic"), aimSpeed.getValue(), groundThreshold.getValue(), silentAim.getValue(),
                LOCK_PITCH, CLEARANCE, true, true,
                ModuleDelays.msToTicks(swapBack.sample()),
                ModuleDelays.clampMs(reopenDelay.sample()),
                ModuleDelays.clampMs(reopenInterval.sample()));
        dive = new AlienWCController(this, cfg, wcSlot, initialSlot);
        phase = Phase.GLIDE_GROUND_CHECK;
    }

    private void handleGlideGroundCheck() {
        holdForwardAndSprint();
        if (dive == null || dive.tick()) {
            finishGroundLaunch();
        }
    }

    /** Ends the take-off: in Both, hand off to the rebound watcher without disabling the module; otherwise stop. */
    private void finishGroundLaunch() {
        if (behavior.is("Both")) {
            if (dive != null) {
                dive.finish();
                dive = null;
            }
            SystemInputSimulator.releaseForward();
            SystemInputSimulator.releaseSprint();
            SystemInputSimulator.releaseJump();
            RotationManager.clearTarget(this);
            stage = 0;
            ticksInStage = 0;
            returnSlot = -1;
            reboundChargeSlot = -1;
            targetGroundY = -1;
            executionReadyTime = -1;
            groundDone = true;
        } else {
            toggle();
        }
    }

    private boolean selectSlot(int slot) {
        if (SlotSync.current(mc) == slot) return true;
        if (!TickActionOrder.canSwitchSlot()) return false;
        SlotSync.select(mc, slot);
        return SlotSync.current(mc) == slot;
    }

    private void holdForwardAndSprint() {
        if (mc.options == null) return;
        if (!mc.options.forwardKey.isPressed()) SystemInputSimulator.pressForward();
        if (!mc.options.sprintKey.isPressed()) SystemInputSimulator.pressSprint();
    }

    // ---- auto activation ---------------------------------------------------------------------------

    private void autoTick() {
        if (mc.player == null || mc.world == null) {
            endAutoRun();
            return;
        }
        if (!mc.player.isGliding() || mc.player.isOnGround()) autoYielded = false;

        if (autoDive != null) {
            int held = SlotSync.current(mc);
            // The run only changes the hotbar twice: to the charge slot in the throw tick, and back to the
            // slot it came from after the charge has been thrown (the charge is then on its cooldown).
            // Any other change is someone else's choice.
            boolean ownSelect = held == autoChargeSlot;
            boolean ownRestore = autoHeldLastTick == autoChargeSlot && held == autoDive.restoreSlot()
                    && !AlienWCSupport.isChargeReady(autoChargeSlot);
            boolean takenAway = held != autoHeldLastTick && !ownSelect && !ownRestore;
            if (takenAway) {
                autoYielded = true;
                endAutoRun();
                return;
            }
            if (mc.player != boundPlayer || mc.world != boundWorld || autoDive.tick()) {
                endAutoRun();
                return;
            }
            autoHeldLastTick = SlotSync.current(mc);
            return;
        }

        if (autoYielded || !mc.player.isGliding() || mc.currentScreen != null || AlienWCController.isBusy()) return;
        if (System.currentTimeMillis() - autoLastRunAt < autoCooldownMs) return;

        // Heading down toward a floor, not climbing or flat.
        Vec3d vel = mc.player.getVelocity();
        if (vel.y >= -0.05) return;

        double floorDist = AlienWCSupport.floorDistance();
        if (floorDist == Double.MAX_VALUE || floorDist > AUTO_SOLVER_RANGE) return;

        int chargeSlot = AlienWCSupport.findWindChargeSlot();
        if (chargeSlot < 0 || !AlienWCSupport.isChargeReady(chargeSlot)) return;

        boolean dynamic = mode.is("Dynamic");
        // Start only when the look-down itself is due, so the slot is taken for the dive and nothing more.
        if (dynamic) {
            if (!AlienWCSolver.shouldStartLookDown(aimSpeed.getValue(), AUTO_LOCK_PITCH, AUTO_CLEARANCE,
                    groundThreshold.getValue())) return;
        } else if (floorDist > autoLookDownDistance()) {
            return;
        }

        AlienWCController.Config cfg = new AlienWCController.Config(
                dynamic, aimSpeed.getValue(), groundThreshold.getValue(), silentAim.getValue(),
                AUTO_LOCK_PITCH, AUTO_CLEARANCE, false, false,
                ModuleDelays.msToTicks(swapBack.sample()),
                ModuleDelays.clampMs(reopenDelay.sample()),
                ModuleDelays.clampMs(reopenInterval.sample()));
        boundPlayer = mc.player;
        boundWorld = mc.world;
        autoLastRunAt = System.currentTimeMillis();
        autoCooldownMs = Math.max(0L, Math.round(runCooldown.sample()));
        autoChargeSlot = chargeSlot;
        autoHeldLastTick = SlotSync.current(mc);
        autoDive = new AlienWCController(this, cfg, chargeSlot, SlotSync.current(mc));
        if (autoDive.tick()) {
            endAutoRun();
            return;
        }
        autoHeldLastTick = SlotSync.current(mc);
    }

    /**
     * Floor distance at which the auto look-down must start for the pitch to reach the lock before the
     * ground window: the ticks the turn needs times the (growing) fall per tick, as the controller uses.
     */
    private double autoLookDownDistance() {
        double pitchStep = Math.max(0.5, aimSpeed.getValue() * 100.0 * 0.05);
        double remaining = Math.max(0.0, AUTO_LOCK_PITCH - RotationManager.getFinalPitch());
        int ticks = (int) Math.ceil(remaining / pitchStep) + 1;
        return groundThreshold.getValue() + ticks * Math.max(AlienWCSupport.descentPerTick(), 0.3) * 1.5;
    }

    /** Ends the current auto run (owner-checked restore via the controller) without leaving Auto. */
    private void endAutoRun() {
        if (autoDive != null) {
            // finish() only restores the original slot while the charge slot is still selected, so a
            // slot the player picked is never overwritten.
            autoDive.finish();
            autoDive = null;
        }
        SlotSync.releaseOwner(this);
        RotationManager.clearTarget(this);
        boundPlayer = boundWorld = null;
        autoChargeSlot = -1;
        autoHeldLastTick = -1;
    }

    // ---- air rebound -------------------------------------------------------------------------------

    private void reboundTick() {
        if (stage > 0) {
            ticksInStage++;

            if (stage == 1) {
                // Aiming down. Gives up when the glide ended or the dive never reached the trigger.
                if (!mc.player.isGliding() || ticksInStage > REBOUND_AIM_TIMEOUT_TICKS) {
                    endRebound();
                    return;
                }
                // Keep the floor under the glide current, in case the player climbed or moved over new terrain.
                HitResult floorHit = mc.player.raycast(armDistance.getValue(), 1.0f, false);
                if (floorHit.getType() == HitResult.Type.BLOCK) {
                    targetGroundY = ((BlockHitResult) floorHit).getBlockPos().getY() + 1.0;
                }
                float targetPitch = (float) -downAngle.getValue();
                aimAtPitch(targetPitch, aimDownSpeed.getValue());

                float currentPitch = RotationManager.getFinalPitch();
                boolean pitchReached = Math.abs(currentPitch - targetPitch) <= 2.0f || ticksInStage > 20;
                double nextY = mc.player.getY() + mc.player.getVelocity().y;
                boolean heightReached = targetGroundY != -1 && (nextY - targetGroundY <= triggerDistance.getValue());

                if (executionReadyTime != -1) {
                    if (System.currentTimeMillis() - executionReadyTime >= triggerDelayMs) {
                        stage = 2;
                        ticksInStage = 0;
                        executionReadyTime = -1;
                    }
                } else if (pitchReached && heightReached) {
                    executionReadyTime = System.currentTimeMillis();
                    triggerDelayMs = ModuleDelays.clampMs(triggerDelay.sample());
                    if (System.currentTimeMillis() - executionReadyTime >= triggerDelayMs) {
                        stage = 2;
                        ticksInStage = 0;
                        executionReadyTime = -1;
                    }
                }
            } else if (stage == 2) {
                // Swap and fire, only once the charge can actually be thrown and the swap is committed,
                // so the hotbar never changes without the use that justifies it.
                int windChargeSlot = findWindCharge();
                if (windChargeSlot == -1) {
                    warnMissing("wind", "No wind charges in hotbar");
                    endRebound();
                    return;
                }
                boolean fired = false;
                if (AlienWCSupport.isChargeReady(windChargeSlot)) {
                    int held = SlotSync.current(mc);
                    boolean holding = held == windChargeSlot
                            || (TickActionOrder.canSwitchSlot() && SlotSync.select(mc, windChargeSlot)
                                    && SlotSync.current(mc) == windChargeSlot);
                    if (holding) {
                        if (held != windChargeSlot) reboundChargeSlot = windChargeSlot;
                        ClickSimController.pulseUse(mc);
                        fired = true;
                    }
                }
                if (!fired) {
                    // Retry next tick; give up before the floor arrives.
                    if (ticksInStage > 3) endRebound();
                    return;
                }
                stage = 3;
                ticksInStage = 0;
                reboundThrowAge = mc.player.age;
                reboundHoldTicks = ModuleDelays.msToTicks(swapBack.sample());
                pullUpTicks = ModuleDelays.msToTicks(pullUpDelay.sample());
            } else if (stage == 3) {
                // Aiming up fast.
                float targetPitch = (float) -upAngle.getValue();
                aimAtPitch(targetPitch, aimUpSpeed.getValue());

                if (spamJump.isEnabled()) {
                    mc.options.jumpKey.setPressed(ticksInStage % 2 == 0);
                    reboundJumpPressed = true;
                }

                if (ticksInStage >= pullUpTicks) {
                    stage = 4;
                    ticksInStage = 0;
                }
            } else if (stage == 4) {
                // Swap back once the hold has passed (never in the throw's own call), then end this rebound
                // (the watcher below arms the next one).
                releaseOwnJump();
                if (mc.player.age - reboundThrowAge < reboundHoldTicks) return;
                endRebound();
            }
            return;
        }

        if (mc.player.isGliding()) {
            HitResult hitResult = mc.player.raycast(armDistance.getValue(), 1.0f, false);
            if (hitResult.getType() == HitResult.Type.BLOCK && ((BlockHitResult) hitResult).getPos().y < mc.player.getY()) {
                if (findWindCharge() != -1) {
                    returnSlot = ((interstellarTikiTiks.apexx.client.mixin.PlayerInventoryAccessor) mc.player.getInventory()).getSelectedSlot();
                    targetGroundY = ((BlockHitResult) hitResult).getBlockPos().getY() + 1.0;
                    stage = 1;
                    ticksInStage = 0;
                }
            }
        }
    }

    /** Releases the jump key only if this module's spam-jump is the one holding it. */
    private void releaseOwnJump() {
        if (reboundJumpPressed && mc.options != null) {
            mc.options.jumpKey.setPressed(false);
        }
        reboundJumpPressed = false;
    }

    /** Owner-checked swap back off the rebound's charge slot, then reset the rebound stage. */
    private void endRebound() {
        restoreReboundSlot();
        releaseOwnJump();
        RotationManager.clearTarget(this);
        stage = 0;
        ticksInStage = 0;
        returnSlot = -1;
        reboundChargeSlot = -1;
        targetGroundY = -1;
        executionReadyTime = -1;
    }

    private void restoreReboundSlot() {
        if (mc.player != null && returnSlot != -1 && reboundChargeSlot != -1 && returnSlot != reboundChargeSlot
                && SlotSync.current(mc) == reboundChargeSlot) {
            SlotSync.restore(mc, returnSlot);
        }
    }

    private void aimAtPitch(float pitch, double speedPct) {
        Supplier<Vec3d> sup = () -> {
            if (mc.player == null) return null;
            float yaw = mc.player.getYaw();
            Vec3d lookVec = Vec3d.fromPolar(pitch, yaw);
            return mc.player.getEyePos().add(lookVec.multiply(10.0));
        };

        double engineStrength = RotationManager.engineStrength(speedPct, RotationManager.STRENGTH_SCALE_FLICK);
        RotationManager.setRotationSupplier(
                this,
                RotationManager.Priority.HIGHEST,
                sup,
                engineStrength,
                RotationManager.RotationMode.SMOOTH,
                0.0,
                false,
                false);
    }

    private int findWindCharge() {
        for (int i = 0; i < 9; i++) {
            if (mc.player.getInventory().getStack(i).getItem() == Items.WIND_CHARGE) {
                return i;
            }
        }
        return -1;
    }

    @Override
    protected void onRuntimeStateReleased() {
        // Auto-activation cleanup.
        if (autoDive != null) {
            autoDive.finish();
            autoDive = null;
        }
        autoChargeSlot = -1;
        autoHeldLastTick = -1;
        // Ground-launch cleanup.
        if (dive != null) {
            // finish() only swaps back while the charge slot it selected is still held.
            dive.finish();
            dive = null;
        }
        if (mc.player != null && initialSlot >= 0 && mc.player == boundPlayer
                && SlotSync.current(mc) == elytraSlot && elytraSlot != initialSlot) {
            SlotSync.restore(mc, initialSlot);
        }
        // Air-rebound cleanup: only off the charge slot the rebound itself selected.
        if (stage > 0) {
            restoreReboundSlot();
        }
        releaseOwnJump();

        RotationManager.clearTarget(this);
        SystemInputSimulator.releaseForward();
        SystemInputSimulator.releaseSprint();
        SystemInputSimulator.releaseJump();
        phase = Phase.INITIATE;
        equipQueued = false;
        groundDone = false;
        stage = 0;
        ticksInStage = 0;
        returnSlot = -1;
        reboundChargeSlot = -1;
        targetGroundY = -1;
        executionReadyTime = -1;
        boundPlayer = boundWorld = null;
        initialSlot = elytraSlot = wcSlot = -1;
    }
}
