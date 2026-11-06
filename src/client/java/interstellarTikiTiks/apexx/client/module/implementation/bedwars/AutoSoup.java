package interstellarTikiTiks.apexx.client.module.implementation.bedwars;

import interstellarTikiTiks.apexx.client.module.BooleanSetting;
import interstellarTikiTiks.apexx.client.module.Category;
import interstellarTikiTiks.apexx.client.module.ModeSetting;
import interstellarTikiTiks.apexx.client.module.Module;
import interstellarTikiTiks.apexx.client.module.SliderSetting;
import interstellarTikiTiks.apexx.client.util.SystemInputSimulator;
import net.minecraft.client.gui.screen.ingame.InventoryScreen;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.screen.slot.SlotActionType;

/**
 * Soup healing for soup-PvP servers: below a health threshold, swaps to a soup, uses it, drops the empty
 * bowl and swaps back.
 *
 * <p>Instant soup healing is a server-plugin feature; in vanilla 1.21 a stew is ordinary food. Auto mode
 * handles both: it holds use, and treats either an instant heal or a finished meal as success. Every
 * cycle runs to its end even if health recovers mid-cycle, the bowl is only dropped when the held item
 * really is a bowl (never the soup itself), and the swap-back waits until the module still owns the slot.
 * While your inventory is open it can refill empty hotbar slots with soups, re-scanning before every click.
 */
public class AutoSoup extends Module implements interstellarTikiTiks.apexx.client.module.hooks.ClickSimulating {

    private final SliderSetting health = new SliderSetting("Health Threshold", 7, 1, 19, 1)
            .setDescription("Start a soup cycle when health is at or below this.");
    private final ModeSetting mode = new ModeSetting("Server Type", "Auto", "Auto", "Instant Heal", "Vanilla Eat")
            .setDescription("Auto: works on both. Instant Heal: one use press, for soup servers that heal on click. Vanilla Eat: hold use until the stew is eaten (restores hunger, not health).");
    private final BooleanSetting rabbitStew = new BooleanSetting("Rabbit Stew", true)
            .setDescription("Also use rabbit stew. Mushroom stew is always used.");
    private final BooleanSetting beetrootSoup = new BooleanSetting("Beetroot Soup", true)
            .setDescription("Also use beetroot soup.");
    private final BooleanSetting suspiciousStew = new BooleanSetting("Suspicious Stew", false)
            .setDescription("Also use suspicious stew (its effect is unknown until eaten).");
    private final BooleanSetting dropBowls = new BooleanSetting("Drop Bowls", true)
            .setDescription("Drop the empty bowl after a soup, so the slot is free for the next one.");
    private final BooleanSetting refill = new BooleanSetting("Hotbar Refill", true)
            .setDescription("While your inventory is open, shift-click soups from it into empty hotbar slots.");

    private final SliderSetting preSwap = new SliderSetting("Reaction Delay (ms)", 40, 0, 500, 0).withJitter(15)
            .setSection("Delays")
            .setDescription("Wait after health drops below the threshold before swapping to a soup.");
    private final SliderSetting dropDelay = new SliderSetting("Drop Delay (ms)", 60, 50, 500, 0).withJitter(20)
            .setDependsOn("Drop Bowls")
            .setSection("Delays")
            .setDescription("Wait after the soup is used before dropping the bowl. At least 50 ms so the server has processed the use first.");
    private final SliderSetting swapBack = new SliderSetting("Swap Back Delay (ms)", 60, 5, 500, 0).withJitter(20)
            .setSection("Delays")
            .setDescription("Wait before returning to your previous slot.");
    private final SliderSetting cycleCooldown = new SliderSetting("Cycle Cooldown (ms)", 300, 50, 3000, 0).withJitter(80)
            .setSection("Delays")
            .setDescription("Least time between the end of one soup cycle and the start of the next.");
    private final SliderSetting invWait = new SliderSetting("Inventory Open Wait (ms)", 90, 40, 600, 0).withJitter(30)
            .setDependsOn("Hotbar Refill")
            .setSection("Delays")
            .setDescription("Wait after the inventory opens before the first refill click.");
    private final SliderSetting refillGap = new SliderSetting("Refill Click Gap (ms)", 70, 40, 400, 0).withJitter(20)
            .setDependsOn("Hotbar Refill")
            .setSection("Delays")
            .setDescription("Wait between refill clicks.");

