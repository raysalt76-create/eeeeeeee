package interstellarTikiTiks.apexx.client.module.implementation.player;

import interstellarTikiTiks.apexx.client.event.EventBus;
import interstellarTikiTiks.apexx.client.event.EventTarget;
import interstellarTikiTiks.apexx.client.event.impl.CombatSwapLockEvent;
import interstellarTikiTiks.apexx.client.mixin.PlayerInventoryAccessor;
import interstellarTikiTiks.apexx.client.module.BooleanSetting;
import interstellarTikiTiks.apexx.client.module.Category;
import interstellarTikiTiks.apexx.client.module.ModeSetting;
import interstellarTikiTiks.apexx.client.module.Module;
import interstellarTikiTiks.apexx.client.module.SliderSetting;
import interstellarTikiTiks.apexx.client.module.hooks.ClickSimulating;
import interstellarTikiTiks.apexx.client.module.hooks.VelocityPacketAware;
import interstellarTikiTiks.apexx.client.module.implementation.player.clutch.ClutchSearchPlanner;
import interstellarTikiTiks.apexx.client.module.implementation.player.clutch.PlacementTarget;
import interstellarTikiTiks.apexx.client.util.RotationManager;
import interstellarTikiTiks.apexx.client.util.SystemInputSimulator;
import interstellarTikiTiks.apexx.client.util.combat.BlockPlacementValidator;
import interstellarTikiTiks.apexx.client.util.combat.InteractSim;
import interstellarTikiTiks.apexx.client.util.combat.ModuleDelays;
import interstellarTikiTiks.apexx.client.util.combat.SlotSync;
import interstellarTikiTiks.apexx.client.util.combat.SubTickSequencer;
import interstellarTikiTiks.apexx.client.util.combat.TickActionOrder;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.BlockItem;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;
import net.minecraft.world.attribute.EnvironmentAttributes;

/**
 * Clutch - the one fall-save module (the former ClutchMLG is merged in). Two engines share it:
 *
 * <ul>
 *   <li><b>Blocks</b>: a 3D placement planner catches void drops, lethal falls and knockback off edges
 *       with a block, a short bridge or a ladder on a wall.</li>
 *   <li><b>MLG</b>: a water-bucket style landing (water, powder snow, cobweb, slime, hay, wind charge) just
 *       before impact, with Auto Retract. The same engine also pours water against fire and in lava.</li>
 * </ul>
 *
 * <p>Fall Method picks Blocks, MLG, or Blocks + MLG (blocks first; the MLG landing when no block can be
 * placed). Only one engine owns the rotation and hotbar at a time: while an MLG sequence runs, the block
 * engine waits. Both engines swap only in the tick the live ray reaches the planned face/block and use the
 * item later in that same tick; swap backs are separate, later events and only while the slot is still ours.
 */
public class Clutch extends Module implements VelocityPacketAware, ClickSimulating {

    private static final String BLOCKS = "Blocks";
    private static final String MLG = "MLG";
    private static final String BOTH = "Blocks + MLG";
    private static final String MLG_SECTION = "MLG";
    private static final String FIRE_SECTION = "Fire";
    private static final String DELAYS = "Delays";

    private final ModeSetting fallMethod = new ModeSetting("Fall Method", BLOCKS, BLOCKS, MLG, BOTH)
            .setDescription("How a fall is saved. Blocks: places a block, a short bridge or a ladder to catch you. MLG: lands in water (or powder snow, cobweb, slime, hay, a wind charge) just before impact. Blocks + MLG: blocks first, the MLG landing when no block can be placed in time.");

    // ---- Block engine ----
    private final BooleanSetting onVoid = new BooleanSetting("On Void", true)
            .setDescription("Clutches when there is no solid block anywhere below you, i.e. you are falling into the void.");
    private final BooleanSetting onLethalFall = new BooleanSetting("On Lethal Fall", true)
            .setDescription("Clutches when you have fallen at least Min Fall Distance and are still dropping fast, to stop a damaging landing.");
    private final BooleanSetting onKnockback = new BooleanSetting("On Knockback", true)
            .setDescription("Clutches when a hit's knockback has carried you off an edge with a drop of 5+ blocks below.");
    private final SliderSetting minFallDist = new SliderSetting("Min Fall Distance", 3.0, 1.0, 20.0, 1)
            .setDescription("Blocks you must have fallen before On Lethal Fall reacts. Lower catches shorter drops; higher ignores harmless hops.")
            .setDependsOn("On Lethal Fall");
    private final SliderSetting maxReach = new SliderSetting("Max Reach", 4.5, 3.0, 4.5, 1)
            .setDescription("Farthest support block, in blocks, a clutch placement may use. Capped at the vanilla block-interaction reach of 4.5; the server rejects anything further.");
    private final SliderSetting maxDepth = new SliderSetting("Max Graph Depth", 2.0, 1.0, 3.0, 0)
            .setDescription("How many blocks deep the placement search may chain: 1 places directly against existing blocks only, 2-3 may bridge out with extra blocks first.");
    private final BooleanSetting autoLadder = new BooleanSetting("Auto Ladder", true)
            .setDescription("When a ladder is in your hotbar, prefers placing it on a nearby wall to grab onto instead of a block.");
    private final BooleanSetting silentRotations = new BooleanSetting("Silent Rotations", false)
            .setDescription("Turns only the angles sent to the server toward the placement face or landing block; your camera keeps its view. Off turns the camera like a player would.");
    private final ModeSetting rotationMode = new ModeSetting("Rotation Mode", "Smooth", "Sine", "Linear", "Instant", "Wind")
            .setDescription("How the aim moves onto a block placement face. Smooth, Sine, Linear and Wind ease in like a hand; Instant snaps in one step and is easy for anticheats to spot. MLG landings use their own fast flick.");
    private final SliderSetting aimStrength = new SliderSetting("Aim Strength", 100, 20, 100, 0)
            .setDescription("How fast the aim converges on the block clutch face. 100 is the fastest turn; too low and the fall can outrun it.");
    private final ModeSetting blockSelection = new ModeSetting("Block Selection", "First In Hotbar", "First In Hotbar", "Biggest Stack")
            .setDescription("Which hotbar block to clutch with: the leftmost usable one, or the largest stack.");
    private final BooleanSetting keystrokeSim = new BooleanSetting("Keystrokes HUD Sim", true)
            .setDescription("Lights up Right Click and Jump on keystroke-display mods for each block clutch, without sending any extra click.");

