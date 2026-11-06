package interstellarTikiTiks.apexx.client.module.implementation.combat;

import interstellarTikiTiks.apexx.client.friend.FriendManager;
import interstellarTikiTiks.apexx.client.module.implementation.player.BotFilterProvider;
import interstellarTikiTiks.apexx.client.util.ModuleAccess;
import interstellarTikiTiks.apexx.client.util.PlaceResolver;
import interstellarTikiTiks.apexx.client.util.PlaceSolution;
import interstellarTikiTiks.apexx.client.util.combat.SubTickSequencer;
import interstellarTikiTiks.apexx.client.event.EventBus;
import interstellarTikiTiks.apexx.client.event.EventTarget;
import interstellarTikiTiks.apexx.client.event.impl.CombatSwapLockEvent;
import interstellarTikiTiks.apexx.client.event.impl.PearlModuleExecuteEvent;
import interstellarTikiTiks.apexx.client.event.impl.PearlThrowEvent;
import interstellarTikiTiks.apexx.client.module.BooleanSetting;
import interstellarTikiTiks.apexx.client.module.Category;
import interstellarTikiTiks.apexx.client.module.Module;
import interstellarTikiTiks.apexx.client.module.ModeSetting;
import interstellarTikiTiks.apexx.client.module.SliderSetting;
import interstellarTikiTiks.apexx.client.module.StringSetting;
import interstellarTikiTiks.apexx.client.module.SubCategory;
import interstellarTikiTiks.apexx.client.module.hooks.AttackGatingProvider;
import interstellarTikiTiks.apexx.client.module.hooks.AttackTargetAware;
import interstellarTikiTiks.apexx.client.module.hooks.ItemUseBlockProvider;
import interstellarTikiTiks.apexx.client.util.AttackValidator;
import interstellarTikiTiks.apexx.client.util.RotationManager;
import interstellarTikiTiks.apexx.client.util.combat.BlastModel;
import interstellarTikiTiks.apexx.client.util.combat.BlockPlacementValidator;
import interstellarTikiTiks.apexx.client.util.combat.CrystalHeadbob;
import interstellarTikiTiks.apexx.client.util.combat.CrystalPlacement;
import interstellarTikiTiks.apexx.client.util.combat.InteractSim;
import interstellarTikiTiks.apexx.client.util.combat.ModuleDelays;
import interstellarTikiTiks.apexx.client.util.combat.PearlFollowSolver;
import interstellarTikiTiks.apexx.client.util.combat.PearlThrow;
import interstellarTikiTiks.apexx.client.util.combat.PlayerMotionSimulation;
import interstellarTikiTiks.apexx.client.util.combat.PlayerTrajectoryEngine;
import interstellarTikiTiks.apexx.client.util.combat.ThrowPhysics;
import interstellarTikiTiks.apexx.client.util.combat.ServerAim;
import interstellarTikiTiks.apexx.client.util.combat.SmoothAim;
import interstellarTikiTiks.apexx.client.util.combat.SwapAction;
import interstellarTikiTiks.apexx.client.util.combat.TickActionOrder;
import interstellarTikiTiks.apexx.client.util.combat.WebTrapPlanner;
import net.minecraft.block.Blocks;
import net.minecraft.block.ShapeContext;
import net.minecraft.entity.Entity;
import net.minecraft.entity.decoration.EndCrystalEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemPlacementContext;
import net.minecraft.item.Items;
import net.minecraft.registry.tag.ItemTags;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.EntityHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.client.network.PlayerListEntry;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.GameMode;

/**
 * AutoHitCrystal - two modes.
 *
 * <p><b>Mode Auto: crystal aura.</b> While an Allowed Item is in the main hand (and, with Require
 * Sword Hit, inside the Hit Window), a player in Target Range and Max Angle (friends, bots and a sticky
 * lock respected) is picked by Target Mode. After the Activation Delay the run engages once something
 * scores, and {@link HitCrystalAura} ranks the reachable crystals, bases and (with Auto Place) obsidian
 * cells by the damage on the target's predicted box (Predict Movement) against Min Efficiency, Max Self
 * Damage and Anti Suicide. The chosen action is committed: the look stays on it until it is done, until
 * it fails ({@link HitCrystalAura#rescore}), or until, after a short dwell, another scores clearly more.
 * The target is kept while usable and in range (Max Angle only picks a new one). One action a tick:
 * break the crystal, else place on the base, else place obsidian and wait for it. Each action turns the
 * look (Aim Speed, Silent Aim, Headbob for a crystal over the obsidian edge) and fires only on the tick
 * the live ray reaches the base or the crystal, with the slot swap first and the click later in that
 * tick; with nothing to do the look is released. Place, Break, Activation and Switch Back delays and
 * Crystal Speed pace it. The run ends when no target is left or nothing scores for a while, and the slot
 * goes back. Optimization (blatant) can re-place in the break's tick (Rapid Fire) or drop the hit crystal
 * client-side at once (Predict). Pearl Follow chases the target after the first break.</p>
 *
 * <p><b>Mode Manual (default): the hit-crystal combo.</b> With a sword in hand, a right click held on a placeable
 * block face places obsidian there, then spams crystals on it (place, break, place, ...). When the
 * obsidian is out of reach, you let go of use, or the crystals run out, it goes back to the sword slot.
 *
 * <p><b>Trigger.</b> With a sword in hand, holding use polls the crosshair every tick; the first tick it
 * is on a block face obsidian can go against (or on obsidian or bedrock already) the run starts. With
 * Require Sword Hit on, a sword hit on a player must have opened the window (400 ms by default) first.
 * Presses shorter than a tick are caught from the use hook, and a press that starts a run is kept from
 * vanilla (so an offhand gapple or shield is not used instead). After a run ends, use has to be let go
 * before the next one starts.
 *
 * <p><b>Auto Place (default off).</b> A sword attack attempt starts a terrain scan without a use
 * press. A 7x7 patch is rebuilt around the live opponent each tick, down through the blast envelope. The
 * closest supported, reachable obsidian cell below their feet and with room for a crystal is aimed
 * at and placed. Placement waits for the live ray, then the base must appear before crystals start.
 * The automatic run ends when that opponent leaves the base's blast radius. The attack hook does
 * not confirm server damage or knockback; all placement decisions use observed positions.</p>
 *
 * <p><b>Headbob.</b> Standing under the obsidian you see its side but not the crystal on top. With
 * Headbob on, when the crosshair is not on the crystal, the look is turned up and over the obsidian
 * edge to a point of the crystal's hitbox that the live ray reaches first, the crystal is hit on the
 * tick the ray gets there, and the look is released from the next tick. Silent Aim (off by default)
 * keeps that turn off your camera.
 *
 * <p><b>Pearl Follow.</b> With Pearl Follow on, a set time after the first crystal has been hit (the
 * knockback is then on the player you sword-hit) the module maps where that player is flying
 * ({@link PlayerMotionSimulation}), solves a pearl that lands on the ground a block or two ahead of them
 * ({@link PearlFollowSolver}), turns to it and throws it on a tick the look applied lands it with Follow
 * Confidence. The throw is the whole step: after it the run ends and the module waits for the next sword
 * hit and ground right click. The pearl item cooldown is never overridden.
 *
 * <p><b>Timing.</b> One action per tick. A slot selection goes out first and the click follows later
 * in that same tick ({@link SwapAction}); breaks go through {@link AttackValidator}. Every wait is this
 * module's own jittered slider in Delays (Place, Break, Follow, Switch Back). The swap-back is deferred
 * to the head of a later movement interval. Reach is vanilla's: entity reach to hit a crystal, block
 * reach to place, and a base whose crystal could not be hit from here is never used. A crystal whose
 * estimated blast would kill you (no totem held) is not hit.</p>
 */
public final class AutoHitCrystal extends Module implements AttackTargetAware, ItemUseBlockProvider, AttackGatingProvider, interstellarTikiTiks.apexx.client.module.hooks.ClickSimulating {

    /** RETURN: use was let go (or the run ended) with the look away from rest; breaks the last crystal and goes home. */
    private enum Phase { IDLE, OBSIDIAN, WAIT_OBSIDIAN, CRYSTALS, RETURN, FOLLOW }

    /** The look counts as back at the starting pose within this many degrees. */
    private static final double REST_TOLERANCE = 0.6;
    /** The return gives up (and releases the look) this long after it starts. */
    private static final long RETURN_LIMIT_MS = 1800L;

    /** A run with nothing to do for this long (plus the round trip) ends. */
    private static final long IDLE_END_MS = 600L;
    private static final int MAX_UNANSWERED = 3;
    private static final long NEVER = Long.MIN_VALUE;
    private static final int NO_AGE = Integer.MIN_VALUE;
    /** The pearl step gives up this long after it starts if no throw lands. */
    private static final long FOLLOW_WINDOW_MS = 1500L;
    private static final int FOLLOW_MARGIN_TICKS = 8;
    /** End crystal explosion power. */
    private static final float CRYSTAL_POWER = 6.0f;

