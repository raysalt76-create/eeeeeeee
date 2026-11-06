package interstellarTikiTiks.apexx.client.module.implementation.player;

import interstellarTikiTiks.apexx.client.event.EventBus;
import interstellarTikiTiks.apexx.client.event.EventTarget;
import interstellarTikiTiks.apexx.client.event.impl.CombatSwapLockEvent;
import interstellarTikiTiks.apexx.client.module.BooleanSetting;
import interstellarTikiTiks.apexx.client.module.Category;
import interstellarTikiTiks.apexx.client.module.ModeSetting;
import interstellarTikiTiks.apexx.client.module.Module;
import interstellarTikiTiks.apexx.client.module.SliderSetting;
import interstellarTikiTiks.apexx.client.util.combat.ModuleDelays;
import interstellarTikiTiks.apexx.client.util.ClickSimController;
import interstellarTikiTiks.apexx.client.util.RotationManager;
import interstellarTikiTiks.apexx.client.util.combat.AutomationPriority;
import interstellarTikiTiks.apexx.client.util.combat.InteractSim;
import interstellarTikiTiks.apexx.client.util.combat.SlotSync;
import interstellarTikiTiks.apexx.client.util.combat.SubTickSequencer;
import interstellarTikiTiks.apexx.client.util.combat.TickActionOrder;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.FluidFillable;
import net.minecraft.fluid.FluidState;
import net.minecraft.fluid.Fluids;
import net.minecraft.item.Item;
import net.minecraft.item.Items;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;
import net.minecraft.world.attribute.EnvironmentAttributes;

/**
 * Breaks the cobweb you are standing in with a water bucket, then takes the water back.
 *
 * <p><b>Vanilla mechanics (1.21.11, read from the game jar).</b> A filled bucket puts its water in the
 * cell on the face it hits, so a click on the top of the web places a source in the cell above it. The
 * water does not replace the web when placed: a web does not block movement, so the source flows into
 * it on its first fluid tick, 5 game ticks after placement, and that flow destroys the web. Picking the
 * source up before that tick leaves the web standing, which is what "Wait For Break" guards against.</p>
 *
 * <p><b>Trigger.</b> The player's box overlaps a cobweb below the eye's block (the eye itself is not in
 * a web), the cell above that web can hold a water source, and a water bucket is in the hotbar.</p>
 *
 * <p><b>Sequence.</b> Tick N: an owner-scoped HIGHEST rotation request turns toward the web's top face.
 * As soon as the live filled-bucket ray along the authoritative angles lands on that face, the water
 * bucket is selected and the use follows later in the same tick; the reached angles are held for the
 * rest of tick N. The same aim stays on the face afterwards, which also runs through the placed source.
 * Once Pickup Delay has passed (and the web is gone, or Max Hold has passed, when waiting for the
 * break), the emptied bucket is used again as soon as the live source ray reaches the water. The slot is
 * restored on a later tick, only while this module still holds it. Uses are gated by vanilla's use
 * cooldown, so the pickup is never sooner than 4 ticks after the placement.</p>
 *
 * <p><b>Rotation.</b> The turn goes through the shared {@link RotationManager} controller (GCD
 * quantisation, humanisation, smooth release) toward a sticky face point picked to keep the yaw close
 * to where the player already looks. The point stays off the vertical, so the pitch never reaches 90
 * and the yaw is always defined. Both axes are capped at {@value #MAX_TURN_PER_TICK} degrees per tick,
 * under the single-tick snap thresholds in {@code checkers/} (MXAc AimFactor, Intave RotationSnap and
 * placement Snap), and a large yaw turn never ends on a 45-degree line (Intave AngleSnap). Every ray uses
 * {@value #MAX_REACH} blocks of reach.</p>
 *
 * <p><b>Priority.</b> While an engagement is in flight every other module is cancelled and its tick
 * dispatch held back through {@link AutomationPriority}, the rotation channel is held exclusively, and
 * other modules' item swaps and automatic attacks are refused through {@link CombatSwapLockEvent}.</p>
 */
public class WebEscape extends Module implements interstellarTikiTiks.apexx.client.module.hooks.ClickSimulating {

    private static final String WATER_THEN_PEARL = "Water, Pearl Fallback";
    private static final String PEARL_THEN_WATER = "Pearl, Water Fallback";
    private static final String WATER_ONLY = "Water Only";
    private static final String PEARL_ONLY = "Pearl Only";

