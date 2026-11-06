package interstellarTikiTiks.apexx.client.module.implementation.smp;

import interstellarTikiTiks.apexx.client.module.BooleanSetting;
import interstellarTikiTiks.apexx.client.module.Category;
import interstellarTikiTiks.apexx.client.module.ModeSetting;
import interstellarTikiTiks.apexx.client.module.Module;
import interstellarTikiTiks.apexx.client.module.SliderSetting;
import interstellarTikiTiks.apexx.client.module.hooks.MovementInputModifier;
import interstellarTikiTiks.apexx.client.module.implementation.support.BaritoneBridge;
import interstellarTikiTiks.apexx.client.util.Notifier;
import interstellarTikiTiks.apexx.client.util.RotationManager;
import interstellarTikiTiks.apexx.client.util.SystemInputSimulator;
import interstellarTikiTiks.apexx.client.util.combat.BlockPlacementValidator;
import interstellarTikiTiks.apexx.client.util.combat.ModuleDelays;
import interstellarTikiTiks.apexx.client.util.combat.SlotSync;
import interstellarTikiTiks.apexx.client.util.combat.SmoothAim;
import interstellarTikiTiks.apexx.client.util.combat.TickActionOrder;
import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.fluid.FluidState;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.tag.FluidTags;
import net.minecraft.util.PlayerInput;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.List;

/**
 * AutoMine / Tunnel: digs a straight 1x2 or 3x3 tunnel in the direction you faced when it was turned on.
 *
 * <p>Mining is the vanilla one: the look is turned (owner-scoped {@link RotationManager} request) onto the next
 * block of the tunnel, and the attack key is held only while the crosshair and the live ray are on that exact
 * block, so the game breaks it at normal speed. When the next slice is clear the look turns back down the
 * tunnel and the module walks forward (movement input, or Baritone when installed and chosen). It stops on
 * lava or water next to the tunnel, a missing floor, an unbreakable block, a tool about to break, or the
 * chosen length.</p>
 */
public final class AutoMine extends Module implements MovementInputModifier, interstellarTikiTiks.apexx.client.module.hooks.ClickSimulating {

    private static final String SIZE_1X2 = "1x2";
    private static final String SIZE_3X3 = "3x3";
    /** A block the look cannot get onto within this long ends the run. */
    private static final long STALL_MS = 4000L;
    /** Walking (or Baritone) that has not reached the next slice within this long ends the run. */
    private static final long WALK_STALL_MS = 8000L;

    private final ModeSetting size = new ModeSetting("Size", SIZE_1X2, SIZE_1X2, SIZE_3X3)
            .setDescription("1x2: a one-wide, two-high tunnel. 3x3: three wide and three high, centred on you.");
    private final SliderSetting length = new SliderSetting("Length", 0, 0, 512, 0)
            .setDescription("Stop after this many blocks of tunnel. 0 = keep going until something stops it.");
    private final BooleanSetting autoTool = new BooleanSetting("Auto Tool", true)
            .setDescription("Selects the fastest tool in your hotbar for each block before mining it. Your old slot is given back at the next movement interval after the run ends.");
    private final SliderSetting minDurability = new SliderSetting("Min Durability", 10, 0, 200, 0)
            .setDescription("Stops before the tool in hand has this many uses or fewer left, so it never breaks. 0 = no check.");
    private final BooleanSetting stopAtFluid = new BooleanSetting("Stop At Lava/Water", true)
            .setDescription("Stops when lava (or water) is next to a block about to be mined, before it can flood the tunnel.");
    private final BooleanSetting stopAtDrop = new BooleanSetting("Stop At Drop", true)
            .setDescription("Stops when the floor ahead is missing (a hole, a cave or the void), instead of walking into it.");
    private final BooleanSetting useBaritone = new BooleanSetting("Walk With Baritone", false)
            .setDescription("When the Baritone mod is installed, lets it walk you to the next slice of the tunnel. Off (or without Baritone) walks with the normal forward input.");
    private final SliderSetting aimSpeed = new SliderSetting("Aim Speed", 240, 60, 720, 0)
            .setDescription("How fast the look turns to the next block, in degrees per second.");