    // ---- MLG engine (the former ClutchMLG) ----
    private final SliderSetting mlgMinFallDist = new SliderSetting("MLG Min Fall Distance", 3.5, 2.0, 10.0, 1)
            .setDescription("How far you must already have fallen, in blocks, before an MLG landing is attempted. Lower saves smaller falls but may waste water on harmless drops.")
            .setSection(MLG_SECTION);
    private final BooleanSetting autoRetract = new BooleanSetting("Auto Retract", true)
            .setDescription("Scoops the MLG water back into the bucket once you have landed safely, so you keep the bucket and leave no water behind.")
            .setSection(MLG_SECTION);
    private final BooleanSetting autoSwitch = new BooleanSetting("Auto Switch", true)
            .setDescription("MLG, fire and lava: switch to the water bucket (or other clutch item) from your hotbar automatically. Off: only acts when you are already holding one.")
            .setSection(MLG_SECTION);
    private final SliderSetting mlgCooldown = new SliderSetting("MLG Cooldown (ms)", 350.0, 100.0, 1000.0, 0)
            .setDescription("Minimum time after a finished MLG landing before another one may start, so a bouncy landing does not trigger a second pour.")
            .setSection(MLG_SECTION);
    private final BooleanSetting swapBack = new BooleanSetting("Swap Back", true)
            .setDescription("MLG, fire and lava: reselects the hotbar slot you were holding before the clutch item once the sequence ends. Off keeps the bucket in hand.")
            .setSection(MLG_SECTION);
    private final BooleanSetting triggerInLava = new BooleanSetting("Trigger in Lava", false)
            .setDescription("Also pours water when you are standing in lava, turning nearby lava sources into obsidian so you can escape.")
            .setSection(MLG_SECTION);

    // ---- Fire (the former AutoExtinguish, via ClutchMLG) ----
    private final BooleanSetting triggerOnFire = new BooleanSetting("Trigger on Fire", false)
            .setDescription("Also places water at your feet when you are on fire, to put the flames out.")
            .setSection(FIRE_SECTION);
    private final BooleanSetting fireSkipNether = new BooleanSetting("Skip in Nether", true)
            .setDescription("Never pours water against fire where water evaporates (the Nether).")
            .setDependsOn("Trigger on Fire").setSection(FIRE_SECTION);
    private final BooleanSetting fireSkipResistance = new BooleanSetting("Skip with Fire Resistance", true)
            .setDescription("Does not pour water against fire while you have Fire Resistance, since the flames cannot hurt you.")
            .setDependsOn("Trigger on Fire").setSection(FIRE_SECTION);
    private final BooleanSetting fireSilentAim = new BooleanSetting("Fire Silent Aim", false)
            .setDescription("For fire pours only: turns just the angles sent to the server while the water is placed and picked up; your camera keeps its view. Off follows Silent Rotations.")
            .setDependsOn("Trigger on Fire").setSection(FIRE_SECTION);
    private final SliderSetting fireReactionDelay = new SliderSetting("Fire Reaction Delay (ms)", 120, 0, 1000, 0)
            .withJitter(40)
            .setDescription("How long you must be burning before the bucket comes out, like a player noticing the fire. 0 reacts on the first burning tick.")
            .setDependsOn("Trigger on Fire").setSection(FIRE_SECTION);
    private final SliderSetting firePickupDelay = new SliderSetting("Fire Pickup Delay (ms)", 120, 0, 1000, 0)
            .withJitter(30)
            .setDescription("Minimum time the water from a fire pour stays down before it is scooped back up (Auto Retract). Vanilla's item-use cooldown applies on top.")
            .setDependsOn("Trigger on Fire").setSection(FIRE_SECTION);
    private final SliderSetting fireCooldown = new SliderSetting("Fire Cooldown (ms)", 800, 0, 3000, 0)
            .withJitter(150)
            .setDescription("Rest time after a finished fire pour before another one can start.")
            .setDependsOn("Trigger on Fire").setSection(FIRE_SECTION);