    /** Manual first: saved configs and fresh installs keep the proven combo unless Auto is chosen. */
    private final ModeSetting mode = new ModeSetting("Mode", "Manual", "Auto");
    private final ModeSetting targetMode = new ModeSetting("Target Mode", "Distance", "Angle", "Armor", "Health");
    private final SliderSetting targetRange = new SliderSetting("Target Range", 6.0, 1.0, 12.0, 1);
    private final SliderSetting maxAngle = new SliderSetting("Max Angle", 120.0, 10.0, 360.0, 0);
    private final SliderSetting minEfficiency = new SliderSetting("Min Efficiency (%)", 50.0, 0.0, 100.0, 0);
    private final SliderSetting maxSelfDamage = new SliderSetting("Max Self Damage", 10.0, 0.0, 20.0, 1);
    private final BooleanSetting antiSuicide = new BooleanSetting("Anti Suicide", true);
    private final BooleanSetting predictMovement = new BooleanSetting("Predict Movement", true);
    private final ModeSetting optimization = new ModeSetting("Optimization", "None", "Rapid Fire", "Predict");
    private final SliderSetting rapidMinEfficiency = new SliderSetting("Rapid Min Efficiency (%)", 50.0, 0.0, 100.0, 0);
    private final SliderSetting activationDelay = new SliderSetting("Activation Delay (ms)", 80.0, 0.0, 500.0, 0)
            .withJitter(30.0).setSection("Delays")
            .setDescription("Auto mode: time from a target first being in range (with an allowed item in hand) to the run engaging. 0 engages on the first tick it can.");
    private final BooleanSetting autoPlace = new BooleanSetting("Auto Place", false)
            .setDescription("Manual mode: after a manual or TriggerBot sword attack on a player, maps nearby terrain and aims to place obsidian below their feet with clear crystal space, then runs crystals without holding use until they leave blast radius. Auto mode: lets the aura place obsidian for a crystal when no existing base scores well enough. Does nothing when no placement fits.");
    private final BooleanSetting requireHit = new BooleanSetting("Require Sword Hit", false)
            .setDescription("Off: right clicking a block face while holding a sword starts the combo. On: it only starts within the Hit Window after you sword-hit a player, so ordinary right clicks are never hijacked.");
    private final SliderSetting hitWindow = new SliderSetting("Hit Window (ms)", 400.0, 100.0, 1000.0, 0)
            .setDescription("How long after a sword hit, in ms, a right click can still start the combo (or Auto Place can still pick an obsidian spot). Longer is more forgiving.");
    // ---- Allowed Items: what must be in your main hand for a right click or a hit to start the combo ----
    private final BooleanSetting allowSword = new BooleanSetting("Sword", true)
            .setSection("Allowed Items")
            .setDescription("A sword in hand can start the combo (the classic hit-crystal).");
    private final BooleanSetting allowAxe = new BooleanSetting("Axe", false)
            .setSection("Allowed Items")
            .setDescription("An axe in hand can start the combo.");
    private final BooleanSetting allowPickaxe = new BooleanSetting("Pickaxe", false)
            .setSection("Allowed Items")
            .setDescription("A pickaxe in hand can start the combo.");
    private final BooleanSetting allowMace = new BooleanSetting("Mace", false)
            .setSection("Allowed Items")
            .setDescription("A mace in hand can start the combo.");
    private final BooleanSetting allowTotem = new BooleanSetting("Totem", false)
            .setSection("Allowed Items")
            .setDescription("A totem of undying in your main hand can start the combo.");
    private final BooleanSetting allowAny = new BooleanSetting("Any Item", false)
            .setSection("Allowed Items")
            .setDescription("Anything in hand (even an empty hand) can start the combo. Careful: right clicking a block face with a block or a usable item then starts the combo instead of the normal use.");
    private final StringSetting allowCustom = new StringSetting("Custom Items", "")
            .setSection("Allowed Items")
            .setDescription("Extra item ids that can start the combo, comma separated (for example: stick, minecraft:blaze_rod).");

    private final BooleanSetting headbob = new BooleanSetting("Headbob", true)
            .setSection("Headbob")
            .setDescription("When the crosshair is not on the crystal (for example standing under the obsidian), rises along a diagonal over the edge to the crystal and hits it, then comes back down to place the next one. Letting go of use breaks the last crystal and returns the look first.");
    private final ModeSetting hand = new ModeSetting("Hand", CrystalHeadbob.RIGHTY, CrystalHeadbob.LEFTY);
    private final SliderSetting headMovement = new SliderSetting("Head Movement", 15.0, 0.0, 100.0, 0);
    private final BooleanSetting heightVariation = new BooleanSetting("Height Variation", true);
    private final BooleanSetting silentAim = new BooleanSetting("Silent Aim", false)
            .setSection("Headbob")
            .setDescription("Keeps Headbob and Auto Place turns off your camera and sends them to the server only. Off by default.");
    private final SliderSetting aimSpeed = new SliderSetting("Aim Speed", 28.0, 10.0, 72.0, 0)
            .setSection("Headbob")
            .setDescription("Largest turn, in degrees a tick (x20 a second), the look makes on its way to the crystal and back to the obsidian; 72 is 1440 degrees a second, the fastest that stays under the per-tick rotation limits. The camera turns every render frame.");
    private final SliderSetting crystalSpeed = new SliderSetting("Crystal Speed", 18.0, 2.0, 20.0, 0)
            .setDescription("Most crystal actions (a place or a break) per second. 20 is one per tick, the fastest vanilla allows; lower spaces them whole ticks apart. Place Delay and Break Delay add on top.");
    private final BooleanSetting pearlFollow = new BooleanSetting("Pearl Follow", false)
            .setDescription("A set time after the first crystal hit, throws a pearl to land a block or two ahead of the player you sword-hit, along the way their knockback is carrying them. One throw, then the run ends.");
    private final SliderSetting followDelay = new SliderSetting("Follow Delay (ms)", 250.0, 0.0, 1500.0, 0)
            .withJitter(40.0).setSection("Delays")
            .setDescription("Time after the first crystal hit before the pearl step starts. The knockback must be on the player by then.");
    private final SliderSetting breakDelay = new SliderSetting("Break Delay (ms)", 20.0, 0.0, 300.0, 0)
            .withJitter(12.0).setSection("Delays")
            .setDescription("Reaction time between the live ray reaching a crystal and the hit. Hits are made on ticks, so the value is rounded to whole ticks (on average it matches the setting). 0 hits on the tick the ray gets there.");
    private final SliderSetting placeDelay = new SliderSetting("Place Delay (ms)", 25.0, 0.0, 300.0, 0)
            .withJitter(12.0).setSection("Delays")
            .setDescription("Reaction time between the base being free (obsidian down, or the last crystal gone) and the next crystal going on it. Rounded to whole ticks on average; 0 places on the first tick it can.");
    private final SliderSetting switchBackDelay = new SliderSetting("Switch Back Delay (ms)", 60.0, 0.0, 500.0, 0)
            .withJitter(25.0).setSection("Delays")
            .setDescription("How long after the combo ends before your original slot is selected again. 0 switches back at the next tick, the soonest it can.");
    private final SliderSetting followDistance = new SliderSetting("Follow Distance", 1.5, 1.0, 2.0, 1)
            .setDescription("How far ahead of the knocked-back player, in blocks, the follow-up pearl should land.");
    private final SliderSetting followConfidence = new SliderSetting("Follow Confidence", 0.6, 0.2, 1.0, 2)
            .setDescription("How sure the pearl prediction must be (0.2-1) that it lands ahead of the player before throwing. Higher throws less often but more accurately.");
    private final BooleanSetting switchBack = new BooleanSetting("Switch Back", true)
            .setDescription("Puts you back on the slot you held before the combo (your sword) when it ends, unless you switched slots yourself.");

    private final SwapAction swap = new SwapAction(this);
    private final CrystalHeadbob bob = new CrystalHeadbob();
    /** Highest the look has risen above the start pose in this run, degrees of pitch. */
    private double peakUp;
    private long returnSince;
    private Phase phase = Phase.IDLE;
    private Object boundPlayer;
    private Object boundWorld;
    private long swordHitAt = NEVER;
    private boolean pressQueued;
    private boolean autoPending;
    private boolean automatic;
    /** A run ended while use was held: the next run needs use let go and pressed again. */
    private boolean needsRelease;
    private long swordAttackTick = NEVER;
    private long obsidianSentAt;
    private BlockPos base;
    private long lastActionAt;
    private BlockPos pendingBase;
    private long pendingSince;
    private int unanswered;
    private int lastBrokenId = -1;
    private long lastBrokenAt;
    /** The client tick (player age) of the last place or break; Crystal Speed spaces actions from it. */
    private int lastActionAge = NO_AGE;
    /** The client tick a break was last dispatched on, so the return keeps that look to the tick's end. */
    private int lastAttackAge = NO_AGE;
    private boolean aiming;
    private PlayerEntity swordTarget;
    private long firstBreakAt = NEVER;
    private long followSince = NEVER;
    private boolean followDone;
    private PearlFollowSolver.Shot followShot;
    private long followSolvedTick = Long.MIN_VALUE;
    /** Follow Delay sampled at the first break of a run. */
    private long followDelayMs;
    /** Break Delay bookkeeping: the crystal the ray reached, the tick it did, and the sampled wait in ticks. */
    private int breakDelayCrystal = -1;
    private int breakDelayArmedAge = NO_AGE;
    private int breakDelayTicks;
    /** Place Delay bookkeeping: the tick the base was first seen free, and the sampled wait in ticks. */
    private int placeArmedAge = NO_AGE;
    private int placeDelayTicks;
    /** A swap-back is due at {@link #restoreAtAge} (Switch Back Delay). */
    private boolean restorePending;
    private int restoreAtAge;
    /** Auto mode: the run is the aura's (not a Manual combo), the player it fights and the plan it acts on. */
    private final HitCrystalAura planner = HitCrystalAura.create();
    private boolean auraRun;
    private PlayerEntity auraTarget;
    private HitCrystalAura.Plan currentPlan;
    /** The tick (player age) the current plan was committed on. */
    private int planSinceAge = NO_AGE;
    /** A committed plan is kept at least this many ticks before a clearly better one may replace it. */
    private static final int PLAN_MIN_DWELL_TICKS = 8;
    /** "Clearly better": at least this factor of the committed plan's damage, and at least PLAN_BETTER_MIN more. */
    private static final double PLAN_BETTER_FACTOR = 1.35;
    private static final double PLAN_BETTER_MIN = 1.0;
    /** Activation Delay: when a target was first seen while idle, and the sampled wait. */
    private long auraArmedAt = NEVER;
    private long auraDelayMs;
    /** The tick we last hit the aura target (Predict Movement then adds the knockback). */
    private int knockedAge = NO_AGE;

