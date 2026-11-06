package interstellarTikiTiks.apexx.client.module.implementation.automations;

import interstellarTikiTiks.apexx.client.mixin.KeyBindingAccessor;
import interstellarTikiTiks.apexx.client.module.BooleanSetting;
import interstellarTikiTiks.apexx.client.module.Category;
import interstellarTikiTiks.apexx.client.module.ModeSetting;
import interstellarTikiTiks.apexx.client.module.Module;
import interstellarTikiTiks.apexx.client.util.ClickSimController;
import interstellarTikiTiks.apexx.client.util.Notifier;
import interstellarTikiTiks.apexx.client.util.combat.AutomationPriority;
import interstellarTikiTiks.apexx.client.util.combat.HotbarBindCompat;
import interstellarTikiTiks.apexx.client.util.combat.InteractSim;
import interstellarTikiTiks.apexx.client.util.combat.SlotSync;
import interstellarTikiTiks.apexx.client.util.combat.SubTickSequencer;
import interstellarTikiTiks.apexx.client.util.combat.TickActionOrder;
import interstellarTikiTiks.apexx.client.module.SliderSetting;
import net.minecraft.item.Item;

/**
 * Shared mechanics of a tap automation: on press, swap to the item, use it once, swap back.
 *
 * <p><b>Order.</b> The bind poll runs at the head of the tick. The swap goes out first through
 * {@link SlotSync}; the use follows later in the same tick (queued through {@link SubTickSequencer},
 * so it runs inline when the action window is open). The swap-back is a later step, never the same
 * call as the use.</p>
 *
 * <p><b>Hotbar-key binds.</b> When the bind is also the vanilla hotbar key of the slot holding the
 * item, nothing is selected here: vanilla selects the slot, then its use handling processes the
 * press edge queued by {@link ClickSimController#pulseUseNow}, in the same tick and in that order.
 * The swap-back runs from the next tick, after the use, and only while the slot is still the item
 * slot.</p>
 */
public abstract class HotbarOneShotModule extends Module implements interstellarTikiTiks.apexx.client.module.hooks.ClickSimulating {
    protected final ModeSetting priority = AutomationPriority.createSetting();
    private final SliderSetting useDelay = new SliderSetting("Swap To Use (ms)", 25, 0, 40, 0)
            .withJitter(5)
            .setDescription("Wait between selecting the item slot and using it, inside the same tick. Capped at 40 ms so the use never slips into the next tick; 0 uses it straight after the swap.");
    private final SliderSetting swapBackDelay = new SliderSetting("Swap Back Delay (ms)", 60, 5, 500, 0)
            .withJitter(15)
            .setDescription("Wait after the use before returning to the slot you were holding. Never below 5 ms, so the swap, use and swap-back never land as one burst.");

    private int originalSlot = -1;
    private int expectedSlot = -1;
    private Object boundPlayer;
    private Object boundWorld;
    private long restoreAt;
    private int startAge;
    private boolean vanillaSelects;

    protected HotbarOneShotModule(String name, String description) {
        super(name, description, Category.AUTOMATIONS);
        addSetting(priority);
        addSetting(new BooleanSetting("Hold to Repeat", false)
                .setDescription("Off: each key press runs exactly one swap, use and swap-back. On: keeps repeating that sequence for as long as the key is held."));
        useDelay.setSection("Delays");
        swapBackDelay.setSection("Delays");
        addSetting(useDelay);
        addSetting(swapBackDelay);
    }

    protected abstract Item item();

    protected abstract String itemLabel();

    /** Runs once a slot has been chosen, before the swap. */
    protected void beforeSwap() {
    }

    /** Runs after the use was dispatched (not necessarily accepted by the server). */
    protected void afterUse() {
    }

    /** A module can refuse a press before anything is selected (e.g. the pearl limit). */
    protected boolean canTrigger() {
        return true;
    }

    /** The module's toggle is a master switch; the bind only fires the sequence while it is on. */
    @Override
    public boolean isKeyTriggered() {
        return true;
    }

    @Override
    public boolean isTriggerActive() {
        return boundPlayer != null;
    }

