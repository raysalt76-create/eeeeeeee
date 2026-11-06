package interstellarTikiTiks.apexx.client.module.implementation.bedwars;

import interstellarTikiTiks.apexx.client.event.EventBus;
import interstellarTikiTiks.apexx.client.event.EventTarget;
import interstellarTikiTiks.apexx.client.event.impl.CombatSwapLockEvent;
import interstellarTikiTiks.apexx.client.module.BooleanSetting;
import interstellarTikiTiks.apexx.client.module.Category;
import interstellarTikiTiks.apexx.client.module.ColorSetting;
import interstellarTikiTiks.apexx.client.module.ModeSetting;
import interstellarTikiTiks.apexx.client.module.Module;
import interstellarTikiTiks.apexx.client.module.SliderSetting;
import interstellarTikiTiks.apexx.client.module.hooks.MovementInputModifier;
import interstellarTikiTiks.apexx.client.util.IApexxRenderer;
import interstellarTikiTiks.apexx.client.util.PlaceResolver;
import interstellarTikiTiks.apexx.client.util.PlaceSolution;
import interstellarTikiTiks.apexx.client.util.Renderer;
import interstellarTikiTiks.apexx.client.util.RotationManager;
import interstellarTikiTiks.apexx.client.util.SystemInputSimulator;
import interstellarTikiTiks.apexx.client.util.combat.BlockPlacementValidator;
import interstellarTikiTiks.apexx.client.util.combat.ModuleDelays;
import interstellarTikiTiks.apexx.client.util.render.RenderUtils;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.util.BufferAllocator;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.Hand;
import net.minecraft.util.PlayerInput;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;

import java.awt.Color;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Scaffold: lays building blocks under and ahead of you while you walk, bridge, telly-bridge or tower.
 *
 * <p>Modes:
 * <ul>
 *   <li><b>Legit</b> - never turns for you. Sneaks at the block edge (ninja/eagle bridging) and, with
 *       Auto Place, right-clicks only when your own crosshair already lands on a face that fills a
 *       cell you need.</li>
 *   <li><b>Normal</b> - plans the cells your movement needs (diagonals become a staircase so every
 *       block has a neighbour to go against), keeps a look that already places a needed cell, and
 *       otherwise turns to the nearest point of the best support face (or a steep backwards look).</li>
 *   <li><b>Telly</b> - sprint-jumps facing your travel direction, keeps looking forward for the turn
 *       delay after takeoff, then turns onto the support faces and fills the cells between the takeoff
 *       and the predicted landing, then turns forward again for the next jump.</li>
 *   <li><b>Blatant</b> - always server-only, steep backwards look with optional instant snaps and no
 *       per-second place limit. Flagged blatant.</li>
 * </ul>
 * In every mode a block goes down only when the live ray of the current authoritative rotation hits
 * the planned support face and fills the planned cell. A hotbar change is a real synchronized
 * selection with the click later in the same tick, the reached angles are held for the rest of the
 * tick, and the swap back is a separate deferred event (all through {@link BedPlacer}). Towering and
 * telly jumps are real jump inputs only; edge safety is real sneaking.
 */
public class Scaffold extends Module implements MovementInputModifier, interstellarTikiTiks.apexx.client.module.hooks.ClickSimulating {

    private enum TellyPhase { GROUND, FORWARD, TURN, RETURN }

    /** Telly takeoff yaw tolerance (degrees) between the wire yaw and the travel direction. */
    private static final float TAKEOFF_TOLERANCE = 12.0f;
    /** Ticks a turn back onto the faces plus the click after it are assumed to take. */
    private static final int TELLY_TURN_TICKS = 3;
    /** Blocks ahead a grounded telly looks for a gap worth jumping over. */
    private static final double TELLY_GAP_LOOKAHEAD = 3.0;
    /** Ticks of current velocity the Normal path looks ahead. */
    private static final int LOOKAHEAD_TICKS = 3;
    /** How far inside a face's edges the aim point stays. */
    private static final double FACE_MARGIN = 0.2;

    // ── General ───────────────────────────────────────────────────────────────────
    private final ModeSetting mode = new ModeSetting("Mode", "Legit", "Normal", "Telly", "Blatant")
            .setDescription("Legit: never turns for you; sneaks at edges and places only when your own crosshair is on a needed face. Normal: turns (optionally server-only) to the best support face. Telly: sprint-jumps forward, turns back mid-air to fill the gap to the landing, turns forward again. Blatant: server-only steep backwards look with instant snaps and no place-rate limit (see the Blatant settings).");
    private final BooleanSetting autoPlace = new BooleanSetting("Auto Place", true)
            .setDescription("Legit mode: right-clicks for you when your crosshair is on a face that fills a needed cell. Off: Legit only edge-sneaks and you place yourself.");
    private final BooleanSetting requireSneak = new BooleanSetting("Require Sneak", false)
            .setDescription("Legit mode: only active while you hold sneak; it then lets you stand up between edges and re-sneaks at each edge (ninja bridging while holding shift).");
    private final SliderSetting minPitch = new SliderSetting("Min Pitch", 0, 0, 90, 0)
            .setDescription("Legit/Normal: only active while your camera looks at least this far down (degrees). 0 = always.");
    private final BooleanSetting keepY = new BooleanSetting("Keep Y", false)
            .setDescription("Keeps building at the height you started on even while you jump, for jump-bridging. The anchor height resets according to Keep Y Release.");
    private final ModeSetting keepYRelease = new ModeSetting("Keep Y Release", "Jump Held", "Jump Pressed", "Jump Held", "Never")
            .setDescription("When the Keep Y height is re-picked. Jump Pressed: on any jump-key press. Jump Held: only when jump is held for the hold time below. Never: the height stays until you toggle the module.");
    private final SliderSetting keepYHold = new SliderSetting("Keep Y Hold (ms)", 300, 50, 1500, 0)
            .setDescription("How long the jump key must stay held before Keep Y picks a new height (Jump Held release only).");
    private final SliderSetting expand = new SliderSetting("Expand", 0, 0, 4, 0)
            .setDescription("Extra blocks planned ahead of you along your walking direction (0 = only what your next steps need).");
    private final SliderSetting reach = new SliderSetting("Reach", 4.5, 3.0, 4.5, 1)
            .setDescription("Farthest the clicked point may be from your eyes, in blocks. Also capped by your real block-interaction range.");
    private final BooleanSetting onlyMoving = new BooleanSetting("Only When Moving", false)
            .setDescription("Places only while you hold a movement key (towering still works standing still).");
    private final BooleanSetting allowPartial = new BooleanSetting("Allow Partial Blocks", false)
            .setDescription("Also builds with slabs, stairs and other non-full blocks. Off: only full solid cubes, the safest to stand on.");
    private final SliderSetting maxRate = new SliderSetting("Max Places Per Second", 10, 4, 15, 0)
            .setDescription("Most blocks placed in any one-second window. Telly needs about 8-10 straight and more on diagonals.");

