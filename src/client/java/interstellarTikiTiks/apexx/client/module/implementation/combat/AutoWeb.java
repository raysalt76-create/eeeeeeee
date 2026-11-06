package interstellarTikiTiks.apexx.client.module.implementation.combat;

import interstellarTikiTiks.apexx.client.util.combat.ModuleDelays;
import interstellarTikiTiks.apexx.client.event.EventBus;
import interstellarTikiTiks.apexx.client.event.EventTarget;
import interstellarTikiTiks.apexx.client.event.impl.CombatSwapLockEvent;
import interstellarTikiTiks.apexx.client.friend.FriendManager;
import interstellarTikiTiks.apexx.client.module.BooleanSetting;
import interstellarTikiTiks.apexx.client.module.Category;
import interstellarTikiTiks.apexx.client.module.ModeSetting;
import interstellarTikiTiks.apexx.client.module.Module;
import interstellarTikiTiks.apexx.client.module.SliderSetting;
import interstellarTikiTiks.apexx.client.module.SubCategory;
import interstellarTikiTiks.apexx.client.module.hooks.AttackTargetAware;
import interstellarTikiTiks.apexx.client.util.Notifier;
import interstellarTikiTiks.apexx.client.util.RotationManager;
import interstellarTikiTiks.apexx.client.util.combat.BlockPlacementValidator;
import interstellarTikiTiks.apexx.client.util.combat.CombatItemUtil;
import interstellarTikiTiks.apexx.client.util.combat.InteractSim;
import interstellarTikiTiks.apexx.client.util.combat.MeleeMechanics;
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
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

import java.util.UUID;

/**
 * AutoWeb — traps enemies in cobweb.
 *
 * <p><b>Trigger</b> decides when: Proximity webs the nearest enemy; On Hit webs after each confirmed
 * hit; Next Hit is armed by the Keybind and webs on the next confirmed hit after the press (never a
 * hit that was already on its way); One Shot webs the opponent as soon as the Keybind is pressed,
 * wherever they are. <b>Coverage</b> decides where: Feet, Head (with a feet web under it first when
 * the head cell has nothing to place against), or Full (feet, then head).
 *
 * <p><b>Prediction</b> ({@link WebTrapPlanner}) works from the server-reported position, leads by the
 * round trip so the web exists by the time the target gets there, and only fires on a cell the input
 * hypotheses agree on and the target is still in if the web arrives late (jitter-measured spread).
 * After a hit, until the knockback shows up in the target's position updates, the launch is computed
 * from vanilla's knockback rules ({@link WebHitModel}: direction, sprint, Knockback level, armor
 * knockback resistance) and the target's ping. Engagement windows stretch with the round trip, and
 * the fire threshold relaxes with the round trip and with every tick spent aiming
 * ({@link WebTrapPlanner#fireConfidence}), so a laggy link or a weaving target still gets a web
 * instead of none. When no scored cell can be clicked, the cell the target's feet will be in is
 * webbed directly ({@link WebTrapPlanner#directClick}). On a laggy link an On Hit / Next Hit web on
 * an unshielded target fires on the knockback estimate without waiting a round trip for the damage
 * echo. A placed web the server did not keep is placed again (bounded), and a kept web the target
 * was not caught by within the catch window is followed by one more.
 *
 * <p><b>Action</b>: aim at a face a vanilla client could click (no hitbox in front of it). The place
 * fires only when the live ray places into a confident cell, as a real slot swap followed by one
 * placement later in the same tick; the aim request stays alive for the rest of that tick. The slot
 * goes back through {@link SlotSync#restore} on a later tick, only while this module still owns it.
 */
public class AutoWeb extends Module implements AttackTargetAware, interstellarTikiTiks.apexx.client.module.hooks.ClickSimulating {