    public AutoHitCrystal() {
        super("AutoHitCrystal", "The classic hit-crystal combo with an allowed item in hand (a sword by default, see Allowed Items): right click to place obsidian and cycle crystals on it (Mode Manual). Mode Auto is a crystal aura: picks a target, then places and breaks end crystals on the bases that hurt them most within your self-damage limits. Optional Pearl Follow chases the knocked-back player.", Category.COMBAT);
        setSubCategory(SubCategory.CRYSTAL);
        mode.setDescription("Manual (default): the hit-crystal combo - right click a block face (holding an allowed item) to place obsidian and cycle crystals on it while use is held. Auto: crystal aura - with an allowed item in hand it picks a target and places and breaks crystals on its own.");
        targetMode.setSection("Aura").setDescription("Which player the aura fights when several are in range: the closest, the one nearest your crosshair, the one with the least armour, or the one with the least health.");
        targetRange.setSection("Aura").setDescription("How far away, in blocks, a player can be to be picked as the aura's target.");
        maxAngle.setSection("Aura").setDescription("Only players within this angle around your crosshair (horizontally) are picked. 360 is all around.");
        minEfficiency.setSection("Aura").setDescription("How much of a point-blank crystal's damage (0-100%) a place or a break must do to the target, on its predicted position, before the aura does it.");
        maxSelfDamage.setSection("Aura").setDescription("The most health a crystal may take from you (estimate) for the aura to place or break it.");
        antiSuicide.setSection("Aura").setDescription("Never places or breaks a crystal whose estimated blast would take all your health.");
        predictMovement.setSection("Aura").setDescription("Rates crystals on where the target will be a few ticks from now (with the knockback of your last hit on them) instead of where they stand.");
        optimization.setSection("Aura").setBlatant(true).setDescription("None: one action a tick, waiting for the server to remove a hit crystal (vanilla cadence). Rapid Fire: re-places a crystal in the same tick it breaks one. Predict: removes a hit crystal on your side at once so the next place does not wait for the server. Both are faster than a player can click.");
        rapidMinEfficiency.setSection("Aura").setBlatant(true).setDescription("Rapid Fire only re-places in the break's tick when the crystal was at least this efficient (0-100%).");
        addSetting(mode);
        addSetting(targetMode);
        addSetting(targetRange);
        addSetting(maxAngle);
        addSetting(minEfficiency);
        addSetting(maxSelfDamage);
        addSetting(antiSuicide);
        addSetting(predictMovement);
        addSetting(optimization);
        addSetting(rapidMinEfficiency);
        targetMode.setVisibility(this::auraMode);
        targetRange.setVisibility(this::auraMode);
        maxAngle.setVisibility(this::auraMode);
        minEfficiency.setVisibility(this::auraMode);
        maxSelfDamage.setVisibility(this::auraMode);
        antiSuicide.setVisibility(this::auraMode);
        predictMovement.setVisibility(this::auraMode);
        optimization.setVisibility(this::auraMode);
        rapidMinEfficiency.setVisibility(() -> auraMode() && optimization.is("Rapid Fire"));
        addSetting(autoPlace);
        addSetting(requireHit);
        addSetting(hitWindow);
        hitWindow.setVisibility(() -> requireHit.isEnabled() || autoPlace.isEnabled());
        addSetting(allowSword);
        addSetting(allowAxe);
        addSetting(allowPickaxe);
        addSetting(allowMace);
        addSetting(allowTotem);
        addSetting(allowAny);
        addSetting(allowCustom);
        hand.setSection("Headbob").setDescription("Which side the rise leans toward. Righty rises up and to the right (a line from left-bottom to right-top), Lefty the mirror.");
        headMovement.setSection("Headbob").setDescription("How far the look goes into the crystal on each rise. Low turns only as far as the crystal needs, so the head bobs as little as it can; high goes deeper.");
        heightVariation.setSection("Headbob").setDescription("Varies the depth of each rise a little from bob to bob (skewed, never a fixed range). Off keeps every rise the same.");
        addSetting(headbob);
        addSetting(hand);
        hand.setVisibility(headbob::isEnabled);
        addSetting(headMovement);
        headMovement.setVisibility(headbob::isEnabled);
        addSetting(heightVariation);
        heightVariation.setVisibility(headbob::isEnabled);
        addSetting(silentAim);
        silentAim.setVisibility(() -> headbob.isEnabled() || autoPlace.isEnabled() || auraMode());
        addSetting(aimSpeed);
        aimSpeed.setVisibility(() -> headbob.isEnabled() || autoPlace.isEnabled() || auraMode());
        addSetting(crystalSpeed);
        addSetting(pearlFollow);
        addSetting(followDelay);
        addSetting(followDistance);
        addSetting(followConfidence);
        addSetting(switchBack);
        addSetting(placeDelay);
        addSetting(breakDelay);
        addSetting(switchBackDelay);
        addSetting(activationDelay);
        activationDelay.setVisibility(this::auraMode);
        switchBackDelay.setVisibility(switchBack::isEnabled);
        followDelay.setVisibility(pearlFollow::isEnabled);
        followDistance.setVisibility(pearlFollow::isEnabled);
        followConfidence.setVisibility(pearlFollow::isEnabled);
    }

    @Override
    public void onEnable() {
        EventBus.register(this);
        resetState(true);
    }

    @Override
    public void onDisable() {
        finish(true);
    }

    @Override
    protected void onRuntimeStateReleased() {
        finish(true);
        resetState(false);
    }

    @EventTarget
    public void onCombatSwapLock(CombatSwapLockEvent event) {
        if (event.isRequestedBy(this)) return;
        if (phase != Phase.IDLE) {
            event.setItemSwapLocked(true);
            event.setAutoAttackLocked(true);
        }
    }

    // ------------------------------------------------------------------ hooks

    /** A sword hit on a player opens the window. */
    @Override
    public void onAttack(Entity target) {
        if (mc.player != null && target != null && target == auraTarget) {
            // Predict Movement adds this hit's knockback to the target's forecast.
            knockedAge = mc.player.age;
        }
        if (mc.player == null || mc.world == null || !(target instanceof PlayerEntity) || phase != Phase.IDLE
                || !holdingSword()
                || !followTargetUsable((PlayerEntity) target)
                || !interstellarTikiTiks.apexx.client.util.combat.StickyTargets.allows(this, target)) {
            // A friend or a bot never opens the hit window.
            return;
        }
        swordHitAt = System.currentTimeMillis();
        swordTarget = (PlayerEntity) target;
        swordAttackTick = SubTickSequencer.currentTick();
        // In Auto mode the aura places obsidian itself; the hit only opens the window.
        autoPending = !auraMode() && autoPlace.isEnabled() && validAutoTarget();
    }

    /**
     * Called from every vanilla use. Inside the window with a sword in hand it records the press, so a
     * click shorter than a tick still counts. A press that is going to start a run is kept from vanilla:
     * otherwise vanilla would go on to the offhand (a gapple starts eating, a shield goes up) and the run
     * could never begin. While a run is going it holds vanilla's own use back so only this module clicks.
     */
    @Override
    public boolean shouldBlockItemUse() {
        if (phase != Phase.IDLE) return true;
        // Auto mode never takes over an ordinary right click.
        if (auraMode()) return false;
        if (mc.player == null || mc.world == null || mc.currentScreen != null || autoPending
                || mc.player.isUsingItem() || !holdingSword() || !inWindow(System.currentTimeMillis())) {
            return false;
        }
        boolean startable = startPhase(BlockPlacementValidator.rayFromLook(mc, silentAim.isEnabled())) != null;
        if (startable && !needsRelease) pressQueued = true;
        return startable;
    }

    /**
     * A manual left click while a run is acting would be a second action in the module's tick, a hit with
     * the crystal or obsidian the module has selected, or the start of mining the base. The module breaks
     * the crystals itself, so those clicks are held back; a click on anything else still goes through.
     */
    @Override
    public boolean shouldGateAttackInput() {
        if (phase == Phase.IDLE || phase == Phase.FOLLOW || mc.player == null) return false;
        if (lastActionAge == mc.player.age || swap.queuedThisTick() || swap.holdsSlot()) return true;
        HitResult target = mc.crosshairTarget;
        if (target instanceof EntityHitResult entityHit) return entityHit.getEntity() instanceof EndCrystalEntity;
        return target instanceof BlockHitResult blockHit && base != null && blockHit.getBlockPos().equals(base);
    }

    // ------------------------------------------------------------------ tick

    @Override
    public void onTick() {
        if (!canRun()) {
            abort();
            return;
        }
        if (mc.player != boundPlayer || mc.world != boundWorld) {
            resetState(true);
        }
        bob.configure(hand.getCurrentMode(), headMovement.getValue(), heightVariation.isEnabled());
        if (swap.takeUserSlotChange()) {
            // The player took the hotbar: the slot is left as chosen and nothing is restored. A look that
            // is away from rest still comes home first.
            swap.reset(true);
            restorePending = false;
            pendingBase = null;
            if (automatic) finish(false);
            else endRun();
            autoPending = false;
        }
        boolean useDown = mc.options.useKey.isPressed();
        if (needsRelease && !useDown) needsRelease = false;
        boolean pressed = pressQueued;
        pressQueued = false;
        boolean held = useDown || pressed;
        if (swap.queuedThisTick()) return;
        long now = System.currentTimeMillis();

        if (auraMode() ? phase == Phase.IDLE || auraRun : auraRun) {
            // Auto mode (or an aura run still finishing after a switch to Manual).
            auraTick(now);
            return;
        }

        if (phase == Phase.IDLE) {
            releaseAim();
            idleRestore();
            if (autoPending) {
                if (!autoPlace.isEnabled() || !validAutoTarget() || !holdingSword()
                        || now - swordHitAt > Math.round(hitWindow.getValue())) {
                    autoPending = false;
                    return;
                }
                // The sword attack closes slot switching for its interval. Start at a later tick head.
                if (SubTickSequencer.currentTick() == swordAttackTick || !TickActionOrder.isWindowOpen()
                        || !TickActionOrder.canSwitchSlot()) return;
                if (CrystalPlacement.hotbarSlot(mc.player, Items.OBSIDIAN) < 0
                        || CrystalPlacement.hotbarSlot(mc.player, Items.END_CRYSTAL) < 0) {
                    autoPending = false;
                    return;
                }
                resetRun(now);
                automatic = true;
                autoPending = false;
                phase = Phase.OBSIDIAN;
            } else if (needsRelease || !held || !inWindow(now) || !holdingSword() || !tryStart(now)) {
                return;
            }
        } else if (phase == Phase.FOLLOW) {
            // Only the pearl step runs; use being let go or the target leaving the base no longer matter.
        } else if (automatic && (!autoPlace.isEnabled() || !validAutoTarget()
                || (base != null && !HitCrystalTerrain.inBlastRadius(swordTarget.getEntityPos(), base)))) {
            // Do not break another crystal after the target has left this base's blast radius. The
            // crystal knockback is exactly what carries them out of it, so an armed Pearl Follow takes
            // over here instead of the run simply ending (it used to end before the pearl was ever due).
            if (enterFollow()) return;
            finish(false);
            return;
        } else if (phase != Phase.RETURN && !automatic && !held) {
            // Hold only: letting go ends the run, after the last crystal is broken and the look is home.
            // An armed Pearl Follow keeps the run alive for the throw instead.
            if (enterFollow()) return;
            endRun();
            if (phase == Phase.IDLE) return;
        }

        boolean aimed = false;
        switch (phase) {
            case OBSIDIAN -> {
                if (automatic) aimed = autoObsidianStep(now);
                else aimed = obsidianStep(now);
            }
            case WAIT_OBSIDIAN -> {
                aimed = aiming;
                if (base != null && CrystalPlacement.isBase(mc.world, base)) {
                    phase = Phase.CRYSTALS;
                    lastActionAt = now;
                } else if (now - obsidianSentAt > CrystalPlacement.spawnWaitMs(mc)) {
                    finish(false);
                }
            }
            case CRYSTALS -> aimed = followStep(now) || crystalStep(now);
            case RETURN -> aimed = returnStep(now);
            case FOLLOW -> {
                aimed = followStep(now);
                if (phase == Phase.FOLLOW && (followDone
                        || now - firstBreakAt > followDelayMs + FOLLOW_WINDOW_MS + 500L)) {
                    finish(false);
                    return;
                }
            }
            default -> { }
        }
        // A look held from an earlier turn is not let go in the tick of the action it served: the same
        // request stays alive to the end of that tick and is released from the next.
        if (!aimed && phase != Phase.RETURN && lastActionAge != mc.player.age && !swap.queuedThisTick()) {
            releaseAim();
        }
        if ((phase == Phase.OBSIDIAN || phase == Phase.CRYSTALS)
                && now - lastActionAt > IDLE_END_MS + 2L * CrystalPlacement.spawnWaitMs(mc)) {
            endRun();
        }
    }

