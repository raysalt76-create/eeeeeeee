package interstellarTikiTiks.apexx.client.module.implementation.automations;

import interstellarTikiTiks.apexx.client.mixin.KeyBindingAccessor;
import interstellarTikiTiks.apexx.client.module.Category;
import interstellarTikiTiks.apexx.client.module.ModeSetting;
import interstellarTikiTiks.apexx.client.module.Module;
import interstellarTikiTiks.apexx.client.util.ClickSimController;
import interstellarTikiTiks.apexx.client.util.Notifier;
import interstellarTikiTiks.apexx.client.util.SystemInputSimulator;
import interstellarTikiTiks.apexx.client.util.combat.AutomationPriority;
import interstellarTikiTiks.apexx.client.util.combat.HotbarBindCompat;
import interstellarTikiTiks.apexx.client.util.combat.SlotSync;
import interstellarTikiTiks.apexx.client.util.combat.SubTickSequencer;
import interstellarTikiTiks.apexx.client.util.combat.TickActionOrder;
import net.minecraft.item.ItemStack;

/**
 * Shared mechanics of a "hold the bind, hold the item" automation: on press, swap to the item and
 * hold the use key; on release, let go and swap back to the slot held before.
 *
 * <p><b>Order.</b> The keybind poll runs at the head of the client tick, before vanilla input
 * handling. A swap therefore goes out first through {@link SlotSync}, and the use follows later in
 * that same tick (queued through {@link SubTickSequencer}); nothing here emits a packet from a render
 * frame. The use key is held through {@link SystemInputSimulator} so vanilla keeps the item in use
 * and sends the release itself.</p>
 *
 * <p><b>Hotbar-key binds.</b> When the module's bind is also the vanilla hotbar key of the slot that
 * holds the item, the module does not select anything: vanilla selects the slot in its own input
 * handling, which runs before its use handling in the same tick, so the held use key is processed
 * against the new slot. The slot held before the press is still remembered and restored on release.</p>
 *
 * <p>If the bind is a hotbar key for a slot that does not hold the item, the pending vanilla switch is
 * dropped and the module swaps to the item itself.</p>
 *
 * <p><b>Priority.</b> Normal yields as below; High cancels and holds back every other module while the
 * session is live (see {@link AutomationPriority}).</p>
 *
 * <p><b>Ownership.</b> The swap-back only happens while the selected slot is still the one this
 * module selected. If another module (AutoMace's smash) takes the slot, the hold pauses and resumes
 * when the slot comes back; if it does not come back within a short grace the session is abandoned
 * without touching the hotbar. A session ends once per key press: after a stop (limit reached, item
 * gone, menu opened) it does not restart until the key is released and pressed again.</p>
 */
public abstract class HotbarHoldModule extends Module implements interstellarTikiTiks.apexx.client.module.hooks.ClickSimulating {
    private static final int OFF_SLOT_GRACE_TICKS = 10;
    private static final int VANILLA_SWAP_WAIT_TICKS = 2;

    protected final ModeSetting priority = AutomationPriority.createSetting();
    private final interstellarTikiTiks.apexx.client.module.SliderSetting useDelay =
            new interstellarTikiTiks.apexx.client.module.SliderSetting("Swap To Use (ms)", 25, 0, 40, 0)
                    .withJitter(5)
                    .setDescription("Wait between selecting the item slot and starting to hold use, inside the same tick. Capped at 40 ms so the use never slips into the next tick; 0 starts the hold straight after the swap.");

    private Object boundPlayer;
    private Object boundWorld;
    private boolean session;
    private boolean viaRightClick;
    private boolean holdClaimed;
    private boolean waitingForVanillaSwap;
    private int originalSlot = -1;
    /** Hotbar slot the item sits in, or -1 when the item is in the offhand and no swap is needed. */
    private int itemSlot = -1;
    private int graceTicks;
    private int offSlotTicks;

    protected HotbarHoldModule(String name, String description) {
        super(name, description, Category.AUTOMATIONS);
        addSetting(priority);
        useDelay.setSection("Delays");
        addSetting(useDelay);
    }

