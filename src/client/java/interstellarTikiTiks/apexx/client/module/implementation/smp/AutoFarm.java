package interstellarTikiTiks.apexx.client.module.implementation.smp;

import interstellarTikiTiks.apexx.client.module.BooleanSetting;
import interstellarTikiTiks.apexx.client.module.Category;
import interstellarTikiTiks.apexx.client.module.Module;
import interstellarTikiTiks.apexx.client.module.SliderSetting;
import interstellarTikiTiks.apexx.client.util.RotationManager;
import interstellarTikiTiks.apexx.client.util.SystemInputSimulator;
import interstellarTikiTiks.apexx.client.util.combat.BlockPlacementValidator;
import interstellarTikiTiks.apexx.client.util.combat.InteractSim;
import interstellarTikiTiks.apexx.client.util.combat.ModuleDelays;
import interstellarTikiTiks.apexx.client.util.combat.SlotSync;
import interstellarTikiTiks.apexx.client.util.combat.SmoothAim;
import interstellarTikiTiks.apexx.client.util.combat.TickActionOrder;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.CropBlock;
import net.minecraft.block.NetherWartBlock;
import net.minecraft.item.Item;
import net.minecraft.item.Items;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * AutoFarm: harvests mature crops within reach and replants them from your hotbar.
 *
 * <p>Every action is a vanilla one on the live look: the module turns the look (owner-scoped
 * {@link RotationManager} request through {@link SmoothAim}) onto the crop and only clicks once both the
 * crosshair and the live ray are on it, so a crop is broken with an ordinary left click. Replanting selects the
 * seed with {@link SlotSync} first, then uses it on the farmland's top face once the live ray reaches that face,
 * and the original slot is given back later through {@link TickActionOrder#deferSlot}. One action at a time,
 * each separated by its own jittered delay.</p>
 */
public final class AutoFarm extends Module implements interstellarTikiTiks.apexx.client.module.hooks.ClickSimulating {

    /** A look that has not reached its target within this long gives the spot up for a while. */
    private static final long STALL_MS = 1500L;
    private static final long BLACKLIST_MS = 5000L;
    private static final long REPLANT_MEMORY_MS = 15_000L;

    private final BooleanSetting harvest = new BooleanSetting("Harvest", true)
            .setDescription("Breaks fully grown wheat, carrots, potatoes, beetroots (and nether wart) in reach.");
    private final BooleanSetting replant = new BooleanSetting("Replant", true)
            .setDescription("Plants the matching seed back on the farmland (or soul sand) after harvesting it, from your hotbar.");
    private final BooleanSetting replantEmpty = new BooleanSetting("Replant Empty Farmland", false)
            .setDependsOn("Replant")
            .setDescription("Also plants on any empty farmland / soul sand in reach, not just the spots this module harvested.");
    private final BooleanSetting netherWart = new BooleanSetting("Nether Wart", true)
            .setDescription("Include nether wart (harvest at full age, replant on soul sand).");
    private final SliderSetting range = new SliderSetting("Range", 4.0, 2.0, 4.5, 1)
            .setDescription("Farthest a crop can be, in blocks from your eyes. Never more than your block reach.");
    private final SliderSetting aimSpeed = new SliderSetting("Aim Speed", 240, 60, 720, 0)
            .setDescription("How fast the look turns to the next crop, in degrees per second.");

    private final SliderSetting harvestDelay = new SliderSetting("Harvest Delay (ms)", 150, 0, 1000, 0).withJitter(45)
            .setSection("Delays")
            .setDescription("Wait after an action before breaking the next crop.");
    private final SliderSetting replantDelay = new SliderSetting("Replant Delay (ms)", 170, 0, 1000, 0).withJitter(50)
            .setSection("Delays")
            .setDescription("Wait after an action before planting the next seed.");
    private final SliderSetting swapBackDelay = new SliderSetting("Swap Back Delay (ms)", 200, 0, 1000, 0).withJitter(60)
            .setSection("Delays")
            .setDescription("After the last replant, how long before your previous hotbar slot is selected again. It is never in the same instant as the plant.");

    private enum Action { HARVEST, REPLANT }

    private BlockPos target;
    private Action action;
    private Vec3d aimPoint;
    private long targetSince;
    private long nextActionAt;
    private boolean aiming;
    /** An aim request of this module may be registered with the RotationManager. */
    private boolean aimRequested;
    /** Spots this module harvested, with the seed that grew there and when. */
    private final Map<BlockPos, Item> harvested = new HashMap<>();
    private final Map<BlockPos, Long> harvestedAt = new HashMap<>();
    private final Map<BlockPos, Long> blacklist = new HashMap<>();
    private int restoreSlot = -1;
    private int swappedTo = -1;
    private long restoreAt = -1L;

    public AutoFarm() {
        super("AutoFarm", "Harvests fully grown crops in reach and replants them from your hotbar: turns your look onto each crop, breaks it with a normal click, then plants the seed back on the farmland. Each step has its own delay.", Category.SMP);
        addSetting(harvest);
        addSetting(replant);
        addSetting(replantEmpty);
        addSetting(netherWart);
        addSetting(range);
        addSetting(aimSpeed);
        addSetting(harvestDelay);
        addSetting(replantDelay);
        addSetting(swapBackDelay);
    }

    @Override
    public void onDisable() {
        giveSlotBack();
        reset();
    }

    @Override
    protected void onRuntimeStateReleased() {
        reset();
    }

    private void reset() {
        dropTarget();
        releaseAim();
        harvested.clear();
        harvestedAt.clear();
        blacklist.clear();
        restoreSlot = -1;
        swappedTo = -1;
        restoreAt = -1L;
        nextActionAt = 0L;
    }

    /** Queues the slot the player held before replanting, if the module's seed slot is still selected. */
    private void giveSlotBack() {
        if (restoreSlot < 0 || mc.player == null) return;
        if (mc.player.getInventory().getSelectedSlot() == swappedTo) {
            TickActionOrder.deferSlot(restoreSlot, swappedTo);
        }
        restoreSlot = -1;
        swappedTo = -1;
        restoreAt = -1L;
    }

    @Override
    public void onTick() {
        if (mc.player == null || mc.world == null || mc.interactionManager == null) {
            reset();
            return;
        }
        long now = System.currentTimeMillis();
        expire(now);
        if (restoreSlot >= 0 && mc.player.getInventory().getSelectedSlot() != swappedTo) {
            // The player changed slot: the seed slot is not ours any more.
            restoreSlot = -1;
            swappedTo = -1;
            restoreAt = -1L;
        }
        if (mc.currentScreen != null || mc.player.isUsingItem() || mc.player.isDead()) {
            dropTarget();
            releaseAim();
            return;
        }
        if (target != null && !stillValid()) dropTarget();
        if (target == null) {
            if (now < nextActionAt) {
                // Keep the previous request alive until the next tick that picks something new.
                return;
            }
            pickTarget(now);
            if (target == null) {
                releaseAim();
                if (restoreSlot >= 0 && restoreAt >= 0L && now >= restoreAt) giveSlotBack();
                return;
            }
        }
        if (now - targetSince > STALL_MS) {
            blacklist.put(target, now);
            dropTarget();
            return;
        }
        BlockPos aimed = target;
        Vec3d point = aimPoint;
        aimRequested = true;
        aiming = SmoothAim.aimPoint(this, () -> point, aimSpeed.getValue(), false);
        if (!aiming || now < nextActionAt) return;

        if (action == Action.HARVEST) {
            if (!liveOn(aimed, null)) return;
            if (!SystemInputSimulator.tapAttack()) return;
            if (replant.isEnabled()) {
                Item seed = seedFor(mc.world.getBlockState(aimed).getBlock());
                if (seed != null) {
                    harvested.put(aimed.toImmutable(), seed);
                    harvestedAt.put(aimed.toImmutable(), now);
                }
            }
            nextActionAt = now + ModuleDelays.clampMs(harvestDelay.sample());
            dropTarget();
        } else {
            BlockPos soil = aimed.down();
            Item seed = seedForSoil(aimed);
            int slot = seed == null ? -1 : hotbarSlotOf(seed);
            if (slot < 0) {
                blacklist.put(aimed, now);
                dropTarget();
                return;
            }
            BlockHitResult live = BlockPlacementValidator.rayFromLook(mc, false);
            if (live == null || !live.getBlockPos().equals(soil) || live.getSide() != Direction.UP
                    || !liveOn(soil, Direction.UP)) {
                return;
            }
            int held = mc.player.getInventory().getSelectedSlot();
            if (held != slot) {
                if (restoreSlot < 0) restoreSlot = held;
                if (!SlotSync.selectNow(mc, slot)) return;
                swappedTo = slot;
                SlotSync.paceAfterSwap();
            }
            if (InteractSim.interactBlockOncePerTick(mc, live, false)) {
                harvested.remove(aimed);
                harvestedAt.remove(aimed);
                nextActionAt = now + ModuleDelays.clampMs(replantDelay.sample());
                if (restoreSlot >= 0) restoreAt = now + Math.max(5L, ModuleDelays.clampMs(swapBackDelay.sample()));
            } else {
                blacklist.put(aimed, now);
            }
            dropTarget();
        }
    }

    /** Crosshair and live ray both on {@code pos} (and on {@code side} when given). */
    private boolean liveOn(BlockPos pos, Direction side) {
        if (!(mc.crosshairTarget instanceof BlockHitResult cross) || !cross.getBlockPos().equals(pos)) return false;
        if (side != null && cross.getSide() != side) return false;
        BlockHitResult live = BlockPlacementValidator.rayFromLook(mc, false);
        return live != null && live.getBlockPos().equals(pos) && (side == null || live.getSide() == side);
    }

    private void pickTarget(long now) {
        Vec3d eye = mc.player.getEyePos();
        double reach = Math.min(range.getValue(), mc.player.getBlockInteractionRange() - 0.25);
        double reachSq = reach * reach;
        int r = (int) Math.ceil(reach) + 1;
        BlockPos origin = BlockPos.ofFloored(eye);
        BlockPos bestReplant = null;
        BlockPos bestHarvest = null;
        double bestReplantTurn = Double.MAX_VALUE;
        double bestHarvestTurn = Double.MAX_VALUE;
        float yaw = RotationManager.getFinalYaw();
        float pitch = RotationManager.getFinalPitch();
        boolean canReplant = replant.isEnabled();
        for (BlockPos pos : BlockPos.iterate(origin.add(-r, -r, -r), origin.add(r, r, r))) {
            if (blacklist.containsKey(pos)) continue;
            Vec3d center = Vec3d.ofCenter(pos);
            if (eye.squaredDistanceTo(center) > reachSq) continue;
            BlockState state = mc.world.getBlockState(pos);
            if (harvest.isEnabled() && isMature(state)) {
                Vec3d point = new Vec3d(pos.getX() + 0.5, pos.getY() + 0.25, pos.getZ() + 0.5);
                double turn = SmoothAim.turnTo(eye, point, yaw, pitch);
                if (turn < bestHarvestTurn) {
                    bestHarvestTurn = turn;
                    bestHarvest = pos.toImmutable();
                }
            } else if (canReplant && state.isAir() && (harvested.containsKey(pos) || replantEmpty.isEnabled())) {
                Item seed = seedForSoil(pos);
                if (seed == null || hotbarSlotOf(seed) < 0) continue;
                Vec3d point = soilPoint(pos.down());
                if (point == null) continue;
                double turn = SmoothAim.turnTo(eye, point, yaw, pitch);
                // A spot this module just emptied comes first.
                if (harvested.containsKey(pos)) turn -= 1000.0;
                if (turn < bestReplantTurn) {
                    bestReplantTurn = turn;
                    bestReplant = pos.toImmutable();
                }
            }
        }
        if (bestReplant != null) {
            Vec3d point = soilPoint(bestReplant.down());
            if (point != null) {
                setTarget(bestReplant, Action.REPLANT, point, now);
                return;
            }
        }
        if (bestHarvest != null) {
            setTarget(bestHarvest, Action.HARVEST,
                    new Vec3d(bestHarvest.getX() + 0.5, bestHarvest.getY() + 0.25, bestHarvest.getZ() + 0.5), now);
        }
    }

    private Vec3d soilPoint(BlockPos soil) {
        Vec3d point = SmoothAim.nearestFacePoint(mc, soil, Direction.UP);
        return point;
    }

    private void setTarget(BlockPos pos, Action next, Vec3d point, long now) {
        target = pos;
        action = next;
        aimPoint = point;
        targetSince = now;
    }

    private void dropTarget() {
        target = null;
        action = null;
        aimPoint = null;
    }

    private void releaseAim() {
        if (aimRequested) {
            aimRequested = false;
            aiming = false;
            RotationManager.clearTarget(this);
        }
    }

    private boolean stillValid() {
        BlockState state = mc.world.getBlockState(target);
        if (action == Action.HARVEST) return harvest.isEnabled() && isMature(state);
        return replant.isEnabled() && state.isAir() && seedForSoil(target) != null;
    }

    private void expire(long now) {
        Iterator<Map.Entry<BlockPos, Long>> it = harvestedAt.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<BlockPos, Long> e = it.next();
            if (now - e.getValue() > REPLANT_MEMORY_MS) {
                harvested.remove(e.getKey());
                it.remove();
            }
        }
        blacklist.values().removeIf(t -> now - t > BLACKLIST_MS);
    }

    private boolean isMature(BlockState state) {
        Block block = state.getBlock();
        if (block instanceof CropBlock crop) return seedFor(block) != null && crop.isMature(state);
        if (block instanceof NetherWartBlock && netherWart.isEnabled()) {
            return state.get(NetherWartBlock.AGE) >= NetherWartBlock.MAX_AGE;
        }
        return false;
    }

    private Item seedFor(Block block) {
        if (block == Blocks.WHEAT) return Items.WHEAT_SEEDS;
        if (block == Blocks.CARROTS) return Items.CARROT;
        if (block == Blocks.POTATOES) return Items.POTATO;
        if (block == Blocks.BEETROOTS) return Items.BEETROOT_SEEDS;
        if (block == Blocks.NETHER_WART && netherWart.isEnabled()) return Items.NETHER_WART;
        return null;
    }

    /** The seed to plant in the (empty) cell {@code pos}, from what grew there or what the soil takes. */
    private Item seedForSoil(BlockPos pos) {
        Block soil = mc.world.getBlockState(pos.down()).getBlock();
        Item remembered = harvested.get(pos);
        if (soil == Blocks.FARMLAND) {
            if (remembered != null && remembered != Items.NETHER_WART) return remembered;
            for (Item seed : new Item[]{Items.WHEAT_SEEDS, Items.CARROT, Items.POTATO, Items.BEETROOT_SEEDS}) {
                if (hotbarSlotOf(seed) >= 0) return seed;
            }
            return null;
        }
        if (soil == Blocks.SOUL_SAND && netherWart.isEnabled()) return Items.NETHER_WART;
        return null;
    }

    private int hotbarSlotOf(Item item) {
        int held = mc.player.getInventory().getSelectedSlot();
        if (mc.player.getInventory().getStack(held).isOf(item)) return held;
        for (int i = 0; i < 9; i++) {
            if (mc.player.getInventory().getStack(i).isOf(item)) return i;
        }
        return -1;
    }
}
