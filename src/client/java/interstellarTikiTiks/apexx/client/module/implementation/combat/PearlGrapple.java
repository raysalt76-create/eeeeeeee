package interstellarTikiTiks.apexx.client.module.implementation.combat;

import interstellarTikiTiks.apexx.client.event.EventBus;
import interstellarTikiTiks.apexx.client.event.EventTarget;
import interstellarTikiTiks.apexx.client.event.impl.CombatSwapLockEvent;
import interstellarTikiTiks.apexx.client.event.impl.PearlModuleExecuteEvent;
import interstellarTikiTiks.apexx.client.event.impl.PearlThrowEvent;
import interstellarTikiTiks.apexx.client.event.impl.ShieldDrainEvent;
import interstellarTikiTiks.apexx.client.friend.FriendManager;
import interstellarTikiTiks.apexx.client.mixin.MinecraftClientAccessor;
import interstellarTikiTiks.apexx.client.module.BooleanSetting;
import interstellarTikiTiks.apexx.client.module.Category;
import interstellarTikiTiks.apexx.client.module.Module;
import interstellarTikiTiks.apexx.client.module.SliderSetting;
import interstellarTikiTiks.apexx.client.module.implementation.player.BotFilterProvider;
import interstellarTikiTiks.apexx.client.util.ClickSimController;
import interstellarTikiTiks.apexx.client.util.ClientInternalLog;
import interstellarTikiTiks.apexx.client.util.ModuleAccess;
import interstellarTikiTiks.apexx.client.util.Notifier;
import interstellarTikiTiks.apexx.client.util.RotationManager;
import interstellarTikiTiks.apexx.client.util.RotationMath;
import interstellarTikiTiks.apexx.client.util.combat.InteractSim;
import interstellarTikiTiks.apexx.client.util.combat.PearlGrappleSolver;
import interstellarTikiTiks.apexx.client.util.combat.PlayerMotionSimulation;
import interstellarTikiTiks.apexx.client.util.combat.PlayerTrajectoryEngine;
import interstellarTikiTiks.apexx.client.util.combat.SlotSync;
import interstellarTikiTiks.apexx.client.util.combat.ThrowPhysics;
import interstellarTikiTiks.apexx.client.util.combat.TickActionOrder;
import interstellarTikiTiks.apexx.client.util.combat.WindChargeIntercept;
import net.minecraft.client.network.PlayerListEntry;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.projectile.thrown.EnderPearlEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.tag.ItemTags;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.world.RaycastContext;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * PearlGrapple: one key press throws an ender pearl that runs into a player in range; the pearl's
 * hit teleports you to where it was on that tick, right beside the player.
 *
 * <p>Only players inside the FOV cone are grappled. With nobody in it the key throws a plain pearl
 * along the current look immediately (Pearl Outside FOV), or waits briefly for a target when that is off.</p>
 *
 * <p>On the press tick it forecasts the target's hitbox from the positions the server has sent
 * ({@link PlayerMotionSimulation}: exact glide, air and ground movement, the 0.6-tall gliding box,
 * network lead), solves the throw with the exact pearl flight ({@link PearlGrappleSolver}), turns
 * with the shared aim step and, once the look actually applied carries the pearl into the forecast
 * box, swaps to the pearl and throws in that same tick. Low-confidence throws remain eligible.
 * At the aim deadline it throws the best reachable trajectory even if no hit is predicted; a
 * blocked slot/action window is retried next tick using fresh state. Upper-body landings are
 * preferred when similarly reliable. The original slot comes back after Swap Back Delay, at the
 * earliest on the next tick.</p>
 */
public class PearlGrapple extends Module implements interstellarTikiTiks.apexx.client.module.hooks.ClickSimulating {

    private final SliderSetting range = new SliderSetting("Range", 30, 5, 50, 0)
            .setDescription("Farthest player, in blocks, the grapple will try to land on.");
    private final SliderSetting fov = new SliderSetting("FOV", 90, 10, 360, 0)
            .setDescription("Full width, in degrees, of the cone around your crosshair a player must be inside to be grappled. Narrow it so the grapple only picks who you are already looking toward.");
    private final BooleanSetting pearlOutsideFov = new BooleanSetting("Pearl Outside FOV", true)
            .setDescription("With nobody inside the FOV, the press throws a normal pearl along your current look (like a plain pearl key). Off: waits briefly for a target, then still throws a plain pearl.");
    private final SliderSetting maceDelay = new SliderSetting("Mace Delay", 150, 0, 1000, 0)
            .withJitter(40)
            .setSection("Delays")
            .setDescription("How long AutoMace stays paused after the pearl lands on the target, so your fall can start before it swings. AutoMace is always paused during the pearl's flight.");
    private final SliderSetting reactionMs = new SliderSetting("Reaction Delay (ms)", 20, 0, 250, 0)
            .withJitter(10)
            .setSection("Delays")
            .setDescription("Pause between your key press and the first aim step, like a player's reaction before flicking onto the throw. Aiming runs once per tick, so it is rounded to whole ticks (a remainder still counts on average); 0 aims on the press tick.");
    private final SliderSetting regrappleDelay = new SliderSetting("Regrapple Delay (ms)", 200, 0, 1000, 0)
            .withJitter(40)
            .setSection("Delays")
            .setDescription("While the keybind stays held, how long after a grapple ends (landing plus Mace Delay) before the next one starts. The pearl cooldown is always waited out as well.");
    private final SliderSetting swapBackDelay = new SliderSetting("Swap Back Delay (ms)", 50, 0, 400, 0)
            .withJitter(15)
            .setSection("Delays")
            .setDescription("Wait after the throw before your original hotbar slot is reselected (with Swap Back on). Always at least the next tick.")
            .setDependsOn("Swap Back");
    private final SliderSetting maxTurn = new SliderSetting("Max Turn Per Tick", 28, 10, 34, 0)
            .setSection("Humanization")
            .setDescription("Largest turn, in degrees, made in a single tick while lining up the throw. Lower values look smoother but may need an extra tick; it is capped at 34 because single turns above ~35-40 degrees around a throw are a known rotation flag.");
    private final SliderSetting turnJitter = new SliderSetting("Turn Variance %", 15, 0, 40, 0)
            .setSection("Humanization")
            .setDescription("Random reduction of each tick's turn so the flick speed differs from throw to throw.");
    private final SliderSetting throwSpread = new SliderSetting("Throw Spread", 0.3, 0.0, 1.5, 2)
            .setSection("Humanization")
            .setDescription("Tiny random error, in degrees, of the final aim so throws are never pixel-perfect. Keep it small: larger values start costing hits at long range.");
    private final BooleanSetting forceCooldownReset = new BooleanSetting("Force Use Cooldown Reset", false)
            .setBlatant(true)
            .setDescription("BLATANT: clears the vanilla item-use cooldown before every throw or chestplate use, even back-to-back ones. Off: only a genuinely fresh press skips it, exactly like a real click.");
    private final BooleanSetting autoBreachPressure = new BooleanSetting("AutoBreach Pressure", false)
            .setDescription("Right after the pearl lands, if the target is more than 5 blocks above the ground, AutoMace's first hit uses your Breach mace and the next your Density mace. Needs both maces in the hotbar and AutoMace on.");
    private final BooleanSetting elytraUnequip = new BooleanSetting("Elytra Unequip", false)
            .setDescription("If you are wearing an elytra, the tick the pearl lands on the target a hotbar chestplate is selected and used to swap the elytra off (the chestplate slot stays selected). Needs a chestplate in the hotbar.");
    private final BooleanSetting silentAim = new BooleanSetting("Silent Aim", false)
            .setDescription("Aims the throw with the rotation sent to the server only, leaving your camera where it is. Off: your camera visibly flicks to the throw, as a player's would.");
    private final BooleanSetting swapBack = new BooleanSetting("Swap Back", true)
            .setDescription("Reselects your original hotbar slot after the throw, once Swap Back Delay has passed (deferred to the next valid moment when the game does not allow it yet).");
    private final BooleanSetting ignoreFriends = new BooleanSetting("Ignore Friends", true)
            .setDescription("Never grapples onto players on your friends list.");