    private final ModeSetting escapeWith = new ModeSetting("Escape With", WATER_THEN_PEARL,
            WATER_THEN_PEARL, PEARL_THEN_WATER, WATER_ONLY, PEARL_ONLY)
            .setDescription("Water, Pearl Fallback: breaks the web with a water bucket, and throws a pearl when there is no bucket or no water spot. Pearl, Water Fallback: the other way round. Water Only / Pearl Only: just that one.");
    private final WebPearlEscaper pearl = new WebPearlEscaper(this);

    private final SliderSetting reactionDelay = new SliderSetting("Reaction Delay (ms)", 80, 0, 1000, 0)
            .withJitter(30)
            .setDescription("How long you must be stuck in a web before the escape starts, like a player noticing it. 0 reacts on the first stuck tick.");
    private final SliderSetting pickupDelay = new SliderSetting("Pickup Delay (ms)", 100, 0, 1000, 0)
            .withJitter(20)
            .setDescription("Minimum time the water stays down before it is picked back up. Vanilla's 4-tick item-use cooldown applies on top, so the pickup never shares the placement tick, even at 0.");
    private final BooleanSetting waitForBreak = new BooleanSetting("Wait For Break", true)
            .setDescription("Holds the pickup until the web is actually gone. Water flows into the web 5 game ticks after it is placed; picking it up sooner leaves the web in place.");
    private final SliderSetting maxHold = new SliderSetting("Max Hold (ms)", 600, 0, 2000, 0)
            .setDescription("Safety limit for Wait For Break: picks the water up after this long even if the web is still there.");
    private final SliderSetting cooldown = new SliderSetting("Cooldown", 250, 0, 2000, 0)
            .withJitter(60)
            .setDescription("Rest time in ms after a finished escape before another one can start.");
    private final SliderSetting aimStrength = new SliderSetting("Aim Strength", 85, 1, 100, 0)
            .setDescription("How fast the view turns down to the web. Higher is quicker; the turn is always capped at 24 degrees per tick on both axes to stay below snap checks.");
    private final BooleanSetting silentAim = new BooleanSetting("Silent Aim", false)
            .setDescription("Turns only the angles sent to the server; your camera keeps its view. Off turns the camera visibly like a player would.");
    private final BooleanSetting switchBack = new BooleanSetting("Swap Back", true)
            .setDescription("Reselects the hotbar slot you were holding before, once the water is picked back up.");

    /** The flick must land on the face within this window, or the engagement is dropped. */
    private static final long AIM_TIMEOUT_MS = 900L;
    /** Extra time after the latest planned pickup before an unreachable source is left behind. */
    private static final long PICKUP_GRACE_MS = 1500L;
    /** Ticks a use gets to show up in the world (client prediction or server reply) before a retry. */
    private static final int CONFIRM_TICKS = 3;
    private static final int MAX_ATTEMPTS = 2;
    private static final double FACE_INSET = 0.2;
    /** The aim point sits this far up the web cell: just under the top face, so the ray enters through it. */
    private static final double FACE_DEPTH = 0.9;
    /** Candidate points per face axis. */
    private static final int AIM_GRID = 7;
    /** Keeps the look off the vertical: target pitch stays under ~82 degrees and the yaw stays well defined. */
    private static final double MIN_HORIZONTAL = 0.25;
    /**
     * Turn ceiling per tick, both axes. Below MXAc AimFactor (35), Intave RotationSnap (40) and Intave
     * placement Snap (70) single-tick thresholds, with room for a late client tick.
     */
    private static final double MAX_TURN_PER_TICK = 24.0;
    /** A yaw turn larger than this must not end within {@link #ALIGNED_MARGIN} of a 45-degree line (Intave AngleSnap). */
    private static final double ALIGNED_TURN_MIN = 10.0;
    private static final double ALIGNED_MARGIN = 0.5;
    /** Block interaction distance used for every ray, below vanilla's 4.5 (checkers notes: interactions within 3.0). */
    private static final double MAX_REACH = 3.0;
    /** Organic drift/tremor/overshoot of the shared controller; sub-degree, well inside the face. */
    private static final double AIM_HUMANIZATION = 0.35;

    private enum Phase { IDLE, PLACE, WAIT, PICKUP, CONFIRM }