    // ---- Delays ----
    private final SliderSetting reactionDelay = new SliderSetting("Reaction Delay (ms)", 40, 0, 250, 0)
            .withJitter(12)
            .setDescription("Time between noticing the fall and the first block clutch attempt.");
    private final SliderSetting placeDelay = new SliderSetting("Place Delay (ms)", 200, 0, 1000, 0)
            .withJitter(30)
            .setDescription("Minimum time between two block clutch placements. 200 ms matches vanilla's held right-click rate; lower is like fast clicking. Still at most one placement per tick.");
    private final SliderSetting swapDelay = new SliderSetting("Swap Delay (ms)", 8, 0, 40, 0)
            .withJitter(3)
            .setDescription("Gap between selecting the clutch block and placing it, inside the same tick.");
    private final SliderSetting swapBackDelay = new SliderSetting("Swap Back Delay (ms)", 200, 0, 1500, 0)
            .withJitter(40)
            .setDescription("Wait after a block clutch has landed you safely before your previous item is reselected.");
    private final SliderSetting postClutchCooldown = new SliderSetting("Re-Clutch Cooldown (ticks)", 6, 0, 40, 0)
            .withJitter(1)
            .setDescription("Game ticks to wait after landing before another block clutch can start.");
    private final SliderSetting knockbackWindow = new SliderSetting("Knockback Window (ticks)", 15, 1, 80, 0)
            .setDescription("For how many game ticks after you take knockback the On Knockback trigger stays armed.")
            .setDependsOn("On Knockback");
    private final SliderSetting mlgSwapDelay = new SliderSetting("MLG Swap Delay (ms)", 20, 0, 300, 0)
            .withJitter(5)
            .setDescription("Minimum wait after an MLG landing starts before the clutch item is selected and used. The swap still only happens in the tick the aim actually reaches the landing block, and the use follows it in that same tick.");
    private final SliderSetting retractDelay = new SliderSetting("Retract Delay (ms)", 30, 0, 500, 0)
            .withJitter(10)
            .setDescription("Minimum wait after the MLG water is placed before it is scooped back up; you must also be standing on the ground.");

    // ---- block engine state ----
    /** Slot held before the block engine swapped (-1 = never swapped) and the slot it selected. */
    private int originalSlot = -1;
    private int expectedSlot = -1;
    private long restoreAtMs = 0L;
    private boolean dispatchPending = false;
    private long lastPlaceTick = Long.MIN_VALUE;
    private boolean clutching = false;
    private int clutchedCooldownTicks = 0;
    private int recentKnockbackTicks = 0;
    private long nextPlaceAtMs = 0L;
    private long threatSinceMs = -1L;
    private long reactionMs = 0L;
    private volatile Vec3d aimPoint;

    // ---- MLG engine state ----
    private enum MlgState { IDLE, AIMING, PLACED, WAITING_RETRACT, RETRACTING, COOLDOWN }

    /** Longest an MLG aim (landing or retract) may run before the attempt is abandoned. */
    private static final long MLG_AIM_TIMEOUT_MS = 1000L;
    /** MLG legit flick speed (0-100 slider scale): smooth, but fast enough to land before impact. */
    private static final double MLG_FLICK_SPEED = 85.0;

    private MlgState mlgState = MlgState.IDLE;
    private int mlgOriginalSlot = -1;
    private int mlgClutchSlot = -1;
    private int mlgSwappedSlot = -1;
    private BlockPos placedWaterPos = null;
    private BlockHitResult mlgAimHit = null;
    private boolean mlgRotationRequested = false;
    private boolean mlgReleaseRotationNextTick = false;
    private long mlgRetractGateMs = 0L;
    private long mlgSwapGateMs = 0L;
    private long mlgStateStartTime = 0L;
    private long mlgLastClutchTime = 0L;
    private boolean mlgFireTriggered = false;
    private long mlgActiveCooldownMs = 0L;
    private long burningSinceMs = 0L;
    private long fireReactAtMs = 0L;

    public Clutch() {
        super("Clutch", "Saves you from deadly falls: when you drop into the void, fall far enough to take damage, or get knocked off an edge, it places a block, a short bridge or a ladder to catch you, or lands you in water (or powder snow, cobweb, slime, hay, a wind charge) just before impact and scoops the water back up. Fall Method picks blocks, the MLG landing, or both. It can also pour water to put out fire or escape lava. Every placement waits for the live aim to reach its target.", Category.PLAYER);
        reactionDelay.setSection(DELAYS);
        placeDelay.setSection(DELAYS);
        swapDelay.setSection(DELAYS);
        swapBackDelay.setSection(DELAYS);
        postClutchCooldown.setSection(DELAYS);
        knockbackWindow.setSection(DELAYS);
        mlgSwapDelay.setSection(DELAYS);
        retractDelay.setSection(DELAYS);
        addSetting(fallMethod);
        addSetting(onVoid);
        addSetting(onLethalFall);
        addSetting(onKnockback);
        addSetting(minFallDist);
        addSetting(maxReach);
        addSetting(maxDepth);
        addSetting(autoLadder);
        addSetting(silentRotations);
        addSetting(rotationMode);
        addSetting(aimStrength);
        addSetting(blockSelection);
        addSetting(keystrokeSim);
        addSetting(mlgMinFallDist);
        addSetting(autoRetract);
        addSetting(autoSwitch);
        addSetting(mlgCooldown);
        addSetting(swapBack);
        addSetting(triggerInLava);
        addSetting(triggerOnFire);
        addSetting(fireSkipNether);
        addSetting(fireSkipResistance);
        addSetting(fireSilentAim);
        addSetting(fireReactionDelay);
        addSetting(firePickupDelay);
        addSetting(fireCooldown);
        addSetting(reactionDelay);
        addSetting(placeDelay);
        addSetting(swapDelay);
        addSetting(swapBackDelay);
        addSetting(postClutchCooldown);
        addSetting(knockbackWindow);
        addSetting(mlgSwapDelay);
        addSetting(retractDelay);

        for (var s : java.util.List.of(onVoid, onLethalFall, onKnockback, maxReach, maxDepth, autoLadder,
                rotationMode, aimStrength, blockSelection, keystrokeSim, reactionDelay, placeDelay, swapDelay,
                swapBackDelay, postClutchCooldown)) {
            s.setVisibility(this::blocksEnabled);
        }
        minFallDist.setVisibility(() -> blocksEnabled() && onLethalFall.isEnabled());
        knockbackWindow.setVisibility(() -> blocksEnabled() && onKnockback.isEnabled());
        mlgMinFallDist.setVisibility(this::mlgFallsEnabled);
        mlgSwapDelay.setVisibility(() -> mlgFallsEnabled() || triggerOnFire.isEnabled() || triggerInLava.isEnabled());
    }

