package interstellarTikiTiks.apexx.client.module.implementation.player.autobuild;

import interstellarTikiTiks.apexx.client.module.implementation.support.BaritoneBridge;
import interstellarTikiTiks.apexx.client.util.ClickSimController;
import interstellarTikiTiks.apexx.client.util.PlaceResolver;
import interstellarTikiTiks.apexx.client.util.RotationManager;
import interstellarTikiTiks.apexx.client.util.SystemInputSimulator;
import interstellarTikiTiks.apexx.client.util.combat.BlockPlacementValidator;
import interstellarTikiTiks.apexx.client.util.combat.InteractSim;
import net.minecraft.block.entity.BarrelBlockEntity;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.entity.ChestBlockEntity;
import net.minecraft.block.entity.ShulkerBoxBlockEntity;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.screen.GenericContainerScreenHandler;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.ShulkerBoxScreenHandler;
import net.minecraft.screen.slot.Slot;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.ChunkStatus;
import net.minecraft.world.chunk.WorldChunk;

import java.util.HashMap;
import java.util.Map;

/**
 * Restocks building materials from nearby chests, barrels and shulker boxes, the way a player would:
 * walk up (optionally through Baritone), look at the container, right-click it, shift-click the
 * needed stacks one at a time, then close it. The open click is gated on the live ray reaching the
 * container; every inventory click is bound to the container's {@code syncId}; all waits come from
 * the owning module's own delay sliders.
 *
 * <p>Owner-scoped: rotation requests and paths use the owner object. {@link #abort} never calls
 * {@code RotationManager.stop} (the owner releases its own rotation once, in its own cleanup), and it
 * only closes a screen this controller opened. Client thread only.
 */
public final class RestockController {

    /** What the owning module supplies. */
    public interface Host {
        Object owner();

        boolean silentAim();

        double rotationStrength();

        double reach();

        double range();

        /** Items still needed beyond what the player carries (count &gt; 0 only). Computed live. */
        Map<Item, Integer> shortfall();

        /** Whether a block of this item is used by the build (for moving it to the hotbar). */
        boolean isMaterial(Item item);

        long openDelayMs();

        long clickDelayMs();

        long closeDelayMs();

        long pathDelayMs();

        boolean walkAllowed();

        /** Runs a click this module makes itself, so its own item-use hooks let it through. */
        void runSelfUse(Runnable click);

        /** Releases the owner's rotation (called once the container is open). */
        void releaseAim();

        void notice(String key, String message);
    }

    private enum State { IDLE, APPROACH, AIM, WAIT_OPEN, LOOT, CLOSING }

    private static final long APPROACH_TIMEOUT_MS = 30_000L;
    private static final long AIM_TIMEOUT_MS = 3_000L;
    private static final long OPEN_TIMEOUT_MS = 1_500L;
    private static final long LOOT_TIMEOUT_MS = 20_000L;
    private static final long SKIP_MS = 60_000L;
    private static final long SEARCH_COOLDOWN_MS = 2_000L;
    private static final int MAX_CLICKS = 64;

    private final Host host;
    private State state = State.IDLE;
    private BlockPos target;
    private Vec3d aimPoint;
    private long stateSince;
    private long nextActionMs;
    private long nextPathMs;
    private long nextSearchMs;
    private int syncId = -1;
    private int clicks;
    private boolean tookAnything;
    private ClientPlayerEntity player;
    private ClientWorld world;
    private final Map<BlockPos, Long> skip = new HashMap<>();
    private final Map<Integer, Integer> blockedSlots = new HashMap<>();

    public RestockController(Host host) {
        this.host = host;
    }

    public boolean isActive() {
        return state != State.IDLE;
    }

    /** True while a container this controller opened is (or may be about to be) on screen. */
    public boolean ownsScreen() {
        return state == State.WAIT_OPEN || state == State.LOOT || state == State.CLOSING;
    }

    /** Short state text for the overlay, or null when idle. */
    public String describe() {
        return switch (state) {
            case IDLE -> null;
            case APPROACH -> "Restock: walking to " + target.toShortString();
            case AIM, WAIT_OPEN -> "Restock: opening " + target.toShortString();
            case LOOT -> "Restock: taking materials";
            case CLOSING -> "Restock: closing";
        };
    }

