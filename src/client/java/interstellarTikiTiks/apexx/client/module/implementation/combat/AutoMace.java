package interstellarTikiTiks.apexx.client.module.implementation.combat;

import interstellarTikiTiks.apexx.client.util.combat.ModuleDelays;
import interstellarTikiTiks.apexx.client.event.EventBus;
import interstellarTikiTiks.apexx.client.event.EventTarget;
import interstellarTikiTiks.apexx.client.event.impl.CombatSwapLockEvent;
import interstellarTikiTiks.apexx.client.event.impl.MaceHitEvent;
import interstellarTikiTiks.apexx.client.event.impl.ShieldDrainEvent;
import interstellarTikiTiks.apexx.client.module.BooleanSetting;
import interstellarTikiTiks.apexx.client.module.Category;
import interstellarTikiTiks.apexx.client.module.SubCategory;
import interstellarTikiTiks.apexx.client.module.Module;
import interstellarTikiTiks.apexx.client.module.ModeSetting;
import interstellarTikiTiks.apexx.client.module.SliderSetting;
import interstellarTikiTiks.apexx.client.util.RotationManager;
import interstellarTikiTiks.apexx.client.util.RotationRequest;
import interstellarTikiTiks.apexx.client.util.ClientInternalLog;
import interstellarTikiTiks.apexx.client.mixin.KeyBindingAccessor;
import interstellarTikiTiks.apexx.client.mixin.MinecraftClientAccessor;
import interstellarTikiTiks.apexx.client.mixin.ClientPlayerInteractionManagerMixin;
import interstellarTikiTiks.apexx.client.friend.FriendManager;
import interstellarTikiTiks.apexx.client.util.InputTracker;
import interstellarTikiTiks.apexx.client.util.SystemInputSimulator;
import org.lwjgl.glfw.GLFW;
import interstellarTikiTiks.apexx.client.util.render.RenderUtils;
import interstellarTikiTiks.apexx.client.util.AttackValidator;
import interstellarTikiTiks.apexx.client.util.ExecutionMode;
import interstellarTikiTiks.apexx.client.util.combat.CombatAimUtil;
import interstellarTikiTiks.apexx.client.util.combat.CombatItemUtil;
import interstellarTikiTiks.apexx.client.util.combat.CombatPrediction;
import interstellarTikiTiks.apexx.client.util.combat.InterceptPredictor;
import interstellarTikiTiks.apexx.client.util.combat.StunSlamEngine;
import interstellarTikiTiks.apexx.client.util.combat.AimSmoother;
import interstellarTikiTiks.apexx.client.util.combat.StrikePlanner;
import interstellarTikiTiks.apexx.client.util.combat.SpearReachPlanner;
import interstellarTikiTiks.apexx.client.util.combat.SubTickSequencer;
import interstellarTikiTiks.apexx.client.util.combat.SlotSync;
import interstellarTikiTiks.apexx.client.util.combat.TickActionOrder;
import interstellarTikiTiks.apexx.client.module.implementation.movement.AutoLunge;
import interstellarTikiTiks.apexx.client.util.ModuleAccess;
import interstellarTikiTiks.apexx.client.module.hooks.AttackExecutionAware;
import interstellarTikiTiks.apexx.client.module.hooks.AttackHandleAware;
import interstellarTikiTiks.apexx.client.module.hooks.RenderFrameAware;
import net.minecraft.block.Blocks;
import net.minecraft.block.BlockState;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.render.*;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.ItemEnchantmentsComponent;
import net.minecraft.enchantment.Enchantment;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.*;
import net.minecraft.registry.Registries;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.registry.tag.FluidTags;
import net.minecraft.util.hit.EntityHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.*;
import net.minecraft.util.Hand;

import net.minecraft.world.RaycastContext;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.component.type.EquippableComponent;

import java.awt.Color;
import java.util.Comparator;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;

public class AutoMace extends Module implements interstellarTikiTiks.apexx.client.module.hooks.MaceSmashArmProvider, AttackExecutionAware, AttackHandleAware, interstellarTikiTiks.apexx.client.module.hooks.AttackTargetAware, RenderFrameAware, interstellarTikiTiks.apexx.client.module.hooks.MouseSensitivityModifier, interstellarTikiTiks.apexx.client.module.hooks.ClickSimulating, interstellarTikiTiks.apexx.client.module.hooks.ItemUseBlockProvider {
    // ---- Delays ----
    private final SliderSetting profileDelay = new SliderSetting("Post-Hit Delay (ms)", 30, 0, 1000, 0)
            .withJitter(8)
            .setDescription("Delay added to the cooldown after a smash, drawn around this value with the StDev below.")
            .setSection("Delays");
    private final SliderSetting lockOnWait = new SliderSetting("Lock-On Wait (ms)", 50, 0, 250, 0)
            .withJitter(15)
            .setDescription("Time to hold off after a fresh target is locked before any smash or stun slam may begin. Aiming continues during the wait. 0 = fire as soon as the hit is valid.")
            .setSection("Delays");
    private final BooleanSetting waitOnPearlArrival = new BooleanSetting("Wait On Pearl Arrival", true)
            .setDescription("When an enemy ender-pearls onto you (lands within about 1.5 blocks of you horizontally), hold every smash and stun slam on them for the Pearl Arrival Wait. Aiming continues.")
            .setSection("Delays");
    private final SliderSetting pearlArrivalWait = new SliderSetting("Pearl Arrival Wait (ms)", 1000, 0, 3000, 0)
            .withJitter(100)
            .setDescription("How long to wait after an enemy pearls onto you before macing them.")
            .setSection("Delays");
    private final SliderSetting backstabGiveUp = new SliderSetting("Backstab Give-Up (ms)", 400, 0, 2000, 0)
            .withJitter(60)
            .setDescription("Shield Counter 'Backstab then Stun Slam': how long to keep trying to get behind a blocking target before switching to the axe stun slam. The switch also happens early when the fall is about to end.")
            .setSection("Delays");
    private final SliderSetting swapBackDelay = new SliderSetting("Swap Back Delay (ms)", 50, 0, 1000, 0)
            .withJitter(10)
            .setDescription("How long the mace stays selected after a smash (or the axe after a skipped stun-slam follow-up) before your previous slot is restored. Counted in client ticks (50 ms each, fraction rounded at random); the restore never happens before the next tick, so 0 means the earliest legal tick after the hit.")
            .setSection("Delays");

    /** Vanilla's 3.0-block entity attack reach; the closest-point test below adds a small epsilon. */
    private static final double ATTACK_RANGE = 3.0;
    /** Margin on the eye-to-hitbox reach test, matching how the server measures reach to the closest box point. */
    private static final double REACH_EPSILON = 1.0E-3;
    /** Outer cap for the speed-scaled predicted reach during a fast dive. */
    private static final double SWEPT_REACH_CAP = 3.0;
    /** Attack reach while a spear is the held item (1.21.11 spear attack range). */
    private static final double SPEAR_ATTACK_RANGE = 4.5;
    /** Head-yaw slack for a raising shield: a target not yet facing us can turn onto us within a tick, so cosine down to this still counts as covering. */
    private static final double SHIELD_RAISE_FACING_DOT = -0.25;
    /** Extra blocks of slack before a held spear is given up, so a marginal forecast does not flicker the hotbar. */
    private static final double AUTO_SPEAR_HOLD_MARGIN = 0.75;
    /** Ticks the spear stays selected after its swap tick: the one tick in which the smash swaps from it to the mace. */
    private static final int AUTO_SPEAR_MAX_HOLD_TICKS = 1;
    /**
     * Ticks AutoSpear Reach stays disarmed after it gave the spear back without a smash taking it. A target
     * hovering at the edge of the 3.0 to 4.5 band would otherwise select and drop the spear every other
     * tick, which is a slot flap vanilla input does not produce.
     */
    private static final int AUTO_SPEAR_REARM_TICKS = 4;
    private static final String MODE_AUTO = "Auto";
    private static final String MODE_HIT_ONLY = "Hit Only";
    private static final String MODE_MANUAL = "Manual Trigger";
    private static final double HARD_CODED_MIN_DROP = 1.5;
    /** Manual mouse dampening ramps in inside this distance while the camera aim is active. */
    private static final double OPTIMIZER_RANGE = 4.5;
    private static final double MANUAL_DAMPEN_FLOOR = 0.15;
    /**
     * Tracking runs at the Aim Speed setting with this response, no lead and no filter of the controller's
     * own: the smoothing happens once, upstream, in {@link AimSmoother}, on the planner's command. A second,
     * unmodelled filter inside the controller's loop would lag the arrival solve and ring.
     */
    private static final double TRACK_RESPONSE = 0.95;
    /** 1450 deg/s x 1.1 controller jitter = 80 degrees per tick at most, below Intave's 100 per tick. */
    private static final double MAX_TRACK_DEG_PER_SEC = 1450.0;
    /** The aim point stays this far inside every hitbox face. */
    private static final double AIM_INSET = 0.08;
    /** Tick heads the strike engine looks ahead (the mace follow-up window is six ticks). */
    private static final int STRIKE_HORIZON = 6;
    /**
     * The engine takes the view off ordinary tracking only when its strike needs it now: the strike is on
     * the next head, it is the mace after a landed axe, or the turn uses this share of what the rotation
     * can still reach by that head (the last responsible moment). Steering earlier turns the camera toward
     * a forecast several ticks out, which the target's next strafe then invalidates (a whip).
     */
    private static final double STRIKE_ENGAGE_USAGE = 0.6;
    /**
     * Intave's RotationSnapHeuristic flags a calm tick (under 9 degrees), then over 40, then calm again,
     * near an attack. After a calm tick the commanded yaw stays within 38 degrees of where the tick began,
     * so a large turn always ramps over at least two ticks.
     */
    private static final float SNAP_CALM_DEGREES = 9.0f;
    private static final float SNAP_STEP_DEGREES = 38.0f;
    /** Distance of the steering point along the commanded direction; long, so eye differences vanish. */
    private static final double AIM_DISTANCE = 64.0;
    /** Height of the aim point as a share of the hitbox: the centre of the body. */
    private static final double AIM_HEIGHT_FRACTION = 0.5;
    /**
     * How far the aim point wanders from the body's centre, as a share of the half-extent inside the
     * inset: sideways, front-to-back and up-down. It is slow value noise (a new target every second or
     * so, eased between), so the wander is a drift of the view and never a per-frame wobble.
     */
    private static final double BODY_WANDER_XZ = 0.45;
    private static final double BODY_WANDER_Y = 0.30;
    /** Seconds per unit of the body-wander noise (lower = faster drift). */
    private static final double BODY_WANDER_PERIOD = 1.4;
    /** Planner-output smoothing time constant while tracking, seconds. */
    private static final double TRACK_SMOOTH_SECONDS = 0.05;
    /** Floor and share of the time left that a committed strike's smoothing may use, seconds. */
    private static final double STRIKE_SMOOTH_MIN_SECONDS = 0.004;
    private static final double STRIKE_SMOOTH_SHARE = 0.3;
    /**
     * Straight above or below the aim point yaw is ill-conditioned (a centimetre of drift swings it
     * through any angle). Inside the inner radius yaw is held at its current value, ramping back to
     * the true bearing by the outer radius; both stay well inside the 0.3 half-width of a player box,
     * so the held ray still lands on the target.
     */
    private static final double YAW_HOLD_INNER = 0.15;
    private static final double YAW_HOLD_OUTER = 0.45;

    // ── Mode ──
    private final ModeSetting mode = new ModeSetting("Mode", MODE_AUTO, MODE_HIT_ONLY, MODE_MANUAL)
            .setDescription("Auto: aims at the target and lands the smash. Hit Only: never moves your view; smashes automatically only when your own crosshair is already on a valid player in reach. Manual Trigger: nothing fires on its own; your left click on a valid player while falling is replaced by the mace smash (or the axe stun slam against a blocker), then your weapon is restored.");

    // ── Aim ──
    private final BooleanSetting silentRotations = new BooleanSetting("Silent Aim", false)
            .setDescription("Aims with server-side rotation only, so the camera never moves. Smash clicks follow the same aim path.");
    private final SliderSetting aimSpeed = new SliderSetting("Aim Speed", 70.0, 1.0, 100.0, 0)
            .setDescription("How fast the view turns onto the target. 100 = as fast as the rotation controller allows; lower values turn more slowly. The aim itself is always a straight lock with no lead.");
    private final SliderSetting preAimDistance = new SliderSetting("Pre-Aim Distance", 3.0, 0.5, 8.0, 1)
            .setDescription("Blocks beyond the 3.0-block hit range at which the target is acquired and locked on. Tracking is always at full strength once acquired, in every direction.");
    private final BooleanSetting registrationCheck = new BooleanSetting("Registration Check", true)
            .setDescription("Only clicks when the hit should also register on the server: the target box as the server will hold it when the attack arrives (your latency ahead of what you see) must be within reach, and your vanilla fall distance must already allow the smash.");
    private final SliderSetting stunSlamHitChance = new SliderSetting("Stun Slam Hit Chance", 100.0, 0.0, 100.0, 0)
            .setDescription("Each time a stun slam is about to start, rolls 1-100 against this percentage. On a failed roll only the axe shield-disable is done and the mace follow-up is skipped.");

    // ── Targeting ──

    private final BooleanSetting lastHit = new BooleanSetting("Last Hit", true)
            .setDescription("Focuses the player you last hit with your own click (not a module's) and stays on them until they die or leave range, instead of switching to whoever is closest.");

    // ── Smash ──
    private final SliderSetting cooldown = new SliderSetting("Cooldown (ms)", 500.0, 100.0, 5000.0, 0)
            .withJitter(40)
            .setDescription("Internal cooldown in milliseconds between successive mace smash strike sequences, drawn around this value with its StDev once per smash.");
    /** Cooldown drawn for the current gap between smashes (re-drawn at every committed smash). */
    private long sampledCooldownMs = 500L;
    private long lastAttackTime = Long.MIN_VALUE / 4; // monotonic ms; "long ago" until the first hit
    private final BooleanSetting weaponOnly = new BooleanSetting("Weapon Only", false)
            .setDescription("Only works while you are holding a weapon (sword, axe, mace, spear or trident), so falling with blocks or food in hand never triggers a smash.");
    private final BooleanSetting autoSpearReach = new BooleanSetting("AutoSpear Reach", true)
            .setDescription("Reach is 3.0 blocks unless a spear is the held item, then 4.5. With a spear in the hotbar, when a target is about to come within spear reach the spear is selected, held, and the smash then swaps from it to the mace; the slot held before is restored if no smash follows. Not selected when the target holds or raises a shield, the mace already reaches, or the target is leaving. The swap reaction, reach, margin and hold timings are tuned in the AutoSpearReach module (it does not need to be switched on).");
    private final BooleanSetting horizontalReachOnly = new BooleanSetting("Horizontal Reach Only", false)
            .setDescription("With the AutoSpearReach module on (Auto Swap), only selects the spear while the target's horizontal (X/Z) distance from you is above 3.0 and below 4.5 blocks, whatever the height difference. The 3D reach is still checked: the forecast must put the target inside 4.5 on the tick the smash lands, so no swap is made for a hit that cannot reach. Off: the swap follows the 3D distance to the target's box.");

    // ── Stun Slam ──
    private static final String COUNTER_STUN = "Stun Slam";
    private static final String COUNTER_BACKSTAB = "Backstab";
    private static final String COUNTER_BACKSTAB_ELSE_STUN = "Backstab then Stun Slam";
    private final ModeSetting shieldCounter = new ModeSetting("Shield Counter", COUNTER_STUN, COUNTER_STUN, COUNTER_BACKSTAB, COUNTER_BACKSTAB_ELSE_STUN, "None")
            .setDescription("What to do when the target's shield is up and covers you. Stun Slam: axe the shield, then smash with the mace (backstabbing a blocker skips the axe). Backstab: do not attack into the shield; keep diving and aim at their back, and smash only once you are on their unshielded side. Backstab then Stun Slam: try the backstab first and fall back to the axe stun slam after the Backstab Give-Up time or when the fall is about to end. None: no special handling, so a smash into a raised shield is blocked.");
    private final ModeSetting stunSlamMode = new ModeSetting("StunSlam Mode", "Normal", "Adaptive")
            .setDescription("Normal fires the mace exactly the Stun Slam Ticks after the axe (1 = the very next tick). Adaptive follows the fall-speed profile (4/3/2/1 ticks, halved beyond 2 horizontal blocks, never below 1) as a ceiling and waits the most ticks up to it that a forecast of your fall and the target's motion says still lands the hit; if the window starts closing it fires early. The axe and the mace each go out on a tick head, swap first, with the mace visibly held.");
    private final SliderSetting stunSlamTicks = new SliderSetting("Stun Slam Ticks", 1.0, 1.0, 10.0, 0)
            .withJitter(0)
            .setDescription("Exact gap, in client ticks counted from the tick the axe was clicked in, between the axe and the mace in Normal mode (minimum 1: the mace never shares the axe's packet interval). 1 delivers the mace on the next tick.");
    private final SliderSetting stunSlamMinTicks = new SliderSetting("Minimum Ticks", 1.0, 1.0, 3.0, 0)
            .setDescription("Adaptive mode only: lowest axe-to-mace tick gap the planner prefers while the hit still holds; if the forecast says the next tick no longer lands, the mace goes earlier. The minimum is 1 tick: the mace on the very next tick head, the fastest follow-up vanilla can send. Counted in ticks from the tick the axe left in.");
    private final BooleanSetting onlyStunSlam = new BooleanSetting("Only Stunslam", false)
            .setDescription("Only executes attacks against blocking players; ignores unshielded targets.");

    // ── Equipment ──
    private final BooleanSetting autoShieldUnblock = new BooleanSetting("Auto Unblock", true)
            .setDescription("Drops your own shield as you close in on the target during the dive so the smash is not blocked by your own use, then re-raises it if you are still holding block.");
    private final ModeSetting maceType = new ModeSetting("Mace Type", "Best Damage", "Density")
            .setDescription("Best Damage estimates the smash damage of each hotbar mace against the target's armor for the current fall (Density, Breach and armor included) and uses the highest. Density always prefers the highest Density mace (Wind Burst first).");

    /**
     * Returns the distance between two positions on the horizontal X/Z plane.
     * Vertical separation is intentionally ignored: being directly above a target
     * does not make the target horizontally farther away.
     */
    static double horizontalDistanceBetween(double firstX, double firstZ, double secondX, double secondZ) {
        return Math.hypot(firstX - secondX, firstZ - secondZ);
    }

    private double horizontalDistanceToTarget(Entity target) {
        if (mc.player == null || target == null) {
            return Double.POSITIVE_INFINITY;
        }
        return horizontalDistanceBetween(mc.player.getX(), mc.player.getZ(), target.getX(), target.getZ());
    }

    /** Returns the adaptive axe-to-mace gap for vanilla downward Y velocity. */
    static int adaptiveStunTickGap(double velocityY) {
        return adaptiveStunTickGap(velocityY, 0.0);
    }

    /** Profile gap after horizontal spacing (4 -> 2, 3 -> 1, 2 -> 1 beyond two horizontal blocks). */
    static int adaptiveStunTickGap(double velocityY, double horizontalDistance) {
        return adaptiveStunTickGap(velocityY, horizontalDistance, Double.POSITIVE_INFINITY);
    }

    /** Ticks the forecast-less fallback removes for a short absolute (3D) distance. */
    static int closeRangeTickReduction(double distance3d) {
        return StunSlamEngine.closeRangeReduction(distance3d);
    }

    /**
     * Forecast-less adaptive gap: the fall-speed profile, halved beyond two horizontal blocks, then
     * shortened by the close-range rule. The live planner ({@link StunSlamEngine#plan}) uses only the
     * profile ceiling and decides the rest from the forecast.
     */
    static int adaptiveStunTickGap(double velocityY, double horizontalDistance, double distance3d) {
        return StunSlamEngine.fallbackGap(velocityY, horizontalDistance, distance3d);
    }

    /** Fixed inputs of the slam in flight: Normal gap or Adaptive ceiling/floor, reach, registration. */
    private StunSlamEngine.Params stunParams = null;
    /** The gap chosen when the axe left, and the confidence of each candidate tick. */
    private StunSlamEngine.Plan stunPlan = null;
    /** Forecast box of the target for the next tick head, refreshed each tick of the follow-up. */
    private Box stunForecastBox = null;

    private StunSlamEngine.Params buildStunParams(Entity target) {
        double reach = attackRange(target);
        boolean registration = registrationCheck.isEnabled();
        if (stunSlamMode.is("Adaptive")) {
            int ceiling = StunSlamEngine.profileCeiling(
                    mc.player != null ? mc.player.getVelocity().y : 0.0, horizontalDistanceToTarget(target));
            return StunSlamEngine.Params.adaptive(ceiling, (int) Math.round(stunSlamMinTicks.getValue()),
                    reach, registration);
        }
        return StunSlamEngine.Params.normal(Math.max(1, (int) Math.round(stunSlamTicks.sample())), reach, registration);
    }

    /** Whole ticks of connection lead the server box carries (round trip, as the registration check uses). */
    private int stunServerLead() {
        return registrationCheck.isEnabled() ? InterceptPredictor.getInstance().lagTicks() : 0;
    }

    /** Plans the follow-up the moment the axe has left. Falls back to the profile alone if the forecast fails. */
    private void planStunFollowUp(Entity target) {
        stunParams = buildStunParams(target);
        StunSlamEngine.Plan plan;
        try {
            if (mc.player == null || !(target instanceof PlayerEntity victim)) {
                throw new IllegalStateException("no target");
            }
            plan = StunSlamEngine.plan(StunSlamEngine.observe(mc, victim, stunServerLead()), stunParams);
        } catch (RuntimeException failure) {
            int gap = stunParams.exact() ? stunParams.exactGap()
                    : adaptiveStunTickGap(mc.player != null ? mc.player.getVelocity().y : 0.0,
                            horizontalDistanceToTarget(target),
                            mc.player != null && target != null ? mc.player.distanceTo(target)
                                    : Double.POSITIVE_INFINITY);
            plan = new StunSlamEngine.Plan(Math.max(1, gap), 0.0, new double[0],
                    stunParams.exact() ? StunSlamEngine.Source.EXACT : StunSlamEngine.Source.BEST_EFFORT);
        }
        stunPlan = plan;
        stunRequiredTickGap = Math.max(1, plan.gap());
    }

    /**
     * The engine's verdict for this tick head, from a fresh forecast. A forecast failure never blocks
     * the slam: Normal fires on its gap, Adaptive on its planned gap, both still behind the live ray.
     */
    private StunSlamEngine.Decision decideStunFollowUp(int ticksSinceAxe, boolean liveHitValid) {
        StunSlamEngine.Params params = stunParams != null ? stunParams : buildStunParams(currentTarget);
        StunSlamEngine.Plan plan = stunPlan != null ? stunPlan
                : new StunSlamEngine.Plan(Math.max(1, stunRequiredTickGap), 0.0, new double[0],
                        StunSlamEngine.Source.EXACT);
        StunSlamEngine.Outlook live = null;
        try {
            if (mc.player != null && currentTarget != null) {
                live = StunSlamEngine.observe(mc, currentTarget, stunServerLead());
                stunForecastBox = live.likeliestVisible(1);
            }
        } catch (RuntimeException failure) {
            stunForecastBox = null;
        }
        if (live == null) {
            // No forecast: hold to the planned gap only.
            live = new StunSlamEngine.Outlook(new net.minecraft.util.math.Vec3d[] {
                    mc.player != null ? mc.player.getEyePos() : net.minecraft.util.math.Vec3d.ZERO,
                    mc.player != null ? mc.player.getEyePos() : net.minecraft.util.math.Vec3d.ZERO},
                    new boolean[2], java.util.List.of(), 0.0f, 0.0f);
            params = new StunSlamEngine.Params(true, plan.gap(), plan.gap(), 1, params.reach(), false);
        }
        return StunSlamEngine.decide(params, plan, live, ticksSinceAxe, liveHitValid);
    }

    /**
     * The mace follow-up is due once the configured gap has elapsed in client ticks counted from the
     * tick the axe click was issued in (the unit Normal "Stun Slam Ticks" and the Adaptive buckets
     * are tuned in), and the mace would leave on a strictly later wire tick than the axe, so the
     * two never share a movement-packet interval.
     */
    static boolean stunFollowUpDue(long clientTicksSinceAxe, long wireTicksSinceAxe, int requiredGap) {
        return wireTicksSinceAxe >= 1L && clientTicksSinceAxe >= Math.max(1, requiredGap);
    }

    /**
     * Ticks since the axe in the unit the engine is asked in. Normal counts client ticks from the axe
     * click (what "Stun Slam Ticks" is tuned in). Adaptive counts the movement-packet intervals since
     * the axe's own, because that is the forecast's index: the plan is observed when the axe goes out,
     * so index 0 is the axe's interval whether the axe left inside a tick or was held from a render
     * frame to the next tick head.
     */
    static long stunEngineTicksSinceAxe(boolean adaptive, long clientTicksSinceAxe, long wireTicksSinceAxe) {
        return adaptive ? wireTicksSinceAxe : clientTicksSinceAxe;
    }

    /**
     * A smash is still deliverable: airborne, not gliding, and fallen past the vanilla threshold. A
     * landing between the axe and the mace resets the server fall distance, so the mace would only be
     * a plain hit; the slam unwinds instead.
     */
    private boolean stunSmashStillAvailable() {
        return mc.player != null && !mc.player.isOnGround() && !mc.player.isGliding()
                && !mc.player.isClimbing() && !isInLiquidOrWeb() && !mc.player.isTouchingWater()
                && !mc.player.hasVehicle() && currentFallDistance() >= HARD_CODED_MIN_DROP;
    }

