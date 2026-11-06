package interstellarTikiTiks.apexx.client.module.implementation.combat;

import interstellarTikiTiks.apexx.client.event.EventBus;
import interstellarTikiTiks.apexx.client.event.EventTarget;
import interstellarTikiTiks.apexx.client.event.impl.CombatSwapLockEvent;
import interstellarTikiTiks.apexx.client.mixin.MinecraftClientAccessor;
import interstellarTikiTiks.apexx.client.mixin.KeyBindingAccessor;
import interstellarTikiTiks.apexx.client.mixin.PlayerInventoryAccessor;
import interstellarTikiTiks.apexx.client.module.BooleanSetting;
import interstellarTikiTiks.apexx.client.module.Category;
import interstellarTikiTiks.apexx.client.module.SubCategory;
import interstellarTikiTiks.apexx.client.module.ModeSetting;
import interstellarTikiTiks.apexx.client.module.Module;
import interstellarTikiTiks.apexx.client.module.SliderSetting;
import interstellarTikiTiks.apexx.client.module.hooks.AttackHandleAware;
import interstellarTikiTiks.apexx.client.module.hooks.ShieldBreakConfirmationHandler;
import interstellarTikiTiks.apexx.client.util.AttackValidator;
import interstellarTikiTiks.apexx.client.util.ModuleAccess;
import interstellarTikiTiks.apexx.client.util.ModuleExecutionContext;
import interstellarTikiTiks.apexx.client.util.RotationManager;
import interstellarTikiTiks.apexx.client.util.SystemInputSimulator;
import interstellarTikiTiks.apexx.client.util.combat.BlockPlacementValidator;
import interstellarTikiTiks.apexx.client.util.combat.CombatItemUtil;
import interstellarTikiTiks.apexx.client.util.combat.InteractSim;
import interstellarTikiTiks.apexx.client.util.combat.MeleeMechanics;
import interstellarTikiTiks.apexx.client.util.combat.ModuleDelays;
import interstellarTikiTiks.apexx.client.util.combat.SlotSync;
import interstellarTikiTiks.apexx.client.util.combat.SubTickSequencer;
import interstellarTikiTiks.apexx.client.util.combat.TickActionOrder;
import interstellarTikiTiks.apexx.client.util.combat.WebHitModel;
import interstellarTikiTiks.apexx.client.util.combat.WebPlacementHelper;
import interstellarTikiTiks.apexx.client.util.combat.WebTrapPlanner;
import interstellarTikiTiks.apexx.client.util.combat.WebTrapPlanner.Cell;
import interstellarTikiTiks.apexx.client.util.combat.WebTrapPlanner.Click;
import interstellarTikiTiks.apexx.client.util.combat.WebTrapPlanner.Motion;
import interstellarTikiTiks.apexx.client.util.combat.WebTrapPlanner.Plan;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.BlocksAttacksComponent;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.fluid.FluidState;
import net.minecraft.fluid.Fluids;
import net.minecraft.item.AxeItem;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.item.ShieldItem;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.world.RaycastContext;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.hit.EntityHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.BooleanSupplier;
import java.util.function.LongPredicate;
import java.util.function.LongSupplier;

/**
 * AutoShieldBreak — switches to an axe and disables a raised shield, then swaps back to sword.
 *
 * <p>Deterministic state machine running on the client tick, inside the pre-movement action window.
 * The swap starts only when a hit sent now meets a shield the server already counts as blocking
 * (its block delay plus our round trip), with the target facing us by vanilla's 90 degree rule.
 * Aim is owner-scoped through RotationManager, with visible Legit or silent rotation selected by
 * the user. Auto Stun clicks the axe twice more after the break. At most one attack leaves per
 * movement interval ({@link TickActionOrder#canAttack()}), so each follow-up goes out in a later
 * interval once its own gap has also elapsed; one that cannot go out within 600 ms is dropped. The
 * swap back waits the Swap Back Delay and is never sent in the interval of the last action. The axe and
 * the web are only selected once the live ray reaches the target, and the hit or placement follows
 * that selection inside the same tick. The web follows the axe hit as soon as it is dispatched against a raised shield
 * and is planned from the target's server-reported positions ({@link WebTrapPlanner#planHold}: each cell is simulated with the web in
 * place, so it must hold most of the body once it catches them). Swap-back to the original weapon is enforced across frames
 * to prevent the player from getting stuck on the axe.
 */
public class AutoShieldBreak extends Module implements AttackHandleAware, ShieldBreakConfirmationHandler, interstellarTikiTiks.apexx.client.module.hooks.ClickSimulating {

    private final ModeSetting mode = new ModeSetting("Trigger", "On Swing", "On Swing", "Auto")
            .setDescription("On Swing: acts only when you swing at someone who is blocking. Auto: acts by itself on any blocker inside range.");
    private final ModeSetting weaponMode = new ModeSetting("Wielded Item", "Sword", "Sword", "Sword + Axe", "Anything")
            .setDescription("Which item you must already be holding for the swap to start: a sword, a sword or axe, or whatever is in hand.");
    private final SliderSetting breakChance = new SliderSetting("Success Rate %", 100, 0, 100, 0)
            .setDescription("Odds that an eligible blocker is actually answered with an axe swap.");
    private final SliderSetting shieldHoldTicks = new SliderSetting("Guard Age (ticks)", 1, 0, 20, 0)
            .setDescription("Ticks the enemy shield must already have been raised before the swap may begin. 0 reacts the instant it goes up.")
            .setSection("Delays");
    private final SliderSetting reactionDelay = new SliderSetting("Swap Wait (ms)", 20, 0, 500, 0).withJitter(6)
            .setDescription("Additional pause, started once the Guard Age has elapsed, before the axe is selected.")
            .setSection("Delays");
    private final SliderSetting reuseCooldown = new SliderSetting("Retry Lockout (ms)", 1500, 0, 3000, 0).withJitter(100)
            .setDescription("Dead time after an attempt during which no new axe swap is started, preventing rapid back-and-forth item flicker.")
            .setSection("Delays");
    private final SliderSetting attemptGap = new SliderSetting("Attempt Gap (ms)", 250, 0, 1000, 0).withJitter(25)
            .setDescription("Minimum time between two checks that may start a swap (also after a Success Rate roll declined).")
            .setSection("Delays");
    private final BooleanSetting switchBack = new BooleanSetting("Swap Back", true)
            .setDescription("Reselects the item you were holding as soon as the shield has been knocked down.");
    private final SliderSetting reach = new SliderSetting("Max Distance", 3.0, 1.0, 3.0, 1)
            .setDescription("Farthest a blocking enemy can be, in blocks, and still be answered (vanilla melee tops out at 3.0).");
    private final SliderSetting stunChance = new SliderSetting("Follow-Up Rate %", 100, 0, 100, 0)
            .setDescription("Odds that the knockdown is chained into two more axe clicks. Only one attack leaves per movement interval, so each follow-up goes out in a later tick once its gap has passed; one that cannot go out within 600 ms is dropped. 0 disables them. Neither waits for charge.");
    private final SliderSetting stunFirstGap = new SliderSetting("Stun First Gap (ms)", 19, 0, 60, 0).withJitter(2)
            .setDescription("Minimum wait between the shield-breaking axe hit and the first follow-up click (it never shares the break hit's tick).")
            .setDependsOn("Follow-Up Rate %").setSection("Delays");
    private final SliderSetting stunFinalGap = new SliderSetting("Stun Second Gap (ms)", 30, 0, 120, 0).withJitter(3)
            .setDescription("Minimum wait between the first and the second follow-up click (they never share a tick).")
            .setDependsOn("Follow-Up Rate %").setSection("Delays");
    private final ModeSetting webMode = new ModeSetting("Web Landing", "Off", "Off", "On", "Avoid Own Path", "Stun Lava", "Stun Web Lava")
            .setDescription("After the stun, covers the cell the enemy is about to be pushed into, predicted ahead of server confirmation using both pings. On / Avoid Own Path drop a cobweb (Avoid Own Path also rejects cells beside you or along your heading). Stun Lava pours lava there instead. Stun Web Lava drops the web, pours lava on top of it, then scoops the lava back up. Lava modes always keep clear of where you stand and walk.")
            .setDependsOn("Follow-Up Rate %");
    private final BooleanSetting landingEnabled = new BooleanSetting("Landing Enabled", true).setBindable(true)
            .setDescription("Master switch for Web Landing. Bind a key to it to turn the web/lava follow-up on and off mid-fight.")
            .setDependsOn("Follow-Up Rate %,!Web Landing:Off");
    private final BooleanSetting retrieveLava = new BooleanSetting("Retrieve Lava", true)
            .setDescription("Stun Lava: scoop the lava back up with the emptied bucket after the Lava Pickup Delay.")
            .setDependsOn("Follow-Up Rate %,Web Landing:Stun Lava");
    private final SliderSetting webToLavaDelay = new SliderSetting("Web To Lava (ms)", 40, 0, 500, 0).withJitter(10)
            .setDescription("Stun Web Lava: wait between the web landing and pouring lava on top of it (at least the next tick).")
            .setDependsOn("Follow-Up Rate %,Web Landing:Stun Web Lava").setSection("Delays");
    private final SliderSetting lavaPickupDelay = new SliderSetting("Lava Pickup Delay (ms)", 60, 0, 1000, 0).withJitter(15)
            .setDescription("How long the lava stays before the bucket scoops it back up (at least the next tick).")
            .setDependsOn("Follow-Up Rate %,!Web Landing:Off,!Web Landing:On,!Web Landing:Avoid Own Path").setSection("Delays");
    private final ModeSetting aimMode = new ModeSetting("Facing", "Off", "Off", "Smooth", "Hidden")
            .setDescription("Off: never turns; the axe strike, web and lava each wait until your own crosshair covers them. Smooth: eases your visible camera over. Hidden: turns only what the server sees, via the shared silent rotation engine.");
    private final SliderSetting aimSpeed = new SliderSetting("Aim Speed", 20, 1, 60, 0)
            .setDescription("Turn speed onto the blocking enemy. Follow-up clicks track a quarter faster.")
            .setDependsOn("!Facing:Off");
    private final SliderSetting placeAimSpeed = new SliderSetting("Placement Aim Speed", 30, 1, 60, 0)
            .setDescription("Turn speed onto the web or lava cell.")
            .setDependsOn("!Facing:Off");
    private final SliderSetting aimImperfection = new SliderSetting("Aim Randomness %", 10, 0, 100, 0)
            .setDescription("Human-like wobble added to every turn. 0 is a perfectly smooth path.")
            .setDependsOn("!Facing:Off");
    private final SliderSetting swapBackDelay = new SliderSetting("Swap Back Delay (ms)", 50, 0, 1000, 0).withJitter(10)
            .setDescription("Wait after the last axe hit, web or lava use before your weapon is reselected. Never sent in the same movement interval as that action.")
            .setSection("Delays");

