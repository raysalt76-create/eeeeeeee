package interstellarTikiTiks.apexx.client.module.implementation.combat;

import interstellarTikiTiks.apexx.client.module.BooleanSetting;
import interstellarTikiTiks.apexx.client.module.Category;
import interstellarTikiTiks.apexx.client.module.SubCategory;
import interstellarTikiTiks.apexx.client.module.Module;
import interstellarTikiTiks.apexx.client.module.SliderSetting;
import interstellarTikiTiks.apexx.client.util.SystemInputSimulator;
import interstellarTikiTiks.apexx.client.util.combat.ModuleDelays;
import net.minecraft.item.BlockItem;
import net.minecraft.item.ItemStack;

/**
 * Tops up a depleted hotbar stack from the main inventory before it runs out.
 *
 * <h2>Ported, not copied</h2>
 * Reimplemented from echoclient's {@code Replenish} (Mojang mappings, its own Feature/event
 * API) into Apexx's Module + Yarn architecture. Two deliberate changes from the original:
 *
 * <ul>
 *   <li>echo's version only acted while the inventory <em>screen</em> was open. This works
 *       without it: {@code clickSlot} routes through {@code currentScreenHandler}, which is the
 *       player handler (syncId 0) whenever no container is open, so the refill is silent — which
 *       is what makes it actually useful in combat.</li>
 *   <li>All movement is a quick-move ({@code QUICK_MOVE} / shift-click), the only inventory
 *       operation the game offers here; there is no hardware-click equivalent for a slot move,
 *       so this is the simclick form, the same path AutoTotem already uses.</li>
 * </ul>
 *
 * <p>This was a registered-but-missing module (in ModuleManager's list with no source, so it
 * silently failed to load); this supplies the source.
 *
 * <h2>Slot mapping</h2>
 * In the player screen handler, main-inventory inventory-indices 9..35 map to the same handler
 * slot ids, and quick-moving a matching main-inventory stack merges it into the depleted hotbar
 * stack first (vanilla shift-click behaviour), which is exactly the top-up wanted.
 */
public class Replenish extends Module {

    private final SliderSetting minPercent = new SliderSetting("Min %", 25, 0, 100, 0)
            .setDescription("A hotbar stack is topped up once it falls to this percentage of its maximum size (e.g. 25% of 64 = 16 items).");
    private final BooleanSetting singleOnly = new BooleanSetting("Also On Count 1", true)
            .setDescription("Also top up any stack that drops to a single item, whatever its percentage (useful for small stacks like pearls).");
    private final BooleanSetting requireNoScreen = new BooleanSetting("Only When Closed", false)
            .setDescription("Blatant: restrict refills to moments with no screen open, i.e. inventory clicks a vanilla player could not make without opening their inventory. Off (default) also lets refills happen while your own inventory screen is open.")
            .setBlatant(true);

    // Kept (unregistered) so configs that saved it still load; the per-module slider always applies.
    private final BooleanSetting useProfile = new BooleanSetting("Use Timing Profile", false);
    private final SliderSetting refillDelay = new SliderSetting("Refill Delay (ms)", 120, 30, 500, 0)
            .withJitter(30)
            .setDescription("Minimum gap between two refill shift-clicks. At least 30 ms so several stacks are never refilled in one frame; around 100-150 ms matches a quick human.")
            .setSection("Delays");

    private long nextAllowedMs = 0L;

    public Replenish() {
        super("Replenish", "Keeps your hotbar stocked: when a stackable hotbar item (blocks, pearls, food...) runs low, it shift-clicks a matching stack from your main inventory into it before it runs out. One refill per Refill Delay, never inside container screens where slot ids differ.", Category.PLAYER);
        setSubCategory(SubCategory.SMP);
        addSetting(minPercent);
        addSetting(singleOnly);
        addSetting(requireNoScreen);
        addSetting(refillDelay);
    }

    @Override
    public void onDisable() {
        nextAllowedMs = 0L;
    }

    @Override
    protected void onRuntimeStateReleased() {
        nextAllowedMs = 0L;
    }

    @Override
    public void onTick() {
        if (mc.player == null || mc.world == null) return;
        // Only act on the player's own inventory handler. If a chest/anvil is open, its
        // slot ids differ and a quick-move would drag that container's contents.
        if (requireNoScreen.isEnabled() && mc.currentScreen != null) return;
        if (mc.player.currentScreenHandler != mc.player.playerScreenHandler) return;

        long now = System.currentTimeMillis();
        if (now < nextAllowedMs) return;

        for (int hotbar = 0; hotbar < 9; hotbar++) {
            ItemStack stack = mc.player.getInventory().getStack(hotbar);
            if (stack.isEmpty() || !stack.isStackable()) continue;

            float pct = (stack.getCount() / (float) stack.getMaxCount()) * 100f;
            boolean low = pct <= Math.max(minPercent.getValue(), 1.0);
            boolean single = singleOnly.isEnabled() && stack.getCount() == 1;
            if (!low && !single) continue;

            if (refillFrom(stack)) {
                // One refill per gap: a human does not shift-click nine slots in one frame.
                nextAllowedMs = now + ModuleDelays.clampMs(refillDelay.sample());
                return;
            }
        }
    }

    /** Quick-moves the first matching stack from main inventory (handler slots 9..35). */
    private boolean refillFrom(ItemStack target) {
        for (int slot = 9; slot <= 35; slot++) {
            ItemStack src = mc.player.getInventory().getStack(slot);
            if (src.isEmpty()) continue;
            if (!canMerge(target, src)) continue;
            // Main-inventory inventory-index == player-handler slot id for 9..35.
            SystemInputSimulator.quickMoveSlot(slot);
            return true;
        }
        return false;
    }

    private boolean canMerge(ItemStack a, ItemStack b) {
        // Same item AND components: a differently named/enchanted stack would not merge, and the
        // shift-click would drop it into another hotbar slot instead of topping this one up.
        return ItemStack.areItemsAndComponentsEqual(a, b);
    }
}