    private boolean isStunFollowUpDue() {
        if (stunAxeExecutedTickId < 0L || stunAxeWireTickId < 0L) {
            return false;
        }
        long clientTicksSinceAxe = stunClientTickId - stunAxeExecutedTickId;
        long wireTicksSinceAxe = currentWireTick() - stunAxeWireTickId;
        if (wireTicksSinceAxe < 1L || clientTicksSinceAxe < 1L) {
            return false;
        }
        if (stunSlamMode.is("Adaptive")) {
            return true;
        }
        return stunFollowUpDue(clientTicksSinceAxe, wireTicksSinceAxe, stunRequiredTickGap);
    }

    /**
     * The movement-packet interval an action dispatched right now leaves in. Inside the action
     * window that is the current tick; from a render frame the window is closed, so
     * {@link TickActionOrder} holds the packet and releases it at the next tick head.
     * Counting the axe-to-mace gap on this clock (not on the tick a click was issued in) is what
     * keeps a gap of N meaning N ticks on the wire whichever path dispatches the mace.
     */
    private long currentWireTick() {
        return stunClientTickId + (TickActionOrder.isWindowOpen() ? 0L : 1L);
    }

    private boolean shouldReblockShield = false;
    // Minecraft's render-loop callback is authoritative. HudRenderCallback remains a fallback for
    // unusual clients where another mixin prevents the primary callback from reaching us.
    private boolean primaryRenderPassSeen = false;
    private net.minecraft.client.world.ClientWorld lastWorld = null;

    private PlayerEntity currentTarget = null;
    /** Target the Initial Hit Delay is counting for, and the client tick it was acquired on. */
    private PlayerEntity initialDelayTarget = null;
    private long initialDelayStartTick = 0L;
    /** Last Hit: the player the user last hit themselves, how long ago, and the player currently locked. */
    private java.util.UUID lastUserHitId = null;
    private long lastUserHitMs = 0L;
    private java.util.UUID lockedTargetId = null;
    private static final long LAST_HIT_MEMORY_MS = 2000L;
    private PlayerEntity lastAimTarget = null;
    /** True while this module owns a live rotation request, so release happens once per engagement. */
    private boolean aimRequested = false;
    private int originalSlot = -1;
    private int preSequenceSlot = -1;
    // DETERMINISM: every former System.currentTimeMillis() gate is now an int TICK counter,
    // decremented (or counted up) once per onTick and clamped at 0. Wall-clock gating is gone —
    // a frozen/jumped game clock can no longer strand the cooldown or the axe→mace window.
    // cooldownTicks counts DOWN: the smash/combo is "ready" once it reaches 0.
    private int cooldownTicks = 0;
    private boolean awaitServerBounce = false;
    private long awaitBounceUntil = 0L;

    /**
     * Monotonic milliseconds. Every interval in this module is measured on it: a wall-clock step
     * (NTP correction, manual clock change) can no longer strand a pause, cooldown or wait in the future.
     */
    private static long monoMs() {
        return System.nanoTime() / 1_000_000L;
    }

    /** The local player's round-trip latency from the player list, clamped to 0..1000 ms. */
    private int pingMs() {
        if (mc.getNetworkHandler() == null || mc.player == null) {
            return 0;
        }
        net.minecraft.client.network.PlayerListEntry entry = mc.getNetworkHandler().getPlayerListEntry(mc.player.getUuid());
        return entry == null ? 0 : MathHelper.clamp(entry.getLatency(), 0, 1000);
    }

    /**
     * Waits for the server's answer to a mace hit (the reset fall / Wind Burst bounce) before another
     * smash is armed in the same fall. The answer needs about one round trip, so the wait is that plus a
     * margin, never below {@code floorMs}, and capped so it always lapses. Landing or rising clears it.
     */
    private void armBounceWait(long floorMs) {
        awaitServerBounce = true;
        awaitBounceUntil = monoMs() + MathHelper.clamp(pingMs() + BOUNCE_WAIT_MARGIN_MS, floorMs, BOUNCE_WAIT_MAX_MS);
    }

    /**
     * The bounce wait, cleared on read once the player lands, rises or the deadline passes, so a read from
     * any path (including one reached while the render pass is paused) can never see a lapsed wait.
     */
    private boolean isAwaitingBounce() {
        if (awaitServerBounce && (mc.player == null || mc.player.isOnGround()
                || mc.player.getVelocity().y > 0 || monoMs() > awaitBounceUntil)) {
            awaitServerBounce = false;
        }
        return awaitServerBounce;
    }

    private static final long BOUNCE_WAIT_MARGIN_MS = 100L;
    private static final long BOUNCE_WAIT_MAX_MS = 1200L;

    private double highestY = 0.0;

    // Cached AutoElytra reference (refreshed lazily, null-safe) for the direct divebomb API that
    // replaces the AutoMaceSyncEvent handshake.
    private AutoElytra autoElytra = null;

    private static final double MS_PER_TICK = 50.0;
    // Min blocks of empty space below the feet (straight-down raycast) before we ask AutoElytra to drop
    // the wing for a divebomb. A ground-level breach/mace-swap hop leaves <=2b of clearance, so this keeps
    // the swap from ever touching the elytra (drop + re-equip-on-landing flap) while on/near the ground.
    // NOTE this gates ONLY the elytra-drop request — the mace smash / breach swap itself is unaffected.
    private static final double MIN_DIVE_CLEARANCE = 2.0;

    // Fast direct mace smash. The render path switches to the mace as soon as reach is valid,
    // then gives vanilla's selected-slot sync a short render-frame lead before the attack.
    // Tick-anchored armed latch. A per-frame raise sets this; ONLY onTick
    // consumes/clears it (after firing). This stops the per-frame raise from being discarded by the
    // next evaluateLogic() before the tick path ever sees it — the core high-speed-miss fix.
    private boolean shouldDirectSmashArmed = false;
    private long directMaceHitDueNs = 0L;
    /** Client tick in which the direct-smash slot was selected; the attack must land in that same tick. */
    private long directMacePreparedTickId = -1L;
    /** Movement-packet interval of that selection; the hit must leave in the same one. */
    private long directMacePreparedInterval = -1L;
    private long directMaceRetryUntilNs = 0L;
    private static final long DIRECT_MODE_RETRY_WINDOW_NS = 150_000_000L;
    /** Slot this module last selected itself; swap-back only restores while it is still held. */
    private int moduleSelectedSlot = -1;
    private int directMacePreparedSlot = -1;
    // Invalidates delayed callbacks without requiring a new payload-to-loader API. This preserves
    // compatibility with older matched loader boundaries while ensuring stale callbacks no-op.
    private long sequenceEpoch = 0L;

    /**
     * True from the instant a burst begins until it fully unwinds.
     *
     * <p><b>This is the fix for the stun slam landing on a live shield.</b> onTick clears
     * {@code shouldDirectSmashArmed} BEFORE calling startSmashBurst, and neither
     * {@code smashStage} nor {@code stunSlamState} is set until doAxeDisable has already
     * returned. So at the exact moment {@code accessor.attack()} fires the axe,
     * {@link #isSmashArmed()} was false, {@link #onCombatSwapLock} did not lock, and the
     * attack re-entered MinecraftClientMixin.onDoAttack -- where a handler registered
     * EARLIER in ModuleManager and therefore given first refusal saw the crosshair
     * doAxeDisable had just forged, found itself grounded and not falling, and fell
     * through to startBreachSwap: it swapped to a mace and swung THAT into the still-raised
     * shield. The axe swing that was supposed to break the shield never happened.
     *
     * <p>Raising the claim before any attack closes the window without touching those handlers,
     * whose existing CombatSwapLockEvent check then does the right thing.
     */
    private boolean burstClaimed = false;
    /** True while this module's own dispatched attack runs through doAttack, so handleAttack ignores it. */
    private boolean reentrantAttack = false;
    /** Set by the attackEntity callback while this module's own click is in flight: the real hit result. */
    private boolean attackLandedThisClick = false;

    /** Ticks the mace follow-up may keep retrying (ray settling, hotbar window) before the slam is unwound. */
    private static final int STUN_MACE_RETRY_TICKS = 6;
    /** Ticks the axe may keep retrying before the attempt is unwound (below the 30-tick wedge watchdog). */
    private static final int STUN_AXE_MAX_WAIT_TICKS = 20;

    private long stunAxeQueuedAtMs = 0L;
    private boolean stunAxeAttackExecuted = false;
    private boolean stunMaceAttackExecuted = false;

    // ── Tick-locked Stun Slam timing ──────────────────────────────────────────
    // Normal Stun Slam is two adjacent logical client ticks: axe on N, mace on N+1.
    // Adaptive mode may shorten that gap based on fall speed and horizontal target distance. Both
    // interactions travel through the ordinary hotbar-key + vanilla attack path; no combat/slot
    // packet is constructed here.
    private long stunClientTickId = 0L;
    private long stunAxeExecutedTickId = -1L;
    /** Wire tick the axe leaves in (a render-frame axe is held to the next tick head). */
    private long stunAxeWireTickId = -1L;
    private int stunRequiredTickGap = 1;

    private int smashStage = 0;        // 0 = idle, 1 = swap back next tick, 2 = hold mace one extra tick for a true click to land, then swap back
    private int smashReturnSlot = -1;
    private int lastWeaponSlot = -1;
    private boolean swappedByModule = false;
    private int stunSwapBackHoldTicks = 0;
    /** Tick heads left before the direct smash burst restores the previous slot (Swap Back Delay). */
    private int smashSwapBackHoldTicks = 0;
    /** Rolled once per stun slam: true means axe the shield only and skip the mace follow-up. */
    private boolean stunSlamMissed = false;

    public boolean isBusy() {
        return burstClaimed || smashStage > 0 || stunSlamState != StunSlamState.IDLE;
    }

    private boolean isMaceSlot(int slot) {
        if (mc.player == null || slot < 0 || slot > 8) {
            return false;
        }
        ItemStack stack = mc.player.getInventory().getStack(slot);
        return stack != null && stack.getItem() instanceof MaceItem;
    }