    private final ModeSetting trigger = new ModeSetting("Trigger", "Proximity", "Proximity", "On Hit", "Next Hit", "One Shot")
            .setDescription("When a web goes down. Proximity: webs the nearest enemy in range. On Hit: webs the spot the target is knocked to after each confirmed hit. Next Hit: press the Keybind and your next confirmed hit webs. One Shot: press the Keybind to web the opponent right now.");
    private final ModeSetting coverage = new ModeSetting("Coverage", "Feet", "Feet", "Head", "Full")
            .setDescription("Which cells get webbed. Feet: the lowest cell the target passes through (most reliable). Head: the cell their head will be in, placing a feet web first if there is nothing to place against. Full: feet, then head, for a full trap.");
    private final ModeSetting activation = new ModeSetting("Activation", "Toggle", "Toggle", "Hold", "Keybind Held")
            .setDescription("For Proximity and On Hit. Toggle: works whenever the module is on. Hold: only while you hold the use key. Keybind Held: only while you hold the Keybind.");
    private final BooleanSetting keybind = new BooleanSetting("Keybind", false).setBindable(true)
            .setDescription("Bind a key here. Next Hit: press to arm, press again to disarm. One Shot: press to web. Keybind Held: hold to allow webbing.");
    private final SliderSetting armWindow = new SliderSetting("Arm Window", 6.0, 1.0, 15.0, 0)
            .setDescription("Next Hit: how many seconds the web stays armed after you press the key before it gives up.");
    private final ModeSetting rotationMode = new ModeSetting("Rotation", "Smooth", "Smooth", "Silent", "None")
            .setDescription("Smooth turns your real camera onto the placement face. Silent turns only what the server sees. None never turns: it places only when your own crosshair is already on a usable face.");
    private final SliderSetting strength = new SliderSetting("Aim Strength", 50, 1, 100, 0)
            .setDescription("How quickly the camera turns onto the placement face (1-100). Higher is faster but less natural.");
    private final BooleanSetting weaponsOnly = new BooleanSetting("Weapons Only", true)
            .setDescription("On Hit only: only webs after hits landed with a sword, axe, mace or spear.");
    private final SliderSetting cooldownMs = new SliderSetting("Cooldown (ms)", 1000, 300, 3000, 0)
            .withJitter(120)
            .setDescription("Proximity and On Hit: how long, in ms, the same target is left alone after being webbed, so one engagement gets one web instead of a spam of them.");
    private final SliderSetting reactionDelay = new SliderSetting("Reaction Delay (ms)", 50, 0, 1000, 0)
            .withJitter(15)
            .setDescription("Wait after a new engagement starts (target picked, hit landed, key pressed) before the first web may be placed. Aiming starts right away.")
            .setSection("Delays");
    private final SliderSetting swapBackDelay = new SliderSetting("Swap Back Delay (ms)", 50, 0, 1000, 0)
            .withJitter(15)
            .setDescription("Wait after the last web before your original hotbar slot is reselected. 0 = the next tick (never the same tick as the placement).")
            .setSection("Delays")
            .setDependsOn("Swap Back");
    private final SliderSetting stageHold = new SliderSetting("Stage Hold (ms)", 300, 0, 1000, 0)
            .withJitter(40)
            .setDescription("Full / Head with support: how long the cobweb slot stays selected waiting for the next web of the same engagement before your slot goes back.")
            .setSection("Delays");
    private final BooleanSetting switchBack = new BooleanSetting("Swap Back", true)
            .setDescription("Puts you back on the hotbar slot you were holding after the web is placed, unless you switched slots yourself.");
    private final BooleanSetting ignoreFriends = new BooleanSetting("Ignore Friends", true)
            .setDescription("Never targets players on your friends list and never webs a cell a friend is standing in.");
    private final BooleanSetting silentActions = new BooleanSetting("Packet Clicks", false)
            .setDescription("Places through the silent interaction path instead of your visible crosshair. Off (default) clicks exactly where you see.");
    private final SliderSetting reach = new SliderSetting("Reach", 3.5, 1.0, 4.5, 1)
            .setDescription("Farthest block face, in blocks, the web can be placed against. Never beyond vanilla's 4.5 block reach.");
    private final SliderSetting targetRange = new SliderSetting("Target Range", 5.0, 2.0, 8.0, 1)
            .setDescription("How far away, in blocks, enemies are looked for. Farther targets are only webbed once they come within Reach.");
    private final SliderSetting leadOffset = new SliderSetting("Lead Offset", 0.0, -2.0, 4.0, 0)
            .setDescription("Adjusts how far ahead of a moving target the web is placed, in ticks, on top of the automatic ping lead. Raise it if webs land behind targets, lower it if they land ahead.");
    private final BooleanSetting noPlaceInLiquid = new BooleanSetting("No Liquid Place", true)
            .setDescription("Never places a cobweb into a water, lava or other fluid cell (the fluid washes it away). You can still web while standing in water — only the target cell is checked.");

    /** On Hit / Next Hit: how long after the swing a web is still worth placing. */
    private static final int HIT_ENGAGE_TICKS = 32;
    /** One Shot: how long a press keeps trying. */
    private static final int ONE_SHOT_TICKS = 20;
    /** Aim only at a cell at least this many hypotheses (by weight) agree on. */
    private static final double AIM_CONFIDENCE = 0.3;
    private static final double FIRE_CONFIDENCE = 0.55;
    /** Before the knockback is visible the start state is computed, so the hypotheses must all agree. */
    private static final double FIRE_CONFIDENCE_ESTIMATE = 0.75;
    private static final double ONE_SHOT_CONFIDENCE = 0.3;
    /** Hitbox inflation while choosing a face, because entities move before the aim arrives. */
    private static final double PLAN_MARGIN = 0.08;
    private static final int MAX_CLICK_CELLS = 6;
    /** Re-placements allowed when the server rejects a placed web. */
    private static final int MAX_REPLACEMENTS = 2;
    /** Extra webs allowed when a kept web did not catch the target within the catch window. */
    private static final int MAX_MISS_RETRIES = 1;
    /** Ticks after the last web is confirmed in which the target must be caught, on top of the round trip. */
    private static final int CATCH_WINDOW_TICKS = 10;
    /** On Hit: from this round trip (ticks) on, a hit on an unshielded target fires before the server confirms it. */
    private static final double EARLY_FIRE_RTT_TICKS = 3.0;

    private enum Kind { PROXIMITY, HIT, ONE_SHOT }

    /** {@code direct}: from {@link WebTrapPlanner#directClick}, not a scored cell of the plan. */
    private record Choice(Cell cell, Click click, boolean support, boolean direct) {
    }

    private record Shot(BlockHitResult hit, BlockPos cell, boolean support) {
    }

    private final WebTrapPlanner planner = new WebTrapPlanner();