    private final ConsumeRoutine routine = new ConsumeRoutine();
    private long startAtMs = -1L;
    private long nextCycleMs;
    private int refillSyncId = -1;
    private long nextRefillMs;

    public AutoSoup() {
        super("AutoSoup", "Soup healing for soup-PvP servers: below your health threshold it swaps to a soup, uses it, drops the bowl and swaps back, and refills the hotbar from your inventory. Works with instant-heal plugins and with vanilla eating.", Category.BEDWARS);
        addSetting(health);
        addSetting(mode);
        addSetting(rabbitStew);
        addSetting(beetrootSoup);
        addSetting(suspiciousStew);
        addSetting(dropBowls);
        addSetting(refill);
        addSetting(preSwap);
        addSetting(dropDelay);
        addSetting(swapBack);
        addSetting(cycleCooldown);
        addSetting(invWait);
        addSetting(refillGap);
    }

    @Override
    public void onDisable() {
        routine.abort();
        resetState();
    }

    @Override
    protected void onRuntimeStateReleased() {
        routine.abort();
        resetState();
    }

    private void resetState() {
        startAtMs = -1L;
        refillSyncId = -1;
    }

    @Override
    public void onTick() {
        if (mc.player == null || mc.world == null) {
            routine.abort();
            return;
        }
        long now = System.currentTimeMillis();
        handleRefill(now);
        if (routine.active()) {
            routine.tick();
            if (!routine.active()) nextCycleMs = now + Math.round(cycleCooldown.sample());
            routine.takeResult();
            return;
        }
        if (now < nextCycleMs || mc.currentScreen != null || mc.player.isUsingItem()
                || mc.player.getHealth() > health.getValue()) {
            startAtMs = -1L;
            return;
        }
        int slot = findSoupSlot();
        if (slot < 0) {
            startAtMs = -1L;
            return;
        }
        if (startAtMs < 0L) startAtMs = now + Math.round(preSwap.sample());
        if (now < startAtMs) return;
        startAtMs = -1L;
        ConsumeRoutine.Kind kind = switch (mode.getCurrentMode()) {
            case "Instant Heal" -> ConsumeRoutine.Kind.INSTANT;
            case "Vanilla Eat" -> ConsumeRoutine.Kind.HOLD;
            default -> ConsumeRoutine.Kind.AUTO;
        };
        if (!routine.start(slot, kind, Math.round(dropDelay.sample()), Math.round(swapBack.sample()), dropBowls.isEnabled())) {
            nextCycleMs = now + Math.round(cycleCooldown.sample());
        }
    }

    private int findSoupSlot() {
        int selected = mc.player.getInventory().getSelectedSlot();
        if (isSoup(mc.player.getInventory().getStack(selected))) return selected;
        for (int i = 0; i < 9; i++) {
            if (isSoup(mc.player.getInventory().getStack(i))) return i;
        }
        return -1;
    }

    private boolean isSoup(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return false;
        if (stack.isOf(Items.MUSHROOM_STEW)) return true;
        if (stack.isOf(Items.RABBIT_STEW)) return rabbitStew.isEnabled();
        if (stack.isOf(Items.BEETROOT_SOUP)) return beetrootSoup.isEnabled();
        if (stack.isOf(Items.SUSPICIOUS_STEW)) return suspiciousStew.isEnabled();
        return false;
    }

    /** Shift-clicks soups from the open player inventory into empty hotbar slots, re-scanning before each click. */
    private void handleRefill(long now) {
        if (!refill.isEnabled() || !(mc.currentScreen instanceof InventoryScreen)
                || mc.player.currentScreenHandler == null) {
            refillSyncId = -1;
            return;
        }
        int syncId = mc.player.currentScreenHandler.syncId;
        if (refillSyncId != syncId) {
            refillSyncId = syncId;
            nextRefillMs = now + Math.round(invWait.sample());
            return;
        }
        if (now < nextRefillMs) return;
        boolean emptyHotbar = false;
        for (int i = 0; i < 9 && !emptyHotbar; i++) {
            emptyHotbar = mc.player.getInventory().getStack(i).isEmpty();
        }
        if (!emptyHotbar) return;
        for (int i = 9; i < 36; i++) {
            if (isSoup(mc.player.getInventory().getStack(i))) {
                // Player screen handler: main inventory slots 9-35 keep the same index.
                SystemInputSimulator.clickSlotBound(syncId, i, 0, SlotActionType.QUICK_MOVE);
                nextRefillMs = now + Math.round(refillGap.sample());
                return;
            }
        }
    }
}
