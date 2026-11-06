package interstellarTikiTiks.apexx.client.module.implementation.automations;

import interstellarTikiTiks.apexx.client.util.combat.SubTickSequencer;
import net.minecraft.item.Item;
import net.minecraft.item.Items;

/** One-tap pearl throw: swaps to the pearl, throws, swaps back. */
public class AutoPearl extends HotbarOneShotModule {
    private static volatile long lastPearlThrownTime;
    private static volatile long lastPearlFinishTime;

    public AutoPearl() {
        super("AutoPearl", "Tap the bind to throw an ender pearl without changing your held item: it selects the pearl in your hotbar, throws it inside the same tick, then returns to your previous slot after the Swap Back Delay. Respects the pearl cooldown and the pearl limit. Binding it to the pearl's own hotbar key also works.");
    }

    public static long getLastPearlThrownTime() { return lastPearlThrownTime; }
    public static long getLastPearlFinishTime() { return lastPearlFinishTime; }

    @Override protected Item item() { return Items.ENDER_PEARL; }
    @Override protected String itemLabel() { return "ender pearl"; }
    @Override protected boolean canTrigger() {
        return interstellarTikiTiks.apexx.client.util.combat.PearlLimit.canThrow(mc);
    }

    @Override protected void beforeSwap() {
        interstellarTikiTiks.apexx.client.event.EventBus.post(
                new interstellarTikiTiks.apexx.client.event.impl.PearlModuleExecuteEvent(getName()));
    }

    @Override protected void afterUse() {
        if (mc.player != null && mc.player.getItemCooldownManager().isCoolingDown(Items.ENDER_PEARL.getDefaultStack())) {
            lastPearlThrownTime = System.currentTimeMillis();
        }
    }

    @Override protected void onSequenceEnd() { lastPearlFinishTime = System.currentTimeMillis(); }
}