    private boolean blocksEnabled() {
        return !fallMethod.is(MLG);
    }

    private boolean mlgFallsEnabled() {
        return !fallMethod.is(BLOCKS);
    }

    @Override
    public void onEnable() {
        EventBus.register(this);
        resetState();
        mlgResetState();
    }

    @Override
    public void onDisable() {
        EventBus.unregister(this);
        releaseAll();
    }

    @Override
    protected void onRuntimeStateReleased() {
        releaseAll();
    }

    private void releaseAll() {
        SubTickSequencer.cancelOwner(this);
        dispatchPending = false;
        RotationManager.clearTarget(this);
        restoreNow();
        resetState();
        mlgRestoreOriginalSlot();
        mlgResetState();
        mlgRotationRequested = false;
        mlgReleaseRotationNextTick = false;
    }

    private void resetState() {
        RotationManager.clearTarget(this);
        // Never leave a simulated key held down (jump/use are pulsed during clutching).
        SystemInputSimulator.releaseJump();
        SystemInputSimulator.releaseUse();
        clutching = false;
        clutchedCooldownTicks = 0;
        recentKnockbackTicks = 0;
        nextPlaceAtMs = 0L;
        threatSinceMs = -1L;
        aimPoint = null;
        dispatchPending = false;
        lastPlaceTick = Long.MIN_VALUE;
    }

    @Override
    public void onVelocityPacket() {
        if (!isEnabled() || !onKnockback.isEnabled() || mc.player == null) return;
        recentKnockbackTicks = (int) knockbackWindow.getValue();
    }

    @EventTarget
    public void onCombatSwapLock(CombatSwapLockEvent event) {
        if (event.isRequestedBy(this)) return;
        if (dispatchPending || originalSlot >= 0 || mlgSwappedSlot >= 0) event.setItemSwapLocked(true);
    }

    @Override
    public void onTick() {
        if (!isEnabled() || mc.player == null || mc.world == null) return;
        long now = System.currentTimeMillis();

        // MLG engine first: it releases its own rotation one tick after a sequence, and while a sequence
        // runs (aiming, placed, retracting) it owns the rotation and hotbar, so the block engine waits.
        mlgPreTick(now);
        if (mlgBusy()) {
            mlgStep(now);
            return;
        }

        boolean blockPlanned = blocksEnabled() && blockTick(now);
        if (!blockPlanned && mc.player != null && mc.player.isAlive()) {
            mlgIdle(now, mlgFallsEnabled());
        }
    }

    // =========================================================================
    // BLOCK ENGINE
    // =========================================================================

    /** One block-engine tick; true while it is planning/placing a block this tick (MLG must not start). */
    private boolean blockTick(long now) {
        ClientPlayerEntity p = mc.player;
        // The sequencer drops work that did not run before its tick ended; never stay latched on it.
        if (dispatchPending && SubTickSequencer.currentTick() != lastPlaceTick) dispatchPending = false;
        pollRestore(now);

        if (recentKnockbackTicks > 0) recentKnockbackTicks--;
        if (clutchedCooldownTicks > 0) clutchedCooldownTicks--;

        if (mc.currentScreen != null || !p.isAlive() || p.isSpectator() || p.hasVehicle() || p.isGliding()) {
            if (clutching) endClutch(now);
            threatSinceMs = -1L;
            return false;
        }

        if (p.isOnGround() || p.isTouchingWater() || p.isClimbing()) {
            if (clutching) endClutch(now);
            threatSinceMs = -1L;
            return false;
        }

        if (clutchedCooldownTicks > 0) return false;

        if (!isThreat()) {
            threatSinceMs = -1L;
            return false;
        }
        if (threatSinceMs < 0L) {
            threatSinceMs = now;
            reactionMs = ModuleDelays.clampMs(reactionDelay.sample());
        }
        // Still reacting: keep MLG from jumping in before the block engine has had its chance.
        if (now - threatSinceMs < reactionMs) return true;

        boolean checkLadders = autoLadder.isEnabled() && findHotbar(Items.LADDER) != -1;
        double reachLimit = Math.min(maxReach.getValue(), p.getBlockInteractionRange());
        PlacementTarget target = ClutchSearchPlanner.findBestClutchTarget(mc.world, p, reachLimit,
                (int) maxDepth.getValue(), checkLadders);

        if (target != null) {
            int slot = target.isLadder() ? findHotbar(Items.LADDER) : findBlockSlot();
            if (slot != -1) {
                executeClutch(target, slot);
                return true;
            }
        }
        return false;
    }

    private boolean isThreat() {
        if (onVoid.isEnabled() && isFallingIntoVoid()) return true;
        if (onLethalFall.isEnabled() && mc.player.fallDistance >= minFallDist.getValue()
                && mc.player.getVelocity().y < -0.55) {
            return true;
        }
        return onKnockback.isEnabled() && recentKnockbackTicks > 0
                && mc.player.getVelocity().y < -0.2 && isAboveDrop();
    }

    private void endClutch(long now) {
        clutching = false;
        aimPoint = null;
        RotationManager.clearTarget(this);
        scheduleRestore(now);
        clutchedCooldownTicks = (int) Math.round(postClutchCooldown.sample());
    }

    private RotationManager.RotationMode mode() {
        try {
            return RotationManager.RotationMode.valueOf(rotationMode.getCurrentMode().toUpperCase());
        } catch (IllegalArgumentException e) {
            return RotationManager.RotationMode.SMOOTH;
        }
    }