    /**
     * Looks for the nearest container in range that has not been tried recently and starts on it.
     * @return true when a restock run started.
     */
    public boolean begin(MinecraftClient mc) {
        if (state != State.IDLE || mc.player == null || mc.world == null) return false;
        long now = now();
        if (now < nextSearchMs) return false;
        nextSearchMs = now + SEARCH_COOLDOWN_MS;
        if (host.shortfall().isEmpty()) return false;
        skip.values().removeIf(until -> until <= now);
        BlockPos found = nearestContainer(mc);
        if (found == null) {
            host.notice("restock-none", "Out of materials and no unchecked chest, barrel or shulker box nearby.");
            return false;
        }
        player = mc.player;
        world = mc.world;
        target = found;
        enter(State.APPROACH);
        return true;
    }

    /** Advances the run. Call every client tick while {@link #isActive()}. */
    public void tick(MinecraftClient mc) {
        if (state == State.IDLE) return;
        if (mc.player == null || mc.world == null || mc.player != player || mc.world != world) {
            reset(); // the world is gone; nothing of ours is left to close
            return;
        }
        if (mc.player.hurtTime > 0) {
            host.notice("restock-hurt", "Restock stopped: you took damage.");
            abort(mc);
            return;
        }
        long now = now();
        switch (state) {
            case APPROACH -> approach(mc, now);
            case AIM -> aim(mc, now);
            case WAIT_OPEN -> waitOpen(mc, now);
            case LOOT -> loot(mc, now);
            case CLOSING -> closing(mc, now);
            default -> { }
        }
    }

    private void approach(MinecraftClient mc, long now) {
        if (mc.currentScreen != null) {
            abort(mc);
            return;
        }
        if (!isContainer(mc, target)) {
            skipTarget();
            abort(mc);
            return;
        }
        if (now - stateSince > APPROACH_TIMEOUT_MS) {
            host.notice("restock-far", "Could not reach the container at " + target.toShortString() + ".");
            skipTarget();
            abort(mc);
            return;
        }
        BlockHitResult face = PlaceResolver.resolveExistingBlockHit(mc, target, host.reach());
        if (face != null) {
            BaritoneBridge.cancel(host.owner());
            aimPoint = face.getPos();
            enter(State.AIM);
            return;
        }
        if (!host.walkAllowed() || !BaritoneBridge.isAvailable()) {
            host.notice("restock-reach", "Walk within reach of the container at " + target.toShortString()
                    + (BaritoneBridge.isAvailable() ? " (or enable Walk To Blocks)." : "."));
            skipTarget();
            abort(mc);
            return;
        }
        if (now >= nextPathMs && !BaritoneBridge.isOwnedBy(host.owner())) {
            BaritoneBridge.goNear(host.owner(), target, 2);
            nextPathMs = now + Math.max(50L, host.pathDelayMs());
        }
    }

    private void aim(MinecraftClient mc, long now) {
        if (mc.currentScreen != null) {
            abort(mc);
            return;
        }
        if (!isContainer(mc, target) || now - stateSince > AIM_TIMEOUT_MS) {
            skipTarget();
            abort(mc);
            return;
        }
        boolean silent = host.silentAim();
        Vec3d point = aimPoint;
        RotationManager.aimAt(host.owner(), RotationManager.Priority.HIGH, () -> point, host.rotationStrength(), 0.0, silent);
        if (RotationManager.getCurrentOwner() != host.owner()) return;
        if (mc.player.isSneaking() || mc.player.isUsingItem() || !InteractSim.useReady(mc)) return;
        if (BlockPlacementValidator.hitForInteraction(mc, target, silent) == null) return;
        HitResult click = RotationManager.isSilentRotationActive()
                ? RotationManager.getSilentCrosshairTarget() : mc.crosshairTarget;
        if (!(click instanceof BlockHitResult hit) || hit.getType() != HitResult.Type.BLOCK
                || !hit.getBlockPos().equals(target)) return;
        host.runSelfUse(() -> ClickSimController.useNow(mc));
        RotationManager.setPostAttackHold(host.owner(), 1, RotationManager.getFinalYaw(), RotationManager.getFinalPitch());
        enter(State.WAIT_OPEN);
    }