    private boolean isNonMaceWeapon(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return false;
        }
        if (stack.getItem() instanceof MaceItem) {
            return false;
        }
        return CombatItemUtil.isWeapon(stack);
    }

    /** A spear whose held attack range is the 4.5-block spear reach (tridents are not). */
    private boolean isReachSpear(ItemStack stack) {
        return stack != null && !stack.isEmpty()
                && Registries.ITEM.getId(stack.getItem()).getPath().contains("spear");
    }

    private boolean isSpear(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return false;
        }
        Item item = stack.getItem();
        return item instanceof net.minecraft.item.TridentItem
                || Registries.ITEM.getId(item).getPath().contains("spear");
    }

    public int findWeaponSlot() {
        if (mc.player == null) {
            return -1;
        }
        // 1. If smashReturnSlot contains a valid non-mace item, return it
        if (smashReturnSlot >= 0 && smashReturnSlot < 9 && !isMaceSlot(smashReturnSlot)) {
            return smashReturnSlot;
        }
        // 2. If preSequenceSlot contains a valid non-mace item, return it
        if (preSequenceSlot >= 0 && preSequenceSlot < 9 && !isMaceSlot(preSequenceSlot)) {
            return preSequenceSlot;
        }
        // 3. If originalSlot contains a valid non-mace item, return it
        if (originalSlot >= 0 && originalSlot < 9 && !isMaceSlot(originalSlot)) {
            return originalSlot;
        }
        // 4. If lastWeaponSlot contains a valid non-mace item, return it
        if (lastWeaponSlot >= 0 && lastWeaponSlot < 9 && !isMaceSlot(lastWeaponSlot)) {
            ItemStack stack = mc.player.getInventory().getStack(lastWeaponSlot);
            if (stack != null && !stack.isEmpty()) {
                return lastWeaponSlot;
            }
        }
        // 5. Look for a Sword in hotbar
        for (int i = 0; i < 9; i++) {
            ItemStack stack = mc.player.getInventory().getStack(i);
            if (stack != null && CombatItemUtil.isSword(stack)) {
                return i;
            }
        }
        // 6. Look for a Spear / Trident in hotbar
        for (int i = 0; i < 9; i++) {
            ItemStack stack = mc.player.getInventory().getStack(i);
            if (stack != null && isSpear(stack)) {
                return i;
            }
        }
        // 7. Look for an Axe in hotbar
        for (int i = 0; i < 9; i++) {
            ItemStack stack = mc.player.getInventory().getStack(i);
            if (stack != null && CombatItemUtil.isAxe(stack)) {
                return i;
            }
        }
        // 8. Look for any other non-mace item in hotbar
        for (int i = 0; i < 9; i++) {
            ItemStack stack = mc.player.getInventory().getStack(i);
            if (stack != null && !stack.isEmpty() && !isMaceSlot(i)) {
                return i;
            }
        }
        return -1;
    }

    // ───────────────────────────── AutoSpear Reach ─────────────────────────────

    /** True while this module holds the spear it selected and nothing else has changed the hotbar. */
    private boolean isAutoSpearHeld() {
        return autoSpearSlot >= 0 && mc.player != null
                && mc.player.getInventory().getSelectedSlot() == autoSpearSlot;
    }

    /**
     * While the spear this module selected is in hand, a right click would start charging it (the
     * player meant the shield or nothing): vanilla use is cancelled so the spear stays a reach-only
     * selection and the smash can take it.
     */
    @Override
    public boolean shouldBlockItemUse() {
        return isEnabled() && isAutoSpearHeld();
    }

    private boolean autoSpearSelectedThisInterval() {
        return isAutoSpearHeld() && autoSpearSwapInterval == TickActionOrder.interval();
    }

    /** A smash that starts from the AutoSpear spear returns to the slot held before it, not to the spear. */
    private int autoSpearReturnSlot(int slot) {
        if (autoSpearSlot >= 0 && slot == autoSpearSlot && autoSpearPrevSlot >= 0 && autoSpearPrevSlot < 9
                && autoSpearPrevSlot != autoSpearSlot && !isMaceSlot(autoSpearPrevSlot)) {
            lastWeaponSlot = autoSpearPrevSlot;
            return autoSpearPrevSlot;
        }
        return slot;
    }

    private void clearAutoSpear() {
        spearSwapDueAtMs = -1L;
        autoSpearSlot = -1;
        autoSpearPrevSlot = -1;
        autoSpearSwapInterval = -1L;
        autoSpearHeldTicks = 0;
    }

    /** Gives the hotbar back to the slot held before the spear, only while the spear is still selected. */
    private void releaseAutoSpear() {
        if (autoSpearSlot < 0) {
            return;
        }
        autoSpearRearmTicks = AUTO_SPEAR_REARM_TICKS;
        if (mc.player != null && isAutoSpearHeld() && autoSpearPrevSlot >= 0 && autoSpearPrevSlot < 9) {
            if (!TickActionOrder.canSwitchSlot()) {
                TickActionOrder.deferSlot(autoSpearPrevSlot, autoSpearSlot);
            } else {
                selectSlotLegit(autoSpearPrevSlot);
            }
        }
        clearAutoSpear();
    }

    /**
     * The hotbar spear AutoSpear Reach selects. A spear one hit from breaking is skipped: the swap would
     * spend the tool on a reach the smash then does not need, and a broken stack reads as empty.
     */
    private int findReachSpearSlot() {
        if (mc.player == null) {
            return -1;
        }
        for (int i = 0; i < 9; i++) {
            ItemStack stack = mc.player.getInventory().getStack(i);
            if (isReachSpear(stack)
                    && !(stack.isDamageable() && stack.getDamage() >= stack.getMaxDamage() - 1)) {
                return i;
            }
        }
        return -1;
    }

    /**
     * True while the target has a shield up or is raising one, and it covers (or can turn to cover) us.
     * {@link #isTargetBlocking} only turns true after the shield's 5-tick warm-up, which is exactly the
     * window in which a spear swap would be wasted, so the raise itself counts here.
     */
    private boolean isShieldUpOrRaising(PlayerEntity target) {
        if (target == null || mc.player == null) {
            return false;
        }
        if (isTargetBlocking(target)) {
            return true;
        }
        if (!target.isUsingItem() || !target.getActiveItem().contains(DataComponentTypes.BLOCKS_ATTACKS)) {
            return false;
        }
        return shieldFacingDot(target) > SHIELD_RAISE_FACING_DOT;
    }

    /**
     * Whether a candidate may feed the reach plan. The smash goes to {@link #currentTarget}, so while
     * one is locked it is the only player worth a spear for; a swap made for a bystander would hold the
     * spear for a hit that never comes. With no target locked yet every valid player counts.
     */
    private boolean isSpearReachCandidate(PlayerEntity candidate) {
        if (candidate == mc.player || !isTargetable(candidate) || !isAimTargetValid(candidate)
                || isShieldUpOrRaising(candidate)) {
            return false;
        }
        return currentTarget == null || !currentTarget.isAlive() || candidate == currentTarget;
    }

    /**
     * The best swap plan over the valid targets that are not behind a shield, from the per-tick
     * forecast curve of the same tracking engine the predictive pre-aim uses
     * ({@link SpearReachPlanner#planGuarded}): the spear reach has to hold on the tick the smash lands,
     * a target the mace reaches by itself or one that is leaving is no swap, and with Horizontal Reach
     * Only the gate is the X/Z distance strictly between 3.0 and 4.5. Also reports whether the hold
     * should continue through {@code holdOut[0]}.
     */
    private SpearReachPlanner.Plan planSpearReach(boolean[] holdOut) {
        SpearReachPlanner.Plan best = SpearReachPlanner.Plan.NONE;
        boolean melee = false;
        boolean hold = false;
        boolean horizontal = horizontalReachOnly.isEnabled();
        InterceptPredictor engine = InterceptPredictor.getInstance();
        for (PlayerEntity candidate : mc.world.getPlayers()) {
            if (!isSpearReachCandidate(candidate)) {
                continue;
            }
            double radius = spearRange() + 2.0;
            if (mc.player.squaredDistanceTo(candidate) > radius * radius && !horizontal) {
                continue; // before the forecast curve, which costs a ground raycast and a simulation
            }
            double[] curve = engine.approachCurve(candidate, SpearReachPlanner.MAX_HORIZON);
            if (curve == null) {
                continue;
            }
            double horizontalDistance = horizontalDistanceToTarget(candidate);
            hold |= SpearReachPlanner.keepHolding(curve, spearRange(), spearHoldMargin())
                    && (!horizontal || SpearReachPlanner.keepHoldingHorizontal(
                            horizontalDistance, spearRange(), spearHoldMargin()));
            SpearReachPlanner.Plan plan = SpearReachPlanner.planGuarded(
                    curve, spearRange(), ATTACK_RANGE, horizontalDistance, horizontal);
            melee |= plan.inMeleeReach();
            if (plan.swapNow() && (!best.swapNow() || plan.entryTick() < best.entryTick())) {
                best = plan;
            }
        }
        if (holdOut != null) {
            holdOut[0] = hold;
        }
        return melee || !best.swapNow() ? SpearReachPlanner.Plan.NONE : best;
    }

    /** An armed spear-swap reaction whose opening never came is dropped this long after it fell due. */
    private static final long SPEAR_SWAP_ARM_STALE_MS = 500L;

    /**
     * True when a spear-reach candidate, outside 3.0 now, is forecast to enter the spear reach within
     * {@code ticks}: the opening the AutoSpearReach Swap Reaction is timed from ({@link SpearReachPlanner#opensWithin}).
     */
    private boolean spearOpeningWithin(double ticks) {
        InterceptPredictor engine = InterceptPredictor.getInstance();
        double radius = SpearReachPlanner.candidateRadius(spearRange(), 0.0);
        for (PlayerEntity candidate : mc.world.getPlayers()) {
            if (mc.player.squaredDistanceTo(candidate) > radius * radius || !isSpearReachCandidate(candidate)) {
                continue;
            }
            double[] curve = engine.approachCurve(candidate, SpearReachPlanner.MAX_HORIZON);
            if (SpearReachPlanner.opensWithin(curve, spearRange(), ATTACK_RANGE, ticks)) {
                return true;
            }
        }
        return false;
    }

    /**
     * AutoSpear Reach. While a smash-eligible fall is under way with no sequence running: select the
     * hotbar spear as soon as a target is already inside spear reach (but not inside 3.0), hold it
     * for one tick ({@link #AUTO_SPEAR_MAX_HOLD_TICKS}), and give the previous slot back if no smash
     * took it or the target raises a shield. The smash itself then swaps from the spear to the mace.
     */
    private void tickAutoSpearReach() {
        if (mc.player == null || mc.world == null || mc.interactionManager == null) {
            return;
        }
        if (autoSpearSlot >= 0) {
            if (!isAutoSpearHeld()) {
                clearAutoSpear(); // the hotbar moved on (smash swap, user, another module): nothing to restore
                return;
            }
            if (isBusy() || burstClaimed || smashStage > 0 || stunSlamState != StunSlamState.IDLE) {
                return; // the sequence owns the selection and restores through its own return slot
            }
            if (mc.player.isUsingItem() && isReachSpear(mc.player.getActiveItem()) && TickActionOrder.isWindowOpen()) {
                // A charge that slipped in before the use block: stop it at the tick head, so the mace
                // selection is never refused for an item in use.
                mc.interactionManager.stopUsingItem(mc.player);
                TickActionOrder.recordUse();
            }
            autoSpearHeldTicks++;
            boolean[] hold = new boolean[1];
            planSpearReach(hold);
            // planSpearReach skips every target behind a shield or raising one, so a target that
            // raises it drops out of the forecast and the spear goes back this same tick.
            boolean keep = autoSpearEnabled() && hasSmashFallRequirement()
                    && autoSpearHeldTicks <= spearMaxHoldTicks() && hold[0];
            if (!keep) {
                releaseAutoSpear();
                return;
            }
            return;
        }
        if (autoSpearRearmTicks > 0) {
            autoSpearRearmTicks--;
            return;
        }
        if (!autoSpearEnabled() || isBusy() || burstClaimed || smashStage > 0
                || stunSlamState != StunSlamState.IDLE || shouldDirectSmashArmed
                || mc.currentScreen != null || mc.player.isUsingItem() || mc.player.isSpectator()
                || mc.player.isCreative() || isAwaitingBounce() || cooldownTicks > 0
                || !hasSmashFallRequirement() || isInLiquidOrWeb() || elytraBlocksAction()
                || (weaponOnly.isEnabled() && !isHoldingWeapon())) {
            return;
        }
        if (isReachSpear(mc.player.getMainHandStack())) {
            return; // already on a spear (the player's own): the reach applies without a swap
        }
        if (findBestMace() == -1 && !(mc.player.getMainHandStack().getItem() instanceof MaceItem)) {
            return;
        }
        int spearSlot = findReachSpearSlot();
        if (spearSlot < 0 || !TickActionOrder.canSwitchSlot()) {
            return;
        }
        CombatSwapLockEvent lock = new CombatSwapLockEvent();
        EventBus.post(lock);
        if (lock.isItemSwapLocked()) {
            return;
        }
        // Worth a swap only when contact is forecast inside spear reach within the speed-scaled lead
        // and is not already inside 3.0; a target raising or holding a shield never is.
        boolean swapNow = planSpearReach(null).swapNow();
        long nowMs = monoMs();
        if (spearSwapDueAtMs >= 0L && nowMs > spearSwapDueAtMs + SPEAR_SWAP_ARM_STALE_MS) {
            spearSwapDueAtMs = -1L; // armed for an opening that never came
        }
        if (spearSwapDueAtMs < 0L) {
            // The Swap Reaction runs from the moment the opening is FORESEEN, so it has elapsed by the
            // tick the target is inside the 3.0-4.5 band. A dive crosses that band in about one tick:
            // timed from the band tick itself, the reaction always ran out with the target already
            // inside 3.0, the spear was never selected and no smash landed from beyond 3 blocks.
            AutoSpearReach tuning = spearTuning();
            long reaction = tuning != null ? tuning.swapReactionMs() : 0L;
            // Checked at tick heads only: one extra tick of lookahead for the tick phase.
            double reactionTicks = Math.ceil(reaction / MS_PER_TICK) + 1.0;
            if (!swapNow && !spearOpeningWithin(reactionTicks)) {
                return;
            }
            spearSwapDueAtMs = nowMs + reaction;
        }
        if (!swapNow || nowMs < spearSwapDueAtMs) {
            return;
        }
        spearSwapDueAtMs = -1L;
        int prev = userHeldSlot();
        if (!selectSlotLegit(spearSlot)) {
            return;
        }
        autoSpearSlot = spearSlot;
        autoSpearPrevSlot = prev;
        autoSpearSwapInterval = TickActionOrder.interval();
        autoSpearHeldTicks = 0;
    }

    private void swapBackToWeapon() {
        if (mc.player == null) {
            smashReturnSlot = -1;
            preSequenceSlot = -1;
            originalSlot = -1;
            swappedByModule = false;
            return;
        }
        if (smashReturnSlot != -1 || preSequenceSlot != -1 || originalSlot != -1 || swappedByModule || isBusy()) {
            int targetWeaponSlot = -1;
            // AutoLunge is mid swap-back: the spear is not a slot to return to, its slot is.
            AutoLunge lunge = ModuleAccess.getModule(AutoLunge.class);
            int lungeReturn = lunge != null ? lunge.getPendingReturnSlot() : -1;
            if (lungeReturn >= 0 && !isMaceSlot(lungeReturn)) {
                targetWeaponSlot = lungeReturn;
            } else if (smashReturnSlot >= 0 && smashReturnSlot < 9 && !isMaceSlot(smashReturnSlot)) {
                targetWeaponSlot = smashReturnSlot;
            } else if (preSequenceSlot >= 0 && preSequenceSlot < 9 && !isMaceSlot(preSequenceSlot)) {
                targetWeaponSlot = preSequenceSlot;
            } else if (originalSlot >= 0 && originalSlot < 9 && !isMaceSlot(originalSlot)) {
                targetWeaponSlot = originalSlot;
            } else if (lastWeaponSlot >= 0 && lastWeaponSlot < 9 && !isMaceSlot(lastWeaponSlot)) {
                targetWeaponSlot = lastWeaponSlot;
            } else {
                targetWeaponSlot = findWeaponSlot();
            }

            // Never overwrite a newer user/module choice: restore only while the slot this module
            // selected is still the held one.
            boolean stillOwnsSlot = moduleSelectedSlot < 0
                    || mc.player.getInventory().getSelectedSlot() == moduleSelectedSlot;
            if (targetWeaponSlot >= 0 && targetWeaponSlot < 9 && stillOwnsSlot) {
                if (mc.player.getInventory().getSelectedSlot() != targetWeaponSlot
                        && !TickActionOrder.canSwitchSlot()) {
                    // An attack or use-release already went out in this packet interval: a slot change
                    // now would follow it, which vanilla never sends. Restore at the next interval.
                    TickActionOrder.deferSlot(targetWeaponSlot, mc.player.getInventory().getSelectedSlot());
                } else {
                    selectSlotLegit(targetWeaponSlot);
                }
            }
        }
        moduleSelectedSlot = -1;
        smashReturnSlot = -1;
        preSequenceSlot = -1;
        originalSlot = -1;
        swappedByModule = false;
    }

    private enum StunSlamState {
        IDLE,
        AXE_ATTACK,
        WAITING_DELAY,
        MACE_SMASH,
        SWAP_BACK
    }

    private StunSlamState stunSlamState = StunSlamState.IDLE;

    // ── AutoSpear Reach ── the spear slot this module selected, the slot held before it, the packet
    // interval of the selection, and how many ticks it has been held.
    private int autoSpearSlot = -1;
    private long spearSwapDueAtMs = -1L;

    /**
     * The AutoSpearReach module, whether or not it is switched on itself: its reach, margin, reaction and
     * hold settings drive the swap, and the "AutoSpear Reach" setting here is the switch for the feature.
     */
    private AutoSpearReach spearTuning() {
        AutoSpearReach module = ModuleAccess.getModule(AutoSpearReach.class);
        return module;
    }

    private boolean autoSpearEnabled() {
        AutoSpearReach tuning = spearTuning();
        return autoSpearReach.isEnabled() && tuning != null && tuning.autoSwapEnabled();
    }

    private double spearRange() {
        AutoSpearReach tuning = spearTuning();
        return tuning != null ? tuning.spearReach() : SPEAR_ATTACK_RANGE;
    }

    private double spearHoldMargin() {
        AutoSpearReach tuning = spearTuning();
        return tuning != null ? tuning.holdMargin() : AUTO_SPEAR_HOLD_MARGIN;
    }

    private int spearMaxHoldTicks() {
        AutoSpearReach tuning = spearTuning();
        return tuning != null ? tuning.maxHoldTicks() : AUTO_SPEAR_MAX_HOLD_TICKS;
    }
    private int autoSpearPrevSlot = -1;
    private long autoSpearSwapInterval = -1L;
    private int autoSpearHeldTicks = 0;
    /** Ticks left before AutoSpear Reach may select the spear again after giving it back unused. */
    private int autoSpearRearmTicks = 0;
    /** True from a smash or stun slam that started from a held spear until that sequence ends; the reach stays 4.5 through the axe and mace swaps. */
    private boolean spearReachSequence = false;
    private int elapsedStunTicks = 0;
    private int reachWaitTicks = 0;

    // ── RECOVERY WATCHDOG ──
    // Number of consecutive ticks ANY multi-tick sequence latch has been held. Every latch below
    // is meant to clear within a few ticks; if one is held far longer the state machine has WEDGED
    // (an interrupted armor-swap / smash-burst / reset / click-burst, a target that vanished
    // mid-sequence, or a hotbar-slot desync) and the module silently stops triggering. When the
    // counter crosses WEDGE_WATCHDOG_TICKS we force recover() so the mace can NEVER get stuck —
    // this is the hard guarantee that it always returns to a firing state. Reset to 0 whenever the
    // module is cleanly idle, so normal rapid combat (latch → clear → latch) never trips it.
    private int wedgeTicks = 0;
    private static final int WEDGE_WATCHDOG_TICKS = 30; // 1.5s @20TPS — far above any real sequence

    // ── EXTERNAL PAUSE ── another module (e.g. PearlMacro) can suppress AutoMace for a moment so it
    // neither rotates the camera off the pearl's axis nor swings mid-throw. Wall-clock TTL refreshed
    // by the suppressor each tick; auto-expires so a forgotten suppressor can never wedge us off.
    private long externalPauseUntilMs = 0L;
    /** No single pause request may hold AutoMace longer than this; suppressors refresh it every tick. */
    private static final long MAX_EXTERNAL_PAUSE_MS = 3000L;
    public void externalPause(long ttlMs) {
        long now = monoMs();
        long until = now + MathHelper.clamp(ttlMs, 0L, MAX_EXTERNAL_PAUSE_MS);
        // Never extend a deadline already further out than one request may reach (a stale one is dropped).
        long current = externalPauseUntilMs - now > MAX_EXTERNAL_PAUSE_MS ? 0L : externalPauseUntilMs;
        externalPauseUntilMs = Math.max(current, until);
    }
    public void externalResume() {
        externalPauseUntilMs = 0L;
    }

    private boolean isExternalPauseActive() {
        long left = externalPauseUntilMs - monoMs();
        if (left > MAX_EXTERNAL_PAUSE_MS) {
            externalPauseUntilMs = 0L; // cannot come from a clamped request: drop it rather than wedge
            return false;
        }
        return left > 0L;
    }

    // ── EXCLUSIVE ROTATION LOCK ── PearlCatch/PearlGrapple/WebEscape take RotationManager's exclusive lock
    // for a throw. A lock left behind by a module that has since been disabled, or held far longer than any
    // pearl sequence lasts, would keep AutoMace standing down for the rest of the session; it is ignored.
    private static final long STALE_EXCLUSIVE_LOCK_MS = 8000L;
    private Object staleLockReported = null;

    private boolean isRotationLockedByOther() {
        if (!RotationManager.isExclusiveLocked()) {
            staleLockReported = null;
            return false;
        }
        Object owner = RotationManager.getExclusiveOwner();
        boolean stale = (owner instanceof Module m && !m.isEnabled() && !m.isOneShot())
                || RotationManager.exclusiveLockHeldMs() > STALE_EXCLUSIVE_LOCK_MS;
        if (!stale) {
            return true;
        }
        if (staleLockReported != owner) {
            staleLockReported = owner;
            ClientInternalLog.warn(getName(), "ignoring a stale exclusive rotation lock held by "
                    + (owner == null ? "null" : owner.getClass().getSimpleName()));
        }
        return false;
    }

    // ── AUTOBREACH PRESSURE ── PearlGrapple arms this right after a grapple onto a target that is high
    // above the ground: the next mace hit is the Breach mace's (armor cut), the hit after it the Density
    // mace's again, then Mace Type applies as before. It only chooses WHICH hotbar mace the smash uses;
    // timing, aim, reach and dispatch are unchanged.
    private enum PressureStep { NONE, BREACH, DENSITY }
    private PressureStep pressureStep = PressureStep.NONE;
    private long pressureUntilMs = 0L;

    /** Arms the Breach-then-Density pair; it lapses on its own after {@code ttlMs} if no hit uses it. */
    public void armBreachPressure(long ttlMs) {
        pressureStep = PressureStep.BREACH;
        pressureUntilMs = monoMs() + ttlMs;
    }

    private PressureStep activePressureStep() {
        if (pressureStep != PressureStep.NONE && monoMs() >= pressureUntilMs) {
            pressureStep = PressureStep.NONE;
        }
        return pressureStep;
    }

    /** Called once per committed mace hit: Breach -> Density -> normal. */
    private void advancePressure() {
        PressureStep step = activePressureStep();
        pressureStep = step == PressureStep.BREACH ? PressureStep.DENSITY : PressureStep.NONE;
    }
    /** Item -> "is a spear", resolved once per item: this is read several times per frame by other modules. */
    private static final java.util.Map<Item, Boolean> SPEAR_ITEMS = new java.util.IdentityHashMap<>();

    private static boolean isSpearItem(Item item) {
        Boolean known = SPEAR_ITEMS.get(item);
        if (known == null) {
            known = Registries.ITEM.getId(item).getPath().toLowerCase(java.util.Locale.ROOT).contains("spear");
            SPEAR_ITEMS.put(item, known);
        }
        return known;
    }

    public static boolean isPlayerUsingSpear() {
        net.minecraft.client.MinecraftClient client = net.minecraft.client.MinecraftClient.getInstance();
        if (client == null || client.player == null) return false;
        // The spear AutoSpear Reach selected is this module's own reach swap, not the player's spear play:
        // a held use key must not pause the smash that is about to take it.
        AutoMace self = ModuleAccess.getModule(AutoMace.class);
        if (self != null && self.isAutoSpearHeld()) return false;
        ItemStack main = client.player.getMainHandStack();
        boolean holdingSpear = main != null && !main.isEmpty() && isSpearItem(main.getItem());
        if (holdingSpear) {
            if (client.player.isUsingItem()) {
                ItemStack active = client.player.getActiveItem();
                if (active != null && !active.isEmpty() && isSpearItem(active.getItem())) {
                    return true;
                }
            }
            if (client.options != null && client.options.useKey != null && client.options.useKey.isPressed()) {
                return true;
            }
        }
        return false;
    }

    /**
     * SpearMaceDtap runs its own mace follow-up and owns the rest of that fall, so AutoMace stays
     * out of it (no aim, swap or second smash) until the player lands.
     */
    public static boolean isSpearDtapActive() {
        return SpearMaceDtap.isComboActive();
    }

    /**
     * True while Auto Unblock has the player's own shield down and intends to raise it again. Lets
     * AutoShield leave the use key released instead of re-pressing it mid-dive.
     */
    public static boolean isOwnShieldSuppressed() {
        AutoMace module = ModuleAccess.getModule(AutoMace.class);
        return module != null && module.isEnabled() && (module.shouldReblockShield || module.isAutoSpearHeld());
    }

    /**
     * SpearMaceDtap asked for the aim during a spear charge and nothing else pauses AutoMace: the target
     * choice, pre-aim, tracking and Silent Aim run exactly as for a smash, but no swap, click or sequence starts.
     */
    boolean spearAimOnlyActive() {
        return !isExternalPauseActive() && !isSpearDtapActive()
                && !isRotationLockedByOther() && SpearMaceDtap.wantsAim();
    }

    void runSpearAimOnly() {
        if (mc.player == null || mc.world == null || isInLiquidOrWeb() || !hasSpearAimRequirement()) {
            stopAiming();
            return;
        }
        currentTarget = findTarget();
        if (currentTarget == null || !isAimTargetValid(currentTarget)) {
            stopAiming();
            return;
        }
        if (mc.player.distanceTo(currentTarget) <= getAimEngagementDistance(currentTarget)) {
            applySmashAim();
        } else {
            stopAiming();
        }
    }

    private boolean hasSpearAimRequirement() {
        if (mc.player == null || mc.player.isOnGround() || mc.player.isGliding()
                || mc.player.isClimbing() || isInLiquidOrWeb() || mc.player.isTouchingWater()
                || mc.player.hasVehicle()) {
            return false;
        }
        return mc.player.getVelocity().y < 0.0 || mc.player.fallDistance > 0.0;
    }

    private boolean isExternallyPaused() {
        // AutoShield is deliberately not a pause reason: AutoMace's Auto Unblock must still drop the
        // shield on the dive, and AutoShield stays out of the way while isOwnShieldSuppressed().
        return isExternalPauseActive()
                || isPlayerUsingSpear() || isSpearDtapActive();
    }

    public AutoMace() {
        super("AutoMace", "Lands the falling mace smash for you: while you fall onto a player it locks on, switches to the mace at the right tick and hits only when the hit will register, then swaps your weapon back. Against a raised shield it can axe-stun first, backstab, or try a backstab and fall back to the stun slam. Hit Only never moves your aim; Manual Trigger only upgrades your own clicks.", Category.COMBAT, SubCategory.MACE);
        // Mode and aim first; the Delays block goes last.
        mode.setSection("Mode");
        addSetting(mode);

        silentRotations.setSection("Aim");
        aimSpeed.setSection("Aim");
        preAimDistance.setSection("Aim");
        addSetting(silentRotations);
        addSetting(aimSpeed);
        addSetting(preAimDistance);

        lastHit.setSection("Targeting");
        addSetting(lastHit);

        cooldown.setSection("Smash");
        registrationCheck.setSection("Smash");
        addSetting(registrationCheck);
        weaponOnly.setSection("Smash");
        addSetting(cooldown);
        addSetting(weaponOnly);
        autoSpearReach.setSection("Smash");
        addSetting(autoSpearReach);
        horizontalReachOnly.setSection("Smash");
        addSetting(horizontalReachOnly);
        horizontalReachOnly.setVisibility(autoSpearReach::isEnabled);
        // Aim and acquisition only apply in Auto mode; Hit Only takes whoever is under the crosshair.
        silentRotations.setDependsOn("Mode:Auto");
        aimSpeed.setDependsOn("Mode:Auto");
        preAimDistance.setDependsOn("Mode:Auto");
        lastHit.setDependsOn("Mode:Auto");

        shieldCounter.setSection("Stun Slam");
        stunSlamMode.setSection("Stun Slam");
        stunSlamTicks.setSection("Stun Slam");
        stunSlamHitChance.setSection("Stun Slam");
        stunSlamMinTicks.setSection("Stun Slam");
        onlyStunSlam.setSection("Stun Slam");
        addSetting(shieldCounter);
        addSetting(stunSlamMode);
        addSetting(stunSlamTicks);
        addSetting(stunSlamHitChance);
        addSetting(stunSlamMinTicks);
        addSetting(onlyStunSlam);
        // The stun-slam settings also apply to the Backstab-else-Stun-Slam fallback.
        stunSlamMode.setVisibility(this::stunSettingsVisible);
        stunSlamTicks.setVisibility(() -> stunSettingsVisible() && stunSlamMode.is("Normal"));
        stunSlamHitChance.setVisibility(this::stunSettingsVisible);
        stunSlamMinTicks.setVisibility(() -> stunSettingsVisible() && stunSlamMode.is("Adaptive"));
        onlyStunSlam.setVisibility(this::stunSettingsVisible);
        stunSlamTicks.getJitter().setVisibility(() -> stunSettingsVisible() && stunSlamMode.is("Normal"));

        maceType.setSection("Equipment");
        autoShieldUnblock.setSection("Equipment");
        addSetting(maceType);
        addSetting(autoShieldUnblock);

        addSetting(profileDelay);
        addSetting(lockOnWait);
        addSetting(waitOnPearlArrival);
        addSetting(pearlArrivalWait);
        pearlArrivalWait.setDependsOn("Wait On Pearl Arrival");
        addSetting(backstabGiveUp);
        addSetting(swapBackDelay);
        backstabGiveUp.setDependsOn("Shield Counter:" + COUNTER_BACKSTAB_ELSE_STUN);
        backstabGiveUp.getJitter().setVisibility(() -> shieldCounter.is(COUNTER_BACKSTAB_ELSE_STUN));
        pearlArrivalWait.getJitter().setVisibility(waitOnPearlArrival::isEnabled);
        lockOnWait.setDependsOn("!Mode:Manual Trigger");
    }

    /** Single source of truth for the friend filter — AutoElytra reads this so the two modules can
     *  never disagree about whether a player is a friend. Friends are always skipped. */
    public boolean isIgnoreFriends() {
        return true;
    }

    /** Single source of truth for the dive victim — AutoElytra's glide-aim reads this so it never
     *  steers toward a different player than the wing actually drops for. Null when not engaged. */
    public PlayerEntity getCurrentTarget() {
        return currentTarget;
    }

    /** READ-ONLY: true while any smash/shield-break sequence is armed or in flight. AutoElytra checks
     *  this so it never re-equips the wing under a pending hit (which would void the slam bonus). */
    public boolean isSmashArmed() {
        if (isPlayerUsingSpear() || isSpearDtapActive()) {
            return false;
        }
        return burstClaimed || shouldDirectSmashArmed || smashStage > 0
                || stunSlamState != StunSlamState.IDLE;
    }

    /** READ-ONLY: true while an airborne dive has a live target inside predicted smash reach — the
     *  seconds-away-from-a-hit window in which AutoElytra must leave the wing alone. */
    public boolean hasCommittedSmashTarget() {
        if (isPlayerUsingSpear() || isSpearDtapActive()) {
            return false;
        }
        return isInsidePredictedSmashBubble(currentTarget);
    }

    /**
     * READ-ONLY: true only while a smash can really happen - a sequence is armed or in flight, or the
     * player is already falling past the smash floor with a victim in predicted smash reach. Unlike
     * {@link #hasCommittedSmashTarget()} alone, an ordinary jump near a player does not count, so other
     * melee modules (TriggerBot) keep working until a dive is genuinely under way.
     */
    public boolean isSmashImminent() {
        if (isBusy() || isSmashArmed()) {
            return true;
        }
        return hasCommittedSmashTarget() && hasSmashFallRequirement();
    }

    /** READ-ONLY: the actual swept-reach bubble used by the direct smash trigger for this victim. */
    public boolean isInsidePredictedSmashBubble(PlayerEntity target) {
        return mc.player != null && target != null && target.isAlive()
                && !mc.player.isOnGround() && isWithinPredictedSmashReach(target);
    }

    // Lazily-refreshed, null-safe handle to AutoElytra for the direct divebomb API.
    private AutoElytra getAutoElytra() {
        if (autoElytra == null) {
            autoElytra = ModuleAccess.getModule(AutoElytra.class);
        }
        return autoElytra;
    }

    /**
     * READ-ONLY: true while AutoMace is the module that must own attacks - a smash is armed, in
     * flight or committed, or the player is falling past the smash floor with a mace in the hotbar.
     * Shield breakers and other attackers hand over to AutoMace in that window.
     */
    public boolean ownsAttackWindow() {
        if (!isEnabled() || mc.player == null) {
            return false;
        }
        if (isSmashArmed() || (hasCommittedSmashTarget() && hasSmashFallRequirement())) {
            return true;
        }
        return hasSmashFallRequirement() && !isInLiquidOrWeb()
                && (mc.player.getMainHandStack().getItem() instanceof MaceItem || findBestMace() != -1);
    }

    /**
     * Mace Swap Only entry point. The player's own left click is intercepted: when the smash
     * conditions hold for the entity under the crosshair, it is cancelled and replaced by the
     * regular smash / stun-slam sequence (same swap, attack, restore path as the automatic mode).
     * No aim is applied and nothing is clicked without the player's click.
     */
    @Override
    public boolean handleAttack() {
        if (!isManualMode() || reentrantAttack) {
            return false;
        }
        if (mc.player == null || mc.world == null || mc.interactionManager == null || mc.currentScreen != null
                || mc.player.isSpectator() || mc.player.isCreative()) {
            return false;
        }
        if (isExternallyPaused() || elytraBlocksAction()) {
            return false;
        }
        if (isBusy()) {
            // A sequence owns the swing; never let a second click reach vanilla with the wrong item.
            suppressAttackInput();
            return true;
        }

        CombatSwapLockEvent lock = new CombatSwapLockEvent();
        EventBus.post(lock);
        if (lock.isItemSwapLocked() || lock.isAutoAttackLocked()) {
            return false;
        }

        if (isAwaitingBounce() || isInLiquidOrWeb() || !hasSmashFallRequirement() || !isBurstCooldownReady()) {
            return false;
        }
        if (weaponOnly.isEnabled() && !isHoldingWeapon()) {
            return false;
        }
        boolean holdingMace = mc.player.getMainHandStack().getItem() instanceof MaceItem;
        if (!holdingMace && findBestMace() == -1) {
            return false;
        }

        refreshCrosshairForActiveAim();
        if (!(mc.crosshairTarget instanceof EntityHitResult hit) || !(hit.getEntity() instanceof PlayerEntity victim)
                || victim == mc.player || !isTargetable(victim) || !isAimTargetValid(victim)
                || !isWithinLegitReach(victim, attackRange(victim))) {
            return false;
        }

        updateBackstabFallback(victim);
        boolean canStunSlam = isStunSlamSelected() && findAxe() != -1 && findBestMace() != -1
                && isTargetBlocking(victim) && !isPlayerBehindTarget(victim);
        if (onlyStunSlam.isEnabled() && isStunSlamSelected() && !canStunSlam) {
            return false;
        }
        if (isBackstabHold(victim)) {
            // Backstab: the shield still covers us, so this click is held back, not sent into it.
            suppressAttackInput();
            return true;
        }

        currentTarget = victim;
        startSmashBurst();
        if (burstClaimed || smashStage > 0 || stunSlamState != StunSlamState.IDLE) {
            return true;
        }
        currentTarget = null;
        return false;
    }

    @Override
    public void onEnable() {
        try {
            EventBus.register(this);
            recover();
        } catch (Throwable failure) {
            ClientInternalLog.error(getName(), "Failed to initialize AutoMace", failure);
            if (failure instanceof RuntimeException runtimeFailure) throw runtimeFailure;
            if (failure instanceof Error error) throw error;
            throw new IllegalStateException("AutoMace initialization failed", failure);
        }
    }

    @Override
    public void onDisable() {
        try {
            EventBus.unregister(this);
            pressureStep = PressureStep.NONE;
            recover();
            InterceptPredictor.getInstance().clear();
            // Tell AutoElytra the combo is over so it deterministically re-equips and clears its latch
            // instead of being left mid-swap with the elytra off.
            AutoElytra elytra = getAutoElytra();
            if (elytra != null) {
                elytra.notifyComboAborted();
            }
        } catch (Throwable failure) {
            ClientInternalLog.error(getName(), "Failed to shut down AutoMace cleanly", failure);
        }
    }

    @Override
    protected void onRuntimeStateReleased() {
        recover();
    }

    @EventTarget
    public void onCombatSwapLock(CombatSwapLockEvent event) {
        if (!isEnabled()) return;
        if (isPlayerUsingSpear() || isSpearDtapActive()) return;
        if (isSmashArmed() || smashStage > 0 || stunSlamState != StunSlamState.IDLE) {
            event.setItemSwapLocked(true);
            event.setAutoAttackLocked(true);
        }
    }

    /**
     * Single hard reset for EVERY latch/flag/timer. Restores the saved hotbar slot FIRST (if it is
     * a valid 0..8) so a toggle/lock/config-sync can never leave the player parked on the mace/axe
     * slot, then clears the whole state machine so the module is immediately ready. Cooldown is
     * cleared so the next dive can fire at once.
     */
    private void recover() {
        if (autoSpearSlot >= 0 || spearReachSequence) {
        }
        if (stunAxeAttackExecuted && !stunMaceAttackExecuted && stunSlamState != StunSlamState.IDLE
                && !stunSlamMissed) {
            ClientInternalLog.warn(getName(), "stun slam unwound after the axe without the mace: "
                    + (stunRefusal != null ? stunRefusal : "no refusal recorded (state=" + stunSlamState + ")"));
        }
        stunRefusal = null;
        sequenceEpoch++;
        reentrantAttack = false;
        swapBackToWeapon();
        releaseAutoSpear();
        spearReachSequence = false;

        // 2) Clear every latch / slot tracker.
        burstClaimed = false;
        currentTarget = null;
        initialDelayTarget = null;
        lockedTargetId = null;
        lastUserHitId = null;
        originalSlot = -1;
        preSequenceSlot = -1;
        smashReturnSlot = -1;

        // 3) Clear every state-machine flag.
        smashStage = 0;
        stunSlamState = StunSlamState.IDLE;
        stunSwapBackHoldTicks = 0;
        smashSwapBackHoldTicks = 0;
        elapsedStunTicks = 0;
        reachWaitTicks = 0;
        stunAxeQueuedAtMs = 0L;
        stunAxeAttackExecuted = false;
        stunMaceAttackExecuted = false;
        stunAxeExecutedTickId = -1L;
        stunAxeWireTickId = -1L;
        stunRequiredTickGap = 1;
        stunParams = null;
        stunPlan = null;
        stunForecastBox = null;
        stunSlamMissed = false;
        clearStrikePlan();
        shouldDirectSmashArmed = false;
        directMaceHitDueNs = 0L;
        directMacePreparedTickId = -1L;
        directMacePreparedInterval = -1L;
        directMaceRetryUntilNs = 0L;
        directMacePreparedSlot = -1;

        // 4) Clear the cooldown (deterministic, no wall-clock) so the next dive can fire at once.
        cooldownTicks = 0;

        // 5) Reset fall tracking and stop aiming so nothing is mid-rotation.
        highestY = (mc.player != null) ? mc.player.getY() : 0.0;
        awaitServerBounce = false;
        awaitBounceUntil = 0L;
        aimRequested = false;
        aimSmoother.reset();
        smootherLastNanos = Long.MIN_VALUE;
        RotationManager.abortOwner(this);
        // Owner-scoped: a slot intent from an unwound sequence must not refuse the next one's action.
        SlotSync.releaseOwner(this);
        clearFrameCaches();
        if (getAutoElytra() != null) {
            getAutoElytra().notifyComboAborted();
        }
        restoreOwnShieldIfNeeded();
    }

    @Override
    public void onRender(DrawContext context, float tickDelta) {
        if (primaryRenderPassSeen) {
            primaryRenderPassSeen = false;
            return;
        }
        runRenderPassSafely();
    }

    @Override
    public void onRenderFrame() {
        primaryRenderPassSeen = true;
        runRenderPassSafely();
    }

    /** Remembers the last non-mace hotbar slot so a swap-back always has somewhere correct to return to. */
    private boolean isManualMode() {
        return mode.is(MODE_MANUAL);
    }

    private boolean isHitOnlyMode() {
        return mode.is(MODE_HIT_ONLY);
    }

    /** Hit Only and Manual Trigger never move the view or request a rotation. */
    private boolean aimDisabled() {
        return !mode.is(MODE_AUTO);
    }

    /** Attack reach for the current target: 3.0 blocks, or 4.5 while the spear reach applies. */
    private double attackRange() {
        return attackRange(currentTarget);
    }

    private double attackRange(Entity target) {
        return isSpearReachActive(target) ? spearRange() : ATTACK_RANGE;
    }

    /**
     * True while a spear is the held item, and for the rest of a smash or stun slam that started from
     * one: the axe and mace swaps keep the 4.5 reach until the sequence ends (recover or idle).
     */
    private boolean isSpearReachActive(Entity target) {
        if (mc.player == null) {
            return false;
        }
        if (spearReachSequence) {
            if (burstClaimed || smashStage > 0 || stunSlamState != StunSlamState.IDLE) {
                return true;
            }
            spearReachSequence = false;
        }
        return isHoldingSpearForReach();
    }

    /** Latches the 4.5 reach for the sequence being started when it starts from a held spear. */
    private void latchSpearReach() {
        if (isHoldingSpearForReach()) {
            spearReachSequence = true;
        }
    }

    /**
     * The held spear counts once at least one movement packet has gone out since AutoSpear Reach
     * selected it, so the spear selection and the mace selection never share a packet interval.
     */
    private boolean isHoldingSpearForReach() {
        if (mc.player == null || !isReachSpear(mc.player.getMainHandStack())) {
            return false;
        }
        AutoSpearReach tuning = spearTuning();
        if (tuning != null && !tuning.ownSpearReachEnabled() && autoSpearSlot < 0) {
            return false;
        }
        return !(autoSpearSlot >= 0 && autoSpearSwapInterval == TickActionOrder.interval());
    }

    /**
     * True while a spear-started sequence holds the axe or mace: vanilla's own crosshair pick would use
     * that item's 3.0 reach, so the live ray is cast at the sequence reach from the current angles instead.
     */
    private boolean isSpearRayCarried() {
        return spearReachSequence && mc.player != null && !isReachSpear(mc.player.getMainHandStack())
                && isSpearReachActive(currentTarget);
    }

    /** The live ray from the current authoritative angles at the extended reach, or null. */
    private HitResult spearRay() {
        return RotationManager.getCrosshairTarget(mc.player.getYaw(), mc.player.getPitch(), spearRange());
    }

    /**
     * The hotbar slot the player is really on. While AutoElytra holds an armor slot it selected for the
     * wing swap, that is the slot it will give back, not the armor slot, so a smash never "returns" to it.
     */
    private int userHeldSlot() {
        int held = mc.player.getInventory().getSelectedSlot();
        AutoElytra elytra = getAutoElytra();
        int origin = elytra != null ? elytra.getTransientOriginSlot() : -1;
        return origin >= 0 ? origin : held;
    }

    private void trackWeaponSlot() {
        if (mc.player == null || isBusy() || isAutoSpearHeld()) return;
        int cur = userHeldSlot();
        if (cur >= 0 && cur < 9 && !isMaceSlot(cur)) {
            lastWeaponSlot = cur;
        }
    }

    /**
     * A new world (dimension change, server switch, respawn into a fresh level) invalidates every
     * entity reference and hotbar assumption the state machine holds, so unwind it instead of
     * letting a latch from the old world strand the first dive in the new one.
     */
    private boolean recoverOnWorldChange() {
        net.minecraft.client.world.ClientWorld world = mc.world;
        if (world == lastWorld) return false;
        boolean hadWorld = lastWorld != null;
        lastWorld = world;
        if (hadWorld && world != null) {
            recover();
            return true;
        }
        return false;
    }

    private void runRenderPassSafely() {
        if (!isEnabled()) return;
        if (mc.player == null || mc.world == null || mc.interactionManager == null || mc.currentScreen != null) {
            if (mc.world == null) lastWorld = null;
            if (isBusy() || currentTarget != null) recover();
            else abortAiming();
            return;
        }
        try {
            if (recoverOnWorldChange()) return;
            trackWeaponSlot();
            evaluateLogic();
            // Smash and stun-slam steps are dispatched from the tick head only; a render frame keeps
            // the aim fed and arms the burst, nothing else (Rule 1 / transport phase isolation).
            pollDirectSmash();
        } catch (Throwable failure) {
            ClientInternalLog.error(getName(), "AutoMace render execution failed; sequence recovered", failure);
            // Never leave a half-completed slot/attack transaction wedged because one render-frame
            // dependency failed. A later frame starts clean and retries the same real conditions.
            try {
                recover();
            } catch (Throwable recoveryFailure) {
                burstClaimed = false;
                shouldDirectSmashArmed = false;
                smashStage = 0;
                stunSlamState = StunSlamState.IDLE;
            }
            rethrowForFaultBoundary("AutoMace render execution failed", failure);
        }
    }

    @Override
    public void onWorldRender(MatrixStack matrices, float tickDelta) {
    }

    @Override
    public void onTick() {
        tickPass = true;
        try {
            stunClientTickId++;
            tickTeleportGuard();
            updateSnapGuard();
            tickInterceptEngine();
            trackPearlArrivals();

            if (mc.player != null) {
                if (mc.player.isOnGround() || mc.player.isClimbing() || isInLiquidOrWeb()
                        || mc.player.isTouchingWater() || mc.player.hasVehicle()) {
                    highestY = mc.player.getY();
                    // A new dive starts a new backstab attempt.
                    if (stunSlamState == StunSlamState.IDLE) {
                        resetBackstabFallback();
                    }
                } else {
                    highestY = Math.max(highestY, mc.player.getY());
                }
            }

            // Keep our shield down during active smash dive/combo so we don't re-block mid-fall.
            boolean inSmashDive = currentTarget != null && hasSmashFallRequirement()
                    && (isCommittedSmashApproach(currentTarget) || shouldDirectSmashArmed || burstClaimed);
            if (shouldReblockShield) {
                if (stunSlamState == StunSlamState.IDLE && smashStage == 0 && !inSmashDive) {
                    restoreOwnShieldIfNeeded();
                } else {
                    if (mc.options != null && mc.options.useKey != null && mc.options.useKey.isPressed()) {
                        while (mc.options.useKey.wasPressed()) {}
                        mc.options.useKey.setPressed(false);
                    }
                    // Between the axe and the mace the release is left to performSlotAttack, which
                    // does it right after the mace selection: releasing it here would mark this
                    // interval "used" and refuse the mace's hotbar change until the next one.
                    if (mc.player != null && mc.player.isUsingItem() && mc.interactionManager != null
                            && !isStunFollowUpPhase()) {
                        mc.interactionManager.stopUsingItem(mc.player);
                        TickActionOrder.recordUse();
                    }
                }
            }

            // Release a stale burst claim. burstClaimed is raised before any attack is
            // dispatched (see its javadoc) and the burst entry point has several early-return
            // paths; releasing it from observable state rather than from each of those paths
            // means a missed release can cost at most one tick of over-claiming instead of
            // wedging the module for the session.
            if (burstClaimed && smashStage == 0 && stunSlamState == StunSlamState.IDLE
                    && !shouldDirectSmashArmed) {
                burstClaimed = false;
            }

            // RECOVERY WATCHDOG (the "no matter what, it keeps working" guarantee). Runs BEFORE the
            // early-return latch guards below (smashStage / stunSlamState), so a wedge in either is
            // broken instead of permanently blocking the trigger. A sequence latch held continuously
            // past the bound can only mean the state machine got stuck; recover() restores the saved
            // slot and clears every flag/timer, leaving the module immediately ready to fire again.
            if (isEnabled()) {
                boolean inSequence = smashStage > 0 || stunSlamState != StunSlamState.IDLE;
                int wedgeBound = WEDGE_WATCHDOG_TICKS;
                if (inSequence) {
                    if (++wedgeTicks > wedgeBound) {
                        recover();
                        wedgeTicks = 0;
                        return;
                    }
                } else {
                    wedgeTicks = 0;
                }
            } else {
                wedgeTicks = 0;
            }

            // DETERMINISTIC tick counters: advance exactly once per tick, clamped at 0. This is the
            // ONLY place the wall-clock-free gates move.
            if (cooldownTicks > 0) {
                cooldownTicks--;
            }

            // Externally paused (e.g. during a PearlMacro throw): release the aim channel and take no
            // action so we never rotate the camera off the pearl's axis or swing mid-throw.
            //
            // NOTE the elytra gate is deliberately NOT here. This module is what asks AutoElytra to shed
            // the wing (requestDivebombDrop, below); returning early while the elytra is on would mean
            // the drop is never requested, the wing never comes off, and the dive never happens — the
            // gate would deadlock the very combo it is meant to clean up. The logic keeps running; the
            // aim and the swing are suppressed at their own chokepoints instead (applySmashAim and
            // elytraBlocksAction).
            if (isExternallyPaused() || isRotationLockedByOther()) {
                if (autoSpearSlot >= 0 && !isBusy()) {
                    releaseAutoSpear(); // nothing will smash while paused: never leave the spear in hand
                }
                if (spearAimOnlyActive()) {
                    runSpearAimOnly();
                    return;
                }
                if (!SpearMaceDtap.firedThisTick()) {
                    abortAiming();
                }
                return;
            }

            if (recoverOnWorldChange()) return;
            trackWeaponSlot();
            tickAutoSpearReach();

            evaluateLogic();
            if (!isEnabled())
                return;

            // Stun slam state machine
            if (stunSlamState != StunSlamState.IDLE) {
                shouldDirectSmashArmed = false;
                tickStunSlamCombo();
                return;
            }

            // Fast direct mace smash — tick-quantised, since the hotbar swap and the attack
            // packet have to land on a tick boundary in the right order.
            if (smashStage > 0) {
                shouldDirectSmashArmed = false; // Never arm a new burst while one is active
                tickSmashBurst();
                return;
            }

            // Trigger the smash burst from the armed decision
            if (shouldDirectSmashArmed) {
                shouldDirectSmashArmed = false;
                startSmashBurst();
                return;
            }
        } catch (Throwable failure) {
            ClientInternalLog.error(getName(), "AutoMace tick execution failed; sequence recovered", failure);
            // Tick bookkeeping is secondary to render-driven execution, but a transient failure
            // must not strand a state latch and make subsequent render frames permanently inert.
            try {
                recover();
            } catch (Throwable recoveryFailure) {
                burstClaimed = false;
                shouldDirectSmashArmed = false;
                smashStage = 0;
                stunSlamState = StunSlamState.IDLE;
            }
            rethrowForFaultBoundary("AutoMace tick execution failed", failure);
        } finally {
            tickPass = false;
        }
    }

    // ── Teleport / respawn guard ──
    private PlayerEntity lastTickPlayer = null;
    private double lastTickX = Double.NaN, lastTickY = Double.NaN, lastTickZ = Double.NaN;
    /** A per-tick move longer than any dive or glide covers: a server teleport or setback. */
    private static final double TELEPORT_JUMP_SQ = 8.0 * 8.0;

    /**
     * A respawn (new player object in the same world), a server teleport/setback, or death invalidates the
     * fall the module tracked and any sequence in flight: the server reset its fall distance, so a "smash"
     * from the old fall would only be a plain hit. Unwind once and re-anchor the fall instead of leaving a
     * latch that only the 1.5 s watchdog would break.
     */
    private void tickTeleportGuard() {
        PlayerEntity player = mc.player;
        if (player == null) {
            lastTickPlayer = null;
            return;
        }
        boolean respawned = lastTickPlayer != null && lastTickPlayer != player;
        boolean teleported = !respawned && lastTickPlayer != null && !Double.isNaN(lastTickX)
                && player.squaredDistanceTo(lastTickX, lastTickY, lastTickZ) > TELEPORT_JUMP_SQ;
        boolean dead = !player.isAlive() || player.getHealth() <= 0.0f;
        lastTickPlayer = player;
        lastTickX = player.getX();
        lastTickY = player.getY();
        lastTickZ = player.getZ();
        if (respawned || teleported || dead) {
            highestY = player.getY();
            if (isBusy() || currentTarget != null || shouldDirectSmashArmed || awaitServerBounce) {
                recover();
            }
        }
    }

    private static void rethrowForFaultBoundary(String message, Throwable failure) {
        if (failure instanceof Error error) throw error;
        if (failure instanceof RuntimeException runtime) throw runtime;
        throw new IllegalStateException(message, failure);
    }

    private void evaluateLogic() {
        if (!isEnabled() || isRotationLockedByOther())
            return;
        if (isShieldDrainActive())
            return;

        // The armed latch (shouldDirectSmashArmed) is intentionally NOT cleared here — only onTick
        // consumes it after firing, so a frame that raises it between two ticks is preserved for the
        // tick to act on. (High-speed between-tick miss fix #1.)
        runRenderLogic();
    }

    // ── Per-tick memo for the render pass ──
    // The render pass re-runs the whole decision every frame (60-300+ Hz) but positions, inventories and
    // other modules' states only change per tick or on packet arrival. These caches hold values that
    // are pure functions of their key, so a frame reads the same answer the uncached call would give.
    private long drainTick = Long.MIN_VALUE;
    private boolean drainActive = false;
    /** True while onTick runs; tick-pass reads always ask fresh, render frames reuse one answer per tick. */
    private boolean tickPass = false;

    /**
     * MaceShieldDrain's hold. Its draining state only changes on its own tick, so the event is posted on
     * every tick pass and by the first render frame after it (which sees MaceShieldDrain's tick as well);
     * later frames of that tick reuse the answer instead of a reflective EventBus dispatch per frame.
     */
    private boolean isShieldDrainActive() {
        if (tickPass || drainTick != stunClientTickId) {
            ShieldDrainEvent drainEvent = new ShieldDrainEvent();
            EventBus.post(drainEvent);
            drainActive = drainEvent.isActive();
            // A tick-pass answer may predate MaceShieldDrain's own tick: the next frame asks again.
            drainTick = tickPass ? Long.MIN_VALUE : stunClientTickId;
        }
        return drainActive;
    }

    private net.minecraft.client.world.ClientWorld liquidCacheWorld = null;
    private double liquidCacheX = Double.NaN, liquidCacheY = Double.NaN, liquidCacheZ = Double.NaN;
    private long liquidCacheTick = Long.MIN_VALUE;
    private boolean liquidCacheValue = false;

    private net.minecraft.client.world.ClientWorld clearanceCacheWorld = null;
    private double clearanceCacheX = Double.NaN, clearanceCacheY = Double.NaN, clearanceCacheZ = Double.NaN;
    private double clearanceCacheVy = Double.NaN;
    private long clearanceCacheTick = Long.MIN_VALUE;
    private double clearanceCacheValue = 0.0;

    /** Line of sight per target: last eye positions it was computed for and the answer. */
    private final java.util.Map<Integer, double[]> losCache = new java.util.HashMap<>();

    private long botHooksTick = Long.MIN_VALUE;
    private java.util.List<interstellarTikiTiks.apexx.client.module.implementation.player.BotFilterProvider> botHooks =
            java.util.List.of();

    private long bestMaceTick = Long.MIN_VALUE;
    private PlayerEntity bestMaceTarget = null;
    private double bestMaceFall = Double.NaN;
    private PressureStep bestMacePressure = null;
    private boolean bestMaceDamageMode = false;
    private final ItemStack[] bestMaceHotbar = new ItemStack[9];
    private final boolean[] bestMaceEmpty = new boolean[9];
    private int bestMaceSlot = -1;

    private void clearFrameCaches() {
        drainTick = Long.MIN_VALUE;
        liquidCacheWorld = null;
        clearanceCacheWorld = null;
        losCache.clear();
        botHooksTick = Long.MIN_VALUE;
        bestMaceTick = Long.MIN_VALUE;
        bestMaceTarget = null;
        java.util.Arrays.fill(bestMaceHotbar, null);
        strikeCandidates = null;
        strikeCandidatesFor = null;
    }

    public boolean hasSmashFallRequirement() {
        if (mc.player == null || mc.player.isOnGround() || mc.player.isGliding()
                || mc.player.isClimbing() || isInLiquidOrWeb() || mc.player.isTouchingWater()
                || mc.player.hasVehicle()) {
            return false;
        }
        if (mc.player.getVelocity().y >= 0.0) {
            return false;
        }
        double requiredFall = HARD_CODED_MIN_DROP;
        return currentFallDistance() >= requiredFall;
    }

    static Vec3d lookVector(float yaw, float pitch) {
        double pitchRad = Math.toRadians(pitch);
        double yawRad = Math.toRadians(-yaw);
        double cosPitch = Math.cos(pitchRad);
        return new Vec3d(Math.sin(yawRad) * cosPitch, -Math.sin(pitchRad), Math.cos(yawRad) * cosPitch);
    }

    private Vec3d getInterpolatedEyePos(float t) {
        if (mc.player == null) return Vec3d.ZERO;
        double eyeOffset = mc.player.getEyeY() - mc.player.getY();
        double x = MathHelper.lerp(t, mc.player.lastRenderX, mc.player.getX());
        double y = MathHelper.lerp(t, mc.player.lastRenderY, mc.player.getY()) + eyeOffset;
        double z = MathHelper.lerp(t, mc.player.lastRenderZ, mc.player.getZ());
        return new Vec3d(x, y, z);
    }

    // ── High-velocity strike engine ──
    //
    // A click is judged at a tick head against the positions of the tick before it, and those positions do not
    // change between ticks. So the aim is not a chase of a moving point: it is "be on the target in the geometry
    // the next head judges, by the time that head runs". The rotation controller is asked for exactly that with
    // a deadline (RotationManager.aimForDeadline), which removes the controller lag the old 0.7-tick lead tried
    // to cover. That lead extrapolated the closing speed and, in a near-vertical dive at 2-3 blocks/tick, put the
    // aim point two blocks above a target below us: the pitch whipped up and back, the "shake" before a slam.
    //
    // At dive speed the next head is often already lost, so StrikePlanner looks six heads ahead (our fall from
    // the shared motion model, the target from its forecast) and keeps only body points the view can reach by
    // each head. It finds the earliest strike that can land: a single smash; behind a raised shield the backstab
    // (a 180-degree turn when we pass over); in front of it the axe on a head from which the mace can still be
    // reached; after the axe the mace itself. It steers for that only at the last responsible moment, and keeps
    // the same head and body point while they stay valid, so the view does not switch between options.
    //
    // Measured on a model of this tick/frame order and controller (600 passes in five families: slow, fast,
    // maximum speed, near-vertical, grazing; the blocker turns its head toward us with a delay): success went
    // from 95.8/70.8/25.8/64.2/66.7% to 95.8/77.5/35.0/65.0/75.0%, the maximum-speed figure within a point of
    // what any aim could do there (35.8%: at 2.4-3.9 blocks/tick the target is in reach for about one head);
    // backstabs roughly doubled; no Intave snap, MXAc aim-factor or 100-degree-per-tick pattern occurred.
    // Clicks are unchanged: every one still needs the live ray on the target in reach.

    /** Continuous command between the tick-rate planner and the rotation controller. */
    private final AimSmoother aimSmoother = new AimSmoother();
    private long smootherLastNanos = Long.MIN_VALUE;
    private final float[] smoothed = new float[2];
    /** Slow noise for where on the body the aim sits (see {@link #BODY_WANDER_XZ}). */
    private final interstellarTikiTiks.apexx.client.util.math.Randomization.SmoothNoise wanderX =
            new interstellarTikiTiks.apexx.client.util.math.Randomization.SmoothNoise();
    private final interstellarTikiTiks.apexx.client.util.math.Randomization.SmoothNoise wanderY =
            new interstellarTikiTiks.apexx.client.util.math.Randomization.SmoothNoise();
    private final interstellarTikiTiks.apexx.client.util.math.Randomization.SmoothNoise wanderZ =
            new interstellarTikiTiks.apexx.client.util.math.Randomization.SmoothNoise();

    /** The strike steered for: absolute head (client tick id), body point and kind; head < 0 when none. */
    private long strikeHead = -1L;
    private int strikePoint = -1;
    private StrikePlanner.Kind strikeKind = null;
    private StrikePlanner.Need strikeNeed = null;
    /** Forecast heads for one client tick, computed once and read by every frame of that tick. */
    private long strikeGeometryTick = Long.MIN_VALUE;
    private java.util.UUID strikeGeometryTarget = null;
    private StrikePlanner.Head[] strikeGeometry = null;
    /** Body-grid candidates of {@link #strikeGeometry} (54 box raycasts per head), built once per heads + reach. */
    private float[][][] strikeCandidates = null;
    private StrikePlanner.Head[] strikeCandidatesFor = null;
    private double strikeCandidatesReach = Double.NaN;
    /** Snap guard: authoritative yaw at this tick head, and how far the previous tick turned. */
    private float guardTickYaw = Float.NaN;
    private float guardLastTickTurn = 360.0f;

    /** The angles the next movement packet will carry. */
    private float wireYaw() {
        return aimRequested && RotationManager.getCurrentOwner() == this ? RotationManager.getFinalYaw() : mc.player.getYaw();
    }

    private float wirePitch() {
        return aimRequested && RotationManager.getCurrentOwner() == this ? RotationManager.getFinalPitch() : mc.player.getPitch();
    }

    /** Called at each tick head: records how far the view turned over the tick that just ended. */
    private void updateSnapGuard() {
        if (mc.player == null) {
            return;
        }
        float yaw = wireYaw();
        if (!Float.isNaN(guardTickYaw)) {
            guardLastTickTurn = Math.abs(MathHelper.wrapDegrees(yaw - guardTickYaw));
        }
        guardTickYaw = yaw;
    }

    private float guardYaw(float commanded, float view) {
        if (Float.isNaN(guardTickYaw) || guardLastTickTurn >= SNAP_CALM_DEGREES) {
            return commanded;
        }
        float near = view + MathHelper.wrapDegrees(commanded - view);
        float base = view + MathHelper.wrapDegrees(guardTickYaw - view);
        return MathHelper.clamp(near, base - SNAP_STEP_DEGREES, base + SNAP_STEP_DEGREES);
    }

    private void clearStrikePlan() {
        strikeHead = -1L;
        strikePoint = -1;
        strikeKind = null;
        strikeNeed = null;
    }

    /**
     * The steering point for the rotation supplier: the commanded direction from the controller's eye. The
     * direction is the planned strike when the engine is engaged, else the torso in the next head's geometry;
     * either way the controller is asked to arrive by that head.
     */
    private Vec3d getAimPos(Entity target) {
        if (!(target instanceof PlayerEntity victim) || mc.player == null) {
            return Vec3d.ZERO;
        }
        float t = mc.getRenderTickCounter() != null ? mc.getRenderTickCounter().getTickProgress(false) : 1.0f;
        double toHead = (1.0 - MathHelper.clamp(t, 0.0f, 1.0f)) * MS_PER_TICK / 1000.0;
        float[] view = {wireYaw(), wirePitch()};
        float[] want;
        double deadline = toHead;
        StrikePlanner.Plan plan = steeringPlan(victim, view, toHead);
        if (plan != null) {
            want = new float[]{plan.yaw(), plan.pitch()};
            deadline = toHead + plan.head() * MS_PER_TICK / 1000.0;
        } else {
            want = headAimAngles(victim, view[0]);
        }
        // Smooth the planner's staircase into one continuous command first. A strike that is committed
        // shortens the time constant to a fraction of the time left, so the glide has landed on the planned
        // angle by the head that clicks; ordinary tracking leads by the target's measured rate instead.
        long nanos = System.nanoTime();
        double dt = smootherLastNanos == Long.MIN_VALUE ? 0.016 : (nanos - smootherLastNanos) * 1.0e-9;
        smootherLastNanos = nanos;
        boolean striking = plan != null;
        double smoothTime = striking
                ? MathHelper.clamp(deadline * STRIKE_SMOOTH_SHARE, STRIKE_SMOOTH_MIN_SECONDS, TRACK_SMOOTH_SECONDS)
                : TRACK_SMOOTH_SECONDS;
        aimSmoother.step(want[0], want[1], nanos * 1.0e-9, dt, smoothTime, !striking, smoothed);
        want = new float[]{smoothed[0], smoothed[1]};
        // The arrival solve is only for a committed strike: it exists to land on a deadline. Ordinary
        // tracking has no deadline, and its frame count (round(seconds / frame)) changes in whole steps,
        // which would step the command; the controller's own pursuit already follows a smooth angle.
        float[] command = striking ? RotationManager.aimForDeadline(this, want[0], want[1], deadline) : null;
        if (command == null) {
            command = want;
        }
        float yaw = guardYaw(command[0], view[0]);
        float pitch = MathHelper.clamp(command[1], -90.0f, 90.0f);
        Vec3d eye = isSilentMode() ? mc.player.getEyePos() : getInterpolatedEyePos(t);
        return eye.add(lookVector(yaw, pitch).multiply(AIM_DISTANCE));
    }

    /**
     * Ordinary tracking: the upper torso of the target box as the next head judges it, from the eye that head
     * judges from. Straight above or below the point yaw is ill-conditioned, so it is held at the current view
     * there (the ray hits the top of the box either way).
     */
    private float[] headAimAngles(PlayerEntity target, float viewYaw) {
        Box box = target.getBoundingBox();
        double insetX = Math.min(AIM_INSET, box.getLengthX() * 0.4);
        double insetY = Math.min(AIM_INSET, box.getLengthY() * 0.4);
        double insetZ = Math.min(AIM_INSET, box.getLengthZ() * 0.4);
        Box inner = new Box(box.minX + insetX, box.minY + insetY, box.minZ + insetZ,
                box.maxX - insetX, box.maxY - insetY, box.maxZ - insetZ);
        double seconds = System.nanoTime() * 1.0e-9 / BODY_WANDER_PERIOD;
        double halfX = inner.getLengthX() * 0.5;
        double halfZ = inner.getLengthZ() * 0.5;
        Vec3d point = new Vec3d(
                inner.getCenter().x + halfX * BODY_WANDER_XZ * wanderX.fbm(seconds, 2),
                inner.minY + inner.getLengthY() * (AIM_HEIGHT_FRACTION + 0.5 * BODY_WANDER_Y * wanderY.fbm(seconds + 17.0, 2)),
                inner.getCenter().z + halfZ * BODY_WANDER_XZ * wanderZ.fbm(seconds + 41.0, 2));
        Vec3d eye = mc.player.getEyePos();
        // The closest part of the hitbox to us that no block covers; the torso point above is the fallback
        // when every part is hidden. Chosen once per tick (a few ray casts), kept as an offset in the box.
        if (nearAimTick != mc.player.age || !target.getUuid().equals(nearAimTarget)) {
            nearAimTick = mc.player.age;
            nearAimTarget = target.getUuid();
            Vec3d near = StrikePlanner.closestVisiblePoint(
                    new StrikePlanner.Head(eye, box, false, visibilityFrom(eye)));
            nearAimOffset = near == null ? null : near.subtract(box.minX, box.minY, box.minZ);
        }
        if (nearAimOffset != null) {
            point = new Vec3d(box.minX + nearAimOffset.x, box.minY + nearAimOffset.y, box.minZ + nearAimOffset.z);
        }
        double dx = point.x - eye.x;
        double dz = point.z - eye.z;
        double horizontal = Math.hypot(dx, dz);
        if (horizontal < YAW_HOLD_OUTER) {
            Vec3d facing = lookVector(viewYaw, 0.0f);
            double along = Math.max(0.0, dx * facing.x + dz * facing.z);
            Vec3d held = new Vec3d(eye.x + facing.x * along, point.y, eye.z + facing.z * along);
            double blend = MathHelper.clamp((horizontal - YAW_HOLD_INNER) / (YAW_HOLD_OUTER - YAW_HOLD_INNER), 0.0, 1.0);
            point = held.lerp(point, blend * blend * (3.0 - 2.0 * blend));
        }
        Vec3d to = point.subtract(eye);
        double h = Math.sqrt(to.x * to.x + to.z * to.z);
        if (h < 1.0e-6 && Math.abs(to.y) < 1.0e-6) {
            return new float[]{viewYaw, mc.player.getPitch()};
        }
        float yaw = h < 1.0e-6 ? viewYaw : (float) Math.toDegrees(Math.atan2(-to.x, to.z));
        return new float[]{yaw, (float) -Math.toDegrees(Math.atan2(to.y, h))};
    }

    /** The planned strike when the engine should steer for it this frame, else null (ordinary tracking). */
    private StrikePlanner.Plan steeringPlan(PlayerEntity victim, float[] view, double toHead) {
        if (TickActionOrder.isWindowOpen() || mc.player == null) {
            // Only frames between ticks steer: inside a tick head the next head's geometry is not known yet.
            return null;
        }
        long nextHead = stunClientTickId + 1L;
        StrikePlanner.Need need;
        int maceFrom = 0;
        int maceTo = -1;
        switch (stunSlamState) {
            case WAITING_DELAY, MACE_SMASH -> {
                if (stunAxeExecutedTickId < 0L) {
                    return null;
                }
                if (stunAxeWireTickId < 0L) {
                    return null;
                }
                // Earliest head the mace may leave from: always a later interval than the axe's;
                // Normal also waits its client-tick gap. Adaptive may fire at any head after that.
                long earliest = stunAxeWireTickId + 1L;
                if (!stunSlamMode.is("Adaptive")) {
                    earliest = Math.max(earliest, stunAxeExecutedTickId + Math.max(1, stunRequiredTickGap));
                }
                maceFrom = (int) Math.max(0L, earliest - nextHead);
                maceTo = (int) (stunAxeWireTickId + StunSlamEngine.MAX_FOLLOW_UP_TICKS - nextHead);
                if (maceTo < 0) {
                    return null;
                }
                need = StrikePlanner.Need.MACE;
            }
            case AXE_ATTACK -> need = StrikePlanner.Need.STUN;
            case SWAP_BACK -> {
                return null;
            }
            default -> {
                if (smashStage > 0) {
                    return null;
                }
                boolean blocking = isTargetBlocking(victim);
                boolean spearDtapBackstab = SpearMaceDtap.isAutoBackstabActive();
                need = blocking && spearDtapBackstab ? StrikePlanner.Need.BACKSTAB
                        : blocking && isStunSlamSelected() && findAxe() != -1 ? StrikePlanner.Need.STUN
                        : blocking && isBackstabCounterActive() ? StrikePlanner.Need.BACKSTAB
                        : StrikePlanner.Need.SMASH;
            }
        }
        StrikePlanner.Head[] heads = strikeGeometry(victim);
        if (heads == null || heads.length == 0) {
            return null;
        }
        StrikePlanner.Reach[] reach = new StrikePlanner.Reach[heads.length];
        for (int k = 0; k < heads.length; k++) {
            double[] r = RotationManager.reachWithin(this, toHead + k * MS_PER_TICK / 1000.0);
            if (r == null) {
                return null;
            }
            reach[k] = new StrikePlanner.Reach(r[0], r[1], r[2], r[3]);
        }
        // Adaptive's gap is chosen only once the axe has left, so the pair is aimed for its earliest mace head.
        int pairGap = stunSlamMode.is("Adaptive") ? 1 : Math.max(1, (int) Math.round(stunSlamTicks.getValue()));
        StrikePlanner.Plan keep = strikeHead >= nextHead && need == strikeNeed && strikeKind != null
                ? new StrikePlanner.Plan((int) (strikeHead - nextHead), strikePoint, strikeKind, 0.0f, 0.0f) : null;
        double reachDistance = attackRange(victim);
        // The heads change once per tick but this runs every frame: the grid candidates (the costly part,
        // a box raycast per point per head) are built once for these heads and this reach.
        if (strikeCandidates == null || strikeCandidatesFor != heads || strikeCandidatesReach != reachDistance) {
            strikeCandidates = StrikePlanner.prepareCandidates(heads, reachDistance);
            strikeCandidatesFor = heads;
            strikeCandidatesReach = reachDistance;
        }
        java.util.Optional<StrikePlanner.Plan> planned = StrikePlanner.plan(heads, reach, view, need, pairGap,
                !stunSlamMode.is("Adaptive"), maceFrom, maceTo, reachDistance, keep, strikeCandidates);
        if (planned.isEmpty() && need == StrikePlanner.Need.STUN) {
            // A one-head flyby may allow the axe but no legal mace follow-up. Keep aiming for the
            // shield disable instead of falling back to the slower, uncommitted torso tracker.
            need = StrikePlanner.Need.AXE;
            planned = StrikePlanner.plan(heads, reach, view, need, pairGap, false,
                    maceFrom, maceTo, reachDistance, null, strikeCandidates);
        }
        if (planned.isEmpty()) {
            clearStrikePlan();
            return null;
        }
        StrikePlanner.Plan plan = planned.get();
        strikeHead = nextHead + plan.head();
        strikePoint = plan.point();
        strikeKind = plan.kind();
        strikeNeed = need;
        double dYaw = MathHelper.wrapDegrees(plan.yaw() - view[0]);
        double dPitch = plan.pitch() - view[1];
        boolean engage = plan.head() == 0 || need == StrikePlanner.Need.MACE
                || reach[plan.head()].usage(dYaw, dPitch) >= STRIKE_ENGAGE_USAGE;
        return engage ? plan : null;
    }

    /**
     * Eye and target box per upcoming head, once per client tick. Head 0 is the geometry the next head judges
     * (positions now); later heads use our fall from the shared motion model and the target forecast, anchored
     * to the target's current box.
     */
    private StrikePlanner.Head[] strikeGeometry(PlayerEntity victim) {
        if (strikeGeometry != null && strikeGeometryTick == stunClientTickId && victim.getUuid().equals(strikeGeometryTarget)) {
            return strikeGeometry;
        }
        strikeGeometryTick = stunClientTickId;
        strikeGeometryTarget = victim.getUuid();
        strikeGeometry = null;
        try {
            // Same lead as the stun follow-up's own observe: the visible boxes used here do not depend on
            // it, and asking for the same horizon lets PlayerMotionSimulation's per-tick memo serve both.
            StunSlamEngine.Outlook outlook = StunSlamEngine.observe(mc, victim, stunServerLead());
            int count = Math.min(STRIKE_HORIZON, outlook.eyes().length);
            Box now = victim.getBoundingBox();
            Box forecastNow = outlook.likeliestVisible(0);
            Vec3d anchor = forecastNow == null ? Vec3d.ZERO : now.getCenter().subtract(forecastNow.getCenter());
            Vec3d step = new Vec3d(victim.getX() - victim.lastX, victim.getY() - victim.lastY, victim.getZ() - victim.lastZ);
            boolean blocking = isTargetBlocking(victim);
            StrikePlanner.Head[] heads = new StrikePlanner.Head[count];
            for (int k = 0; k < count; k++) {
                Box forecast = k == 0 ? null : outlook.likeliestVisible(k);
                Box box = k == 0 ? now : forecast != null ? forecast.offset(anchor) : now.offset(step.multiply(k));
                Vec3d eye = outlook.eyes()[k];
                heads[k] = new StrikePlanner.Head(eye, box, blocking && shieldCoversFrom(victim, eye, box),
                        visibilityFrom(eye));
            }
            strikeGeometry = heads;
        } catch (RuntimeException failure) {
            strikeGeometry = null;
        }
        return strikeGeometry;
    }

    private int nearAimTick = Integer.MIN_VALUE;
    private java.util.UUID nearAimTarget = null;
    private Vec3d nearAimOffset = null;

    /** True for a point whose straight line from {@code eye} crosses no block collision (the last 3 cm excluded). */
    private java.util.function.Predicate<Vec3d> visibilityFrom(Vec3d eye) {
        return point -> {
            if (mc.world == null || mc.player == null) {
                return true;
            }
            Vec3d toEye = eye.subtract(point);
            double length = toEye.length();
            if (length < 0.05) {
                return true;
            }
            Vec3d end = point.add(toEye.multiply(0.03 / length));
            return mc.world.raycast(new RaycastContext(eye, end, RaycastContext.ShapeType.COLLIDER,
                    RaycastContext.FluidHandling.NONE, mc.player)).getType() == HitResult.Type.MISS;
        };
    }

    /** Whether the target's raised shield faces an eye at {@code eye} (horizontal plane, as vanilla). */
    private static boolean shieldCoversFrom(PlayerEntity target, Vec3d eye, Box box) {
        Vec3d centre = box.getCenter();
        double dx = eye.x - centre.x;
        double dz = eye.z - centre.z;
        double length = Math.sqrt(dx * dx + dz * dz);
        if (length < 1.0e-3) {
            return true;
        }
        double yawRad = Math.toRadians(target.getHeadYaw());
        return (-Math.sin(yawRad) * dx + Math.cos(yawRad) * dz) / length > 0.0;
    }

    /** Advances the shared intercept engine, which feeds Registration Check and AutoSpear Reach only. */
    private void tickInterceptEngine() {
        if (!isEnabled() || mc.player == null || mc.world == null) {
            return;
        }
        boolean spearForecast = autoSpearEnabled();
        if (!spearForecast && !registrationCheck.isEnabled()) {
            return;
        }
        InterceptPredictor.getInstance().onTick(mc, spearForecast);
    }

    private void runRenderLogic() {
        if (mc.player == null || mc.world == null)
            return;

        isAwaitingBounce();

        // AIM PRIORITY LADDER — the Pearl Catch Macro sits STRICTLY on top: while it runs (until the
        // whole pearl→wind-charge sequence completely finishes) it holds AutoMace externally paused so
        // the mace never rotates the camera off the pearl's throw axis. onTick already honours this, but
        // this render path aims too (and at the same HIGHEST priority as the pearl look-up), so without
        // this guard the per-frame smash aim would steal the channel back mid-throw. Standing down here
        // is what makes "all other aim assists strictly pause until the pearl catch finishes" actually
        // hold. Below the pearl macro, AutoMace's own HIGHEST smash aim still outranks the LOW sword
        // AimAssist, so the order is Pearl > AutoMace > AimAssist.
        if (isExternallyPaused()) {
            if (spearAimOnlyActive()) {
                runSpearAimOnly();
            } else if (!SpearMaceDtap.firedThisTick()) {
                abortAiming();
            }
            return;
        }

        // A smash burst or stun slam is mid-flight: keep the aim on its target ahead of every idle
        // gate below (a fall that momentarily reads as over must not drop the follow-up's ray).
        if (stunSlamState != StunSlamState.IDLE && currentTarget != null && currentTarget.isAlive()) {
            applySmashAim();
            if (stunSlamState == StunSlamState.AXE_ATTACK) {
                pollStunAxe();
            }
            // The mace follow-up is decided and dispatched on tick heads only (tickStunSlamCombo): a
            // frame's click would be held to the next head anyway, where the tick path acts on a fresh
            // forecast. Frames between the two clicks only keep the aim on the target.
            return;
        }

        // Update the fall tracker before activation. Reset whenever the player is on ground,
        // climbing, in liquid/web, touching water, or in a vehicle.
        if (mc.player != null) {
            if (mc.player.isOnGround() || mc.player.isClimbing() || isInLiquidOrWeb()
                    || mc.player.isTouchingWater() || mc.player.hasVehicle()) {
                highestY = mc.player.getY();
            } else {
                highestY = Math.max(highestY, mc.player.getY());
            }
        }

        if (isInLiquidOrWeb()) {
            stopAiming();
            return;
        }

        // A direct smash burst or stun slam combo is mid-flight — keep the cranked aim glued to the target.
        if (smashStage > 0 || stunSlamState != StunSlamState.IDLE) {
            if (currentTarget != null && currentTarget.isAlive()) {
                applySmashAim();
            }
            return;
        }

        // Manual Trigger: nothing is acquired, aimed or armed here. handleAttack() starts every
        // sequence from the player's own click; this pass only keeps the idle state clean.
        if (isManualMode()) {
            shouldDirectSmashArmed = false;
            if (!burstClaimed) {
                currentTarget = null;
            }
            releaseAim();
            return;
        }

        // Aiming and smashing are only active during a genuine smash fall (falling past >= 1.5 blocks).
        // Walking on the ground, jumping upward, standing still, or ordinary jump crits (which only fall
        // ~1.25 blocks) must never capture the player's view or rotation channel.
        if (!hasSmashFallRequirement()) {
            stopAiming();
            shouldDirectSmashArmed = false;
            return;
        }

        boolean canUseMace = mc.player.getMainHandStack().getItem() instanceof MaceItem || findBestMace() != -1;
        if (!canUseMace || (weaponOnly.isEnabled() && !isHoldingWeapon())) {
            stopAiming();
            return;
        }

        currentTarget = findTarget();

        if (currentTarget == null || !isAimTargetValid(currentTarget)) {
            stopAiming();
            return;
        }

        updateBackstabFallback(currentTarget);
        boolean isBlocking = isTargetBlocking(currentTarget);
        boolean canStunSlam = isStunSlamSelected() && isBlocking;

        // Only Stunslam — when on, do nothing unless a stun-slam is actually possible. Kept ABOVE
        // the elytra-drop request so we never drop the wing for a non-slam we won't take.
        if (onlyStunSlam.isEnabled() && isStunSlamSelected() && !canStunSlam) {
            stopAiming();
            return;
        }

        // Perfect AutoElytra sync: only drop the wing once the exact victim is inside the same
        // predicted swept-reach bubble that can arm the mace smash. The old engage-range gate could
        // strip the wing just because we were descending near a target and had ground clearance, which
        // made low/near-ground passes around ~10 blocks away lose the elytra before a smash existed.
        boolean divingAtTarget = isWearingElytra() && !mc.player.isOnGround()
                && mc.player.getVelocity().y < -0.10
                && currentTarget.getY() < mc.player.getY() - 0.5
                && isWithinPredictedSmashReach(currentTarget)
                && groundClearanceBelow() > MIN_DIVE_CLEARANCE;   // real height below → never a ground swap
        if (divingAtTarget) {
            AutoElytra elytra = getAutoElytra();
            if (elytra != null) {
                // Pass the victim so AutoElytra locks the wing to exactly who the mace is diving at.
                elytra.requestDivebombDrop(currentTarget);
            }
        }

        // Aim at the target once falling in a valid smash and within engagement distance.
        double aimDist = getAimEngagementDistance(currentTarget);
        double dist = mc.player.distanceTo(currentTarget);
        if (dist <= aimDist) {
            applySmashAim();
        } else {
            stopAiming();
        }

        // Keep the lock alive during the inter-smash cooldown; only the action path is paused.
        if (cooldownTicks > 0) {
            return;
        }
        // Lock-On Wait / Pearl Arrival Wait: aim keeps tracking, nothing is armed or fired yet.
        if (isFireHeld(currentTarget)) {
            shouldDirectSmashArmed = false;
            return;
        }

        // Pre-arm or immediately execute Stun Slam during a valid smash fall
        if (canStunSlam && shouldPreArmStunSlam()) {
            if (!burstClaimed && smashStage == 0 && stunSlamState == StunSlamState.IDLE) {
                applySmashAim();
                refreshCrosshairForActiveAim();
                if (canHitWithLegitClick(currentTarget)) {
                    // The slam releases the shield itself, after the axe is selected.
                    int returnSlot = autoSpearReturnSlot(userHeldSlot());
                    startStunSlamSequence(returnSlot);
                    return;
                }
            }
            unblockOwnShield();
            shouldDirectSmashArmed = true;
            return;
        }

        // Trigger per-frame smash evaluation
        calculateDirectMaceLogic();
    }

    /**
     * Predicted swept-reach check used to decouple the direct smash from the fall-distance gate:
     * does the eye segment over the upcoming tick come within the speed-scaled attack range of the
     * lead-corrected target hitbox? Crosshair-independent (like the direct attack itself).
     */
    private boolean isWithinPredictedSmashReach(PlayerEntity target) {
        if (mc.player == null || target == null) {
            return false;
        }
        return sweptApproach(target) <= sweptReach();
    }

    /** Attack reach widened toward {@link #SWEPT_REACH_CAP} by how far the player moves per tick. */
    private double sweptReach() {
        double base = attackRange();
        return CombatPrediction.speedScaledRange(
                base, Math.max(SWEPT_REACH_CAP, base), CombatPrediction.perTickDisplacement(mc.player));
    }

    /**
     * Closest the eye gets to the target hitbox over the upcoming tick. Both halves of the swept
     * test use ONE motion model (pos - lastPos), and the target box is CO-SWEPT with the player
     * fraction t (boxNow + targetVel*t) instead of frozen at a fixed lead.
     */
    private double sweptApproach(PlayerEntity target) {
        Vec3d eyeNow = mc.player.getEyePos();
        Vec3d eyeNext = eyeNow.add(CombatPrediction.perTickVelocityVec(mc.player));
        Box boxNow = CombatPrediction.predictBox(target, 0.0);
        Vec3d targetVel = CombatPrediction.perTickVelocityVec(target);
        return CombatPrediction.closestApproachOverTick(eyeNow, eyeNext, boxNow, targetVel);
    }

    /**
     * The small, descending approach window in which AutoMace may own the visible camera.
     * It deliberately sits well inside the configurable candidate range, but extends past click
     * reach so a target that moves behind the player can be turned toward before the smash arrives.
     */
    private boolean isCommittedSmashApproach(PlayerEntity target) {
        if (mc.player == null || target == null || !target.isAlive()) {
            return false;
        }
        if (!hasSmashFallRequirement()) {
            return false;
        }

        double turnInDistance = getAimEngagementDistance(target);
        return mc.player.distanceTo(target) <= turnInDistance || isWithinPredictedSmashReach(target);
    }

    private boolean isInLiquidOrWeb() {
        if (mc.player == null || mc.world == null)
            return false;
        Box box = mc.player.getBoundingBox();
        // Asked a dozen times per frame (every fall gate); the answer only changes when the box or the
        // blocks around it do, so it is reused for the same box within one tick.
        if (liquidCacheWorld == mc.world && liquidCacheTick == stunClientTickId
                && liquidCacheX == box.minX && liquidCacheY == box.minY && liquidCacheZ == box.minZ) {
            return liquidCacheValue;
        }
        liquidCacheWorld = mc.world;
        liquidCacheTick = stunClientTickId;
        liquidCacheX = box.minX;
        liquidCacheY = box.minY;
        liquidCacheZ = box.minZ;
        liquidCacheValue = scanLiquidOrWeb(box);
        return liquidCacheValue;
    }

    private boolean scanLiquidOrWeb(Box box) {
        BlockPos min = BlockPos.ofFloored(box.minX, box.minY, box.minZ);
        BlockPos max = BlockPos.ofFloored(box.maxX, box.maxY, box.maxZ);
        BlockPos.Mutable mutable = new BlockPos.Mutable();

        for (int x = min.getX(); x <= max.getX(); x++) {
            for (int y = min.getY(); y <= max.getY(); y++) {
                for (int z = min.getZ(); z <= max.getZ(); z++) {
                    mutable.set(x, y, z);
                    BlockState state = mc.world.getBlockState(mutable);
                    if (state.getFluidState().isIn(FluidTags.WATER) || state.getBlock() == Blocks.COBWEB) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private void calculateDirectMaceLogic() {
        if (isAwaitingBounce()) return;

        if (currentTarget == null || !currentTarget.isAlive() || !isAimTargetValid(currentTarget) ||
                mc.player.distanceTo(currentTarget) > getAimEngagementDistance(currentTarget)) {
            stopAiming();
            return;
        }

        // Good aiming: crank the crosshair onto the target so it is already dead-on by the
        // time we are in range.
        applySmashAim();

        boolean holdingMace = mc.player.getMainHandStack().getItem() instanceof MaceItem;
        if (findBestMace() == -1 && !holdingMace) {
            return;
        }

        // PREDICTED swept-reach trigger. Fire on the tick where the eye segment over the upcoming
        // tick comes within the SPEED-SCALED attack range of the lead-corrected hitbox, NOT the
        // instantaneous distance (which a fast diver skips between ticks). The cooldown gate is
        // loosened from progress==1 to >=0.95 so a fast diver in-range for only one tick still
        // fires. This only ARMS the burst; startSmashBurst re-validates instantaneous legal reach
        // before the packet is sent.
        double reach = sweptReach();
        double approach = sweptApproach(currentTarget);

        // Unblock shield early during committed descent approach so shield drops well before strike window
        // The release leaves from a tick head and the axe from the one after it, so the lead is the
        // distance fallen in about three ticks: at 1.8 blocks/tick a fixed 1.5 blocks is already late.
        double releaseLead = Math.max(1.5, 3.0 * CombatPrediction.perTickDisplacement(mc.player));
        if (isAirborneFalling() && (approach <= reach + releaseLead
                || isWithinLegitReach(currentTarget, attackRange() + releaseLead))) {
            unblockOwnShield();
        }

        if (isBurstCooldownReady() && hasSmashFallRequirement()
                && (isWithinLegitReach(currentTarget, attackRange()) || approach <= reach)) {
            unblockOwnShield();
            if (!burstClaimed && smashStage == 0 && stunSlamState == StunSlamState.IDLE) {
                if (isWithinLegitReach(currentTarget, attackRange()) && canHitWithLegitClick(currentTarget)) {
                    burstClaimed = true;
                    startSmashBurst();
                    return;
                }
            }
            shouldDirectSmashArmed = true;
        }
    }

    private boolean isBurstCooldownReady() {
        if (mc.player == null) {
            return false;
        }
        // Only the module's own feature cooldown gates a burst. The vanilla attack-charge meter is
        // deliberately NOT a gate: it reads near zero right after a recent swing or item change, which
        // held off the first hit of a fast dive for ~5 ticks. AttackValidator still enforces the
        // vanilla attack interval (mc.attackCooldown) at dispatch.
        return monoMs() - lastAttackTime >= sampledCooldownMs
                && cooldownTicks <= 0;
    }

    private boolean shouldPreArmStunSlam() {
        if (mc.player == null || currentTarget == null || !hasSmashFallRequirement()
                || findAxe() < 0 || findBestMace() < 0 || !isBurstCooldownReady()) {
            return false;
        }
        return isWithinLegitReach(currentTarget, stunAxeReach());
    }

    // Trigger tick — the SAME point for both the plain smash and the stun-slam. Gated strictly on
    // instantaneous server-legal reach, so no packet is ever sent out of reach; a too-early arm simply
    // no-fires and retries next tick (cooldown untouched).
    //  • shield DOWN → swap to mace, hit, swap back next tick (the plain smash — unchanged).
    //  • shield UP   → axe (disable shield) → mace (smash) → swap back (the stun-slam).
    private void startSmashBurst() {
        if (!isEnabled() || mc.player == null || mc.world == null || mc.interactionManager == null
                || mc.currentScreen != null) {
            burstClaimed = false;
            return;
        }
        // Backstab: never claim a burst while the shield covers us. The caller retries on the next
        // frame or tick, so the strike lands the moment we are on the unshielded side.
        if (isBackstabHold(currentTarget)) {
            burstClaimed = false;
            return;
        }
        // Lock-On Wait / Pearl Arrival Wait (Manual Trigger: pearl arrival only).
        if (isFireHeld(currentTarget)) {
            burstClaimed = false;
            return;
        }

        // AutoSpear Reach just selected the spear in this packet interval: the mace selection must
        // follow in a later one, so wait for the next tick instead of stacking two hotbar changes.
        if (autoSpearSelectedThisInterval()) {
            burstClaimed = false;
            shouldDirectSmashArmed = true;
            return;
        }
        // Claim BEFORE any attack is dispatched -- see burstClaimed's javadoc.
        burstClaimed = true;
        // A stun slam releases the shield after its axe selection (see startStunSlamSequence); every
        // other path drops it here, ahead of the mace selection.
        if (!(isStunSlamSelected() && findAxe() != -1 && isTargetBlocking(currentTarget)
                && !isPlayerBehindTarget(currentTarget))) {
            unblockOwnShield();
        }
        if (mc.player == null || mc.interactionManager == null
                || currentTarget == null || !currentTarget.isAlive()
                || !isAimTargetValid(currentTarget)
                || !hasSmashFallRequirement()) {
            smashStage = 0;
            smashReturnSlot = -1;
            burstClaimed = false;
            return;
        }

        // A blocker we are clearly behind cannot soak the hit, so a backstab skips the axe and smashes directly.
        boolean targetBlocking = isStunSlamSelected() && findAxe() != -1
                && isTargetBlocking(currentTarget) && !isPlayerBehindTarget(currentTarget);

        // Confirm reach BEFORE touching any slot, so an out-of-reach tick never desyncs the hotbar.
        // Gated strictly on true vanilla reach (<= 3.0m). Swept prediction is only allowed for unshielded smashes.
        // A stun slam's axe needs room for the shield pushback before the mace (see stunAxeReach).
        boolean inReach = targetBlocking
                ? isWithinLegitReach(currentTarget, stunAxeReach())
                : isWithinLegitReach(currentTarget, attackRange()) || isWithinPredictedSmashReach(currentTarget);
        if (!inReach) {
            smashStage = 0;
            smashReturnSlot = -1;
            burstClaimed = false;
            return;
        }

        // Verify crosshair is confirmed on target before committing to attack
        if (currentTarget != null && currentTarget.isAlive()) {
            applySmashAim();
        }
        refreshCrosshairForActiveAim();
        if (!canHitWithLegitClick(currentTarget)) {
            smashStage = 0;
            smashReturnSlot = -1;
            burstClaimed = false;
            return;
        }

        // A smash or stun slam that starts from the held spear keeps its 4.5 reach through the axe and
        // mace swaps (see isSpearReachActive).
        latchSpearReach();

        int currentHeldSlot = userHeldSlot();
        ItemStack heldStack = mc.player.getInventory().getStack(currentHeldSlot);
        int returnSlot;
        if (isNonMaceWeapon(heldStack)) {
            returnSlot = currentHeldSlot;
            lastWeaponSlot = currentHeldSlot;
        } else if (!isMaceSlot(currentHeldSlot)) {
            returnSlot = currentHeldSlot;
        } else {
            returnSlot = findWeaponSlot();
            if (returnSlot == -1) {
                returnSlot = (lastWeaponSlot >= 0 && lastWeaponSlot < 9) ? lastWeaponSlot : currentHeldSlot;
            }
        }

        returnSlot = autoSpearReturnSlot(returnSlot);
        originalSlot = returnSlot;
        smashReturnSlot = returnSlot;
        preSequenceSlot = returnSlot;

        // ── Shield UP → stun-slam. Same trigger, true vanilla reach; axe shield-break first. ──
        if (targetBlocking) {
            startStunSlamSequence(returnSlot);
            return;
        }

        // Shield DOWN. Only Stunslam never does a plain smash (covers the rare case where the target
        // lowered its shield between the arm and this fire tick) — stand down instead.
        if (onlyStunSlam.isEnabled() && isStunSlamSelected()) {
            recover();
            return;
        }

        // ── Shield DOWN → plain mace smash: select now, hit after the configured varied delay. ──
        boolean holdingMace = heldStack.getItem() instanceof MaceItem;
        int maceSlot = findBestMace();
        if (maceSlot == -1 && holdingMace) {
            maceSlot = currentHeldSlot;
        }
        if (maceSlot < 0 || maceSlot > 8) {
            recover();
            return;
        }
        if (maceSlot != currentHeldSlot) {
            if (!TickActionOrder.canSwitchSlot()) {
                // An attack/use-release already left in this packet interval (e.g. the shield drop):
                // select the mace first thing in the next one instead of after it.
                burstClaimed = false;
                shouldDirectSmashArmed = true;
                return;
            }
            if (!selectSlotLegit(maceSlot)) {
                recover();
                return;
            }
            swappedByModule = true;
            // Real, bounded wire gap between the hotbar packet and the smash click, inside this tick.
            SubTickSequencer.awaitWireSeparation(mc);
        }

        directMacePreparedSlot = maceSlot;
        directMacePreparedTickId = stunClientTickId;
        directMacePreparedInterval = TickActionOrder.interval();

        // The hit follows the mace selection in this same call, so both leave in one movement-packet
        // interval (a delay here would only push the hit past this tick's movement packet). A hit
        // vanilla refuses is retried briefly, but only while that interval is still open.
        directMaceHitDueNs = System.nanoTime();
        directMaceRetryUntilNs = directMaceHitDueNs + DIRECT_MODE_RETRY_WINDOW_NS;
        smashStage = 3;
        pollPreparedDirectMaceHit();
    }

    /**
     * Completes a prepared direct smash. The slot was selected before the render-frame delay;
     * do not re-select/sync it here, otherwise the attack would again be adjacent to a slot swap.
     */
    private void pollPreparedDirectMaceHit() {
        if (smashStage != 3) {
            return;
        }
        if (!isEnabled() || mc.player == null || mc.world == null
                || mc.interactionManager == null || mc.currentScreen != null) {
            recover();
            return;
        }
        if (currentTarget == null || !currentTarget.isAlive()) {
            recover();
            return;
        }
        // The hit belongs to the interval of the mace selection. Once that interval's movement packet
        // is out, the hit would follow the swap a tick late: undo the swap and re-plan instead.
        if (TickActionOrder.interval() != directMacePreparedInterval) {
            recover();
            return;
        }
        if (mc.player.getInventory().getSelectedSlot() != directMacePreparedSlot
                || !(mc.player.getMainHandStack().getItem() instanceof MaceItem)) {
            recover();
            return;
        }
        applySmashAim();
        refreshCrosshairForActiveAim();
        if (!canHitWithLegitClick(currentTarget)) {
            // Crosshair still converging after the swap: keep the prepared slot and retry next frame
            // inside the window. recover() would tell AutoElytra the combo is over and re-deploy the
            // wing right at the hit, which then blocks every action (elytraBlocksAction).
            if (System.nanoTime() < directMaceRetryUntilNs) {
                return;
            }
            recover();
            return;
        }
        if (mc.player.isUsingItem()) {
            // Slot first, release second, click in a later interval: never release and attack together.
            releaseOwnShield();
            return;
        }
        boolean attacked = performPreparedAttack(false);
        if (!attacked) {
            if (System.nanoTime() < directMaceRetryUntilNs) {
                return;
            }
            recover();
            return;
        }

        commitMaceAttack();
        smashStage = 2;
        smashSwapBackHoldTicks = sampleSwapBackHoldTicks();
        // The deadline was never set on this path, so the wait lapsed at once (or on a stale stun-slam
        // deadline). It now lasts about one round trip: at high ping the client keeps falling until the
        // server's bounce arrives, and a second "smash" in that window is a plain hit the server refuses
        // to count as a smash.
        armBounceWait(0L);
    }

    private void suppressAttackInput() {
        if (mc.options != null && mc.options.attackKey != null) {
            while (mc.options.attackKey.wasPressed()) {}
            mc.options.attackKey.setPressed(false);
        }
    }

    public boolean doAxeDisable(int axeSlot) {
        if (mc.player == null || currentTarget == null || !currentTarget.isAlive() || axeSlot < 0 || axeSlot > 8) {
            return false;
        }
        applySmashAim();
        refreshCrosshairForActiveAim();
        if (!canHitWithLegitClick(currentTarget)) {
            return false;
        }
        return performSlotAttack(axeSlot, !isSilentMode());
    }

    public boolean doMaceSmash(int maceSlot) {
        if (mc.player == null || currentTarget == null || !currentTarget.isAlive() || maceSlot < 0 || maceSlot > 8) {
            return false;
        }
        boolean legitStunFollowup = stunSlamState != StunSlamState.IDLE;
        applySmashAim();
        refreshCrosshairForActiveAim();
        if (!canHitWithLegitClick(currentTarget)) {
            if (wantsStunRefusal()) {
                // Only the first refusal of a slam is kept; the extra ray for the message is cast once.
                noteStunRefusal("mace: reach=" + isWithinLegitReach(currentTarget, attackRange())
                        + " crosshair=" + isCrosshairOnTarget(currentTarget));
            }
            return false;
        }
        if (!performSlotAttack(maceSlot, !isSilentMode() && legitStunFollowup)) {
            return false;
        }
        // The vanilla attack callback is the execution authority for a Stun Slam.  It fires
        // synchronously from the supported MinecraftClient path, so committing again here would
        // double-post MaceHitEvent and double-advance cooldown state.
        if (!legitStunFollowup || !stunMaceAttackExecuted) {
            commitMaceAttack();
        }
        return true;
    }

    /** First reason the mace follow-up was refused this slam; logged if the slam unwinds without it. */
    private String stunRefusal = null;

    /** True while a refusal note would be recorded, so callers skip building the message otherwise. */
    private boolean wantsStunRefusal() {
        return isStunFollowUpPhase() && stunRefusal == null;
    }

    private void noteStunRefusal(String reason) {
        if (wantsStunRefusal()) {
            stunRefusal = reason + " [tickId=" + stunClientTickId
                    + " sinceAxe=" + (stunClientTickId - stunAxeExecutedTickId)
                    + " wire=" + (currentWireTick() - stunAxeWireTickId)
                    + " gap=" + stunRequiredTickGap
                    + " canSwitch=" + TickActionOrder.canSwitchSlot()
                    + " window=" + TickActionOrder.isWindowOpen() + "]";
        }
    }

    private void commitMaceAttack() {
        advancePressure();
        holdSilentAimAfterAttack();
        EventBus.post(new MaceHitEvent());
        sampledCooldownMs = Math.round(cooldown.sample());
        cooldownTicks = msToTicks(sampledCooldownMs) + ModuleDelays.msToTicks(profileDelay.sample());
        lastAttackTime = monoMs();
        if (mc.player != null) {
            highestY = mc.player.getY();
        }
        // No vanilla attackCooldown write here: the module's own cooldown (lastAttackTime/cooldownTicks)
        // already prevents a second dispatch, and vanilla never locks the player's own clicks after a hit.
    }

    @Override
    public void onVanillaEntityAttack(Entity target) {
        if (reentrantAttack && target != null && target == currentTarget) {
            attackLandedThisClick = true;
        }
        if (!burstClaimed || target == null || target != currentTarget || mc.player == null) {
            return;
        }
        Item held = mc.player.getMainHandStack().getItem();
        if (held instanceof AxeItem && (stunSlamState == StunSlamState.AXE_ATTACK
                || stunSlamState == StunSlamState.WAITING_DELAY)) {
            stunAxeAttackExecuted = true;
            stunAxeExecutedTickId = stunClientTickId;
            stunAxeWireTickId = currentWireTick();
            return;
        }
        if (held instanceof MaceItem && (stunSlamState == StunSlamState.MACE_SMASH
                || stunSlamState == StunSlamState.SWAP_BACK)) {
            if (!stunMaceAttackExecuted) {
                stunMaceAttackExecuted = true;
                commitMaceAttack();
            }
        }
    }

    private boolean performSlotAttack(int slot, boolean forceLegit) {
        // Every swing this module makes funnels through here (mace and axe alike), so the elytra
        // click-suppression only needs to live at this one point.
        if (elytraBlocksAction()) {
            noteStunRefusal("elytra worn");
            return false;
        }
        // Hotbar first (Rule 1/2 order), always ahead of any use-release in the interval.
        int heldBefore = mc.player != null ? mc.player.getInventory().getSelectedSlot() : -1;
        if (!selectSlotLegit(slot)) {
            if (wantsStunRefusal()) {
                noteStunRefusal("hotbar select refused (slot " + slot + ", held "
                        + (mc.player != null ? mc.player.getInventory().getSelectedSlot() : -1) + ")");
            }
            return false;
        }
        if (mc.player != null && mc.player.isUsingItem()) {
            // Vanilla ignores an attack click while the shield is raised and never sends a release and
            // an attack in one interval (Grim PacketOrderI, AGC tick order). The release leaves now,
            // after the selection; the click goes out on the next tick head.
            releaseOwnShield();
            noteStunRefusal("shield released this interval; click follows on the next tick");
            return false;
        }
        if (heldBefore != slot) {
            // Real, bounded wire gap between the hotbar packet and the click, inside this tick.
            SubTickSequencer.awaitWireSeparation(mc);
        }

        return performPreparedAttack(forceLegit);
    }

    /** Dispatches vanilla attack using the already-selected item; no hotbar mutation occurs here. */
    private boolean performPreparedAttack(boolean forceLegit) {
        if (!isEnabled() || mc.player == null || mc.world == null || mc.interactionManager == null
                || mc.currentScreen != null || currentTarget == null || !currentTarget.isAlive()
                || !canHitWithLegitClick(currentTarget)) {
            return false;
        }

        // An attack never shares an interval with a use-release or another attack.
        if (!TickActionOrder.canAttack()) {
            noteStunRefusal("an action already left in this interval");
            return false;
        }

        if (!registersOnServer(currentTarget)) {
            noteStunRefusal("registration check: server box out of reach or vanilla fall too short");
            return false;
        }

        // Vanilla ignores attack clicks while an item is in use, so an attack from a still-raised
        // shield is a sequence a vanilla client cannot send.
        if (mc.player.isUsingItem()) {
            noteStunRefusal("still using item (shield) at the click");
            return false;
        }

        // 1. Dispatch real GLFW mouse event for Keystrokes HUD visibility.  If a screen or invalid
        // input state rejects the physical click, do not fall through to a hidden interaction.
        if (!AttackValidator.emitAttackClick(mc)) {
            noteStunRefusal("attack click rejected (screen or input state)");
            return false;
        }

        // 2. Immediate zero-latency vanilla doAttack() execution
        if (mc instanceof MinecraftClientAccessor accessor) {
            // Legit clears vanilla's miss-click cooldown; Direct leaves that gate untouched, so a click
            // vanilla would refuse is refused and retried.
            ExecutionMode.relaxAttackCooldown(accessor);
            boolean fired;
            if (isSpearReachActive(currentTarget) && !isSilentMode() && spearRay() instanceof EntityHitResult ray
                    && ray.getEntity() == currentTarget) {
                // Vanilla's pick used the axe or mace's 3.0 reach; the live ray (already validated
                // against the target above) is what the click resolves against.
                mc.crosshairTarget = ray;
            }
            attackLandedThisClick = false;
            reentrantAttack = true;
            try {
                // Silent Aim reroutes vanilla's attack along the server angles at a fixed default
                // reach; this module's reach is the vanilla 3.0.
                fired = RotationManager.withSilentReach(attackRange(),
                        () -> AttackValidator.invokeVanillaAttack(mc));
            } finally {
                reentrantAttack = false;
            }
            suppressAttackInput();
            // The real result in both modes: vanilla's answer, or the attackEntity callback proving
            // the hit on the target left. A refused click is never counted as a hit.
            return fired || attackLandedThisClick;
        }

        return true;
    }

    /**
     * Registration Check: the target box as the server will hold it when this click arrives (latency
     * ahead, from the intercept engine) must be inside reach from the eye, and a mace click needs the
     * vanilla fall distance the server requires for a smash. True when the check is off, or when the
     * target is not tracked yet (no server box to test against).
     */
    private boolean registersOnServer(PlayerEntity target) {
        if (!registrationCheck.isEnabled() || mc.player == null || target == null) {
            return true;
        }
        if (mc.player.getMainHandStack().getItem() instanceof MaceItem
                && mc.player.fallDistance <= StunSlamEngine.MIN_FALL_DISTANCE) {
            return false;
        }
        Box server = InterceptPredictor.getInstance().serverBox(target, 0.0);
        if (server == null) {
            return true;
        }
        Vec3d eye = mc.player.getEyePos();
        double dx = Math.max(Math.max(server.minX - eye.x, 0.0), eye.x - server.maxX);
        double dy = Math.max(Math.max(server.minY - eye.y, 0.0), eye.y - server.maxY);
        double dz = Math.max(Math.max(server.minZ - eye.z, 0.0), eye.z - server.maxZ);
        return Math.sqrt(dx * dx + dy * dy + dz * dz) <= attackRange(target) - InterceptPredictor.SERVER_MARGIN;
    }

    /** Drops the own shield (tick head only); an attack never follows in the same interval. */
    private void unblockOwnShield() {
        releaseOwnShield();
    }

    private void releaseOwnShield() {
        if (!autoShieldUnblock.isEnabled() || mc.player == null) {
            return;
        }
        boolean hasShield = mc.player.getMainHandStack().getItem() instanceof net.minecraft.item.ShieldItem
                || mc.player.getOffHandStack().getItem() instanceof net.minecraft.item.ShieldItem
                || (mc.player.isUsingItem() && mc.player.getActiveItem().getItem() instanceof net.minecraft.item.ShieldItem);
        if (!hasShield) {
            return;
        }
        boolean isPhysicallyHoldingUse = mc.options != null && mc.options.useKey != null
                && (mc.options.useKey.isPressed()
                    || SystemInputSimulator.isPhysicallyPressed(mc.options.useKey)
                    || InputTracker.isMouseButtonPressed(GLFW.GLFW_MOUSE_BUTTON_RIGHT));
        boolean isUsingShield = mc.player.isBlocking()
                || (mc.player.isUsingItem() && mc.player.getActiveItem().getItem() instanceof net.minecraft.item.ShieldItem);
        if (isUsingShield || isPhysicallyHoldingUse || shouldReblockShield) {
            if (isPhysicallyHoldingUse || isUsingShield) {
                shouldReblockShield = true;
            }
            if (mc.options != null && mc.options.useKey != null) {
                while (mc.options.useKey.wasPressed()) {}
                mc.options.useKey.setPressed(false);
            }
            SystemInputSimulator.releaseOwner(this);
            // The release packet is an action: it leaves from the tick head only, so a render frame
            // never queues it into the interval of the click that follows (Grim PacketOrderI).
            if (TickActionOrder.isWindowOpen() && mc.interactionManager != null && mc.player.isUsingItem()) {
                mc.interactionManager.stopUsingItem(mc.player);
                TickActionOrder.recordUse();
            }
        }
    }

    private void restoreOwnShieldIfNeeded() {
        if (!shouldReblockShield) {
            return;
        }
        shouldReblockShield = false;
        SystemInputSimulator.releaseOwner(this);
        if (mc.player == null || mc.options == null || mc.options.useKey == null || mc.currentScreen != null) {
            return;
        }
        boolean userHoldingUse = SystemInputSimulator.isPhysicallyPressed(mc.options.useKey)
                || InputTracker.isMouseButtonPressed(GLFW.GLFW_MOUSE_BUTTON_RIGHT);
        if (userHoldingUse) {
            SystemInputSimulator.restorePhysicalState(mc.options.useKey);
            if (mc.options.useKey instanceof KeyBindingAccessor acc) {
                acc.setTimesPressed(Math.max(acc.getTimesPressed() + 1, 1));
            }
        } else {
            mc.options.useKey.setPressed(false);
            while (mc.options.useKey.wasPressed()) {}
            SystemInputSimulator.restorePhysicalState(mc.options.useKey);
        }
    }

    /** Executes the axe at the first valid render or tick opportunity. */
    private void pollStunAxe() {
        if (stunSlamState != StunSlamState.AXE_ATTACK
                || mc.player == null || currentTarget == null || !currentTarget.isAlive()
                || isExternallyPaused() || isRotationLockedByOther() || elytraBlocksAction()) {
            return;
        }

        // A shield drop before our axe actually fires invalidates this transaction.  Recover and let
        // the ordinary direct-smash path reassess it; never synthesize a mace half of a missing pair.
        if (!isTargetBlocking(currentTarget)) {
            recover();
            return;
        }

        int axeSlot = findAxe();
        if (axeSlot < 0 || findBestMace() < 0 || !hasSmashFallRequirement()) {
            recover();
            return;
        }

        applySmashAim();
        refreshCrosshairForActiveAim();
        // Every failure below is transient (ray still converging, hotbar window already used this
        // interval, shield release still settling). The axe is the half of the slam that must land,
        // so it is retried on every render frame and tick until it does; only a real invalidation
        // above (shield dropped, fall over, items gone, target dead) or the backstop in
        // tickStunSlamCombo ends the attempt.
        if (!canHitWithLegitClick(currentTarget)) {
            return;
        }
        // Wait (the dive keeps closing) until the axe leaves room for the shield pushback.
        if (!isWithinLegitReach(currentTarget, stunAxeReach())) {
            return;
        }

        if (!doAxeDisable(axeSlot)) {
            return;
        }

        // performSlotAttack invokes vanilla doAttack synchronously in the supported client.  The
        // local vanilla callback is the proof the axe went out; without it nothing was sent, so keep
        // waiting in AXE_ATTACK (a duplicate is impossible: the callback latches on the first one).
        if (!stunAxeAttackExecuted) {
            return;
        }
        stunAxeQueuedAtMs = monoMs();

        reachWaitTicks = 0;
        elapsedStunTicks = 0;
        if (stunSlamMissed) {
            // Failed hit-chance roll: the shield is already disabled; skip the mace and swap back.
            stunSlamMissed = false;
            // Arm the module cooldown so the plain smash is skipped for this hit too, not just the stun follow-up.
            sampledCooldownMs = Math.round(cooldown.sample());
            cooldownTicks = msToTicks(sampledCooldownMs) + ModuleDelays.msToTicks(profileDelay.sample());
            lastAttackTime = monoMs();
            stunSwapBackHoldTicks = sampleSwapBackHoldTicks();
            stunSlamState = StunSlamState.SWAP_BACK;
            return;
        }
        // The mace swap cannot share the axe's movement interval (a hotbar change after an attack
        // is not a vanilla sequence), so the earliest legal follow-up is one tick later. The engine
        // picks the gap (Normal: the slider; Adaptive: the most ticks the forecast says still hit,
        // under the fall-speed profile); an adaptive profile of 0 means "as soon as legal".
        planStunFollowUp(currentTarget);
        stunSlamState = StunSlamState.WAITING_DELAY;

        // Keep the axe selected until the next legal client-tick boundary.  Pre-selecting the mace
        // here used to happen in the same render pass as the axe swing, which made the axe invisible
        // in the vanilla hotbar and hid its number-key pulse from Keystrokes HUDs.  The tick head
        // (tickStunSlamCombo -> doMaceSmash) performs the synchronized mace selection immediately
        // before the follow-up attack once the engine clears it.
    }

    /**
     * Executes the direct mace smash on render frames as soon as reach and crosshair align,
     * decoupling attack execution from 20Hz tick boundaries while preserving hotbar synchronization.
     */
    private void pollDirectSmash() {
        if (burstClaimed || smashStage > 0 || stunSlamState != StunSlamState.IDLE
                || mc.player == null || currentTarget == null || !currentTarget.isAlive()
                || isExternallyPaused() || isRotationLockedByOther() || elytraBlocksAction()) {
            return;
        }

        if (isAwaitingBounce() || !isBurstCooldownReady() || !hasSmashFallRequirement()) {
            return;
        }

        unblockOwnShield();
        applySmashAim();
        refreshCrosshairForActiveAim();

        if (isWithinLegitReach(currentTarget, attackRange()) && canHitWithLegitClick(currentTarget)) {
            shouldDirectSmashArmed = false;
            burstClaimed = true;
            startSmashBurst();
        }
    }

    private void startStunSlamSequence(int returnSlot) {

        burstClaimed = true;
        latchSpearReach();
        // The shield is released by performSlotAttack right after the axe is selected: releasing it
        // here would use up this packet interval's hotbar window and cost the axe a whole tick.
        int axeSlot = findAxe();
        int maceSlot = findBestMace();
        if (axeSlot < 0 || maceSlot < 0) {
            recover();
            return;
        }

        if (currentTarget != null && currentTarget.isAlive()) {
            applySmashAim();
        }

        int returnSlotToUse = returnSlot;
        if (isMaceSlot(returnSlotToUse)) {
            int weaponSlot = findWeaponSlot();
            if (weaponSlot != -1) {
                returnSlotToUse = weaponSlot;
            } else if (lastWeaponSlot >= 0 && lastWeaponSlot < 9 && !isMaceSlot(lastWeaponSlot)) {
                returnSlotToUse = lastWeaponSlot;
            }
        }
        originalSlot = returnSlotToUse;
        smashReturnSlot = returnSlotToUse;
        preSequenceSlot = returnSlotToUse;
        swappedByModule = true;
        reachWaitTicks = 0;
        stunAxeAttackExecuted = false;
        stunMaceAttackExecuted = false;
        stunAxeQueuedAtMs = 0L;
        stunAxeExecutedTickId = -1L;
        stunAxeWireTickId = -1L;
        stunSwapBackHoldTicks = 0;
        stunRequiredTickGap = 1;
        stunParams = null;
        stunPlan = null;
        stunForecastBox = null;
        elapsedStunTicks = 0;
        // Stun Slam Hit Chance: a failed roll keeps the axe and skips the mace follow-up (100 = always).
        stunSlamMissed = ThreadLocalRandom.current().nextInt(100) >= (int) Math.round(stunSlamHitChance.getValue());

        stunSlamState = StunSlamState.AXE_ATTACK;
        // Fire during the same evaluation that proved shield, reach, aim and fall validity.
        pollStunAxe();
    }

    private void tickStunSlamCombo() {
        if (mc.player == null || currentTarget == null || !currentTarget.isAlive()) {
            recover();
            return;
        }

        applySmashAim();

        switch (stunSlamState) {
            case AXE_ATTACK -> {
                // Retry at the first tick opportunity as well as every render frame.
                pollStunAxe();
                if (stunSlamState != StunSlamState.AXE_ATTACK) {
                    return;
                }

                if (!isTargetBlocking(currentTarget)) {
                    recover();
                    return;
                }

                if (findAxe() < 0 || findBestMace() < 0 || !hasSmashFallRequirement()) {
                    recover();
                    return;
                }

                // Backstop only: the axe keeps retrying every frame and tick (see pollStunAxe), and
                // the fall/shield/reach gates above already unwind a dead attempt.
                if (++reachWaitTicks > STUN_AXE_MAX_WAIT_TICKS) {
                    recover();
                }
            }
            case WAITING_DELAY -> {
                if (!stunAxeAttackExecuted || stunAxeExecutedTickId < 0L) {
                    recover();
                    return;
                }

                long clientTicksSinceAxe = stunClientTickId - stunAxeExecutedTickId;
                if (clientTicksSinceAxe <= 0L) {
                    return;
                }
                elapsedStunTicks = (int) Math.min(Integer.MAX_VALUE, clientTicksSinceAxe);

                long elapsedTotalMs = monoMs() - stunAxeQueuedAtMs;
                if (elapsedTotalMs > 1500L) {
                    recover();
                    return;
                }

                // Landed (or started gliding) since the axe: there is no smash left to deliver.
                if (!stunSmashStillAvailable()) {
                    noteStunRefusal("fall ended before the mace");
                    recover();
                    return;
                }

                // Legality first (a strictly later wire tick than the axe), then the engine's
                // decision on this tick head. The engine is the only authority for when the mace
                // leaves: Normal on its configured gap, Adaptive on its plan or a closing window.
                if (!isStunFollowUpDue()) {
                    return;
                }
                refreshCrosshairForActiveAim();
                boolean liveHitValid = canHitWithLegitClick(currentTarget)
                        && !elytraBlocksAction() && mc.currentScreen == null;
                long engineTicks = stunEngineTicksSinceAxe(stunSlamMode.is("Adaptive"), clientTicksSinceAxe,
                        currentWireTick() - stunAxeWireTickId);
                StunSlamEngine.Decision decision = decideStunFollowUp((int) Math.max(0L, Math.min(engineTicks,
                        StunSlamEngine.MAX_FOLLOW_UP_TICKS + 1L)), liveHitValid);
                switch (decision.verdict()) {
                    case FIRE -> {
                        stunSlamState = StunSlamState.MACE_SMASH;
                        tickStunSlamCombo();
                    }
                    case ABANDON -> {
                        noteStunRefusal("engine abandoned: " + decision.why());
                        recover();
                    }
                    case WAIT -> { }
                }
            }
            case MACE_SMASH -> {
                long elapsedTotalMs = monoMs() - stunAxeQueuedAtMs;
                if (elapsedTotalMs > 1500L) {
                    recover();
                    return;
                }

                int maceSlot = findBestMace();
                if (maceSlot < 0) {
                    recover();
                    return;
                }
                if (!stunSmashStillAvailable()) {
                    noteStunRefusal("fall ended before the mace");
                    recover();
                    return;
                }

                refreshCrosshairForActiveAim();
                if (doMaceSmash(maceSlot)) {
                    reachWaitTicks = 0;
                    stunSlamState = StunSlamState.SWAP_BACK;
                    stunSwapBackHoldTicks = sampleSwapBackHoldTicks();
                    armBounceWait(400L);
                } else if (++reachWaitTicks > STUN_MACE_RETRY_TICKS) {
                    // Counted once per tick head (frames do not count), so this is a tick bound.
                    noteStunRefusal("mace refused for " + STUN_MACE_RETRY_TICKS + " ticks after the decision");
                    recover();
                }
            }
            case SWAP_BACK -> {
                if (--stunSwapBackHoldTicks > 0) {
                    return;
                }
                swapBackToWeapon();
                restoreOwnShieldIfNeeded();

                stunSlamState = StunSlamState.IDLE;
                smashStage = 0;
                elapsedStunTicks = 0;
                reachWaitTicks = 0;
                burstClaimed = false;
            }
            default -> recover();
        }
    }

    /** Selects a slot through the normal keybinding path and immediately synchronizes it. */
    private boolean selectSlotLegit(int slot) {
        if (mc.player == null || mc.options == null || mc.options.hotbarKeys == null
                || slot < 0 || slot > 8) {
            return false;
        }
        if (mc.player.getInventory().getSelectedSlot() == slot) {
            if (mc.interactionManager instanceof ClientPlayerInteractionManagerMixin.ClientPlayerInteractionManagerAccessor acc) {
                acc.invokeSyncSelectedSlot();
            }
            moduleSelectedSlot = slot;
            return true;
        }
        if (!TickActionOrder.canSwitchSlot()) {
            return false;
        }
        TickActionOrder.cancelDeferredSlot();
        SlotSync.selectNow(mc, slot);
        boolean selected = mc.player.getInventory().getSelectedSlot() == slot;
        if (selected) {
            moduleSelectedSlot = slot;
        }
        return selected;
    }

    /** Convert a millisecond constant to a deterministic tick count by TRUNCATION (floor), so a
     *  configured value never silently rounds UP into an extra tick of dead time (e.g. 125ms -> 2t
     *  = 100ms, not Math.round(2.5) = 3t = 150ms). Deterministic int math; the watchdogs are absolute
     *  counters and are unaffected. */
    private int msToTicks(double ms) {
        return Math.max(0, (int) (ms / MS_PER_TICK));
    }

    /**
     * Tick heads the weapon stays selected after a hit before the restore: the Swap Back Delay as
     * ticks, never below 1 (the restore cannot share the hit's packet interval).
     */
    private int sampleSwapBackHoldTicks() {
        return Math.max(1, ModuleDelays.msToTicks(swapBackDelay.sample()));
    }

    // Swap Back Delay after the hit (one tick at the least): swap back to the slot we were on,
    // keeping the look on the target through the swap so the whole thing reads cleanly.
    private void tickSmashBurst() {
        if (currentTarget != null && currentTarget.isAlive() && mc.player != null
                && mc.player.distanceTo(currentTarget) <= getAimEngagementDistance(currentTarget)) {
            applySmashAim();
        }

        // The direct mace slot has been prepared, but its delayed render-frame attack has not
        // fired yet. Keep ownership of the transaction without swapping back early.
        if (smashStage == 3) {
            // A prepared hit that did not leave in the interval of its selection is undone here.
            pollPreparedDirectMaceHit();
            return;
        }

        // The attack is dispatched synchronously; the restore waits the Swap Back Delay in ticks.
        if (smashStage == 2 || smashStage == 1) {
            if (--smashSwapBackHoldTicks > 0) {
                return;
            }
            swapBackToWeapon();
            smashStage = 0;
            burstClaimed = false;
            restoreOwnShieldIfNeeded();
        }
    }

    private boolean isHoldingWeapon() {
        if (mc.player == null)
            return false;
        ItemStack stack = mc.player.getMainHandStack();
        return CombatItemUtil.isWeapon(stack);
    }

    private boolean isTargetBlocking(PlayerEntity target) {
        if (target == null || mc.player == null) {
            return false;
        }

        // Vanilla-correct blocking check: returns true ONLY if the shield has been raised for >= 5 ticks.
        // If the target is in the 5-tick warmup, their shield cannot block anyway, so an axe hit will
        // not disable the shield.
        if (!target.isBlocking()) {
            return false;
        }

        // Vanilla-correct facing (matches LivingEntity#getDamageBlockedAmount): head-yaw view vector
        // from eye positions, front hemisphere only.
        Vec3d toAttacker = mc.player.getEyePos().subtract(target.getEyePos());
        double lenSq = toAttacker.x * toAttacker.x + toAttacker.z * toAttacker.z;
        if (lenSq < 1.0e-6) return true;
        double yawRad = Math.toRadians(target.getHeadYaw());
        double facingX = -Math.sin(yawRad);
        double facingZ = Math.cos(yawRad);
        double dot = (facingX * toAttacker.x + facingZ * toAttacker.z) / Math.sqrt(lenSq);
        return dot > 0.0;
    }

    /** Best-effort current fall distance (tracked + vanilla), matching this module's other fall reads. */
    private double currentFallDistance() {
        if (mc.player == null) return 0.0;
        if (mc.player.isOnGround() || mc.player.isClimbing() || isInLiquidOrWeb()
                || mc.player.isTouchingWater() || mc.player.hasVehicle()) {
            return 0.0;
        }
        double trackedFall = Math.max(0.0, highestY - mc.player.getY());
        double vanillaFall = Math.max(0.0, mc.player.fallDistance);
        return Math.max(trackedFall, vanillaFall);
    }

    /**
     * Picks the hotbar mace to smash with. Density mode: Wind Burst first, then the highest Density,
     * Breach as the tiebreak. Best Damage mode: the mace with the highest estimated smash damage
     * against the current target for the current fall (Wind Burst, then Density, break ties).
     */
    private int findBestMace() {
        if (mc.player == null) {
            return -1;
        }
        PressureStep pressure = activePressureStep();
        if (pressure != PressureStep.NONE) {
            int forced = findMaceByEnchant(pressure == PressureStep.BREACH ? "breach" : "density");
            if (forced != -1) {
                return forced;
            }
        }
        boolean bestDamage = maceType.is("Best Damage");
        double fall = currentFallDistance();

        // Read several times per frame by the fall gates and by other modules (ownsAttackWindow); the
        // ranking is a pure function of the hotbar stacks, the fall, the target and the mode, so it is
        // reused while all of them are unchanged within one tick.
        if (bestMaceTick == stunClientTickId && bestMaceTarget == currentTarget && bestMaceFall == fall
                && bestMacePressure == pressure && bestMaceDamageMode == bestDamage && hotbarUnchanged()) {
            return bestMaceSlot;
        }
        int slot = rankMaces(bestDamage, fall);
        bestMaceTick = stunClientTickId;
        bestMaceTarget = currentTarget;
        bestMaceFall = fall;
        bestMacePressure = pressure;
        bestMaceDamageMode = bestDamage;
        for (int i = 0; i < 9; i++) {
            bestMaceHotbar[i] = mc.player.getInventory().getStack(i);
            bestMaceEmpty[i] = bestMaceHotbar[i].isEmpty();
        }
        bestMaceSlot = slot;
        return slot;
    }

    /** Same stack objects (and counts) in every hotbar slot as when the mace ranking was cached. */
    private boolean hotbarUnchanged() {
        for (int i = 0; i < 9; i++) {
            ItemStack now = mc.player.getInventory().getStack(i);
            if (now != bestMaceHotbar[i] || now.isEmpty() != bestMaceEmpty[i]) {
                return false;
            }
        }
        return true;
    }

    private int rankMaces(boolean bestDamage, double fall) {
        int bestSlot = -1;
        ItemStack bestStack = null;
        double bestDamageValue = -1.0;
        for (int i = 0; i < 9; i++) {
            ItemStack stack = mc.player.getInventory().getStack(i);
            if (!(stack.getItem() instanceof MaceItem)) {
                continue;
            }
            double damage = bestDamage ? estimateSmashDamage(stack, fall, currentTarget) : 0.0;
            boolean better;
            if (bestStack == null) {
                better = true;
            } else if (bestDamage && Math.abs(damage - bestDamageValue) > 1.0E-6) {
                better = damage > bestDamageValue;
            } else {
                better = isBetterMaceByEnchant(stack, bestStack);
            }
            if (better) {
                bestStack = stack;
                bestSlot = i;
                bestDamageValue = damage;
            }
        }
        return bestSlot;
    }

    /**
     * The hotbar mace with the highest level of {@code enchant} (Wind Burst, then Density, then Breach
     * break ties), or -1 when no mace has it.
     */
    private int findMaceByEnchant(String enchant) {
        int bestSlot = -1;
        int bestLevel = 0;
        ItemStack bestStack = null;
        for (int i = 0; i < 9; i++) {
            ItemStack stack = mc.player.getInventory().getStack(i);
            if (!(stack.getItem() instanceof MaceItem)) {
                continue;
            }
            int level = getEnchantLevel(stack, enchant);
            if (level <= 0) {
                continue;
            }
            if (bestStack == null || level > bestLevel
                    || (level == bestLevel && isBetterMaceByEnchant(stack, bestStack))) {
                bestSlot = i;
                bestLevel = level;
                bestStack = stack;
            }
        }
        return bestSlot;
    }

    /** Wind Burst, then Density, then Breach - used by Density mode and as the damage tiebreak. */
    private boolean isBetterMaceByEnchant(ItemStack candidate, ItemStack currentBest) {
        int wbCand = getEnchantLevel(candidate, "wind_burst");
        int wbBest = getEnchantLevel(currentBest, "wind_burst");
        if (wbCand != wbBest) {
            return wbCand > wbBest;
        }
        int densCand = getEnchantLevel(candidate, "density");
        int densBest = getEnchantLevel(currentBest, "density");
        if (densCand != densBest) {
            return densCand > densBest;
        }
        return getEnchantLevel(candidate, "breach") > getEnchantLevel(currentBest, "breach");
    }

    /**
     * Vanilla smash damage estimate for {@code mace} after {@code fall} blocks against {@code target}.
     * Base 6, plus the fall bonus (4/block to 3, 2/block to 8, then 1/block), plus Density (0.5 per
     * level per block fallen); the target's armor is then reduced by Breach (15% per level) and the
     * vanilla armor/toughness formula is applied. Armor enchantments and effects are not modelled,
     * so this ranks maces rather than predicting exact HP. Without a target, armor counts as 0.
     */
    private double estimateSmashDamage(ItemStack mace, double fall, LivingEntity target) {
        double bonus = 0.0;
        if (fall >= HARD_CODED_MIN_DROP) {
            bonus = Math.min(fall, 3.0) * 4.0
                    + Math.min(Math.max(fall - 3.0, 0.0), 5.0) * 2.0
                    + Math.max(fall - 8.0, 0.0);
            bonus += getEnchantLevel(mace, "density") * 0.5 * fall;
        }
        double raw = 6.0 + bonus;
        if (target == null) {
            return raw;
        }
        double armor = target.getArmor();
        double toughness = target.getAttributeValue(net.minecraft.entity.attribute.EntityAttributes.ARMOR_TOUGHNESS);
        armor *= Math.max(0.0, 1.0 - 0.15 * getEnchantLevel(mace, "breach"));
        double divisor = 2.0 + toughness / 4.0;
        double effective = Math.min(20.0, Math.max(armor - raw / divisor, armor * 0.2));
        return raw * (1.0 - effective / 25.0);
    }

    private int getEnchantLevel(ItemStack stack, String enchantPathPart) {
        if (stack.isEmpty())
            return 0;
        ItemEnchantmentsComponent enchantments = stack.get(DataComponentTypes.ENCHANTMENTS);
        if (enchantments == null)
            return 0;
        for (RegistryEntry<Enchantment> entry : enchantments.getEnchantments()) {
            if (entry.getKey().isPresent()) {
                String id = entry.getKey().get().getValue().getPath();
                if (id.contains(enchantPathPart)) {
                    return enchantments.getLevel(entry);
                }
            }
        }
        return 0;
    }

    private int findAxe() {
        for (int i = 0; i < 9; i++) {
            if (mc.player.getInventory().getStack(i).getItem() instanceof AxeItem)
                return i;
        }
        return -1;
    }

    /** True while an elytra is worn in the chest slot. Matches AutoElytra's own check. */
    private boolean isWearingElytra() {
        return mc.player != null && mc.player.getEquippedStack(EquipmentSlot.CHEST).isOf(Items.ELYTRA);
    }

    /**
     * While the elytra is on your chest, aiming and swinging are held back. The wing is worn during
     * the approach and AutoElytra sheds it right before the hit; swinging mid-glide would waste the
     * swing on a hit that cannot smash (a smash needs a real fall, not a glide). The rest of the tick
     * still runs — in particular the AutoElytra drop request — so the wing can come off and the dive
     * can proceed.
     */
    private boolean elytraBlocksAction() {
        return isWearingElytra();
    }

    /**
     * Natural end of an engagement (target gone, fall finished, sequence done). Releases the aim
     * through {@link RotationManager#stop(Object)} so a silent rotation decays back to the camera
     * instead of snapping in a single tick, and so a post-attack hold is honoured.
     */
    void stopAiming() {
        if (stunSlamState != StunSlamState.IDLE) {
            // The slam owns its target, aim and hotbar until it finishes or unwinds; an idle-path
            // "nothing to do here" must not swap the axe back or drop the target between the two hits.
            return;
        }
        releaseAim();
        clearSequenceState();
    }

    /**
     * Releases this module's rotation request through {@link RotationManager#stop(Object)}. Only done
     * once per engagement: stop() restarts the silent decay on every call, and this is reached every
     * render frame while idle.
     */
    private void releaseAim() {
        aimSmoother.reset();
        smootherLastNanos = Long.MIN_VALUE;
        if (aimRequested) {
            aimRequested = false;
            RotationManager.clearTarget(this);
        }
    }

    /** Immediate release for pauses and faults: drops the rotation channel with no decay or hold. */
    private void abortAiming() {
        if (stunSlamState != StunSlamState.IDLE) {
            // A pause (pearl macro, dtap, exclusive lock) mid-slam: unwind through the one owner-scoped
            // path, which also restores the hotbar, instead of leaving a half-cleared state machine.
            recover();
            return;
        }
        aimRequested = false;
        RotationManager.abortOwner(this);
        clearSequenceState();
    }

    private void clearSequenceState() {
        if (stunSlamState != StunSlamState.IDLE) {
            return;
        }
        lastAimTarget = null;
        clearStrikePlan();
        initialDelayTarget = null;
        swapBackToWeapon();
        currentTarget = null;
        shouldDirectSmashArmed = false;
        burstClaimed = false;
        smashStage = 0;
    }

    /**
     * Reach as the server measures it: from the eye this client last sent to the target box as last
     * received (Rule 6, current positions, never interpolated or history-replayed). Render
     * interpolation trails both by up to a tick, which in a dive both refused hits that were in reach
     * and allowed clicks the server would measure as out of it.
     */
    private boolean isWithinLegitReach(Entity target, double range) {
        if (mc.player == null || target == null) {
            return false;
        }
        Vec3d eyePos = mc.player.getEyePos();
        Box box = target.getBoundingBox();
        double clampedX = MathHelper.clamp(eyePos.x, box.minX, box.maxX);
        double clampedY = MathHelper.clamp(eyePos.y, box.minY, box.maxY);
        double clampedZ = MathHelper.clamp(eyePos.z, box.minZ, box.maxZ);
        double maxRange = Math.max(0.0, range) + REACH_EPSILON;
        return eyePos.squaredDistanceTo(clampedX, clampedY, clampedZ) <= (maxRange * maxRange);
    }

    private boolean isSilentMode() {
        return silentRotations.isEnabled() && !aimDisabled();
    }

    /**
     * Aiming gate: Built-in aiming gate that is on at all times.
     * Aiming ONLY applies if the opponent is anywhere below us, or, if above us,
     * less than 3.5 blocks above us AND immediately hittable via line of sight.
     */
    private boolean isAimTargetValid(PlayerEntity target) {
        if (mc.player == null || target == null || !target.isAlive()) {
            return false;
        }

        double heightDiff = target.getY() - mc.player.getY();
        // Opponent is >= 3.5 blocks above us -> disallow aiming.
        if (heightDiff > 3.5) {
            return false;
        }

        return canSeeCached(target);
    }

    /**
     * {@code canSee} is a block raycast, and this gate runs several times per frame per candidate. Its
     * answer depends only on the two eye positions (and the blocks, refreshed every tick), so it is reused
     * while both stay where they were in this tick.
     */
    private boolean canSeeCached(PlayerEntity target) {
        Vec3d me = mc.player.getEyePos();
        double tx = target.getX();
        double ty = target.getEyeY();
        double tz = target.getZ();
        double[] e = losCache.get(target.getId());
        if (e != null && e[0] == stunClientTickId && e[1] == me.x && e[2] == me.y && e[3] == me.z
                && e[4] == tx && e[5] == ty && e[6] == tz) {
            return e[7] != 0.0;
        }
        boolean visible = mc.player.canSee(target);
        if (e == null) {
            if (losCache.size() > 64) {
                losCache.clear();
            }
            e = new double[8];
            losCache.put(target.getId(), e);
        }
        e[0] = stunClientTickId;
        e[1] = me.x;
        e[2] = me.y;
        e[3] = me.z;
        e[4] = tx;
        e[5] = ty;
        e[6] = tz;
        e[7] = visible ? 1.0 : 0.0;
        return visible;
    }

    /**
     * Manual mouse dampening applies on the way into a smash: valid target, fallen past the smash
     * floor, inside {@link #OPTIMIZER_RANGE}. It never applies to Silent Aim, where the camera is not
     * being steered and the player keeps full control of the view.
     */
    public boolean isAimOptimizerActive() {
        if (!isEnabled() || isSilentMode() || aimDisabled()) {
            return false;
        }
        if (mc.player == null || mc.world == null) {
            return false;
        }
        if (currentTarget == null || !currentTarget.isAlive() || currentTarget.isSpectator()) {
            return false;
        }
        if (isExternallyPaused() || elytraBlocksAction() || isInLiquidOrWeb()) {
            return false;
        }
        // Vanilla smash requires > 1.5 blocks fall distance; engage only after falling >= 1.5 blocks
        if (!hasSmashFallRequirement()) {
            return false;
        }
        return mc.player.distanceTo(currentTarget) <= OPTIMIZER_RANGE;
    }

    @Override
    public float getSensitivityMultiplier() {
        if (!isAimOptimizerActive()) {
            return 1.0f;
        }
        double dist = mc.player.distanceTo(currentTarget);
        // Normalized proximity progress: 0.0 at OPTIMIZER_RANGE -> 1.0 at hit range or closer
        double progress = MathHelper.clamp((OPTIMIZER_RANGE - dist) / Math.max(0.2, OPTIMIZER_RANGE - ATTACK_RANGE), 0.0, 1.0);
        // Smooth hermite interpolation curve for natural transition
        double smooth = progress * progress * (3.0 - 2.0 * progress);
        return (float) MathHelper.lerp(smooth, 1.0, MANUAL_DAMPEN_FLOOR);
    }

    private boolean isAirborneFalling() {
        if (mc.player == null) return false;
        if (mc.player.isOnGround()) return false;
        if (mc.player.isGliding()) {
            return mc.player.getVelocity().y < -0.20;
        }
        return mc.player.getVelocity().y < -0.05;
    }

    /**
     * Axe the shield, then smash: Shield Counter = Stun Slam, or the Backstab-else-Stun-Slam fallback once
     * the backstab attempt on the current target has been given up.
     */
    private boolean isStunSlamSelected() {
        return shieldCounter.is(COUNTER_STUN)
                || (shieldCounter.is(COUNTER_BACKSTAB_ELSE_STUN) && backstabGaveUp);
    }

    /** The backstab hold applies: Backstab, or Backstab-else-Stun-Slam before its give-up. */
    private boolean isBackstabCounterActive() {
        return shieldCounter.is(COUNTER_BACKSTAB)
                || (shieldCounter.is(COUNTER_BACKSTAB_ELSE_STUN) && !backstabGaveUp);
    }

    private boolean stunSettingsVisible() {
        return shieldCounter.is(COUNTER_STUN) || shieldCounter.is(COUNTER_BACKSTAB_ELSE_STUN);
    }

    // ── Backstab, else Stun Slam ──────────────────────────────────────────────
    /** Target the current backstab attempt is for; the attempt restarts when it changes. */
    private java.util.UUID backstabAttemptTarget = null;
    private long backstabAttemptStartMs = 0L;
    private long backstabAttemptGiveUpMs = 0L;
    private boolean backstabGaveUp = false;

    /**
     * Runs once per evaluation with the locked target. While the target blocks and still covers us, the
     * backstab attempt is timed; it is given up (and the stun slam takes over) after the drawn Backstab
     * Give-Up time, or early when there is too little fall left for the dive to reach their back.
     */
    private void updateBackstabFallback(PlayerEntity target) {
        if (!shieldCounter.is(COUNTER_BACKSTAB_ELSE_STUN) || target == null || mc.player == null) {
            resetBackstabFallback();
            return;
        }
        if (stunSlamState != StunSlamState.IDLE) {
            return; // the fallback slam is in flight; keep the decision until it unwinds
        }
        boolean covering = target.isBlocking() && shieldFacingDot(target) > 0.0;
        if (!covering) {
            // Shield down, or we are already behind it: nothing to fall back from.
            resetBackstabFallback();
            return;
        }
        long now = monoMs();
        if (!target.getUuid().equals(backstabAttemptTarget)) {
            backstabAttemptTarget = target.getUuid();
            backstabAttemptStartMs = now;
            backstabAttemptGiveUpMs = Math.round(backstabGiveUp.sample());
            backstabGaveUp = false;
        }
        if (backstabGaveUp) {
            return;
        }
        // About to land: one or two ticks of fall left cannot carry us around the shield any more.
        double fallSpeed = Math.max(0.05, -mc.player.getVelocity().y);
        boolean fallEnding = groundClearanceBelow() <= fallSpeed * 2.0 + 0.5;
        if (now - backstabAttemptStartMs >= backstabAttemptGiveUpMs || fallEnding) {
            backstabGaveUp = true;
        }
    }

    private void resetBackstabFallback() {
        backstabAttemptTarget = null;
        backstabGaveUp = false;
    }

    // ── Fire holds: lock-on wait and pearl arrival ────────────────────────────
    private java.util.UUID lockOnTarget = null;
    private long lockOnReadyAtMs = 0L;

    /** Pearls seen last tick: entity id -> (owner uuid or null, last position). */
    private final java.util.Map<Integer, Object[]> trackedPearls = new java.util.HashMap<>();
    /** Pearls that vanished recently: [owner uuid or null, last position, vanish time ms]. */
    private final java.util.ArrayDeque<Object[]> vanishedPearls = new java.util.ArrayDeque<>();
    /** Player positions at the previous tick head, for teleport detection. */
    private final java.util.Map<java.util.UUID, Vec3d> lastPlayerPos = new java.util.HashMap<>();
    /** Enemies that pearled onto us: uuid -> time (ms) until which no smash may start on them. */
    private final java.util.Map<java.util.UUID, Long> pearlArrivalHoldUntil = new java.util.HashMap<>();
    /** Per-tick scratch collections for {@link #trackPearlArrivals}, reused instead of reallocated. */
    private final java.util.Set<Integer> pearlSeenScratch = new java.util.HashSet<>();
    private final java.util.Map<java.util.UUID, Vec3d> playerPosScratch = new java.util.HashMap<>();
    private static final double PEARL_ARRIVAL_RADIUS_XZ = 1.5;
    private static final double PEARL_ARRIVAL_MAX_DY = 3.0;
    private static final double PEARL_TELEPORT_MIN_JUMP = 2.0;
    private static final double PEARL_MATCH_RADIUS = 3.0;
    private static final long PEARL_VANISH_MATCH_MS = 250L;

    /**
     * Tick head: an enemy whose position jumps in one tick to where one of their ender pearls just
     * vanished, landing within {@link #PEARL_ARRIVAL_RADIUS_XZ} of us horizontally, has pearled onto us.
     * A pearl whose owner the client cannot resolve still counts when it vanished at the landing point.
     */
    private void trackPearlArrivals() {
        if (mc.player == null || mc.world == null) {
            trackedPearls.clear();
            vanishedPearls.clear();
            lastPlayerPos.clear();
            pearlArrivalHoldUntil.clear();
            return;
        }
        long now = monoMs();
        java.util.Set<Integer> seen = pearlSeenScratch;
        seen.clear();
        for (Entity e : mc.world.getEntities()) {
            if (e instanceof net.minecraft.entity.projectile.thrown.EnderPearlEntity pearl) {
                Entity owner = pearl.getOwner();
                trackedPearls.put(pearl.getId(), new Object[]{owner != null ? owner.getUuid() : null, pearl.getEntityPos()});
                seen.add(pearl.getId());
            }
        }
        java.util.Iterator<java.util.Map.Entry<Integer, Object[]>> it = trackedPearls.entrySet().iterator();
        while (it.hasNext()) {
            java.util.Map.Entry<Integer, Object[]> entry = it.next();
            if (!seen.contains(entry.getKey())) {
                Object[] v = entry.getValue();
                vanishedPearls.addLast(new Object[]{v[0], v[1], now});
                it.remove();
            }
        }
        while (!vanishedPearls.isEmpty() && now - (long) vanishedPearls.peekFirst()[2] > PEARL_VANISH_MATCH_MS) {
            vanishedPearls.pollFirst();
        }
        pearlArrivalHoldUntil.values().removeIf(until -> until < now);

        Vec3d me = mc.player.getEntityPos();
        java.util.Map<java.util.UUID, Vec3d> current = playerPosScratch;
        current.clear();
        for (PlayerEntity p : mc.world.getPlayers()) {
            if (p == mc.player) continue;
            Vec3d pos = p.getEntityPos();
            current.put(p.getUuid(), pos);
            Vec3d before = lastPlayerPos.get(p.getUuid());
            if (before == null || before.distanceTo(pos) < PEARL_TELEPORT_MIN_JUMP) continue;
            double dxz = Math.hypot(pos.x - me.x, pos.z - me.z);
            if (dxz > PEARL_ARRIVAL_RADIUS_XZ || Math.abs(pos.y - me.y) > PEARL_ARRIVAL_MAX_DY) continue;
            boolean byPearl = false;
            for (Object[] v : vanishedPearls) {
                Object ownerId = v[0];
                if ((ownerId == null || ownerId.equals(p.getUuid())) && ((Vec3d) v[1]).distanceTo(pos) <= PEARL_MATCH_RADIUS) {
                    byPearl = true;
                    break;
                }
            }
            if (byPearl && waitOnPearlArrival.isEnabled()) {
                pearlArrivalHoldUntil.put(p.getUuid(), now + Math.round(pearlArrivalWait.sample()));
            }
        }
        lastPlayerPos.clear();
        lastPlayerPos.putAll(current);
    }

    /**
     * True while a new smash or stun slam on {@code target} must wait: the drawn Lock-On Wait after the
     * target was locked, or the Pearl Arrival Wait after they pearled onto us. Aim keeps tracking.
     */
    private boolean isFireHeld(PlayerEntity target) {
        if (target == null) {
            return false;
        }
        if (isPearlArrivalHeld(target)) {
            return true;
        }
        if (isManualMode()) {
            return false; // Manual Trigger fires on the player's own click; no lock-on wait applies
        }
        long now = monoMs();
        if (!target.getUuid().equals(lockOnTarget)) {
            lockOnTarget = target.getUuid();
            lockOnReadyAtMs = now + Math.round(lockOnWait.sample());
        }
        return now < lockOnReadyAtMs;
    }

    private boolean isPearlArrivalHeld(PlayerEntity target) {
        if (target == null || !waitOnPearlArrival.isEnabled()) {
            return false;
        }
        Long until = pearlArrivalHoldUntil.get(target.getUuid());
        return until != null && monoMs() < until;
    }

    // ── Stun reach: the shield knocks the attacker back ───────────────────────
    /** Server shield-block knockback on the attacker: LivingEntity#takeShieldHit -> knockback(0.5). */
    private static final double SHIELD_PUSHBACK = 0.5;
    /** Horizontal air drag per tick applied to that pushback while airborne. */
    private static final double AIR_DRAG_XZ = 0.91;
    /** The stun axe is never held back below this distance (the dive is already on top of the target). */
    private static final double STUN_AXE_MIN_REACH = 1.6;

    /**
     * Farthest the axe may be clicked from: the server answers a shield-blocked axe by knocking the
     * attacker 0.5 blocks/tick away from the victim, so an axe thrown at the edge of 3.0 leaves the mace,
     * one or more ticks later, out of reach. The reach is shortened by that pushback over the planned gap.
     */
    private double stunAxeReach() {
        int gap = stunSlamMode.is("Adaptive")
                ? Math.max(1, (int) Math.round(stunSlamMinTicks.getValue()))
                : Math.max(1, (int) Math.round(stunSlamTicks.getValue()));
        double push = 0.0;
        double v = SHIELD_PUSHBACK;
        for (int i = 0; i < gap; i++) {
            push += v;
            v *= AIR_DRAG_XZ;
        }
        return Math.max(STUN_AXE_MIN_REACH, attackRange() - push);
    }

    /**
     * Cosine of the angle between the target's head-yaw facing and the direction to us, on the
     * horizontal plane (same facing source as {@link #isTargetBlocking}); 1.0 when directly in front,
     * -1.0 when directly behind. 1.0 when the two share a column, which counts as covered.
     */
    static double shieldFacingDot(PlayerEntity target, net.minecraft.client.MinecraftClient client) {
        if (client == null || client.player == null || target == null) return 1.0;
        Vec3d toAttacker = client.player.getEyePos().subtract(target.getEyePos());
        double lenSq = toAttacker.x * toAttacker.x + toAttacker.z * toAttacker.z;
        if (lenSq < 1.0e-6) return 1.0;
        double yawRad = Math.toRadians(target.getHeadYaw());
        return (-Math.sin(yawRad) * toAttacker.x + Math.cos(yawRad) * toAttacker.z) / Math.sqrt(lenSq);
    }

    private double shieldFacingDot(PlayerEntity target) {
        return shieldFacingDot(target, mc);
    }

    /**
     * Backstab mode: true while the target's shield is up and still covers us (in front of them).
     * While true, no attack is started; the dive and aim continue until we cross to their unshielded side.
     */
    private boolean isBackstabHold(PlayerEntity target) {
        if (!isBackstabCounterActive() || target == null || mc.player == null || !target.isBlocking()) {
            return false;
        }
        return shieldFacingDot(target) > 0.0;
    }

    /**
     * True when the player's horizontal position is behind the target's head facing direction (rear hemisphere),
     * exactly matching vanilla Minecraft shield blocking mechanics.
     */
    static boolean isPlayerBehindTarget(PlayerEntity target, net.minecraft.client.MinecraftClient client) {
        if (client == null || client.player == null || target == null) return false;
        return shieldFacingDot(target, client) <= 0.0;
    }

    private boolean isPlayerBehindTarget(PlayerEntity target) {
        return isPlayerBehindTarget(target, mc);
    }

    /** Acquisition and tracking share this one turn-in window (a locked target is held 15% further out). */
    private double getAimEngagementDistance(PlayerEntity target) {
        double range = attackRange() + preAimDistance.getValue();
        return (target != null && target == lastAimTarget) ? range * 1.15 : range;
    }

    /**
     * Requests the one tracking rotation: Aim Speed ceiling, near-critical response, no lead and no filter
     * of the controller's own. The command it is given is already smooth ({@link AimSmoother}) and, for a
     * committed strike, solved to land on its head, so the controller only has to follow it faithfully.
     * RotationManager still owns stepping and GCD quantization.
     */
    private void applySmashAim() {
        if (spearAimOnlyActive()) {
            if (mc.player == null || currentTarget == null || !isAimTargetValid(currentTarget)
                    || elytraBlocksAction() || isInLiquidOrWeb()
                    || mc.player.distanceTo(currentTarget) > getAimEngagementDistance(currentTarget)) {
                releaseAim();
                return;
            }
        } else if (aimDisabled()) {
            // Hit Only and Manual Trigger never steer the view; the player's own aim decides the hit.
            releaseAim();
            return;
        } else if (isStunAimPhase()) {
            // Between the axe and the mace the aim is never released: the fall gate, the visibility
            // test and the engagement distance all describe a fresh engagement, not a committed pair.
            if (mc.player == null || currentTarget == null || !currentTarget.isAlive() || elytraBlocksAction()) {
                releaseAim();
                return;
            }
        } else {
            if (mc.player == null || currentTarget == null || !isAimTargetValid(currentTarget)
                    || elytraBlocksAction() || !hasSmashFallRequirement()
                    || mc.player.distanceTo(currentTarget) > getAimEngagementDistance(currentTarget)) {
                // Soft release: right after a hit the fall requirement lapses, and a hard abort here
                // would wipe the post-attack hold that keeps a silent rotation visible to the server.
                releaseAim();
                return;
            }
        }

        lastAimTarget = currentTarget;
        boolean silent = isSilentMode();
        double speed = RotationManager.engineStrength(aimSpeed.getValue(), RotationManager.STRENGTH_SCALE_TRACK) * 60.0;
        if (silent) {
            speed *= 1.5;
        }
        // Intave's RotationModuloResetHeuristic flags a yaw jump over 100 degrees in one tick onto an
        // acquired target; the controller's speed jitter adds up to 10%, so the ceiling stays under it.
        speed = Math.min(speed, MAX_TRACK_DEG_PER_SEC);
        RotationRequest req = RotationRequest.create()
                .speed(speed)
                .response(TRACK_RESPONSE)
                .prediction(0.0)
                .targetSmoothing(false)
                .silent(silent)
                .yawOnly(false);
        aimRequested = true;
        RotationManager.setRotationSupplier(this, RotationManager.Priority.HIGHEST, aimSupplier, req);
    }

    /** One supplier for every request (applySmashAim runs several times per frame). */
    private final Supplier<Vec3d> aimSupplier = () -> currentTarget != null ? getAimPos(currentTarget) : null;

    /**
     * Keeps the reached rotation (silent or camera) across the tick that carries the attack: the
     * swing happens on a render frame, so the angle must outlive it until the next movement packet.
     * RotationManager ignores the hold unless this module owns the active request.
     */
    private void holdSilentAimAfterAttack() {
        RotationManager.setPostAttackHold(this, 1, RotationManager.getFinalYaw(), RotationManager.getFinalPitch());
    }

    private boolean isCrosshairOnTarget(Entity target) {
        if (target == null || mc.player == null) return false;
        if (isSilentMode()) {
            if (RotationManager.isSilentRotationActive()) {
                net.minecraft.util.hit.HitResult hit = RotationManager.getCrosshairTarget(
                        RotationManager.getFinalYaw(), RotationManager.getFinalPitch(), attackRange());
                return hit instanceof net.minecraft.util.hit.EntityHitResult ehr && ehr.getEntity() == target;
            }
            return isPhysicalCrosshairOnTarget(target);
        }
        return isPhysicalCrosshairOnTarget(target);
    }

    private boolean isPhysicalCrosshairOnTarget(Entity target) {
        if (target == null || mc.player == null) return false;
        if (isSpearReachActive(target)) {
            // The spear reach applies (held spear, or carried through the axe and mace swaps). Vanilla's
            // pick only reaches as far as the client's interaction-range attribute, which trails the
            // spear selection by a tick (the server applies the modifier and sends it back), so the
            // live ray at the sequence reach decides, the same ray the click then resolves against.
            return spearRay() instanceof EntityHitResult ray && ray.getEntity() == target;
        }
        if (mc.gameRenderer != null) {
            mc.gameRenderer.updateCrosshairTarget(1.0f);
        }
        // Vanilla's own ray, refreshed from the current authoritative angles just above, decides
        // eligibility. The crosshair target is never rewritten to make a click possible.
        return mc.crosshairTarget instanceof EntityHitResult ehr && ehr.getEntity() == target;
    }

    /**
     * Re-run vanilla's own crosshair raycast after an instant lock. This ensures
     * that mc.crosshairTarget reflects the freshly aimed view direction immediately.
     */
    private void refreshCrosshairForActiveAim() {
        if (mc.gameRenderer == null || mc.player == null || mc.world == null) {
            return;
        }
        if (isSpearRayCarried()) {
            return;
        }
        mc.gameRenderer.updateCrosshairTarget(1.0f);
    }

    /**
     * Ground-truth gate for every smash/axe click: the target is inside vanilla reach and the aim
     * (physical camera, or the server-side angles under Silent Aim) is on it.
     */
    private boolean canHitWithLegitClick(Entity target) {
        if (target == null || mc.player == null) {
            return false;
        }
        return isWithinLegitReach(target, attackRange()) && isCrosshairOnTarget(target);
    }

    /**
     * The click is only worth sending when the server can also accept it. Two server-side facts differ
     * from what the client shows: the smash needs a vanilla fall distance past 1.5 (the tracked fall can
     * be larger after a glide), and the target is held by the server about one round trip ahead of the
     * box drawn here, so the reach test is repeated against that forecast box.
     */
    /** True from the axe selection until the slam unwinds: the aim uses the core point, not the edge. */
    private boolean isStunAimPhase() {
        return stunSlamState != StunSlamState.IDLE && stunSlamState != StunSlamState.SWAP_BACK;
    }

    private boolean isStunFollowUpPhase() {
        return stunSlamState == StunSlamState.WAITING_DELAY || stunSlamState == StunSlamState.MACE_SMASH
                || stunSlamState == StunSlamState.SWAP_BACK;
    }

    private boolean isRegistrationReady(Entity target) {
        return true;
    }

    /**
     * Accurately calculates the distance from the bottom of the player's bounding box
     * to the nearest collision surface below across 5 sample points (center + 4 corners).
     */
    private double groundClearanceBelow() {
        if (mc.player == null || mc.world == null) {
            return 0.0;
        }
        if (mc.player.isOnGround()) {
            return 0.0;
        }

        // Five 48-block raycasts: reused for the same position and fall speed within one tick.
        double vyNow = mc.player.getVelocity().y;
        if (clearanceCacheWorld == mc.world && clearanceCacheTick == stunClientTickId
                && clearanceCacheX == mc.player.getX() && clearanceCacheY == mc.player.getY()
                && clearanceCacheZ == mc.player.getZ() && clearanceCacheVy == vyNow) {
            return clearanceCacheValue;
        }
        clearanceCacheWorld = mc.world;
        clearanceCacheTick = stunClientTickId;
        clearanceCacheX = mc.player.getX();
        clearanceCacheY = mc.player.getY();
        clearanceCacheZ = mc.player.getZ();
        clearanceCacheVy = vyNow;
        clearanceCacheValue = scanGroundClearance();
        return clearanceCacheValue;
    }

    private double scanGroundClearance() {
        Box box = mc.player.getBoundingBox();
        double minY = box.minY;

        double minClearance = Double.MAX_VALUE;
        double px = mc.player.getX();
        double pz = mc.player.getZ();

        double[] offsetsX = {0.0, -0.28, 0.28, -0.28, 0.28};
        double[] offsetsZ = {0.0, -0.28, -0.28, 0.28, 0.28};

        for (int i = 0; i < offsetsX.length; i++) {
            Vec3d start = new Vec3d(px + offsetsX[i], minY + 0.05, pz + offsetsZ[i]);
            Vec3d end = start.add(0.0, -48.0, 0.0);
            HitResult hit = mc.world.raycast(new RaycastContext(
                    start, end, RaycastContext.ShapeType.COLLIDER, RaycastContext.FluidHandling.NONE, mc.player));
            if (hit.getType() == HitResult.Type.BLOCK) {
                double c = Math.max(0.0, start.y - 0.05 - hit.getPos().y);
                if (c < minClearance) {
                    minClearance = c;
                }
            }
        }

        // Also check if any block collision intersects the player within the upcoming tick
        double vy = mc.player.getVelocity().y;
        if (vy < 0.0) {
            Box nextBox = box.offset(0.0, vy, 0.0);
            if (!mc.world.isSpaceEmpty(mc.player, nextBox)) {
                return Math.min(minClearance, Math.abs(vy));
            }
        }

        return minClearance;
    }

    /**
     * Highest-value player inside the pre-aim window: low HP, popped totem and raised shield score
     * higher, with distance as the lowest-tier tiebreak. A victim already in range is kept so two
     * equidistant enemies cannot flip the choice frame-to-frame.
     */
    /** The valid player under the live crosshair ray, or null. Used by Hit Only in place of acquisition. */
    private PlayerEntity crosshairPlayerTarget() {
        refreshCrosshairForActiveAim();
        if (mc.crosshairTarget instanceof EntityHitResult hit && hit.getEntity() instanceof PlayerEntity victim
                && victim != mc.player && isTargetable(victim) && isAimTargetValid(victim)) {
            return victim;
        }
        return null;
    }

    private PlayerEntity findTarget() {
        if (mc.world == null || mc.player == null)
            return null;

        // Hit Only never acquires or tracks: the target is whoever the player's own crosshair is on.
        if (isHitOnlyMode()) {
            return crosshairPlayerTarget();
        }

        double range = attackRange() + preAimDistance.getValue();

        if (lastHit.isEnabled()) {
            PlayerEntity locked = resolveLastHitLock();
            if (locked != null) {
                // Hold the locked player while inside range; never fall through to someone else.
                return isAimTargetValid(locked) && mc.player.squaredDistanceTo(locked) <= range * range
                        ? locked : null;
            }
        } else {
            lockedTargetId = null;
        }

        // In-range stickiness: keep the current victim while it stays targetable and inside the
        // engage range. That also stops the divebomb drop being re-issued against alternating victims.
        double holdRange = currentTarget != null ? getAimEngagementDistance(currentTarget) : range;
        if (currentTarget != null && isTargetable(currentTarget)
                && isAimTargetValid(currentTarget)
                && mc.player.squaredDistanceTo(currentTarget) <= holdRange * holdRange) {
            return currentTarget;
        }

        PlayerEntity bestTarget = null;
        double bestScore = -Double.MAX_VALUE;
        for (PlayerEntity entity : mc.world.getPlayers()) {
            // Cheap distance first: the targetable and line-of-sight gates (hook scan, raycast) only run
            // for players that could be chosen at all, not for every player in the world each frame.
            if (entity == mc.player || mc.player.squaredDistanceTo(entity) > range * range)
                continue;
            if (!isTargetable(entity) || !isAimTargetValid(entity))
                continue;
            double score = interstellarTikiTiks.apexx.client.util.combat.TargetPriority.score(entity, mc.player, range);
            if (score > bestScore) {
                bestScore = score;
                bestTarget = entity;
            }
        }
        if (lastHit.isEnabled() && bestTarget != null) {
            lockedTargetId = bestTarget.getUuid(); // nothing locked yet: the candidate seeds the lock
        }
        return bestTarget;
    }

    /**
     * Last Hit lock: a fresh hit the user made on another valid player re-targets the lock, a
     * still-valid lock is kept, and a dead/removed/other-world lock is dropped. Null = nothing held.
     */
    private PlayerEntity resolveLastHitLock() {
        if (mc.world == null || mc.player == null) {
            return null;
        }
        if (lastUserHitId != null && monoMs() - lastUserHitMs <= LAST_HIT_MEMORY_MS) {
            PlayerEntity hit = lookupPlayer(lastUserHitId);
            if (hit != null && isTargetable(hit)) {
                lockedTargetId = lastUserHitId;
            }
        }
        if (lockedTargetId == null) {
            return null;
        }
        PlayerEntity locked = lookupPlayer(lockedTargetId);
        if (locked == null || !isTargetable(locked)) {
            if (lockedTargetId.equals(lastUserHitId)) {
                lastUserHitId = null;
            }
            lockedTargetId = null;
            return null;
        }
        return locked;
    }

    private PlayerEntity lookupPlayer(java.util.UUID id) {
        for (PlayerEntity p : mc.world.getPlayers()) {
            if (p != mc.player && p.getUuid().equals(id)) {
                return p;
            }
        }
        return null;
    }

    /** Records only the user's own hits for Last Hit; this module's and other modules' attacks are ignored. */
    @Override
    public void onAttack(Entity target) {
        if (reentrantAttack || target == null || mc.player == null || target == mc.player
                || interstellarTikiTiks.apexx.client.util.ModuleExecutionContext.currentOwner() != null
                || !(target instanceof PlayerEntity player)) {
            return;
        }
        lastUserHitId = player.getUuid();
        lastUserHitMs = monoMs();
    }

    /**
     * A player we may target: alive, not a friend, and not creative/spectator. A creative/spectator
     * player produces no server hit, so locking one would starve real enemies and drive an elytra
     * drop AutoElytra disagrees with (it already filters them). The friend filter is UUID-based to
     * match AutoElytra so the two modules never disagree.
     */
    private boolean isTargetable(PlayerEntity entity) {
        if (entity == null || !entity.isAlive() || entity.isSpectator() || entity.isCreative())
            return false;
        if (FriendManager.getInstance().isFriend(entity.getUuid())) {
            return false;
        }
        // The enabled bot filters are a scan over every module: resolved once per tick, not per player per frame.
        if (botHooksTick != stunClientTickId) {
            botHooks = ModuleAccess.getEnabledHooks(
                    interstellarTikiTiks.apexx.client.module.implementation.player.BotFilterProvider.class);
            botHooksTick = stunClientTickId;
        }
        for (interstellarTikiTiks.apexx.client.module.implementation.player.BotFilterProvider botFilter : botHooks) {
            if (botFilter.isBot(entity)) {
                return false;
            }
        }
        return true;
    }
}