    /** 100 keeps the original full-speed clutch turn; lower values ease the turn through the shared strength scale. */
    private double aimSpeed() {
        double strength = aimStrength.getValue();
        return strength >= 100.0 ? 100.0
                : RotationManager.engineStrength(strength, RotationManager.STRENGTH_SCALE_TRACK);
    }

    private void aimAt(Vec3d point) {
        aimPoint = point;
        // The request stays alive across ticks while clutching so a smooth turn can actually reach
        // the face; it is released on landing or reset, never in the tick of a placement.
        RotationManager.setRotationSupplier(this, RotationManager.Priority.HIGHEST,
                () -> aimPoint, aimSpeed(), mode(),
                0.0, 0L, silentRotations.isEnabled(), false);
    }

    private boolean placeReady() {
        return !dispatchPending && SubTickSequencer.currentTick() != lastPlaceTick
                && System.currentTimeMillis() >= nextPlaceAtMs;
    }

    private void executeClutch(PlacementTarget target, int blockSlot) {
        clutching = true;

        Vec3d hitVec = target.getHitVec();
        BlockHitResult bhr = new BlockHitResult(hitVec, target.getSide(), target.getSupportPos(), false);
        boolean silent = silentRotations.isEnabled();
        aimAt(hitVec);
        if (mc.interactionManager == null || !placeReady()) return;

        // Validation raycasts the manager's live silent angle when the silent setting is on,
        // or the current camera pose otherwise. Only swap and dispatch when that current ray
        // already reaches the planned support face.
        BlockHitResult liveHit = BlockPlacementValidator.validateHit(mc, bhr, silent);
        if (liveHit == null) return;

        double gap = 0.0;
        int held = mc.player.getInventory().getSelectedSlot();
        if (held != blockSlot) {
            if (otherModuleHoldsSwap() || !SlotSync.select(mc, blockSlot)) return;
            if (originalSlot < 0) originalSlot = held;
            gap = Math.max(0.0, swapDelay.sample());
        }
        expectedSlot = blockSlot;
        restoreAtMs = 0L;

        dispatchPending = true;
        lastPlaceTick = SubTickSequencer.currentTick();
        final boolean pulseJump = target.isLadder() || target.getDepth() > 0;
        SubTickSequencer.queueAction(this, () -> {
            try {
                if (mc.player == null || mc.world == null || mc.interactionManager == null) return;
                ItemStack heldStack = mc.player.getMainHandStack();
                if (!(heldStack.getItem() instanceof BlockItem) && !heldStack.isOf(Items.LADDER)) return;
                // Re-cast at dispatch: still the same clickable face.
                BlockHitResult now = BlockPlacementValidator.validateHit(mc, bhr, silent);
                if (now == null) return;
                SlotSync.paceAfterSwap();
                mc.interactionManager.interactBlock(mc.player, Hand.MAIN_HAND, now);
                mc.player.swingHand(Hand.MAIN_HAND);
                nextPlaceAtMs = System.currentTimeMillis() + ModuleDelays.clampMs(placeDelay.sample());
                RotationManager.setPostAttackHold(this, 1, RotationManager.getFinalYaw(), RotationManager.getFinalPitch());

                // The real placement above is the only use action. For Keystrokes HUD visibility,
                // illuminate RMB WITHOUT dispatching a second vanilla use (tapUseSilent suppresses it).
                if (keystrokeSim.isEnabled()) {
                    SystemInputSimulator.tapUseSilent();
                    if (pulseJump) SystemInputSimulator.tapJump();
                }
            } finally {
                dispatchPending = false;
            }
        }, gap);
    }

    /** Arms the block engine's swap back as a separate, later event (only if it swapped). */
    private void scheduleRestore(long now) {
        if (originalSlot < 0) return;
        restoreAtMs = now + Math.max(0L, Math.round(swapBackDelay.sample()));
    }

    private void pollRestore(long now) {
        if (originalSlot < 0 || restoreAtMs <= 0L || dispatchPending || now < restoreAtMs) return;
        restoreNow();
    }

    /** Swaps back if the block engine still owns the selected slot; otherwise just forgets it. */
    private void restoreNow() {
        if (originalSlot >= 0 && expectedSlot >= 0 && originalSlot != expectedSlot && mc.player != null
                && mc.player.getInventory().getSelectedSlot() == expectedSlot) {
            SlotSync.restore(mc, originalSlot);
        }
        originalSlot = -1;
        expectedSlot = -1;
        restoreAtMs = 0L;
    }

    /** Another module (shield break, mace chain, cart cover, ...) owns the hotbar right now. */
    private boolean otherModuleHoldsSwap() {
        CombatSwapLockEvent lock = new CombatSwapLockEvent(this);
        EventBus.post(lock);
        return lock.isItemSwapLocked();
    }

    private boolean isFallingIntoVoid() {
        if (mc.player.getY() < 0) return true;
        BlockPos playerPos = mc.player.getBlockPos();
        for (int y = playerPos.getY(); y >= mc.world.getBottomY(); y--) {
            BlockPos check = new BlockPos(playerPos.getX(), y, playerPos.getZ());
            BlockState state = mc.world.getBlockState(check);
            if (!state.isAir() && state.getBlock() != Blocks.VOID_AIR && state.isSolidBlock(mc.world, check)) {
                return false;
            }
        }
        return true;
    }

    private boolean isAboveDrop() {
        BlockPos playerPos = mc.player.getBlockPos();
        for (int y = playerPos.getY(); y >= playerPos.getY() - 4; y--) {
            BlockPos check = new BlockPos(playerPos.getX(), y, playerPos.getZ());
            if (mc.world.getBlockState(check).isSolidBlock(mc.world, check)) return false;
        }
        return true;
    }