    private void waitOpen(MinecraftClient mc, long now) {
        ScreenHandler handler = mc.player.currentScreenHandler;
        if (handler != null && handler != mc.player.playerScreenHandler && containerSlots(handler) > 0) {
            syncId = handler.syncId;
            clicks = 0;
            tookAnything = false;
            blockedSlots.clear();
            host.releaseAim();
            nextActionMs = now + Math.max(0L, host.openDelayMs());
            enter(State.LOOT);
            return;
        }
        if (now - stateSince > OPEN_TIMEOUT_MS) {
            skipTarget(); // blocked lid, protected container, or it is not ours to open
            abort(mc);
        }
    }

    private void loot(MinecraftClient mc, long now) {
        ScreenHandler handler = mc.player.currentScreenHandler;
        if (handler == null || handler.syncId != syncId || mc.currentScreen == null) {
            finishRun(); // the player (or the server) closed it
            return;
        }
        if (now - stateSince > LOOT_TIMEOUT_MS) {
            startClosing(now);
            return;
        }
        if (now < nextActionMs) return;
        if (!handler.getCursorStack().isEmpty() || clicks >= MAX_CLICKS) {
            startClosing(now);
            return;
        }
        int containerSlots = containerSlots(handler);
        Map<Item, Integer> needed = host.shortfall();

        // 1. Shift-click the next stack the build still needs.
        // A full inventory leaves the slot unchanged; two such clicks mark the slot blocked.
        if (!needed.isEmpty()) {
            for (int i = 0; i < containerSlots && i < handler.slots.size(); i++) {
                ItemStack stack = handler.getSlot(i).getStack();
                if (stack.isEmpty() || !needed.containsKey(stack.getItem())) continue;
                if (blockedSlots.getOrDefault(i, 0) >= 2) continue;
                ItemStack before = stack.copy();
                boolean ran = SystemInputSimulator.clickSlotBound(syncId, i, 0, SlotActionType.QUICK_MOVE);
                clicks++;
                nextActionMs = now + Math.max(0L, host.clickDelayMs());
                if (!ran) {
                    finishRun();
                    return;
                }
                if (ItemStack.areEqual(before, handler.getSlot(i).getStack())) blockedSlots.merge(i, 1, Integer::sum);
                else tookAnything = true;
                return;
            }
        }

        // 2. A needed material sitting in the main inventory with an empty hotbar slot free: swap it down.
        int empty = emptyHotbarSlot(mc);
        if (empty >= 0) {
            for (Slot slot : handler.slots) {
                if (slot.inventory != mc.player.getInventory()) continue;
                int index = slot.getIndex();
                if (index < 9 || index >= 36) continue;
                ItemStack stack = slot.getStack();
                if (stack.isEmpty() || !host.isMaterial(stack.getItem()) || inHotbar(mc, stack.getItem())) continue;
                boolean ran = SystemInputSimulator.clickSlotBound(syncId, slot.id, empty, SlotActionType.SWAP);
                clicks++;
                nextActionMs = now + Math.max(0L, host.clickDelayMs());
                if (!ran) finishRun();
                return;
            }
        }

        // 3. Nothing more to take here.
        if (!tookAnything) skipTarget();
        else markDoubleChest(mc);
        startClosing(now);
    }

    private void closing(MinecraftClient mc, long now) {
        ScreenHandler handler = mc.player.currentScreenHandler;
        if (handler == null || handler.syncId != syncId || mc.currentScreen == null) {
            finishRun();
            return;
        }
        if (now < nextActionMs) return;
        mc.player.closeHandledScreen();
        finishRun();
    }