    // Slot ownership
    private int originalSlot = -1;
    private int expectedSlot = -1;
    private Object boundPlayer;
    private Object boundWorld;
    private boolean pendingRestore = false;
    private int restoreTicks = 0;
    private int holdTicks = 0;
    /** Ticks the web slot is kept between the webs of one engagement (Full, Head with support). */
    private int stageHoldTicks = 0;
    /** No web of the current engagement before this time (Reaction Delay). */
    private long reactionReadyMs = 0L;
    private boolean placePending = false;

    // Engagement
    private Kind kind = null;
    private PlayerEntity target = null;
    private int deadlineClock = 0;
    private boolean feetDone = false;
    private boolean headDone = false;
    private boolean supportPlaced = false;
    private int placedCount = 0;
    private Click aimClick = null;
    private BlockPos lastCell = null;
    private UUID lastWebUuid = null;
    private long lastWebMs = 0L;
    private long webCooldownMs = 0L;
    /** The web just placed, until the server has had time to reject it. */
    private BlockPos placedCell = null;
    private boolean placedHeadWeb = false;
    private int placedClock = 0;
    private int replacements = 0;
    /** Ticks spent aiming (with firing allowed) since the last web; relaxes the fire threshold. */
    private int aimedTicks = 0;
    private int aimedClock = Integer.MIN_VALUE;
    /** Clock by which the placed webs must have caught the target, or MIN_VALUE before it is set. */
    private int catchDeadline = Integer.MIN_VALUE;
    private int missRetries = 0;

    // Hit
    private int attackClock = 0;
    private int hurtAtAttack = 0;
    private boolean blockedAtAttack = false;
    private boolean confirmed = false;
    private boolean fromArm = false;
    private Vec3d attackerPos = Vec3d.ZERO;
    private float attackerYaw = 0.0F;
    /** Knockback added by the swing beyond the base 0.4: sprint, Knockback level, attack knockback. */
    private double knockbackBonus = 0.0;

    // Next Hit / One Shot key
    private boolean armed = false;
    private int armClock = 0;
    private boolean lastKey = false;

    public AutoWeb() {
        super("AutoWeb", "Places cobwebs on nearby opponents, or exactly where a hit opponent is about to land, predicting their knockback and your ping so they are caught mid-combo. Every placement is a real aimed click.", Category.COMBAT);
        setSubCategory(SubCategory.SMP);
        keybind.setKeyHoldMode(true);
        activation.setVisibility(() -> trigger.is("Proximity") || trigger.is("On Hit"));
        armWindow.setVisibility(() -> trigger.is("Next Hit"));
        weaponsOnly.setVisibility(() -> trigger.is("On Hit"));
        cooldownMs.setVisibility(() -> trigger.is("Proximity") || trigger.is("On Hit"));
        strength.setVisibility(() -> !rotationMode.is("None"));
        addSetting(trigger);
        addSetting(coverage);
        addSetting(activation);
        addSetting(keybind);
        addSetting(armWindow);
        addSetting(rotationMode);
        addSetting(strength);
        addSetting(weaponsOnly);
        addSetting(cooldownMs);
        addSetting(reactionDelay);
        addSetting(swapBackDelay);
        addSetting(stageHold);
        addSetting(switchBack);
        addSetting(ignoreFriends);
        addSetting(silentActions);
        addSetting(reach);
        addSetting(targetRange);
        addSetting(leadOffset);
        addSetting(noPlaceInLiquid);
    }

    @Override
    public void onEnable() {
        EventBus.register(this);
        clearEngagement();
        planner.clear();
        lastWebUuid = null;
        lastWebMs = 0L;
        armed = false;
        lastKey = keybind.isEnabled();
    }

    @Override
    public void onDisable() {
        EventBus.unregister(this);
        armed = false;
    }

    @Override
    protected void onRuntimeStateReleased() {
        restoreSlot();
        clearEngagement();
        planner.clear();
        lastWebUuid = null;
        lastWebMs = 0L;
    }

    @EventTarget
    public void onCombatSwapLock(CombatSwapLockEvent event) {
        if (event.isRequestedBy(this)) return;
        if (pendingRestore || placePending) {
            event.setItemSwapLocked(true);
            event.setAutoAttackLocked(true);
        }
    }

    // ── triggers ──────────────────────────────────────────────────────────────────

    @Override
    public void onAttack(Entity attacked) {
        if (!isEnabled() || mc.player == null || mc.world == null) return;
        if (!(attacked instanceof PlayerEntity victim) || !isValidTarget(victim)) return;
        if (pendingRestore || placePending || (kind == Kind.HIT && placedCount > 0) || kind == Kind.ONE_SHOT) return;
        // A swing into damage immunity deals no knockback; there is no launch to web.
        if (MeleeMechanics.isInDamageImmunity(victim)) return;

        if (trigger.is("On Hit")) {
            if (!activationMet() || !weaponConditionMet() || recentlyWebbed(victim)) return;
            startHit(victim, false);
        } else if (trigger.is("Next Hit") && armed) {
            // armed is only set by a key press seen on an earlier tick, so the hit that was
            // already on its way when the key went down never counts.
            startHit(victim, true);
        }
    }

