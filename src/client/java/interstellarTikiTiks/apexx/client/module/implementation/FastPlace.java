package interstellarTikiTiks.apexx.client.module.implementation;

import interstellarTikiTiks.apexx.client.mixin.MinecraftClientAccessor;
import interstellarTikiTiks.apexx.client.module.BooleanSetting;
import interstellarTikiTiks.apexx.client.module.Category;
import interstellarTikiTiks.apexx.client.module.Module;
import interstellarTikiTiks.apexx.client.module.SliderSetting;
import interstellarTikiTiks.apexx.client.util.combat.ModuleDelays;
import net.minecraft.item.BlockItem;

/**
 * Faster block placement — the place-side twin of the existing NoBreakDelay.
 *
 * <h2>Ported without echo's mixin</h2>
 * echoclient's FastPlace is only a settings shell; its behaviour lives in a mixin that rewrites
 * MinecraftClient's item-use cooldown. Apexx already exposes that field through
 * {@link MinecraftClientAccessor#setItemUseCooldown}, so this reproduces the effect from a plain
 * module tick with no new mixin and no packets: while the use key is held it clamps the item-use
 * cooldown down to the configured value, so held right-click places at the chosen rate instead of
 * vanilla's fixed 4-tick throttle.
 *
 * <p>echo's per-block {@code RegistryPickerSetting} filter is dropped — Apexx has no registry-picker
 * setting type — in favour of a simple "blocks only" toggle, which covers the real use (spamming a
 * block) without a whole new setting widget.
 */
public class FastPlace extends Module {

    private final SliderSetting extraDelay = new SliderSetting("Extra Delay (ms)", 30, 0, 200, 0)
            .withJitter(10)
            .setDescription("Extra wait added on top of Delay before each placement, drawn around this value with the StDev below, so placements are not a fixed tick rhythm. 0 = exactly Delay.");
    private final SliderSetting delay = new SliderSetting("Delay (ticks)", 2, 0, 4, 0)
            .setDescription("Ticks between placements while right click is held (vanilla is 4). Lower places faster, but 0-1 is far beyond what servers expect from a human.");
    private final BooleanSetting blocksOnly = new BooleanSetting("Blocks Only", true)
            .setDescription("Only speeds up placing blocks; other right-click items (food, potions, pearls) keep vanilla timing.");

    /** Ticks remaining before the next placement is allowed; re-sampled (with jitter) after each. */
    private int ticksUntilNextPlace = 0;

    public FastPlace() {
        // BLATANT: shortens vanilla's fixed 4-tick item-use throttle. Rather than placing at a flat
        // tick rate, the interval is drawn each cycle as (base + TimingProfile jitter) so the cadence
        // varies and stays within what a fast human right-click can produce.
        super("FastPlace", "Places blocks faster than vanilla while you hold right click by shortening the 4-tick use cooldown to Delay plus a varied Extra Delay. Great for bridging and building; keep Delay at 2 or more on servers that check place rates.", Category.BLATANT);
        delay.setSection("Delays");
        extraDelay.setSection("Delays");
        addSetting(delay);
        addSetting(extraDelay);
        addSetting(blocksOnly);
    }

    @Override
    public void onDisable() {
        ticksUntilNextPlace = 0;
    }

    @Override
    public void onTick() {
        if (mc.player == null || mc.options == null || mc.currentScreen != null) {
            ticksUntilNextPlace = 0;
            return;
        }
        if (!mc.options.useKey.isPressed()) {
            ticksUntilNextPlace = 0;
            return;
        }
        if (blocksOnly.isEnabled() && !(mc.player.getMainHandStack().getItem() instanceof BlockItem)) {
            return;
        }

        MinecraftClientAccessor acc = (MinecraftClientAccessor) mc;
        if (ticksUntilNextPlace > 0) {
            // Mid-interval: hold vanilla's use-cooldown positive so no placement happens this tick.
            ticksUntilNextPlace--;
            acc.setItemUseCooldown(Math.max(1, ticksUntilNextPlace));
            return;
        }

        // Interval elapsed: allow one placement this tick, then draw the next (varied) interval.
        acc.setItemUseCooldown(0);
        ticksUntilNextPlace = (int) delay.getValue() + ModuleDelays.msToTicks(extraDelay.sample());
    }
}