    /** Shortest gap between two of our presses that a player could physically produce, ms. */
    private static final long MIN_PRESS_GAP_MS = 60L;
    private long lastPressAtMs = 0L;
    private long maceDelayMs = 150L;
    private long aimStartMs = 0L;
    /** Aim ticks still to skip for the reaction delay (whole ticks, rounded probabilistically). */
    private int reactionTicks;
    /** When this tick's aim began: solve time between the forecast and the use packet adds to the lead. */
    private long aimTickStartNanos;
    /** The id of the pearl this sequence threw, once the client has its entity. */
    private int ownPearlId = -1;
    private float spreadYaw = 0f;
    private float spreadPitch = 0f;
    /** Players considered per tick, nearest the crosshair first. */
    private static final int MAX_CANDIDATES = 3;
    /** Ticks a forecast reaches past the flight: the still-player limit plus the lead blur. */
    private static final int FORECAST_MARGIN = 8;
    private static final long WARNING_REPEAT_MS = 1500L;

    /** Pearl flight is capped at the longest the solver supports. */
    private static final int MAX_FLIGHT_TICKS = 80;
    /** A one-tick position change this far beyond what the movement explains is the pearl teleport. */
    private static final double TELEPORT_BLOCKS = 3.0;
    /** Ticks past the flight cap the module waits for a landing before giving up. */
    private static final int LANDING_GRACE_TICKS = 40;
    /** Ticks past the expected flight (plus the round trip) after which an unseen landing is assumed. */
    private static final int LANDING_SLACK_TICKS = 12;
    /** Initial flight timeout before an intercept has been selected. */
    private static final int DEFAULT_FLIGHT_TICKS = 30;

    /** Time allowed to improve the aim before taking the best available shot; action-window conflicts retry. */
    private static final long THROW_DEADLINE_MS = 250L;
    /** Longest a press waits for a blocking condition (pearl cooldown, open screen, other action) to clear. */
    private static final long BLOCKED_WAIT_MS = 3000L;

    /**
     * Largest turn in one tick (degrees). Anti-cheat rotation heuristics (checkers/: Intave
     * RotationSnap above 40 degrees, MXAc AimFactor above 35) look for a single large turn between
     * near-still ticks around a swing, so no single tick turns further than this.
     */
    private static final float MAX_TICK_TURN = 34.0f; // hard ceiling; the Max Turn Per Tick setting is clamped to it
    /**
     * Share of the tick cap each successive aim tick may turn: eases in instead of a constant-speed
     * flick, but the first tick already covers most targets near the crosshair so the throw can
     * leave on the press tick.
     */
    private static final float[] TURN_RAMP = {0.7f, 1.0f};
    /**
     * Pearl ticks the solver and its forecasts reach: a throw at the 50 block range lands inside
     * this, and a shorter horizon keeps the per-tick forecast and search cheap.
     */
    private static final int PLAN_FLIGHT_TICKS = 56;
    /**
     * The throw that leaves now (the best look the press tick's turn reaches) is taken when it
     * retains at least this share of the best later aim's probability. This decides whether
     * improving the look is worth waiting for; there is no absolute confidence cutoff.
     */
    private static final double INSTANT_SHARE = 0.90;
    /** A throw this likely to connect leaves at once without the search over the longer turn. */
    private static final double INSTANT_SURE_CHANCE = 0.95;
    /** Share of the throw tick's turn kept going on the ticks after it, easing out instead of freezing. */
    private static final float[] FOLLOW_THROUGH = {0.5f, 0.2f};

    /**
     * The Throw Spread offset is dropped for the rest of a press when the offset look keeps less than
     * this share of the solved look's chance: humanization must not be what makes a grapple miss.
     */
    private static final double SPREAD_KEEP_SHARE = 0.9;
    /** Solve time (ms) past which the throw is re-checked against the target's later position. */
    private static final double LATE_SOLVE_MS = 20.0;

    /** A fresh solve replaces the committed throw only when it beats it by more than this chance. */
    private static final double KEEP_MARGIN = 0.005;

    /** The target counts as airborne for AutoBreach Pressure above this many blocks over the ground. */
    private static final double PRESSURE_GROUND_GAP = 5.0;
    /** Longest the pressure stays armed in AutoMace after the landing if no hit uses it. */
    private static final long PRESSURE_TTL_MS = 6000L;

    private enum Phase { IDLE, AIMING, THROWN, SWAP_BACK, REPEAT }

    private record Plan(PlayerEntity target, PearlGrappleSolver.Target forecast, PearlGrappleSolver.Shot shot,
                        PlayerMotionSimulation.Mode mode) {}