    private boolean isClutchBlock(ItemStack stack) {
        return !stack.isEmpty() && stack.getItem() instanceof BlockItem bi
                && bi.getBlock().getDefaultState().isSolidBlock(mc.world, BlockPos.ORIGIN);
    }

    private int findBlockSlot() {
        PlayerInventory inv = mc.player.getInventory();
        if ("Biggest Stack".equals(blockSelection.getCurrentMode())) {
            int held = inv.getSelectedSlot();
            int best = -1;
            int bestCount = 0;
            for (int i = 0; i < 9; i++) {
                ItemStack stack = inv.getStack(i);
                if (!isClutchBlock(stack)) continue;
                int count = stack.getCount();
                // Ties keep the held slot so the hotbar does not flicker.
                if (count > bestCount || (count == bestCount && i == held)) {
                    best = i;
                    bestCount = count;
                }
            }
            return best;
        }
        for (int i = 0; i < 9; i++) {
            if (isClutchBlock(inv.getStack(i))) return i;
        }
        return -1;
    }

    private int findHotbar(Item item) {
        if (mc.player == null) return -1;
        PlayerInventory inv = mc.player.getInventory();
        for (int i = 0; i < 9; i++) {
            if (inv.getStack(i).isOf(item)) return i;
        }
        return -1;
    }

    // =========================================================================
    // MLG ENGINE (water / powder snow / cobweb / slime / hay / wind charge; fire and lava pours)
    // =========================================================================

    private boolean mlgBusy() {
        return mlgState != MlgState.IDLE && mlgState != MlgState.COOLDOWN;
    }

    /** Rotation release, death reset, burn tracking and the cooldown countdown; runs every tick. */
    private void mlgPreTick(long now) {
        // A rotation is released one tick after the sequence that used it ends, so the angle the
        // action went out on is never cleared mid-tick.
        if (mlgReleaseRotationNextTick) {
            mlgReleaseRotationNextTick = false;
            mlgRotationRequested = false;
            if (!clutching) RotationManager.clearTarget(this);
        }
        if (!mc.player.isAlive()) {
            mlgRestoreOriginalSlot();
            mlgResetState();
            return;
        }
        trackBurning(now);
        if (mlgState == MlgState.COOLDOWN && now - mlgStateStartTime >= mlgActiveCooldownMs) {
            mlgState = MlgState.IDLE;
        }
    }

    /** Starts an MLG sequence when a fall (if allowed), fire or lava calls for one. */
    private void mlgIdle(long now, boolean fallsAllowed) {
        if (mlgState != MlgState.IDLE) return;
        if (now - mlgLastClutchTime < mlgActiveCooldownMs) return;

        boolean fire = shouldFireClutch();
        boolean lava = shouldLavaClutch();
        boolean falling = fallsAllowed && isFallingDangerously();
        if (!falling && !fire && !lava) return;

        int slot = findClutchItemSlot(fire || lava);
        if (slot == -1) {
            warnMissing("items", "No water bucket or clutch item in hotbar");
            return;
        }
        if (!autoSwitch.isEnabled() && slot != currentSlot()) return;

        BlockHitResult targetHit = findLandingTarget();
        if (targetHit == null) return;

        // Take over from a block clutch that could not finish: its swapped slot becomes ours to restore.
        if (clutching) {
            clutching = false;
            if (originalSlot >= 0 && mlgOriginalSlot < 0) {
                mlgOriginalSlot = originalSlot;
                // The block slot it swapped to is ours now too, so an MLG that ends without its own swap still restores.
                if (expectedSlot >= 0 && mlgSwappedSlot < 0) mlgSwappedSlot = expectedSlot;
            }
            originalSlot = -1;
            expectedSlot = -1;
            restoreAtMs = 0L;
        }
        if (mlgOriginalSlot == -1) mlgOriginalSlot = currentSlot();
        mlgFireTriggered = fire && !falling && !lava;
        mlgClutchSlot = slot;
        mlgAimHit = targetHit;
        aimPoint = targetHit.getPos();
        mlgSwapGateMs = ModuleDelays.clampMs(mlgSwapDelay.sample());
        mlgState = MlgState.AIMING;
        mlgStateStartTime = now;
        // Aim this tick; the swap and the place follow once the live ray reaches the landing face.
        mlgRequestAim();
    }

    private void mlgStep(long now) {
        switch (mlgState) {
            case AIMING -> {
                boolean fallsAllowed = mlgFallsEnabled();
                if (now - mlgStateStartTime > MLG_AIM_TIMEOUT_MS
                        || (!(fallsAllowed && isFallingDangerously()) && !shouldFireClutch() && !shouldLavaClutch())
                        || !isMlgItem(mc.player.getInventory().getStack(mlgClutchSlot))) {
                    mlgAbort();
                    return;
                }
                BlockHitResult fresh = findLandingTarget();
                if (fresh != null) {
                    mlgAimHit = fresh;
                    aimPoint = fresh.getPos();
                }
                mlgRequestAim();
                if (now - mlgStateStartTime >= mlgSwapGateMs) mlgTryAction(mlgAimHit);
            }
            case PLACED -> {
                if (autoRetract.isEnabled() && placedWaterPos != null) {
                    mlgState = MlgState.WAITING_RETRACT;
                    mlgStateStartTime = now;
                    mlgRetractGateMs = ModuleDelays.clampMs(mlgFireTriggered ? firePickupDelay.sample() : retractDelay.sample());
                } else {
                    mlgFinish();
                }
            }
            case WAITING_RETRACT -> {
                if (now - mlgStateStartTime >= mlgRetractGateMs && mc.player.isOnGround()) {
                    mlgState = MlgState.RETRACTING;
                    mlgStateStartTime = now;
                    aimPoint = Vec3d.ofCenter(placedWaterPos);
                    mlgRequestAim();
                }
            }
            case RETRACTING -> {
                if (placedWaterPos == null || now - mlgStateStartTime > MLG_AIM_TIMEOUT_MS
                        || !mc.world.getFluidState(placedWaterPos).isStill()) {
                    mlgFinish();
                    return;
                }
                mlgRequestAim();
                mlgTryRetract();
            }
            default -> {
            }
        }
    }