    private final SliderSetting nextBlockDelay = new SliderSetting("Next Block Delay (ms)", 120, 0, 1000, 0).withJitter(40)
            .setSection("Delays")
            .setDescription("Wait after a block breaks before starting on the next one.");
    private final SliderSetting walkDelay = new SliderSetting("Walk Delay (ms)", 150, 0, 1000, 0).withJitter(50)
            .setSection("Delays")
            .setDescription("Wait after the slice ahead is clear before walking forward.");

    private BlockPos origin;
    private Direction axis;
    private BlockPos mining;
    private long miningSince;
    private boolean attackHeld;
    private boolean aiming;
    /** An aim request of this module may be registered with the RotationManager. */
    private boolean aimRequested;
    private boolean walking;
    private long nextActionAt;
    private long walkAt = -1L;
    private int restoreSlot = -1;
    private int swappedTo = -1;
    private BlockPos baritoneGoal;
    private long walkSince = -1L;
    private int lastColumn = Integer.MIN_VALUE;

    public AutoMine() {
        super("AutoMine", "Digs a straight 1x2 or 3x3 tunnel the way you are facing: turns onto each block and mines it at normal speed, walks forward when the way is clear, and stops on lava, a drop, an unbreakable block, a worn tool or the chosen length.", Category.SMP);
        addSetting(size);
        addSetting(length);
        addSetting(autoTool);
        addSetting(minDurability);
        addSetting(stopAtFluid);
        addSetting(stopAtDrop);
        addSetting(useBaritone);
        addSetting(aimSpeed);
        addSetting(nextBlockDelay);
        addSetting(walkDelay);
    }

    @Override
    public void onEnable() {
        clearRun();
        if (mc.player == null) return;
        origin = mc.player.getBlockPos().toImmutable();
        axis = Direction.fromHorizontalDegrees(mc.player.getYaw());
    }

    @Override
    public void onDisable() {
        releaseAttack();
        if (restoreSlot >= 0 && mc.player != null && mc.player.getInventory().getSelectedSlot() == swappedTo) {
            TickActionOrder.deferSlot(restoreSlot, swappedTo);
        }
        clearRun();
    }

    @Override
    protected void onRuntimeStateReleased() {
        releaseAttack();
        clearRun();
    }

    private void clearRun() {
        releaseAttack();
        if (aimRequested) RotationManager.clearTarget(this);
        aimRequested = false;
        aiming = false;
        BaritoneBridge.cancel(this);
        baritoneGoal = null;
        origin = null;
        axis = null;
        mining = null;
        walking = false;
        walkAt = -1L;
        walkSince = -1L;
        lastColumn = Integer.MIN_VALUE;
        nextActionAt = 0L;
        restoreSlot = -1;
        swappedTo = -1;
    }

    private void stop(String reason) {
        Notifier.warn("AutoMine", reason);
        setEnabled(false);
    }

    @Override
    public void onTick() {
        ClientPlayerEntity player = mc.player;
        if (player == null || mc.world == null || mc.interactionManager == null) {
            setEnabled(false);
            return;
        }
        if (origin == null || axis == null) {
            origin = player.getBlockPos().toImmutable();
            axis = Direction.fromHorizontalDegrees(player.getYaw());
        }
        if (restoreSlot >= 0 && player.getInventory().getSelectedSlot() != swappedTo) {
            // The player picked a slot themselves: the tool slot is not ours to give back any more.
            restoreSlot = -1;
            swappedTo = -1;
        }
        if (mc.currentScreen != null || player.isDead()) {
            releaseAttack();
            walking = false;
            return;
        }
        if (player.getBlockPos().getY() < origin.getY()
                || (player.isOnGround() && player.getBlockPos().getY() != origin.getY())) {
            stop("Left the tunnel level, stopping.");
            return;
        }
        long now = System.currentTimeMillis();
        int column = columnOf(player.getBlockPos());
        if (column != lastColumn) {
            lastColumn = column;
            walkSince = -1L;
        }
        int limit = (int) length.getValue();
        if (limit > 0 && column >= limit) {
            stop("Tunnel finished (" + limit + " blocks).");
            return;
        }

        if (mining != null && mc.world.getBlockState(mining).isAir()) {
            // The block broke: short pause before the next one.
            mining = null;
            releaseAttack();
            nextActionAt = now + ModuleDelays.clampMs(nextBlockDelay.sample());
        }

        if (mining == null) {
            if (now < nextActionAt) return;
            String problem = pickNext(column, limit);
            if (problem != null) {
                stop(problem);
                return;
            }
            if (mining != null) {
                miningSince = now;
                walking = false;
                walkAt = -1L;
                BaritoneBridge.cancel(this);
                baritoneGoal = null;
            }
        }

        if (mining != null) {
            mine(now);
        } else {
            walk(now, column);
        }
    }