    // ------------------------------------------------------------------ start

    private boolean tryStart(long now) {
        BlockHitResult hit = BlockPlacementValidator.rayFromLook(mc, silentAim.isEnabled());
        Phase start = startPhase(hit);
        if (start == null) return false;
        resetRun(now);
        base = start == Phase.CRYSTALS ? hit.getBlockPos().toImmutable() : null;
        phase = start;
        return true;
    }

    /**
     * The phase a run would start in from {@code hit}: CRYSTALS on a base whose crystal can be hit from
     * here, OBSIDIAN on a face obsidian can go against with room and reach for a crystal, else null.
     */
    private Phase startPhase(BlockHitResult hit) {
        if (hit == null || CrystalPlacement.hotbarSlot(mc.player, Items.END_CRYSTAL) < 0) return null;
        if (CrystalPlacement.isBase(mc.world, hit.getBlockPos())) {
            return crystalReachable(hit.getBlockPos()) ? Phase.CRYSTALS : null;
        }
        if (CrystalPlacement.hotbarSlot(mc.player, Items.OBSIDIAN) < 0 || !canPlaceObsidian(hit)) return null;
        BlockPos cell = BlockPlacementValidator.placementCell(mc, hit);
        return cell != null && crystalReachable(cell) ? Phase.OBSIDIAN : null;
    }

    /** Clears every per-run counter; the hotbar ownership is kept so a new run still returns home. */
    private void resetRun(long now) {
        lastActionAt = now;
        lastActionAge = NO_AGE;
        pendingBase = null;
        unanswered = 0;
        lastBrokenId = -1;
        firstBreakAt = NEVER;
        followSince = NEVER;
        followDone = false;
        followShot = null;
        breakDelayCrystal = -1;
        breakDelayArmedAge = NO_AGE;
        placeArmedAge = NO_AGE;
        restorePending = false;
        currentPlan = null;
        planSinceAge = NO_AGE;
        bob.end();
        peakUp = 0.0;
    }

    // ------------------------------------------------------------------ obsidian

    /** Rebuild the horizontal patch and all candidate heights from the live target every tick. */
    private boolean autoObsidianStep(long now) {
        if (!validAutoTarget() || now - swordHitAt > Math.round(hitWindow.getValue())) {
            finish(false);
            return false;
        }
        int slot = CrystalPlacement.hotbarSlot(mc.player, Items.OBSIDIAN);
        if (slot < 0) {
            finish(false);
            return false;
        }
        // Sort cheap geometric candidates first; expensive support raycasts stop at the first valid cell.
        PlaceSolution[] chosen = new PlaceSolution[1];
        BlockPos cell = HitCrystalTerrain.closest(swordTarget.getBoundingBox(), candidate -> {
            if (!autoCellClear(candidate) || outOfBlockReach(candidate) || !crystalReachable(candidate)) return false;
            PlaceSolution solution = autoPlacement(candidate);
            if (solution == null) return false;
            chosen[0] = solution;
            return true;
        });
        if (cell == null) return false;
        PlaceSolution solution = chosen[0];
        if (!SmoothAim.aimPoint(this, solution::hitVec, aimDegPerSec(), silentAim.isEnabled())) return false;
        aiming = true;
        BlockHitResult hit = BlockPlacementValidator.hitForPlacement(mc, cell, silentAim.isEnabled());
        if (hit == null || !autoSupportUsable(hit) || !canPlaceObsidian(hit) || !actionReady()
                || !TickActionOrder.isWindowOpen() || !TickActionOrder.canSwitchSlot()) return true;
        swap.act(slot, () -> {
            // The target's box is re-read here: in Legit mode this runs later in the tick, after the
            // entities may have moved, so the cell is re-checked against where they are now.
            if (mc.world == null || !automatic || phase != Phase.OBSIDIAN || !autoPlace.isEnabled()
                    || !validAutoTarget() || !TickActionOrder.isWindowOpen() || !TickActionOrder.canSwitchSlot()
                    || !HitCrystalTerrain.belowTarget(swordTarget.getBoundingBox(), cell)
                    || !HitCrystalTerrain.inBlastRadius(swordTarget.getEntityPos(), cell)
                    || !autoCellClear(cell) || mc.player.isUsingItem()
                    || !mc.player.getMainHandStack().isOf(Items.OBSIDIAN)) return;
            BlockHitResult live = BlockPlacementValidator.hitForPlacement(mc, cell, silentAim.isEnabled());
            if (live == null || !autoSupportUsable(live) || !canPlaceObsidian(live)) return;
            if (!InteractSim.interactBlockOncePerTick(mc, live, silentAim.isEnabled())) return;
            base = cell.toImmutable();
            obsidianSentAt = System.currentTimeMillis();
            lastActionAt = obsidianSentAt;
            markAction();
            placeArmedAge = NO_AGE;
            phase = Phase.WAIT_OBSIDIAN;
            holdLook();
        });
        return true;
    }

    private boolean autoCellClear(BlockPos cell) {
        return cell.getY() >= mc.world.getBottomY() && cell.getY() < mc.world.getTopYInclusive()
                && mc.world.getWorldBorder().contains(cell)
                && mc.world.getBlockState(cell).isReplaceable()
                && CrystalPlacement.crystalFits(mc.world, cell)
                && mc.world.canPlace(Blocks.OBSIDIAN.getDefaultState(), cell, ShapeContext.of(mc.player));
    }

    private boolean autoSupportUsable(BlockHitResult hit) {
        return mc.player.isSneaking() || !WebTrapPlanner.isInteractive(mc.world, hit.getBlockPos(),
                mc.world.getBlockState(hit.getBlockPos()));
    }

    private PlaceSolution autoPlacement(BlockPos cell) {
        PlaceSolution preferred = PlaceResolver.resolvePlacement(mc, cell, mc.player.getBlockInteractionRange());
        if (autoSolutionUsable(preferred)) return preferred;
        // The least-turn face may be an interactive block, or only a corner may be exposed. Try
        // the other supports before discarding this otherwise closer terrain cell.
        for (Direction direction : Direction.values()) {
            BlockPos support = cell.offset(direction);
            if (mc.world.getBlockState(support).isReplaceable()) continue;
            Vec3d point = SmoothAim.nearestFacePoint(mc, support, direction.getOpposite());
            if (point == null) continue;
            PlaceSolution alternative = new PlaceSolution(support, direction.getOpposite(), point);
            if (autoSolutionUsable(alternative)) return alternative;
        }
        return null;
    }

    private boolean autoSolutionUsable(PlaceSolution solution) {
        if (solution == null) return false;
        BlockHitResult hit = new BlockHitResult(solution.hitVec(), solution.side(), solution.against(), false);
        return autoSupportUsable(hit) && canPlaceObsidian(hit);
    }

    private boolean autoRunStillValid() {
        if (auraRun) {
            return auraTargetUsable(auraTarget) && base != null
                    && HitCrystalTerrain.inBlastRadius(auraTarget.getEntityPos(), base);
        }
        return !automatic || (autoPlace.isEnabled() && validAutoTarget() && base != null
                && HitCrystalTerrain.inBlastRadius(swordTarget.getEntityPos(), base));
    }

    private boolean validAutoTarget() {
        if (swordTarget == null || swordTarget == mc.player || !swordTarget.isAlive() || swordTarget.isRemoved()
                || swordTarget.isSpectator() || swordTarget.isCreative() || mc.world == null
                || mc.world.getEntityById(swordTarget.getId()) != swordTarget) return false;
        FriendManager friends = FriendManager.getInstance();
        if (friends.isFriend(swordTarget.getUuid()) || friends.isFriend(swordTarget.getName().getString())) return false;
        for (BotFilterProvider filter : ModuleAccess.getEnabledHooks(BotFilterProvider.class)) {
            if (filter.isBot(swordTarget)) return false;
        }
        return true;
    }

    /** Manual obsidian on the face the crosshair is on. True when it used the aim request this tick. */
    private boolean obsidianStep(long now) {
        int slot = CrystalPlacement.hotbarSlot(mc.player, Items.OBSIDIAN);
        if (slot < 0) {
            finish(false);
            return false;
        }
        BlockHitResult hit = BlockPlacementValidator.rayFromLook(mc, silentAim.isEnabled());
        if (hit == null) return false;
        if (CrystalPlacement.isBase(mc.world, hit.getBlockPos()) && crystalReachable(hit.getBlockPos())) {
            // The crosshair moved onto obsidian already standing: no need for another block.
            base = hit.getBlockPos().toImmutable();
            phase = Phase.CRYSTALS;
            return false;
        }
        if (!canPlaceObsidian(hit)) return false;
        BlockPos cell = BlockPlacementValidator.placementCell(mc, hit);
        if (cell == null || !crystalReachable(cell) || !actionReady()) return false;
        swap.act(slot, () -> {
            if (mc.world == null || phase != Phase.OBSIDIAN) return;
            BlockHitResult live = BlockPlacementValidator.rayFromLook(mc, silentAim.isEnabled());
            if (live == null || !live.getBlockPos().equals(hit.getBlockPos()) || live.getSide() != hit.getSide()
                    || !cell.equals(BlockPlacementValidator.placementCell(mc, live)) || mc.player.isUsingItem()
                    || !mc.player.getMainHandStack().isOf(Items.OBSIDIAN) || !canPlaceObsidian(live)) {
                return;
            }
            if (!InteractSim.interactBlockOncePerTick(mc, live, silentAim.isEnabled())) return;
            base = cell.toImmutable();
            phase = Phase.CRYSTALS;
            lastActionAt = now;
            markAction();
            placeArmedAge = NO_AGE;
            holdLook();
        });
        return false;
    }

