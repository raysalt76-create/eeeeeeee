package interstellarTikiTiks.apexx.client.module.implementation.combat;

import interstellarTikiTiks.apexx.client.event.EventBus;
import interstellarTikiTiks.apexx.client.event.EventTarget;
import interstellarTikiTiks.apexx.client.event.impl.CombatSwapLockEvent;
import interstellarTikiTiks.apexx.client.event.impl.PearlModuleExecuteEvent;
import interstellarTikiTiks.apexx.client.event.impl.PearlThrowEvent;
import interstellarTikiTiks.apexx.client.mixin.ClientPlayerEntityAccessor;
import interstellarTikiTiks.apexx.client.util.RotationMath;
import interstellarTikiTiks.apexx.client.module.BooleanSetting;
import interstellarTikiTiks.apexx.client.module.Category;
import interstellarTikiTiks.apexx.client.module.Module;
import interstellarTikiTiks.apexx.client.module.SliderSetting;
import interstellarTikiTiks.apexx.client.module.SubCategory;
import interstellarTikiTiks.apexx.client.module.hooks.ItemUseAware;
import interstellarTikiTiks.apexx.client.module.hooks.ItemUseBlockProvider;
import interstellarTikiTiks.apexx.client.util.RotationManager;
import interstellarTikiTiks.apexx.client.util.combat.BlockPlacementValidator;
import interstellarTikiTiks.apexx.client.util.combat.CrystalPlacement;
import interstellarTikiTiks.apexx.client.util.combat.InteractSim;
import interstellarTikiTiks.apexx.client.util.combat.ObsidianIntent;
import interstellarTikiTiks.apexx.client.util.combat.PearlThrow;
import interstellarTikiTiks.apexx.client.util.combat.ServerAim;
import interstellarTikiTiks.apexx.client.util.combat.SmoothAim;
import interstellarTikiTiks.apexx.client.util.combat.SubTickSequencer;
import interstellarTikiTiks.apexx.client.util.combat.SwapAction;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.RespawnAnchorBlock;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.projectile.ProjectileUtil;
import net.minecraft.item.BlockItem;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.tag.ItemTags;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.GameMode;
import net.minecraft.world.RaycastContext;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Shared fill/explode engine behind the anchor modules (ported from Mochi's {@code AnchorSequenceModule}).
 * A module acquires an anchor - the one under the crosshair, or a placement it watched the player make -
 * swaps to glowstone to charge it, then swaps to a non-glowstone slot and right-clicks it to detonate.
 *
 * <p>Charge and explode are separate ticks. Each click is the same-tick swap + use of {@link SwapAction}:
 * the slot is selected first and the use follows later in that tick, and only when the live ray from the
 * look that tick's aim step produced is on the anchor. Extra waits before a charge or an explosion come
 * from the subclass ({@link #chargeGapMs()}, {@link #explodeGapMs()}), sampled once per click. The slot goes
 * back at the head of the next movement interval and only while the module still owns it.</p>
 */
public abstract class AnchorSequenceModule extends Module implements ItemUseAware, ItemUseBlockProvider, interstellarTikiTiks.apexx.client.module.hooks.ClickSimulating {

    /** Smallest time between a charge and the explosion: they never share a tick. */
    protected static final long TICK_MS = 50L;
    /** A charge or explosion whose click never lines up is given up after this long. */
    protected static final long CLICK_TIMEOUT_MS = 1000L;
    /** A finished anchor is left alone for this long so a sequence does not restart on it. */
    protected static final long IGNORE_MS = 2500L;
    protected static final long NEVER = Long.MIN_VALUE;
    /** An anchor pearl that has not been thrown this long after the explosion is given up. */
    private static final long PEARL_WINDOW_MS = 600L;
    /**
     * The anchor pearl's turn is the fastest the per-tick rotation limits allow: 1450 deg/s is 72.5
     * degrees a tick, under Intave's RotationModuloResetHeuristic (a yaw jump over 100 degrees).
     */
    private static final double PEARL_DEG_PER_SEC = 1450.0;
    /** The look glides back to where it was before the sequence for at most this long, then is let go. */
    private static final long RETURN_MS = 400L;
    /** The look is back when within this of where the sequence started. */
    private static final float RETURN_TOLERANCE_DEG = 0.75f;
    /** The applied look must be within this of the pearl angle (a mouse-lattice step) before the throw. */
    private static final float PEARL_TOLERANCE_DEG = 1.0f;

    protected final BooleanSetting useTotem = new BooleanSetting("Use Totem", true)
            .setDescription("Detonates the anchor while holding a totem from your hotbar when you carry one, so a self-damage pop is covered the instant it happens.");
    protected final BooleanSetting onlyOwnAnchors = new BooleanSetting("Only Own Anchors", true)
            .setDescription("Only charges and explodes anchors you placed or right-clicked yourself, never an enemy's or a stray one in the world.");
    protected final BooleanSetting swapBack = new BooleanSetting("Swap Back", true)
            .setDescription("Puts you back on the hotbar slot you were holding once the anchor is done, but only if you have not switched slots yourself meanwhile.");

    protected final BooleanSetting anchorPearl = new BooleanSetting("Anchor Pearl", false)
            .setDescription("Right after the explosion, looks down to Pearl Angle and throws one ender pearl to get you clear of the follow-up. The turn is the fastest the rotation limits allow and stays off your screen only with Silent Aim on; a pearl still on cooldown is never forced.");
    protected final SliderSetting pearlAngle = new SliderSetting("Pearl Angle", 45.0, 0.0, 90.0, 0)
            .setDescription("How far below the horizon the escape pearl is thrown, in degrees, in the direction you were facing. 90 is straight down; around 45 carries you a few blocks away.");

    protected final SwapAction swap = new SwapAction(this);
    private final Set<BlockPos> ownedAnchors = new HashSet<>();
    private final Map<BlockPos, Long> ignoredAnchors = new HashMap<>();

    protected BlockPos trackedAnchor;
    protected BlockPos pendingPlacedAnchor;
    protected int pendingPlacementTicks;
    protected int takebackTicks;
    protected int explodeClicks;
    /** The tick of the last click of the plain fill/explode loop: a charge and an explosion never share one. */
    private long lastClickTick = Long.MIN_VALUE;
    /** When the last click (or the start of tracking) happened, and the extra waits sampled for the next clicks. */
    private long lastClickAt = NEVER;
    private long nextChargeGapMs;
    private long nextExplodeGapMs;
    /** When a charge click (this module's or the player's) was sent and the world may not show it yet. */
    protected long chargeSentAt = NEVER;
    /** When the player's (or this module's) anchor placement click went out: the first delay counts from it. */
    protected long lastPlacementAt = NEVER;
    protected boolean selfClicking;
    /** Why the last {@link #anchorClick} did not go out (null after one that did), for a subclass's notice. */
    protected String lastClickBlocker;
    private boolean aiming;
    /** The point the frame-rate aim is turning to; read by the controller every render frame. */
    private Vec3d aimTarget;
    /** The look the player had when the sequence began, and the glide back to it once it ends. */
    private boolean originValid;
    private float originYaw;
    private float originPitch;
    private boolean returning;
    private long returnUntil;
    /** The anchor pearl step after an explosion: running, the yaw it keeps, when it began, and thrown. */
    private boolean pearlActive;
    private boolean pearlThrown;
    private float pearlYaw;
    private long pearlSince;
    /**
     * The player changed the hotbar slot (a key or the wheel) while holding use: the module stays out
     * until use is let go or the anchor or glowstone is back in hand, so the swap is not undone and
     * the held use clicks (obsidian, crystals) go through.
     */
    private boolean yielded;
    private Object boundPlayer;
    private Object boundWorld;

    protected AnchorSequenceModule(String name, String description) {
        super(name, description, Category.COMBAT);
        setSubCategory(SubCategory.ANCHOR);
        addSetting(useTotem);
        addSetting(onlyOwnAnchors);
        addSetting(swapBack);
        addSetting(anchorPearl);
        addSetting(pearlAngle);
        pearlAngle.setVisibility(anchorPearl::isEnabled);
    }

    // ------------------------------------------------------------------ variant hooks

    /** Whether the module is allowed to turn the look at all. */
    protected abstract boolean isAimEnabled();

    /** Whether turns are kept server-side instead of being applied to the camera. */
    protected abstract boolean isSilentAim();

    /** Largest turn, in degrees a second, toward each target face. */
    protected abstract double aimSpeedValue();

    /** True when the module runs a pre-planned safe sequence instead of the fill/explode loop. */
    protected boolean usesSafeSequence() {
        return false;
    }

    /** Runs one tick of the safe sequence; only invoked when {@link #usesSafeSequence()} is true. */
    protected void stepSafeSequence() {
    }

    /** Extra state that keeps the player's own clicks held back while a sequence is in flight. */
    protected boolean hasSequenceState() {
        return false;
    }

    /** Called when a sequence is abandoned or completed so a subclass can drop its own state. */
    protected void onSequenceReset() {
    }

    /** Called when a sequence starts tracking a new anchor. */
    protected void onTrackingStarted(BlockPos anchor) {
    }

    /**
     * Called, before the state is dropped, when a tracked anchor is given up because the client cannot
     * run the sequence (a screen, death, an item in use, spectator, pause).
     */
    protected void onSequenceAbandoned() {
    }

    /**
     * Ticks after a sequence before the next anchor may be taken, own clicks held back meanwhile. Each
     * subclass supplies it from its own Delays slider; there is no hard-coded or client-wide default.
     */
    protected abstract int cooldownTicks();

    /** Whether a new anchor waits for a look still gliding back from the last one. */
    protected boolean waitsForRotationRelease() {
        return true;
    }

    /** Runs on a tick with no anchor tracked and no cooldown (a subclass may place one itself). */
    protected void stepIdle() {
    }

    /** Extra reason to hold the player's own use clicks back (a subclass about to place an anchor). */
    protected boolean holdsPlayerUse() {
        return false;
    }

    /**
     * Largest turn, degrees, a click may finish right before it in its own tick (0: the look must be on
     * the face already). The step is committed before the tick's movement packet, so the click and the
     * look the server judges it with agree.
     */
    protected float sameTickStepDeg() {
        return 0.0f;
    }

    // ------------------------------------------------------------------ lifecycle

    @Override
    public void onEnable() {
        EventBus.register(this);
        resetSequence(true);
        ownedAnchors.clear();
        ignoredAnchors.clear();
    }

    @Override
    public void onDisable() {
        shutDown(false);
    }

    @Override
    protected void onRuntimeStateReleased() {
        shutDown(false);
    }

    private void shutDown(boolean dropDeferredRestore) {
        yielded = false;
        releaseAim();
        if (swapBack.isEnabled()) swap.restore();
        else swap.reset(dropDeferredRestore);
        resetSequence(dropDeferredRestore);
        ownedAnchors.clear();
        ignoredAnchors.clear();
    }

    @EventTarget
    public void onCombatSwapLock(CombatSwapLockEvent event) {
        if (event.isRequestedBy(this) || isYielded()) return;
        if (trackedAnchor != null || pearlActive) {
            event.setItemSwapLocked(true);
            event.setAutoAttackLocked(true);
        } else if (swap.holdsSlot() || swap.queuedThisTick()) {
            event.setItemSwapLocked(true);
        }
    }

    // ------------------------------------------------------------------ player-click hooks

    /** Learns which anchors are the player's: the ones they place and the ones they right-click. */
    @Override
    public boolean onItemUse(BlockHitResult hit) {
        if (selfClicking || mc.player == null || mc.world == null || isYielded()) return false;
        BlockPos clicked = hit.getBlockPos();
        ignoredAnchors.remove(clicked);
        if (mc.world.getBlockState(clicked).isOf(Blocks.RESPAWN_ANCHOR)) {
            ownedAnchors.add(clicked.toImmutable());
            if (trackedAnchor == null && takebackTicks <= 0) {
                if (mc.player.getMainHandStack().isOf(Items.GLOWSTONE)) chargeSentAt = System.currentTimeMillis();
                beginTracking(clicked);
            }
            return false;
        }
        if (mc.player.getMainHandStack().isOf(Items.RESPAWN_ANCHOR)) {
            BlockPos placement = BlockPlacementValidator.placementCell(mc, hit);
            if (placement != null) {
                ignoredAnchors.remove(placement);
                ownedAnchors.add(placement.toImmutable());
                pendingPlacedAnchor = placement.toImmutable();
                pendingPlacementTicks = 0;
                lastPlacementAt = System.currentTimeMillis();
            }
        }
        return false;
    }

    /** The player's own use clicks wait while a sequence (or its cooldown) holds the anchor. */
    @Override
    public boolean shouldBlockItemUse() {
        if (isYielded()) return false;
        return trackedAnchor != null || pendingPlacedAnchor != null || takebackTicks > 0 || pearlActive
                || hasSequenceState() || holdsPlayerUse() || (aiming && !returning)
                || (RotationManager.isSilentRotationActive() && RotationManager.getCurrentOwner() == this);
    }

    // ------------------------------------------------------------------ tick

    @Override
    public void onTick() {
        if (!canRun()) {
            abandon();
            return;
        }
        if (mc.player != boundPlayer || mc.world != boundWorld) {
            resetSequence(true);
            ownedAnchors.clear();
            ignoredAnchors.clear();
        }
        if (swap.takeUserSlotChange()) {
            if (comboBusy() && mc.player.getMainHandStack().isOf(Items.OBSIDIAN)) {
                // Obsidian pressed mid-combo: the combo finishes first, then AutoCrystal takes the slot,
                // places and crystals. The slot the player chose is what the combo hands back.
                ObsidianIntent.arm(mc.player.getInventory().getSelectedSlot());
                swap.adoptUserSlot();
            } else {
                yieldToUser();
            }
        }
        if (comboBusy()) ObsidianIntent.keepAlive();
        if (isYielded()) return;
        if (takebackTicks > 0) --takebackTicks;
        if (swap.queuedThisTick()) return;
        if (pearlActive) {
            stepPearl();
            return;
        }

        // The tick after the explosion click in the plain loop: the slot goes back and the look is
        // released without waiting for the anchor to blow up.
        if (!usesSafeSequence() && explodeClicks >= 1) {
            finishSequence();
            return;
        }

        if (returning && trackedAnchor == null) stepReturn();
        else if (trackedAnchor == null && !holdsPlayerUse()) releaseAim();
        // A new anchor is not taken while the look is still gliding back from the last one.
        if (takebackTicks > 0 || (waitsForRotationRelease() && RotationManager.isReleasing())) return;

        if (trackedAnchor == null) {
            acquireAnchor();
            if (trackedAnchor == null) {
                if (pendingPlacedAnchor == null) stepIdle();
                return;
            }
        }
        if (!mc.world.getBlockState(trackedAnchor).isOf(Blocks.RESPAWN_ANCHOR)) {
            finishSequence();
            return;
        }

        if (usesSafeSequence()) {
            stepSafeSequence();
            return;
        }
        stepFillExplode();
    }

    /**
     * The player took the hotbar: everything this module holds is dropped without touching the slot
     * (no restore, no further swap), the anchor in flight is left alone, and the held use is theirs.
     */
    private void yieldToUser() {
        if (trackedAnchor != null) {
            ignoredAnchors.put(trackedAnchor, System.currentTimeMillis() + IGNORE_MS);
            ownedAnchors.remove(trackedAnchor);
        }
        releaseAim();
        swap.reset(true);
        resetSequence(true);
        yielded = mc.options.useKey.isPressed() && !holdsAnchorItem();
    }

    /** A sequence is in flight: an anchor tracked or being placed, the pearl step, or the cooldown after it. */
    private boolean comboBusy() {
        return trackedAnchor != null || pendingPlacedAnchor != null || pearlActive || takebackTicks > 0
                || hasSequenceState();
    }

    private boolean holdsAnchorItem() {
        return mc.player != null && (mc.player.getMainHandStack().isOf(Items.RESPAWN_ANCHOR)
                || mc.player.getMainHandStack().isOf(Items.GLOWSTONE));
    }

    private boolean isYielded() {
        if (yielded && (mc.player == null || !mc.options.useKey.isPressed() || holdsAnchorItem())) yielded = false;
        return yielded;
    }

    private void acquireAnchor() {
        if (pendingPlacedAnchor != null) {
            if (pendingPlacementTicks++ < 10) {
                if (mc.world.getBlockState(pendingPlacedAnchor).isOf(Blocks.RESPAWN_ANCHOR)) {
                    BlockPos placed = pendingPlacedAnchor;
                    pendingPlacedAnchor = null;
                    beginTracking(placed);
                    return;
                }
            } else {
                pendingPlacedAnchor = null;
            }
        }
        BlockPos target = anchorAt(BlockPlacementValidator.rayFromLook(mc, isSilentAim()));
        if (target != null) beginTracking(target);
    }

    /**
     * An anchor placement this module clicked itself: it counts as the player's own, and the sequence
     * takes it on the first tick the world shows it (as for a placement the player makes).
     */
    protected void expectPlacedAnchor(BlockPos anchor) {
        BlockPos pos = anchor.toImmutable();
        ignoredAnchors.remove(pos);
        ownedAnchors.add(pos);
        pendingPlacedAnchor = pos;
        pendingPlacementTicks = 0;
        lastPlacementAt = System.currentTimeMillis();
    }

    protected void beginTracking(BlockPos anchor) {
        trackedAnchor = anchor.toImmutable();
        // The look the player had is what the camera goes back to afterwards; a glide still running from the
        // last anchor keeps the origin it already has.
        if (!originValid && mc.player != null) {
            originYaw = RotationManager.getFinalYaw();
            originPitch = RotationManager.getFinalPitch();
            originValid = true;
        }
        returning = false;
        explodeClicks = 0;
        lastClickAt = System.currentTimeMillis();
        sampleGaps();
        onTrackingStarted(trackedAnchor);
    }

    // ------------------------------------------------------------------ plain fill/explode loop

    private void stepFillExplode() {
        // A click that keeps failing must not hold the anchor (and the player's right-clicks) forever.
        if (lastClickAt != NEVER && System.currentTimeMillis() - lastClickAt
                > CLICK_TIMEOUT_MS + nextChargeGapMs + nextExplodeGapMs) {
            finishSequence();
            return;
        }
        if (isAimEnabled()) {
            aim(aimFacePoint());
            if (!aiming) return;
        }
        if (SubTickSequencer.currentTick() <= lastClickTick) return;
        if (charges(trackedAnchor) <= 0 && predictedExplodeSlot() < 0) stepCharging();
        else stepExplode();
    }

    /**
     * Extra wait before a charge click, ms since the last click or the start of tracking, from the
     * subclass's own Delays slider; sampled once per click. 0 keeps the fastest cadence (the tick gate alone).
     */
    protected abstract long chargeGapMs();

    /** Extra wait before an explosion click, ms since the last click, from the subclass's own Delays slider. */
    protected abstract long explodeGapMs();

    private void sampleGaps() {
        nextChargeGapMs = Math.max(0L, chargeGapMs());
        nextExplodeGapMs = Math.max(0L, explodeGapMs());
    }

    private boolean gapPending(long gapMs) {
        return gapMs > 0L && lastClickAt != NEVER && System.currentTimeMillis() - lastClickAt < gapMs;
    }

    private void stepCharging() {
        if (chargeInFlight()) return;
        if (gapPending(nextChargeGapMs)) return;
        int glowstone = CrystalPlacement.hotbarSlot(mc.player, Items.GLOWSTONE);
        if (glowstone < 0) {
            // Nothing to charge with: do not hold the anchor (and the player's clicks) for it.
            finishSequence();
            return;
        }
        anchorClick(glowstone, () -> {
            chargeSentAt = System.currentTimeMillis();
            markClick();
        });
    }

    private void stepExplode() {
        // Charge sent but not yet shown: explode with an item that cannot place a block, without waiting
        // for the round trip (the server takes the charge click first).
        int slot = charges(trackedAnchor) <= 0 ? predictedExplodeSlot() : findExplodeSlot();
        if (slot < 0) {
            finishSequence();
            return;
        }
        if (gapPending(nextExplodeGapMs)) return;
        anchorClick(slot, () -> {
            ++explodeClicks;
            markClick();
            afterExplosion();
        });
    }

    /**
     * The explosion click is out: the slot is queued back now, so it is applied at the head of the very
     * next tick (never in the call that acted) and the next anchor can be placed on that tick. Not when
     * the pearl step still needs the slot.
     */
    protected final void afterExplosion() {
        if (anchorPearl.isEnabled() && pearlReady()) return;
        if (swapBack.isEnabled()) swap.restore();
    }

    private void markClick() {
        // The tick id gate keeps a charge and an explosion apart. A millisecond floor on top of the swap
        // delay already spent inside the tick would drop every second tick, so there is none.
        lastClickTick = SubTickSequencer.currentTick();
        lastClickAt = System.currentTimeMillis();
        sampleGaps();
    }

    // ------------------------------------------------------------------ look and clicks

    /** One owner-scoped aim step toward {@code point}; the step is committed before this tick's movement packet. */
    protected void aim(Vec3d point) {
        if (!isAimEnabled()) {
            releaseAim();
            return;
        }
        aimTarget = point;
        // The controller turns the look every render frame toward the point; the click below is decided on
        // the tick from the live ray. False when another owner holds the look: nothing of this module's is
        // then left to release.
        aiming = SmoothAim.aimPoint(this, () -> aimTarget, aimSpeedValue(), isSilentAim());
    }

    protected void releaseAim() {
        if (!aiming) return;
        aiming = false;
        RotationManager.clearTarget(this);
    }

    protected boolean isAiming() {
        return aiming;
    }

    /**
     * Aims at the anchor and clicks it with {@code slot}'s item once this tick's look reaches it. A look
     * that is on the anchor already (the ray from the angles that go on the wire) is clicked as it is,
     * even while another module holds the look; only a turn still to make needs the aim.
     *
     * @return true when the click ran or was queued for later in this tick; {@link #lastClickBlocker}
     *         says why not otherwise
     */
    protected final boolean anchorClick(int slot, Runnable after) {
        Vec3d point = aimFacePoint();
        aim(point);
        if (anchorHit() == null) {
            if (isAimEnabled() && !aiming) {
                lastClickBlocker = "another module holds the look";
                return false;
            }
            if (!(finishTurn(point) && anchorHit() != null)) {
                lastClickBlocker = isAimEnabled() ? "the look is not on the anchor yet"
                        : "your crosshair is not on the anchor (Rotate is None)";
                return false;
            }
        }
        boolean queued = swap.act(slot, () -> {
            BlockHitResult hit = anchorHit();
            if (hit == null) {
                lastClickBlocker = "the anchor moved out of the look";
                return;
            }
            if (!click(hit)) {
                lastClickBlocker = "the anchor click was refused";
                return;
            }
            lastClickBlocker = null;
            after.run();
        });
        if (!queued) lastClickBlocker = "the hotbar switch was refused (another module holds the slot, or an action already left this tick)";
        return queued;
    }

    /**
     * Finishes the turn to {@code point} right now, before this tick's movement packet, when what is
     * left of it (measured from the look the server last received) fits in {@link #sameTickStepDeg()}.
     * The click that follows in this tick is then judged with the look that packet carries. Never while
     * a click of this tick still holds the look.
     *
     * @return true when the look was stepped onto the point
     */
    protected final boolean finishTurn(Vec3d point) {
        float budget = sameTickStepDeg();
        if (budget <= 0.0f || point == null || !isAimEnabled() || mc.player == null
                || RotationManager.getPostAttackHoldTicks() > 0) {
            return false;
        }
        float nowYaw = RotationManager.getFinalYaw();
        float nowPitch = RotationManager.getFinalPitch();
        float[] goal = RotationMath.anglesTo(mc.player.getEyePos(), point, nowYaw);
        float wireYaw = nowYaw;
        float wirePitch = nowPitch;
        if (mc.player instanceof ClientPlayerEntityAccessor accessor
                && Float.isFinite(accessor.getLastYawClient()) && Float.isFinite(accessor.getLastPitchClient())) {
            wireYaw = accessor.getLastYawClient();
            wirePitch = accessor.getLastPitchClient();
        }
        double fromWire = Math.hypot(MathHelper.wrapDegrees(goal[0] - wireYaw), goal[1] - wirePitch);
        if (fromWire > budget) return false;
        double fromNow = Math.hypot(MathHelper.wrapDegrees(goal[0] - nowYaw), goal[1] - nowPitch);
        if (!RotationManager.stepAim(this, RotationManager.Priority.HIGH, goal[0], goal[1],
                (float) fromNow + 0.01f, isSilentAim())) {
            return false;
        }
        aiming = true;
        return true;
    }

    /**
     * The click on the anchor this tick's look makes: the vanilla interaction ray, on the anchor from
     * outside it, with no entity in front that would take the click instead.
     */
    protected final BlockHitResult anchorHit() {
        ClientPlayerEntity player = mc.player;
        Vec3d eye = player.getEyePos();
        float[] look = appliedLook();
        Vec3d direction = ServerAim.getRotationVector(look[1], look[0]);
        double reach = player.getBlockInteractionRange();
        HitResult result = mc.world.raycast(new RaycastContext(eye, eye.add(direction.multiply(reach)),
                RaycastContext.ShapeType.OUTLINE, RaycastContext.FluidHandling.NONE, player));
        if (!(result instanceof BlockHitResult hit) || result.getType() != HitResult.Type.BLOCK || hit.isInsideBlock()
                || !hit.getBlockPos().equals(trackedAnchor)) {
            return null;
        }
        Vec3d end = eye.add(direction.multiply(Math.min(player.getEntityInteractionRange(), eye.distanceTo(hit.getPos()))));
        if (ProjectileUtil.getEntityCollision(mc.world, player, eye, end, new Box(eye, end).expand(1.0),
                entity -> entity != player && !entity.isSpectator() && entity.canHit(), 0.0f) != null) {
            return null;
        }
        return hit;
    }

    /** The block click itself; the tracker ignores it as this module's own. */
    protected final boolean click(BlockHitResult hit) {
        if (mc.player.isUsingItem()) return false;
        try {
            selfClicking = true;
            boolean used = InteractSim.interactBlockOncePerTick(mc, hit, isSilentAim());
            // The look this click used is kept for the rest of its tick; a new turn starts next tick.
            if (used && aiming) RotationManager.setPostAttackHold(this, 1, mc.player.getYaw(), mc.player.getPitch());
            return used;
        } finally {
            selfClicking = false;
        }
    }

    // ------------------------------------------------------------------ slots

    /**
     * The hotbar slot to explode a charged anchor with: any item but glowstone (it would charge the
     * anchor again). A totem first when asked, then an anchor, a sword or axe, the slot the sequence
     * started on, anything else.
     */
    protected int findExplodeSlot() {
        ClientPlayerEntity player = mc.player;
        int totem = useTotem.isEnabled() ? CrystalPlacement.hotbarSlot(player, Items.TOTEM_OF_UNDYING) : -1;
        if (totem != -1) return totem;
        int anchor = CrystalPlacement.hotbarSlot(player, Items.RESPAWN_ANCHOR);
        if (anchor != -1) return anchor;
        for (int slot = 0; slot < 9; ++slot) {
            ItemStack stack = player.getInventory().getStack(slot);
            if (stack.isIn(ItemTags.SWORDS) || stack.isIn(ItemTags.AXES)) return slot;
        }
        int origin = swap.originalSlot() >= 0 ? swap.originalSlot() : player.getInventory().getSelectedSlot();
        if (!player.getInventory().getStack(origin).isOf(Items.GLOWSTONE)) return origin;
        for (int slot = 0; slot < 9; ++slot) {
            if (!player.getInventory().getStack(slot).isOf(Items.GLOWSTONE)) return slot;
        }
        return -1;
    }

    // ------------------------------------------------------------------ anchors

    protected BlockPos anchorAt(BlockHitResult hit) {
        if (hit == null) return null;
        BlockPos pos = hit.getBlockPos();
        if (!mc.world.getBlockState(pos).isOf(Blocks.RESPAWN_ANCHOR)) return null;
        Long until = ignoredAnchors.get(pos);
        if (until != null) {
            if (System.currentTimeMillis() < until) return null;
            ignoredAnchors.remove(pos);
        }
        if (onlyOwnAnchors.isEnabled() && !ownedAnchors.contains(pos)) return null;
        return pos.toImmutable();
    }

    protected final int charges(BlockPos anchor) {
        BlockState state = mc.world == null ? null : mc.world.getBlockState(anchor);
        return state != null && state.isOf(Blocks.RESPAWN_ANCHOR) ? state.get(RespawnAnchorBlock.CHARGES) : 0;
    }

    /** A charge click was sent a moment ago and the world may not show it yet. */
    protected final boolean chargeInFlight() {
        return chargeSentAt != NEVER && System.currentTimeMillis() - chargeSentAt < CrystalPlacement.echoWaitMs(mc);
    }

    /**
     * A charge click went out a moment ago and the world has not shown it yet. The server takes the
     * charge before the explosion click that follows it (same connection, in order), so the explosion
     * does not wait for the world to catch up. Only for an item that cannot place a block if the charge
     * was refused.
     */
    protected final int predictedExplodeSlot() {
        if (!chargeInFlight() || mc.player == null) return -1;
        ClientPlayerEntity player = mc.player;
        int totem = useTotem.isEnabled() ? CrystalPlacement.hotbarSlot(player, Items.TOTEM_OF_UNDYING) : -1;
        if (totem != -1) return totem;
        int fallback = -1;
        for (int slot = 0; slot < 9; ++slot) {
            ItemStack stack = player.getInventory().getStack(slot);
            if (stack.isOf(Items.GLOWSTONE) || stack.getItem() instanceof BlockItem) continue;
            if (stack.isIn(ItemTags.SWORDS) || stack.isIn(ItemTags.AXES)) return slot;
            if (fallback < 0) fallback = slot;
        }
        return fallback;
    }

    // ------------------------------------------------------------------ aim points

    protected Vec3d aimFacePoint() {
        // The point of the anchor's visible faces that takes the least turn from the current look.
        Vec3d nearest = SmoothAim.nearestFacePoint(mc, trackedAnchor, null);
        if (nearest != null) return nearest;
        Vec3d eye = mc.player.getEyePos();
        Vec3d upPoint = facePoint(trackedAnchor, Direction.UP);
        if (eye.squaredDistanceTo(upPoint) <= 18.0625) return upPoint;
        return facePoint(trackedAnchor, directionToPlayer());
    }

    protected Direction directionToPlayer() {
        double dx = mc.player.getX() - (trackedAnchor.getX() + 0.5);
        double dz = mc.player.getZ() - (trackedAnchor.getZ() + 0.5);
        if (Math.abs(dx) > Math.abs(dz)) return dx > 0 ? Direction.EAST : Direction.WEST;
        return dz > 0 ? Direction.SOUTH : Direction.NORTH;
    }

    protected static Vec3d facePoint(BlockPos pos, Direction direction) {
        return new Vec3d(pos.getX() + 0.5 + direction.getOffsetX() * 0.49,
                pos.getY() + 0.5 + direction.getOffsetY() * 0.49,
                pos.getZ() + 0.5 + direction.getOffsetZ() * 0.49);
    }

    protected static Vec3d blockCenter(BlockPos pos) {
        return new Vec3d(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
    }

    protected static BlockHitResult createHitResult(BlockPos pos, Direction dir) {
        return new BlockHitResult(facePoint(pos, dir), dir, pos, false);
    }

    // ------------------------------------------------------------------ state

    protected boolean canRun() {
        return mc.player != null && mc.world != null && mc.interactionManager != null && mc.currentScreen == null
                && !mc.isPaused() && mc.player.isAlive() && !mc.player.isUsingItem()
                && mc.interactionManager.getCurrentGameMode() != GameMode.SPECTATOR;
    }

    /**
     * Gives the slot and the look back and ignores the anchor for a moment; the cooldown starts. After
     * an explosion with Anchor Pearl on, the slot and the look are kept for the pearl step instead.
     */
    protected void finishSequence() {
        boolean pearl = anchorPearl.isEnabled() && explodeClicks >= 1 && pearlReady();
        if (trackedAnchor != null) {
            ignoredAnchors.put(trackedAnchor, System.currentTimeMillis() + IGNORE_MS);
            ownedAnchors.remove(trackedAnchor);
        }
        // A placement the player made this very tick is the next sequence's, not this one's to drop.
        BlockPos pendingPlacement = pendingPlacedAnchor;
        int pendingTicks = pendingPlacementTicks;
        if (pearl) {
            float yaw = aiming || RotationManager.isSilentRotationActive()
                    ? RotationManager.getFinalYaw() : mc.player.getYaw();
            boolean keepOrigin = originValid;
            clearSequenceState();
            // The pearl step ends with the glide back to where the look was before the anchor.
            originValid = keepOrigin;
            pearlActive = true;
            pearlThrown = false;
            pearlSince = System.currentTimeMillis();
            pearlYaw = yaw;
            EventBus.post(new PearlModuleExecuteEvent(getName()));
        } else {
            // The camera goes back to the look the player had; the aim is released once it is there.
            boolean glideBack = originValid && aiming && isAimEnabled() && !isSilentAim();
            float backYaw = originYaw;
            float backPitch = originPitch;
            if (!glideBack) releaseAim();
            if (swapBack.isEnabled()) swap.restore();
            else swap.reset(false);
            resetSequence(false);
            if (glideBack) startReturn(backYaw, backPitch);
        }
        pendingPlacedAnchor = pendingPlacement;
        pendingPlacementTicks = pendingTicks;
        takebackTicks = cooldownTicks();
        if (pearl) stepPearl();
    }

    private boolean pearlReady() {
        return mc.player != null && CrystalPlacement.hotbarSlot(mc.player, Items.ENDER_PEARL) >= 0
                && !PearlThrow.cooling(mc);
    }

    /**
     * One tick of the anchor pearl: turn to Pearl Angle at the fastest allowed step, and once the
     * applied look is there, select the pearl and throw it later in the same tick. The look it was
     * thrown with is kept for the rest of that tick; the next tick gives the look and the slot back.
     */
    private void stepPearl() {
        if (pearlThrown || !canRun() || System.currentTimeMillis() - pearlSince > PEARL_WINDOW_MS || !pearlReady()) {
            endPearl();
            return;
        }
        // Silent only when the module's own aim is silent; with no aim a pearl turn has to be the camera's.
        boolean silent = isAimEnabled() && isSilentAim();
        float goalPitch = (float) pearlAngle.getValue();
        if (!SmoothAim.aimAngles(this, pearlYaw, goalPitch, PEARL_DEG_PER_SEC, silent)) {
            endPearl();
            return;
        }
        aiming = true;
        float[] applied = appliedLook();
        if (!pearlLookReached(applied, goalPitch)) return;
        int slot = CrystalPlacement.hotbarSlot(mc.player, Items.ENDER_PEARL);
        swap.act(slot, () -> {
            float[] now = appliedLook();
            if (!pearlLookReached(now, goalPitch) || !PearlThrow.use(mc, now[0], now[1])) return;
            EventBus.post(new PearlThrowEvent());
            RotationManager.setPostAttackHold(this, 1, now[0], now[1]);
            pearlThrown = true;
        });
    }

    private boolean pearlLookReached(float[] look, float goalPitch) {
        return Math.abs(MathHelper.wrapDegrees(look[0] - pearlYaw)) <= PEARL_TOLERANCE_DEG
                && Math.abs(look[1] - goalPitch) <= PEARL_TOLERANCE_DEG;
    }

    private float[] appliedLook() {
        if (RotationManager.isSilentRotationActive()) {
            return new float[]{RotationManager.getFinalYaw(), RotationManager.getFinalPitch()};
        }
        return new float[]{mc.player.getYaw(), mc.player.getPitch()};
    }

    private void endPearl() {
        pearlActive = false;
        pearlThrown = false;
        boolean glideBack = originValid && aiming && isAimEnabled() && !isSilentAim();
        if (glideBack) {
            startReturn(originYaw, originPitch);
        } else {
            releaseAim();
            originValid = false;
        }
        if (swapBack.isEnabled()) swap.restore();
        else swap.reset(false);
    }

    /** Begins the glide of the camera back to the look the player had before the sequence. */
    private void startReturn(float yaw, float pitch) {
        originYaw = yaw;
        originPitch = pitch;
        originValid = true;
        returning = true;
        returnUntil = System.currentTimeMillis() + RETURN_MS;
        aiming = SmoothAim.aimAngles(this, yaw, pitch, PEARL_DEG_PER_SEC, false);
        if (!aiming) {
            returning = false;
            originValid = false;
        }
    }

    /** One tick of the glide back: the aim is let go when the look is there, or the time is up. */
    private void stepReturn() {
        float yaw = RotationManager.getFinalYaw();
        float pitch = RotationManager.getFinalPitch();
        boolean there = Math.abs(MathHelper.wrapDegrees(yaw - originYaw)) <= RETURN_TOLERANCE_DEG
                && Math.abs(pitch - originPitch) <= RETURN_TOLERANCE_DEG;
        if (there || System.currentTimeMillis() > returnUntil || !canRun()) {
            returning = false;
            originValid = false;
            releaseAim();
            return;
        }
        aiming = SmoothAim.aimAngles(this, originYaw, originPitch, PEARL_DEG_PER_SEC, false);
        if (!aiming) {
            returning = false;
            originValid = false;
        }
    }

    /** The sequence cannot go on (screen, death, item in use): drop it without starting a cooldown. */
    private void abandon() {
        if (trackedAnchor != null) onSequenceAbandoned();
        pearlActive = false;
        pearlThrown = false;
        releaseAim();
        if (swapBack.isEnabled()) swap.restore();
        else swap.reset(false);
        resetSequence(false);
    }

    protected void resetSequence(boolean dropDeferredRestore) {
        swap.reset(dropDeferredRestore);
        pearlActive = false;
        pearlThrown = false;
        clearSequenceState();
    }

    /** Everything of a sequence but the slot ownership and the pearl step, which the pearl keeps. */
    private void clearSequenceState() {
        trackedAnchor = null;
        pendingPlacedAnchor = null;
        pendingPlacementTicks = 0;
        explodeClicks = 0;
        lastClickTick = Long.MIN_VALUE;
        lastClickAt = NEVER;
        chargeSentAt = NEVER;
        selfClicking = false;
        lastClickBlocker = null;
        aiming = false;
        returning = false;
        originValid = false;
        aimTarget = null;
        takebackTicks = 0;
        boundPlayer = mc != null ? mc.player : null;
        boundWorld = mc != null ? mc.world : null;
        onSequenceReset();
    }
}