    private static final long WATCHDOG_MS = 1500L;
    /**
     * Fire as soon as the aim reaches a cell where this much of the outcome weight (see
     * {@link WebTrapPlanner#planHold}) leaves most of the body inside the web. The measured-input
     * hypothesis alone carries 0.30 (0.14 at half strength), so a cell the best estimate puts the landing on is enough: waiting
     * for every hypothesis to agree over a knockback flight only fires after the target has landed.
     */
    private static final double WEB_FIRE_CONFIDENCE = 0.22;
    /** Aim at the best cell as long as it scores at least this (some outcome catches the target in it). */
    private static final double WEB_MIN_SCORE = 0.04;
    /**
     * A cell below {@link #WEB_FIRE_CONFIDENCE} still fires once the target is expected to reach it
     * within this many ticks of the web existing: waiting any longer only loses it.
     */
    private static final double WEB_DEADLINE_TICKS = 2.0;
    /** The launch model is trusted for this many ticks after the hit, unless the target's positions contradict it. */
    private static final int KB_MODEL_TICKS = 24;
    /** The model is dropped when the target's reported position is further than this (blocks) from the modelled one. */
    private static final double KB_MODEL_TOLERANCE = 1.5;
    /** Hitbox inflation while choosing a face, because entities move before the aim arrives. */
    private static final double WEB_PLAN_MARGIN = 0.08;
    private static final int WEB_MAX_CLICK_CELLS = 6;
    /** No Self Web: cells within this many blocks of our hitbox, and along our motion for this many ticks, are skipped. */
    private static final double SELF_WEB_MARGIN = 0.6;
    private static final double SELF_WEB_LOOKAHEAD_TICKS = 4.0;
    /** How long a swap-back keeps being re-applied (and clicks swallowed) if the server resyncs the slot. */
    private static final long RETURN_RETRY_MS = 150L;
    /** Lava modes: horizontal berth (beyond our hitbox) and vertical berth no lava cell may enter. */
    private static final double LAVA_SELF_MARGIN = 1.2;
    private static final double LAVA_SELF_MARGIN_Y = 1.0;
    /** Stun Web Lava: give up pouring on the web this long after it was placed. */
    private static final long LAVA_POUR_TIMEOUT_MS = 700L;
    /** Keep trying to scoop placed lava back up for this long before leaving it. */
    private static final long LAVA_PICKUP_TIMEOUT_MS = 2500L;
    /** Longest an axe keeps a shield disabled (vanilla axe: 5 s); a stale "still blocking" inside it is ignored. */
    private static final long SHIELD_DISABLE_MS = 5000L;

    private enum State {
        IDLE,
        /** Aiming at the shield; nothing selected yet. */
        AIM_AXE,
        /** Axe selected and hit queued inside the current tick. */
        AXE_PENDING,
        STUN_DELAY,
        /** Aiming at the web cell while the server confirms the break; still holding the axe. */
        AIM_WEB,
        /** Web selected and placement queued inside the current tick. */
        WEB_PENDING,
        HOLD_WEB,
        /** Stun Web Lava: aiming at the placed web's top face to pour lava onto it. */
        AIM_LAVA,
        /** Lava bucket selected and the pour queued inside the current tick. */
        LAVA_PENDING,
        /** Lava placed; waiting for the pickup delay, then aiming the emptied bucket at the source. */
        LAVA_PICKUP,
        /** Bucket scoop queued inside the current tick. */
        PICKUP_PENDING,
        SWAP_BACK
    }

    private State state = State.IDLE;
    private long deadlineMs = 0L;
    private long startedMs = 0L;
    private long lastAdvanceMs = -1L;
    private long lastAttemptMs = 0L;
    /** Last time a run that selected a slot ended (or started); gates the Reuse Cooldown. */
    private long lastUseMs = 0L;

    private LivingEntity target = null;
    private int originalSlot = -1;
    private int axeSlot = -1;
    private int webSlot = -1;
    private int lavaSlot = -1;
    /** The landing placement of this run pours lava (Stun Lava, or Stun Web Lava without a web) instead of a web. */
    private boolean landingLava = false;
    /** Cell the in-flight landing placement is aimed at. */
    private BlockPos pendingLandingCell = null;
    /** Stun Web Lava: the web whose top face the lava is poured onto. */
    private BlockPos lavaSupport = null;
    /** Where this run's lava source was placed (to scoop it back). */
    private BlockPos placedLavaCell = null;
    private long lavaStageMs = 0L;
    private long lavaPlacedMs = 0L;
    // Delay draws for this run/attempt, sampled once so repeated checks cannot shorten them.
    private long plannedReactionMs = 20L;
    private long plannedReuseMs = 1500L;
    private long plannedAttemptGapMs = 250L;
    private long plannedStunFirstGapMs = StunSequence.DEFAULT_FIRST_GAP_MS;
    private final WebTrapPlanner planner = new WebTrapPlanner();
    /** When the first axe hit of this run went out; the shield break is confirmed against it. */
    private long breakHitMs = 0L;
    /** The target's shield was up and facing us when the first axe hit went out, so the break is taken as done at once. */
    private boolean shieldHitClientSide = false;
    // Launch of the first damaging stun hit, modelled once at the hit from the pre-hit server positions
    // (later samples already contain the flight) and kept while the reported positions agree with it.
    private boolean kbPending = false;
    private int kbClock = 0;
    private Motion kbModel = null;
    // Web plan for the current tick; the frames in between reuse it.
    private int planClock = Integer.MIN_VALUE;
    private Plan webPlan = null;
    private Click webClick = null;
    private BlockPos lastWebCell = null;
    /** Planner time (ticks after the model's start) at which a web placed this tick exists on the victim's client. */
    private double webTick = 0.0;
    /** The target's measured path already runs into a web, so another would be wasted. */
    private boolean webPathCovered = false;
    private boolean executingAttack = false;
    /** True once this run has selected a slot, so only then is there anything to swap back. */
    private boolean ownsSelection = false;
    private SubTickSequencer.FluentSequence pendingSequence = null;
    private boolean pendingResult = false;
    private final StunSequence stunSequence = new StunSequence();
    private long lastAxeHitNanos;
    private int stunAttempts = 0;

    /** Timing and click count for the two follow-ups; deadlines start at dispatched hits. */
    static final class StunSequence {
        static final long DEFAULT_FIRST_GAP_MS = 19L;
        static final long DEFAULT_FINAL_GAP_MS = 30L;

        private int remaining;
        private long deadlineNanos;
        private int generation;
        private LongSupplier finalGapMs = () -> DEFAULT_FINAL_GAP_MS;

        void start(long breakHitNanos) {
            start(breakHitNanos, DEFAULT_FIRST_GAP_MS, () -> DEFAULT_FINAL_GAP_MS);
        }

        /** Gaps come from the module's delay sliders; the second is drawn when the first click has gone out. */
        void start(long breakHitNanos, long firstGapMs, LongSupplier finalGapMs) {
            generation++;
            remaining = 2;
            this.finalGapMs = finalGapMs == null ? () -> DEFAULT_FINAL_GAP_MS : finalGapMs;
            deadlineNanos = breakHitNanos + Math.max(0L, firstGapMs) * 1_000_000L;
        }

        boolean advance(LongSupplier lastHitClock, LongPredicate awaitDeadline, BooleanSupplier attack) {
            int run = generation;
            while (remaining > 0) {
                if (!awaitDeadline.test(deadlineNanos) || generation != run) return false;
                if (!attack.getAsBoolean()) return false;
                if (generation != run) return false;
                remaining--;
                deadlineNanos = lastHitClock.getAsLong() + Math.max(0L, finalGapMs.getAsLong()) * 1_000_000L;
            }
            return true;
        }

        void clear() {
            generation++;
            remaining = 0;
            deadlineNanos = 0L;
        }
    }

    // Persistent return-slot enforcement (guarantees swapping back to sword)
    private int pendingReturnSlot = -1;
    private long returnDeadlineMs = 0L;
    /** The utility slot held while SWAP_BACK waits its delay; the player picking another slot ends the run. */
    private int swapBackFrom = -1;

    private long lastConfirmedBreakMs = 0L;
    private int confirmedBreakTargetId = Integer.MIN_VALUE;
    // Survive a run's reset: the last shield break the server reported (entity status 30), so a use that
    // began before it (the target's client has not caught up yet) is never answered with another swap.
    private int disabledTargetId = Integer.MIN_VALUE;
    private long disabledAtMs = 0L;
    /** The utility slot held when the swap-back was issued; the restore is only retried while it is still held. */
    private int pendingReturnFrom = -1;
    /** World and player the run state belongs to; a change (world switch, respawn) drops it. */
    private Object lastWorld = null;
    private Object lastPlayer = null;

    // Player age at the last hit or web placement. The aim on that target stays until the movement
    // packet after it is out (the age moves on), then a new aim or the release may follow.
    private int lastActionAge = Integer.MIN_VALUE;
    private boolean aimReleasePending = false;

    @Override
    public void breakConfirmed(PlayerEntity player) {
        if (player == null) return;
        confirmedBreakTargetId = player.getId();
        lastConfirmedBreakMs = System.currentTimeMillis();
        disabledTargetId = confirmedBreakTargetId;
        disabledAtMs = lastConfirmedBreakMs;
    }

    public AutoShieldBreak() {
        super("AutoShieldBreak", "When an enemy raises a shield at you, briefly switches to your axe and hits the shield to disable it, then returns your weapon. Can chain stun follow-up clicks and drop a cobweb or lava where the stunned player lands, with every step aimed and timed like a real player.", Category.COMBAT);
        setSubCategory(SubCategory.SMP);
        addSetting(mode);
        addSetting(weaponMode);
        addSetting(breakChance);
        addSetting(shieldHoldTicks);
        addSetting(switchBack);
        addSetting(reach);
        addSetting(stunChance);
        addSetting(webMode);
        addSetting(landingEnabled);
        addSetting(retrieveLava);
        addSetting(aimMode);
        addSetting(aimSpeed);
        addSetting(placeAimSpeed);
        addSetting(aimImperfection);
        addSetting(reactionDelay);
        addSetting(reuseCooldown);
        addSetting(attemptGap);
        addSetting(stunFirstGap);
        addSetting(stunFinalGap);
        addSetting(webToLavaDelay);
        addSetting(lavaPickupDelay);
        addSetting(swapBackDelay);
    }

    @Override
    public void onEnable() {
        try {
            EventBus.register(this);
            reset(false);
            planner.clear();
            resampleAttemptDelays();
            plannedReuseMs = sampleMs(reuseCooldown);
        } catch (Throwable ignored) {
        }
    }

    @Override
    public void onDisable() {
        try {
            EventBus.unregister(this);
            reset(true);
            planner.clear();
            clearPendingReturn();
        } catch (Throwable ignored) {
        }
    }

    @Override
    protected void onRuntimeStateReleased() {
        try {
            reset(true);
            planner.clear();
            clearPendingReturn();
        } catch (Throwable ignored) {}
        SlotSync.releaseOwner(this);
    }

    public boolean isBusy() {
        return state != State.IDLE || pendingReturnSlot != -1;
    }