    /** Obsidian can go into the cell this face leads to, with room above it for a crystal. */
    private boolean canPlaceObsidian(BlockHitResult hit) {
        BlockPos cell = BlockPlacementValidator.placementCell(mc, hit);
        if (cell == null || !mc.world.isAir(cell.up())) return false;
        ItemPlacementContext context = new ItemPlacementContext(mc.player, Hand.MAIN_HAND, Items.OBSIDIAN.getDefaultStack(), hit);
        return context.getBlockPos().equals(cell) && context.canPlace()
                && mc.world.canPlace(Blocks.OBSIDIAN.getDefaultState(), cell, ShapeContext.of(mc.player));
    }

    // ------------------------------------------------------------------ crystals

    /** One crystal step. Returns true when it used the aim request this tick. */
    private boolean crystalStep(long now) {
        if (automatic && (!TickActionOrder.isWindowOpen() || !TickActionOrder.canSwitchSlot())) return aiming;
        BlockPos working = workingBase();
        if (working == null || outOfBlockReach(working)) {
            endRun();
            return false;
        }
        if (bob.isActive()) peakUp = Math.max(peakUp, bob.risen());
        EndCrystalEntity crystal = crystalAt(working);
        if (crystal != null) {
            pendingBase = null;
            unanswered = 0;
            placeArmedAge = NO_AGE;
            if (crystal.getId() == lastBrokenId && now - lastBrokenAt < echoWaitMs()) {
                // Already hit; waiting for the server to remove it. The look stays where it is.
                return aiming;
            }
            return breakStep(crystal, now);
        }
        return placeStep(working, now);
    }

    /** The base the crosshair is on if any (obsidian or bedrock), else the one this run placed. */
    private BlockPos workingBase() {
        if (automatic) return base != null && CrystalPlacement.isBase(mc.world, base) ? base : null;
        BlockHitResult hit = BlockPlacementValidator.rayFromLook(mc, silentAim.isEnabled());
        if (hit != null && !hit.getBlockPos().equals(base) && CrystalPlacement.isBase(mc.world, hit.getBlockPos())
                && crystalReachable(hit.getBlockPos())) {
            base = hit.getBlockPos().toImmutable();
            placeArmedAge = NO_AGE;
        }
        return base != null && CrystalPlacement.isBase(mc.world, base) ? base : null;
    }

    /**
     * The crystal on {@code working}: the one standing on it if there is one, else any crystal whose box
     * fills the space above it (a neighbour's crystal there blocks the next place, so it is hit too).
     */
    private EndCrystalEntity crystalAt(BlockPos working) {
        BlockPos top = working.up();
        EndCrystalEntity blocking = null;
        for (EndCrystalEntity crystal : mc.world.getEntitiesByClass(EndCrystalEntity.class, new Box(top), Entity::isAlive)) {
            if (crystal.isRemoved()) continue;
            if (crystal.getBlockPos().equals(top)) return crystal;
            if (blocking == null) blocking = crystal;
        }
        return blocking;
    }

    /** Places a crystal on the base. True when it used the aim request this tick. */
    private boolean placeStep(BlockPos working, long now) {
        int slot = CrystalPlacement.hotbarSlot(mc.player, Items.END_CRYSTAL);
        if (slot < 0) {
            endRun();
            return false;
        }
        if (working.equals(pendingBase)) {
            // Placed and not shown yet: the spawn takes the round trip plus a server tick. Placing again
            // before then would put a second crystal packet on a spot the first one already holds.
            if (now - pendingSince < CrystalPlacement.spawnWaitMs(mc)) return aiming;
            pendingBase = null;
            if (++unanswered >= MAX_UNANSWERED) {
                endRun();
                return false;
            }
        }
        if (!CrystalPlacement.crystalFits(mc.world, working)) return aiming;
        // A crystal that could not be hit from here is never placed: it would only sit there.
        if (!crystalReachable(working)) return false;
        boolean delayDone = placeDelayElapsed();
        BlockHitResult hit = BlockPlacementValidator.rayFromLook(mc, silentAim.isEnabled());
        boolean aimed = false;
        if (hit == null || !hit.getBlockPos().equals(working)) {
            // The look is still up over the obsidian from the last break: come back down onto the base so
            // the next crystal goes out (without this the run stopped after one crystal). Along the arc
            // home while the bob has a rest pose; from the nearest face point when it is home already.
            if (!headbob.isEnabled() && !scripted()) return false;
            // The aura's base can be anywhere around, so its look goes straight to the base's nearest
            // face: the way home via the bob's rest pose is Manual's, where rest is the base itself.
            if (!auraRun && bob.isActive() && !bob.atRest(REST_TOLERANCE)) {
                float[] home = bob.downGoal(peakUp);
                if (!CrystalHeadbob.aim(this, home[0], home[1], aimDegPerSec(), silentAim.isEnabled())) return false;
            } else {
                Vec3d point = SmoothAim.nearestFacePoint(mc, working, null);
                if (point == null) return false;
                if (!SmoothAim.aimPoint(this, () -> point, aimDegPerSec(), silentAim.isEnabled())) return false;
            }
            aiming = true;
            aimed = true;
            hit = BlockPlacementValidator.rayFromLook(mc, silentAim.isEnabled());
            if (hit == null || !hit.getBlockPos().equals(working)) return true;
        }
        if (!delayDone || !actionReady()) return aimed || aiming;
        swap.act(slot, () -> {
            if (mc.world == null) return;
            BlockHitResult live = BlockPlacementValidator.rayFromLook(mc, silentAim.isEnabled());
            if (!autoRunStillValid() || (scripted() && (!TickActionOrder.isWindowOpen() || !TickActionOrder.canSwitchSlot()))
                    || live == null || !live.getBlockPos().equals(working) || mc.player.isUsingItem()
                    || !CrystalPlacement.isBase(mc.world, working) || !CrystalPlacement.crystalFits(mc.world, working)
                    || !mc.player.getMainHandStack().isOf(Items.END_CRYSTAL)) {
                return;
            }
            if (!InteractSim.interactBlockOncePerTick(mc, live, silentAim.isEnabled())) return;
            holdLook();
            pendingBase = working.toImmutable();
            pendingSince = now;
            lastActionAt = now;
            placeArmedAge = NO_AGE;
            markAction();
        });
        return aimed || aiming;
    }

    /** Breaks the crystal: straight from the crosshair, or after a Headbob turn onto it. */
    private boolean breakStep(EndCrystalEntity crystal, long now) {
        int slot = breakSlot();
        if (slot == -2) {
            endRun();
            return false;
        }
        double reach = mc.player.getEntityInteractionRange();
        Vec3d eye = mc.player.getEyePos();
        if (crystal.getBoundingBox().squaredMagnitude(eye) > reach * reach) return false;
        if (!breakSurvivable(crystal)) return false;
        if (rayHits(crystal, reach)) {
            if (!actionReady() || !breakDelayElapsed(crystal)) return aiming;
            attack(crystal, slot, now);
            return aiming;
        }
        if (!headbob.isEnabled() && !scripted()) return false;
        float[] goal = null;
        if (headbob.isEnabled() || automatic) {
            bob.begin(silentAim.isEnabled());
            goal = bob.upGoal(crystal, null, reach);
        }
        // The camera turns toward the crystal every render frame; eligibility is the live ray of the look
        // those frames produced, checked on the tick, not an angle threshold.
        if (goal != null) {
            if (!CrystalHeadbob.aim(this, goal[0], goal[1], aimDegPerSec(), silentAim.isEnabled())) return false;
        } else if (auraRun) {
            // The aura's crystal can be anywhere around: when no rise over an edge reaches it (or Headbob is
            // off), turn straight onto the middle of its box.
            Vec3d middle = crystal.getBoundingBox().getCenter();
            if (!SmoothAim.aimPoint(this, () -> middle, aimDegPerSec(), silentAim.isEnabled())) return false;
        } else {
            return false;
        }
        aiming = true;
        if (rayHits(crystal, reach) && actionReady() && breakDelayElapsed(crystal)) {
            attack(crystal, slot, now);
        }
        return true;
    }

    /**
     * Break Delay: the hit waits a sampled reaction time after the live ray first reaches this crystal,
     * instead of landing on the very tick the turn gets there. Ticks, rounded probabilistically.
     */
    private boolean breakDelayElapsed(EndCrystalEntity crystal) {
        int age = mc.player.age;
        if (crystal.getId() != breakDelayCrystal || breakDelayArmedAge == NO_AGE || age < breakDelayArmedAge) {
            breakDelayCrystal = crystal.getId();
            breakDelayArmedAge = age;
            breakDelayTicks = ModuleDelays.msToTicks(breakDelay.sample());
        }
        return age - breakDelayArmedAge >= breakDelayTicks;
    }

    /** Place Delay: counted from the first tick the base is seen free, in probabilistically rounded ticks. */
    private boolean placeDelayElapsed() {
        int age = mc.player.age;
        if (placeArmedAge == NO_AGE || age < placeArmedAge) {
            placeArmedAge = age;
            placeDelayTicks = ModuleDelays.msToTicks(placeDelay.sample());
        }
        return age - placeArmedAge >= placeDelayTicks;
    }

    /**
     * Whether hitting this crystal is survivable: never a hit whose estimated blast takes all of the
     * health left, unless a totem is held. {@link BlastModel} is an estimate (UNVERIFIED against the game).
     */
    private boolean breakSurvivable(EndCrystalEntity crystal) {
        // The aura already applied Max Self Damage; with Anti Suicide off the user accepts a lethal hit.
        if (auraRun && !antiSuicide.isEnabled()) return true;
        if (mc.player.isCreative() || mc.player.getOffHandStack().isOf(Items.TOTEM_OF_UNDYING)
                || mc.player.getMainHandStack().isOf(Items.TOTEM_OF_UNDYING)) {
            return true;
        }
        try {
            double damage = BlastModel.damage(mc.world, mc.player, crystal.getEntityPos(), CRYSTAL_POWER);
            return !(damage >= mc.player.getHealth());
        } catch (RuntimeException estimateFailed) {
            return true;
        }
    }

    /**
     * Switches the run to the pearl step when Pearl Follow is armed: a crystal has been broken this run,
     * a pearl is in the hotbar, and there is a player to follow (the one sword-hit, else the nearest
     * valid player near the base). True when the run is now following.
     */
    private boolean enterFollow() {
        if (!pearlFollow.isEnabled() || followDone || firstBreakAt == NEVER
                || CrystalPlacement.hotbarSlot(mc.player, Items.ENDER_PEARL) < 0 || PearlThrow.cooling(mc)) {
            return false;
        }
        if (!followTargetUsable(swordTarget)) swordTarget = nearestFollowTarget();
        if (swordTarget == null) return false;
        // The automatic-run validity checks no longer apply: the target is meant to leave the base.
        automatic = false;
        auraRun = false;
        auraTarget = null;
        currentPlan = null;
        autoPending = false;
        phase = Phase.FOLLOW;
        return true;
    }