    private void startHit(PlayerEntity victim, boolean viaArm) {
        clearEngagement();
        kind = Kind.HIT;
        target = victim;
        attackClock = planner.clock();
        deadlineClock = attackClock + HIT_ENGAGE_TICKS + (int) Math.round(2.0 * rttTicks());
        hurtAtAttack = victim.hurtTime;
        blockedAtAttack = victim.isBlocking();
        confirmed = false;
        fromArm = viaArm;
        attackerPos = mc.player.getEntityPos();
        attackerYaw = serverYaw();
        MeleeMechanics.AttackRecord rec = MeleeMechanics.lastAttack();
        boolean sprintKnockback = rec != null && rec.targetId() == victim.getId() && rec.sprintKnockback();
        knockbackBonus = (sprintKnockback ? 1.0 : 0.0) + WebHitModel.weaponKnockback(mc);
    }

    @Override
    public void onTick() {
        if (mc.player == null || mc.world == null || mc.interactionManager == null || mc.currentScreen != null
                || !mc.player.isAlive() || (pendingRestore && (mc.player != boundPlayer || mc.world != boundWorld))) {
            releaseRuntimeState();
            return;
        }
        planner.tick(mc, targetRange.getValue() + 6.0);
        // SubTickSequencer drops unexecuted work at tick start, before module ticks, so a queued
        // placement whose finally never ran must not keep the slot and swap lock held.
        placePending = false;
        int now = planner.clock();
        boolean pressed = pollKeyPress();
        verifyPlacement(now);

        if (pendingRestore) {
            boolean more = kind != null && !stagesDone() && isValidTarget(target) && holdTicks < stageHoldTicks;
            if (more) {
                holdTicks++;
            } else {
                if (--restoreTicks > 0) return;
                restoreSlot();
                finishIfDone();
                return;
            }
        }

        if (armed && now - armClock > (int) Math.round(armWindow.getValue() * 20.0)) {
            armed = false;
            Notifier.info("AutoWeb", "Hit web disarmed");
        }
        if (pressed) {
            onKeyPress(now);
        }

        switch (trigger.getCurrentMode()) {
            case "Proximity" -> tickProximity();
            case "One Shot" -> tickOneShot(now);
            default -> tickHit(now);
        }
    }

    private void onKeyPress(int now) {
        if (trigger.is("Next Hit")) {
            armed = !armed;
            armClock = now;
            if (armed && findHotbarSlot() == -1) {
                Notifier.warn("AutoWeb", "Armed, but there is no cobweb in your hotbar");
            } else {
                Notifier.info("AutoWeb", armed ? "Next hit webs" : "Hit web disarmed");
            }
        } else if (trigger.is("One Shot")) {
            if (kind == Kind.ONE_SHOT && placedCount > 0) return;
            if (findHotbarSlot() == -1) {
                Notifier.warn("AutoWeb", "No cobweb in your hotbar");
                return;
            }
            PlayerEntity t = oneShotTarget();
            if (t == null) {
                Notifier.warn("AutoWeb", "No target in range");
                return;
            }
            clearEngagement();
            kind = Kind.ONE_SHOT;
            target = t;
            deadlineClock = now + ONE_SHOT_TICKS + (int) Math.round(2.0 * rttTicks());
        }
    }

    private void tickProximity() {
        if (!activationMet()) {
            idle();
            return;
        }
        PlayerEntity t = nearestEnemy();
        // Finish a started Full / head-support pair on the same player.
        if (kind == Kind.PROXIMITY && placedCount > 0 && isValidTarget(target)) t = target;
        if (t == null || (placedCount == 0 && recentlyWebbed(t))) {
            idle();
            return;
        }
        if (kind != Kind.PROXIMITY || target != t) {
            clearEngagement();
            kind = Kind.PROXIMITY;
            target = t;
        }
        step(planner.observed(t), observedWebTick(t), true, FIRE_CONFIDENCE);
        finishIfDone();
    }

    private void tickOneShot(int now) {
        if (kind != Kind.ONE_SHOT) {
            if (kind != null) idle();
            return;
        }
        if (!isValidTarget(target) || now > deadlineClock) {
            finishEngagement();
            return;
        }
        step(planner.observed(target), observedWebTick(target), true, ONE_SHOT_CONFIDENCE);
        finishIfDone();
    }

    private void tickHit(int now) {
        if (kind != Kind.HIT) {
            if (kind != null) idle();
            return;
        }
        if (!isValidTarget(target) || now > deadlineClock || (!fromArm && !activationMet())) {
            idle();
            return;
        }
        if (!confirmed) {
            // The damage packet resets hurtTime; a blocked or missed swing never does.
            if (target.hurtTime > hurtAtAttack || (hurtAtAttack == 0 && target.hurtTime > 0)) {
                confirmed = true;
                if (fromArm) armed = false;
            } else if (now - attackClock > confirmTimeoutTicks()) {
                // Nothing landed. Next Hit stays armed for the next swing.
                idle();
                return;
            }
        }

        // On a laggy link the damage echo takes a whole round trip; waiting for it puts the web a
        // round trip behind the launch. A hit on a target that was not blocking fires on the
        // knockback estimate instead (still behind the stricter estimate threshold).
        boolean allowFire = confirmed || (rttTicks() >= EARLY_FIRE_RTT_TICKS && !blockedAtAttack);

        double targetPing = pingTicks(target.getUuid());
        boolean knockbackSeen = planner.impulseSince(target, attackClock)
                || now - attackClock > rttTicks() + targetPing + 6.0;
        if (knockbackSeen) {
            step(planner.observed(target), observedWebTick(target), allowFire, FIRE_CONFIDENCE);
        } else {
            // Knockback reaches the server-side path once the target's client has applied it: the web
            // sent now lands (now - attack - targetPing) ticks into that path.
            double webTick = (now - attackClock) - targetPing + 0.5 + leadOffset.getValue();
            Motion launched = WebHitModel.knockbackMotion(mc, planner, target, now, attackClock, attackerPos,
                    attackerYaw, knockbackBonus, rttTicks(), targetPing);
            step(launched, webTick, allowFire, FIRE_CONFIDENCE_ESTIMATE);
        }
        finishIfDone();
    }