    // ── Rotations ─────────────────────────────────────────────────────────────────
    private final ModeSetting rotation = new ModeSetting("Rotation", "Face", "Face", "Backwards", "None")
            .setDescription("Normal mode. Face: turns to the nearest point of the support face. Backwards: faces opposite your walking direction with the steep pitch that still hits the face (classic backwards bridging). None: never turns; places only when your own aim hits the face.");
    private final BooleanSetting silent = new BooleanSetting("Silent Rotation", true)
            .setDescription("Turns only what the server sees; your camera stays where you look. (Blatant mode is always server-only.)");
    private final BooleanSetting moveFix = new BooleanSetting("Movement Fix", true)
            .setDescription("While the server-side rotation differs from your camera, presses the movement keys that keep you walking where your camera points, so movement and rotation stay consistent for the server.");
    private final ModeSetting aimMotion = new ModeSetting("Aim Motion", "Wind", "Wind", "Smooth", "Sine", "Linear")
            .setDescription("Shape of each turn. Wind adds human overshoot and uneven yaw/pitch speed; Smooth/Sine/Linear are steadier curves.");
    private final SliderSetting aimStrength = new SliderSetting("Aim Strength", 70, 10, 100, 0)
            .setDescription("How fast the rotation converges. Lower looks calmer but needs more time before each placement; Telly needs a fast turn.");
    private final SliderSetting aimRandom = new SliderSetting("Aim Randomness", 0.15, 0.0, 1.0, 2)
            .setDescription("Small human variation in the aim path (0 = perfectly steady).");
    private final SliderSetting keepRotation = new SliderSetting("Keep Rotation (ticks)", 2, 1, 10, 0)
            .setDescription("Ticks the placement angles are held after a block goes down before the aim may move on.");

    // ── Telly ─────────────────────────────────────────────────────────────────────
    private final ModeSetting tellyStyle = new ModeSetting("Telly Style", "Short", "Short", "Long")
            .setDescription("Short: turns back onto the faces soon after takeoff (Short Turn Delay). Long: looks forward longer (Long Turn Delay). Either turns earlier when the landing would otherwise come before the cells are in.");
    private final BooleanSetting tellyAutoJump = new BooleanSetting("Telly Auto Jump", true)
            .setDescription("Jumps for you once you face your travel direction and a gap lies ahead. Off: jump yourself; the turns still follow.");
    private final BooleanSetting tellyOnJump = new BooleanSetting("Telly Only Holding Jump", false)
            .setDescription("Telly only while you hold jump; otherwise it bridges like Normal on the ground.");

    // ── Movement aids ─────────────────────────────────────────────────────────────
    private final ModeSetting eagle = new ModeSetting("Eagle", "Off", "Off", "Edge", "Backwards Only", "Over Air", "Airborne Only")
            .setDescription("Sneaks for you. Edge: when your body would be past the edge by Edge Distance. Backwards Only: same, only while not holding forward (ninja bridging). Over Air: whenever no block is under you. Airborne Only: only while in the air. Never releases a sneak you are holding (except with Require Sneak).");
    private final SliderSetting edgeDistance = new SliderSetting("Edge Distance", 0.2, 0.05, 0.6, 2)
            .setDescription("Edge/Backwards Only: how far ahead along your movement the edge check looks, in blocks. Larger sneaks earlier.");
    private final BooleanSetting safeWalk = new BooleanSetting("Safe Walk", false)
            .setDescription("Sneaks for the single tick in which your next step would carry you off a ledge, so you cannot walk off while bridging.");
    private final ModeSetting sprint = new ModeSetting("Sprint", "Allow", "Allow", "Off While Active", "Off When Placing")
            .setDescription("Allow: sprint normally. Off While Active: stops sprinting whenever Scaffold has a block to place. Off When Placing: stops sprinting on the ticks a block is placed.");
    private final BooleanSetting tower = new BooleanSetting("Tower", false)
            .setDescription("Holding jump stacks blocks straight up under you using real jumps only.");
    private final ModeSetting towerJump = new ModeSetting("Tower Jump", "Vanilla", "Vanilla", "Timed")
            .setDescription("Vanilla: lets your held jump key repeat naturally. Timed: re-taps jump the moment you land on the new block (after the re-jump delay), like a player tapping space.");
    private final BooleanSetting towerOnlyStill = new BooleanSetting("Tower Only When Still", true)
            .setDescription("Towers only while no movement key is held, so walking with jump held still bridges normally.");
    private final BooleanSetting antiFall = new BooleanSetting("Anti-Fall", false)
            .setDescription("When you have fallen past the distance below, places a block at your predicted landing point within reach.");
    private final SliderSetting antiFallDistance = new SliderSetting("Anti-Fall Distance", 3.0, 1.0, 10.0, 1)
            .setDescription("Fall distance in blocks after which Anti-Fall tries to catch you.");

    // ── Items ─────────────────────────────────────────────────────────────────────
    private final ModeSetting blockSelection = new ModeSetting("Block Selection", "Biggest Stack", "Held Only", "First In Hotbar", "Biggest Stack")
            .setDescription("Held Only: builds only with the block in your hand. First In Hotbar: the leftmost usable block. Biggest Stack: the largest usable stack, so you run out last.");
    private final ModeSetting swapBack = new ModeSetting("Swap Back", "After Stop", "Never", "After Stop", "After Each Place")
            .setDescription("When your previous item is reselected: never, once you stop needing blocks, or after every single placement. Always a separate, delayed hotbar change.");
    private final BooleanSetting useOffhand = new BooleanSetting("Use Offhand Blocks", true)
            .setDescription("Builds straight from blocks held in your offhand without touching the hotbar.");