    // ------------------------------------------------------------------ mining

    private void mine(long now) {
        walkSince = -1L;
        if (now - miningSince > STALL_MS) {
            stop("Could not reach the next block, stopping.");
            return;
        }
        BlockPos block = mining;
        Vec3d point = SmoothAim.nearestFacePoint(mc, block, null);
        Vec3d aimAt = point != null ? point : Vec3d.ofCenter(block);
        aimRequested = true;
        aiming = SmoothAim.aimPoint(this, () -> aimAt, aimSpeed.getValue(), false);
        if (!aiming || !liveOn(block)) {
            releaseAttack();
            return;
        }
        // The look is on the block: the stall timer only counts time spent getting onto it, not mining it.
        miningSince = now;
        if (autoTool.isEnabled() && !attackHeld) {
            int best = bestToolSlot(mc.world.getBlockState(block));
            int held = mc.player.getInventory().getSelectedSlot();
            if (best >= 0 && best != held) {
                if (restoreSlot < 0) restoreSlot = held;
                if (!SlotSync.selectNow(mc, best)) return;
                swappedTo = best;
                SlotSync.paceAfterSwap();
            }
        }
        if (toolWorn()) {
            stop("Tool is almost broken, stopping.");
            return;
        }
        if (!attackHeld) {
            SystemInputSimulator.pressAttack();
            attackHeld = true;
        }
    }

    /** Crosshair and live ray both on {@code pos}. */
    private boolean liveOn(BlockPos pos) {
        if (!(mc.crosshairTarget instanceof BlockHitResult cross) || !cross.getBlockPos().equals(pos)) return false;
        BlockHitResult live = BlockPlacementValidator.rayFromLook(mc, false);
        return live != null && live.getBlockPos().equals(pos);
    }

    private void releaseAttack() {
        if (!attackHeld) return;
        attackHeld = false;
        SystemInputSimulator.releaseAttack();
    }

    private boolean toolWorn() {
        int min = (int) minDurability.getValue();
        if (min <= 0) return false;
        ItemStack hand = mc.player.getMainHandStack();
        return hand.isDamageable() && hand.getMaxDamage() - hand.getDamage() <= min;
    }

    private int bestToolSlot(BlockState state) {
        int held = mc.player.getInventory().getSelectedSlot();
        float bestSpeed = mc.player.getInventory().getStack(held).getMiningSpeedMultiplier(state);
        int best = held;
        int min = (int) minDurability.getValue();
        for (int i = 0; i < 9; i++) {
            ItemStack stack = mc.player.getInventory().getStack(i);
            if (stack.isEmpty()) continue;
            if (min > 0 && stack.isDamageable() && stack.getMaxDamage() - stack.getDamage() <= min) continue;
            float speed = stack.getMiningSpeedMultiplier(state);
            if (speed > bestSpeed + 0.01f) {
                bestSpeed = speed;
                best = i;
            }
        }
        return best;
    }

    // ------------------------------------------------------------------ planning

    /** Picks the next block to mine into {@link #mining}; returns a stop reason, or null. */
    private String pickNext(int column, int limit) {
        int endColumn = column + 2;
        if (limit > 0) endColumn = Math.min(endColumn, limit);
        Vec3d eye = mc.player.getEyePos();
        double reach = mc.player.getBlockInteractionRange() - 0.1;
        for (int c = column; c <= endColumn; c++) {
            if (c == column + 1 && stopAtDrop.isEnabled()) {
                BlockPos floor = cell(c, 0, -1);
                BlockState below = mc.world.getBlockState(floor);
                if (below.getCollisionShape(mc.world, floor).isEmpty()) return "No floor ahead, stopping.";
            }
            for (BlockPos pos : slice(c)) {
                BlockState state = mc.world.getBlockState(pos);
                if (state.isAir()) continue;
                FluidState fluid = state.getFluidState();
                if (!fluid.isEmpty() || (stopAtFluid.isEnabled() && fluidNextTo(pos))) {
                    if (stopAtFluid.isEnabled() || fluid.isIn(FluidTags.LAVA)) return "Lava or water ahead, stopping.";
                    continue;
                }
                // Torches, grass and the like have no hardness to speak of; they break the same way.
                if (state.getHardness(mc.world, pos) < 0.0f) return "Unbreakable block ahead, stopping.";
                if (eye.squaredDistanceTo(Vec3d.ofCenter(pos)) > reach * reach) {
                    if (c <= column + 1) return null; // walk closer first
                    continue;
                }
                mining = pos.toImmutable();
                return null;
            }
            // Nothing to mine in this slice: only the next slice is needed before walking.
            if (c >= column + 1) return null;
        }
        return null;
    }