    // ── one placement attempt ─────────────────────────────────────────────────────

    private void step(Motion motion, double webTick, boolean allowFire, double fireConfidence) {
        int webSlot = findHotbarSlot();
        if (webSlot == -1) {
            finishEngagement();
            return;
        }
        markCovered();
        if (stagesDone()) return;

        boolean head = headStage();
        Plan plan = planner.plan(target, motion, webTick,
                head ? WebTrapPlanner.Slice.HEAD : WebTrapPlanner.Slice.FEET, mc.player.getEntityPos());
        if (!head && plan.webbedTick() >= 0 && plan.webbedTick() <= WebTrapPlanner.HORIZON / 2) {
            // Their path already runs into a web; a second one there is wasted.
            feetDone = true;
            return;
        }

        Choice choice = choose(plan, head);
        if (choice == null && !head) {
            Click descent = planner.findDescentApexClick(mc, target, motion, webTick, reachLimit(), PLAN_MARGIN, this::placeable);
            if (descent != null) {
                choice = new Choice(new Cell(descent.cell(), 0.0, 1.0, 0), descent, false, true);
            }
        }
        if (choice == null && !head) {
            // Nothing scored is clickable (a wall, a corner, a target pressed against us): web the
            // cell their feet will be in, as a plain placement under them.
            Click direct = planner.directClick(mc, target, motion, webTick, reachLimit(), PLAN_MARGIN, this::placeable);
            if (direct != null) {
                choice = new Choice(new Cell(direct.cell(), 0.0, 1.0, 0), direct, false, true);
            }
        }
        boolean silentRotation = rotationMode.is("Silent");
        double aimBar = WebTrapPlanner.fireConfidence(AIM_CONFIDENCE, rttTicks(), aimedTicks);
        if (choice != null && (choice.direct() || choice.cell().confidence() >= aimBar)) {
            aimClick = choice.click();
            lastCell = choice.click().cell();
            requestAim();
        } else {
            aimClick = null;
            lastCell = null;
            if (!pendingRestore) RotationManager.stop(this);
        }

        if (!allowFire || placePending || System.currentTimeMillis() < reactionReadyMs
                || !InteractSim.useReady(mc) || otherModuleHoldsSwap()) return;
        if (aimClick != null && aimedClock != planner.clock()) {
            aimedClock = planner.clock();
            aimedTicks++;
        }
        Shot shot = liveShot(plan, choice, WebTrapPlanner.fireConfidence(fireConfidence, rttTicks(), aimedTicks),
                silentRotation);
        if (shot != null) {
            fire(shot, webSlot, head, silentRotation);
        }
    }

    /** Best clickable cell, sticking with last tick's cell while it is nearly as good. */
    private Choice choose(Plan plan, boolean head) {
        double best = plan.bestScore();
        double maxReach = reachLimit();
        Choice first = null;
        Choice kept = null;
        int examined = 0;
        for (Cell c : plan.cells()) {
            if (examined >= MAX_CLICK_CELLS || c.score() < best * 0.25) break;
            if (!placeable(c.pos())) continue;
            examined++;
            Click click = WebTrapPlanner.findClick(mc, c.pos(), maxReach, PLAN_MARGIN);
            if (click == null) continue;
            Choice ch = new Choice(c, click, false, false);
            if (first == null) first = ch;
            if (c.pos().equals(lastCell)) kept = ch;
        }
        if (kept != null && kept.cell().score() >= first.cell().score() * 0.8) return kept;
        if (first != null) return first;

        // Head cell with nothing to click against: a feet web under it gives the head web a face,
        // and holds the target on its own.
        if (head && !supportPlaced) {
            int tried = 0;
            for (Cell c : plan.cells()) {
                if (tried >= 3 || c.score() < best * 0.5) break;
                tried++;
                BlockPos below = c.pos().down();
                if (!placeable(below)) continue;
                Click click = WebTrapPlanner.findClick(mc, below, maxReach, PLAN_MARGIN);
                if (click != null) return new Choice(c, click, true, false);
            }
        }
        return null;
    }

