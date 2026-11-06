package interstellarTikiTiks.apexx.client.module.implementation.bedwars;

import interstellarTikiTiks.apexx.client.event.EventBus;
import interstellarTikiTiks.apexx.client.event.impl.CombatSwapLockEvent;
import interstellarTikiTiks.apexx.client.util.PlaceSolution;
import interstellarTikiTiks.apexx.client.util.RotationManager;
import interstellarTikiTiks.apexx.client.util.combat.BlockPlacementValidator;
import interstellarTikiTiks.apexx.client.util.combat.InteractSim;
import interstellarTikiTiks.apexx.client.util.combat.SlotSync;
import interstellarTikiTiks.apexx.client.util.combat.SubTickSequencer;
import net.minecraft.client.MinecraftClient;
import net.minecraft.item.BlockItem;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

import java.util.function.Supplier;

/**
 * One module's block-placement executor, shared by the Bedwars modules.
 *
 * <p>Order per placement: the owner's rotation request steps toward the face through
 * {@link RotationManager}; nothing is clicked until the live authoritative ray actually lands on the
 * planned support block and face and resolves to the planned cell. Then, in that same tick, the
 * hotbar slot is selected through {@link SlotSync} (only when needed), and the single placement is
 * dispatched a few milliseconds later inside the tick through {@link SubTickSequencer}, re-validated
 * at dispatch. The reached angles are held for the rest of the tick ({@code setPostAttackHold}); the
 * swap back is a separate, later event through {@link SlotSync#restore} (which defers to the next
 * interval when needed) and only while this module still owns the selected slot.
 */
final class BedPlacer implements interstellarTikiTiks.apexx.client.module.hooks.ClickSimulating {

    private final Object owner;
    private final MinecraftClient mc = MinecraftClient.getInstance();

    private int originalSlot = -1;
    private int expectedSlot = -1;
    private Object boundPlayer;
    private Object boundWorld;
    private long restoreAtMs = Long.MAX_VALUE;
    private boolean dispatchPending;
    private long lastPlaceTick = Long.MIN_VALUE;
    private long nextPlaceAtMs;

    BedPlacer(Object owner) {
        this.owner = owner;
    }

    // ── rotation ───────────────────────────────────────────────────────────────────

    void aim(Supplier<Vec3d> point, double strength0to100, RotationManager.RotationMode mode,
             double randomization, boolean silent) {
        double engine = RotationManager.engineStrength(strength0to100, RotationManager.STRENGTH_SCALE_TRACK);
        RotationManager.setRotationSupplier(owner, RotationManager.Priority.HIGH, point, engine, mode,
                randomization, silent, false);
    }

    void stopAim() {
        RotationManager.stop(owner);
    }

    // ── gating ─────────────────────────────────────────────────────────────────────

    /** One placement per tick at most, after the module's own place delay, never two queued at once. */
    boolean ready() {
        return !dispatchPending && SubTickSequencer.currentTick() != lastPlaceTick
                && System.currentTimeMillis() >= nextPlaceAtMs;
    }

    /** The live ray of the current authoritative rotation fills {@code sol}'s cell through its exact support face. */
    BlockHitResult liveHitFor(PlaceSolution sol, boolean silent) {
        BlockHitResult live = BlockPlacementValidator.rayFromLook(mc, silent);
        if (live == null || !live.getBlockPos().equals(sol.against()) || live.getSide() != sol.side()) return null;
        BlockPos cell = BlockPlacementValidator.placementCell(mc, live);
        if (cell == null || !cell.equals(sol.placedAt())) return null;
        double reach = mc.player.getBlockInteractionRange();
        if (mc.player.getEyePos().squaredDistanceTo(live.getPos()) > reach * reach) return null;
        return live;
    }

    /** Another module (shield break, mace chain, cart cover, ...) owns the hotbar right now. */
    boolean otherModuleHoldsSwap() {
        CombatSwapLockEvent lock = new CombatSwapLockEvent(owner);
        EventBus.post(lock);
        return lock.isItemSwapLocked();
    }

    /** This module has swapped the hotbar and has not handed it back yet. */
    boolean holdsSwap() {
        return dispatchPending || originalSlot >= 0;
    }

    // ── placement ──────────────────────────────────────────────────────────────────