    private boolean followTargetUsable(PlayerEntity target) {
        if (target == null || target == mc.player || !target.isAlive() || target.isRemoved()
                || target.isSpectator() || mc.world.getEntityById(target.getId()) != target) return false;
        FriendManager friends = FriendManager.getInstance();
        if (friends.isFriend(target.getUuid()) || friends.isFriend(target.getName().getString())) return false;
        for (BotFilterProvider filter : ModuleAccess.getEnabledHooks(BotFilterProvider.class)) {
            if (filter.isBot(target)) return false;
        }
        return true;
    }

    /** No sword hit recorded: the nearest valid player to the base this run broke crystals on. */
    private PlayerEntity nearestFollowTarget() {
        Vec3d center = base != null ? Vec3d.ofCenter(base) : mc.player.getEntityPos();
        PlayerEntity best = null;
        double bestDistance = 12.0 * 12.0;
        for (PlayerEntity player : mc.world.getPlayers()) {
            if (!followTargetUsable(player)) continue;
            double distance = player.getEntityPos().squaredDistanceTo(center);
            if (distance < bestDistance) {
                bestDistance = distance;
                best = player;
            }
        }
        return best;
    }

    private void attack(EndCrystalEntity crystal, int slot, long now) {
        double reach = mc.player.getEntityInteractionRange();
        swap.act(slot, () -> {
            // One attack per movement interval, and only on the crystal the live ray reaches now.
            if (mc.world == null || !autoRunStillValid() || (scripted() && !TickActionOrder.isWindowOpen())
                    || !TickActionOrder.canAttack() || !crystal.isAlive() || crystal.isRemoved()
                    || !rayHits(crystal, reach) || !breakSurvivable(crystal)) return;
            if (!AttackValidator.dispatchEntityAttack(mc, crystal, false, true)) return;
            TickActionOrder.recordAttack();
            lastAttackAge = mc.player.age;
            lastBrokenId = crystal.getId();
            lastBrokenAt = now;
            if (firstBreakAt == NEVER) {
                firstBreakAt = now;
                followDelayMs = Math.max(0L, Math.round(followDelay.sample()));
            }
            lastActionAt = now;
            pendingBase = null;
            unanswered = 0;
            markAction();
            // The look reached this tick keeps for the rest of it; the way down starts next tick.
            holdLook();
            if (bob.isActive()) bob.nextCycle();
            if (auraRun) afterAuraBreak(crystal, now);
        });
    }

    /** Whether the live ray from the current look reaches {@code crystal} first. */
    private boolean rayHits(EndCrystalEntity crystal, double reach) {
        HitResult live = ServerAim.getCrosshairTarget(RotationManager.getFinalYaw(), RotationManager.getFinalPitch(), reach);
        return live instanceof EntityHitResult hit && hit.getEntity() == crystal;
    }

    // ------------------------------------------------------------------ pearl follow

    /**
     * The pearl step. True while it owns this tick (turning to a throw, or throwing); false when it is
     * not due, cannot run, or has nothing that lands yet, so the crystal step carries on meanwhile.
     */
    private boolean followStep(long now) {
        if (scripted() && (!TickActionOrder.isWindowOpen() || !TickActionOrder.canSwitchSlot())) return false;
        if (!pearlFollow.isEnabled() || followDone || firstBreakAt == NEVER
                || now - firstBreakAt < followDelayMs) {
            return false;
        }
        if (!followTargetUsable(swordTarget)) swordTarget = nearestFollowTarget();
        PlayerEntity target = swordTarget;
        if (target == null || !target.isAlive() || target.isRemoved() || mc.player.distanceTo(target) > 40.0
                || PearlThrow.cooling(mc) || CrystalPlacement.hotbarSlot(mc.player, Items.ENDER_PEARL) < 0) {
            followDone = true;
            return false;
        }
        if (followSince == NEVER) {
            followSince = now;
            EventBus.post(new PearlModuleExecuteEvent("AutoHitCrystal"));
        }
        if (now - followSince > FOLLOW_WINDOW_MS) {
            followDone = true;
            return false;
        }
        double rtt = roundTripMs();
        int horizon = PearlFollowSolver.MAX_AGE + FOLLOW_MARGIN_TICKS + (int) Math.ceil(rtt / 50.0);
        PlayerMotionSimulation.Prediction prediction = PlayerMotionSimulation.predict(mc.world, target, horizon);
        if (prediction.forecasts().isEmpty()) return false;
        PlayerMotionSimulation.Forecast likeliest = prediction.forecasts().get(0);
        for (PlayerMotionSimulation.Forecast forecast : prediction.forecasts()) {
            if (forecast.weight() > likeliest.weight()) likeliest = forecast;
        }
        long sinceSeen = PlayerTrajectoryEngine.getInstance().currentTick() - prediction.observedTick();
        int lead = (int) Math.round(Math.max(0.0, sinceSeen + rtt / 50.0));
        PearlFollowSolver.Forecast forecast = new PearlFollowSolver.Forecast(likeliest.boxes(), lead);
        Vec3d spawn = ThrowPhysics.pearlSpawn(mc.player.getEntityPos(), mc.player.getEyeY() - mc.player.getY());
        Vec3d movement = ThrowPhysics.localShooterMovement(mc.player);
        float[] look = appliedLook();
        // The full solve is the costly part: redo it every third tick and aim at the last answer between.
        long tick = mc.world.getTime();
        if (followShot == null || tick - followSolvedTick >= 3L || tick < followSolvedTick) {
            followShot = PearlFollowSolver.solve(mc.world, mc.player, spawn, movement,
                    mc.player.isOnGround(), forecast, followDistance.getValue(), look[0], look[1]);
            followSolvedTick = tick;
        }
        PearlFollowSolver.Shot shot = followShot;
        if (shot == null) return false;
        if (!SmoothAim.aimAngles(this, shot.yaw(), shot.pitch(), aimDegPerSec(), silentAim.isEnabled())) {
            return false;
        }
        aiming = true;
        float[] applied = appliedLook();
        Vec3d heading = PearlFollowSolver.heading(forecast.boxes(), lead, mc.player);
        double chance = PearlFollowSolver.evaluate(mc.world, mc.player, movement, forecast, heading,
                applied[0], applied[1]).chance();
        if (chance < followConfidence.getValue()) return true;
        int slot = CrystalPlacement.hotbarSlot(mc.player, Items.ENDER_PEARL);
        swap.act(slot, () -> {
            if (mc.world == null || followDone) return;
            if (scripted() && (!autoRunStillValid() || !TickActionOrder.isWindowOpen()
                    || !TickActionOrder.canSwitchSlot())) return;
            float[] now2 = appliedLook();
            double again = PearlFollowSolver.evaluate(mc.world, mc.player, movement, forecast, heading,
                    now2[0], now2[1]).chance();
            if (again < followConfidence.getValue() || !PearlThrow.use(mc, now2[0], now2[1])) return;
            EventBus.post(new PearlThrowEvent());
            // The look just thrown keeps for the rest of the tick. The run is over: the next tick's idle
            // branch releases it and gives the slot back, and a new run needs a new sword hit.
            RotationManager.setPostAttackHold(this, 1, now2[0], now2[1]);
            markAction();
            endAfterThrow();
        });
        return true;
    }

    /** The run ends with the throw; the look is released and the slot restored from the next tick. */
    private void endAfterThrow() {
        phase = Phase.IDLE;
        automatic = false;
        auraRun = false;
        auraTarget = null;
        currentPlan = null;
        autoPending = false;
        bob.end();
        peakUp = 0.0;
        base = null;
        pendingBase = null;
        pressQueued = false;
        swordHitAt = NEVER;
        swordTarget = null;
        followDone = true;
        needsRelease = useHeld();
        scheduleRestore();
    }

    private float[] appliedLook() {
        if (RotationManager.isSilentRotationActive()) {
            return new float[]{RotationManager.getFinalYaw(), RotationManager.getFinalPitch()};
        }
        return new float[]{mc.player.getYaw(), mc.player.getPitch()};
    }

    private double roundTripMs() {
        if (mc.getNetworkHandler() == null) return 0.0;
        PlayerListEntry entry = mc.getNetworkHandler().getPlayerListEntry(mc.player.getUuid());
        return entry == null ? 0.0 : Math.max(0, entry.getLatency());
    }

    // ------------------------------------------------------------------ aura (Mode Auto)

    private boolean auraMode() {
        return mode.is("Auto");
    }

    /** A run the module drives on its own (Manual's Auto Place, or the aura): no use key involved. */
    private boolean scripted() {
        return automatic || auraRun;
    }

    /** One Auto-mode tick: engage when idle, else run the aura's phase. */
    private void auraTick(long now) {
        if (phase == Phase.IDLE) {
            releaseAim();
            idleRestore();
            if (!auraStart(now)) return;
        }
        boolean aimed = false;
        switch (phase) {
            case RETURN -> aimed = returnStep(now);
            case WAIT_OBSIDIAN -> {
                aimed = aiming;
                if (base != null && CrystalPlacement.isBase(mc.world, base)) {
                    phase = Phase.CRYSTALS;
                    lastActionAt = now;
                } else if (now - obsidianSentAt > CrystalPlacement.spawnWaitMs(mc)) {
                    // The obsidian never showed: plan afresh.
                    phase = Phase.CRYSTALS;
                    base = null;
                }
            }
            case IDLE, FOLLOW -> { }
            default -> {
                if (!auraMode()) {
                    endRun();
                } else {
                    aimed = auraStep(now);
                }
            }
        }
        if (!auraRun) return;
        // A look held from an earlier turn is not let go in the tick of the action it served.
        if (!aimed && phase != Phase.RETURN && lastActionAge != mc.player.age && !swap.queuedThisTick()) {
            releaseAim();
        }
        if (phase == Phase.CRYSTALS && now - lastActionAt > IDLE_END_MS + 2L * CrystalPlacement.spawnWaitMs(mc)) {
            // Nothing worth doing for a while: end, give the slot back, re-engage when there is.
            endRun();
        }
    }

