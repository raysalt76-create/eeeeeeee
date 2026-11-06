package interstellarTikiTiks.apexx.client.module.implementation.bedwars;

import interstellarTikiTiks.apexx.client.util.ClickSimController;
import interstellarTikiTiks.apexx.client.util.SystemInputSimulator;
import interstellarTikiTiks.apexx.client.util.combat.SlotSync;
import net.minecraft.client.MinecraftClient;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.ConsumableComponent;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;

/**
 * One consumption cycle for the Bedwars healing modules: select the slot (real, synced selection), use
 * it later in the same tick through the click controller, then either hold use until the item is actually
 * consumed (1.21 eating takes time) or, on servers whose plugins heal instantly, confirm the instant heal.
 * Optionally drops the empty bowl, then swaps back after a deferred delay — only if the module still owns
 * the selected slot, so a manual switch always wins.
 *
 * <p>Once started, a cycle always runs to its end (success, failure or abort); it never freezes on the item
 * slot because health recovered mid-cycle. Every method must be called on the client thread from the
 * owning module's tick, so slot ownership and Execution Mode apply.
 */
final class ConsumeRoutine {

    enum Kind {
        /** Hold use until consumed (vanilla consumables). */
        HOLD,
        /** One use press; success is an instant heal or the stack shrinking (server plugins). */
        INSTANT,
        /** Press and hold; accept either an instant heal or a normal finished consumption. */
        AUTO
    }

    enum Result { NONE, SUCCESS, FAILED }

    private enum Phase { IDLE, HOLDING, CHECK_INSTANT, DROP_PENDING, RESTORE_PENDING }

    /** A use that has not started within this long after the press is treated as refused. */
    private static final long START_GRACE_MS = 350L;
    private static final long INSTANT_WINDOW_MS = 450L;
    private static final long HOLD_MARGIN_MS = 700L;

    private final MinecraftClient mc = MinecraftClient.getInstance();
    private Phase phase = Phase.IDLE;
    private Kind kind = Kind.AUTO;
    private boolean offhand;
    private int slot = -1;
    private int previousSlot = -1;
    private Item item;
    private int countBefore;
    private float poolBefore;
    private long startMs;
    private long deadlineMs;
    private long dropAtMs;
    private long restoreAtMs;
    private long dropDelayMs;
    private long restoreDelayMs;
    private boolean dropEmpty;
    private boolean holdingKey;
    private boolean sawUse;
    private Result lastResult = Result.NONE;

    boolean active() {
        return phase != Phase.IDLE;
    }

    /** True while the item is being used (not yet in the drop/swap-back tail). */
    boolean consuming() {
        return phase == Phase.HOLDING || phase == Phase.CHECK_INSTANT;
    }

    Result takeResult() {
        Result r = lastResult;
        lastResult = Result.NONE;
        return r;
    }

    /**
     * Starts a cycle on hotbar slot {@code hotbarSlot} (0-8) or, for -1, the offhand.
     *
     * @return false when nothing was started
     */
    boolean start(int hotbarSlot, Kind kind, long dropDelayMs, long restoreDelayMs, boolean dropEmpty) {
        if (mc.player == null || mc.interactionManager == null || active() || mc.currentScreen != null) return false;
        boolean useOffhand = hotbarSlot < 0;
        ItemStack stack = useOffhand ? mc.player.getOffHandStack() : mc.player.getInventory().getStack(hotbarSlot);
        if (stack.isEmpty()) return false;
        int selected = mc.player.getInventory().getSelectedSlot();
        if (!useOffhand && selected != hotbarSlot && !SlotSync.selectNow(mc, hotbarSlot)) return false;

        this.kind = kind;
        this.offhand = useOffhand;
        this.slot = useOffhand ? selected : hotbarSlot;
        this.previousSlot = selected;
        this.item = stack.getItem();
        this.countBefore = stack.getCount();
        this.poolBefore = pool();
        this.dropDelayMs = Math.max(0L, dropDelayMs);
        this.restoreDelayMs = Math.max(0L, restoreDelayMs);
        this.dropEmpty = dropEmpty;
        this.sawUse = false;
        long now = System.currentTimeMillis();
        this.startMs = now;

        // The use follows the swap inside this tick; the click controller paces it after the slot packet.
        ClickSimController.use(mc);
        if (kind == Kind.INSTANT) {
            phase = Phase.CHECK_INSTANT;
            deadlineMs = now + INSTANT_WINDOW_MS;
        } else {
            SystemInputSimulator.pressUse();
            holdingKey = true;
            phase = Phase.HOLDING;
            deadlineMs = now + consumeTicks(stack) * 50L + HOLD_MARGIN_MS;
        }
        return true;
    }

