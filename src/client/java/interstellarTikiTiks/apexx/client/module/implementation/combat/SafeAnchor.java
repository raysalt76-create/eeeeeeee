package interstellarTikiTiks.apexx.client.module.implementation.combat;

import interstellarTikiTiks.apexx.client.module.BooleanSetting;
import interstellarTikiTiks.apexx.client.module.ModeSetting;
import interstellarTikiTiks.apexx.client.module.SliderSetting;
import interstellarTikiTiks.apexx.client.module.hooks.IncomingPacketInterceptor;
import interstellarTikiTiks.apexx.client.module.implementation.player.BotFilterProvider;
import interstellarTikiTiks.apexx.client.util.ModuleAccess;
import interstellarTikiTiks.apexx.client.util.PlaceResolver;
import interstellarTikiTiks.apexx.client.util.PlaceSolution;
import interstellarTikiTiks.apexx.client.util.RotationManager;
import interstellarTikiTiks.apexx.client.util.combat.AnchorCycle;
import interstellarTikiTiks.apexx.client.util.combat.AnchorPlanner;
import interstellarTikiTiks.apexx.client.util.combat.AnchorShieldPlanner;
import interstellarTikiTiks.apexx.client.util.combat.BlastModel;
import interstellarTikiTiks.apexx.client.util.combat.BlockPlacementValidator;
import interstellarTikiTiks.apexx.client.util.combat.CrystalPlacement;
import interstellarTikiTiks.apexx.client.util.combat.ModuleDelays;
import interstellarTikiTiks.apexx.client.util.combat.SmoothAim;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.ShapeContext;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemPlacementContext;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.network.packet.Packet;
import net.minecraft.network.packet.s2c.play.BlockUpdateS2CPacket;
import net.minecraft.network.packet.s2c.play.ChunkDeltaUpdateS2CPacket;
import net.minecraft.text.Text;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import net.minecraft.world.attribute.EnvironmentAttributes;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * SafeAnchor - shields you with glowstone, charges the anchor and blows it up, one click per tick.
 *
 * <h2>Sequence</h2>
 * <ol>
 *   <li><b>Shield</b> - glowstone into the free cell that cuts off the most of your body from the blast
 *       ({@link AnchorShieldPlanner}: vanilla's exposure samples over your box and next tick's box, the
 *       anchor's own cell ignored as it removes itself before exploding), against a solid support that is
 *       not the anchor, leaving a point of the anchor clickable. Skipped when existing blocks already cover
 *       you or a shield would not lower the damage. While a cell exists it is retried every tick until it
 *       lands or Shield Timeout runs out. With no cell the sequence goes straight on when the blast is
 *       safe without a shield (Health Buffer, Allow Totem Pop); when it is not, the search goes on until
 *       the timeout (you may step clear of a cell) and then the anchor is left uncharged.</li>
 *   <li><b>Charge</b> - glowstone into the anchor, only when the blast is safe for you and, with an enemy
 *       in Target Range, worth it (Min Target Damage, Out-Damage Only; both off by default).</li>
 *   <li><b>Explode</b> - the anchor clicked with a non-glowstone item (a totem first with Use Totem) on the
 *       next tick, from the same aim point as the charge.</li>
 * </ol>
 * The shield goes before the charge so the anchor is charged for a single tick and a shield that cannot
 * be placed never leaves a charged anchor behind. A look already on its face is clicked as it is, even
 * while another module holds the look; only a turn still to make needs the aim.
 *
 * <h2>Speed</h2>
 * With Same-Tick Turn the last part of each turn is committed right before the click
 * ({@code RotationManager.stepAim}), so the turn and its click share a tick and the server judges the
 * click with the look that tick's movement packet carries. A placed anchor is shielded, charged and
 * exploded on the next three ticks. Delays count from the previous click (the first from the anchor
 * placement), so values under a tick never add one. Block updates for the anchor and the shield cell are
 * read from the network to learn at once when the server refused the shield or removed the anchor.
 *
 * <h2>Why nothing happened</h2>
 * Every skipped, held or dropped step has a reason; Chat Diagnostics prints it to chat. Two vanilla
 * rules are checked up front because they make a cycle look dead: glowstone in the offhand makes vanilla
 * hand every main-hand click on the anchor to the offhand (it charges instead of setting it off), and
 * sneaking makes a click on the anchor place the held block instead of using the anchor.
 *
 * <p>Auto Place (off by default) places the anchor itself, while you hold right click with an anchor, at
 * the spot {@link AnchorPlanner} scores best against the nearest enemy (friends and bots skipped). Double
 * Anchor (off by default) places a second anchor into the same cell once the server has removed the
 * first, and blows it once the target's damage immunity is over. Nothing runs where anchors do not
 * explode (the Nether).</p>
 */
public final class SafeAnchor extends AnchorSequenceModule implements IncomingPacketInterceptor {

    private static final String NONE = "None";
    private static final String LEGIT = "Legit";
    private static final String SILENT = "Silent";
    /** Mochi's Aim Speed unit: degrees a second per point of the setting. */
    private static final double DEG_PER_SEC_PER_POINT = 60.0;
    /**
     * 1450 deg/s is 72.5 degrees a tick, under Intave's RotationModuloResetHeuristic (a yaw jump over 100
     * degrees onto an acquired target) and the 80 degree per tick ceiling AutoMace and AutoLunge keep.
     */
    private static final double MAX_DEG_PER_SEC = 1450.0;
    /** Below this predicted damage to you the shield is not worth a click. */
    private static final double SKIP_SHIELD_DAMAGE = 0.5;
    /** A shield must lower the predicted damage by at least this much to be placed. */
    private static final double MIN_SHIELD_GAIN = 0.5;
    /** How long a charge or explosion that is not yet safe or worth it is waited on before giving up. */
    private static final long HOLD_MS = 600L;
    /** Glowstone clicks on an anchor the world keeps showing uncharged before the anchor is left alone. */
    private static final int MAX_CHARGE_CLICKS = 2;
    /** Double Anchor: the second anchor goes out within this long after the first explosion. */
    private static final long DOUBLE_WINDOW_MS = 1500L;
    /** Double Anchor: the second explosion waits at most this long for the target's damage immunity. */
    private static final long IMMUNITY_WAIT_MS = 700L;
    /** Auto Place re-plans this often (ticks); the scan is a few thousand rays. */
    private static final int REPLAN_TICKS = 3;
    /** Chat Diagnostics repeats one reason at most this often. */
    private static final long DIAG_REPEAT_MS = 1000L;
    private static final String SNEAK_BLOCKER = "you are sneaking (a sneaked click places the held item instead of using the anchor)";

    private final ModeSetting rotate = new ModeSetting("Rotate", LEGIT, NONE, SILENT)
            .setDescription("None: no turns, clicks go where your crosshair is. Legit: the camera turns to each face. Silent: the turns are kept off your camera and sent to the server only.");
    private final SliderSetting aimSpeed = new SliderSetting("Aim Speed", 18.0, 2.0, 24.0, 0)
            .setDescription("Largest turn toward each face, 60 degrees a second per point: 2 is 120 degrees a second, 24 is 1440, the fastest turn that stays under the per-tick rotation limits.");
    private final BooleanSetting shield = new BooleanSetting("Glowstone Shield", true)
            .setDescription("Places glowstone where it blocks the most of the blast from your body before the anchor is charged. Skipped when blocks already cover you.");
    private final SliderSetting shieldTimeout = new SliderSetting("Shield Timeout (ms)", 450.0, 50.0, 1000.0, 0)
            .setDescription("How long the shield placement is retried (plus your ping). Past it the anchor is charged only if the blast is safe without a shield; otherwise it is left uncharged.");
    private final SliderSetting healthBuffer = new SliderSetting("Health Buffer", 4.0, 0.0, 20.0, 1)
            .setDescription("The anchor is not charged or blown while its predicted damage to you (after armor, protection, resistance, absorption and the shield) would leave less than this much health.");
    private final BooleanSetting allowTotemPop = new BooleanSetting("Allow Totem Pop", true)
            .setDescription("Still explode when the blast is too strong, as long as a totem is in your offhand or is the item the anchor is clicked with.");
    private final BooleanSetting sameTickTurn = new BooleanSetting("Same-Tick Turn", true)
            .setDescription("Finishes the last part of each turn right before its click, so the turn and the click share one tick (a step within Aim Speed and the rotation limits). Off: the look turns between ticks and each new face costs an extra tick.");
    private final SliderSetting targetRange = new SliderSetting("Target Range", 8.0, 3.0, 14.0, 1)
            .setDescription("Enemies within this many blocks of you count for Min Target Damage, Out-Damage Only, Auto Place and Double Anchor. Friends and bots are skipped.");
    private final SliderSetting minTargetDamage = new SliderSetting("Min Target Damage", 0.0, 0.0, 36.0, 1)
            .setDescription("The anchor is only charged when its blast is predicted to deal at least this much to an enemy in range. 0 turns the check off.");
    private final BooleanSetting outDamageOnly = new BooleanSetting("Out-Damage Only", false)
            .setDescription("With an enemy in range, the anchor is only charged when its blast hurts them more than it hurts you. No enemy in range: no check. Off: the anchor goes whenever it is safe for you.");
    private final BooleanSetting autoPlace = new BooleanSetting("Auto Place", false)
            .setDescription("While you hold right click with an anchor, places it at the spot that hurts the nearest enemy most for the least damage to you, then runs the sequence on it.");
    private final BooleanSetting doubleAnchor = new BooleanSetting("Double Anchor", false)
            .setDescription("After an explosion, places a second anchor in the same spot as soon as the server has removed the first, and blows it once the target's damage immunity is over.");
    private final BooleanSetting chatDiagnostics = new BooleanSetting("Chat Diagnostics", false)
            .setDescription("Prints to chat why a shield, charge or explosion was skipped, held or dropped (and when no shield was needed).");
    private final SliderSetting cooldown = new SliderSetting("Cooldown (ms)", 50.0, 0.0, 1000.0, 0)
            .withJitter(15.0).setSection("Delays")
            .setDescription("Pause after an explosion before the next anchor; your own use clicks are held back meanwhile.");
    private final SliderSetting chargeDelay = new SliderSetting("Charge Delay (ms)", 30.0, 0.0, 400.0, 0)
            .withJitter(10.0).setSection("Delays")
            .setDescription("Wait after the shield (or after the anchor is placed, with no shield) before the glowstone charge click. Under 50 ms never adds a tick.");
    private final SliderSetting shieldDelay = new SliderSetting("Shield Delay (ms)", 30.0, 0.0, 400.0, 0)
            .withJitter(10.0).setSection("Delays")
            .setDescription("Wait after the anchor is placed before the shield glowstone. Under 50 ms never adds a tick.");
    private final SliderSetting explodeDelay = new SliderSetting("Explode Delay (ms)", 30.0, 0.0, 400.0, 0)
            .withJitter(10.0).setSection("Delays")
            .setDescription("Wait after the charge before the explosion click. Under 50 ms never adds a tick.");
    private final SliderSetting placeDelay = new SliderSetting("Place Delay (ms)", 40.0, 0.0, 400.0, 0)
            .withJitter(10.0).setSection("Delays")
            .setDescription("Auto Place and Double Anchor: wait before placing an anchor (after the last explosion for the second anchor).");

    private enum Phase { NONE, SHIELD, CHARGE, EXPLODE, DONE }

    private Phase phase = Phase.NONE;
    /** When the current phase began (its timeout) and when its click may go out (its delay). */
    private long phaseSince;
    private long phaseFrom;
    private long readyAt;
    /** The shield this phase aims at, the cell a shield click went to, and when. */
    private AnchorShieldPlanner.Shield plannedShield;
    private BlockPos shieldCell;
    private long shieldSentAt = NEVER;
    private int shieldRetries;
    /** Why the shield click has not gone out yet, for the notice when it times out. */
    private String shieldBlocker;
    /** Glowstone clicks sent to this anchor; the world showing it uncharged after two means the server refuses them. */
    private int chargeClicks;
    /** Chat Diagnostics: when each reason was last printed. */
    private final Map<String, Long> diagAt = new HashMap<>();

    /** Auto Place / Double Anchor. */
    private boolean placing;
    private long placeReadyAt = NEVER;
    private AnchorPlanner.Plan autoPlan;
    private int planAge;
    private BlockPos doubleCell;
    private long doubleUntil;
    private PlayerEntity doubleTarget;
    private boolean nextIsSecond;
    private boolean secondAnchor;
    private long lastExplodeAt = NEVER;

    /** Block updates for the watched cells, handed over from the network thread. */
    private record BlockUpdate(BlockPos pos, BlockState state) {
    }

    private final ConcurrentLinkedQueue<BlockUpdate> updates = new ConcurrentLinkedQueue<>();
    private volatile BlockPos watchAnchor;
    private volatile BlockPos watchShield;
    private volatile BlockPos watchDouble;
    /** The server took the shield away (refused the placement) after it was sent. */
    private boolean shieldRefused;

    public SafeAnchor() {
        super("SafeAnchor", "Respawn anchor helper: places a glowstone shield where it blocks the most of the blast from you, charges the anchor and detonates it, one click per tick. Only charges when the blast is safe for you, tells you (Chat Diagnostics) why a step was skipped, and does nothing in the Nether where anchors do not explode.");
        addSetting(rotate);
        addSetting(aimSpeed);
        addSetting(shield);
        addSetting(shieldTimeout);
        addSetting(healthBuffer);
        addSetting(allowTotemPop);
        addSetting(sameTickTurn);
        addSetting(targetRange);
        addSetting(minTargetDamage);
        addSetting(outDamageOnly);
        addSetting(autoPlace);
        addSetting(doubleAnchor);
        addSetting(chatDiagnostics);
        addSetting(cooldown);
        addSetting(chargeDelay);
        addSetting(shieldDelay);
        addSetting(explodeDelay);
        addSetting(placeDelay);
        shieldDelay.setVisibility(shield::isEnabled);
        aimSpeed.setVisibility(() -> !rotate.is(NONE));
        sameTickTurn.setVisibility(() -> !rotate.is(NONE));
        shieldTimeout.setVisibility(shield::isEnabled);
        placeDelay.setVisibility(() -> autoPlace.isEnabled() || doubleAnchor.isEnabled());
    }

    @Override
    protected boolean isAimEnabled() {
        return !rotate.is(NONE);
    }

    @Override
    protected boolean isSilentAim() {
        return rotate.is(SILENT);
    }

    @Override
    protected double aimSpeedValue() {
        return Math.min(aimSpeed.getValue() * DEG_PER_SEC_PER_POINT, MAX_DEG_PER_SEC);
    }

    @Override
    protected float sameTickStepDeg() {
        if (!sameTickTurn.isEnabled() || !isAimEnabled()) return 0.0f;
        double perTick = aimSpeedValue() * 0.05;
        return (float) Math.min(perTick, RotationManager.aimStepCeiling());
    }

    @Override
    protected boolean usesSafeSequence() {
        return true;
    }

    @Override
    protected boolean hasSequenceState() {
        // DONE only waits for the next tick head to give the slot back; it must not hold the player's next
        // placement (vanilla's own use delay already spaces it).
        return phase != Phase.NONE && phase != Phase.DONE;
    }

    @Override
    protected boolean holdsPlayerUse() {
        return placing;
    }

    @Override
    protected int cooldownTicks() {
        return ModuleDelays.msToTicks(cooldown.sample());
    }

    /** Not used: the safe sequence spaces its clicks with its own Delays (see {@code enter}). */
    @Override
    protected long chargeGapMs() {
        return 0L;
    }

    /** Not used: the safe sequence spaces its clicks with its own Delays (see {@code enter}). */
    @Override
    protected long explodeGapMs() {
        return 0L;
    }

    @Override
    protected boolean waitsForRotationRelease() {
        // A new anchor takes the look straight from the glide back of the last one.
        return false;
    }

    @Override
    public void onEnable() {
        clearPlacing();
        doubleCell = null;
        doubleTarget = null;
        nextIsSecond = false;
        lastExplodeAt = NEVER;
        updates.clear();
        diagAt.clear();
        super.onEnable();
    }

    @Override
    public void onDisable() {
        super.onDisable();
        clearPlacing();
        doubleCell = null;
        doubleTarget = null;
        nextIsSecond = false;
        watchAnchor = watchShield = watchDouble = null;
        updates.clear();
    }

    @Override
    protected void onRuntimeStateReleased() {
        super.onRuntimeStateReleased();
        clearPlacing();
        doubleCell = null;
        doubleTarget = null;
        nextIsSecond = false;
        watchAnchor = watchShield = watchDouble = null;
        updates.clear();
    }

    @Override
    protected void onTrackingStarted(BlockPos anchor) {
        clearPlacing();
        plannedShield = null;
        shieldCell = null;
        shieldSentAt = NEVER;
        shieldRetries = 0;
        shieldBlocker = null;
        shieldRefused = false;
        chargeClicks = 0;
        lastClickBlocker = null;
        secondAnchor = nextIsSecond && anchor.equals(doubleCell);
        nextIsSecond = false;
        if (secondAnchor) {
            doubleCell = null;
            watchDouble = null;
        }
        watchAnchor = anchor;
        long now = System.currentTimeMillis();
        long from = lastPlacementAt != NEVER && now - lastPlacementAt < 1000L ? lastPlacementAt : now;
        enter(shield.isEnabled() ? Phase.SHIELD : charges(anchor) > 0 ? Phase.EXPLODE : Phase.CHARGE, from);
    }

    @Override
    protected void onSequenceReset() {
        phase = Phase.NONE;
        plannedShield = null;
        shieldCell = null;
        shieldSentAt = NEVER;
        shieldRetries = 0;
        shieldBlocker = null;
        shieldRefused = false;
        chargeClicks = 0;
        secondAnchor = false;
        watchAnchor = null;
        watchShield = null;
    }

    @Override
    protected void onSequenceAbandoned() {
        String why = mc.player == null || !mc.player.isAlive() ? "you died"
                : mc.currentScreen != null ? "a screen is open"
                : mc.player.isUsingItem() ? "you are using an item (eating, blocking, drawing a bow)"
                : mc.isPaused() ? "the game is paused"
                : "the client cannot run it (spectator or no world)";
        report("abandoned", "Anchor sequence dropped: " + why + ". The anchor is left as it is.");
    }

    // ------------------------------------------------------------------ notices

    /** A reason worth a notice: the notification sink and, with Chat Diagnostics, chat. */
    private void report(String key, String message) {
        warnMissing(key, message);
        diag(key, message);
    }

    /** Chat Diagnostics only (client thread): one line per reason, repeated at most once a second. */
    private void diag(String key, String message) {
        if (!chatDiagnostics.isEnabled() || mc == null || mc.inGameHud == null) return;
        long now = System.currentTimeMillis();
        Long last = diagAt.get(key);
        if (last != null && now - last < DIAG_REPEAT_MS) return;
        diagAt.put(key, now);
        try {
            mc.inGameHud.getChatHud().addMessage(Text.literal("[SafeAnchor] " + message));
        } catch (Throwable ignored) {
            // chat not ready: the notice is lost, never the sequence
        }
    }

    // ------------------------------------------------------------------ network

    /** Network thread: only hands the block updates for the watched cells over to the client thread. */
    @Override
    public boolean handleIncomingPacket(Packet<?> packet) {
        BlockPos anchor = watchAnchor;
        BlockPos shieldPos = watchShield;
        BlockPos second = watchDouble;
        if (anchor == null && shieldPos == null && second == null) return false;
        if (packet instanceof BlockUpdateS2CPacket update) {
            offer(update.getPos(), update.getState(), anchor, shieldPos, second);
        } else if (packet instanceof ChunkDeltaUpdateS2CPacket delta) {
            delta.visitUpdates((pos, state) -> offer(pos, state, anchor, shieldPos, second));
        }
        return false;
    }

    private void offer(BlockPos pos, BlockState state, BlockPos anchor, BlockPos shieldPos, BlockPos second) {
        if (updates.size() < 64 && (pos.equals(anchor) || pos.equals(shieldPos) || pos.equals(second))) {
            updates.add(new BlockUpdate(pos.toImmutable(), state));
        }
    }

    private void drainUpdates() {
        BlockUpdate update;
        while ((update = updates.poll()) != null) {
            if (shieldCell != null && update.pos().equals(shieldCell) && !update.state().isOf(Blocks.GLOWSTONE)) {
                shieldRefused = true;
            }
            if (doubleCell != null && update.pos().equals(doubleCell) && !update.state().isOf(Blocks.RESPAWN_ANCHOR)) {
                // The server removed the first anchor: the second may go out this tick.
                doubleUntil = Math.max(doubleUntil, System.currentTimeMillis() + 250L);
            }
        }
    }

    // ------------------------------------------------------------------ sequence

    @Override
    protected void stepSafeSequence() {
        drainUpdates();
        if (trackedAnchor == null || mc.interactionManager == null) {
            finishSequence();
            return;
        }
        if (!anchorsExplode(trackedAnchor)) {
            report("dimension", "Respawn anchors do not explode in this dimension (they set your spawn), so SafeAnchor leaves them alone.");
            finishSequence();
            return;
        }
        if (phase != Phase.DONE && mc.player.getOffHandStack().isOf(Items.GLOWSTONE)) {
            // Vanilla hands a main-hand click on a chargeable anchor to glowstone in the offhand: the
            // click with the explode item charges instead, so the anchor could never be set off.
            report("offhand-glowstone", "Glowstone in your offhand: every click on the anchor would charge it instead of setting it off. Move the glowstone to your hotbar. The anchor is left alone.");
            finishSequence();
            return;
        }
        switch (phase) {
            case NONE -> onTrackingStarted(trackedAnchor);
            case SHIELD -> shieldTick();
            case CHARGE -> chargeTick();
            case EXPLODE -> explodeTick();
            // The explosion's tick kept its look; the slot and the look go back from this one.
            case DONE -> finishSequence();
        }
    }

    private void shieldTick() {
        long now = System.currentTimeMillis();
        boolean charged = charges(trackedAnchor) > 0 || chargeInFlight();
        int glowstone = CrystalPlacement.hotbarSlot(mc.player, Items.GLOWSTONE);
        if (glowstone < 0) {
            skipShield("no glowstone in your hotbar");
            return;
        }
        if (!charged && hotbarCount(Items.GLOWSTONE) < 2) {
            skipShield("only one glowstone in your hotbar, it is needed for the charge");
            return;
        }
        double before = selfDamage(List.of());
        if (before <= SKIP_SHIELD_DAMAGE) {
            // Existing blocks (or distance) already cover you: no click needed, straight on this very tick.
            diag("shield-unneeded", String.format(Locale.ROOT,
                    "No glowstone shield needed: blocks or distance already cover you (%.1f predicted damage).", before));
            proceedWithoutShield(charged);
            return;
        }
        AnchorShieldPlanner.Plan plan = AnchorShieldPlanner.plan(mc, trackedAnchor, selfBoxes());
        if (plan.exposureBefore() <= 0.0) {
            diag("shield-unneeded", "No glowstone shield needed: " + plan.reason() + ".");
            proceedWithoutShield(charged);
            return;
        }
        boolean timedOut = now - phaseSince > (long) shieldTimeout.getValue() + CrystalPlacement.echoWaitMs(mc);
        AnchorShieldPlanner.Shield planned = plan.shield();
        if (planned == null) {
            // No cell this tick. Safe without a shield: straight on. Not safe: keep looking until the
            // timeout (a cell frees up as you move), then refuse with the reason.
            if (timedOut || withoutShieldStep(charged) != null) {
                skipShield(plan.reason());
                return;
            }
            shieldBlocker = plan.reason();
            aim(aimFacePoint());
            return;
        }
        double after = selfDamage(List.of(new Box(planned.cell())));
        if (before - after < MIN_SHIELD_GAIN) {
            diag("shield-unneeded", String.format(Locale.ROOT,
                    "No glowstone shield needed: the best cell would only lower the damage from %.1f to %.1f.", before, after));
            proceedWithoutShield(charged);
            return;
        }
        // Keep aiming at the same cell while it is still free and about as good: a new best every tick
        // would move the aim point under the turn.
        if (plannedShield == null || plannedShield.cell().equals(planned.cell())
                || planned.score() < plannedShield.score() - 5.0
                || !mc.world.getBlockState(plannedShield.cell()).isReplaceable()) {
            plannedShield = planned;
        }
        AnchorShieldPlanner.Shield target = plannedShield;
        if (timedOut) {
            skipShield(shieldBlocker != null ? shieldBlocker : "it did not line up in time");
            return;
        }
        aim(target.aimPoint());
        if (now < readyAt) return;
        // A look already on the support face is clicked as it is, whoever holds the look.
        BlockHitResult hit = shieldHit(target);
        if (hit == null) {
            if (rotate.is(NONE)) {
                // No turn will ever come: waiting for the timeout would only cost the cycle half a second.
                skipShield("Rotate is None and your crosshair is not on the shield's support face");
                return;
            }
            if (!isAiming()) {
                shieldBlocker = "another module holds the look";
                return;
            }
            if (finishTurn(target.aimPoint())) hit = shieldHit(target);
            if (hit == null) {
                shieldBlocker = "the look did not reach the shield's support face";
                return;
            }
        }
        boolean queued = swap.act(glowstone, () -> {
            BlockHitResult live = shieldHit(target);
            if (live == null || !mc.player.getMainHandStack().isOf(Items.GLOWSTONE)) {
                shieldBlocker = "the support face moved out of the look";
                return;
            }
            if (!click(live)) {
                shieldBlocker = "the glowstone click was refused";
                return;
            }
            shieldCell = target.cell();
            watchShield = shieldCell;
            shieldSentAt = System.currentTimeMillis();
            shieldRefused = false;
            enter(charged ? Phase.EXPLODE : Phase.CHARGE, shieldSentAt);
        });
        if (!queued) shieldBlocker = "the hotbar switch was refused (another module holds the slot, or an action already left this tick)";
    }

    /**
     * On to the charge (or the explosion) this very tick, with the delay counted from where the shield's
     * would have been, so a skipped shield never costs a tick.
     */
    private void proceedWithoutShield(boolean charged) {
        enter(charged ? Phase.EXPLODE : Phase.CHARGE, phaseFrom);
        if (phase == Phase.CHARGE) chargeTick();
        else explodeTick();
    }

    /** The step the cycle takes with no shield down, null when the blast is vetoed without one. */
    private AnchorCycle.Step withoutShieldStep(boolean charged) {
        return AnchorCycle.withoutShield(explodeVeto(List.of(), findExplodeSlot()), charged);
    }

    /**
     * No shield goes down: on when the blast is safe and worth it without one, otherwise the anchor is
     * left (still uncharged when it was). The reason goes to a notice either way.
     */
    private void skipShield(String reason) {
        boolean charged = charges(trackedAnchor) > 0 || chargeInFlight();
        String veto = explodeVeto(List.of(), findExplodeSlot());
        if (AnchorCycle.withoutShield(veto, charged) != null) {
            report("shield-skipped", "No glowstone shield: " + reason + ". The blast is safe without one.");
            proceedWithoutShield(charged);
            return;
        }
        report("shield-failed", "No glowstone shield: " + reason + ". " + veto
                + (charged ? "; the explosion is held." : "; the anchor is left uncharged."));
        finishSequence();
    }

    private void chargeTick() {
        long now = System.currentTimeMillis();
        if (charges(trackedAnchor) > 0) {
            // Charged already (the click went through, or someone else charged it).
            enter(Phase.EXPLODE, now);
            explodeTick();
            return;
        }
        // A charge sent a moment ago has not shown yet; wait for the world before charging again.
        if (chargeInFlight()) return;
        if (retryRefusedShield()) return;
        int glowstone = CrystalPlacement.hotbarSlot(mc.player, Items.GLOWSTONE);
        if (glowstone < 0) {
            report("no-glowstone", "No glowstone in your hotbar to charge the anchor.");
            finishSequence();
            return;
        }
        String veto = explodeVeto(inFlightCover(), findExplodeSlot());
        if (veto != null) {
            if (now - phaseSince > HOLD_MS + CrystalPlacement.echoWaitMs(mc)) {
                report("charge-held", "Anchor left uncharged: " + veto + ".");
                finishSequence();
                return;
            }
            aim(aimFacePoint());
            return;
        }
        if (now - phaseSince > CLICK_TIMEOUT_MS + CrystalPlacement.echoWaitMs(mc)) {
            report("charge-timeout", "The anchor was not charged: " + clickReason() + ". It is left uncharged.");
            finishSequence();
            return;
        }
        if (now < readyAt) {
            aim(aimFacePoint());
            return;
        }
        if (mc.player.shouldCancelInteraction()) {
            lastClickBlocker = SNEAK_BLOCKER;
            aim(aimFacePoint());
            return;
        }
        anchorClick(glowstone, () -> {
            ++chargeClicks;
            chargeSentAt = System.currentTimeMillis();
            enter(Phase.EXPLODE, chargeSentAt);
        });
    }

    private void explodeTick() {
        long now = System.currentTimeMillis();
        // A charge sent a moment ago is taken by the server before this explosion click (same connection,
        // in order), so the world need not show it first. Without a charge in flight, charge again.
        int slot;
        if (charges(trackedAnchor) <= 0) {
            slot = predictedExplodeSlot();
            if (slot < 0) {
                if (chargeInFlight()) return;
                if (chargeClicks >= MAX_CHARGE_CLICKS) {
                    report("charge-lost", "The anchor is still uncharged after " + chargeClicks
                            + " glowstone clicks: the server did not take them. It is left alone.");
                    finishSequence();
                    return;
                }
                enter(Phase.CHARGE, now);
                chargeTick();
                return;
            }
        } else {
            slot = findExplodeSlot();
        }
        if (slot < 0) {
            report("no-explode-item", "No hotbar item other than glowstone to set the anchor off with.");
            finishSequence();
            return;
        }
        if (now - phaseSince > CLICK_TIMEOUT_MS + HOLD_MS + CrystalPlacement.echoWaitMs(mc)) {
            report("explode-timeout", "The anchor was not set off: " + clickReason() + ". It is left charged.");
            finishSequence();
            return;
        }
        if (!selfSafe(slot, selfDamage(inFlightCover()))) {
            if (retryRefusedShield()) return;
            boolean shieldInFlight = shieldCell != null && !shieldRefused
                    && now - shieldSentAt < CrystalPlacement.echoWaitMs(mc);
            if (shieldInFlight || now - phaseSince <= HOLD_MS) {
                aim(aimFacePoint());
                return;
            }
            report("explode-held", "Explosion held: it would leave you under your Health Buffer. The anchor is left charged.");
            finishSequence();
            return;
        }
        // Double Anchor: the second blast waits for the target's damage immunity to run out.
        if (secondAnchor && doubleTarget != null && doubleTarget.isAlive() && doubleTarget.hurtTime > 0
                && now - phaseSince < IMMUNITY_WAIT_MS) {
            aim(aimFacePoint());
            return;
        }
        if (now < readyAt) {
            aim(aimFacePoint());
            return;
        }
        if (mc.player.shouldCancelInteraction()) {
            lastClickBlocker = SNEAK_BLOCKER;
            aim(aimFacePoint());
            return;
        }
        anchorClick(slot, () -> {
            ++explodeClicks;
            phase = Phase.DONE;
            onExploded();
            afterExplosion();
        });
    }

    /** Why the anchor click of this phase has not gone out, for its timeout notice. */
    private String clickReason() {
        return lastClickBlocker != null ? lastClickBlocker : "the anchor never came under the look";
    }

    /** A shield the server took back is placed again once; true while that retry is running. */
    private boolean retryRefusedShield() {
        if (shieldCell == null || shieldRetries >= 1 || !shield.isEnabled()) return false;
        boolean gone = !mc.world.getBlockState(shieldCell).isOf(Blocks.GLOWSTONE);
        boolean refused = shieldRefused || (gone && System.currentTimeMillis() - shieldSentAt > CrystalPlacement.echoWaitMs(mc));
        if (!refused || !gone) return false;
        ++shieldRetries;
        shieldCell = null;
        watchShield = null;
        shieldRefused = false;
        plannedShield = null;
        enter(Phase.SHIELD, System.currentTimeMillis());
        shieldTick();
        return true;
    }

    private void onExploded() {
        lastExplodeAt = System.currentTimeMillis();
        if (doubleAnchor.isEnabled() && !secondAnchor && trackedAnchor != null) {
            doubleCell = trackedAnchor;
            watchDouble = doubleCell;
            doubleUntil = lastExplodeAt + DOUBLE_WINDOW_MS + CrystalPlacement.echoWaitMs(mc);
            doubleTarget = bestTarget();
        }
    }

    private void enter(Phase next, long from) {
        phase = next;
        phaseSince = System.currentTimeMillis();
        phaseFrom = from;
        SliderSetting delay = switch (next) {
            case CHARGE -> chargeDelay;
            case SHIELD -> shieldDelay;
            case EXPLODE -> explodeDelay;
            default -> null;
        };
        readyAt = from + (delay == null ? 0L : ModuleDelays.clampMs(delay.sample()));
    }

    // ------------------------------------------------------------------ safety and targets

    /** Why the blast should not happen now (unsafe for you, not worth it against enemies), or null. */
    private String explodeVeto(List<Box> cover, int explodeSlot) {
        double self = selfDamage(cover);
        Double best = null;
        String name = null;
        for (PlayerEntity target : targets()) {
            double damage = targetDamage(target);
            if (best == null || damage > best) {
                best = damage;
                name = target.getName().getString();
            }
        }
        return AnchorCycle.veto(self, mc.player.getHealth(), healthBuffer.getValue(), allowTotemPop.isEnabled(),
                totemCovers(explodeSlot), best, name, minTargetDamage.getValue(), outDamageOnly.isEnabled());
    }

    /**
     * The blast leaves at least Health Buffer, or (Allow Totem Pop) a totem is in the offhand or is the
     * item the anchor is clicked with.
     */
    private boolean selfSafe(int slot, double damage) {
        return AnchorCycle.selfSafe(damage, mc.player.getHealth(), healthBuffer.getValue(), allowTotemPop.isEnabled(),
                totemCovers(slot));
    }

    /** A totem in the offhand, or as the item the anchor is set off with, covers a pop. */
    private boolean totemCovers(int slot) {
        return mc.player.getOffHandStack().isOf(Items.TOTEM_OF_UNDYING)
                || (slot >= 0 && mc.player.getInventory().getStack(slot).isOf(Items.TOTEM_OF_UNDYING));
    }

    /** Worst predicted damage to you over your box now and next tick's, with the anchor's own cell ignored. */
    private double selfDamage(List<Box> cover) {
        Vec3d blast = blockCenter(trackedAnchor);
        double worst = 0.0;
        for (Box box : selfBoxes()) {
            worst = Math.max(worst, BlastModel.damage(mc.world, mc.player, blast, BlastModel.ANCHOR_POWER, box,
                    trackedAnchor, cover));
        }
        return worst;
    }

    private List<Box> selfBoxes() {
        Box now = mc.player.getBoundingBox();
        Vec3d velocity = mc.player.getVelocity();
        if (velocity.lengthSquared() < 1.0e-4) return List.of(now);
        return List.of(now, now.offset(velocity));
    }

    /** A shield sent a moment ago that the world does not show yet still covers you. */
    private List<Box> inFlightCover() {
        if (shieldCell == null || shieldRefused || mc.world.getBlockState(shieldCell).isOf(Blocks.GLOWSTONE)
                || System.currentTimeMillis() - shieldSentAt > CrystalPlacement.echoWaitMs(mc)) {
            return List.of();
        }
        return List.of(new Box(shieldCell));
    }

    private double targetDamage(PlayerEntity target) {
        Vec3d blast = blockCenter(trackedAnchor);
        if (BlastModel.shieldBlocks(target, blast)) return 0.0;
        return BlastModel.damage(mc.world, target, blast, BlastModel.ANCHOR_POWER, target.getBoundingBox(),
                trackedAnchor, List.of());
    }

    private List<PlayerEntity> targets() {
        List<PlayerEntity> out = new ArrayList<>();
        for (PlayerEntity player : mc.world.getPlayers()) {
            if (AnchorPlanner.validTarget(mc, player, targetRange.getValue(), this::notBot)) out.add(player);
        }
        return out;
    }

    private PlayerEntity bestTarget() {
        PlayerEntity best = null;
        double bestDamage = 0.0;
        for (PlayerEntity target : targets()) {
            double damage = targetDamage(target);
            if (best == null || damage > bestDamage) {
                best = target;
                bestDamage = damage;
            }
        }
        return best;
    }

    private boolean notBot(PlayerEntity player) {
        for (BotFilterProvider filter : ModuleAccess.getEnabledHooks(BotFilterProvider.class)) {
            if (filter.isBot(player)) return false;
        }
        return true;
    }

    /** Anchors explode here (not where they set the spawn point, like the Nether). */
    private boolean anchorsExplode(BlockPos pos) {
        try {
            Object works = mc.world.getEnvironmentAttributes()
                    .getAttributeValue(EnvironmentAttributes.RESPAWN_ANCHOR_WORKS_GAMEPLAY, pos);
            if (works instanceof Boolean value) return !value;
        } catch (Throwable ignored) {
            // fall through to the dimension check
        }
        return mc.world.getRegistryKey() != World.NETHER;
    }

    private int hotbarCount(net.minecraft.item.Item item) {
        int count = 0;
        for (int slot = 0; slot < 9; ++slot) {
            ItemStack stack = mc.player.getInventory().getStack(slot);
            if (stack.isOf(item)) count += stack.getCount();
        }
        return count;
    }

    // ------------------------------------------------------------------ shield click

    /** The shield placement this tick's look makes, when it is the planned support face and cell. */
    private BlockHitResult shieldHit(AnchorShieldPlanner.Shield planned) {
        BlockHitResult live = BlockPlacementValidator.rayFromLook(mc, isSilentAim());
        if (live == null || !live.getBlockPos().equals(planned.support()) || live.getSide() != planned.side()
                || !planned.cell().equals(BlockPlacementValidator.placementCell(mc, live))) {
            return null;
        }
        ItemPlacementContext context = new ItemPlacementContext(mc.player, Hand.MAIN_HAND,
                Items.GLOWSTONE.getDefaultStack(), live);
        return context.getBlockPos().equals(planned.cell()) && context.canPlace()
                && mc.world.canPlace(Blocks.GLOWSTONE.getDefaultState(), planned.cell(), ShapeContext.of(mc.player))
                ? live : null;
    }

    // ------------------------------------------------------------------ Auto Place / Double Anchor

    @Override
    protected void stepIdle() {
        drainUpdates();
        boolean wasPlacing = placing;
        placing = false;
        if (mc.player == null || mc.world == null || !anchorsExplode(mc.player.getBlockPos())) {
            clearPlacing();
            return;
        }
        long now = System.currentTimeMillis();
        BlockPos cell = null;
        if (doubleCell != null) {
            if (!doubleAnchor.isEnabled() || now > doubleUntil) {
                doubleCell = null;
                watchDouble = null;
            } else if (doubleTarget != null && (doubleTarget.isRemoved() || doubleTarget.getEntityWorld() != mc.world)) {
                doubleCell = null;
                watchDouble = null;
                doubleTarget = null;
            } else if (mc.world.getBlockState(doubleCell).isOf(Blocks.RESPAWN_ANCHOR)) {
                // Waiting for the server to remove the first anchor (its block update is applied before
                // this tick runs, so the second goes out on the first tick the cell is free).
                placing = true;
                return;
            } else if (AnchorPlanner.usable(mc.world, mc.player, doubleCell)) {
                cell = doubleCell;
            } else {
                doubleCell = null;
                watchDouble = null;
            }
        }
        if (cell == null) {
            if (!autoPlace.isEnabled() || !mc.options.useKey.isPressed()
                    || !mc.player.getMainHandStack().isOf(Items.RESPAWN_ANCHOR)) {
                clearPlacing();
                return;
            }
            if (autoPlan == null || ++planAge >= REPLAN_TICKS || !AnchorPlanner.usable(mc.world, mc.player, autoPlan.cell())) {
                autoPlan = AnchorPlanner.plan(mc, new AnchorPlanner.Params(targetRange.getValue(),
                        Math.max(1.0, minTargetDamage.getValue()), Double.MAX_VALUE, true, true, this::notBot));
                planAge = 0;
            }
            if (autoPlan == null) {
                // No spot worth it: your own click places the anchor where you look, as without Auto Place.
                clearPlacing();
                return;
            }
            cell = autoPlan.cell();
        }
        if (CrystalPlacement.hotbarSlot(mc.player, Items.GLOWSTONE) < 0) {
            report("no-glowstone", "No glowstone in your hotbar: SafeAnchor does not place anchors it cannot charge.");
            clearPlacing();
            doubleCell = null;
            return;
        }
        int anchorSlot = CrystalPlacement.hotbarSlot(mc.player, Items.RESPAWN_ANCHOR);
        if (anchorSlot < 0) {
            clearPlacing();
            doubleCell = null;
            return;
        }
        PlaceSolution solution = PlaceResolver.resolvePlacement(mc, cell, mc.player.getBlockInteractionRange());
        if (solution == null || !solution.placedAt().equals(cell)) {
            if (cell.equals(doubleCell)) {
                doubleCell = null;
                watchDouble = null;
            }
            autoPlan = null;
            clearPlacing();
            return;
        }
        placing = true;
        if (!wasPlacing || placeReadyAt == NEVER) {
            long from = cell.equals(doubleCell) && lastExplodeAt != NEVER ? lastExplodeAt : now;
            placeReadyAt = from + ModuleDelays.clampMs(placeDelay.sample());
        }
        Vec3d point = SmoothAim.nearestFacePoint(mc, solution.against(), solution.side());
        if (point == null) point = solution.hitVec();
        aim(point);
        if (isAimEnabled() && !isAiming()) return;
        if (now < placeReadyAt) return;
        BlockPos target = cell;
        BlockHitResult hit = placeHit(solution, target);
        if (hit == null && finishTurn(point)) hit = placeHit(solution, target);
        if (hit == null) return;
        boolean second = target.equals(doubleCell);
        swap.act(anchorSlot, () -> {
            BlockHitResult live = placeHit(solution, target);
            if (live == null || !mc.player.getMainHandStack().isOf(Items.RESPAWN_ANCHOR) || !click(live)) return;
            nextIsSecond = second;
            placeReadyAt = NEVER;
            autoPlan = null;
            expectPlacedAnchor(target);
        });
    }

    /** The anchor placement this tick's look makes, when it lands in {@code cell} off the planned face. */
    private BlockHitResult placeHit(PlaceSolution solution, BlockPos cell) {
        BlockHitResult live = BlockPlacementValidator.rayFromLook(mc, isSilentAim());
        if (live == null || !live.getBlockPos().equals(solution.against()) || live.getSide() != solution.side()
                || !cell.equals(BlockPlacementValidator.placementCell(mc, live))) {
            return null;
        }
        ItemPlacementContext context = new ItemPlacementContext(mc.player, Hand.MAIN_HAND,
                Items.RESPAWN_ANCHOR.getDefaultStack(), live);
        return context.getBlockPos().equals(cell) && context.canPlace()
                && mc.world.canPlace(Blocks.RESPAWN_ANCHOR.getDefaultState(), cell, ShapeContext.of(mc.player))
                ? live : null;
    }

    private void clearPlacing() {
        placing = false;
        placeReadyAt = NEVER;
        autoPlan = null;
        planAge = 0;
    }
}