    // ── Blatant (Blatant mode only) ──────────────────────────────────────────────
    private final BooleanSetting snapRotation = new BooleanSetting("Snap Rotation", true)
            .setDescription("Blatant mode: the server-side look jumps to each face instantly instead of turning like a hand. No human turns this fast.")
            .setBlatant(true);
    private final BooleanSetting ignoreRate = new BooleanSetting("Ignore Place Rate", true)
            .setDescription("Blatant mode: ignores Max Places Per Second (still at most one block per tick, after Place Delay). Sustained rates above what a hand clicks.")
            .setBlatant(true);

    // ── Delays ────────────────────────────────────────────────────────────────────
    private final SliderSetting placeDelay = new SliderSetting("Place Delay (ms)", 50, 0, 500, 0).withJitter(10)
            .setSection("Delays")
            .setDescription("Minimum time between two placements. Even at 0 at most one block is placed per tick, like fast right-clicking.");
    private final SliderSetting swapDelay = new SliderSetting("Swap Delay (ms)", 12, 0, 40, 0).withJitter(4)
            .setSection("Delays")
            .setDescription("Gap between selecting the block slot and placing, inside the same tick.");
    private final SliderSetting swapBackDelay = new SliderSetting("Swap Back Delay (ms)", 150, 0, 1500, 0).withJitter(30)
            .setSection("Delays")
            .setDependsOn("!Swap Back:Never")
            .setDescription("Wait before your previous item is reselected.");
    private final SliderSetting towerPlaceDelay = new SliderSetting("Tower Place Delay (ms)", 20, 0, 50, 0).withJitter(8)
            .setSection("Delays")
            .setDescription("Minimum time between placements while towering (kept short so the block lands before you fall back).");
    private final SliderSetting towerRejump = new SliderSetting("Tower Re-Jump Delay (ms)", 40, 0, 250, 0).withJitter(15)
            .setSection("Delays")
            .setDescription("Timed tower: wait after landing before jump is tapped again.");
    private final SliderSetting idleStop = new SliderSetting("Idle Release (ms)", 250, 50, 1500, 0).withJitter(40)
            .setSection("Delays")
            .setDescription("How long without anything to place before the aim is released and After Stop swaps back.");
    private final SliderSetting sneakRelease = new SliderSetting("Sneak Release (ms)", 100, 0, 500, 0).withJitter(30)
            .setSection("Delays")
            .setDescription("Eagle Edge/Backwards Only: how long the sneak is kept after the edge check stops asking for it, before you stand up again.");
    private final SliderSetting shortTurnDelay = new SliderSetting("Short Turn Delay (ms)", 50, 0, 300, 0).withJitter(15)
            .setSection("Delays")
            .setDescription("Telly Short: time after takeoff the look stays forward before turning onto the faces (rounded to ticks).");
    private final SliderSetting longTurnDelay = new SliderSetting("Long Turn Delay (ms)", 200, 0, 500, 0).withJitter(30)
            .setSection("Delays")
            .setDescription("Telly Long: time after takeoff the look stays forward before turning onto the faces (rounded to ticks).");

    // ── Render ────────────────────────────────────────────────────────────────────
    private final BooleanSetting renderTarget = new BooleanSetting("Render Target", true)
            .setDescription("Highlights the cell the next block will fill.");
    private final ColorSetting targetColor = new ColorSetting("Target Color", new Color(255, 70, 70))
            .setDescription("Colour of the next-block highlight.");
    private final SliderSetting targetFill = new SliderSetting("Target Fill Opacity", 0.4, 0.0, 1.0, 2)
            .setDescription("Opacity of the highlight's fill (its outline is always solid).");
    private final ModeSetting counter = new ModeSetting("Block Counter", "Below Crosshair", "Off", "Below Crosshair", "Corner")
            .setDescription("Shows how many building blocks you have left (hotbar plus offhand).");
    private final SliderSetting lowWarning = new SliderSetting("Low Block Warning", 16, 0, 64, 0)
            .setDescription("The counter turns red at or below this many blocks, and yellow below twice this.");

    private final BedPlacer placer = new BedPlacer(this);
    private final ArrayDeque<Long> placeTimes = new ArrayDeque<>();

    // Keep Y / tower / idle
    private Integer anchorY;
    private boolean jumpWasDown;
    private long jumpDownSinceMs;
    private long lastWorkMs;
    private long idleLimitMs;
    private boolean idle = true;
    private boolean towering;
    private boolean wasOnGround;
    private long landedAtMs;
    private long rejumpMs;

    // Telly
    private TellyPhase tellyPhase = TellyPhase.GROUND;
    private boolean tellyEngaged;
    private int airTicks;
    private int turnDelayTicks;
    private double takeoffX;
    private double takeoffZ;
    private int tellyLayer;
    private volatile boolean tellyJump;

    // Eagle
    private long sneakUntilMs;

    // Aim
    private BlockPos plannedCell;
    private volatile Vec3d aimPoint;
    private volatile float lookYaw;
    private volatile float lookPitch;
    private volatile boolean aimIsLook;
    private final Supplier<Vec3d> aimSupplier = this::currentAimPoint;