    void tick() {
        if (phase == Phase.IDLE) return;
        if (mc.player == null) {
            abort();
            return;
        }
        long now = System.currentTimeMillis();
        switch (phase) {
            case HOLDING -> {
                if (!offhand && mc.player.getInventory().getSelectedSlot() != slot) {
                    finish(now, Result.FAILED);
                    return;
                }
                if (mc.player.isUsingItem()) sawUse = true;
                if (consumed() || (kind == Kind.AUTO && healed())) {
                    finish(now, Result.SUCCESS);
                } else if (now > deadlineMs) {
                    finish(now, Result.FAILED);
                } else if (!mc.player.isUsingItem() && (sawUse || now - startMs > START_GRACE_MS)) {
                    // The use ended (or never started) without the stack shrinking: refused or interrupted.
                    finish(now, Result.FAILED);
                }
            }
            case CHECK_INSTANT -> {
                if (consumed() || healed()) {
                    finish(now, Result.SUCCESS);
                } else if (now > deadlineMs) {
                    finish(now, Result.FAILED);
                }
            }
            case DROP_PENDING -> {
                if (now < dropAtMs) return;
                if (!offhand && mc.player.getInventory().getSelectedSlot() == slot
                        && mc.player.getMainHandStack().isOf(Items.BOWL)) {
                    SystemInputSimulator.tapDrop();
                }
                phase = Phase.RESTORE_PENDING;
                restoreAtMs = now + restoreDelayMs;
            }
            case RESTORE_PENDING -> {
                if (now < restoreAtMs) return;
                restoreSlot();
                phase = Phase.IDLE;
            }
            default -> { }
        }
    }

    /** Ends the cycle now: releases use and gives the slot back if the module still owns it. */
    void abort() {
        releaseKey();
        if (phase != Phase.IDLE) restoreSlot();
        phase = Phase.IDLE;
    }

    private void finish(long now, Result result) {
        releaseKey();
        lastResult = result;
        if (result == Result.SUCCESS && dropEmpty && !offhand) {
            phase = Phase.DROP_PENDING;
            dropAtMs = now + dropDelayMs;
        } else {
            phase = Phase.RESTORE_PENDING;
            restoreAtMs = now + restoreDelayMs;
        }
    }

    private void restoreSlot() {
        if (mc.player == null || offhand || previousSlot < 0 || previousSlot == slot) return;
        if (mc.player.getInventory().getSelectedSlot() != slot) return;
        SlotSync.restore(mc, previousSlot);
    }

    private void releaseKey() {
        if (holdingKey) SystemInputSimulator.releaseUse();
        holdingKey = false;
    }

    private boolean consumed() {
        ItemStack now = offhand ? mc.player.getOffHandStack() : mc.player.getInventory().getStack(slot);
        return now.getItem() != item || now.getCount() < countBefore;
    }

    private boolean healed() {
        return pool() > poolBefore + 0.5f;
    }

    private float pool() {
        return mc.player == null ? 0f : mc.player.getHealth() + mc.player.getAbsorptionAmount();
    }

    static int consumeTicks(ItemStack stack) {
        ConsumableComponent c = stack.get(DataComponentTypes.CONSUMABLE);
        return c == null ? 32 : Math.max(1, c.getConsumeTicks());
    }
}