    private boolean shouldFireClutch() {
        if (!triggerOnFire.isEnabled() || !mc.player.isOnFire() || mc.player.isTouchingWater()) return false;
        if (mc.currentScreen != null || mc.player.isSpectator()) return false;
        if (fireSkipResistance.isEnabled() && mc.player.hasStatusEffect(StatusEffects.FIRE_RESISTANCE)) return false;
        if (fireSkipNether.isEnabled() && waterEvaporates(mc.player.getBlockPos())) return false;
        return burningSinceMs != 0L && System.currentTimeMillis() >= fireReactAtMs;
    }

    /** Tracks how long the current burn has lasted, for the fire reaction delay. */
    private void trackBurning(long now) {
        if (!mc.player.isOnFire()) {
            burningSinceMs = 0L;
            return;
        }
        if (burningSinceMs == 0L) {
            burningSinceMs = now;
            fireReactAtMs = now + ModuleDelays.clampMs(fireReactionDelay.sample());
        }
    }

    private boolean waterEvaporates(BlockPos pos) {
        return Boolean.TRUE.equals(mc.world.getEnvironmentAttributes()
                .getAttributeValue(EnvironmentAttributes.WATER_EVAPORATES_GAMEPLAY, pos));
    }

    private boolean shouldLavaClutch() {
        return triggerInLava.isEnabled() && mc.player.isInLava();
    }

    private boolean isFallingDangerously() {
        if (mc.player == null || mc.world == null) return false;
        if (mc.currentScreen != null || mc.player.isSpectator() || mc.player.hasVehicle()) return false;
        if (mc.player.getAbilities().flying || mc.player.isGliding()) return false;
        if (mc.player.fallDistance < mlgMinFallDist.getValue() && mc.player.getVelocity().y > -0.7) return false;

        Vec3d eye = mc.player.getEyePos();
        Vec3d end = eye.add(0, -4.5, 0);
        BlockHitResult hit = mc.world.raycast(new RaycastContext(eye, end, RaycastContext.ShapeType.COLLIDER,
                RaycastContext.FluidHandling.NONE, mc.player));
        return hit != null && hit.getType() == HitResult.Type.BLOCK;
    }

    private BlockHitResult findLandingTarget() {
        if (mc.player == null || mc.world == null) return null;
        Vec3d eye = mc.player.getEyePos();
        Vec3d vel = mc.player.getVelocity();
        Vec3d end = eye.add(vel.x * 2.0, -4.5, vel.z * 2.0);
        BlockHitResult hit = mc.world.raycast(new RaycastContext(eye, end, RaycastContext.ShapeType.COLLIDER,
                RaycastContext.FluidHandling.NONE, mc.player));
        if (hit != null && hit.getType() == HitResult.Type.BLOCK) return hit;

        BlockPos downPos = mc.player.getBlockPos().down();
        BlockState state = mc.world.getBlockState(downPos);
        if (!state.isAir() && state.isSolidBlock(mc.world, downPos)) {
            return new BlockHitResult(new Vec3d(downPos.getX() + 0.5, downPos.getY() + 1.0, downPos.getZ() + 0.5),
                    Direction.UP, downPos, false);
        }
        return null;
    }

    private boolean mlgSilent() {
        return silentRotations.isEnabled() || (mlgFireTriggered && fireSilentAim.isEnabled());
    }

    /** (Re)issues the MLG aim at {@link #aimPoint}. Legit is a smooth flick; silent snaps the server rotation. */
    private void mlgRequestAim() {
        if (mc.player == null || aimPoint == null) return;
        mlgRotationRequested = true;
        if (mlgSilent()) {
            RotationManager.setRotationSupplier(this, RotationManager.Priority.HIGHEST, () -> aimPoint,
                    100.0, RotationManager.RotationMode.INSTANT, 0.0, 0L, true, false);
        } else {
            RotationManager.setRotationSupplier(this, RotationManager.Priority.HIGHEST, () -> aimPoint,
                    RotationManager.engineStrength(MLG_FLICK_SPEED, RotationManager.STRENGTH_SCALE_FLICK),
                    RotationManager.RotationMode.SMOOTH, 0.0, 0L, false, false);
        }
    }

    /**
     * One MLG attempt. Acts only when the current wire rotation's ray already reaches the landing block;
     * then selects the clutch slot and uses it later in the same tick. Nothing is swapped when the action
     * cannot follow (ray off target, use throttled, or an action already left this interval).
     */
    private void mlgTryAction(BlockHitResult target) {
        if (target == null || mc.player == null) return;
        ItemStack stack = mc.player.getInventory().getStack(mlgClutchSlot);
        boolean isWater = stack.isOf(Items.WATER_BUCKET);
        // Buckets and wind charges are item uses (the server raycasts the rotation itself); the
        // rest are block placements validated against the exact face by InteractSim.place.
        boolean itemUse = isWater || stack.isOf(Items.WIND_CHARGE);

        BlockHitResult live = wireRay(RaycastContext.FluidHandling.NONE);
        if (live == null || !live.getBlockPos().equals(target.getBlockPos())) return;
        if (!itemUse && live.getSide() != target.getSide()) return;
        if (!InteractSim.useReady(mc)) return;
        if (!mlgSelectForAction(mlgClutchSlot)) return;

        boolean done;
        if (itemUse) {
            done = InteractSim.useItem(mc, Hand.MAIN_HAND, wireYaw(), wirePitch(), true);
            if (done && isWater) placedWaterPos = live.getBlockPos().offset(live.getSide());
        } else {
            done = InteractSim.place(mc, target, mlgSilent());
        }
        if (done) mlgState = MlgState.PLACED;
    }