    private Phase phase = Phase.IDLE;
    private long triggerMs;
    private long lastAimTick = Long.MIN_VALUE;
    private PlayerEntity target;
    private int originalSlot = -1;
    private int ownedSlot = -1;
    private String missReason;
    private int thrownTicks;
    private int expectedFlightTicks;
    private boolean plainMode;
    private int aimSteps;
    private boolean committed;
    private float committedYaw;
    private float committedPitch;
    private float preStepYaw;
    private float preStepPitch;
    private float throwTurnYaw;
    private float throwTurnPitch;
    private boolean pearlSeen;
    private Vec3d lastPos;
    private long landedMs;
    private boolean elytraWorn;
    private boolean unequipDone;
    private long repeatAtMs;
    /** When the pending swap back to the original slot is due; 0 when none is pending. */
    private long swapBackAtMs;
    private String lastWarning;
    private long lastWarningMs;

    public PearlGrapple() {
        super("PearlGrapple",
                "One key press throws an ender pearl that intercepts a player in range and teleports you onto them: it predicts where they will be, flicks to the throw at a human speed after a short reaction, then hands the fight to AutoMace once you land.",
                Category.COMBAT);
        addSetting(range);
        addSetting(fov);
        addSetting(pearlOutsideFov);
        addSetting(maceDelay);
        addSetting(reactionMs);
        addSetting(regrappleDelay);
        addSetting(swapBackDelay);
        addSetting(maxTurn);
        addSetting(turnJitter);
        addSetting(throwSpread);
        addSetting(forceCooldownReset);
        addSetting(autoBreachPressure);
        addSetting(elytraUnequip);
        addSetting(silentAim);
        addSetting(swapBack);
        addSetting(ignoreFriends);
    }
    @Override
    public boolean isOneShot() {
        return true;
    }

    @Override
    public void onEnable() {
        EventBus.post(new PearlModuleExecuteEvent("PearlGrapple"));
        beginSequence();
    }

    /** Starts one grapple: this tick's aim and, when it already connects, the throw. */
    private void beginSequence() {
        resetState();
        if (!interstellarTikiTiks.apexx.client.util.combat.PearlLimit.canThrow(mc)) {
            fail("Pearl Limit reached");
            return;
        }
        String blocked = blockedReason();
        if (blocked != null && !canWaitForThrow()) {
            fail(blocked);
            return;
        }
        triggerMs = System.currentTimeMillis();
        // Aim steps happen once per tick: the reaction delay is whole ticks, rounded probabilistically
        // so its average is the slider's. The throw deadline is measured from the first aim tick.
        reactionTicks = interstellarTikiTiks.apexx.client.util.combat.ModuleDelays.msToTicks(reactionMs.sample());
        aimStartMs = 0L;
        java.util.concurrent.ThreadLocalRandom rnd = java.util.concurrent.ThreadLocalRandom.current();
        spreadYaw = (float) ((rnd.nextDouble() * 2.0 - 1.0) * throwSpread.getValue());
        spreadPitch = (float) ((rnd.nextDouble() * 2.0 - 1.0) * throwSpread.getValue() * 0.6);
        elytraWorn = mc.player.getEquippedStack(EquipmentSlot.CHEST).isOf(Items.ELYTRA);
        originalSlot = ownedSlot = mc.player.getInventory().getSelectedSlot();
        phase = Phase.AIMING;
        EventBus.register(this);
        if (candidates().isEmpty() && pearlOutsideFov.isEnabled()) {
            // Nobody to grapple: this press is a plain pearl along the current look.
            plainMode = true;
            aim();
            return;
        }
        // Keybinds are polled at tick head, so the press tick's aim and throw happen right here,
        // ahead of this tick's movement packet.
        aim();
    }

    @Override
    public void onDisable() {
        EventBus.unregister(this);
        release();
    }

    @Override
    protected void onRuntimeStateReleased() {
        release();
    }

    @Override
    public void onTick() {
        if (mc.player == null || mc.world == null || phase == Phase.IDLE) {
            toggle();
            return;
        }
        try {
            if (phase == Phase.AIMING) {
                aim();
            } else if (phase == Phase.REPEAT) {
                awaitRepeat();
            } else if (phase == Phase.SWAP_BACK) {
                // A plain pearl: only the delayed swap back is left before the press ends.
                if (swapBackDue(false)) endSequence();
            } else {
                awaitLanding();
            }
        } catch (RuntimeException error) {
            // Never leave the look locked behind a failed tick: release, then end the press.
            ClientInternalLog.error(getName(), "Grapple tick failed", error);
            release();
            if (isEnabled()) toggle();
        }
    }

    /**
     * After the throw: release the look, keep AutoMace paused through the flight, and end the
     * sequence Mace Delay after the pearl lands (client-side teleport or the pearl disappearing).
     */
    private void awaitLanding() {
        swapBackDue(false);
        if (thrownTicks == 0) {
            lastPos = mc.player.getEntityPos();
        }
        followThrough(thrownTicks);
        thrownTicks++;
        long now = System.currentTimeMillis();
        if (landedMs == 0L) {
            boolean pearlNow = ownPearlInFlight();
            pearlSeen |= pearlNow;
            Vec3d pos = mc.player.getEntityPos();
            // What the movement of the tick explains is not a teleport: only the rest is.
            boolean teleported = lastPos != null
                    && pos.subtract(lastPos).subtract(mc.player.getVelocity().multiply(1.1)).length() > TELEPORT_BLOCKS;
            lastPos = pos;
            int timeout = Math.min(MAX_FLIGHT_TICKS + LANDING_GRACE_TICKS,
                    expectedFlightTicks + (int) Math.ceil(roundTripMs() / 50.0) + LANDING_SLACK_TICKS);
            if (teleported || (pearlSeen && !pearlNow)) {
                landedMs = now;
                maceDelayMs = Math.round(maceDelay.sample());
                armBreachPressure();
            } else if (thrownTicks > timeout) {
                // Never seen landing (it ended before the client saw the pearl): count it as landed now,
                // so the next press is not swallowed by a sequence that is only waiting.
                landedMs = now;
                maceDelayMs = Math.round(maceDelay.sample());
            }
        }
        if (landedMs != 0L && elytraUnequip.isEnabled() && elytraWorn && !unequipDone) {
            unequipElytra();
        }
        AutoMace mace = ModuleAccess.getModule(AutoMace.class);
        if (landedMs == 0L) {
            if (mace != null) mace.externalPause(150L);
            return;
        }
        long remaining = landedMs + maceDelayMs - now;
        if (remaining <= 0L) {
            endSequence();
        } else if (mace != null) {
            mace.externalPause(remaining + 60L);
        }
    }