    /**
     * The live ray (current authoritative rotation) as a placement, if it already fills a confident
     * cell: actual face reached, not rotation tolerance reached.
     */
    private Shot liveShot(Plan plan, Choice choice, double fireConfidence, boolean silentRotation) {
        BlockHitResult live = BlockPlacementValidator.rayFromLook(mc, silentRotation);
        if (live == null) return null;
        BlockPos cell = BlockPlacementValidator.placementCell(mc, live);
        if (cell == null) return null;
        boolean support = choice != null && choice.support() && cell.equals(choice.click().cell());
        boolean direct = choice != null && choice.direct() && cell.equals(choice.click().cell());
        if (support) {
            if (choice.cell().confidence() < fireConfidence) return null;
        } else if (!direct) {
            Cell c = plan.find(cell);
            if (c == null || c.confidence() < fireConfidence || c.score() < plan.bestScore() * 0.5) return null;
        }
        if (!placeable(cell) || !clickable(live)) return null;
        return new Shot(live, cell, support);
    }

    private void fire(Shot shot, int webSlot, boolean head, boolean silentRotation) {
        int held = mc.player.getInventory().getSelectedSlot();
        if (held != webSlot) {
            // Same-tick swap, then the single placement dispatch, both before this tick's movement
            // packet. No swap once an attack or use already left in this packet interval (checked
            // here: SlotSync.select itself does not refuse); retry next tick.
            if (!TickActionOrder.canSwitchSlot() || !SlotSync.select(mc, webSlot)) return;
            if (originalSlot == -1) originalSlot = held;
        }
        expectedSlot = webSlot;
        boundPlayer = mc.player;
        boundWorld = mc.world;
        pendingRestore = true;
        // Counted down from the next tick, so the restore never shares the placement's tick.
        restoreTicks = ModuleDelays.msToTicks(swapBackDelay.sample());
        holdTicks = 0;
        stageHoldTicks = ModuleDelays.msToTicks(stageHold.sample());
        placePending = true;

        final Kind placeKind = kind;
        final PlayerEntity placeTarget = target;
        final BlockPos cell = shot.cell();
        final boolean support = shot.support();
        final boolean dispatchSilent = silentRotation || silentActions.isEnabled();
        SubTickSequencer.queueAction(this, () -> {
            try {
                if (mc.player == null || mc.world == null || kind != placeKind || target != placeTarget
                        || !isValidTarget(placeTarget) || !mc.player.getMainHandStack().isOf(Items.COBWEB)) {
                    return;
                }
                // Re-cast at dispatch time: the same cell, nothing in front of the face.
                BlockHitResult live = BlockPlacementValidator.rayFromLook(mc, silentRotation);
                if (live == null || !cell.equals(BlockPlacementValidator.placementCell(mc, live))
                        || !placeable(cell) || !clickable(live)) {
                    return;
                }
                if (InteractSim.place(mc, live, dispatchSilent)) {
                    // Keep the reached angles for the rest of this tick; a new aim starts next tick.
                    RotationManager.setPostAttackHold(this, 1, RotationManager.getFinalYaw(), RotationManager.getFinalPitch());
                    onPlaced(placeTarget, head && !support, cell);
                    if (support) supportPlaced = true;
                }
            } finally {
                placePending = false;
            }
            // No micro-delay: a delayed SubTickSequencer action runs from the render loop after this
            // tick's movement packet, which would split the swap and the place across intervals.
        }, 0.0);
    }

    private void onPlaced(PlayerEntity t, boolean headWeb, BlockPos cell) {
        placedCount++;
        placedCell = cell.toImmutable();
        placedHeadWeb = headWeb;
        placedClock = planner.clock();
        aimedTicks = 0;
        catchDeadline = Integer.MIN_VALUE;
        if (headWeb) {
            headDone = true;
        } else {
            feetDone = true;
        }
        lastWebUuid = t.getUuid();
        lastWebMs = System.currentTimeMillis();
        webCooldownMs = ModuleDelays.clampMs(cooldownMs.sample());
    }

    // ── coverage stages ───────────────────────────────────────────────────────────

    private boolean headStage() {
        return switch (coverage.getCurrentMode()) {
            case "Head" -> true;
            case "Full" -> feetDone;
            default -> false;
        };
    }

    private boolean stagesDone() {
        return switch (coverage.getCurrentMode()) {
            case "Head" -> headDone;
            case "Full" -> feetDone && headDone;
            default -> feetDone;
        };
    }

    /** Webs already holding the target count as done, whoever placed them. */
    private void markCovered() {
        if (target == null) return;
        if (WebPlacementHelper.isCollidingWithCobweb(target)) feetDone = true;
        if (WebTrapPlanner.headInWeb(mc.world, target.getBoundingBox())) headDone = true;
    }

    /**
     * Client prediction puts the web in the world at once; the server's refusal arrives a round trip
     * later as a block update that removes it. A web that is gone by then is placed again (bounded)
     * instead of leaving the engagement marked done with nothing in the way.
     */
    private void verifyPlacement(int now) {
        if (placedCell == null) return;
        if (!mc.world.getBlockState(placedCell).isOf(Blocks.COBWEB)) {
            boolean head = placedHeadWeb;
            placedCell = null;
            if (kind != null && replacements < MAX_REPLACEMENTS && isValidTarget(target)) {
                replacements++;
                if (head) headDone = false; else feetDone = false;
                lastWebUuid = null;
            }
            return;
        }
        if (now - placedClock > (int) Math.ceil(rttTicks()) + 3) {
            placedCell = null;
        }
    }

    private boolean awaitingConfirmation() {
        return placedCell != null;
    }