    private Phase phase = Phase.IDLE;
    private BlockPos web;
    private BlockPos cell;
    private Object boundPlayer;
    private Object boundWorld;
    private int originalSlot = -1;
    private int expectedSlot = -1;
    private long engagedMs;
    private long placedMs;
    private Vec3d aimPoint;
    private int noSwapAge = Integer.MIN_VALUE;
    private int lastUseAge = Integer.MIN_VALUE / 2;
    private int placeAttempts;
    private int pickupAttempts;
    private long cooldownUntilMs;
    /** When the current web was first seen (0 = not stuck) and when the reaction wait ends. */
    private long stuckSinceMs;
    private long reactAtMs;
    /** Pickup wait drawn once per placement so the per-tick check does not re-roll it. */
    private long pickupWaitMs;

    public WebEscape() {
        super("WebEscape", "Gets you out of a cobweb you are stuck in: breaks it with a water bucket and scoops the water back up, or throws an ender pearl out, in the order you choose. While the water escape runs it pauses every other module so nothing fights it for your hotbar or aim.", Category.PLAYER);
        reactionDelay.setSection("Delays");
        pickupDelay.setSection("Delays");
        waitForBreak.setSection("Delays");
        maxHold.setSection("Delays");
        maxHold.setVisibility(waitForBreak::isEnabled);
        cooldown.setSection("Delays");
        aimStrength.setSection("Aim");
        silentAim.setSection("Aim");
        // General first, then aim, Delays last.
        addSetting(escapeWith);
        addSetting(switchBack);
        pearl.settings().forEach(this::addSetting);
        addSetting(aimStrength);
        addSetting(silentAim);
        addSetting(reactionDelay);
        addSetting(pickupDelay);
        addSetting(waitForBreak);
        addSetting(maxHold);
        addSetting(cooldown);
    }

    @Override
    public void onEnable() {
        EventBus.register(this);
        resetState();
        cooldownUntilMs = 0L;
    }

    @Override
    public void onDisable() {
        EventBus.unregister(this);
    }

    @Override
    protected void onRuntimeStateReleased() {
        pearl.release();
        restoreSlot();
        releaseControl();
        resetState();
    }

    @EventTarget
    public void onCombatSwapLock(CombatSwapLockEvent event) {
        if (event.isRequestedBy(this) || phase == Phase.IDLE) return;
        event.setItemSwapLocked(true);
        event.setAutoAttackLocked(true);
    }

    @Override
    public void onTick() {
        if (mc.player == null || mc.world == null) {
            if (phase != Phase.IDLE || pearl.isBusy()) releaseRuntimeState();
            return;
        }
        if (pearl.isBusy()) {
            pearl.tick();
            return;
        }
        if (phase != Phase.IDLE && (mc.player != boundPlayer || mc.world != boundWorld)) {
            finish();
            return;
        }
        switch (phase) {
            case IDLE -> escape();
            case PLACE -> tickPlace();
            case WAIT -> tickWait();
            case PICKUP -> tickPickup();
            case CONFIRM -> tickConfirm();
        }
    }

    // ── Phases ───────────────────────────────────────────────────────────

    /**
     * Picks the escape from the mode: the preferred one first, the other as fallback. Water is only
     * tried when a bucket is in the hotbar (or in Water Only, which warns when it is missing).
     */
    private void escape() {
        // Reaction wait applies to both escapes, so a pending water reaction never lets the pearl fallback jump the queue.
        long now = System.currentTimeMillis();
        if (findWebBelow() == null) {
            stuckSinceMs = 0L;
        } else {
            if (stuckSinceMs == 0L) {
                stuckSinceMs = now;
                reactAtMs = now + ModuleDelays.clampMs(reactionDelay.sample());
            }
            if (now < reactAtMs) return;
        }
        boolean waterAllowed = !escapeWith.is(PEARL_ONLY);
        boolean pearlAllowed = !escapeWith.is(WATER_ONLY);
        if (pearlAllowed && escapeWith.is(PEARL_THEN_WATER) || escapeWith.is(PEARL_ONLY)) {
            pearl.tick();
            if (pearl.isBusy()) return;
        }
        if (waterAllowed && (escapeWith.is(WATER_ONLY) || findHotbar(Items.WATER_BUCKET) >= 0)) {
            tryEngage();
            if (phase != Phase.IDLE) return;
        }
        if (pearlAllowed && !escapeWith.is(PEARL_THEN_WATER) && !escapeWith.is(PEARL_ONLY)) {
            pearl.tick();
        }
    }