    /**
     * The tick the pearl lands: when the target hangs more than {@value #PRESSURE_GROUND_GAP} blocks
     * above the ground, AutoMace's next smash is the Breach mace's and the one after it the
     * Density mace's.
     */
    private void armBreachPressure() {
        if (!autoBreachPressure.isEnabled() || target == null || mc.world == null) return;
        AutoMace mace = ModuleAccess.getModule(AutoMace.class);
        if (mace == null || !mace.isEnabled()) return;
        double gap = groundGap(target);
        if (gap <= PRESSURE_GROUND_GAP) return;
        mace.armBreachPressure(maceDelayMs + PRESSURE_TTL_MS);
        ClientInternalLog.info(getName(), String.format("AutoBreach Pressure armed: target %.1f blocks above the ground", gap));
    }

    /** Blocks (and fluid surface) between the player's feet and the first thing below them. */
    private double groundGap(PlayerEntity player) {
        Vec3d feet = player.getEntityPos();
        double depth = Math.max(1.0, feet.y - mc.world.getBottomY());
        BlockHitResult hit = mc.world.raycast(new RaycastContext(feet, feet.subtract(0.0, depth, 0.0),
                RaycastContext.ShapeType.COLLIDER, RaycastContext.FluidHandling.ANY, player));
        return hit.getType() == HitResult.Type.MISS ? depth : feet.y - hit.getPos().y;
    }

    /** A tap ends here; a held keybind waits out the regrapple delay and grapples again. */
    private void endSequence() {
        swapBackDue(true);
        if (!isBindHeld()) {
            toggle();
            return;
        }
        resetState();
        phase = Phase.REPEAT;
        repeatAtMs = System.currentTimeMillis()
                + interstellarTikiTiks.apexx.client.util.combat.ModuleDelays.clampMs(regrappleDelay.sample());
    }

    /**
     * Applies the pending swap back once Swap Back Delay has passed ({@code force}: now). It runs from
     * a tick after the throw, so it never follows the use in the throw's own interval, and only while
     * this module's selection is still the held one. Returns true when nothing is pending any more.
     */
    private boolean swapBackDue(boolean force) {
        if (swapBackAtMs == 0L) return true;
        if (!force && System.currentTimeMillis() < swapBackAtMs) return false;
        swapBackAtMs = 0L;
        restoreSlot();
        return true;
    }

    /** Schedules the swap back after a throw (Swap Back on); the earliest is the next tick. */
    private void scheduleSwapBack() {
        if (!swapBack.isEnabled()) return;
        swapBackAtMs = System.currentTimeMillis()
                + Math.max(1L, interstellarTikiTiks.apexx.client.util.combat.ModuleDelays.clampMs(swapBackDelay.sample()));
    }

    /** Between grapples with the key held: let go ends it, the delay elapsing starts the next one. */
    private void awaitRepeat() {
        if (!isBindHeld()) {
            toggle();
            return;
        }
        if (System.currentTimeMillis() < repeatAtMs) return;
        // The pearl cooldown outlasts a short delay: keep waiting for it rather than cancelling.
        if (mc.player != null && mc.player.getItemCooldownManager().isCoolingDown(Items.ENDER_PEARL.getDefaultStack())) {
            return;
        }
        EventBus.post(new PearlModuleExecuteEvent("PearlGrapple"));
        beginSequence();
    }

    /**
     * The landing tick: chestplate slot first, then the use that swaps it onto the body and the
     * elytra into the hand, both in this tick. The chestplate slot stays selected. Retried on the
     * following ticks only while a slot change is still out of order.
     */
    private void unequipElytra() {
        if (!mc.player.getEquippedStack(EquipmentSlot.CHEST).isOf(Items.ELYTRA)) {
            unequipDone = true;
            return;
        }
        int slot = findChestplateSlot();
        if (slot < 0) {
            unequipDone = true;
            return;
        }
        if (!selectSlot(slot)) return;
        // The chestplate slot is kept: nothing restores it.
        originalSlot = ownedSlot = slot;
        mc.gameRenderer.updateCrosshairTarget(1.0f);
        if (mc instanceof MinecraftClientAccessor accessor && (forceCooldownReset.isEnabled()
                || System.currentTimeMillis() - lastPressAtMs >= MIN_PRESS_GAP_MS)) {
            accessor.setItemUseCooldown(0);
        }
        // Still inside vanilla's use throttle (a press too soon after the last): retry next tick.
        if (!InteractSim.useReady(mc)) return;
        lastPressAtMs = System.currentTimeMillis();
        ClickSimController.useNow(mc, true);
        // Vanilla's use starts by setting its 4-tick throttle, so a ready throttle afterwards means the
        // input seam emitted nothing; only then is the use sent directly, so it can never go twice.
        boolean emitted = !InteractSim.useReady(mc);
        if (!emitted && mc.player.getEquippedStack(EquipmentSlot.CHEST).isOf(Items.ELYTRA)
                && mc.interactionManager.interactItem(mc.player, Hand.MAIN_HAND).isAccepted()) {
            mc.player.swingHand(Hand.MAIN_HAND);
            if (mc instanceof MinecraftClientAccessor accessor) accessor.setItemUseCooldown(4);
        }
        TickActionOrder.recordUse();
        unequipDone = true;
        ClientInternalLog.info(getName(), "Elytra unequipped from hotbar slot " + slot);
    }

    private int findChestplateSlot() {
        for (int slot = 0; slot < 9; slot++) {
            if (mc.player.getInventory().getStack(slot).isIn(ItemTags.CHEST_ARMOR)) return slot;
        }
        return -1;
    }

    /**
     * The look keeps turning the way the throw tick did, with falling speed, then is released: the
     * turn ends in a deceleration instead of an abrupt stop. Runs from the tick after the throw.
     */
    private void followThrough(int tick) {
        if (tick < FOLLOW_THROUGH.length && Math.hypot(throwTurnYaw, throwTurnPitch) > 1.0f) {
            float dy = throwTurnYaw * FOLLOW_THROUGH[tick];
            float dp = throwTurnPitch * FOLLOW_THROUGH[tick];
            float yaw = RotationManager.getFinalYaw() + dy;
            float pitch = MathHelper.clamp(RotationManager.getFinalPitch() + dp, -89.0f, 89.0f);
            if (RotationManager.stepAim(this, RotationManager.Priority.HIGHEST, yaw, pitch,
                    (float) Math.hypot(dy, dp) + 0.01f, silentAim.isEnabled())) {
                return;
            }
        }
        RotationManager.clearTarget(this);
        RotationManager.releaseExclusiveLock(this);
    }