    /**
     * Idle in Auto mode: an allowed item in hand (inside the Hit Window with Require Sword Hit), crystals
     * in the hotbar and a target for the Activation Delay engage a run. True when it engaged.
     */
    private boolean auraStart(long now) {
        if (!holdingSword() || !inWindow(now)) {
            auraArmedAt = NEVER;
            return false;
        }
        PlayerEntity target = HitCrystalAura.pickTarget(mc, this::auraTargetUsable, targetRange.getValue(),
                maxAngle.getValue(), targetMode.getCurrentMode(), requireHit.isEnabled() ? swordTarget : null);
        if (target == null) {
            auraArmedAt = NEVER;
            return false;
        }
        if (CrystalPlacement.hotbarSlot(mc.player, Items.END_CRYSTAL) < 0) {
            warnMissing("crystals", "No end crystals in the hotbar.");
            auraArmedAt = NEVER;
            return false;
        }
        if (auraArmedAt == NEVER) {
            auraArmedAt = now;
            auraDelayMs = Math.max(0L, Math.round(activationDelay.sample()));
        }
        if (now - auraArmedAt < auraDelayMs) return false;
        // A sword attack closes slot switching for its interval: start at a later tick head.
        if (SubTickSequencer.currentTick() == swordAttackTick || !TickActionOrder.isWindowOpen()
                || !TickActionOrder.canSwitchSlot()) return false;
        // Nothing engages (no slot, no look) until a crystal, base or obsidian cell actually scores.
        if (freshPlan(target, now) == null) return false;
        resetRun(now);
        auraRun = true;
        auraTarget = target;
        auraArmedAt = NEVER;
        base = null;
        phase = Phase.CRYSTALS;
        return true;
    }

    /** One aura step: keep or pick the target, keep or commit a plan, act. True when it used the aim request this tick. */
    private boolean auraStep(long now) {
        if (!TickActionOrder.isWindowOpen() || !TickActionOrder.canSwitchSlot()) return aiming;
        if (bob.isActive()) peakUp = Math.max(peakUp, bob.risen());
        // The locked target is kept while usable and in range; Max Angle only picks a new one. Judged
        // against the aimed yaw every tick, a turn onto a crystal at the side dropped the target, ended
        // the run, and the look came back only to engage again: that was most of the looking around.
        PlayerEntity target = auraTarget;
        if (!lockedTargetValid(target)) {
            target = HitCrystalAura.pickTarget(mc, this::auraTargetUsable, targetRange.getValue(),
                    maxAngle.getValue(), targetMode.getCurrentMode(), null);
        }
        if (target == null) {
            if (enterFollow()) return false;
            endRun();
            return false;
        }
        if (target != auraTarget) dropPlan();
        auraTarget = target;
        if (followTargetUsable(target)) swordTarget = target;
        if (followStep(now)) return true;
        if (phase != Phase.CRYSTALS) return aiming;
        if (CrystalPlacement.hotbarSlot(mc.player, Items.END_CRYSTAL) < 0) {
            warnMissing("crystals", "No end crystals in the hotbar.");
            endRun();
            return false;
        }
        if (pendingBase != null) {
            // A crystal placed and not shown yet: wait for it (the round trip plus a server tick) rather
            // than putting a second crystal packet out.
            if (now - pendingSince < CrystalPlacement.spawnWaitMs(mc)) {
                if (crystalAt(pendingBase) == null) return aiming;
            } else if (++unanswered >= MAX_UNANSWERED) {
                endRun();
                return false;
            }
            pendingBase = null;
        }
        if (lastBrokenId >= 0 && now - lastBrokenAt < echoWaitMs()
                && mc.world.getEntityById(lastBrokenId) instanceof EndCrystalEntity hit && hit.isAlive() && !hit.isRemoved()) {
            // Hit and waiting for the server to remove it: the look stays put. Planning around the hit
            // crystal meanwhile sent the look to another base for the round trip, then back.
            return aiming;
        }
        HitCrystalAura.Plan plan = commitPlan(target, now);
        if (plan == null) {
            // Nothing worth doing: the look is released (no turning while idle).
            return false;
        }
        if (!plan.base().equals(base)) placeArmedAge = NO_AGE;
        base = plan.base();
        int obsidian = CrystalPlacement.hotbarSlot(mc.player, Items.OBSIDIAN);
        return switch (plan.kind()) {
            case BREAK -> {
                EndCrystalEntity crystal = plan.crystal();
                pendingBase = null;
                unanswered = 0;
                yield breakStep(crystal, now);
            }
            case PLACE -> placeStep(base, now);
            case OBSIDIAN -> auraObsidianStep(base, obsidian, now);
        };
    }

    private boolean auraTargetUsable(PlayerEntity player) {
        return followTargetUsable(player) && !player.isCreative()
                && interstellarTikiTiks.apexx.client.util.combat.StickyTargets.allows(this, player);
    }

    /** The run's target stays locked while usable and within Target Range (a block of slack), whatever the angle. */
    private boolean lockedTargetValid(PlayerEntity target) {
        return target != null && auraTargetUsable(target) && mc.player.distanceTo(target) <= targetRange.getValue() + 1.0;
    }

    private HitCrystalAura.Limits auraLimits() {
        int obsidian = CrystalPlacement.hotbarSlot(mc.player, Items.OBSIDIAN);
        return new HitCrystalAura.Limits(minEfficiency.getValue() / 100.0,
                maxSelfDamage.getValue(), antiSuicide.isEnabled(), predictMovement.isEnabled(),
                knockedAge != NO_AGE && mc.player.age - knockedAge >= 0 && mc.player.age - knockedAge <= 10,
                autoPlace.isEnabled() && obsidian >= 0);
    }

    /** A crystal we hit and are still waiting on is not planned for again. */
    private boolean crystalUsable(EndCrystalEntity crystal, long now) {
        return !(crystal.getId() == lastBrokenId && now - lastBrokenAt < echoWaitMs());
    }

    /** The best action against {@code target} right now, from scratch. */
    private HitCrystalAura.Plan freshPlan(PlayerEntity target, long now) {
        return planner.plan(mc, target, auraLimits(), crystal -> crystalUsable(crystal, now),
                this::auraObsidianCell, cell -> SmoothAim.nearestFacePoint(mc, cell, null) != null);
    }

    /**
     * The plan this tick acts on. The committed plan is kept while it still stands
     * ({@link HitCrystalAura#rescore}); a fresh one replaces it only when it has failed, or after
     * {@link #PLAN_MIN_DWELL_TICKS} when the fresh one hurts the target clearly more. Planning from scratch
     * every tick sent the look to a different base or crystal tick after tick as the forecast moved.
     */
    private HitCrystalAura.Plan commitPlan(PlayerEntity target, long now) {
        HitCrystalAura.Limits limits = auraLimits();
        HitCrystalAura.Plan kept = currentPlan == null ? null
                : planner.rescore(mc, target, limits, currentPlan, crystal -> crystalUsable(crystal, now), this::auraObsidianCell);
        int age = mc.player.age;
        if (kept != null && (planSinceAge == NO_AGE || age - planSinceAge < PLAN_MIN_DWELL_TICKS)) {
            currentPlan = kept;
            return kept;
        }
        HitCrystalAura.Plan fresh = freshPlan(target, now);
        if (kept != null && (fresh == null || HitCrystalAura.sameAction(fresh, kept)
                || fresh.targetDamage() < kept.targetDamage() * PLAN_BETTER_FACTOR + PLAN_BETTER_MIN)) {
            currentPlan = kept;
            return kept;
        }
        if (fresh == null) {
            dropPlan();
            return null;
        }
        if (currentPlan == null || !fresh.base().equals(currentPlan.base())) {
            // Another base: its bob starts from the look that gets there, not the last base's rest pose.
            bob.end();
            peakUp = 0.0;
        }
        currentPlan = fresh;
        planSinceAge = age;
        return fresh;
    }

    private void dropPlan() {
        currentPlan = null;
        planSinceAge = NO_AGE;
        bob.end();
        peakUp = 0.0;
    }

    /** Whether obsidian could go into {@code cell} from here, with a crystal on it still hittable. */
    private boolean auraObsidianCell(BlockPos cell) {
        return autoCellClear(cell) && !outOfBlockReach(cell) && crystalReachable(cell) && autoPlacement(cell) != null;
    }

    /** Places the obsidian base the plan asked for, on the tick the live ray reaches its support. */
    private boolean auraObsidianStep(BlockPos cell, int slot, long now) {
        if (slot < 0) return false;
        PlaceSolution solution = autoPlacement(cell);
        if (solution == null) return false;
        if (!SmoothAim.aimPoint(this, solution::hitVec, aimDegPerSec(), silentAim.isEnabled())) return false;
        aiming = true;
        BlockHitResult hit = BlockPlacementValidator.hitForPlacement(mc, cell, silentAim.isEnabled());
        if (hit == null || !autoSupportUsable(hit) || !canPlaceObsidian(hit) || !placeDelayElapsed()
                || !actionReady()) return true;
        swap.act(slot, () -> {
            if (mc.world == null || !auraRun || phase != Phase.CRYSTALS || !TickActionOrder.isWindowOpen()
                    || !TickActionOrder.canSwitchSlot() || !autoCellClear(cell) || mc.player.isUsingItem()
                    || !mc.player.getMainHandStack().isOf(Items.OBSIDIAN)) return;
            BlockHitResult live = BlockPlacementValidator.hitForPlacement(mc, cell, silentAim.isEnabled());
            if (live == null || !autoSupportUsable(live) || !canPlaceObsidian(live)) return;
            if (!InteractSim.interactBlockOncePerTick(mc, live, silentAim.isEnabled())) return;
            base = cell.toImmutable();
            obsidianSentAt = System.currentTimeMillis();
            lastActionAt = obsidianSentAt;
            markAction();
            placeArmedAge = NO_AGE;
            phase = Phase.WAIT_OBSIDIAN;
            holdLook();
        });
        return true;
    }

    /**
     * The slot a crystal is hit with: the held item unless Weakness stops it; with Rapid Fire the
     * crystal slot itself when it can break (so the re-place needs no second swap).
     */
    private int breakSlot() {
        int slot = CrystalPlacement.breakingSlot(mc.player);
        if (auraRun && optimization.is("Rapid Fire") && slot == -1
                && !mc.player.getMainHandStack().isOf(Items.END_CRYSTAL)) {
            int crystals = CrystalPlacement.hotbarSlot(mc.player, Items.END_CRYSTAL);
            if (crystals >= 0 && CrystalPlacement.breaksCrystals(mc.player, mc.player.getInventory().getStack(crystals))) {
                return crystals;
            }
        }
        return slot;
    }