    /** Scoops the placed source back up once a fluid-aware wire ray reaches it; select and use share the tick. */
    private void mlgTryRetract() {
        if (mc.player == null || placedWaterPos == null) return;
        int bucketSlot = findHotbar(Items.BUCKET);
        if (bucketSlot == -1 || (bucketSlot != currentSlot() && !autoSwitch.isEnabled())) {
            mlgFinish();
            return;
        }
        BlockHitResult live = wireRay(RaycastContext.FluidHandling.SOURCE_ONLY);
        if (live == null || !live.getBlockPos().equals(placedWaterPos)) return;
        if (!InteractSim.useReady(mc)) return;
        if (!mlgSelectForAction(bucketSlot)) return;
        if (InteractSim.useItem(mc, Hand.MAIN_HAND, wireYaw(), wirePitch(), true)) mlgFinish();
    }

    /** Selects {@code slot} for an action this tick; false (and no swap) when the interval no longer allows one. */
    private boolean mlgSelectForAction(int slot) {
        if (slot == currentSlot()) return true;
        if (!autoSwitch.isEnabled() || !TickActionOrder.canSwitchSlot()) return false;
        if (!SlotSync.select(mc, slot) || currentSlot() != slot) return false;
        mlgSwappedSlot = slot;
        return true;
    }

    /** Block ray along the rotation the server receives (silent final angles, else the player's own). */
    private BlockHitResult wireRay(RaycastContext.FluidHandling fluid) {
        if (mc.player == null || mc.world == null) return null;
        Vec3d eye = mc.player.getEyePos();
        Vec3d look = RotationManager.isSilentRotationActive()
                ? RotationManager.getSilentRotationVec()
                : mc.player.getRotationVec(1.0F);
        Vec3d end = eye.add(look.multiply(mc.player.getBlockInteractionRange()));
        BlockHitResult hit = mc.world.raycast(new RaycastContext(eye, end,
                RaycastContext.ShapeType.OUTLINE, fluid, mc.player));
        return hit != null && hit.getType() == HitResult.Type.BLOCK ? hit : null;
    }

    private float wireYaw() {
        return RotationManager.isSilentRotationActive() ? RotationManager.getFinalYaw() : mc.player.getYaw();
    }

    private float wirePitch() {
        return RotationManager.isSilentRotationActive() ? RotationManager.getFinalPitch() : mc.player.getPitch();
    }

    private void mlgAbort() {
        mlgRestoreOriginalSlot();
        mlgResetState();
    }

    private void mlgFinish() {
        mlgRestoreOriginalSlot();
        long now = System.currentTimeMillis();
        mlgActiveCooldownMs = mlgFireTriggered ? Math.max(0L, Math.round(fireCooldown.sample())) : (long) mlgCooldown.getValue();
        if (mlgFireTriggered) burningSinceMs = 0L;
        mlgResetState();
        mlgLastClutchTime = now;
        mlgState = MlgState.COOLDOWN;
        mlgStateStartTime = now;
    }

    private void mlgResetState() {
        if (mlgRotationRequested) mlgReleaseRotationNextTick = true;
        mlgState = MlgState.IDLE;
        mlgFireTriggered = false;
        mlgOriginalSlot = -1;
        mlgClutchSlot = -1;
        mlgSwappedSlot = -1;
        placedWaterPos = null;
        mlgAimHit = null;
        mlgStateStartTime = 0L;
        if (!clutching) aimPoint = null;
    }

    private boolean isMlgItem(ItemStack stack) {
        return stack.isOf(Items.WATER_BUCKET) || stack.isOf(Items.POWDER_SNOW_BUCKET) || stack.isOf(Items.COBWEB)
                || stack.isOf(Items.SLIME_BLOCK) || stack.isOf(Items.HAY_BLOCK) || stack.isOf(Items.WIND_CHARGE);
    }

    private int findClutchItemSlot(boolean waterOnly) {
        int waterSlot = findHotbar(Items.WATER_BUCKET);
        if (waterSlot != -1 || waterOnly) return waterSlot;
        for (Item item : new Item[]{Items.POWDER_SNOW_BUCKET, Items.COBWEB, Items.SLIME_BLOCK, Items.HAY_BLOCK,
                Items.WIND_CHARGE}) {
            int slot = findHotbar(item);
            if (slot != -1) return slot;
        }
        return -1;
    }

    private int currentSlot() {
        return ((PlayerInventoryAccessor) mc.player.getInventory()).getSelectedSlot();
    }

    /**
     * Hands the hotbar back to the slot held before the MLG sequence, only while the slot it selected is still
     * held. SlotSync.restore defers the select to the next interval when an action already left in this one.
     */
    private void mlgRestoreOriginalSlot() {
        if (swapBack.isEnabled() && mc.player != null && mlgOriginalSlot != -1 && mlgSwappedSlot != -1
                && currentSlot() == mlgSwappedSlot && mlgOriginalSlot != mlgSwappedSlot) {
            SlotSync.restore(mc, mlgOriginalSlot);
        }
        mlgOriginalSlot = -1;
        mlgSwappedSlot = -1;
    }
}