    /**
     * Whether the pearl this press threw is still flying. Once its entity is found it is followed by
     * id; until then one pass over the loaded entities (no 400-block box query every tick).
     */
    private boolean ownPearlInFlight() {
        if (ownPearlId >= 0) {
            net.minecraft.entity.Entity known = mc.world.getEntityById(ownPearlId);
            return known instanceof EnderPearlEntity pearl && pearl.isAlive() && !pearl.isRemoved();
        }
        for (net.minecraft.entity.Entity entity : mc.world.getEntities()) {
            if (entity instanceof EnderPearlEntity pearl && pearl.getOwner() == mc.player && pearl.isAlive()) {
                ownPearlId = pearl.getId();
                return true;
            }
        }
        return false;
    }

    // ── Aim and throw ───────────────────────────────────────────────────────

    /**
     * One tick of the sequence: solve within the turn the deadline still allows, step toward the
     * shot and take a connecting throw as soon as it is competitive. At the deadline, throw the
     * best available path regardless of confidence; do not abandon the press.
     */
    private void aim() {
        // onEnable's press-tick aim and this tick's onTick are the same tick: one aim step per tick.
        long tick = mc.world == null ? Long.MIN_VALUE : mc.world.getTime();
        if (tick == lastAimTick) return;
        lastAimTick = tick;
        String blocked = blockedReason();
        if (blocked != null) {
            // A press waits out a cooldown or a busy action, but never lingers: a throw fired much later
            // (screen closed minutes after the key press) would be a surprise.
            if (canWaitForThrow() && System.currentTimeMillis() - triggerMs <= BLOCKED_WAIT_MS) {
                missReason = blocked;
                // Leave other modules free to finish the action that temporarily blocks this
                // press. Fresh forecasts and slot ownership are checked when it becomes usable.
                RotationManager.clearTarget(this);
                RotationManager.releaseExclusiveLock(this);
                return;
            }
            fail(blocked);
            return;
        }
        if (reactionTicks > 0) {
            reactionTicks--;
            return; // still reacting to the press
        }
        aimTickStartNanos = System.nanoTime();
        if (aimStartMs == 0L) aimStartMs = System.currentTimeMillis();
        long elapsed = System.currentTimeMillis() - aimStartMs;
        if (plainMode) {
            throwPlain();
            return;
        }
        RotationManager.acquireExclusiveLock(this);
        pauseCombatAssists();
        // Ticks still inside the deadline after this one: each adds one more aim step of turn.
        int spareTicks = (int) Math.max(0L, (THROW_DEADLINE_MS - elapsed) / 50L);
        float flickCap = Math.min(RotationManager.aimStepCeiling(), Math.min(MAX_TICK_TURN, (float) maxTurn.getValue()));
        flickCap *= (float) (1.0 - java.util.concurrent.ThreadLocalRandom.current().nextDouble() * turnJitter.getValue() / 100.0);
        float flick = tickTurn(flickCap, aimSteps);
        double budget = 0.0;
        for (int i = 0; i <= spareTicks; i++) budget += tickTurn(flickCap, aimSteps + i);
        PearlGrappleSolver.Lattice lattice = new PearlGrappleSolver.Lattice(
                RotationManager.getFinalYaw(), RotationManager.getFinalPitch(), lookStep());
        PearlGrappleSolver.Thrower thrower = thrower();
        List<Prepared> prepared = prepareAll();
        if (prepared.isEmpty()) {
            if (spareTicks == 0) {
                plainMode = true;
                throwPlain();
            }
            return;
        }
        Plan now = bestPlan(prepared, thrower, lattice, flick, false);
        Plan plan = now;
        if (spareTicks > 0 && budget > flick + 1.0E-3 && !prepared.isEmpty()
                && (now == null || now.shot().chance() < INSTANT_SURE_CHANCE)) {
            // The best look over the whole allowed turn, to see whether waiting for it is worth a tick.
            Plan far = bestPlan(prepared, thrower, lattice, budget, true);
            boolean throwNow = now != null && (far == null || now.shot().chance() >= far.shot().chance() * INSTANT_SHARE);
            if (!throwNow) plan = far;
        }
        if (plan == null) {
            // No predicted hit: keep improving the closest real trajectory, and throw it at
            // the deadline rather than cancelling or applying an arbitrary upward lift.
            Prepared focus = prepared.get(0);
            PearlGrappleSolver.Shot bestEffort = PearlGrappleSolver.bestEffort(mc.world, mc.player,
                    focus.target(), thrower, focus.forecast(), lattice, spareTicks > 0 ? budget : flick,
                    PLAN_FLIGHT_TICKS);
            plan = new Plan(focus.target(), focus.forecast(), bestEffort, focus.mode());
        }
        target = plan.target();
        PearlGrappleSolver.Shot shot = plan.shot();
        WindChargeIntercept.Reach reach = WindChargeIntercept.Reach.from(lattice.yaw(), lattice.pitch());
        boolean reachableNow = reach.turn(shot.yaw(), shot.pitch()) <= flick + 1.0E-3f;
        if ((spreadYaw != 0.0f || spreadPitch != 0.0f) && shot.chance() > 0.0) {
            // The humanization offset is only kept while it costs (almost) nothing.
            PearlGrappleSolver.Shot offset = PearlGrappleSolver.evaluate(null, null, plan.target(), thrower,
                    plan.forecast(), shot.yaw() + spreadYaw, MathHelper.clamp(shot.pitch() + spreadPitch, -89.0f, 89.0f),
                    PLAN_FLIGHT_TICKS);
            PearlGrappleSolver.Shot exact = PearlGrappleSolver.evaluate(null, null, plan.target(), thrower,
                    plan.forecast(), shot.yaw(), shot.pitch(), PLAN_FLIGHT_TICKS);
            double kept = offset == null ? 0.0 : offset.chance();
            double full = exact == null ? shot.chance() : exact.chance();
            if (kept < full * SPREAD_KEEP_SHARE) {
                spreadYaw = 0.0f;
                spreadPitch = 0.0f;
            }
        }
        preStepYaw = RotationManager.getFinalYaw();
        preStepPitch = RotationManager.getFinalPitch();
        if (!RotationManager.stepAim(this, RotationManager.Priority.HIGHEST, shot.yaw() + spreadYaw,
                MathHelper.clamp(shot.pitch() + spreadPitch, -89.0f, 89.0f), flick,
                silentAim.isEnabled())) {
            missReason = "The look is held by another module";
            if (spareTicks == 0) throwAt(plan, thrower, 0);
            return;
        }
        aimSteps++;
        if (spareTicks == 0 || (reachableNow && shot.chance() > 0.0)) {
            throwAt(plan, thrower, spareTicks);
        }
        // Otherwise this tick's step is taken and the next tick solves again.
    }