    /** Whether {@code stack} is an item this module should equip and use. */
    protected abstract boolean isTarget(ItemStack stack);

    /** Name used in the "nothing to equip" warning. */
    protected abstract String itemLabel();

    /** Hotbar slot to swap to, or -1 when none holds the item. Default: the first matching slot. */
    protected int findSlot() {
        for (int i = 0; i < 9; i++) {
            if (isTarget(mc.player.getInventory().getStack(i))) return i;
        }
        return -1;
    }

    /** True when an offhand item may be used instead of swapping (shield). */
    protected boolean allowOffhand() {
        return false;
    }

    /** True while another module has deliberately dropped the use key; the hold must not fight it. */
    protected boolean isHoldSuppressed() {
        return false;
    }

    /**
     * Called each live tick with the held stack. Return true to end the session (limit reached);
     * the hotbar is restored and the use key released.
     */
    protected boolean holdFinished(ItemStack held) {
        return false;
    }

    protected void onSessionStart() {
    }

    protected void onSessionEnd() {
    }

    public final boolean isHolding() {
        return session;
    }

    // ------------------------------------------------------------------ lifecycle

    @Override
    public boolean isKeyTriggered() {
        return true;
    }

    @Override
    public boolean isTriggerHold() {
        return true;
    }

    @Override
    public boolean isTriggerActive() {
        return session;
    }

    /** Bind pressed while the module is toggled on: start a session that lasts while the bind is held. */
    @Override
    protected void onTrigger() {
        startSession(false);
    }

    @Override
    public void onDisable() {
        endSession(true);
    }

    @Override
    protected void onRuntimeStateReleased() {
        endSession(true);
        AutomationPriority.lower(this);
    }

    @Override
    public void onTick() {
        tickSession();
    }

    // ------------------------------------------------------------------ session

    /** @return true when a session is running (or was already running) after the call. */
    protected final boolean startSession(boolean rightClick) {
        if (session) {
            // A bind press adopts a right-click session; the bind then owns when it ends.
            viaRightClick = viaRightClick && rightClick;
            return true;
        }
        if (mc.player == null || mc.world == null || mc.currentScreen != null) return false;

        int bindIndex = rightClick ? -1 : HotbarBindCompat.hotbarIndexOfBind(mc, this);
        boolean bindSlotHolds = bindIndex >= 0 && isTarget(mc.player.getInventory().getStack(bindIndex));

        int slot;
        boolean offhandOnly = false;
        if (bindSlotHolds) {
            slot = bindIndex;
        } else {
            slot = findSlot();
            if (slot < 0) {
                if (allowOffhand() && isTarget(mc.player.getOffHandStack())) {
                    offhandOnly = true;
                } else {
                    if (!rightClick) Notifier.warn(getName(), "No " + itemLabel() + " found in hotbar");
                    return false;
                }
            }
        }

        // High priority: cancel everything else first so its slot restores land before our swap.
        if (AutomationPriority.isHigh(priority)) AutomationPriority.raise(this);
        int current = SlotSync.current(mc);

        boundPlayer = mc.player;
        boundWorld = mc.world;
        viaRightClick = rightClick;
        offSlotTicks = 0;
        itemSlot = offhandOnly ? -1 : slot;
        originalSlot = (offhandOnly || slot == current) ? -1 : current;
        onSessionStart();

        if (bindIndex >= 0 && !bindSlotHolds) {
            // The bind is a hotbar key for a slot without the item: cancel the vanilla switch that
            // press would cause, then do our own swap.
            cancelVanillaHotbarSwitch(bindIndex);
        }
        if (!offhandOnly && slot != current) {
            if (bindSlotHolds) {
                // Vanilla performs the swap itself; hold the use key so its use handling follows it.
                session = true;
                waitingForVanillaSwap = true;
                graceTicks = VANILLA_SWAP_WAIT_TICKS;
                claimHold(false);
                return true;
            }
            if (!TickActionOrder.canSwitchSlot() || !SlotSync.select(mc, slot)) {
                clearState();
                return false;
            }
            session = true;
            SubTickSequencer.queueAction(this, this::beginHold, useDelay.sample());
            return true;
        }
        session = true;
        beginHold();
        return true;
    }

