package interstellarTikiTiks.apexx.client.module.implementation;

import interstellarTikiTiks.apexx.client.util.combat.ModuleDelays;
import interstellarTikiTiks.apexx.client.module.*;
import interstellarTikiTiks.apexx.client.module.Module;
import interstellarTikiTiks.apexx.client.module.hooks.ItemUseAware;
import interstellarTikiTiks.apexx.client.util.ClickSimController;
import interstellarTikiTiks.apexx.client.util.RotationManager;
import interstellarTikiTiks.apexx.client.util.combat.InteractSim;
import interstellarTikiTiks.apexx.client.util.combat.SlotSync;
import interstellarTikiTiks.apexx.client.util.combat.TickActionOrder;
import net.minecraft.block.BlockState;
import net.minecraft.fluid.FluidState;
import net.minecraft.fluid.Fluids;
import net.minecraft.item.Item;
import net.minecraft.item.Items;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Drains still water/lava with an empty bucket.
 *
 * <p>Engine: every tick it ranks reachable fluid sources by rotation cost (angle from the current
 * look, then distance), keeps one sticky target, and steers the shared {@link RotationManager}
 * request at a point inside the fluid shape that a fluid-aware ray can actually hit. The click is
 * decided by the live bucket ray (the same {@code SOURCE_ONLY} ray vanilla's bucket uses) along the
 * authoritative angles, not by angular tolerance. As soon as that ray reaches the source, the bucket
 * slot is selected and the use is issued later in the same tick; the reached angles are held for the
 * rest of that tick and the next source is chosen on the following tick. Click cadence is bounded by
 * vanilla's item-use cooldown and the global Timing Profile.
 */
public class AutoDrain extends Module implements ItemUseAware, interstellarTikiTiks.apexx.client.module.hooks.ClickSimulating {
    // ---- Delays ----
    /** Legacy key kept so old configs load; not registered and no longer read. */
    private final BooleanSetting useTimingProfile = new BooleanSetting("Use Timing Profile", false)
            .setSection("Delays");
    private final SliderSetting profileDelay = new SliderSetting("Click Gap (ms)", 30, 0, 1000, 0)
            .withJitter(10)
            .setDescription("Extra wait after each scoop before the next one may start, on top of vanilla's own bucket cooldown. Raise it to make draining look slower and more deliberate.")
            .setSection("Delays");


    private final ModeSetting mode = new ModeSetting("Mode", "Legit", "Blatant")
            .setDescription("Legit: only targets fluids inside Legit FOV and turns smoothly at Strength. Blatant: targets fluids at any angle and turns 50% faster, which is much easier for servers to spot.");
    private final ModeSetting fluids = new ModeSetting("Fluids", "Both", "Both", "Water", "Lava")
            .setDescription("Which fluid sources to scoop up: water, lava, or both.");
    private final SliderSetting rangeDistance = new SliderSetting("Range", 3.0, 1.0, 3.0, 1)
            .setDescription("Farthest a fluid source can be, in blocks, to be scooped. Capped at vanilla reach.");
    private final SliderSetting legitFOV = new SliderSetting("Legit FOV", 90, 10, 90, 0)
            .setDescription("In Legit mode, only fluids within this many degrees of where you look are targeted.");
    private final BooleanSetting silentMode = new BooleanSetting("Silent Aim", false)
            .setDescription("Turns only the rotation the server sees; your camera stays where you put it. Easier for servers to detect than visible aim.");

    private final SliderSetting rotationStrength = new SliderSetting("Strength", 11.5, 1.0, 20.0, 1)
            .setDescription("How fast the view turns onto a fluid source. Lower is slower and more human-looking.");
    private final ModeSetting rotPattern = new ModeSetting("Pattern", "Smooth", "Sine", "Linear")
            .setDescription("Shape of the turning motion: Smooth (eased), Sine (gentle start and stop) or Linear (constant speed).");
    private final SliderSetting rotRandom = new SliderSetting("Aim Randomness", 0.35, 0.0, 1.0, 2)
            .setDescription("Adds small random variation to the aim path so turns are not perfectly mechanical. 0 = none.");

    private final BooleanSetting autoSwitch = new BooleanSetting("Auto Switch", true)
            .setDescription("Switches to an empty bucket from your hotbar when a fluid is targeted, and switches back when done.");
    private final BooleanSetting ignoreOwnWater = new BooleanSetting("Ignore Own Fluid", true)
            .setDescription("Never scoops fluids you placed yourself (e.g. your clutch water or a lava trap).");
    private final BooleanSetting webDrain = new BooleanSetting("Web Drain", false)
            .setDescription("Instead of scooping the source with a bucket, place a cobweb into it. The web replaces the water or lava source, so it is gone without needing an empty bucket. Needs cobwebs in the hotbar; the web goes against a solid block next to the source.");
    private final SliderSetting scanYRange = new SliderSetting("Scan Height", 2.0, 1.0, 6.0, 0)
            .setDescription("How many blocks above and below you are searched for fluid sources.");

    /** Ticks a clicked source is skipped while the server's reply removing it is in flight. */
    private static final int CLICKED_SKIP_TICKS = 6;
    /** Ticks a target may be held without a scoop before it is dropped and another one is picked. */
    private static final int STALL_TICKS = 20;
    private static final double INSET_MIN = 0.25;
    private static final double INSET_MAX = 0.75;

    private BlockPos target = null;
    private int originalSlot = -1;
    private int ownedSlot = -1;
    private int lastClickAge = Integer.MIN_VALUE / 2;
    private int nextClickAge = 0;
    private int targetSince = 0;
    private final Set<BlockPos> placedWater = new HashSet<>();
    private final Map<BlockPos, Integer> clickedAt = new HashMap<>();

    public AutoDrain() {
        super("AutoDrain", "Automatically scoops up nearby water and lava sources with an empty bucket: it picks the easiest source to reach, turns smoothly onto it, and only clicks once the bucket's own ray actually hits the source. Handy for clearing an opponent's water or lava. Respects vanilla's bucket cooldown.", Category.PLAYER);
        addSetting(profileDelay);
        addSetting(mode);
        addSetting(fluids);
        addSetting(rangeDistance);
        addSetting(legitFOV);
        addSetting(silentMode);
        addSetting(rotationStrength);
        addSetting(rotPattern);
        addSetting(rotRandom);
        addSetting(autoSwitch);
        addSetting(ignoreOwnWater);
        addSetting(scanYRange);
        addSetting(webDrain);
    }

    /** The item a drain click uses: a cobweb in Web Drain mode, otherwise an empty bucket. */
    private Item drainItem() {
        return webDrain.isEnabled() ? Items.COBWEB : Items.BUCKET;
    }

    /**
     * Web Drain aim: a point on the face of a solid neighbour that touches the source, so a block
     * placement against it lands inside the source cell. Nearest reachable face to the eye wins.
     */
    private Vec3d webAimPoint(BlockPos pos) {
        if (mc.player == null || mc.world == null) return null;
        Vec3d eye = mc.player.getEyePos();
        Vec3d best = null;
        double bestDist = Double.MAX_VALUE;
        for (net.minecraft.util.math.Direction dir : net.minecraft.util.math.Direction.values()) {
            BlockPos neighbour = pos.offset(dir);
            BlockState state = mc.world.getBlockState(neighbour);
            if (state.isAir() || state.isReplaceable() || !state.getFluidState().isEmpty()) continue;
            Vec3d point = pos.toCenterPos().add(dir.getOffsetX() * 0.5, dir.getOffsetY() * 0.5, dir.getOffsetZ() * 0.5);
            if (!webRayReaches(eye, point, pos, reach())) continue;
            double d = eye.squaredDistanceTo(point);
            if (d < bestDist) {
                bestDist = d;
                best = point;
            }
        }
        return best;
    }

    /** A fluid-ignoring placement ray from {@code eye} through {@code point} that would place into {@code pos}. */
    private boolean webRayReaches(Vec3d eye, Vec3d point, BlockPos pos, double reach) {
        Vec3d dir = point.subtract(eye);
        if (dir.lengthSquared() < 1.0e-6) return false;
        return webRayPlacesInto(eye, eye.add(dir.normalize().multiply(reach)), pos);
    }

    private boolean webRayPlacesInto(Vec3d eye, Vec3d end, BlockPos pos) {
        BlockHitResult hit = mc.world.raycast(new RaycastContext(
                eye, end, RaycastContext.ShapeType.OUTLINE, RaycastContext.FluidHandling.NONE, mc.player));
        return hit.getType() == HitResult.Type.BLOCK && hit.getBlockPos().offset(hit.getSide()).equals(pos);
    }

    @Override
    public void onEnable() {
        reset();
    }

    @Override
    public void onDisable() {
        releaseAll();
    }

    @Override
    protected void onRuntimeStateReleased() {
        releaseAll();
    }

    private void releaseAll() {
        RotationManager.stop(this);
        restoreOriginalSlot();
        SlotSync.releaseOwner(this);
        reset();
        placedWater.clear();
        clickedAt.clear();
    }

    @Override
    public void onTick() {
        if (mc.player == null || mc.world == null || mc.currentScreen != null) {
            return;
        }
        step();
    }

    @Override
    public boolean onItemUse(BlockHitResult hitResult) {
        if (!ignoreOwnWater.isEnabled() || mc.player == null || mc.world == null)
            return false;

        boolean holdingFluidBucket = mc.player.getMainHandStack().isOf(Items.WATER_BUCKET)
                || mc.player.getMainHandStack().isOf(Items.LAVA_BUCKET)
                || mc.player.getOffHandStack().isOf(Items.WATER_BUCKET)
                || mc.player.getOffHandStack().isOf(Items.LAVA_BUCKET);
        boolean holdingEmptyBucket = mc.player.getMainHandStack().isOf(Items.BUCKET)
                || mc.player.getOffHandStack().isOf(Items.BUCKET);

        if (holdingFluidBucket) {
            BlockPos targetPos = hitResult.getBlockPos();
            BlockState state = mc.world.getBlockState(targetPos);
            BlockPos fluidPlacementPos = state.isReplaceable() ? targetPos : targetPos.offset(hitResult.getSide());
            placedWater.add(fluidPlacementPos);
        }

        if (holdingEmptyBucket) {
            BlockPos targetPos = hitResult.getBlockPos();
            if (isStillFluid(targetPos)) {
                placedWater.remove(targetPos);
            }
        }
        return false;
    }

    // ── Engine ───────────────────────────────────────────────────────────

    private void step() {
        int age = mc.player.age;
        if (age < lastClickAge) {
            // A new player entity (respawn, dimension change) restarts its age at zero; a click
            // gate left from the old one would keep every scoop blocked until the age caught up.
            lastClickAge = Integer.MIN_VALUE / 2;
            nextClickAge = 0;
            clickedAt.clear();
        }
        if ((age & 7) == 0) {
            pruneStalePlacedWater();
        }
        clickedAt.values().removeIf(t -> age - t > CLICKED_SKIP_TICKS);

        int bucketSlot = findBucketSlot();
        if (bucketSlot == -1) {
            finishEngagement();
            return;
        }

        if (target != null && !isTargetUsable(target)) {
            target = null;
        }
        if (target != null && age - targetSince > STALL_TICKS) {
            // The ray never reached it (a block or the player's movement got in the way): move on.
            clickedAt.put(target, age + STALL_TICKS);
            target = null;
        }
        if (target == null) {
            target = scan();
            targetSince = age;
        }
        if (target == null) {
            finishEngagement();
            return;
        }

        final BlockPos aimed = target;
        RotationManager.setRotationSupplier(
                this,
                RotationManager.Priority.NORMAL,
                () -> aimPoint(aimed),
                aimStrength(),
                getRotationMode(),
                rotRandom.getValue(),
                silentMode.isEnabled(),
                false);

        // Action eligibility: the live bucket ray along the authoritative angles reaches the
        // source, and vanilla plus the Timing Profile allow a use now. Angular closeness alone
        // never fires.
        if (age < nextClickAge || !InteractSim.useReady(mc) || !liveRayHits(aimed)) {
            return;
        }

        int held = mc.player.getInventory().getSelectedSlot();
        if (held != bucketSlot) {
            if (!autoSwitch.isEnabled()) {
                return;
            }
            // Swap first in this tick; refused while a use/attack already left this interval.
            if (!SlotSync.select(mc, bucketSlot)) {
                return;
            }
            if (originalSlot == -1) {
                originalSlot = held;
            }
        }
        ownedSlot = bucketSlot;

        if (!mc.player.getMainHandStack().isOf(drainItem()) || !liveRayHits(aimed)) {
            return;
        }

        ClickSimController.pulseUse(mc);
        TickActionOrder.recordUse();
        placedWater.remove(aimed);
        clickedAt.put(aimed, age);
        lastClickAge = age;
        nextClickAge = age + Math.max(1, ModuleDelays.msToTicks(profileDelay.sample()));
        target = null;
        // Keep the reached angles for the rest of this tick; the next source is aimed from tick N+1.
        RotationManager.setPostAttackHold(this, 1, RotationManager.getFinalYaw(), RotationManager.getFinalPitch());
    }

    /** Nothing left to drain: release our rotation request and give the slot back once it is safe. */
    private void finishEngagement() {
        target = null;
        RotationManager.clearTarget(this);
        // Not in the tick that clicked: the swap-back follows the use by at least one tick.
        if (mc.player != null && mc.player.age > lastClickAge) {
            restoreOriginalSlot();
        }
    }

    private double aimStrength() {
        double base = rotationStrength.getValue();
        return mode.getCurrentMode().equals("Blatant") ? Math.min(20.0, base * 1.5) : base;
    }

    private RotationManager.RotationMode getRotationMode() {
        try {
            return RotationManager.RotationMode.valueOf(rotPattern.getCurrentMode().toUpperCase());
        } catch (Exception e) {
            return RotationManager.RotationMode.SMOOTH;
        }
    }

    // ── Targeting ────────────────────────────────────────────────────────

    private boolean isTargetUsable(BlockPos pos) {
        if (!isStillFluid(pos)) return false;
        if (ignoreOwnWater.isEnabled() && placedWater.contains(pos)) return false;
        Integer clicked = clickedAt.get(pos);
        if (clicked != null) return false;
        Vec3d eye = mc.player.getEyePos();
        if (eye.distanceTo(pos.toCenterPos()) > reach() + 0.87) return false;
        Vec3d point = aimPoint(pos);
        return point != null && rayReaches(eye, point, pos, reach());
    }

    private BlockPos scan() {
        Vec3d eye = mc.player.getEyePos();
        double maxRange = reach();
        boolean blatant = mode.getCurrentMode().equals("Blatant");
        int range = (int) Math.ceil(maxRange);
        int yRange = Math.min(range, (int) Math.ceil(scanYRange.getValue()));
        BlockPos base = mc.player.getBlockPos();
        Vec3d look = currentLook();

        BlockPos best = null;
        double bestScore = Double.MAX_VALUE;
        BlockPos.Mutable cursor = new BlockPos.Mutable();
        for (int x = -range; x <= range; x++) {
            for (int y = -yRange; y <= yRange; y++) {
                for (int z = -range; z <= range; z++) {
                    cursor.set(base.getX() + x, base.getY() + y, base.getZ() + z);
                    if (!isStillFluid(cursor)) continue;
                    BlockPos pos = cursor.toImmutable();
                    if (ignoreOwnWater.isEnabled() && placedWater.contains(pos)) continue;
                    if (clickedAt.containsKey(pos)) continue;

                    Vec3d point = aimPoint(pos);
                    if (point == null || !rayReaches(eye, point, pos, maxRange)) continue;

                    Vec3d dir = point.subtract(eye).normalize();
                    double angle = Math.toDegrees(Math.acos(MathHelper.clamp(look.dotProduct(dir), -1.0, 1.0)));
                    if (!blatant && angle > legitFOV.getValue()) continue;

                    double score = angle + eye.distanceTo(point) * 3.0;
                    if (score < bestScore) {
                        bestScore = score;
                        best = pos;
                    }
                }
            }
        }
        return best;
    }

    /**
     * A point inside the source's fluid shape that the fluid-aware ray can hit: the point of a
     * slightly inset fluid box nearest the eye, falling back to the surface centre. Recomputed from the
     * current eye so the aim follows the player's movement.
     */
    private Vec3d aimPoint(BlockPos pos) {
        if (mc.player == null || mc.world == null) return null;
        if (webDrain.isEnabled()) {
            Vec3d web = webAimPoint(pos);
            return web != null ? web : pos.toCenterPos();
        }
        FluidState fluid = mc.world.getFluidState(pos);
        double h = fluid.isEmpty() ? 0.8888889 : Math.max(0.2, fluid.getHeight(mc.world, pos));
        Vec3d eye = mc.player.getEyePos();
        double top = pos.getY() + h - 0.05;
        Vec3d nearest = new Vec3d(
                MathHelper.clamp(eye.x, pos.getX() + INSET_MIN, pos.getX() + INSET_MAX),
                MathHelper.clamp(eye.y, pos.getY() + 0.1, top),
                MathHelper.clamp(eye.z, pos.getZ() + INSET_MIN, pos.getZ() + INSET_MAX));
        double reach = reach();
        if (rayReaches(eye, nearest, pos, reach)) {
            return nearest;
        }
        Vec3d surface = new Vec3d(pos.getX() + 0.5, top, pos.getZ() + 0.5);
        if (rayReaches(eye, surface, pos, reach)) {
            return surface;
        }
        return pos.toCenterPos();
    }

    /** The vanilla bucket ray (outline shapes, source fluids only) from {@code eye} through {@code point} lands on {@code pos}. */
    private boolean rayReaches(Vec3d eye, Vec3d point, BlockPos pos, double reach) {
        if (webDrain.isEnabled()) return webRayReaches(eye, point, pos, reach);
        Vec3d dir = point.subtract(eye);
        if (dir.lengthSquared() < 1.0e-6) return false;
        Vec3d end = eye.add(dir.normalize().multiply(reach));
        BlockHitResult hit = mc.world.raycast(new RaycastContext(
                eye, end, RaycastContext.ShapeType.OUTLINE, RaycastContext.FluidHandling.SOURCE_ONLY, mc.player));
        return hit.getType() == HitResult.Type.BLOCK && hit.getBlockPos().equals(pos);
    }

    /** The same ray along the angles the server will see (silent) or the visible view. */
    private boolean liveRayHits(BlockPos pos) {
        Vec3d eye = mc.player.getEyePos();
        double reach = reach();
        Vec3d end = eye.add(currentLook().multiply(reach));
        if (webDrain.isEnabled()) return webRayPlacesInto(eye, end, pos);
        BlockHitResult hit = mc.world.raycast(new RaycastContext(
                eye, end, RaycastContext.ShapeType.OUTLINE, RaycastContext.FluidHandling.SOURCE_ONLY, mc.player));
        return hit.getType() == HitResult.Type.BLOCK && hit.getBlockPos().equals(pos);
    }

    private Vec3d currentLook() {
        float yaw = RotationManager.isSilentRotationActive() ? RotationManager.getFinalYaw() : mc.player.getYaw();
        float pitch = RotationManager.isSilentRotationActive() ? RotationManager.getFinalPitch() : mc.player.getPitch();
        float f = pitch * ((float) Math.PI / 180.0f);
        float g = -yaw * ((float) Math.PI / 180.0f);
        float h = MathHelper.cos(g);
        float i = MathHelper.sin(g);
        float j = MathHelper.cos(f);
        float k = MathHelper.sin(f);
        return new Vec3d(i * j, -k, h * j);
    }

    private double reach() {
        double vanilla = mc.player != null ? mc.player.getBlockInteractionRange() : 4.5;
        return Math.min(rangeDistance.getValue(), vanilla);
    }

    private void pruneStalePlacedWater() {
        if (placedWater.isEmpty() || mc.world == null)
            return;
        placedWater.removeIf(pos -> !isStillFluid(pos));
    }

    private boolean isStillFluid(BlockPos pos) {
        if (pos == null || mc.world == null)
            return false;
        FluidState fluid = mc.world.getBlockState(pos).getFluidState();
        if (fluid.isEmpty() || !fluid.isStill())
            return false;

        boolean isWater = fluid.isOf(Fluids.WATER);
        boolean isLava = fluid.isOf(Fluids.LAVA);
        String fluidMode = fluids.getCurrentMode();
        if (fluidMode.equals("Water")) return isWater;
        if (fluidMode.equals("Lava")) return isLava;
        return isWater || isLava;
    }

    // ── Slot ownership ───────────────────────────────────────────────────

    /** Main-hand bucket first (no swap needed), then the first hotbar empty bucket when Auto Switch is on. */
    private int findBucketSlot() {
        if (mc.player == null) return -1;
        int held = mc.player.getInventory().getSelectedSlot();
        if (mc.player.getMainHandStack().isOf(drainItem())) return held;
        if (!autoSwitch.isEnabled()) return -1;
        return findItemInHotbar(drainItem());
    }

    private int findItemInHotbar(Item item) {
        for (int i = 0; i < 9; i++) {
            if (mc.player.getInventory().getStack(i).getItem() == item)
                return i;
        }
        return -1;
    }

    /** Swap back only while we still own the selection (the slot we picked is still held). */
    private void restoreOriginalSlot() {
        if (mc.player != null && originalSlot != -1) {
            if (mc.player.getInventory().getSelectedSlot() == ownedSlot) {
                SlotSync.restore(mc, originalSlot);
            }
        }
        originalSlot = -1;
        ownedSlot = -1;
    }

    private void reset() {
        RotationManager.clearTarget(this);
        target = null;
        nextClickAge = 0;
        lastClickAge = Integer.MIN_VALUE / 2;
    }
}
