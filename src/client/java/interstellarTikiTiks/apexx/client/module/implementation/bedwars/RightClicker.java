package interstellarTikiTiks.apexx.client.module.implementation.bedwars;

import interstellarTikiTiks.apexx.client.mixin.MinecraftClientAccessor;
import interstellarTikiTiks.apexx.client.module.BooleanSetting;
import interstellarTikiTiks.apexx.client.module.Category;
import interstellarTikiTiks.apexx.client.module.ModeSetting;
import interstellarTikiTiks.apexx.client.module.Module;
import interstellarTikiTiks.apexx.client.module.SliderSetting;
import interstellarTikiTiks.apexx.client.module.hooks.RenderFrameAware;
import interstellarTikiTiks.apexx.client.util.ClickSimController;
import interstellarTikiTiks.apexx.client.util.SystemInputSimulator;
import interstellarTikiTiks.apexx.client.util.combat.InteractSim;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.item.BlockItem;
import net.minecraft.item.BowItem;
import net.minecraft.item.CrossbowItem;
import net.minecraft.item.FishingRodItem;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.item.consume.UseAction;

/**
 * Use-button auto-clicker for while you hold use: fast, natural-looking block placing and throwable spam.
 *
 * <p>Each click goes through the shared click controller (Execution Mode, sub-tick pacing) and waits for
 * vanilla's 4-tick item-use cooldown, so the effective rate never exceeds what holding use already allows
 * in vanilla; what it adds is a human, varied cadence instead of the perfectly regular held-use repeat.
 * Items whose use is a hold (food, drinks, bows, crossbows, shields, tridents, spears) are left alone.
 */
public class RightClicker extends Module implements RenderFrameAware, interstellarTikiTiks.apexx.client.module.hooks.ClickSimulating {

    private final SliderSetting minCps = new SliderSetting("Min CPS", 4, 1, 20, 1)
            .setDescription("Lower end of the clicks-per-second band. Vanilla's use cooldown caps real uses at 5 per second.");
    private final SliderSetting maxCps = new SliderSetting("Max CPS", 5, 1, 20, 1)
            .setDescription("Upper end of the clicks-per-second band.");
    private final ModeSetting pattern = new ModeSetting("Pattern", ClickTiming.GAUSSIAN, ClickTiming.GAUSSIAN, ClickTiming.BUTTERFLY, ClickTiming.JITTER)
            .setDescription("Gaussian: steady rate. Butterfly: short/long pairs. Jitter: wider, occasionally erratic spread.");
    private final SliderSetting dropChance = new SliderSetting("Missed Click %", 2, 0, 15, 1)
            .setDescription("Chance that a due click is skipped.");
    private final SliderSetting spikeChance = new SliderSetting("Hesitation %", 5, 0, 30, 1)
            .setDescription("Chance that an interval gets a hesitation spike (see Hesitation Length).");

    private final BooleanSetting onlyBlocks = new BooleanSetting("Only Blocks", false)
            .setDescription("Only click while holding a placeable block.");
    private final BooleanSetting allowThrowables = new BooleanSetting("Allow Throwables", true)
            .setDescription("Also click with snowballs, eggs, ender pearls, wind charges, experience bottles and splash/lingering potions.");
    private final BooleanSetting ignoreRods = new BooleanSetting("Ignore Fishing Rods", true)
            .setDescription("Never click with a fishing rod (a rapid cast-reel is useless and obvious).");
    private final BooleanSetting pauseForHeld = new BooleanSetting("Pause For Hold Items", true)
            .setDescription("Leave food, drinks, bows, crossbows, shields, tridents and spears alone so you can use them normally.");

    private final BooleanSetting ignoreCooldown = new BooleanSetting("Ignore Use Cooldown", false)
            .setBlatant(true)
            .setDescription("Clears vanilla's 4-tick use cooldown before each click so the full CPS band reaches the server. Faster than vanilla can place; strict servers flag it.");

    private final SliderSetting holdDelay = new SliderSetting("Start Delay (ms)", 85, 0, 500, 0).withJitter(20)
            .setSection("Delays")
            .setDescription("How long use must be held before the clicker joins in, so a single tap stays a single use.");
    private final SliderSetting spikeLength = new SliderSetting("Hesitation Length (ms)", 70, 10, 400, 0).withJitter(20)
            .setSection("Delays")
            .setDescription("Extra time added to an interval when a hesitation spike happens.");