    private void tryEngage() {
        long now = System.currentTimeMillis();
        if (now < cooldownUntilMs || mc.currentScreen != null || mc.player.isSpectator()) return;
        BlockPos found = findWebBelow();
        if (found == null) return;
        if (findHotbar(Items.WATER_BUCKET) < 0) {
            warnMissing("water", "No water bucket in hotbar");
            return;
        }
        int before = SlotSync.current(mc);
        // Top priority: cancel every other module first, so their slot restores go out before our swap.
        AutomationPriority.raise(this);
        RotationManager.acquireExclusiveLock(this);
        boundPlayer = mc.player;
        boundWorld = mc.world;
        web = found;
        cell = found.up();
        originalSlot = SlotSync.current(mc);
        // A cancelled module just changed the slot: our own swap waits a tick so two hotbar changes
        // never leave back to back.
        if (originalSlot != before) noSwapAge = mc.player.age;
        engagedMs = now;
        placeAttempts = 0;
        pickupAttempts = 0;
        phase = Phase.PLACE;
        tickPlace();
    }

    private void tickPlace() {
        long now = System.currentTimeMillis();
        int waterSlot = findHotbar(Items.WATER_BUCKET);
        if (!mc.world.getBlockState(web).isOf(Blocks.COBWEB) || !overlapsPlayer(web) || !canHoldWater(cell)
                || waterSlot < 0 || now - engagedMs > AIM_TIMEOUT_MS) {
            finish();
            return;
        }
        aim();

        // Action eligibility: the live filled-bucket ray along the authoritative angles lands on the
        // web's top face (the water goes into the cell above). Angular closeness alone never fires.
        if (mc.currentScreen != null || mc.player.isUsingItem() || !InteractSim.useReady(mc) || !placeRayLands()) {
            return;
        }
        int held = SlotSync.current(mc);
        if (held != waterSlot) {
            // Swap first in this tick; refused while a use/attack already left this interval.
            if (mc.player.age == noSwapAge || !SlotSync.select(mc, waterSlot)) return;
            SubTickSequencer.awaitWireSeparation(mc);
        }
        expectedSlot = waterSlot;
        if (!mc.player.getMainHandStack().isOf(Items.WATER_BUCKET) || !placeRayLands()) return;

        ClickSimController.pulseUse(mc);
        TickActionOrder.recordUse();
        // Keep the reached angles for the rest of this tick.
        RotationManager.setPostAttackHold(this, 1, RotationManager.getFinalYaw(), RotationManager.getFinalPitch());
        placedMs = now;
        pickupWaitMs = ModuleDelays.clampMs(pickupDelay.sample());
        lastUseAge = mc.player.age;
        placeAttempts++;
        phase = Phase.WAIT;
    }

    private void tickWait() {
        long now = System.currentTimeMillis();
        if (!isWaterSource(cell)) {
            if (mc.player.age - lastUseAge < CONFIRM_TICKS) {
                aim();
                return;
            }
            // The use placed nothing (input dropped or the server refused it): try again or give up.
            if (placeAttempts < MAX_ATTEMPTS && mc.world.getBlockState(web).isOf(Blocks.COBWEB)) {
                engagedMs = now;
                phase = Phase.PLACE;
                tickPlace();
            } else {
                finish();
            }
            return;
        }
        aim();
        long held = now - placedMs;
        if (held < pickupWaitMs) return;
        boolean webGone = !mc.world.getBlockState(web).isOf(Blocks.COBWEB);
        if (waitForBreak.isEnabled() && !webGone && held < (long) maxHold.getValue()) return;
        phase = Phase.PICKUP;
        tickPickup();
    }