    /**
     * Re-score the actually applied look. Before the deadline, wait if it lost its intercept;
     * at the deadline, throw even at zero predicted chance. Slot selection stays before use.
     */
    private void throwAt(Plan plan, PearlGrappleSolver.Thrower thrower, int spareTicks) {
        float[] applied = appliedLook();
        PearlGrappleSolver.Shot exact = PearlGrappleSolver.evaluate(mc.world, mc.player, plan.target(), thrower,
                lateForecast(plan), applied[0], applied[1], PLAN_FLIGHT_TICKS);
        if (exact == null && spareTicks > 0) {
            missReason = plan.target().getName().getString() + ": improving the applied look";
            return;
        }
        int flightTicks = exact != null && exact.hitAge() > 0 ? exact.hitAge() : DEFAULT_FLIGHT_TICKS;
        if (!throwNow(applied, flightTicks)) return;
        ClientInternalLog.info(getName(), String.format(
                "Thrown at %s (%s) after %d ms: %.0f%% predicted hit chance%s, look %.2f/%.2f",
                plan.target().getName().getString(), plan.mode(), System.currentTimeMillis() - triggerMs,
                exact != null ? exact.chance() * 100.0 : 0.0, exact == null ? " (best effort)" : "",
                applied[0], applied[1]));
    }

    /**
     * Pearl slot first, then the throw, both in this tick, and the bookkeeping of a throw that left.
     *
     * @return true when the pearl was thrown; false when it could not be (the cause is in
     *         {@link #missReason}; the next tick retries with fresh aim and slot state)
     */
    private boolean throwNow(float[] applied, int flightTicks) {
        int pearlSlot = findPearlSlot();
        if (!selectSlot(pearlSlot)) {
            // Another action already went out this tick, so a slot change now would follow it.
            missReason = "Could not select the ender pearl in vanilla order this tick";
            return false;
        }
        if (!usePearl()) {
            // The pearl slot stays selected and the throw is retried next tick: switching back in
            // this same call would be a swap with nothing between it and the next one.
            missReason = "The throw was not accepted this tick; retrying";
            return false;
        }
        // Keep this tick's look for the rest of the tick; it is released on the next one.
        RotationManager.setPostAttackHold(this, 1, applied[0], applied[1]);
        throwTurnYaw = MathHelper.wrapDegrees(applied[0] - preStepYaw);
        throwTurnPitch = applied[1] - preStepPitch;
        expectedFlightTicks = flightTicks;
        EventBus.post(new PearlThrowEvent());
        phase = Phase.THROWN;
        scheduleSwapBack();
        return true;
    }

    /** A normal pearl along the look held now: no aim, no lock, no AutoMace pause. */
    private void throwPlain() {
        RotationManager.clearTarget(this);
        RotationManager.releaseExclusiveLock(this);
        int pearlSlot = findPearlSlot();
        if (!selectSlot(pearlSlot)) {
            missReason = "Could not select the ender pearl in vanilla order this tick";
            return;
        }
        if (!usePearl()) {
            // Keep the pearl slot and retry next tick (no same-call swap back).
            missReason = "The throw was not accepted this tick; retrying";
            return;
        }
        EventBus.post(new PearlThrowEvent());
        ClientInternalLog.info(getName(), "Plain pearl: no player inside the FOV");
        scheduleSwapBack();
        if (swapBackAtMs != 0L) {
            phase = Phase.SWAP_BACK;
            return;
        }
        phase = Phase.IDLE;
        endSequence();
    }

    /**
     * The forecast as it stands when the use packet actually leaves. The leads were set at the start
     * of this tick's aim; a solve that took long (a slow frame) sends the throw later, and the target
     * has moved on by that much, so the leads are shifted by the time spent before the applied look
     * is judged.
     */
    private PearlGrappleSolver.Target lateForecast(Plan plan) {
        double spentMs = (System.nanoTime() - aimTickStartNanos) / 1.0e6;
        if (aimTickStartNanos == 0L || spentMs < LATE_SOLVE_MS) return plan.forecast();
        PlayerMotionSimulation.Prediction prediction = PlayerMotionSimulation.predictWide(mc.world, plan.target(),
                PLAN_FLIGHT_TICKS + FORECAST_MARGIN + (int) Math.ceil(roundTripMs() / 50.0));
        long sinceSeen = PlayerTrajectoryEngine.getInstance().currentTick() - prediction.observedTick();
        PearlGrappleSolver.Target forecast = plan.forecast();
        return new PearlGrappleSolver.Target(forecast.forecasts(),
                PearlGrappleSolver.leads(sinceSeen + spentMs / 50.0, roundTripMs()), forecast.landingFraction());
    }

    private static float tickTurn(float cap, int steps) {
        return cap * TURN_RAMP[Math.min(steps, TURN_RAMP.length - 1)];
    }

    /** A candidate with its forecast, built once per tick for every solve of that tick. */
    private record Prepared(PlayerEntity target, PearlGrappleSolver.Target forecast, PlayerMotionSimulation.Mode mode) {}

    /** The forecasts of the locked target, or of the players nearest the crosshair; empty for none. */
    private List<Prepared> prepareAll() {
        List<PlayerEntity> candidates = target != null && isCandidate(target) ? List.of(target) : candidates();
        if (candidates.isEmpty()) {
            missReason = target != null ? "Target lost" : String.format("No player within %.0f blocks", range.getValue());
            return List.of();
        }
        List<Prepared> out = new ArrayList<>(candidates.size());
        double rtt = roundTripMs();
        int horizon = PLAN_FLIGHT_TICKS + FORECAST_MARGIN + (int) Math.ceil(rtt / 50.0);
        for (PlayerEntity candidate : candidates) {
            PlayerMotionSimulation.Prediction prediction = PlayerMotionSimulation.predictWide(mc.world, candidate, horizon);
            long sinceSeen = PlayerTrajectoryEngine.getInstance().currentTick() - prediction.observedTick();
            // Prefer an upper-body landing only when it does not materially reduce the hit chance.
            // Every collision with the real predicted box remains a valid intercept.
            out.add(new Prepared(candidate, new PearlGrappleSolver.Target(prediction.forecasts(),
                    PearlGrappleSolver.leads(sinceSeen, rtt), PearlGrappleSolver.HEAD_BAND_START), prediction.mode()));
        }
        return out;
    }