    /** Drops the pending press of a vanilla hotbar key so vanilla's input handling does not switch slots. */
    private void cancelVanillaHotbarSwitch(int index) {
        if (mc.options != null && mc.options.hotbarKeys[index] instanceof KeyBindingAccessor acc) {
            acc.setTimesPressed(0);
        }
    }

    /** First use of the session, after the swap, inside the swap's tick. */
    private void beginHold() {
        if (!session || !sameWorld() || mc.currentScreen != null) return;
        if (itemSlot >= 0 && SlotSync.current(mc) != itemSlot) return;
        claimHold(true);
    }

    private void claimHold(boolean immediateUse) {
        if (mc.options == null) return;
        // Right-click sessions ride on the physically held use button: vanilla keeps using the
        // swapped-to item by itself. Synthesizing a press/tap on top of it re-enters the use key
        // state (press edge, tap release) and makes the item drop and re-raise.
        if (viaRightClick) return;
        if (immediateUse) ClickSimController.useNow(mc);
        SystemInputSimulator.pressUse();
        if (immediateUse && mc.options.useKey instanceof KeyBindingAccessor acc) {
            // useNow already used the item this tick; drop the press edge the hold just queued.
            acc.setTimesPressed(0);
            TickActionOrder.recordUse();
        }
        holdClaimed = true;
    }

    private void releaseHold() {
        if (!holdClaimed) return;
        holdClaimed = false;
        SystemInputSimulator.releaseOwner(this);
        if (mc.options != null && mc.options.useKey != null) {
            SystemInputSimulator.restorePhysicalState(mc.options.useKey);
        }
    }

    protected final void tickSession() {
        if (!session) return;
        if (!sameWorld()) {
            clearState();
            return;
        }
        if (mc.currentScreen != null) {
            endSession(true);
            return;
        }
        if (!viaRightClick && !isBindHeld()) {
            endSession(true);
            return;
        }
        if (viaRightClick && mc.options != null
                && !SystemInputSimulator.isPhysicallyPressed(mc.options.useKey)) {
            endSession(true);
            return;
        }

        int current = SlotSync.current(mc);
        if (itemSlot >= 0 && current != itemSlot) {
            if (waitingForVanillaSwap && graceTicks-- > 0) return;
            if (waitingForVanillaSwap || ++offSlotTicks > OFF_SLOT_GRACE_TICKS) {
                // Vanilla never selected the slot, or another owner kept it: leave the hotbar alone.
                originalSlot = -1;
                endSession(false);
                return;
            }
            releaseHold();
            return;
        }
        waitingForVanillaSwap = false;
        offSlotTicks = 0;

        ItemStack held = itemSlot >= 0 ? mc.player.getMainHandStack() : mc.player.getOffHandStack();
        if (!isTarget(held) || holdFinished(held)) {
            endSession(true);
            return;
        }
        if (isHoldSuppressed()) return;
        if (viaRightClick) return;
        if (!holdClaimed || mc.options == null || !mc.options.useKey.isPressed()) claimHold(false);
    }

    /** Ends the session, releases the use key and swaps back only while this module still owns the slot. */
    protected final void endSession(boolean restore) {
        if (!session) return;
        releaseHold();
        if (restore && originalSlot >= 0 && itemSlot >= 0 && sameWorld()
                && SlotSync.current(mc) == itemSlot) {
            SlotSync.restore(mc, originalSlot);
        }
        clearState();
    }

    private void clearState() {
        boolean wasSession = session;
        session = false;
        holdClaimed = false;
        waitingForVanillaSwap = false;
        originalSlot = itemSlot = -1;
        graceTicks = offSlotTicks = 0;
        boundPlayer = boundWorld = null;
        viaRightClick = false;
        AutomationPriority.lower(this);
        if (wasSession) onSessionEnd();
    }

    private boolean sameWorld() {
        return mc.player != null && mc.player == boundPlayer && mc.world == boundWorld;
    }
}