    private boolean fluidNextTo(BlockPos pos) {
        for (Direction d : Direction.values()) {
            FluidState near = mc.world.getFluidState(pos.offset(d));
            if (!near.isEmpty()) return true;
        }
        return false;
    }

    /** The cells of slice {@code c}, top first and the middle column first. */
    private List<BlockPos> slice(int c) {
        List<BlockPos> out = new ArrayList<>();
        boolean big = size.is(SIZE_3X3);
        int height = big ? 3 : 2;
        int[] laterals = big ? new int[]{0, -1, 1} : new int[]{0};
        for (int h = height - 1; h >= 0; h--) {
            for (int l : laterals) out.add(cell(c, l, h));
        }
        return out;
    }

    private BlockPos cell(int column, int lateral, int height) {
        Direction side = axis.rotateYClockwise();
        return origin.offset(axis, column).offset(side, lateral).up(height);
    }

    private int columnOf(BlockPos pos) {
        return (pos.getX() - origin.getX()) * axis.getOffsetX() + (pos.getZ() - origin.getZ()) * axis.getOffsetZ();
    }

    // ------------------------------------------------------------------ walking

    private void walk(long now, int column) {
        releaseAttack();
        if (walkAt < 0L) walkAt = now + ModuleDelays.clampMs(walkDelay.sample());
        if (now < walkAt) {
            walking = false;
            return;
        }
        if (walkSince < 0L) {
            walkSince = now;
        } else if (now - walkSince > WALK_STALL_MS) {
            stop("Stuck: could not walk forward, stopping.");
            return;
        }
        BlockPos next = cell(column + 1, 0, 0);
        if (useBaritone.isEnabled() && BaritoneBridge.isAvailable()) {
            walking = false;
            // Baritone steers the look while it walks: give our look request up.
            if (aimRequested) {
                RotationManager.clearTarget(this);
                aimRequested = false;
                aiming = false;
            }
            if (!next.equals(baritoneGoal) || !BaritoneBridge.isOwnedBy(this)) {
                if (BaritoneBridge.goTo(this, next)) baritoneGoal = next;
            }
            return;
        }
        // Face down the tunnel, then hold forward.
        float yaw = axis.getPositiveHorizontalDegrees();
        aimRequested = true;
        aiming = SmoothAim.aimAngles(this, yaw, 15.0f, aimSpeed.getValue(), false);
        float error = Math.abs(MathHelper.wrapDegrees(mc.player.getYaw() - yaw));
        walking = aiming && error < 10.0f;
    }

    @Override
    public PlayerInput modifyMovementInput(MinecraftClient client, PlayerInput input) {
        if (!walking || mining != null || axis == null || origin == null || client.player == null
                || client.currentScreen != null) {
            return input;
        }
        // Keep to the tunnel's centre line: correct sideways drift with the strafe input.
        Direction side = axis.rotateYClockwise();
        Vec3d pos = client.player.getEntityPos();
        double offset = (pos.x - (origin.getX() + 0.5)) * side.getOffsetX() + (pos.z - (origin.getZ() + 0.5)) * side.getOffsetZ();
        boolean left = input.left();
        boolean right = input.right();
        if (!left && !right && Math.abs(offset) > 0.2) {
            // Facing down the axis, "right" moves toward +side.
            if (offset > 0) left = true; else right = true;
        }
        return new PlayerInput(true, false, left, right, input.jump(), input.sneak(), false);
    }
}