    /**
     * The best shot over the prepared candidates within {@code maxTurn} degrees of the look; null for
     * none. {@code sticky}: keep the throw chosen first while it still connects (see {@link #plan}).
     */
    private Plan bestPlan(List<Prepared> prepared, PearlGrappleSolver.Thrower thrower,
                          PearlGrappleSolver.Lattice lattice, double maxTurn, boolean sticky) {
        Plan best = null;
        for (Prepared candidate : prepared) {
            Plan plan = plan(candidate, thrower, lattice, maxTurn, sticky);
            if (plan != null && (best == null || plan.shot().chance() > best.shot().chance())) best = plan;
        }
        if (best == null) {
            missReason = "No pearl path reaches " + prepared.get(0).target().getName().getString()
                    + " within the aim limit";
            return null;
        }
        if (sticky) target = best.target();
        return best;
    }

    private Plan plan(Prepared prepared, PearlGrappleSolver.Thrower thrower, PearlGrappleSolver.Lattice lattice,
                      double maxTurn, boolean sticky) {
        PlayerEntity candidate = prepared.target();
        PearlGrappleSolver.Target forecast = prepared.forecast();
        long solveStart = System.nanoTime();
        PearlGrappleSolver.Shot shot = PearlGrappleSolver.solve(mc.world, mc.player, candidate, thrower, forecast,
                lattice, maxTurn, PLAN_FLIGHT_TICKS);
        interstellarTikiTiks.apexx.client.util.combat.PearlTiming.report("PearlGrapple", "solve", solveStart);
        // Keep aiming at the throw chosen first while it still connects and the best throw now is
        // not clearly better: re-picking each tick would swing the look between near-equal solutions,
        // yet a falling shooter's committed throw decays and must give way to a better one.
        if (sticky && committed && candidate == target) {
            PearlGrappleSolver.Shot kept = PearlGrappleSolver.evaluate(mc.world, mc.player, candidate, thrower,
                    forecast, committedYaw, committedPitch, PLAN_FLIGHT_TICKS);
            if (kept != null
                    && (shot == null || kept.chance() + KEEP_MARGIN >= shot.chance())) {
                return new Plan(candidate, forecast, kept, prepared.mode());
            }
        }
        if (shot == null) return null;
        if (sticky) {
            committed = true;
            committedYaw = shot.yaw();
            committedPitch = shot.pitch();
        }
        return new Plan(candidate, forecast, shot, prepared.mode());
    }

    /** Players in range and inside the FOV cone, nearest the crosshair first. */
    private List<PlayerEntity> candidates() {
        Vec3d eye = mc.player.getEyePos();
        Vec3d look = RotationMath.direction(RotationManager.getFinalYaw(), RotationManager.getFinalPitch());
        double minCos = Math.cos(Math.toRadians(Math.min(fov.getValue(), 360.0) * 0.5));
        List<PlayerEntity> players = new ArrayList<>();
        for (PlayerEntity player : mc.world.getPlayers()) {
            if (!isCandidate(player)) continue;
            Vec3d to = centre(player).subtract(eye);
            if (to.lengthSquared() > 1.0E-6 && look.dotProduct(to.normalize()) < minCos) continue;
            players.add(player);
        }
        players.sort(Comparator.comparingDouble(p -> -look.dotProduct(centre(p).subtract(eye).normalize())));
        return players.size() > MAX_CANDIDATES ? players.subList(0, MAX_CANDIDATES) : players;
    }

    private boolean isCandidate(PlayerEntity player) {
        if (player == mc.player || !player.isAlive() || player.isRemoved() || player.isSpectator() || player.isCreative()) {
            return false;
        }
        if (ignoreFriends.isEnabled() && FriendManager.getInstance().isFriend(player.getUuid())) return false;
        if (!interstellarTikiTiks.apexx.client.util.combat.StickyTargets.allows(this, player)) return false;
        for (BotFilterProvider botFilter : ModuleAccess.getEnabledHooks(BotFilterProvider.class)) {
            if (botFilter.isBot(player)) return false;
        }
        double reach = range.getValue();
        return mc.player.getEyePos().squaredDistanceTo(centre(player)) <= reach * reach;
    }