    public Scaffold() {
        super("Scaffold", "Builds under and ahead of you while you walk, bridge, telly or tower (Legit, Normal, Telly and Blatant modes): aims at a real block face, places only when the live ray reaches it, and swaps your hotbar with synced, delayed changes.", Category.BEDWARS);
        mode.setCurrentMode("Normal"); // listed Legit-first, defaults to Normal (captured by addSetting)
        autoPlace.setDependsOn("Mode:Legit");
        requireSneak.setDependsOn("Mode:Legit");
        minPitch.setDependsOn("Mode:Legit|Normal");
        keepYRelease.setDependsOn("Keep Y");
        keepYHold.setDependsOn("Keep Y,Keep Y Release:Jump Held");
        rotation.setDependsOn("Mode:Normal");
        silent.setDependsOn("Mode:Normal|Telly");
        moveFix.setDependsOn("!Mode:Legit");
        aimMotion.setDependsOn("!Mode:Legit");
        aimStrength.setDependsOn("!Mode:Legit");
        aimRandom.setDependsOn("!Mode:Legit");
        keepRotation.setDependsOn("!Mode:Legit");
        tellyStyle.setDependsOn("Mode:Telly");
        tellyAutoJump.setDependsOn("Mode:Telly");
        tellyOnJump.setDependsOn("Mode:Telly");
        edgeDistance.setDependsOn("Eagle:Edge|Backwards Only");
        sneakRelease.setDependsOn("Eagle:Edge|Backwards Only");
        towerJump.setDependsOn("Tower");
        towerOnlyStill.setDependsOn("Tower");
        antiFallDistance.setDependsOn("Anti-Fall");
        snapRotation.setDependsOn("Mode:Blatant");
        ignoreRate.setDependsOn("Mode:Blatant");
        shortTurnDelay.setDependsOn("Mode:Telly,Telly Style:Short");
        longTurnDelay.setDependsOn("Mode:Telly,Telly Style:Long");
        targetColor.setDependsOn("Render Target");
        targetFill.setDependsOn("Render Target");
        lowWarning.setDependsOn("!Block Counter:Off");

        addSetting(mode);
        addSetting(autoPlace);
        addSetting(requireSneak);
        addSetting(minPitch);
        addSetting(keepY);
        addSetting(keepYRelease);
        addSetting(keepYHold);
        addSetting(expand);
        addSetting(reach);
        addSetting(onlyMoving);
        addSetting(allowPartial);
        addSetting(maxRate);

        addSetting(rotation);
        addSetting(silent);
        addSetting(moveFix);
        addSetting(aimMotion);
        addSetting(aimStrength);
        addSetting(aimRandom);
        addSetting(keepRotation);

        addSetting(tellyStyle);
        addSetting(tellyAutoJump);
        addSetting(tellyOnJump);

        addSetting(eagle);
        addSetting(edgeDistance);
        addSetting(safeWalk);
        addSetting(sprint);
        addSetting(tower);
        addSetting(towerJump);
        addSetting(towerOnlyStill);
        addSetting(antiFall);
        addSetting(antiFallDistance);

        addSetting(blockSelection);
        addSetting(swapBack);
        addSetting(useOffhand);

        addSetting(snapRotation);
        addSetting(ignoreRate);

        addSetting(placeDelay);
        addSetting(swapDelay);
        addSetting(swapBackDelay);
        addSetting(towerPlaceDelay);
        addSetting(towerRejump);
        addSetting(idleStop);
        addSetting(sneakRelease);
        addSetting(shortTurnDelay);
        addSetting(longTurnDelay);

        addSetting(renderTarget);
        addSetting(targetColor);
        addSetting(targetFill);
        addSetting(counter);
        addSetting(lowWarning);
    }

    @Override
    public void onEnable() {
        EventBus.register(this);
        resetState();
    }

    @Override
    public void onDisable() {
        EventBus.unregister(this);
        placer.release();
        resetState();
    }

    @Override
    protected void onRuntimeStateReleased() {
        placer.release();
        resetState();
    }

    private void resetState() {
        anchorY = null;
        jumpWasDown = false;
        jumpDownSinceMs = 0L;
        lastWorkMs = 0L;
        idle = true;
        towering = false;
        wasOnGround = false;
        landedAtMs = 0L;
        rejumpMs = 0L;
        tellyPhase = TellyPhase.GROUND;
        tellyEngaged = false;
        airTicks = 0;
        turnDelayTicks = 0;
        tellyJump = false;
        sneakUntilMs = 0L;
        plannedCell = null;
        aimPoint = null;
        aimIsLook = false;
        placeTimes.clear();
    }

    @EventTarget
    public void onCombatSwapLock(CombatSwapLockEvent event) {
        if (event.isRequestedBy(this)) return;
        if (placer.holdsSwap()) event.setItemSwapLocked(true);
    }

    // ── tick ──────────────────────────────────────────────────────────────────────

    @Override
    public void onTick() {
        if (!isEnabled() || mc.player == null || mc.world == null || mc.interactionManager == null) return;
        ClientPlayerEntity p = mc.player;
        placer.tick();
        long now = System.currentTimeMillis();
        tellyJump = false;

        boolean onGround = p.isOnGround();
        if (onGround && !wasOnGround) {
            landedAtMs = now;
            rejumpMs = (long) towerRejump.sample();
        }
        wasOnGround = onGround;

        if (mc.currentScreen != null || p.isSpectator() || p.hasVehicle() || p.isGliding() || !p.isAlive()
                || p.getAbilities().flying) {
            stand(now);
            return;
        }

        boolean jumpDown = SystemInputSimulator.isPhysicallyPressed(mc.options.jumpKey);
        updateKeepY(p, jumpDown, onGround, now);
        boolean moving = hasMovementInput(p);
        towering = tower.isEnabled() && jumpDown && !mode.is("Telly") && (!towerOnlyStill.isEnabled() || !moving);

        if (!gateOpen(p)) {
            stand(now);
            return;
        }

        Hand hand;
        int slot;
        if (useOffhand.isEnabled() && BedwarsSupport.isBuildingBlock(p.getOffHandStack(), allowPartial.isEnabled())) {
            hand = Hand.OFF_HAND;
            slot = -1;
        } else {
            slot = BedwarsSupport.findBlockSlot(p.getInventory(), blockSelection.getCurrentMode(), allowPartial.isEnabled());
            if (slot < 0) {
                stand(now);
                return;
            }
            hand = Hand.MAIN_HAND;
        }

        Plan plan = mode.is("Telly") ? planTelly(p, moving, jumpDown, onGround) : planWalk(p, moving);
        if (plan == null) {
            stand(now);
            return;
        }
        plannedCell = plan.sol != null ? plan.sol.placedAt() : null;
        lastWorkMs = now;
        if (idle) {
            idle = false;
            idleLimitMs = (long) idleStop.sample();
        }
        if (sprint.is("Off While Active") && p.isSprinting()) p.setSprinting(false);

        boolean silentAim = silentAim();
        if (plan.noAim) {
            placer.stopAim();
        } else if (plan.lookOnly || plan.aimAt != null) {
            if (plan.lookOnly) {
                lookYaw = plan.yaw;
                lookPitch = plan.pitch;
                aimIsLook = true;
            } else {
                aimPoint = plan.aimAt;
                aimIsLook = false;
            }
            placer.aim(aimSupplier, aimStrength.getValue(), aimMode(), aimRandom.getValue(), silentAim);
        }

        if (plan.sol == null || (mode.is("Legit") && !autoPlace.isEnabled())) return;
        // Never click with another module's look on the wire.
        if (RotationManager.isActive() && RotationManager.getCurrentOwner() != this) return;
        if (!rateAllows(now)) return;
        double delay = towering ? towerPlaceDelay.sample() : placeDelay.sample();
        if (placer.tryPlace(plan.sol, slot, hand, silentAim, swapDelay.sample(), delay, (int) keepRotation.getValue())) {
            placeTimes.addLast(now);
            if (sprint.is("Off When Placing") && p.isSprinting()) p.setSprinting(false);
            if (swapBack.is("After Each Place")) placer.scheduleRestore(swapBackDelay.sample());
        }
    }