    /**
     * Ends the engagement once every stage is placed, the server has kept the last web, and the
     * target is caught. A kept web the target got past (it changed direction, the lead was off) is
     * followed by one more web on where they are now, instead of ending with nothing holding them.
     */
    private void finishIfDone() {
        if (kind == null || !stagesDone() || awaitingConfirmation()) return;
        if (placedCount == 0 || WebPlacementHelper.isCollidingWithCobweb(target)) {
            finishEngagement();
            return;
        }
        int now = planner.clock();
        if (catchDeadline == Integer.MIN_VALUE) {
            catchDeadline = now + (int) Math.ceil(rttTicks()) + CATCH_WINDOW_TICKS;
        }
        if (now < catchDeadline) return;
        if (missRetries < MAX_MISS_RETRIES && isValidTarget(target) && findHotbarSlot() != -1) {
            missRetries++;
            feetDone = false;
            headDone = false;
            supportPlaced = false;
            lastCell = null;
            catchDeadline = Integer.MIN_VALUE;
            return;
        }
        finishEngagement();
    }

    private void finishEngagement() {
        clearEngagement();
        if (!pendingRestore) {
            RotationManager.stop(this);
        }
    }

    private void idle() {
        finishEngagement();
    }

    private void clearEngagement() {
        kind = null;
        target = null;
        feetDone = false;
        headDone = false;
        supportPlaced = false;
        placedCount = 0;
        aimClick = null;
        lastCell = null;
        confirmed = false;
        fromArm = false;
        placedCell = null;
        replacements = 0;
        aimedTicks = 0;
        aimedClock = Integer.MIN_VALUE;
        catchDeadline = Integer.MIN_VALUE;
        missRetries = 0;
        blockedAtAttack = false;
        reactionReadyMs = System.currentTimeMillis() + ModuleDelays.clampMs(reactionDelay.sample());
    }

    // ── prediction inputs ─────────────────────────────────────────────────────────

    /** Ticks after the target's latest server sample at which a web sent now exists on the server. */
    private double observedWebTick(PlayerEntity t) {
        return planner.ticksSinceSample(t) + rttTicks() + 1.0 + leadOffset.getValue();
    }

    private double rttTicks() {
        return WebHitModel.rttTicks(mc);
    }

    private double pingTicks(UUID uuid) {
        return WebHitModel.pingTicks(mc, uuid);
    }

    private int confirmTimeoutTicks() {
        return Math.max(10, (int) Math.ceil(rttTicks() * 1.5) + 6);
    }

    private float serverYaw() {
        return RotationManager.isSilentRotationActive() ? RotationManager.getFinalYaw() : mc.player.getYaw();
    }

    // ── cells and clicks ──────────────────────────────────────────────────────────

    private double reachLimit() {
        return Math.min(reach.getValue(), mc.player.getBlockInteractionRange());
    }

    private boolean placeable(BlockPos cell) {
        return canPlaceInto(cell) && isSafeCell(cell)
                && mc.player.getEyePos().squaredDistanceTo(Vec3d.ofCenter(cell)) <= Math.pow(reachLimit() + 0.9, 2);
    }

    private boolean canPlaceInto(BlockPos cell) {
        BlockState state = mc.world.getBlockState(cell);
        // A web dropped into water/lava is washed away immediately, so never target a fluid cell.
        if (noPlaceInLiquid.isEnabled() && !mc.world.getFluidState(cell).isEmpty()) return false;
        return !state.isOf(Blocks.COBWEB) && (state.isAir() || state.isReplaceable());
    }

    /** Never web yourself (now or where you are heading) or a friend. */
    private boolean isSafeCell(BlockPos cell) {
        Box cb = new Box(cell);
        Box self = mc.player.getBoundingBox().expand(0.15, 0.0, 0.15);
        Vec3d v = mc.player.getVelocity();
        if (cb.intersects(self) || cb.intersects(self.offset(v.x * 2.0, Math.max(v.y, 0.0) * 2.0, v.z * 2.0))) return false;
        if (ignoreFriends.isEnabled()) {
            for (PlayerEntity p : mc.world.getPlayers()) {
                if (p != mc.player && cb.intersects(p.getBoundingBox()) && FriendManager.getInstance().isFriend(p.getUuid())) {
                    return false;
                }
            }
        }
        return true;
    }

    /** In reach, not an interactive block, and no hitbox in front of the face (vanilla's crosshair would pick it). */
    private boolean clickable(BlockHitResult live) {
        Vec3d eye = mc.player.getEyePos();
        double r = reachLimit();
        if (eye.squaredDistanceTo(live.getPos()) > r * r) return false;
        BlockState s = mc.world.getBlockState(live.getBlockPos());
        if (!mc.player.isSneaking() && !s.isReplaceable() && WebTrapPlanner.isInteractive(mc.world, live.getBlockPos(), s)) {
            return false;
        }
        return !WebTrapPlanner.entityOccludes(mc, eye, live.getPos(), 0.0);
    }