    /** The middle of the hitbox where the server has it. */
    private static Vec3d centre(PlayerEntity player) {
        return player.getTrackedPosition().getPos().add(0.0, player.getHeight() * 0.5, 0.0);
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    /** The thrower as the server has it when it handles the use: this tick-head state. */
    private PearlGrappleSolver.Thrower thrower() {
        return new PearlGrappleSolver.Thrower(
                ThrowPhysics.pearlSpawn(mc.player.getEntityPos(), mc.player.getEyeY() - mc.player.getY()),
                ThrowPhysics.localShooterMovement(mc.player), mc.player.isOnGround());
    }

    /** The look the use packet will carry. */
    private float[] appliedLook() {
        if (RotationManager.isSilentRotationActive()) {
            return new float[]{RotationManager.getFinalYaw(), RotationManager.getFinalPitch()};
        }
        return new float[]{mc.player.getYaw(), mc.player.getPitch()};
    }

    /** The mouse-sensitivity step the aim step quantizes to. */
    private double lookStep() {
        double step = RotationMath.mousePixelStep(mc.options.getMouseSensitivity().getValue());
        return step > 1.0E-4 ? step : 0.01;
    }

    private double roundTripMs() {
        if (mc.getNetworkHandler() == null) return 0.0;
        PlayerListEntry entry = mc.getNetworkHandler().getPlayerListEntry(mc.player.getUuid());
        return entry == null ? 0.0 : Math.max(0, entry.getLatency());
    }

    private boolean usePearl() {
        if (mc.interactionManager == null || !mc.player.getMainHandStack().isOf(Items.ENDER_PEARL)
                || !interstellarTikiTiks.apexx.client.util.combat.PearlLimit.canThrow(mc)) return false;
        ItemStack held = mc.player.getMainHandStack();
        int countBefore = held.getCount();
        // Vanilla's use reads the crosshair, last computed for the previous look: recompute it for this one.
        mc.gameRenderer.updateCrosshairTarget(1.0f);
        // A fresh press is not throttled by itemUseCooldown in vanilla (handleInputEvents' wasPressed
        // loop); only a held use key waits on it. The shared use seam gates on it, so clear it for
        // this single press.
        if (mc instanceof MinecraftClientAccessor accessor && (forceCooldownReset.isEnabled()
                || System.currentTimeMillis() - lastPressAtMs >= MIN_PRESS_GAP_MS)) {
            accessor.setItemUseCooldown(0);
        }
        boolean ready = InteractSim.useReady(mc);
        lastPressAtMs = System.currentTimeMillis();
        ClickSimController.useNow(mc, true);
        // One use per movement interval: a use packet that already left is the throw, never repeated.
        if (!TickActionOrder.canSwitchSlot()) {
            return useTookEffect(held, countBefore) || mc.player.getItemCooldownManager().isCoolingDown(held);
        }
        TickActionOrder.recordUse();
        if (useTookEffect(held, countBefore)) return true;
        // Vanilla's use (the seam) sets the throttle first: if it ran, it already decided this press,
        // and a still-throttled press waits for the next tick. Only a dropped use is sent directly,
        // doing what vanilla's use does with nothing under the crosshair, and only then.
        if (!ready || !InteractSim.useReady(mc)) return false;
        if (mc.player.isRiding() || mc.interactionManager.isBreakingBlock()
                || (mc.crosshairTarget != null && mc.crosshairTarget.getType() != HitResult.Type.MISS)) return false;
        if (mc instanceof MinecraftClientAccessor accessor) accessor.setItemUseCooldown(4);
        if (!mc.interactionManager.interactItem(mc.player, Hand.MAIN_HAND).isAccepted()) return false;
        mc.player.swingHand(Hand.MAIN_HAND);
        return true;
    }

    /** A real use shrinks the stack or starts the pearl cooldown on the client at once. */
    private boolean useTookEffect(ItemStack held, int countBefore) {
        return held.isEmpty() || held.getCount() < countBefore
                || mc.player.getItemCooldownManager().isCoolingDown(held);
    }

    /** Another module (shield break, stun slam, ...) owns the hotbar this tick. */
    private boolean hotbarLocked() {
        CombatSwapLockEvent lock = new CombatSwapLockEvent(this);
        EventBus.post(lock);
        return lock.isItemSwapLocked();
    }

    private boolean selectSlot(int slot) {
        if (mc.player == null || slot < 0 || slot > 8) return false;
        if (mc.player.getInventory().getSelectedSlot() != slot && hotbarLocked()) return false;
        if (mc.player.getInventory().getSelectedSlot() == slot) {
            ownedSlot = slot;
            return true;
        }
        if (!TickActionOrder.canSwitchSlot() || !SlotSync.select(mc, slot)) return false;
        ownedSlot = slot;
        return true;
    }

    /** Back to the original slot, only while this module's selection is still the held one; deferred past a use. */
    private void restoreSlot() {
        if (mc.player == null || originalSlot < 0 || ownedSlot == originalSlot
                || mc.player.getInventory().getSelectedSlot() != ownedSlot) return;
        if (TickActionOrder.canSwitchSlot()) {
            SlotSync.select(mc, originalSlot);
        } else {
            TickActionOrder.deferSlot(originalSlot, ownedSlot);
        }
        ownedSlot = originalSlot;
    }

    /** Cooldown, UI, vehicle and competing actions may delay a press, not discard it. */
    private boolean canWaitForThrow() {
        return mc.player != null && mc.world != null && mc.player.isAlive()
                && !mc.player.isSpectator() && findPearlSlot() >= 0;
    }

    private String blockedReason() {
        if (mc.player == null || mc.world == null || mc.interactionManager == null) return "Not in a world";
        if (mc.currentScreen != null) return "Close the open screen first";
        if (!mc.player.isAlive() || mc.player.isSpectator()) return "Not alive";
        if (mc.player.isRiding()) return "Cannot throw while steering a vehicle";
        if (mc.interactionManager.isBreakingBlock()) return "Busy breaking a block";
        int slot = findPearlSlot();
        if (slot < 0) return "No ender pearl in the hotbar";
        if (mc.player.getItemCooldownManager().isCoolingDown(mc.player.getInventory().getStack(slot))) {
            return "Ender pearl is cooling down";
        }
        ShieldDrainEvent drain = new ShieldDrainEvent();
        EventBus.post(drain);
        if (drain.isActive()) return "Shield drain in progress";
        return null;
    }

    private int findPearlSlot() {
        if (mc.player == null) return -1;
        int selected = mc.player.getInventory().getSelectedSlot();
        if (mc.player.getInventory().getStack(selected).isOf(Items.ENDER_PEARL)) return selected;
        for (int slot = 0; slot < 9; slot++) {
            if (mc.player.getInventory().getStack(slot).isOf(Items.ENDER_PEARL)) return slot;
        }
        return -1;
    }

    private void fail(String reason) {
        long now = System.currentTimeMillis();
        if (!reason.equals(lastWarning) || now - lastWarningMs > WARNING_REPEAT_MS) {
            Notifier.warn(getName(), reason);
        }
        lastWarning = reason;
        lastWarningMs = now;
        ClientInternalLog.info(getName(), "Cancelled: " + reason);
        restoreSlot();
        if (isEnabled()) toggle();
    }

    @EventTarget
    public void onCombatSwapLock(CombatSwapLockEvent event) {
        // A module's own lock never blocks its own action.
        if (event.isRequestedBy(this)) return;
        if (RotationManager.isExclusiveOwner(this) && RotationManager.isExclusiveLocked()) {
            event.setItemSwapLocked(true);
            event.setAutoAttackLocked(true);
        }
    }

    private void pauseCombatAssists() {
        AimAssist aim = ModuleAccess.getModule(AimAssist.class);
        if (aim != null) aim.externalPause(250L);
        AutoMace mace = ModuleAccess.getModule(AutoMace.class);
        if (mace != null) mace.externalPause(250L);
    }

    /** Idempotent, owner-scoped cleanup: this module's look and lock only; a queued slot restore stays queued. */
    private void release() {
        if (mc.player != null && (phase == Phase.AIMING || swapBackAtMs != 0L)) restoreSlot();
        RotationManager.clearTarget(this);
        RotationManager.releaseExclusiveLock(this);
        resetState();
    }

    private void resetState() {
        phase = Phase.IDLE;
        triggerMs = 0L;
        lastAimTick = Long.MIN_VALUE;
        target = null;
        originalSlot = -1;
        ownedSlot = -1;
        missReason = null;
        thrownTicks = 0;
        expectedFlightTicks = DEFAULT_FLIGHT_TICKS;
        plainMode = false;
        aimSteps = 0;
        committed = false;
        throwTurnYaw = 0.0f;
        throwTurnPitch = 0.0f;
        pearlSeen = false;
        ownPearlId = -1;
        reactionTicks = 0;
        aimTickStartNanos = 0L;
        lastPos = null;
        landedMs = 0L;
        repeatAtMs = 0L;
        swapBackAtMs = 0L;
        elytraWorn = false;
        unequipDone = false;
    }
}