    /** A press never cancels or restarts a sequence that is still in flight. */
    @Override
    protected void onTrigger() {
        if (boundPlayer != null || !canTrigger()) return;
        originalSlot = expectedSlot = -1;
        restoreAt = 0L;
        vanillaSelects = false;
        if (mc.player == null || mc.world == null || mc.currentScreen != null || !InteractSim.useReady(mc)) {
            return;
        }
        int current = SlotSync.current(mc);
        int bindIndex = HotbarBindCompat.hotbarIndexOfBind(mc, this);
        int slot = -1;
        if (bindIndex >= 0 && mc.player.getInventory().getStack(bindIndex).isOf(item())) {
            slot = bindIndex;
        } else {
            for (int i = 0; i < 9; i++) {
                if (mc.player.getInventory().getStack(i).isOf(item())) { slot = i; break; }
            }
        }
        if (slot < 0 || mc.player.getItemCooldownManager().isCoolingDown(item().getDefaultStack())) {
            if (slot < 0) Notifier.warn(getName(), "No " + itemLabel() + " found in hotbar");
            return;
        }
        // High priority: cancel everything else first so its slot restores land before our swap.
        if (AutomationPriority.isHigh(priority)) AutomationPriority.raise(this);
        current = SlotSync.current(mc);
        boundPlayer = mc.player;
        boundWorld = mc.world;
        originalSlot = slot == current ? -1 : current;
        expectedSlot = slot;
        startAge = mc.player.age;
        beforeSwap();

        if (bindIndex >= 0 && slot != bindIndex && mc.options.hotbarKeys[bindIndex] instanceof KeyBindingAccessor acc) {
            // The bind is a hotbar key for a slot without the item: drop the vanilla switch it would cause.
            acc.setTimesPressed(0);
        }
        if (slot == bindIndex && slot != current) {
            // Vanilla selects the slot in its input handling; the press edge is processed right after.
            vanillaSelects = true;
            ClickSimController.pulseUseNow(mc);
            TickActionOrder.recordUse();
            afterUse();
            return;
        }
        if (slot != current && (!TickActionOrder.canSwitchSlot() || !SlotSync.select(mc, slot))) {
            releaseRuntimeState();
            return;
        }
        SubTickSequencer.queueAction(this, () -> {
            if (!InteractSim.useReady(mc) || mc.currentScreen != null
                    || !mc.player.getMainHandStack().isOf(item())) return;
            ClickSimController.useNow(mc);
            TickActionOrder.recordUse();
            afterUse();
            restoreAt = System.currentTimeMillis() + Math.max(5L, Math.round(swapBackDelay.sample()));
        }, useDelay.sample());
    }

    @Override
    public void onTick() {
        if (boundPlayer == null) return;
        if (mc.player != boundPlayer || mc.world != boundWorld) {
            releaseRuntimeState();
        } else if (vanillaSelects) {
            // Vanilla's input handling already ran for the press tick: swap back from the next tick.
            if (mc.player.age > startAge) releaseRuntimeState();
        } else if (restoreAt == 0L) {
            // The queued use has not run; give it the rest of this tick and the next, then give up.
            if (mc.player.age - startAge >= 2) releaseRuntimeState();
        } else if (System.currentTimeMillis() >= restoreAt) {
            releaseRuntimeState();
        }
    }

    @Override
    public void onDisable() {
        releaseRuntimeState();
    }

    /** Runs when a sequence ends, normally or cancelled. */
    protected void onSequenceEnd() {
    }

    @Override
    protected void onRuntimeStateReleased() {
        if (mc.player != null && mc.player == boundPlayer && mc.world == boundWorld
                && SlotSync.current(mc) == expectedSlot && originalSlot >= 0) SlotSync.restore(mc, originalSlot);
        boolean wasInFlight = boundPlayer != null;
        originalSlot = expectedSlot = -1;
        boundPlayer = boundWorld = null;
        restoreAt = 0L;
        vanillaSelects = false;
        AutomationPriority.lower(this);
        if (wasInFlight) onSequenceEnd();
    }
}