    /**
     * Stops the run: cancels our path and closes the container only if this controller opened it and
     * it is still the open one. Never touches rotation (the owner releases that itself). Idempotent and
     * safe without a player.
     */
    public void abort(MinecraftClient mc) {
        try {
            if (ownsScreen() && mc != null && mc.player != null && mc.player == player && syncId >= 0
                    && mc.player.currentScreenHandler != null && mc.player.currentScreenHandler.syncId == syncId
                    && mc.player.currentScreenHandler != mc.player.playerScreenHandler) {
                mc.player.closeHandledScreen();
            }
        } catch (Throwable ignored) {
            // closing is best effort
        }
        reset();
    }

    /** Forgets everything (world change). */
    public void clearMemory() {
        skip.clear();
        nextSearchMs = 0L;
    }

    private void reset() {
        BaritoneBridge.cancel(host.owner());
        state = State.IDLE;
        target = null;
        aimPoint = null;
        syncId = -1;
        clicks = 0;
        tookAnything = false;
        blockedSlots.clear();
        player = null;
        world = null;
    }

    private void finishRun() {
        nextSearchMs = now() + SEARCH_COOLDOWN_MS;
        reset();
    }

    private void startClosing(long now) {
        nextActionMs = now + Math.max(0L, host.closeDelayMs());
        enter(State.CLOSING);
    }

    private void enter(State next) {
        state = next;
        stateSince = now();
    }

    private void skipTarget() {
        if (target != null) skip.put(target, now() + SKIP_MS);
    }

    /** A looted double chest's other half shares the inventory: skip it too for a while. */
    private void markDoubleChest(MinecraftClient mc) {
        if (target == null || mc.world == null) return;
        for (Direction d : Direction.Type.HORIZONTAL) {
            BlockPos n = target.offset(d);
            if (mc.world.getBlockEntity(n) instanceof ChestBlockEntity) skip.put(n, now() + SKIP_MS);
        }
    }

    private BlockPos nearestContainer(MinecraftClient mc) {
        double range = Math.max(1.0, host.range());
        Vec3d eye = mc.player.getEyePos();
        int cx0 = (int) Math.floor((eye.x - range) / 16.0);
        int cx1 = (int) Math.floor((eye.x + range) / 16.0);
        int cz0 = (int) Math.floor((eye.z - range) / 16.0);
        int cz1 = (int) Math.floor((eye.z + range) / 16.0);
        BlockPos best = null;
        double bestSq = range * range;
        for (int cx = cx0; cx <= cx1; cx++) {
            for (int cz = cz0; cz <= cz1; cz++) {
                Chunk chunk = mc.world.getChunkManager().getChunk(cx, cz, ChunkStatus.FULL, false);
                if (!(chunk instanceof WorldChunk wc)) continue;
                for (BlockEntity be : wc.getBlockEntities().values()) {
                    if (!isContainerEntity(be)) continue;
                    BlockPos pos = be.getPos();
                    if (skip.containsKey(pos)) continue;
                    double sq = eye.squaredDistanceTo(Vec3d.ofCenter(pos));
                    if (sq < bestSq) {
                        bestSq = sq;
                        best = pos.toImmutable();
                    }
                }
            }
        }
        return best;
    }

    private static boolean isContainer(MinecraftClient mc, BlockPos pos) {
        return pos != null && isContainerEntity(mc.world.getBlockEntity(pos));
    }

    private static boolean isContainerEntity(BlockEntity be) {
        return be instanceof ChestBlockEntity || be instanceof BarrelBlockEntity || be instanceof ShulkerBoxBlockEntity;
    }

    private static int containerSlots(ScreenHandler handler) {
        if (handler instanceof GenericContainerScreenHandler generic) return generic.getRows() * 9;
        if (handler instanceof ShulkerBoxScreenHandler) return 27;
        return 0;
    }

    private static int emptyHotbarSlot(MinecraftClient mc) {
        var inv = mc.player.getInventory();
        for (int i = 0; i < 9; i++) {
            if (inv.getStack(i).isEmpty()) return i;
        }
        return -1;
    }

    private static boolean inHotbar(MinecraftClient mc, Item item) {
        var inv = mc.player.getInventory();
        for (int i = 0; i < 9; i++) {
            if (inv.getStack(i).isOf(item)) return true;
        }
        return false;
    }

    private static long now() {
        return System.currentTimeMillis();
    }
}