    /**
     * Places one block for {@code sol} if the live ray already reaches it. {@code hand} OFF_HAND uses the
     * offhand stack without touching the hotbar; MAIN_HAND selects {@code slot} first when needed.
     *
     * @return true when a placement was queued for dispatch this tick
     */
    boolean tryPlace(PlaceSolution sol, int slot, Hand hand, boolean silent, double swapDelayMs,
                     double placeDelayMs, int keepTicks) {
        if (mc.player == null || mc.world == null || mc.interactionManager == null || mc.currentScreen != null
                || sol == null || !ready()) {
            return false;
        }
        BlockHitResult live = liveHitFor(sol, silent);
        if (live == null) return false;

        double gap = 0.0;
        if (hand == Hand.MAIN_HAND) {
            int held = mc.player.getInventory().getSelectedSlot();
            if (held != slot) {
                if (slot < 0 || slot > 8 || otherModuleHoldsSwap()) return false;
                if (!SlotSync.select(mc, slot)) return false;
                if (originalSlot < 0) originalSlot = held;
                gap = Math.max(0.0, swapDelayMs);
            }
            expectedSlot = slot;
            boundPlayer = mc.player;
            boundWorld = mc.world;
        }

        dispatchPending = true;
        lastPlaceTick = SubTickSequencer.currentTick();
        final BlockPos against = live.getBlockPos();
        final int hold = Math.max(1, keepTicks);
        SubTickSequencer.queueAction(owner, () -> {
            try {
                if (mc.player == null || mc.world == null || mc.interactionManager == null) return;
                // Re-cast at dispatch: still the same clickable face and the same destination cell.
                BlockHitResult now = liveHitFor(sol, silent);
                if (now == null || !now.getBlockPos().equals(against)) return;
                if (hand == Hand.MAIN_HAND) {
                    if (!(mc.player.getMainHandStack().getItem() instanceof BlockItem)) return;
                    SlotSync.paceAfterSwap();
                    if (!InteractSim.interactBlockOncePerTick(mc, now, silent)) return;
                } else {
                    if (!(mc.player.getOffHandStack().getItem() instanceof BlockItem)) return;
                    ActionResult result = mc.interactionManager.interactBlock(mc.player, Hand.OFF_HAND, now);
                    if (!result.isAccepted()) return;
                    if (result == ActionResult.SUCCESS) mc.player.swingHand(Hand.OFF_HAND);
                }
                nextPlaceAtMs = System.currentTimeMillis() + (long) Math.max(0.0, placeDelayMs);
                // Keep the angles that reached the face for the rest of this tick (and any extra hold ticks).
                RotationManager.setPostAttackHold(owner, hold, RotationManager.getFinalYaw(),
                        RotationManager.getFinalPitch());
            } finally {
                dispatchPending = false;
            }
        }, gap);
        return true;
    }

    // ── swap-back ──────────────────────────────────────────────────────────────────

    /** Arms the swap back {@code delayMs} from now (only if this module swapped). */
    void scheduleRestore(double delayMs) {
        if (originalSlot < 0) return;
        restoreAtMs = System.currentTimeMillis() + (long) Math.max(0.0, delayMs);
    }

    /** Called every tick: performs a due swap back and drops ownership after a world or player change. */
    void tick() {
        // The sequencer drops work that did not run before the tick ended; never stay latched on it.
        if (dispatchPending && SubTickSequencer.currentTick() != lastPlaceTick) dispatchPending = false;
        if (mc.player == null || mc.world == null) {
            clear();
            return;
        }
        if (originalSlot >= 0 && (mc.player != boundPlayer || mc.world != boundWorld)) {
            clear();
            return;
        }
        if (originalSlot >= 0 && !dispatchPending && System.currentTimeMillis() >= restoreAtMs) {
            restoreNow();
        }
    }

    /** Swaps back now if this module still owns the selected slot; otherwise just forgets it. */
    void restoreNow() {
        if (originalSlot >= 0 && mc.player != null && mc.player == boundPlayer && mc.world == boundWorld
                && mc.player.getInventory().getSelectedSlot() == expectedSlot) {
            SlotSync.restore(mc, originalSlot);
        }
        clear();
    }

    /** Owner-scoped cleanup: cancels queued work, releases the rotation and hands the hotbar back. */
    void release() {
        SubTickSequencer.cancelOwner(owner);
        dispatchPending = false;
        RotationManager.stop(owner);
        restoreNow();
        nextPlaceAtMs = 0L;
        lastPlaceTick = Long.MIN_VALUE;
    }

    private void clear() {
        originalSlot = -1;
        expectedSlot = -1;
        boundPlayer = null;
        boundWorld = null;
        restoreAtMs = Long.MAX_VALUE;
    }
}