    private void tickPickup() {
        long now = System.currentTimeMillis();
        if (!isWaterSource(cell)) {
            finish();
            return;
        }
        if (now - placedMs > pickupDeadlineMs()) {
            warnMissing("pickup", "Could not reach the water to pick it back up");
            finish();
            return;
        }
        int bucketSlot = findEmptyBucketSlot();
        if (bucketSlot < 0) {
            warnMissing("bucket", "No empty bucket to pick the water back up");
            finish();
            return;
        }
        aim();

        if (mc.currentScreen != null || mc.player.isUsingItem() || !InteractSim.useReady(mc) || !pickupRayHits()) {
            return;
        }
        int held = SlotSync.current(mc);
        if (held != bucketSlot) {
            if (!SlotSync.select(mc, bucketSlot)) return;
            SubTickSequencer.awaitWireSeparation(mc);
        }
        expectedSlot = bucketSlot;
        if (!mc.player.getMainHandStack().isOf(Items.BUCKET) || !pickupRayHits()) return;

        ClickSimController.pulseUse(mc);
        TickActionOrder.recordUse();
        RotationManager.setPostAttackHold(this, 1, RotationManager.getFinalYaw(), RotationManager.getFinalPitch());
        lastUseAge = mc.player.age;
        pickupAttempts++;
        phase = Phase.CONFIRM;
    }

    /** Waits for the source to disappear; the slot goes back from the tick after the pickup at the earliest. */
    private void tickConfirm() {
        if (!isWaterSource(cell)) {
            finish();
            return;
        }
        aim();
        if (mc.player.age - lastUseAge < CONFIRM_TICKS) return;
        if (pickupAttempts < MAX_ATTEMPTS) {
            phase = Phase.PICKUP;
            tickPickup();
        } else {
            warnMissing("pickup", "Could not pick the water back up");
            finish();
        }
    }

    private long pickupDeadlineMs() {
        long planned = Math.max(pickupWaitMs, (long) pickupDelay.getValue());
        if (waitForBreak.isEnabled()) planned = Math.max(planned, (long) maxHold.getValue());
        return planned + PICKUP_GRACE_MS;
    }

    /** Ends the engagement: slot back while we still hold it, every lock released, cooldown started. */
    private void finish() {
        restoreSlot();
        releaseControl();
        resetState();
        cooldownUntilMs = System.currentTimeMillis() + ModuleDelays.clampMs(cooldown.sample());
        stuckSinceMs = 0L;
    }

    private void releaseControl() {
        RotationManager.setMaxSpeedCap(this, 0.0);
        RotationManager.clearTarget(this);
        RotationManager.releaseExclusiveLock(this);
        AutomationPriority.lower(this);
        SlotSync.releaseOwner(this);
    }

    private void resetState() {
        phase = Phase.IDLE;
        web = null;
        cell = null;
        boundPlayer = null;
        boundWorld = null;
        originalSlot = -1;
        expectedSlot = -1;
        engagedMs = 0L;
        placedMs = 0L;
        aimPoint = null;
        noSwapAge = Integer.MIN_VALUE;
        placeAttempts = 0;
        pickupAttempts = 0;
    }

    // ── Aim ──────────────────────────────────────────────────────────────

    /**
     * One sticky target for the whole engagement: a point just under the web's top face. The
     * filled-bucket ray enters through that face; the source ray toward it passes through the placed
     * water above the face. A new point is picked only when the eye has moved so the old one no longer
     * works, so the controller never sees the target jump while it is turning.
     */
    private void aim() {
        if (aimPoint == null || !aimPointUsable(aimPoint, web)) {
            aimPoint = selectAimPoint(web);
        }
        final Vec3d point = aimPoint;
        if (point == null) return;
        RotationManager.setMaxSpeedCap(this, MAX_TURN_PER_TICK);
        // This overload also clears a prediction lead left by another owner; the point does not move.
        RotationManager.setRotationSupplier(
                this, RotationManager.Priority.HIGHEST, () -> point,
                RotationManager.engineStrength(aimStrength.getValue(), RotationManager.STRENGTH_SCALE_FLICK),
                RotationManager.RotationMode.SMOOTH, AIM_HUMANIZATION, silentAim.isEnabled(), false);
    }