    private final ClickTiming timing = new ClickTiming();
    private boolean wasHeld;
    private long nextClickMs;
    private int lastClickTick = Integer.MIN_VALUE;

    public RightClicker() {
        super("RightClicker", "Humanized use auto-clicker for while you hold use: natural, varied block placing and throwable spam that respects vanilla's use cooldown and leaves food, bows and shields alone.", Category.BEDWARS);
        addSetting(minCps);
        addSetting(maxCps);
        addSetting(pattern);
        addSetting(dropChance);
        addSetting(spikeChance);
        addSetting(onlyBlocks);
        addSetting(allowThrowables);
        addSetting(ignoreRods);
        addSetting(pauseForHeld);
        addSetting(ignoreCooldown);
        addSetting(holdDelay);
        addSetting(spikeLength);
    }

    @Override
    public void onEnable() {
        reset();
    }

    @Override
    public void onDisable() {
        reset();
    }

    @Override
    protected void onRuntimeStateReleased() {
        reset();
    }

    private void reset() {
        timing.reset();
        wasHeld = false;
        nextClickMs = 0L;
        lastClickTick = Integer.MIN_VALUE;
    }

    @Override
    public void onRenderFrame() {
        if (mc.player == null || mc.world == null || mc.options == null) return;
        long now = System.currentTimeMillis();
        boolean held = mc.currentScreen == null && SystemInputSimulator.isPhysicallyPressed(mc.options.useKey);
        if (!held) {
            if (wasHeld) reset();
            return;
        }
        if (!wasHeld) {
            wasHeld = true;
            nextClickMs = now + Math.round(holdDelay.sample());
            timing.reset();
            return;
        }
        if (now < nextClickMs || mc.player.age == lastClickTick) return;
        if (!clickableItem(mc.player.getMainHandStack())) return;
        if (mc.player.isUsingItem()) return;

        if (!ClickTiming.roll(dropChance.getValue() / 100.0)) {
            if (ignoreCooldown.isEnabled() && mc instanceof MinecraftClientAccessor accessor) {
                accessor.setItemUseCooldown(0);
            }
            if (InteractSim.useReady(mc)) {
                ClickSimController.use(mc);
                lastClickTick = mc.player.age;
            } else {
                // Cooldown still running: retry on the next frame instead of drawing a fresh interval.
                return;
            }
        }
        nextClickMs = now + timing.nextIntervalMs(now, minCps.getValue(), maxCps.getValue(),
                pattern.getCurrentMode(), 0.0, spikeChance.getValue() / 100.0, spikeLength.sample());
    }

    private boolean clickableItem(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return false;
        if (stack.getItem() instanceof BlockItem) return true;
        if (onlyBlocks.isEnabled()) return false;
        if (ignoreRods.isEnabled() && stack.getItem() instanceof FishingRodItem) return false;
        if (pauseForHeld.isEnabled() && isHoldItem(stack)) return false;
        if (isThrowable(stack)) return allowThrowables.isEnabled();
        return !isHoldItem(stack);
    }

    private static boolean isHoldItem(ItemStack stack) {
        if (stack.getItem() instanceof BowItem || stack.getItem() instanceof CrossbowItem) return true;
        if (stack.isOf(Items.SHIELD) || stack.isOf(Items.TRIDENT)) return true;
        if (stack.contains(DataComponentTypes.FOOD) || stack.contains(DataComponentTypes.CONSUMABLE)) return true;
        UseAction action = stack.getUseAction();
        return action != UseAction.NONE;
    }

    private static boolean isThrowable(ItemStack stack) {
        return stack.isOf(Items.SNOWBALL) || stack.isOf(Items.EGG) || stack.isOf(Items.ENDER_PEARL)
                || stack.isOf(Items.WIND_CHARGE) || stack.isOf(Items.EXPERIENCE_BOTTLE)
                || stack.isOf(Items.SPLASH_POTION) || stack.isOf(Items.LINGERING_POTION);
    }
}