    /** Nothing to do this tick: forget the target and, after the idle time, let go of aim and hotbar. */
    private void stand(long now) {
        plannedCell = null;
        if (idle) return;
        if (now - lastWorkMs < idleLimitMs) return;
        idle = true;
        placer.stopAim();
        aimPoint = null;
        aimIsLook = false;
        if (!swapBack.is("Never")) placer.scheduleRestore(swapBackDelay.sample());
    }

    private boolean gateOpen(ClientPlayerEntity p) {
        if (mode.is("Legit") && requireSneak.isEnabled()
                && !SystemInputSimulator.isPhysicallyPressed(mc.options.sneakKey)) {
            return false;
        }
        if ((mode.is("Legit") || mode.is("Normal")) && minPitch.getValue() > 0.0
                && cameraPitch(p) < minPitch.getValue()) {
            return false;
        }
        return true;
    }

    private boolean rateAllows(long now) {
        while (!placeTimes.isEmpty() && now - placeTimes.peekFirst() >= 1000L) placeTimes.pollFirst();
        if (mode.is("Blatant") && ignoreRate.isEnabled()) return true;
        return placeTimes.size() < (int) maxRate.getValue();
    }

    private boolean silentAim() {
        if (mode.is("Blatant")) return true;
        if (mode.is("Legit") || (mode.is("Normal") && rotation.is("None"))) return false;
        return silent.isEnabled();
    }

    private RotationManager.RotationMode aimMode() {
        if (mode.is("Blatant") && snapRotation.isEnabled()) return RotationManager.RotationMode.INSTANT;
        return switch (aimMotion.getCurrentMode()) {
            case "Smooth" -> RotationManager.RotationMode.SMOOTH;
            case "Sine" -> RotationManager.RotationMode.SINE;
            case "Linear" -> RotationManager.RotationMode.LINEAR;
            default -> RotationManager.RotationMode.WIND;
        };
    }

    private Vec3d currentAimPoint() {
        ClientPlayerEntity p = mc.player;
        if (aimIsLook && p != null) {
            return p.getEyePos().add(Vec3d.fromPolar(lookPitch, lookYaw).multiply(8.0));
        }
        return aimPoint;
    }

    // ── Keep Y ────────────────────────────────────────────────────────────────────

    private void updateKeepY(ClientPlayerEntity p, boolean jumpDown, boolean onGround, long now) {
        if (jumpDown && !jumpWasDown) jumpDownSinceMs = now;
        jumpWasDown = jumpDown;
        if (!keepY.isEnabled()) {
            anchorY = null;
            return;
        }
        if (anchorY != null) {
            boolean release = switch (keepYRelease.getCurrentMode()) {
                case "Jump Pressed" -> jumpDown && now - jumpDownSinceMs < 60L;
                case "Jump Held" -> jumpDown && now - jumpDownSinceMs >= (long) keepYHold.getValue();
                default -> false;
            };
            if (release) anchorY = null;
        }
        if (anchorY == null && onGround && !jumpDown) {
            anchorY = MathHelper.floor(p.getY()) - 1;
        }
    }

    // ── planning ──────────────────────────────────────────────────────────────────

    /** One tick's decision: the placement to try (may be null) and what the rotation should do. */
    private static final class Plan {
        PlaceSolution sol;
        Vec3d aimAt;
        boolean lookOnly;
        float yaw;
        float pitch;
        boolean noAim;
    }

    /** Legit, Normal and Blatant: cells under you and along your next steps. */
    private Plan planWalk(ClientPlayerEntity p, boolean moving) {
        double reachLimit = reachLimit(p);
        Set<BlockPos> needed = new LinkedHashSet<>();

        boolean falling = antiFall.isEnabled() && !p.isOnGround() && p.fallDistance >= antiFallDistance.getValue()
                && p.getVelocity().y < -0.1;
        if (falling) {
            Vec3d v = p.getVelocity();
            needed.add(BlockPos.ofFloored(p.getX() + v.x, p.getY() + v.y, p.getZ() + v.z).down());
        }
        if (!falling && onlyMoving.isEnabled() && !moving && !towering) return null;

        int baseY = (keepY.isEnabled() && anchorY != null && !towering) ? anchorY : MathHelper.floor(p.getY()) - 1;
        BlockPos under = BlockPos.ofFloored(p.getX(), baseY, p.getZ());
        Vec3d dir = BedwarsSupport.travelDirection(p, cameraYaw(p));
        if (!falling) needed.addAll(walkPath(p, under, dir, baseY));

        boolean legit = mode.is("Legit") || (mode.is("Normal") && rotation.is("None"));
        Plan plan = new Plan();
        // A look that already places a needed cell is kept rather than traded for a marginally better one.
        PlaceSolution held = liveSolution(p, needed, reachLimit);
        if (legit) {
            if (held == null && !anyGap(needed)) return null;
            plan.sol = held;
            plan.noAim = true;
            return plan;
        }
        if (held != null) {
            plan.sol = held;
            plan.aimAt = held.hitVec();
            return plan;
        }
        PlaceSolution sol = firstSolution(p, needed, under, reachLimit);
        if (sol == null) return null;
        plan.sol = sol;
        Vec3d point = null;
        if (mode.is("Blatant") || rotation.is("Backwards")) point = backwardsPoint(p, sol, dir);
        plan.aimAt = point != null ? point : faceAim(p, sol);
        return plan;
    }