    /**
     * The face point that costs the least turn from the current authoritative angles, yaw weighted
     * above pitch, among points that keep the look off the vertical (at least {@link #MIN_HORIZONTAL}
     * from the eye's column, so the pitch stays well under 90 and the yaw is well defined) and whose
     * ray really enters through the top face within reach. A turn of more than {@link #ALIGNED_TURN_MIN}
     * degrees never ends on a 45-degree yaw line. Null when no point qualifies.
     */
    private Vec3d selectAimPoint(BlockPos pos) {
        if (mc.player == null || pos == null) return null;
        Vec3d eye = mc.player.getEyePos();
        float yawNow = currentYaw();
        float pitchNow = currentPitch();
        double span = 1.0 - 2.0 * FACE_INSET;
        Vec3d best = null;
        double bestCost = Double.MAX_VALUE;
        for (int i = 0; i < AIM_GRID; i++) {
            for (int j = 0; j < AIM_GRID; j++) {
                Vec3d point = new Vec3d(
                        pos.getX() + FACE_INSET + span * i / (AIM_GRID - 1),
                        pos.getY() + FACE_DEPTH,
                        pos.getZ() + FACE_INSET + span * j / (AIM_GRID - 1));
                double dx = point.x - eye.x;
                double dz = point.z - eye.z;
                double horizontal = Math.sqrt(dx * dx + dz * dz);
                if (horizontal < MIN_HORIZONTAL) continue;
                float yaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0);
                float pitch = (float) -Math.toDegrees(Math.atan2(point.y - eye.y, horizontal));
                double yawTurn = Math.abs(MathHelper.wrapDegrees(yaw - yawNow));
                if (yawTurn > ALIGNED_TURN_MIN && distanceTo45(yaw) < ALIGNED_MARGIN) continue;
                if (!rayEntersTop(eye, point, pos)) continue;
                double cost = yawTurn * 2.0 + Math.abs(pitch - pitchNow);
                if (cost < bestCost) {
                    bestCost = cost;
                    best = point;
                }
            }
        }
        return best;
    }

    private boolean aimPointUsable(Vec3d point, BlockPos pos) {
        Vec3d eye = mc.player.getEyePos();
        double dx = point.x - eye.x;
        double dz = point.z - eye.z;
        return dx * dx + dz * dz >= MIN_HORIZONTAL * MIN_HORIZONTAL && rayEntersTop(eye, point, pos);
    }

    private static double distanceTo45(float yaw) {
        double mod = ((yaw % 45.0) + 45.0) % 45.0;
        return Math.min(mod, 45.0 - mod);
    }

    /** The vanilla filled-bucket ray (outline shapes, no fluids) from {@code eye} through {@code point} enters {@code pos} through its top. */
    private boolean rayEntersTop(Vec3d eye, Vec3d point, BlockPos pos) {
        Vec3d dir = point.subtract(eye);
        if (dir.lengthSquared() < 1.0e-6) return false;
        Vec3d end = eye.add(dir.normalize().multiply(reach()));
        BlockHitResult hit = mc.world.raycast(new RaycastContext(
                eye, end, RaycastContext.ShapeType.OUTLINE, RaycastContext.FluidHandling.NONE, mc.player));
        return hit.getType() == HitResult.Type.BLOCK && hit.getBlockPos().equals(pos) && hit.getSide() == Direction.UP;
    }

    /** The same ray along the live authoritative angles: the place is eligible only when it lands on the web's top face. */
    private boolean placeRayLands() {
        Vec3d eye = mc.player.getEyePos();
        Vec3d end = eye.add(currentLook().multiply(reach()));
        BlockHitResult hit = mc.world.raycast(new RaycastContext(
                eye, end, RaycastContext.ShapeType.OUTLINE, RaycastContext.FluidHandling.NONE, mc.player));
        return hit.getType() == HitResult.Type.BLOCK && hit.getBlockPos().equals(web) && hit.getSide() == Direction.UP;
    }

    /** The vanilla empty-bucket ray (outline shapes, source fluids) along the live angles reaches the placed source. */
    private boolean pickupRayHits() {
        Vec3d eye = mc.player.getEyePos();
        Vec3d end = eye.add(currentLook().multiply(reach()));
        BlockHitResult hit = mc.world.raycast(new RaycastContext(
                eye, end, RaycastContext.ShapeType.OUTLINE, RaycastContext.FluidHandling.SOURCE_ONLY, mc.player));
        return hit.getType() == HitResult.Type.BLOCK && hit.getBlockPos().equals(cell);
    }

    /** The angles the server receives with the use: the silent angles while silent, otherwise the player's. */
    private Vec3d currentLook() {
        return RotationManager.isSilentRotationActive()
                ? RotationManager.getSilentRotationVec()
                : mc.player.getRotationVec(1.0f);
    }

    private float currentYaw() {
        return RotationManager.isSilentRotationActive() ? RotationManager.getFinalYaw() : mc.player.getYaw();
    }

    private float currentPitch() {
        return RotationManager.isSilentRotationActive() ? RotationManager.getFinalPitch() : mc.player.getPitch();
    }

    private double reach() {
        return Math.min(MAX_REACH, mc.player.getBlockInteractionRange());
    }

    // ── World queries ────────────────────────────────────────────────────

    /**
     * The cobweb the player stands in below the eye's block: the one under the box centre first, then
     * the higher one. Null while the eye itself is in a web.
     */
    private BlockPos findWebBelow() {
        Vec3d eye = mc.player.getEyePos();
        if (mc.world.getBlockState(BlockPos.ofFloored(eye)).isOf(Blocks.COBWEB)) return null;
        Box box = mc.player.getBoundingBox();
        int minX = MathHelper.floor(box.minX + 1.0e-7);
        int maxX = MathHelper.floor(box.maxX - 1.0e-7);
        int minY = MathHelper.floor(box.minY + 1.0e-7);
        int maxY = Math.min(MathHelper.floor(box.maxY - 1.0e-7), MathHelper.floor(eye.y) - 1);
        int minZ = MathHelper.floor(box.minZ + 1.0e-7);
        int maxZ = MathHelper.floor(box.maxZ - 1.0e-7);
        double cx = (box.minX + box.maxX) * 0.5;
        double cz = (box.minZ + box.maxZ) * 0.5;

        BlockPos best = null;
        double bestScore = Double.MAX_VALUE;
        BlockPos.Mutable cursor = new BlockPos.Mutable();
        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
                for (int z = minZ; z <= maxZ; z++) {
                    cursor.set(x, y, z);
                    if (!mc.world.getBlockState(cursor).isOf(Blocks.COBWEB)) continue;
                    BlockPos pos = cursor.toImmutable();
                    if (!canHoldWater(pos.up()) || selectAimPoint(pos) == null) continue;
                    double dx = x + 0.5 - cx;
                    double dz = z + 0.5 - cz;
                    double score = dx * dx + dz * dz - y * 1.0e-3;
                    if (score < bestScore) {
                        bestScore = score;
                        best = pos;
                    }
                }
            }
        }
        return best;
    }

    private boolean overlapsPlayer(BlockPos pos) {
        return mc.player.getBoundingBox().contract(1.0e-7).intersects(new Box(pos));
    }

    /** A water bucket clicked on the face below would leave a source here (and it would not evaporate). */
    private boolean canHoldWater(BlockPos pos) {
        BlockState state = mc.world.getBlockState(pos);
        if (!state.getFluidState().isEmpty() || state.getBlock() instanceof FluidFillable) return false;
        if (!state.isAir() && !state.canBucketPlace(Fluids.WATER)) return false;
        return !Boolean.TRUE.equals(mc.world.getEnvironmentAttributes()
                .getAttributeValue(EnvironmentAttributes.WATER_EVAPORATES_GAMEPLAY, pos));
    }

    private boolean isWaterSource(BlockPos pos) {
        if (pos == null) return false;
        FluidState fluid = mc.world.getFluidState(pos);
        return fluid.isOf(Fluids.WATER) && fluid.isStill();
    }

    // ── Slot ownership ───────────────────────────────────────────────────

    /** The slot the water bucket emptied into first, then any hotbar empty bucket. */
    private int findEmptyBucketSlot() {
        if (expectedSlot >= 0 && mc.player.getInventory().getStack(expectedSlot).isOf(Items.BUCKET)) return expectedSlot;
        return findHotbar(Items.BUCKET);
    }

    private int findHotbar(Item item) {
        int held = mc.player.getInventory().getSelectedSlot();
        if (mc.player.getInventory().getStack(held).isOf(item)) return held;
        for (int i = 0; i < 9; i++) {
            if (mc.player.getInventory().getStack(i).isOf(item)) return i;
        }
        return -1;
    }

    /** Swap back only while we still own the selection (the slot we picked is still held). */
    private void restoreSlot() {
        if (mc.player == null || mc.player != boundPlayer || mc.world != boundWorld) return;
        if (!switchBack.isEnabled() || originalSlot < 0 || expectedSlot < 0 || originalSlot == expectedSlot) return;
        if (SlotSync.current(mc) == expectedSlot) SlotSync.restore(mc, originalSlot);
    }
}