    /**
     * Optimization after an aura break, in the break's own tick (both blatant): Predict drops the crystal
     * on this side at once; Rapid Fire places the next crystal on the same base when the live ray is on it.
     */
    private void afterAuraBreak(EndCrystalEntity crystal, long now) {
        if (mc.world == null || mc.player == null) return;
        if (optimization.is("Predict")) {
            mc.world.removeEntity(crystal.getId(), Entity.RemovalReason.KILLED);
            return;
        }
        if (!optimization.is("Rapid Fire") || currentPlan == null
                || currentPlan.efficiency() * 100.0 < rapidMinEfficiency.getValue()
                || base == null || !CrystalPlacement.isBase(mc.world, base) || !mc.world.isAir(base.up())
                || !mc.player.getMainHandStack().isOf(Items.END_CRYSTAL)) return;
        BlockHitResult live = BlockPlacementValidator.rayFromLook(mc, silentAim.isEnabled());
        if (live == null || !live.getBlockPos().equals(base)) return;
        if (!InteractSim.interactBlockOncePerTick(mc, live, silentAim.isEnabled())) return;
        pendingBase = base.toImmutable();
        pendingSince = now;
    }

    // ------------------------------------------------------------------ helpers

    private boolean inWindow(long now) {
        if (!requireHit.isEnabled()) return true;
        return swordHitAt != NEVER && now - swordHitAt <= (long) hitWindow.getValue();
    }

    /**
     * Whether the main-hand item may start (or keep alive) a run: the Allowed Items whitelist (Sword by
     * default). Named for the original sword-only rule; it now covers every allowed item.
     */
    private boolean holdingSword() {
        return isAllowedStarter(mc.player.getMainHandStack());
    }

    private boolean isAllowedStarter(net.minecraft.item.ItemStack stack) {
        if (allowAny.isEnabled()) return true;
        if (stack == null || stack.isEmpty()) return false;
        if (allowSword.isEnabled() && stack.isIn(ItemTags.SWORDS)) return true;
        if (allowAxe.isEnabled() && stack.isIn(ItemTags.AXES)) return true;
        if (allowPickaxe.isEnabled() && stack.isIn(ItemTags.PICKAXES)) return true;
        if (allowMace.isEnabled() && stack.isOf(Items.MACE)) return true;
        if (allowTotem.isEnabled() && stack.isOf(Items.TOTEM_OF_UNDYING)) return true;
        String custom = allowCustom.getValue();
        if (custom == null || custom.isBlank()) return false;
        String id = net.minecraft.registry.Registries.ITEM.getId(stack.getItem()).toString();
        String path = net.minecraft.registry.Registries.ITEM.getId(stack.getItem()).getPath();
        for (String part : custom.split(",")) {
            String want = part.trim().toLowerCase(java.util.Locale.ROOT);
            if (want.isEmpty()) continue;
            if (want.equals(id) || want.equals(path)) return true;
        }
        return false;
    }

    private boolean useHeld() {
        return mc.options != null && mc.options.useKey.isPressed();
    }

    private boolean outOfBlockReach(BlockPos pos) {
        double reach = mc.player.getBlockInteractionRange();
        return new Box(pos).squaredMagnitude(mc.player.getEyePos()) > reach * reach;
    }

    /**
     * Whether a crystal standing on {@code pos} would be within entity reach: its 2x2x2 box sits on top of
     * the base, centred on it.
     */
    private boolean crystalReachable(BlockPos pos) {
        double reach = mc.player.getEntityInteractionRange();
        Box crystal = new Box(pos.getX() - 0.5, pos.getY() + 1.0, pos.getZ() - 0.5,
                pos.getX() + 1.5, pos.getY() + 3.0, pos.getZ() + 1.5);
        return crystal.squaredMagnitude(mc.player.getEyePos()) <= reach * reach;
    }

    /**
     * How long a hit or a place may take to show in the world (removal or spawn): the round trip plus the
     * server's tick. Past it the action is retried instead of waited on.
     */
    private long echoWaitMs() {
        return CrystalPlacement.spawnWaitMs(mc);
    }

    /**
     * Crystal Speed: a place or a break goes out on the first tick that is a whole number of ticks
     * after the last one. Every action is on a tick of its own, so a place and the break that follows
     * it never share one. The gap is sampled per action with probabilistic tick rounding, so a value
     * between whole-tick rates (18 = mostly 1 tick, sometimes 2) holds on average instead of rounding to 20.
     */
    private boolean actionReady() {
        int age = mc.player.age;
        if (age < lastActionAge) lastActionAge = NO_AGE;
        if (lastActionAge != gapSampledFor) {
            gapSampledFor = lastActionAge;
            sampledGap = Math.max(1, ModuleDelays.msToTicks(1000.0 / Math.max(1.0, crystalSpeed.getValue())));
        }
        return lastActionAge == NO_AGE || age - lastActionAge >= sampledGap;
    }

    private int gapSampledFor = NO_AGE;
    private int sampledGap = 1;

    private void markAction() {
        lastActionAge = mc.player.age;
    }

    /** Keeps the look an action was made with to the end of the tick (no-op when this module has no look). */
    private void holdLook() {
        RotationManager.setPostAttackHold(this, 1, RotationManager.getFinalYaw(), RotationManager.getFinalPitch());
    }

    /** Look speed in degrees a second: Aim Speed is degrees a tick; the controller caps it. */
    private double aimDegPerSec() {
        return aimSpeed.getValue() * 20.0;
    }

    private void releaseAim() {
        if (!aiming) return;
        aiming = false;
        RotationManager.clearTarget(this);
    }

    /**
     * Ends the run the way a player would: if the look is away from where the bob started, the last
     * crystal is broken and the look comes home first (RETURN); otherwise it ends at once.
     */
    private void endRun() {
        // The aura has no rest pose to come home to (its bases are wherever they scored): it just stops.
        if (!auraRun && phase != Phase.IDLE && phase != Phase.RETURN && bob.displaced(REST_TOLERANCE)) {
            phase = Phase.RETURN;
            returnSince = System.currentTimeMillis();
            return;
        }
        if (phase != Phase.RETURN) finish(false);
    }

    /**
     * One step of the way home: a crystal still standing on the base is broken first (turning up to it if
     * need be), a crystal just placed is waited for, then the look is guided back to the pose the bob
     * started from, and only then the run ends. Nothing is placed. True while it keeps the look.
     */
    private boolean returnStep(long now) {
        if (now - returnSince > RETURN_LIMIT_MS || !bob.isActive() || mc.player == null) {
            finish(false);
            return false;
        }
        peakUp = Math.max(peakUp, bob.risen());
        BlockPos working = base != null && CrystalPlacement.isBase(mc.world, base) ? base : null;
        if (working != null && !outOfBlockReach(working)) {
            EndCrystalEntity crystal = crystalAt(working);
            if (crystal != null) {
                if (crystal.getId() == lastBrokenId && now - lastBrokenAt < echoWaitMs()) {
                    return aiming;
                }
                boolean kept = breakStep(crystal, now);
                // A break dispatched this tick keeps the look it was made with until the tick is over.
                if (kept || lastAttackAge == mc.player.age) return true;
            } else if (working.equals(pendingBase) && now - pendingSince < CrystalPlacement.spawnWaitMs(mc)) {
                // Placed a moment ago and not shown yet: it is broken the tick it does.
                return aiming;
            }
        }
        if (lastAttackAge == mc.player.age) return true;
        if (bob.atRest(REST_TOLERANCE)) {
            finish(false);
            return false;
        }
        float[] home = bob.downGoal(peakUp);
        if (!CrystalHeadbob.aim(this, home[0], home[1], aimDegPerSec(), silentAim.isEnabled())) {
            finish(false);
            return false;
        }
        aiming = true;
        return true;
    }

    /**
     * Ends the run: the look is released and the slot goes back. {@code immediate} (disable, a screen,
     * death) queues the swap-back for the next tick head; otherwise it waits the Switch Back Delay.
     */
    private void finish(boolean immediate) {
        SubTickSequencer.cancelOwner(this);
        automatic = false;
        auraRun = false;
        auraTarget = null;
        currentPlan = null;
        auraArmedAt = NEVER;
        autoPending = false;
        bob.end();
        peakUp = 0.0;
        phase = Phase.IDLE;
        base = null;
        pendingBase = null;
        pressQueued = false;
        placeArmedAge = NO_AGE;
        releaseAim();
        needsRelease = useHeld();
        if (immediate) {
            restorePending = false;
            if (switchBack.isEnabled()) swap.restore();
            else swap.reset(false);
        } else {
            scheduleRestore();
        }
    }

    private void scheduleRestore() {
        restorePending = true;
        restoreAtAge = (mc.player == null ? 0 : mc.player.age) + ModuleDelays.msToTicks(switchBackDelay.sample());
    }

    /**
     * Idle: the swap-back goes out once the Switch Back Delay is over, only while the selection is still
     * the one this module made ({@link SwapAction#restore()}). A slot left owned without a scheduled
     * restore is scheduled now.
     */
    private void idleRestore() {
        if (!restorePending) {
            if (!swap.holdsSlot()) return;
            scheduleRestore();
        }
        if (mc.player.age < restoreAtAge) return;
        restorePending = false;
        if (switchBack.isEnabled()) swap.restore();
        else swap.reset(false);
    }

    /** Cannot run (no world, a screen, dead): nothing to restore into, so just drop the state. */
    private void abort() {
        if (phase != Phase.IDLE || aiming) {
            finish(true);
        } else if (restorePending) {
            restorePending = false;
            if (switchBack.isEnabled()) swap.restore();
            else swap.reset(false);
        }
        pressQueued = false;
        autoPending = false;
        auraArmedAt = NEVER;
        swordHitAt = NEVER;
        swordTarget = null;
    }

    private boolean canRun() {
        return mc.player != null && mc.world != null && mc.interactionManager != null && mc.currentScreen == null
                && !mc.isPaused() && mc.player.isAlive() && !mc.player.isUsingItem()
                && mc.interactionManager.getCurrentGameMode() != GameMode.SPECTATOR;
    }

    private void resetState(boolean dropDeferredRestore) {
        releaseAim();
        swap.reset(dropDeferredRestore);
        phase = Phase.IDLE;
        bob.end();
        peakUp = 0.0;
        base = null;
        pendingBase = null;
        unanswered = 0;
        lastBrokenId = -1;
        pressQueued = false;
        aiming = false;
        firstBreakAt = NEVER;
        followSince = NEVER;
        followDone = false;
        followShot = null;
        swordTarget = null;
        swordHitAt = NEVER;
        swordAttackTick = NEVER;
        autoPending = false;
        automatic = false;
        auraRun = false;
        auraTarget = null;
        currentPlan = null;
        auraArmedAt = NEVER;
        knockedAge = NO_AGE;
        needsRelease = false;
        restorePending = false;
        breakDelayCrystal = -1;
        breakDelayArmedAge = NO_AGE;
        placeArmedAge = NO_AGE;
        lastActionAge = NO_AGE;
        lastAttackAge = NO_AGE;
        boundPlayer = mc.player;
        boundWorld = mc.world;
    }
}