    /** The cell under you, then the staircase your next ticks of motion cross, then Expand cells ahead. */
    private List<BlockPos> walkPath(ClientPlayerEntity p, BlockPos under, Vec3d dir, int baseY) {
        List<BlockPos> path = new ArrayList<>();
        path.add(under);
        if (dir.equals(Vec3d.ZERO) || towering) return path;
        Vec3d v = p.getVelocity();
        double speed = Math.max(0.15, Math.sqrt(v.x * v.x + v.z * v.z));
        double ahead = speed * LOOKAHEAD_TICKS + 0.3 + expand.getValue();
        for (int[] xz : ScaffoldMath.staircase(p.getX(), p.getZ(), p.getX() + dir.x * ahead,
                p.getZ() + dir.z * ahead, 12)) {
            BlockPos cell = new BlockPos(xz[0], baseY, xz[1]);
            if (!path.contains(cell)) path.add(cell);
        }
        return path;
    }

    private boolean anyGap(Set<BlockPos> cells) {
        for (BlockPos cell : cells) {
            if (mc.world.getBlockState(cell).isReplaceable()) return true;
        }
        return false;
    }

    /**
     * The first empty cell, in path order, that can be clicked into now; if it has nothing to go
     * against yet, the supporting cell beside it. A hole directly under you is never skipped.
     */
    private PlaceSolution firstSolution(ClientPlayerEntity p, Iterable<BlockPos> cells, BlockPos under, double reachLimit) {
        for (BlockPos cell : cells) {
            if (!mc.world.getBlockState(cell).isReplaceable()) continue;
            PlaceSolution s = resolve(cell, reachLimit, p);
            if (s != null) return s;
            for (Direction d : Direction.Type.HORIZONTAL) {
                BlockPos support = cell.offset(d);
                if (!mc.world.getBlockState(support).isReplaceable()) continue;
                PlaceSolution sup = resolve(support, reachLimit, p);
                if (sup != null) return sup;
            }
            if (cell.equals(under)) break;
        }
        return null;
    }

    private PlaceSolution resolve(BlockPos cell, double reachLimit, ClientPlayerEntity p) {
        if (BedwarsSupport.intersectsPlayer(p, cell)) return null;
        return PlaceResolver.resolvePlacement(mc, cell, reachLimit);
    }

    /** The placement the current authoritative look already makes, when it fills one of {@code needed}. */
    private PlaceSolution liveSolution(ClientPlayerEntity p, Set<BlockPos> needed, double reachLimit) {
        BlockHitResult live = BlockPlacementValidator.rayFromLook(mc, RotationManager.isSilentRotationActive());
        if (live == null) return null;
        if (RotationManager.isActive() && RotationManager.getCurrentOwner() != this) return null;
        BlockPos cell = BlockPlacementValidator.placementCell(mc, live);
        if (cell == null || !cell.equals(live.getBlockPos().offset(live.getSide()))) return null;
        if (!needed.contains(cell) || !mc.world.getBlockState(cell).isReplaceable()) return null;
        if (BedwarsSupport.intersectsPlayer(p, cell)) return null;
        if (p.getEyePos().squaredDistanceTo(live.getPos()) > reachLimit * reachLimit) return null;
        return new PlaceSolution(live.getBlockPos(), live.getSide(), live.getPos());
    }

    /** Nearest point of the support face to the current look, verified by a ray; else the resolver's point. */
    private Vec3d faceAim(ClientPlayerEntity p, PlaceSolution sol) {
        Vec3d eye = p.getEyePos();
        Vec3d look = Vec3d.fromPolar(RotationManager.getFinalPitch(), RotationManager.getFinalYaw());
        Vec3d point = ScaffoldMath.facePoint(sol.against(), sol.side(), eye, look, FACE_MARGIN);
        return raysOnto(p, eye, point, sol) ? point : sol.hitVec();
    }

    private boolean raysOnto(ClientPlayerEntity p, Vec3d eye, Vec3d point, PlaceSolution sol) {
        Vec3d dir = point.subtract(eye);
        double len = dir.length();
        if (len < 1.0E-6 || len > reachLimit(p)) return false;
        Vec3d end = eye.add(dir.multiply((len + 0.5) / len));
        BlockHitResult hit = mc.world.raycast(new RaycastContext(eye, end, RaycastContext.ShapeType.OUTLINE,
                RaycastContext.FluidHandling.NONE, p));
        return hit != null && hit.getType() == HitResult.Type.BLOCK && hit.getBlockPos().equals(sol.against())
                && hit.getSide() == sol.side();
    }

    /**
     * Backwards bridging: yaw opposite to the walking direction, and the pitch in the 70-89 degree band
     * (closest to the current pitch) whose ray from the eye hits exactly the planned support face.
     */
    private Vec3d backwardsPoint(ClientPlayerEntity p, PlaceSolution sol, Vec3d dir) {
        if (dir.equals(Vec3d.ZERO)) return null;
        float yaw = MathHelper.wrapDegrees(ScaffoldMath.yawOf(dir.x, dir.z) + 180.0f);
        Vec3d eye = p.getEyePos();
        double range = reachLimit(p);
        float current = RotationManager.getFinalPitch();
        Vec3d best = null;
        float bestDelta = Float.MAX_VALUE;
        for (float pitch = 70.0f; pitch <= 89.0f; pitch += 0.5f) {
            Vec3d end = eye.add(Vec3d.fromPolar(pitch, yaw).multiply(range));
            BlockHitResult hit = mc.world.raycast(new RaycastContext(eye, end, RaycastContext.ShapeType.OUTLINE,
                    RaycastContext.FluidHandling.NONE, p));
            if (hit == null || hit.getType() != HitResult.Type.BLOCK) continue;
            if (!hit.getBlockPos().equals(sol.against()) || hit.getSide() != sol.side()) continue;
            float delta = Math.abs(pitch - current);
            if (delta < bestDelta) {
                bestDelta = delta;
                best = hit.getPos();
            }
        }
        return best;
    }

    // ── Telly ─────────────────────────────────────────────────────────────────────