    /**
     * True while this module is handling (or about to handle) a shield: a run is in progress, or it
     * is enabled with an axe in the hotbar and a valid held weapon. TriggerBot stands down on a
     * shielding target in that case, whatever its own shield settings, so the two never fight.
     */
    public boolean ownsShieldedTarget() {
        if (!isEnabled() || mc.player == null) return false;
        return isBusy() || (findAxe() != -1 && weaponConditionMet());
    }

    /**
     * Whether a swing at {@code t} from another module (TriggerBot) would be taken as the click that
     * starts a break: On Swing mode, idle, an axe in the hotbar, the held-item rule met and the
     * target's shield up and facing us. TriggerBot calls {@link #breakForSwing} instead of
     * swinging a sword into the shield.
     */
    public boolean wouldBreakOnSwing(LivingEntity t) {
        try {
            if (!isEnabled() || mc.player == null || t == null || !"On Swing".equals(mode.getCurrentMode())) return false;
            if (state != State.IDLE || pendingReturnSlot != -1 || findAxe() == -1 || !weaponConditionMet()) return false;
            return isValid(t) && isShieldFacingUs(t) && !shieldDisabledRecently(t, System.currentTimeMillis());
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** Starts the break as if the player had clicked at the shield; false when a gate (timing, chance, hold) says not yet. */
    public boolean breakForSwing(LivingEntity t) {
        try {
            return wouldBreakOnSwing(t) && tryStart();
        } catch (Throwable ignored) {
            return false;
        }
    }

    @EventTarget
    public void onCombatSwapLock(CombatSwapLockEvent event) {
        // Our own silent axe hit goes through AttackValidator, which posts this event too.
        if (event.isRequestedBy(this)) return;
        if (isBusy()) {
            event.setItemSwapLocked(true);
            event.setAutoAttackLocked(true);
        }
    }

    // ── triggers ──────────────────────────────────────────────────────────────────

    @Override
    public boolean handleAttack() {
        // A fault here would trip the callback guard and silently stop the module.
        try {
            return handleAttackChecked();
        } catch (Throwable ignored) {
            return false;
        }
    }

    private boolean handleAttackChecked() {
        if (mc.player == null || mc.world == null || executingAttack || interstellarTikiTiks.apexx.client.module.implementation.automations.AutoShield.isShielding()) return false;

        // If currently swapping back to sword, swallow the click so vanilla does not attack with axe
        if (pendingReturnSlot != -1) {
            tickReturnSlot();
            suppressAttack();
            return true;
        }

        if (!"On Swing".equals(mode.getCurrentMode())) {
            return state != State.IDLE;
        }

        if (state != State.IDLE) {
            suppressAttack();
            return true;
        }

        return tryStart();
    }

    /**
     * Everything runs here, at the tick head inside the action window: the axe (or web) select and
     * its hit (or placement) go out before this tick's movement packet. Stun clicks retain their
     * real deadlines; web and swap-back start on a following tick. Render frames never act.
     */
    @Override
    public void onTick() {
        try {
            if (mc.player == null || mc.world == null) {
                reset(false);
                planner.clear();
                return;
            }
            if (mc.world != lastWorld || mc.player != lastPlayer) {
                // World change or respawn: nothing of the old run (target, slots, action age) applies,
                // and the new inventory is not ours to restore into.
                lastWorld = mc.world;
                lastPlayer = mc.player;
                lastActionAge = Integer.MIN_VALUE;
                aimReleasePending = false;
                ownsSelection = false;
                reset(false);
                planner.clear();
                clearPendingReturn();
                disabledTargetId = Integer.MIN_VALUE;
            }
            if (!mc.player.isAlive() || mc.player.isSpectator()) {
                if (state != State.IDLE) {
                    ownsSelection = false;
                    reset(false);
                }
                clearPendingReturn();
                return;
            }
            // Server-reported positions of nearby players, sampled every tick so a hit web has a
            // measured path to work from the moment it is needed.
            if (!webMode.is("Off")) {
                planner.tick(mc, 12.0);
            }
            if (aimReleasePending && !inActionTick()) {
                aimReleasePending = false;
                if (state == State.IDLE) {
                    RotationManager.stop(this);
                }
            }
            tickReturnSlot();
            if (mc.currentScreen != null) {
                if (state != State.IDLE) reset(true);
                return;
            }
            // The crosshair at the tick head is still last frame's interpolated one; the hit is
            // checked against this tick's positions.
            if (mc.gameRenderer != null && mc.getCameraEntity() != null) {
                mc.gameRenderer.updateCrosshairTarget(1.0f);
            }
            if (state == State.IDLE && "Auto".equals(mode.getCurrentMode())) {
                tryStart();
            } else if (state != State.IDLE) {
                advance(System.currentTimeMillis());
            }
        } catch (Throwable ignored) {
        }
    }

    private boolean tryStart() {
        if (interstellarTikiTiks.apexx.client.module.implementation.automations.AutoShield.isShielding()) return false;
        if (pendingReturnSlot != -1) return false;

        long now = System.currentTimeMillis();
        if (now - lastAttemptMs < plannedAttemptGapMs) return false;
        if (now - lastUseMs < plannedReuseMs) return false;
        if (isMaceMomentActive()) return false;
        if (!weaponConditionMet()) return false;
        // Swapping off a food, potion or bow mid-use would cancel it; the break waits for the release.
        if (mc.player == null || mc.player.isUsingItem()) return false;

        LivingEntity t = acquireTarget();
        if (t == null || !isShieldFacingUs(t)) return false;
        // Already knocked down (by us or anyone): the "still blocking" we see is the stale use.
        if (shieldDisabledRecently(t, now)) return false;
        // A shield only blocks once it has been up for its block delay (5 ticks) on the server. An
        // axe hit that arrives earlier lands as a plain hit and disables nothing, so the swap waits
        // until the hit sent now reaches a shield the server already counts as blocking.
        if (ticksUntilServerBlocks(t) > 0) return false;
        // Held time is the target's own use time (ticks); the reaction delay counts on top of the hold threshold.
        long heldMs = (long) t.getItemUseTime() * 50L;
        long neededMs = (long) shieldHoldTicks.getValue() * 50L + plannedReactionMs;
        if (heldMs < neededMs) return false;

        int axe = findAxe();
        if (axe == -1) return false;

        if (breakChance.getValue() < 100 && ThreadLocalRandom.current().nextDouble() * 100.0 >= breakChance.getValue()) {
            markAttempt(now);
            return false;
        }

        int orig = selectedSlot();
        // Fallback to sword/weapon if the currently selected slot was already the axe, web or bucket
        int webInHotbar = findWeb();
        int lavaInHotbar = findLavaBucket();
        if (orig == axe || (webInHotbar != -1 && orig == webInHotbar) || (lavaInHotbar != -1 && orig == lavaInHotbar)
                || orig < 0 || orig > 8) {
            int weapon = findWeapon();
            if (weapon != -1) {
                orig = weapon;
            }
        }

        target = t;
        axeSlot = axe;
        originalSlot = orig;
        markAttempt(now);
        markUse(now);
        startedMs = now;
        plannedStunFirstGapMs = sampleMs(stunFirstGap);

        enter(State.AIM_AXE, now, 0L);
        // The aim, slot select and hit run from the next render/tick pass. Starting them from here
        // would nest them inside doAttack (On Click), where the swap and the click are swallowed.
        if (!"On Swing".equals(mode.getCurrentMode())) {
            tryAxeBreak(now);
        }
        return true;
    }

    // ── State Machine ─────────────────────────────────────────────────────────────

    private void enter(State s, long now, long delay) {
        state = s;
        deadlineMs = now + Math.max(0L, delay);
    }

    private void advance(long now) {
        if (now == lastAdvanceMs) return;
        lastAdvanceMs = now;

        if (mc.player == null || mc.world == null) {
            reset(false);
            return;
        }

        // Lava stages carry their own timeouts: the run watchdog must not abandon a placed source.
        if (state != State.IDLE && !inLavaStage() && now - startedMs > watchdogMs()) {
            reset(true);
            return;
        }

        if (now < deadlineMs) {
            int held = waitSlot();
            if (held != -1 && selectedSlot() != held) {
                // The player picked another slot while we waited: the hotbar is theirs now. Forcing our
                // item back (possibly right after a use, out of vanilla order) or restoring over their
                // choice would both fight them, so the run ends here.
                yieldToPlayer();
            }
            return;
        }

        switch (state) {
            case AIM_AXE -> tryAxeBreak(now);
            case AXE_PENDING -> resolveAxeBreak(now);
            case STUN_DELAY -> {
                executeStunHit(now);
            }
            case AIM_WEB -> processWebPlacement(now);
            case WEB_PENDING -> resolveWebPlacement(now);
            case HOLD_WEB -> {
                // The slot change must not share a movement interval with the placement.
                if (!inActionTick()) toSwapBack(now);
            }
            case AIM_LAVA -> processLavaPour(now);
            case LAVA_PENDING -> resolveLavaPour(now);
            case LAVA_PICKUP -> processLavaPickup(now);
            case PICKUP_PENDING -> resolveLavaPickup(now);
            case SWAP_BACK -> processSwapBack();
            default -> reset(true);
        }
    }

    /** Aims first; the axe is selected only once the live ray reaches the target. */
    private void tryAxeBreak(long now) {
        if (target == null || !target.isAlive() || !inReach(target, 0.4) || mc.player == null) {
            toSwapBack(now);
            return;
        }
        // Lowered (or turned away) while we were still aiming, or already knocked down by someone: an
        // axe hit now would only cost the sword's charge, so the run ends before anything is selected.
        if (!isShieldFacingUs(target) || shieldDisabledRecently(target, now)) {
            toSwapBack(now);
            return;
        }
        if (!isAxeAt(axeSlot)) {
            // The axe left the hotbar (moved, dropped, broke). Before anything was selected another axe
            // may stand in; once the slot was selected the run ends and the weapon comes back.
            int axe = ownsSelection ? -1 : findAxe();
            if (axe == -1) {
                toSwapBack(now);
                return;
            }
            axeSlot = axe;
        }
        if (!aimAtTarget(target)) {
            return;
        }
        // Re-raised since the start (its use time restarted), or we are eating/drinking: keep the aim,
        // but select nothing until a hit sent now meets a shield the server counts as blocking.
        if (mc.player.isUsingItem() || ticksUntilServerBlocks(target) > 0) {
            return;
        }
        // Another attack (ours or the player's) already left this movement interval: the hit waits.
        // The follow-ups always go out in later intervals, so no tick budget is reserved for them.
        if (!TickActionOrder.canAttack()) return;
        // Every gate the hit itself runs (live ray, wire ray, cooldown, swap lock, shield state) is checked
        // BEFORE the axe is selected: a refusal after the hotbar packet left would put a slot change on the
        // wire with no attack behind it. Packets still held from an earlier frame would also carry this
        // swap and hit into the next interval, past the rotation the rays were checked against.
        if (TickActionOrder.heldCount() > 0 || !axeHitReady(target)) return;
        final LivingEntity hitTarget = target;
        state = State.AXE_PENDING;
        dispatchInTick(axeSlot, () -> axeHit(hitTarget));
        resolveAxeBreak(now);
    }

    private void resolveAxeBreak(long now) {
        if (pendingSequence != null && !pendingSequence.isComplete()) {
            return;
        }
        boolean hit = takePendingResult();
        if (!hit) {
            // Rejected, or dropped at the tick boundary: re-validate the aim in a later tick.
            enter(State.AIM_AXE, now, 0L);
            return;
        }
        // The follow-ups only make sense on a guard the first hit actually knocked down.
        if (stunChance.getValue() > 0 && shieldHitClientSide) {
            if (stunChance.getValue() < 100 && ThreadLocalRandom.current().nextDouble() * 100.0 >= stunChance.getValue()) {
                checkAndTransitionToWebOrSwapBack(now);
                return;
            }
            stunSequence.start(lastAxeHitNanos, plannedStunFirstGapMs, () -> sampleMs(stunFinalGap));
            stunAttempts = 0;
            enter(State.STUN_DELAY, now, 0L);
            executeStunHit(now);
            return;
        }

        checkAndTransitionToWebOrSwapBack(now);
    }

    private void executeStunHit(long now) {
        // Only a dead or gone target ends the follow-ups. A target that stepped past reach for a
        // tick (the first follow-up's knockback) or a slot the server briefly resynced is retried
        // below, not abandoned, until the attempt or time limit.
        if (target == null || !target.isAlive() || mc.player == null) {
            toSwapBack(now);
            return;
        }
        if (!isAxeAt(axeSlot)) {
            // The axe broke on the knockdown or left the hotbar: no follow-up is possible.
            checkAndTransitionToWebOrSwapBack(now);
            return;
        }
        if (selectedSlot() != axeSlot || !(mc.player.getMainHandStack().getItem() instanceof AxeItem)) {
            // A hotbar change never follows a hit in the same movement interval; the axe comes back
            // on the next tick and the follow-up goes out behind it.
            if (!inActionTick() && TickActionOrder.canSwitchSlot() && axeSlot != -1
                    && TickActionOrder.heldCount() == 0 && axeHitReady(target)) {
                selectSlot(axeSlot);
                SubTickSequencer.awaitWireSeparation(mc);
            }
            if (selectedSlot() != axeSlot) {
                stunAttempts++;
                if (stunAttempts > 10 || (breakHitMs > 0L && now - breakHitMs > 600L)) {
                    checkAndTransitionToWebOrSwapBack(now);
                } else {
                    enter(State.STUN_DELAY, now, 0L);
                }
                return;
            }
        }
        // Keep the reached aim for the rest of a hit's tick. A moving target may be tracked again
        // on the next tick; every individual click still rechecks the authoritative live ray.
        if ((!inActionTick() && !aimAtTarget(target)) || !stunSequence.advance(
                () -> lastAxeHitNanos,
                // One attack per movement interval: a follow-up never shares the tick of the hit before it.
                deadline -> TickActionOrder.canAttack() && SubTickSequencer.awaitActionDeadline(mc, deadline),
                () -> axeHit(target))) {
            stunAttempts++;
            if (stunAttempts > 10 || (breakHitMs > 0L && now - breakHitMs > 600L)) {
                checkAndTransitionToWebOrSwapBack(now);
            } else {
                enter(State.STUN_DELAY, now, 0L);
            }
            return;
        }

        stunAttempts = 0;
        checkAndTransitionToWebOrSwapBack(System.currentTimeMillis());
    }

    private void checkAndTransitionToWebOrSwapBack(long now) {
        if (shouldLandOn(target)) {
            // Web first (On, Avoid Own Path, Stun Web Lava); Stun Lava, or Stun Web Lava without a
            // web in the hotbar, pours lava on the landing cell instead.
            int web = webMode.is("Stun Lava") ? -1 : findWeb();
            if (web != -1 && !WebPlacementHelper.isCollidingWithCobweb(target)) {
                webSlot = web;
                landingLava = false;
                planClock = Integer.MIN_VALUE;
                enter(State.AIM_WEB, now, 0L);
                processWebPlacement(now);
                return;
            }
            boolean lavaLanding = webMode.is("Stun Lava") || (webMode.is("Stun Web Lava") && web == -1);
            int lava = lavaLanding ? findLavaBucket() : -1;
            if (lava != -1 && !target.isInLava()) {
                lavaSlot = lava;
                landingLava = true;
                planClock = Integer.MIN_VALUE;
                enter(State.AIM_WEB, now, 0L);
                processWebPlacement(now);
                return;
            }
        }
        toSwapBack(now);
    }

    /**
     * Aims at the web cell right after the axe hit and places once the break is confirmed (client
     * side, see {@link #shieldBreakConfirmed}) and the live ray reaches a
     * cell the target's predicted path agrees on and that holds most of their body once it catches
     * them. The prediction runs on the target's server-reported positions with the round trip and the
     * target's ping as lead ({@link WebTrapPlanner#planHold}).
     */
    private void processWebPlacement(long now) {
        if (!(target instanceof PlayerEntity victim) || !victim.isAlive()
                || (landingLava ? victim.isInLava() : WebPlacementHelper.isCollidingWithCobweb(victim))) {
            toSwapBack(now);
            return;
        }
        if (landingLava ? !isItemAt(lavaSlot, Items.LAVA_BUCKET) : !isItemAt(webSlot, Items.COBWEB)) {
            // Used up or moved out of the hotbar since the run picked it.
            toSwapBack(now);
            return;
        }
        if (inActionTick()) {
            // The web face is a new aim target: it starts once the hit's movement packet is out.
            enter(State.AIM_WEB, now, 0L);
            return;
        }
        if (now - startedMs > watchdogMs() - 200L) {
            toSwapBack(now);
            return;
        }

        boolean confirmed = shieldBreakConfirmed(victim, now);
        if (!confirmed && now - breakHitMs > breakTimeoutMs()) {
            // The hit never produced a break (blocked out of reach, shield lowered, dropped by the server).
            toSwapBack(now);
            return;
        }

        planWeb(victim);
        if (webPathCovered) {
            toSwapBack(now);
            return;
        }
        if (webClick == null) {
            RotationManager.stop(this);
            return;
        }
        requestWebAim();
        if (!confirmed || !InteractSim.useReady(mc) || mc.player == null || mc.player.isUsingItem()) {
            return;
        }

        // Eligible only when the live ray (camera or silent) reaches a confident cell, not on angle tolerance.
        boolean silent = aimMode.is("Hidden");
        BlockHitResult live = BlockPlacementValidator.rayFromLook(mc, silent);
        BlockPos cell = live == null ? null : landingCell(live);
        if (cell == null) {
            return;
        }
        Cell scored = webPlan != null ? webPlan.find(cell) : null;
        boolean matchesClick = webClick != null && cell.equals(webClick.cell());
        if (!matchesClick) {
            if (scored == null || scored.score() < WEB_MIN_SCORE || (webPlan != null && scored.score() < webPlan.bestScore() * 0.6)) {
                return;
            }
            // The cell holds most of the body in the outcomes that matter: place now. A weaker cell still
            // goes once the target is about to reach it, because waiting past that only loses the catch.
            boolean confident = scored.confidence() >= WEB_FIRE_CONFIDENCE;
            boolean deadline = scored.firstTick() - webTick <= WEB_DEADLINE_TICKS;
            if (!confident && !deadline) {
                return;
            }
        }
        if (!webPlaceable(cell, webReach()) || !WebHitModel.clickable(mc, live, webReach())) {
            return;
        }
        state = State.WEB_PENDING;
        pendingLandingCell = cell;
        if (landingLava) {
            dispatchInTick(lavaSlot, () -> placeLava(cell, null));
        } else {
            dispatchInTick(webSlot, () -> placeWeb(cell));
        }
        resolveWebPlacement(now);
    }

    private void resolveWebPlacement(long now) {
        if (pendingSequence != null && !pendingSequence.isComplete()) {
            return;
        }
        if (takePendingResult()) {
            if (landingLava) {
                // Stun Lava: the source stays for the pickup delay (or for good without Retrieve Lava).
                if (webMode.is("Stun Lava") && !retrieveLava.isEnabled()) {
                    enter(State.HOLD_WEB, now, 0L);
                } else {
                    enter(State.LAVA_PICKUP, now, sampleMs(lavaPickupDelay));
                }
                return;
            }
            int lava = webMode.is("Stun Web Lava") ? findLavaBucket() : -1;
            if (lava != -1 && pendingLandingCell != null) {
                // Stun Web Lava: pour onto the new web's top face from the next tick on.
                lavaSlot = lava;
                lavaSupport = pendingLandingCell;
                lavaStageMs = now;
                enter(State.AIM_LAVA, now, sampleMs(webToLavaDelay));
                return;
            }
            // Swapped back on a later tick, never in the placement's movement interval.
            enter(State.HOLD_WEB, now, 0L);
        } else if (now - startedMs < watchdogMs() - 200L) {
            enter(State.AIM_WEB, now, 0L);
        } else {
            toSwapBack(now);
        }
    }

    /**
     * Client-side: the axe hit went out against a raised, front-facing shield, so the break needs no
     * round trip (the server handles the hit before the web either way). If the shield was not seen
     * up at that moment, fall back to the server's shield-break status (entity status 30), or to the
     * target no longer blocking once a round trip has passed.
     */
    private boolean shieldBreakConfirmed(PlayerEntity victim, long now) {
        if (breakHitMs == 0L) return false;
        if (shieldHitClientSide) return true;
        if (confirmedBreakTargetId == victim.getId() && lastConfirmedBreakMs >= breakHitMs) return true;
        long roundTripMs = (long) (WebHitModel.rttTicks(mc) * 50.0);
        return now - breakHitMs >= roundTripMs + 50L && !victim.isBlocking();
    }

    /** How long to wait for the break confirmation: a round trip and a half plus a few ticks of jitter, at least 8 ticks. */
    private long breakTimeoutMs() {
        return (long) (Math.max(8.0, WebHitModel.rttTicks(mc) * 1.5 + 4.0) * 50.0);
    }

    /** The run watchdog, stretched by two round trips so a laggy confirmation and flight still fit. */
    private long watchdogMs() {
        return WATCHDOG_MS + (long) (WebHitModel.rttTicks(mc) * 100.0);
    }

    /** {@link WebHitModel#placeable} plus, with No Self Web, a wider berth around where we stand and walk. */
    private boolean webPlaceable(BlockPos cell, double reach) {
        if (!WebHitModel.placeable(mc, cell, reach)) return false;
        if (landingLava) return lavaCellOpen(cell) && lavaSafe(cell);
        if (!avoidOwnPath() || mc.player == null) return true;
        Vec3d v = mc.player.getVelocity();
        Box zone = mc.player.getBoundingBox().expand(SELF_WEB_MARGIN, 0.0, SELF_WEB_MARGIN)
                .stretch(v.x * SELF_WEB_LOOKAHEAD_TICKS, 0.0, v.z * SELF_WEB_LOOKAHEAD_TICKS);
        return !new Box(cell).intersects(zone);
    }

    /** Avoid Own Path, and every lava mode (lava is never poured where we stand or walk). */
    private boolean avoidOwnPath() {
        return webMode.is("Avoid Own Path") || wantsLava();
    }

    private boolean wantsLava() {
        return webMode.is("Stun Lava") || webMode.is("Stun Web Lava");
    }

    /** A cell lava may be poured into: empty (air or a replaceable plant), with no fluid in it. */
    private boolean lavaCellOpen(BlockPos cell) {
        if (mc.world == null || cell == null) return false;
        BlockState s = mc.world.getBlockState(cell);
        return s.isAir() || (s.isReplaceable() && s.getFluidState().isEmpty() && !s.isOf(Blocks.COBWEB));
    }

    /**
     * No lava where we are, will be within the look-ahead, or a step around/above us, and none on a
     * friend ({@link WebHitModel#isSafeCell}). The berth is wider than the web one: lava hurts on contact.
     */
    private boolean lavaSafe(BlockPos cell) {
        if (mc.player == null || cell == null || !WebHitModel.isSafeCell(mc, cell)) return false;
        Vec3d v = mc.player.getVelocity();
        Box zone = mc.player.getBoundingBox().expand(LAVA_SELF_MARGIN, LAVA_SELF_MARGIN_Y, LAVA_SELF_MARGIN)
                .stretch(v.x * SELF_WEB_LOOKAHEAD_TICKS, 0.0, v.z * SELF_WEB_LOOKAHEAD_TICKS);
        return !new Box(cell).intersects(zone);
    }

    /** Destination of a click: block placement semantics for a web, bucket semantics (always the face's neighbour) for lava. */
    private BlockPos landingCell(BlockHitResult live) {
        if (live == null || live.getType() != HitResult.Type.BLOCK) return null;
        return landingLava ? live.getBlockPos().offset(live.getSide()) : BlockPlacementValidator.placementCell(mc, live);
    }

    private double webReach() {
        return mc.player == null ? 0.0 : Math.min(mc.player.getBlockInteractionRange(), 4.0);
    }

    /** Re-plans the web once per client tick; the frames in between reuse the plan and the chosen click. */
    private void planWeb(PlayerEntity victim) {
        int clock = planner.clock();
        if (clock == planClock) {
            return;
        }
        planClock = clock;
        double rtt = WebHitModel.rttTicks(mc);
        double targetPing = WebHitModel.pingTicks(mc, victim.getUuid());

        Motion motion = null;
        if (kbPending && kbModel != null && clock - kbClock <= KB_MODEL_TICKS && modelAgrees(victim, clock, rtt, targetPing)) {
            // The launch is applied on the victim's client when the hit's velocity packet reaches it, and a
            // web we send now reaches the same client (clock - kbClock) ticks after that: both are server
            // packets to the victim, sent that far apart. The model runs on the victim's own physics.
            motion = kbModel;
            webTick = (clock - kbClock) + 1.0;
        }
        if (motion == null) {
            // The latest server position is the victim's client as it was half a round trip each way ago:
            // our packet reaches the server and the server's block update reaches them after both pings.
            motion = planner.observed(victim);
            webTick = planner.ticksSinceSample(victim) + rtt + targetPing + 1.0;
        }
        // Each candidate is simulated with the web in place (caught on first contact, then slowed), so a
        // cell is scored by how much of the body it holds, not by how centred the target passes through it.
        double reach = webReach();
        webPlan = planner.planHold(victim, motion, webTick, mc.player.getEntityPos(), c -> webPlaceable(c, reach));
        webPathCovered = webPlan.webbedTick() >= 0 && webPlan.webbedTick() <= WebTrapPlanner.HORIZON / 2;
        webClick = webPathCovered ? null : chooseWebClick();
    }

    /**
     * Whether the modelled launch still matches the target's reported position: once a position update
     * from inside the flight has arrived, the model is advanced to that moment and compared. A server that
     * alters knockback (or a hit that did not launch) fails this and the plan falls back to what is measured.
     */
    private boolean modelAgrees(PlayerEntity victim, int clock, double rtt, double targetPing) {
        if (!planner.impulseSince(victim, kbClock)) {
            return true;
        }
        // Flight time at which the latest reported position was taken (server-side, so back by both pings).
        double flight = (clock - planner.ticksSinceSample(victim) - kbClock) - (rtt + targetPing);
        if (flight < 3.0) {
            return true;
        }
        Vec3d predicted = planner.advance(victim, kbModel, (int) Math.round(flight)).pos();
        Vec3d reported = victim.getTrackedPosition().getPos();
        double dx = predicted.x - reported.x;
        double dz = predicted.z - reported.z;
        if (Math.sqrt(dx * dx + dz * dz) <= KB_MODEL_TOLERANCE) {
            return true;
        }
        kbPending = false;
        return false;
    }

    /** Best clickable cell, prioritizing trapping the opponent at the highest point of their descent. */
    private Click chooseWebClick() {
        double reach = webReach();

        // 1. Primary: find the click that traps the target at the highest point of their descent!
        if (target instanceof PlayerEntity victim) {
            Motion motion = (kbPending && kbModel != null) ? kbModel : planner.observed(victim);
            Click descentClick = planner.findDescentApexClick(mc, victim, motion, webTick, reach, WEB_PLAN_MARGIN, c -> webPlaceable(c, reach));
            if (descentClick != null) {
                lastWebCell = descentClick.cell();
                return descentClick;
            }
        }

        // 2. Scored plan from WebTrapPlanner
        if (webPlan != null && !webPlan.cells().isEmpty()) {
            double best = webPlan.bestScore();
            Cell firstCell = null;
            Click first = null;
            Cell keptCell = null;
            Click kept = null;
            int examined = 0;
            for (Cell c : webPlan.cells()) {
                if (examined >= WEB_MAX_CLICK_CELLS || c.score() < Math.max(WEB_MIN_SCORE, best * 0.25)) break;
                if (!webPlaceable(c.pos(), reach)) continue;
                examined++;
                Click click = WebTrapPlanner.findClick(mc, c.pos(), reach, WEB_PLAN_MARGIN);
                if (click == null) continue;
                if (first == null) {
                    first = click;
                    firstCell = c;
                }
                if (c.pos().equals(lastWebCell)) {
                    kept = click;
                    keptCell = c;
                }
            }
            if (kept != null && keptCell.score() >= firstCell.score() * 0.8) {
                first = kept;
                firstCell = keptCell;
            }
            if (first != null) {
                lastWebCell = firstCell.pos();
                return first;
            }
        }

        // 3. Robust fallback: directClick to ensure a web is always placed if possible
        if (target instanceof PlayerEntity victim) {
            Motion motion = (kbPending && kbModel != null) ? kbModel : planner.observed(victim);
            Click direct = planner.directClick(mc, victim, motion, webTick, reach, WEB_PLAN_MARGIN, c -> webPlaceable(c, reach));
            if (direct != null) {
                lastWebCell = direct.cell();
                return direct;
            }
        }

        lastWebCell = null;
        return null;
    }

    /** Remembers the first launching hit's attacker state, for the knockback estimate before it is observed. */
    private void captureKnockback(LivingEntity t) {
        if (webMode.is("Off") || !(t instanceof PlayerEntity victim) || mc.player == null) return;
        // A swing into damage immunity deals no knockback; there is no launch to predict.
        kbPending = !MeleeMechanics.isInDamageImmunity(victim);
        kbClock = planner.clock();
        kbModel = null;
        if (!kbPending) return;
        Vec3d attackerPos = mc.player.getEntityPos();
        float attackerYaw = RotationManager.isSilentRotationActive() ? RotationManager.getFinalYaw() : mc.player.getYaw();
        MeleeMechanics.AttackRecord rec = MeleeMechanics.lastAttack();
        boolean sprintKnockback = rec != null && rec.targetId() == victim.getId() && rec.sprintKnockback();
        double bonus = (sprintKnockback ? 1.0 : 0.0) + WebHitModel.weaponKnockback(mc);
        // Modelled now: every later sample is already inside the flight and could not give the pre-hit motion.
        kbModel = WebHitModel.knockbackMotion(mc, planner, victim, kbClock, kbClock, attackerPos, attackerYaw, bonus,
                WebHitModel.rttTicks(mc), WebHitModel.pingTicks(mc, victim.getUuid()));
    }

    /**
     * Selects {@code slot}, then runs {@code action} later in the same tick (sub-tick micro-delay in
     * Legit, back-to-back in Direct). Work the sequencer drops at the tick boundary reads as a failure.
     */
    private void dispatchInTick(int slot, BooleanSupplier action) {
        if (!TickActionOrder.canSwitchSlot()) {
            pendingResult = false;
            return;
        }
        pendingResult = false;
        ownsSelection = true;
        final boolean swaps = selectedSlot() != slot;
        try {
            pendingSequence = SubTickSequencer.sequence(mc, this)
                    .selectSlot(slot)
                    .thenDelay()
                    .action(() -> {
                        // A hotbar packet and the hit, web or bucket use it enables never leave in the
                        // same instant (axe, web and lava alike); nothing to space when no swap happened.
                        if (swaps) {
                            SlotSync.paceAfterSwap();
                            SubTickSequencer.awaitWireSeparation(mc);
                        }
                        pendingResult = action.getAsBoolean();
                    });
            pendingSequence.start();
        } catch (Throwable failure) {
            SubTickSequencer.cancelOwner(this);
            pendingSequence = null;
            pendingResult = false;
        }
    }

    private boolean takePendingResult() {
        boolean result = pendingResult;
        pendingSequence = null;
        pendingResult = false;
        return result;
    }

    private void toSwapBack(long now) {
        if (!ownsSelection || breakHitMs == 0L) {
            // Nothing selected, or the axe went out but no hit ever followed it (a dispatch refused after
            // the swap; the pre-swap gate makes that an AttackEvent cancel only). Returning now would be
            // a slot -> slot pair with no attack between, so the axe stays and the player takes it from here.
            reset(false);
            return;
        }
        int returnSlot = resolveReturnSlot();
        // A web or bucket slot is always given back; the axe only with Swap Back.
        boolean utility = webSlot != -1 || lavaSlot != -1;
        if ((switchBack.isEnabled() || utility) && returnSlot >= 0 && returnSlot <= 8) {
            long delay = ModuleDelays.clampMs(swapBackDelay.sample());
            if (delay > 0L || inActionTick()) {
                // Waits the Swap Back Delay; even at 0 a hotbar change never follows a hit or use in
                // the same movement interval (SlotSync.restore defers it to the next interval head).
                int held = selectedSlot();
                swapBackFrom = isUtilitySlot(held) ? held : -1;
                enter(State.SWAP_BACK, now, delay);
            } else {
                applySwapBack(returnSlot);
                reset(false);
            }
        } else {
            reset(false);
        }
    }

    private void processSwapBack() {
        int returnSlot = resolveReturnSlot();
        if ((switchBack.isEnabled() || webSlot != -1 || lavaSlot != -1) && returnSlot >= 0 && returnSlot <= 8) {
            applySwapBack(returnSlot);
        }
        reset(false);
    }

    /** One of this run's utility slots (axe, web, bucket), never a return target. */
    private boolean isUtilitySlot(int slot) {
        return slot == axeSlot || (webSlot != -1 && slot == webSlot) || (lavaSlot != -1 && slot == lavaSlot);
    }

    private int resolveReturnSlot() {
        int ret = originalSlot;
        if (ret < 0 || ret > 8 || isUtilitySlot(ret)) {
            int sword = findSword();
            if (sword != -1) {
                return sword;
            }
            int weapon = findWeapon();
            if (weapon != -1) {
                return weapon;
            }
        }
        if (ret >= 0 && ret <= 8 && !isUtilitySlot(ret)) {
            return ret;
        }
        int sword = findSword();
        if (sword != -1) return sword;
        int weapon = findWeapon();
        if (weapon != -1) return weapon;
        return (originalSlot >= 0 && originalSlot <= 8) ? originalSlot : 0;
    }

    private void applySwapBack(int slot) {
        // Only restore while the selection is still ours; the player may have picked another slot.
        int current = selectedSlot();
        if (!isUtilitySlot(current)) return;
        pendingReturnSlot = slot;
        pendingReturnFrom = current;
        returnDeadlineMs = System.currentTimeMillis() + RETURN_RETRY_MS;
        // Selected now when nothing left this interval yet, else queued for the next interval head
        // (TickActionOrder.deferSlot), never in the frame of the hit or use it follows.
        if (SlotSync.restore(mc, slot)) {
            clearPendingReturn();
        }
    }

    private void clearPendingReturn() {
        pendingReturnSlot = -1;
        pendingReturnFrom = -1;
        returnDeadlineMs = 0L;
    }

    private void tickReturnSlot() {
        if (pendingReturnSlot < 0 || pendingReturnSlot > 8 || mc.player == null
                || System.currentTimeMillis() >= returnDeadlineMs) {
            clearPendingReturn();
            return;
        }
        int cur = selectedSlot();
        if (cur == pendingReturnSlot || cur != pendingReturnFrom) {
            // Restored, or the player picked another slot meanwhile: nothing left to enforce, and the
            // player's clicks and the other combat modules are released at once.
            clearPendingReturn();
            return;
        }
        SlotSync.restore(mc, pendingReturnSlot);
    }

    // ── Direct Actions (Zero Camera Glitching) ───────────────────────────────────

    /**
     * Every gate the axe hit runs that does not depend on the axe already being in hand: this tick's
     * action window, one attack per movement interval, the target, the live and wire rays, the shield
     * state for the knockdown, the cooldown and the swap lock ({@link AttackValidator#canDispatchEntityAttack}).
     * Checked before the axe is selected and again at the hit, so the hotbar packet only leaves when the
     * attack behind it will.
     */
    private boolean axeHitReady(LivingEntity t) {
        if (mc.player == null || mc.world == null || mc.interactionManager == null || t == null
                || !mc.isOnThread() || !TickActionOrder.isWindowOpen() || mc.currentScreen != null) return false;
        if (mc.player.isUsingItem()) return false;
        // At most one attack per movement interval (break hit and each follow-up alike).
        if (!TickActionOrder.canAttack()) return false;
        if (!isValid(t) || !liveAxeRayReaches(t)) return false;
        // The knockdown hit only goes out against a shield that is up, facing us, counted as blocking by
        // the server and not already disabled; the follow-ups land on the disabled guard.
        if (breakHitMs == 0L && (!isShieldFacingUs(t) || ticksUntilServerBlocks(t) > 0
                || shieldDisabledRecently(t, System.currentTimeMillis()))) return false;
        if (axeSlot < 0 || axeSlot > 8) return false;
        ItemStack axe = mc.player.getInventory().getStack(axeSlot);
        if (!(axe.getItem() instanceof AxeItem)) return false;
        // Legit clears the miss cooldown right before the hit (ExecutionMode.relaxAttackCooldown); Direct keeps it.
        boolean cooldownRelaxed = !interstellarTikiTiks.apexx.client.util.ExecutionMode.isDirect();
        return Boolean.TRUE.equals(ModuleExecutionContext.call(this,
                () -> AttackValidator.canDispatchEntityAttack(mc, t, axe, cooldownRelaxed)));
    }

    private boolean axeHit(LivingEntity t) {
        if (!axeHitReady(t)) return false;
        if (selectedSlot() != axeSlot || !(mc.player.getMainHandStack().getItem() instanceof AxeItem)) return false;
        executingAttack = true;
        try {
            if (mc instanceof MinecraftClientAccessor acc) interstellarTikiTiks.apexx.client.util.ExecutionMode.relaxAttackCooldown(acc);
            boolean attacked = Boolean.TRUE.equals(ModuleExecutionContext.call(this, () -> {
                if (mc.attackCooldown > 0) return false;
                if (!AttackValidator.emitAttackClick(mc)) return false;
                // Vanilla 1.9+ order (MinecraftClient.doAttack): the attack packet, then the arm swing. The
                // swing used to go first (the 1.8 order), which Grim's PacketOrderB flags on every hit
                // ("did not swing for attack": no swing between the attack and the next packet).
                return AttackValidator.dispatchEntityAttack(mc, t, false, true);
            }));
            if (attacked) {
                lastAxeHitNanos = System.nanoTime();
                TickActionOrder.recordAttack();
                lastActionAge = mc.player.age;
                RotationManager.setPostAttackHold(this, 1, RotationManager.getFinalYaw(), RotationManager.getFinalPitch());
                if (breakHitMs == 0L) {
                    breakHitMs = System.currentTimeMillis();
                    // The break is decided here, not a round trip later: an axe hit on a raised,
                    // front-facing shield always disables it on the server.
                    shieldHitClientSide = isShieldFacingUs(t) && ticksUntilServerBlocks(t) == 0;
                }
                captureKnockback(t);
                if (mc instanceof MinecraftClientAccessor acc) interstellarTikiTiks.apexx.client.util.ExecutionMode.relaxAttackCooldown(acc);
            }
            return attacked;
        } catch (Throwable ignored) {
            return false;
        } finally {
            // The target-bound action consumed this click; vanilla must not replay its key edge
            // in input handling later in the tick and create an unpaced fourth attack.
            if (mc.options != null && mc.options.attackKey instanceof KeyBindingAccessor attackKey) {
                attackKey.setTimesPressed(0);
            }
            executingAttack = false;
        }
    }

    private boolean liveAxeRayReaches(LivingEntity entity) {
        double range = Math.min(reach.getValue(), 3.0);
        // The rotation that goes on the wire: any active silent rotation (ours or another module's),
        // else the camera. Gating on the camera while another silent aim is live would check a ray the
        // server never sees.
        boolean wire = RotationManager.isSilentRotationActive();
        float yaw = wire ? RotationManager.getFinalYaw() : mc.player.getYaw();
        float pitch = wire ? RotationManager.getFinalPitch() : mc.player.getPitch();
        var hit = RotationManager.getCrosshairTarget(yaw, pitch, range);
        if (!(hit instanceof EntityHitResult entityHit) || entityHit.getEntity() != entity) return false;
        Vec3d eye = mc.player.getEyePos();
        Vec3d end = eye.add(Vec3d.fromPolar(pitch, yaw).multiply(range));
        return entity.getBoundingBox().contains(eye) || entity.getBoundingBox().raycast(eye, end).isPresent();
    }

    private boolean placeWeb(BlockPos cell) {
        if (cell == null || mc.player == null || mc.world == null || mc.interactionManager == null) return false;
        if (!mc.player.getMainHandStack().isOf(Items.COBWEB)) return false;
        boolean silent = aimMode.is("Hidden");
        // Re-cast at dispatch time: the same cell, nothing in front of the face.
        BlockHitResult live = BlockPlacementValidator.rayFromLook(mc, silent);
        if (live == null || !cell.equals(BlockPlacementValidator.placementCell(mc, live))
                || !webPlaceable(cell, webReach()) || !WebHitModel.clickable(mc, live, webReach())) {
            return false;
        }
        boolean placed = InteractSim.place(mc, live, silent);
        if (placed) {
            TickActionOrder.recordUse();
            lastActionAge = mc.player.age;
            RotationManager.setPostAttackHold(this, 1, RotationManager.getFinalYaw(), RotationManager.getFinalPitch());
        }
        return placed;
    }

    /** Still before the movement packet that follows the last hit or placement. */
    private boolean inActionTick() {
        return mc.player != null && mc.player.age == lastActionAge;
    }

    /** Keeps the aim request alive and reports whether the live ray currently reaches the target. */
    private boolean aimAtTarget(LivingEntity entity) {
        if (entity == null || !entity.isAlive()) return false;
        boolean stunPhase = (state == State.STUN_DELAY);
        double reachMargin = stunPhase ? 0.6 : 0.4;
        double speed = stunPhase ? aimSpeed.getValue() * 1.25 : aimSpeed.getValue();
        RotationManager.Priority priority = stunPhase ? RotationManager.Priority.HIGHEST : RotationManager.Priority.HIGH;

        if (aimMode.is("Off")) {
            // Off never turns: the strike waits for the player's own crosshair.
            return inReach(entity, reachMargin) && InteractSim.isCrosshairOn(mc, entity);
        }
        boolean silent = aimMode.is("Hidden");
        RotationManager.aimAt(this, priority,
                () -> entity.isAlive() ? entity.getBoundingBox().getCenter() : null,
                speed, imperfection(), silent);
        if (!inReach(entity, reachMargin)) return false;
        if (silent && RotationManager.isSilentRotationActive()) {
            return RotationManager.isSilentAimFacing(entity, Math.min(reach.getValue() + reachMargin, 3.0 + reachMargin));
        }
        return InteractSim.isCrosshairOn(mc, entity);
    }

    private void requestWebAim() {
        if (aimMode.is("Off")) return;
        boolean silent = aimMode.is("Hidden");
        RotationManager.aimAt(this, RotationManager.Priority.HIGHEST,
                () -> webClick == null ? null : webClick.hit().getPos(),
                placeAimSpeed.getValue(), imperfection(), silent);
    }

    /** Aim Randomness as the rotation engine's 0..1 humanization. */
    private double imperfection() {
        return MathHelper.clamp(aimImperfection.getValue() / 100.0, 0.0, 1.0);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────────

    /** The utility slot a timed wait in the current state holds, or -1. */
    private int waitSlot() {
        return switch (state) {
            case STUN_DELAY -> axeSlot;
            case SWAP_BACK -> swapBackFrom;
            case AIM_LAVA -> webSlot;
            case LAVA_PICKUP -> lavaSlot;
            case HOLD_WEB -> lavaSlot != -1 ? lavaSlot : webSlot;
            default -> -1;
        };
    }

    /** The player took the hotbar over: end the run without selecting or restoring anything. */
    private void yieldToPlayer() {
        if (ownsSelection) markUse(System.currentTimeMillis());
        ownsSelection = false;
        reset(false);
    }

    private boolean isAxeAt(int slot) {
        return mc.player != null && slot >= 0 && slot <= 8
                && mc.player.getInventory().getStack(slot).getItem() instanceof AxeItem;
    }

    private boolean isItemAt(int slot, Item item) {
        return mc.player != null && slot >= 0 && slot <= 8 && mc.player.getInventory().getStack(slot).isOf(item);
    }

    /**
     * The server reported this target's shield broken (status 30) within the disable time, and the use we
     * see began before that: the target's client has not caught up yet, so the shield is not really up.
     */
    private boolean shieldDisabledRecently(LivingEntity t, long now) {
        if (t == null || t.getId() != disabledTargetId) return false;
        long since = now - disabledAtMs;
        if (since < 0L || since > SHIELD_DISABLE_MS) return false;
        return (long) t.getItemUseTime() * 50L >= since;
    }

    private void selectSlot(int slot) {
        SlotSync.select(mc, slot);
    }

    private int selectedSlot() {
        if (mc.player == null) return 0;
        return ((PlayerInventoryAccessor) mc.player.getInventory()).getSelectedSlot();
    }

    private void suppressAttack() {
        if (mc.options != null && mc.options.attackKey != null && !SystemInputSimulator.isBindingSimulatedPressed(mc.options.attackKey)) {
            while (mc.options.attackKey.wasPressed()) { }
            mc.options.attackKey.setPressed(false);
        }
    }

    private void reset(boolean restoreSlot) {
        if (ownsSelection) markUse(System.currentTimeMillis());
        // A run that just hit or placed keeps its aim until that tick's movement packet is out;
        // onTick releases it afterwards. Disabling still releases it at once (releaseRuntimeState).
        if (inActionTick()) {
            aimReleasePending = true;
        } else {
            aimReleasePending = false;
            RotationManager.stop(this);
        }
        if (pendingSequence != null && !pendingSequence.isComplete()) {
            SubTickSequencer.cancelOwner(this);
        }
        pendingSequence = null;
        pendingResult = false;
        // No restore when no axe hit went out: a slot change back without an attack after ours (see toSwapBack).
        if (restoreSlot && ownsSelection && breakHitMs != 0L && (switchBack.isEnabled() || webSlot != -1 || lavaSlot != -1)) {
            int ret = resolveReturnSlot();
            if (ret >= 0 && ret <= 8 && mc.player != null) {
                applySwapBack(ret);
            }
        }
        state = State.IDLE;
        deadlineMs = 0L;
        startedMs = 0L;
        target = null;
        axeSlot = -1;
        webSlot = -1;
        lavaSlot = -1;
        landingLava = false;
        pendingLandingCell = null;
        lavaSupport = null;
        placedLavaCell = null;
        lavaStageMs = 0L;
        lavaPlacedMs = 0L;
        originalSlot = -1;
        swapBackFrom = -1;
        breakHitMs = 0L;
        shieldHitClientSide = false;
        kbPending = false;
        kbModel = null;
        planClock = Integer.MIN_VALUE;
        webPlan = null;
        webClick = null;
        lastWebCell = null;
        webPathCovered = false;
        executingAttack = false;
        ownsSelection = false;
        stunSequence.clear();
        lastAxeHitNanos = 0L;
        stunAttempts = 0;
        lastConfirmedBreakMs = 0L;
        confirmedBreakTargetId = Integer.MIN_VALUE;
    }

    private boolean weaponConditionMet() {
        if (mc.player == null) return true;
        ItemStack main = mc.player.getMainHandStack();
        boolean sword = CombatItemUtil.isSword(main);
        boolean axe = CombatItemUtil.isAxe(main);
        return switch (weaponMode.getCurrentMode()) {
            case "Sword" -> sword;
            case "Sword + Axe" -> sword || axe;
            case "Anything" -> true;
            default -> sword;
        };
    }

    private int findAxe() {
        if (mc.player == null) return -1;
        for (int i = 0; i < 9; i++) {
            if (mc.player.getInventory().getStack(i).getItem() instanceof AxeItem) return i;
        }
        return -1;
    }

    private int findSword() {
        if (mc.player == null) return -1;
        for (int i = 0; i < 9; i++) {
            if (CombatItemUtil.isSword(mc.player.getInventory().getStack(i))) return i;
        }
        return -1;
    }

    private int findWeapon() {
        if (mc.player == null) return -1;
        int sword = findSword();
        if (sword != -1) return sword;
        for (int i = 0; i < 9; i++) {
            if (!isUtilitySlot(i) && CombatItemUtil.isWeapon(mc.player.getInventory().getStack(i))) return i;
        }
        return -1;
    }

    private int findWeb() {
        if (mc.player == null) return -1;
        for (int i = 0; i < 9; i++) {
            if (mc.player.getInventory().getStack(i).getItem() == Items.COBWEB) return i;
        }
        return -1;
    }

    private boolean inReach(Entity e) {
        return inReach(e, 0.0);
    }

    private boolean inReach(Entity e, double extraMargin) {
        if (e == null || mc.player == null) return false;
        double maxReach = Math.min(reach.getValue(), 3.0) + extraMargin;
        if (mc.crosshairTarget instanceof EntityHitResult ehr && ehr.getEntity() == e) {
            double distSq = mc.player.getEyePos().squaredDistanceTo(ehr.getPos());
            if (distSq <= maxReach * maxReach) {
                return true;
            }
        }
        Vec3d eye = mc.player.getEyePos();
        Box box = e.getBoundingBox().expand(e.getTargetingMargin());
        double cx = MathHelper.clamp(eye.x, box.minX, box.maxX);
        double cy = MathHelper.clamp(eye.y, box.minY, box.maxY);
        double cz = MathHelper.clamp(eye.z, box.minZ, box.maxZ);
        return eye.squaredDistanceTo(cx, cy, cz) <= maxReach * maxReach;
    }

    /**
     * Web Landing applies: a landing mode is chosen, its keybind switch is on, and the stun it follows
     * is active (the setting is hidden without it, so it must not act without it either).
     */
    private boolean shouldLandOn(LivingEntity t) {
        if (webMode.is("Off") || !landingEnabled.isEnabled() || stunChance.getValue() <= 0) return false;
        if (!(t instanceof PlayerEntity) || !t.isAlive()) return false;
        return findWeb() != -1 || (wantsLava() && findLavaBucket() != -1);
    }

    private int findLavaBucket() {
        if (mc.player == null) return -1;
        for (int i = 0; i < 9; i++) {
            if (mc.player.getInventory().getStack(i).isOf(Items.LAVA_BUCKET)) return i;
        }
        return -1;
    }

    // ── Lava (Stun Lava / Stun Web Lava) ─────────────────────────────────────────

    private boolean inLavaStage() {
        return state == State.AIM_LAVA || state == State.LAVA_PENDING
                || state == State.LAVA_PICKUP || state == State.PICKUP_PENDING
                || (state == State.WEB_PENDING && landingLava);
    }

    /**
     * Stun Web Lava: aims at the top face of the web just placed and pours lava into the cell above
     * it, once the live ray reaches that face. Starts on a tick after the web's (a new aim target).
     */
    private void processLavaPour(long now) {
        if (inActionTick()) {
            enter(State.AIM_LAVA, now, 0L);
            return;
        }
        if (target == null || !target.isAlive() || mc.player == null || mc.world == null || lavaSupport == null
                || lavaSlot < 0 || !mc.player.getInventory().getStack(lavaSlot).isOf(Items.LAVA_BUCKET)
                || now - lavaStageMs > LAVA_POUR_TIMEOUT_MS
                || !mc.world.getBlockState(lavaSupport).isOf(Blocks.COBWEB)) {
            toSwapBack(now);
            return;
        }
        BlockPos cell = lavaSupport.up();
        // The top face is only reachable from above it.
        if (!lavaCellOpen(cell) || !lavaSafe(cell) || mc.player.getEyeY() <= lavaSupport.getY() + 1.02) {
            toSwapBack(now);
            return;
        }
        boolean silent = aimMode.is("Hidden");
        final Vec3d point = new Vec3d(lavaSupport.getX() + 0.5, lavaSupport.getY() + 0.98, lavaSupport.getZ() + 0.5);
        if (!aimMode.is("Off")) {
            RotationManager.aimAt(this, RotationManager.Priority.HIGHEST, () -> point, placeAimSpeed.getValue(), imperfection(), silent);
        }
        final BlockPos support = lavaSupport;
        BlockHitResult live = BlockPlacementValidator.rayFromLook(mc, silent);
        if (live == null || !live.getBlockPos().equals(support) || live.getSide() != Direction.UP
                || mc.player.getEyePos().squaredDistanceTo(live.getPos()) > sq(mc.player.getBlockInteractionRange())) {
            return;
        }
        state = State.LAVA_PENDING;
        pendingLandingCell = cell;
        dispatchInTick(lavaSlot, () -> placeLava(cell, support));
        resolveLavaPour(now);
    }

    private void resolveLavaPour(long now) {
        if (pendingSequence != null && !pendingSequence.isComplete()) {
            return;
        }
        if (takePendingResult()) {
            enter(State.LAVA_PICKUP, now, sampleMs(lavaPickupDelay));
        } else if (now - lavaStageMs <= LAVA_POUR_TIMEOUT_MS) {
            enter(State.AIM_LAVA, now, 0L);
        } else {
            toSwapBack(now);
        }
    }

    /** After the pickup delay, aims the emptied bucket at the placed source and scoops it on a later tick. */
    private void processLavaPickup(long now) {
        if (mc.player == null || mc.world == null || placedLavaCell == null || lavaSlot < 0) {
            toSwapBack(now);
            return;
        }
        FluidState fluid = mc.world.getFluidState(placedLavaCell);
        if (!fluid.isOf(Fluids.LAVA) || !fluid.isStill() || now - lavaPlacedMs > LAVA_PICKUP_TIMEOUT_MS) {
            // Gone (scooped, replaced, flowed) or out of time: nothing more to do here.
            toSwapBack(now);
            return;
        }
        if (!mc.player.getInventory().getStack(lavaSlot).isOf(Items.BUCKET)) {
            toSwapBack(now);
            return;
        }
        if (inActionTick()) {
            // The pour's aim is kept for the rest of its tick; the scoop is a new aim and a new use,
            // both from the next tick on.
            return;
        }
        boolean silent = aimMode.is("Hidden");
        final Vec3d center = Vec3d.ofCenter(placedLavaCell);
        if (!aimMode.is("Off")) {
            RotationManager.aimAt(this, RotationManager.Priority.HIGHEST, () -> center, placeAimSpeed.getValue(), imperfection(), silent);
        }
        if (!placedLavaCell.equals(liveFluidCell(silent))) {
            return;
        }
        state = State.PICKUP_PENDING;
        dispatchInTick(lavaSlot, this::pickupLava);
        resolveLavaPickup(now);
    }

    private void resolveLavaPickup(long now) {
        if (pendingSequence != null && !pendingSequence.isComplete()) {
            return;
        }
        boolean used = takePendingResult();
        if (used && mc.player != null && lavaSlot >= 0 && mc.player.getInventory().getStack(lavaSlot).isOf(Items.LAVA_BUCKET)) {
            placedLavaCell = null;
            enter(State.HOLD_WEB, now, 0L);
            return;
        }
        enter(State.LAVA_PICKUP, now, 0L);
    }

    /**
     * Pours the held lava bucket so the source lands in {@code cell}. With {@code requiredSupport}
     * the live ray must be on that block's top face (Stun Web Lava); otherwise any face whose
     * neighbour is {@code cell}.
     */
    private boolean placeLava(BlockPos cell, BlockPos requiredSupport) {
        if (cell == null || mc.player == null || mc.world == null || mc.interactionManager == null) return false;
        if (!mc.player.getMainHandStack().isOf(Items.LAVA_BUCKET)) return false;
        boolean silent = aimMode.is("Hidden");
        BlockHitResult live = BlockPlacementValidator.rayFromLook(mc, silent);
        if (live == null || live.getType() != HitResult.Type.BLOCK) return false;
        if (requiredSupport != null && (!live.getBlockPos().equals(requiredSupport) || live.getSide() != Direction.UP)) {
            return false;
        }
        if (!cell.equals(live.getBlockPos().offset(live.getSide())) || !lavaCellOpen(cell) || !lavaSafe(cell)
                || mc.player.getEyePos().squaredDistanceTo(live.getPos()) > sq(mc.player.getBlockInteractionRange())) {
            return false;
        }
        if (!useBucket(live)) return false;
        TickActionOrder.recordUse();
        lastActionAge = mc.player.age;
        RotationManager.setPostAttackHold(this, 1, RotationManager.getFinalYaw(), RotationManager.getFinalPitch());
        placedLavaCell = cell;
        lavaPlacedMs = System.currentTimeMillis();
        return true;
    }

    private boolean pickupLava() {
        if (mc.player == null || mc.world == null || placedLavaCell == null) return false;
        if (!mc.player.getMainHandStack().isOf(Items.BUCKET)) return false;
        boolean silent = aimMode.is("Hidden");
        if (!placedLavaCell.equals(liveFluidCell(silent))) return false;
        BlockHitResult crosshairBlock = BlockPlacementValidator.rayFromLook(mc, silent);
        if (!useBucket(crosshairBlock)) return false;
        TickActionOrder.recordUse();
        lastActionAge = mc.player.age;
        RotationManager.setPostAttackHold(this, 1, RotationManager.getFinalYaw(), RotationManager.getFinalPitch());
        return true;
    }

    /**
     * A right click with a bucket as vanilla sends it: the block use for the block under the
     * crosshair (buckets pass on it), then the item use, whose own ray decides where the fluid goes.
     * The block use is skipped when a hitbox covers the face (vanilla's crosshair would be on that
     * entity, which a player does not react to) or when that block would open something.
     */
    private boolean useBucket(BlockHitResult blockHit) {
        if (mc.player == null || mc.world == null || mc.interactionManager == null || mc.currentScreen != null) return false;
        ActionResult result = ActionResult.PASS;
        Vec3d eye = mc.player.getEyePos();
        if (blockHit != null && blockHit.getType() == HitResult.Type.BLOCK
                && eye.squaredDistanceTo(blockHit.getPos()) <= sq(mc.player.getBlockInteractionRange())
                && !WebTrapPlanner.entityOccludes(mc, eye, blockHit.getPos(), 0.0)) {
            BlockState s = mc.world.getBlockState(blockHit.getBlockPos());
            if (!mc.player.isSneaking() && !s.isReplaceable() && WebTrapPlanner.isInteractive(mc.world, blockHit.getBlockPos(), s)) {
                return false;
            }
            result = mc.interactionManager.interactBlock(mc.player, Hand.MAIN_HAND, blockHit);
        }
        if (!result.isAccepted()) {
            result = mc.interactionManager.interactItem(mc.player, Hand.MAIN_HAND);
        }
        if (!result.isAccepted()) return false;
        mc.player.swingHand(Hand.MAIN_HAND);
        if (mc instanceof MinecraftClientAccessor acc) acc.setItemUseCooldown(4);
        return true;
    }

    /** The lava source cell the bucket's own (source-only) ray reaches from the angles the use is sent with. */
    private BlockPos liveFluidCell(boolean silent) {
        if (mc.player == null || mc.world == null) return null;
        // The wire rotation, whichever module's silent aim is live.
        boolean useSilent = RotationManager.isSilentRotationActive();
        float yaw = useSilent ? RotationManager.getFinalYaw() : mc.player.getYaw();
        float pitch = useSilent ? RotationManager.getFinalPitch() : mc.player.getPitch();
        Vec3d eye = mc.player.getEyePos();
        Vec3d end = eye.add(Vec3d.fromPolar(pitch, yaw).multiply(mc.player.getBlockInteractionRange()));
        BlockHitResult hit = mc.world.raycast(new RaycastContext(eye, end,
                RaycastContext.ShapeType.OUTLINE, RaycastContext.FluidHandling.SOURCE_ONLY, mc.player));
        if (hit == null || hit.getType() != HitResult.Type.BLOCK) return null;
        return mc.world.getFluidState(hit.getBlockPos()).isOf(Fluids.LAVA) ? hit.getBlockPos() : null;
    }

    // ── Delay draws ───────────────────────────────────────────────────────────────

    private static long sampleMs(SliderSetting slider) {
        return Math.max(0L, Math.round(slider.sample()));
    }

    private static double sq(double v) {
        return v * v;
    }

    private void resampleAttemptDelays() {
        plannedReactionMs = sampleMs(reactionDelay);
        plannedAttemptGapMs = sampleMs(attemptGap);
    }

    private void markAttempt(long now) {
        lastAttemptMs = now;
        resampleAttemptDelays();
    }

    private void markUse(long now) {
        lastUseMs = now;
        plannedReuseMs = sampleMs(reuseCooldown);
    }

    public static boolean isCollidingWithCobweb(LivingEntity entity) {
        return WebPlacementHelper.isCollidingWithCobweb(entity);
    }

    private LivingEntity acquireTarget() {
        if (mc.world == null || mc.player == null) return null;
        try {
            if (mc.gameRenderer != null && mc.getCameraEntity() != null) {
                mc.gameRenderer.updateCrosshairTarget(1.0f);
            }
        } catch (Throwable ignored) {
        }
        if (mc.crosshairTarget instanceof EntityHitResult ehr
                && ehr.getEntity() instanceof LivingEntity le && isValid(le)) {
            return le;
        }
        Vec3d eye = mc.player.getEyePos();
        double maxReach = Math.min(reach.getValue(), 3.0);
        Vec3d end = eye.add(mc.player.getRotationVec(1.0f).multiply(maxReach));
        Box box = mc.player.getBoundingBox().stretch(mc.player.getRotationVec(1.0f).multiply(maxReach)).expand(1.0);
        LivingEntity best = null;
        double bestSq = Double.MAX_VALUE;
        for (Entity e : mc.world.getOtherEntities(mc.player, box, x -> x instanceof LivingEntity le && isValid(le))) {
            var hit = e.getBoundingBox().expand(e.getTargetingMargin()).raycast(eye, end);
            if (hit.isPresent()) {
                double d = eye.squaredDistanceTo(hit.get());
                if (d < bestSq) { bestSq = d; best = (LivingEntity) e; }
            }
        }
        return best;
    }

    private boolean isValid(LivingEntity e) {
        if (e == null || e == mc.player || !e.isAlive() || !(e instanceof PlayerEntity player) || !inReach(e)) {
            return false;
        }
        if (player.isSpectator() || player.isCreative()
                || !interstellarTikiTiks.apexx.client.util.combat.StickyTargets.allows(this, player)
                || interstellarTikiTiks.apexx.client.friend.FriendManager.getInstance().isFriend(player.getUuid())) {
            return false;
        }
        for (interstellarTikiTiks.apexx.client.module.implementation.player.BotFilterProvider botFilter
                : ModuleAccess.getEnabledHooks(interstellarTikiTiks.apexx.client.module.implementation.player.BotFilterProvider.class)) {
            if (botFilter.isBot(player)) {
                return false;
            }
        }
        return true;
    }

    /**
     * Whether the target's raised shield faces us. {@code LivingEntity.getDamageBlockedAmount}
     * (1.21.11) compares the horizontal direction to the attacker with the target's head yaw at
     * pitch 0 and blocks within the shield's horizontal blocking angle (90 degrees), i.e. a dot
     * product of at least 0.
     */
    private boolean isShieldFacingUs(LivingEntity t) {
        if (t == null || mc.player == null) return false;
        boolean shielding = t.isBlocking() || (t.isUsingItem()
                && (t.getActiveItem().isOf(Items.SHIELD) || t.getActiveItem().getItem() instanceof ShieldItem));
        if (!shielding) return false;
        Vec3d look = Vec3d.fromPolar(0.0f, t.getHeadYaw());
        Vec3d toAttacker = new Vec3d(mc.player.getX() - t.getX(), 0.0, mc.player.getZ() - t.getZ());
        if (toAttacker.lengthSquared() < 1.0e-6) return true;
        return toAttacker.normalize().dotProduct(look) >= 0.0;
    }

    /**
     * Ticks until a hit sent now meets a shield the server counts as blocking; 0 once it does. The
     * local use time trails the server's by about one round trip (the start reaches us half a trip
     * late and our hit reaches the server half a trip later), so that trip is added back before it
     * is compared with the item's block delay.
     */
    private int ticksUntilServerBlocks(LivingEntity t) {
        if (t == null || !t.isUsingItem()) return Integer.MAX_VALUE;
        BlocksAttacksComponent blocks = t.getActiveItem().get(DataComponentTypes.BLOCKS_ATTACKS);
        if (blocks == null) return Integer.MAX_VALUE;
        int serverUseTicks = t.getItemUseTime() + (int) Math.floor(WebHitModel.rttTicks(mc));
        return Math.max(0, blocks.getBlockDelayTicks() - serverUseTicks);
    }

    private boolean hasMace() {
        if (mc.player == null) return false;
        if (CombatItemUtil.isMace(mc.player.getMainHandStack())) return true;
        for (int i = 0; i < 9; i++) {
            if (CombatItemUtil.isMace(mc.player.getInventory().getStack(i))) return true;
        }
        return false;
    }

    /**
     * Hands attack permission to AutoMace (auto or Mace Swap Only) for the whole falling window:
     * once the player has fallen past the smash floor with a mace in the hotbar, AutoMace decides
     * between smash and stun slam, so this module never starts its own axe swap there.
     */
    private boolean isMaceMomentActive() {
        if (mc.player == null) return true;
        AutoMace am = ModuleAccess.getModule(AutoMace.class);
        if (am != null && am.isEnabled() && am.ownsAttackWindow()) return true;
        if (hasMace() && am != null && am.isEnabled()
                && !mc.player.isOnGround() && mc.player.fallDistance > 1.5f) return true;
        CombatSwapLockEvent lock = new CombatSwapLockEvent();
        EventBus.post(lock);
        return lock.isAutoAttackLocked() || lock.isItemSwapLocked();
    }
}