    private void requestAim() {
        String rot = rotationMode.getCurrentMode();
        // The 1-100 slider goes through the shared engine mapping like every other aim module; the
        // rotation engine expects roughly 1-25, so the raw value turned the top of the slider into
        // a 6000+ deg/s snap.
        double engineStrength = RotationManager.engineStrength(strength.getValue(), RotationManager.STRENGTH_SCALE_TRACK);
        if ("Silent".equalsIgnoreCase(rot)) {
            RotationManager.aimAt(
                    this, RotationManager.Priority.HIGH, () -> aimClick == null ? null : aimClick.hit().getPos(),
                    engineStrength, 0.0, true);
        } else if ("Smooth".equalsIgnoreCase(rot)) {
            RotationManager.aimAt(
                    this, RotationManager.Priority.HIGH, () -> aimClick == null ? null : aimClick.hit().getPos(),
                    engineStrength, 0.0, false);
        }
    }

    // ── gates / helpers ───────────────────────────────────────────────────────────

    /** A press of the Keybind: a rising edge in hold mode, any change in toggle mode. */
    private boolean pollKeyPress() {
        boolean value = keybind.isEnabled();
        boolean pressed = keybind.isKeyHoldMode() ? value && !lastKey : value != lastKey;
        lastKey = value;
        return pressed;
    }

    private boolean activationMet() {
        return switch (activation.getCurrentMode()) {
            case "Hold" -> mc.options != null && mc.options.useKey.isPressed();
            case "Keybind Held" -> keybind.isEnabled();
            default -> true;
        };
    }

    private boolean weaponConditionMet() {
        if (!weaponsOnly.isEnabled() || mc.player == null) return true;
        ItemStack main = mc.player.getMainHandStack();
        return CombatItemUtil.isSword(main) || CombatItemUtil.isAxe(main) || CombatItemUtil.isMace(main);
    }

    /** Another module (shield break, mace chain, ...) owns the hotbar right now. */
    private boolean otherModuleHoldsSwap() {
        CombatSwapLockEvent lock = new CombatSwapLockEvent(this);
        EventBus.post(lock);
        return lock.isItemSwapLocked();
    }

    private boolean recentlyWebbed(PlayerEntity t) {
        return t != null && t.getUuid().equals(lastWebUuid)
                && System.currentTimeMillis() - lastWebMs < webCooldownMs;
    }

    private PlayerEntity nearestEnemy() {
        PlayerEntity best = null;
        double bestSq = targetRange.getValue() * targetRange.getValue();
        for (PlayerEntity p : mc.world.getPlayers()) {
            if (!isValidTarget(p)) continue;
            double sq = mc.player.squaredDistanceTo(p);
            if (sq <= bestSq) {
                bestSq = sq;
                best = p;
            }
        }
        return best;
    }

    /** The opponent you are looking toward, else the nearest one. */
    private PlayerEntity oneShotTarget() {
        Vec3d eye = mc.player.getEyePos();
        Vec3d look = mc.player.getRotationVec(1.0F);
        PlayerEntity best = null;
        double bestCost = Double.MAX_VALUE;
        for (PlayerEntity p : mc.world.getPlayers()) {
            if (!isValidTarget(p)) continue;
            Vec3d to = p.getBoundingBox().getCenter().subtract(eye);
            double angle = Math.toDegrees(Math.acos(MathHelper.clamp(look.dotProduct(to.normalize()), -1.0, 1.0)));
            double cost = (angle <= 60.0 ? angle : 60.0 + angle * 2.0) + 4.0 * to.length();
            if (cost < bestCost) {
                bestCost = cost;
                best = p;
            }
        }
        return best;
    }

    private boolean isValidTarget(PlayerEntity t) {
        if (t == null || mc.player == null || mc.world == null
                || t == mc.player || !t.isAlive() || t.isSpectator() || t.isCreative()
                || t.getEntityWorld() != mc.world
                || !interstellarTikiTiks.apexx.client.util.combat.StickyTargets.allows(this, t)
                || (ignoreFriends.isEnabled() && FriendManager.getInstance().isFriend(t.getUuid()))
                || mc.player.squaredDistanceTo(t) > targetRange.getValue() * targetRange.getValue()) return false;
        for (interstellarTikiTiks.apexx.client.module.implementation.player.BotFilterProvider filter
                : interstellarTikiTiks.apexx.client.util.ModuleAccess.getEnabledHooks(
                        interstellarTikiTiks.apexx.client.module.implementation.player.BotFilterProvider.class)) {
            if (filter.isBot(t)) return false;
        }
        return true;
    }

    private int findHotbarSlot() {
        if (mc.player == null) return -1;
        int held = mc.player.getInventory().getSelectedSlot();
        if (mc.player.getInventory().getStack(held).isOf(Items.COBWEB)) return held;
        for (int i = 0; i < 9; i++) {
            if (mc.player.getInventory().getStack(i).isOf(Items.COBWEB)) return i;
        }
        return -1;
    }

    /** Restores the pre-swap slot only while this module still owns the selection in the same world. */
    private void restoreSlot() {
        RotationManager.stop(this);
        if (switchBack.isEnabled() && originalSlot >= 0 && originalSlot <= 8 && mc.player != null
                && mc.player == boundPlayer && mc.world == boundWorld
                && mc.player.getInventory().getSelectedSlot() == expectedSlot) {
            SlotSync.restore(mc, originalSlot);
        }
        originalSlot = expectedSlot = -1;
        boundPlayer = boundWorld = null;
        pendingRestore = false;
        placePending = false;
        restoreTicks = 0;
        holdTicks = 0;
    }
}