    /**
     * Grounded: face the travel direction and jump when a gap lies ahead (placing from the ground only
     * when the footing under the next step is already gone). Airborne: keep looking forward for the
     * turn delay, or less when the landing would otherwise come first, then fill the cells between the
     * takeoff and the predicted landing in order, then face forward again for the next takeoff.
     */
    private Plan planTelly(ClientPlayerEntity p, boolean moving, boolean jumpDown, boolean onGround) {
        boolean engaged = moving && (!tellyOnJump.isEnabled() || jumpDown);
        if (!engaged) {
            tellyEngaged = false;
            tellyPhase = TellyPhase.GROUND;
            return planWalk(p, moving);
        }
        Vec3d dir = inputDirection(p, cameraYaw(p));
        if (dir.equals(Vec3d.ZERO)) return planWalk(p, moving);
        float travelYaw = ScaffoldMath.yawOf(dir.x, dir.z);
        float takeoffPitch = MathHelper.clamp(cameraPitch(p), -30.0f, 75.0f);
        double reachLimit = reachLimit(p);

        if (onGround) {
            tellyPhase = TellyPhase.GROUND;
            tellyEngaged = true;
            airTicks = 0;
            takeoffX = p.getX();
            takeoffZ = p.getZ();
            tellyLayer = MathHelper.floor(p.getY()) - 1;
        } else if (!tellyEngaged) {
            // Engaged mid-air (walked off, or enabled while falling): bridge like Normal until landing.
            return planWalk(p, moving);
        } else if (tellyPhase == TellyPhase.GROUND) {
            tellyPhase = p.getVelocity().y > 0.05 ? TellyPhase.FORWARD : TellyPhase.TURN;
            airTicks = 0;
            double ms = tellyStyle.is("Long") ? longTurnDelay.sample() : shortTurnDelay.sample();
            turnDelayTicks = ModuleDelays.msToTicks(ms);
        }
        if (!onGround) airTicks++;

        Plan plan = new Plan();
        if (tellyPhase == TellyPhase.GROUND) {
            // Footing under the next step already gone: place from the ground first.
            List<BlockPos> urgent = layerCells(p.getX(), p.getZ(), p.getX() + dir.x * 0.6, p.getZ() + dir.z * 0.6);
            if (!urgent.isEmpty()) {
                PlaceSolution held = liveSolution(p, new LinkedHashSet<>(urgent), reachLimit);
                PlaceSolution sol = held != null ? held
                        : firstSolution(p, urgent, BlockPos.ofFloored(p.getX(), tellyLayer, p.getZ()), reachLimit);
                if (sol != null) {
                    plan.sol = sol;
                    plan.aimAt = held != null ? held.hitVec() : faceAim(p, sol);
                    return plan;
                }
            }
            boolean gap = !layerCells(p.getX(), p.getZ(), p.getX() + dir.x * TELLY_GAP_LOOKAHEAD,
                    p.getZ() + dir.z * TELLY_GAP_LOOKAHEAD).isEmpty();
            if (!gap) return null;
            boolean aligned = Math.abs(ScaffoldMath.yawDelta(RotationManager.getFinalYaw(), travelYaw)) <= TAKEOFF_TOLERANCE;
            tellyJump = tellyAutoJump.isEnabled() && aligned
                    && !BedwarsSupport.ceilingAbove(mc.world, p, 1.25);
            return look(plan, travelYaw, takeoffPitch);
        }

        List<BlockPos> cells = tellyCells(p, dir);
        if (tellyPhase == TellyPhase.FORWARD) {
            Vec3d v = p.getVelocity();
            int land = ScaffoldMath.ticksToLand(p.getY(), v.y, tellyLayer + 1.0, 40);
            if (land < 0) land = 40;
            boolean deadline = land <= cells.size() + TELLY_TURN_TICKS;
            if (airTicks < turnDelayTicks && !deadline) return look(plan, travelYaw, takeoffPitch);
            tellyPhase = TellyPhase.TURN;
        }
        if (tellyPhase == TellyPhase.RETURN && !cells.isEmpty()) tellyPhase = TellyPhase.TURN;
        if (tellyPhase == TellyPhase.TURN) {
            if (!cells.isEmpty()) {
                PlaceSolution held = liveSolution(p, new LinkedHashSet<>(cells), reachLimit);
                PlaceSolution sol = held != null ? held : firstSolution(p, cells, cells.get(0), reachLimit);
                if (sol != null) {
                    plan.sol = sol;
                    plan.aimAt = held != null ? held.hitVec() : faceAim(p, sol);
                    return plan;
                }
                // Nothing in reach yet: keep the current look (do not swing back and forth).
                return plan;
            }
            tellyPhase = TellyPhase.RETURN;
        }
        return look(plan, travelYaw, takeoffPitch);
    }

    private static Plan look(Plan plan, float yaw, float pitch) {
        plan.lookOnly = true;
        plan.yaw = yaw;
        plan.pitch = pitch;
        return plan;
    }

    /** Empty cells at the telly layer from the takeoff point to just past the predicted landing. */
    private List<BlockPos> tellyCells(ClientPlayerEntity p, Vec3d dir) {
        Vec3d v = p.getVelocity();
        int land = ScaffoldMath.ticksToLand(p.getY(), v.y, tellyLayer + 1.0, 40);
        if (land < 0) land = 40;
        double speed = Math.sqrt(v.x * v.x + v.z * v.z);
        double dist = ScaffoldMath.airDistance(speed, land) + 0.3;
        return layerCells(takeoffX, takeoffZ, p.getX() + dir.x * dist, p.getZ() + dir.z * dist);
    }

    private List<BlockPos> layerCells(double x0, double z0, double x1, double z1) {
        List<BlockPos> cells = new ArrayList<>();
        for (int[] xz : ScaffoldMath.staircase(x0, z0, x1, z1, 16)) {
            BlockPos cell = new BlockPos(xz[0], tellyLayer, xz[1]);
            if (mc.world.getBlockState(cell).isReplaceable()) cells.add(cell);
        }
        return cells;
    }

    // ── helpers ───────────────────────────────────────────────────────────────────

    private double reachLimit(ClientPlayerEntity p) {
        return Math.min(reach.getValue(), p.getBlockInteractionRange());
    }

    private static float cameraYaw(ClientPlayerEntity p) {
        return RotationManager.isSilentRotationActive() ? RotationManager.getVisualYaw() : p.getYaw();
    }

    private static float cameraPitch(ClientPlayerEntity p) {
        return RotationManager.isSilentRotationActive() ? RotationManager.getVisualPitch() : p.getPitch();
    }

