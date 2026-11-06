package interstellarTikiTiks.apexx.client.module.implementation.automations;

import interstellarTikiTiks.apexx.client.module.BooleanSetting;
import interstellarTikiTiks.apexx.client.module.SliderSetting;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;

/**
 * Hold the bind to swap to a golden apple and hold the use key, so you keep eating; release to swap
 * back. Also works when the bind is the apple's own hotbar key.
 *
 * <p>Plain golden apples are preferred over enchanted ones when both are in the hotbar. "Gapple Limit"
 * stops the hold and swaps back after the chosen number of apples has been eaten (counted from the
 * held stack shrinking), until the bind is released and pressed again.</p>
 */
public class AutoGapple extends HotbarHoldModule {
    private final BooleanSetting excludeEnchanted = new BooleanSetting("Exclude Enchanted", false)
            .setDescription("Never picks enchanted golden apples, so they are saved for emergencies; only plain golden apples are equipped and eaten.");
    private final BooleanSetting gappleLimit = new BooleanSetting("Gapple Limit", false)
            .setDescription("Stops eating and swaps back automatically once Max Gapples apples have been eaten, even if you keep holding the key. Press the key again to eat more.");
    private final SliderSetting maxGapples = new SliderSetting("Max Gapples", 1, 1, 4, 0)
            .setDescription("How many apples one key hold may eat before Gapple Limit stops it.")
            .setDependsOn("Gapple Limit");

    private int eaten;
    private int lastCount = -1;

    public AutoGapple() {
        super("AutoGapple", "Hold the bind to eat golden apples without managing your hotbar: it selects a golden apple (plain ones before enchanted), holds use so you keep eating, and returns to your previous item when you let go. Binding it to the apple's own hotbar key also works.");
        addSetting(excludeEnchanted);
        addSetting(gappleLimit);
        addSetting(maxGapples);
    }

    @Override
    protected boolean isTarget(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return false;
        return stack.isOf(Items.GOLDEN_APPLE) || (!excludeEnchanted.isEnabled() && stack.isOf(Items.ENCHANTED_GOLDEN_APPLE));
    }

    @Override protected String itemLabel() { return "golden apple"; }

    @Override
    protected int findSlot() {
        for (int i = 0; i < 9; i++) {
            if (mc.player.getInventory().getStack(i).isOf(Items.GOLDEN_APPLE)) return i;
        }
        for (int i = 0; i < 9; i++) {
            if (isTarget(mc.player.getInventory().getStack(i))) return i;
        }
        return -1;
    }

    @Override
    protected void onSessionStart() {
        eaten = 0;
        lastCount = -1;
    }

    @Override
    protected boolean holdFinished(ItemStack held) {
        if (!gappleLimit.isEnabled()) return false;
        int count = held.getCount();
        if (lastCount >= 0 && count < lastCount) eaten += lastCount - count;
        lastCount = count;
        return eaten >= (int) maxGapples.getValue();
    }
}