    /** Where the held movement keys point at the camera yaw (the intended travel direction). */
    private static Vec3d inputDirection(ClientPlayerEntity p, float cameraYaw) {
        if (p.input == null) return Vec3d.ZERO;
        PlayerInput in = p.input.playerInput;
        int f = BedwarsSupport.forwardAxis(in);
        int s = BedwarsSupport.strafeAxis(in);
        if (f == 0 && s == 0) return Vec3d.ZERO;
        Vec3d w = BedwarsSupport.inputVector(f, s, cameraYaw);
        double len = w.length();
        return len < 1.0E-6 ? Vec3d.ZERO : w.multiply(1.0 / len);
    }

    private static boolean hasMovementInput(ClientPlayerEntity p) {
        if (p.input == null) return false;
        PlayerInput in = p.input.playerInput;
        return in.forward() || in.backward() || in.left() || in.right();
    }

    // ── movement input ───────────────────────────────────────────────────────────

    @Override
    public PlayerInput modifyMovementInput(MinecraftClient client, PlayerInput input) {
        ClientPlayerEntity p = client.player;
        if (p == null || client.world == null || client.currentScreen != null) return input;
        PlayerInput out = input;

        if (moveFix.isEnabled() && silentAim() && RotationManager.getCurrentOwner() == this
                && RotationManager.isSilentRotationActive()) {
            out = BedwarsSupport.correctMovement(out, RotationManager.getVisualYaw(), p.getYaw());
        }

        boolean jump = out.jump();
        boolean tellyJumping = tellyJump && p.isOnGround();
        if (tellyJumping) jump = true;
        if (towering && towerJump.is("Timed")) {
            // Release while airborne (clears vanilla's held-jump cooldown), tap again once landed and the
            // re-jump delay has passed: the same input a player tapping space produces.
            jump = p.isOnGround() && System.currentTimeMillis() - landedAtMs >= rejumpMs;
        }

        boolean physicalSneak = input.sneak();
        boolean edgeSneak = !towering && !tellyJumping && edgeWantsSneak(client, p, input);
        boolean sneak = out.sneak();
        if (mode.is("Legit") && requireSneak.isEnabled() && physicalSneak && p.isOnGround()) {
            // Require Sneak: stand up between edges, sneak again at each edge.
            sneak = edgeSneak || eagle.is("Off");
        } else if (!physicalSneak) {
            sneak = edgeSneak;
            if (!sneak && safeWalk.isEnabled() && !towering && !tellyJumping) {
                sneak = BedwarsSupport.edgeAhead(client.world, p, 1.0, 0.6);
            }
        }

        boolean sprintKey = out.sprint();
        if (sprint.is("Off While Active") && !idle) sprintKey = false;

        if (sneak == out.sneak() && jump == out.jump() && sprintKey == out.sprint()) return out;
        return new PlayerInput(out.forward(), out.backward(), out.left(), out.right(), jump, sneak, sprintKey);
    }

    /** Eagle: whether this tick should be sneaked, including the Sneak Release hold after an edge. */
    private boolean edgeWantsSneak(MinecraftClient client, ClientPlayerEntity p, PlayerInput input) {
        if (eagle.is("Off")) return false;
        if (mode.is("Telly") && !p.isOnGround()) return false;
        if (eagle.is("Over Air")) return BedwarsSupport.centerOverAir(client.world, p, 0.6);
        if (eagle.is("Airborne Only")) return !p.isOnGround() && BedwarsSupport.centerOverAir(client.world, p, 0.6);
        long now = System.currentTimeMillis();
        boolean backwardsOk = !eagle.is("Backwards Only") || BedwarsSupport.forwardAxis(input) <= 0;
        if (p.isOnGround() && backwardsOk) {
            Vec3d dir = BedwarsSupport.travelDirection(p, cameraYaw(p));
            if (!dir.equals(Vec3d.ZERO)) {
                double d = edgeDistance.getValue();
                Box shifted = p.getBoundingBox().offset(dir.x * d, 0.0, dir.z * d);
                Box below = new Box(shifted.minX, shifted.minY - 0.6, shifted.minZ, shifted.maxX, shifted.minY - 0.001, shifted.maxZ);
                if (client.world.isSpaceEmpty(p, below)) sneakUntilMs = now + (long) sneakRelease.sample();
            }
        }
        return now < sneakUntilMs;
    }

    // ── rendering ─────────────────────────────────────────────────────────────────

    @Override
    public void onRender(DrawContext context, float tickDelta) {
        if (counter.is("Off") || mc.player == null || mc.currentScreen != null) return;
        int count = BedwarsSupport.countBlocks(mc.player, allowPartial.isEnabled());
        int low = (int) lowWarning.getValue();
        Color color = count <= low ? new Color(255, 85, 85)
                : count <= low * 2 ? new Color(255, 210, 90) : new Color(240, 244, 250);
        String text = count + (count == 1 ? " block" : " blocks");
        IApexxRenderer r = Renderer.get();
        float size = 9.5f;
        float w = r.getTextWidth(text, size);
        float sw = context.getScaledWindowWidth();
        float sh = context.getScaledWindowHeight();
        float x;
        float y;
        if (counter.is("Corner")) {
            x = 8f;
            y = sh - 22f;
        } else {
            x = sw / 2f - w / 2f;
            y = sh / 2f + 12f;
        }
        r.drawText(context, text, x, y, size, color, true);
    }

    @Override
    public void onWorldRender(MatrixStack matrices, float tickDelta) {
        BlockPos cell = plannedCell;
        if (!renderTarget.isEnabled() || cell == null || mc.world == null) return;
        Vec3d camera = mc.gameRenderer.getCamera().getCameraPos();
        Box local = new Box(cell).expand(0.002).offset(-camera.x, -camera.y, -camera.z);
        Color color = targetColor.getValue();
        try (BufferAllocator allocator = interstellarTikiTiks.apexx.client.util.render.PooledBufferAllocator.acquire(4096)) {
            VertexConsumerProvider.Immediate immediate = VertexConsumerProvider.immediate(allocator);
            float fill = (float) targetFill.getValue();
            if (fill > 0.004f) RenderUtils.drawBoxEsp(matrices, immediate, local, color, fill, false);
            RenderUtils.drawBoxOutlineEsp(matrices, immediate, local, color, 1.0f, false);
            immediate.draw();
        }
    }
}
