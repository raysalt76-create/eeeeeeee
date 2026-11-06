package interstellarTikiTiks.apexx.client.module.implementation;

import interstellarTikiTiks.apexx.client.mixin.ClientPlayerInteractionManagerMixin;
import interstellarTikiTiks.apexx.client.module.Category;
import interstellarTikiTiks.apexx.client.module.Module;
import interstellarTikiTiks.apexx.client.module.SliderSetting;

import interstellarTikiTiks.apexx.client.util.combat.ModuleDelays;

public class NoBreakDelay extends Module {

    /**
     * Base cooldown ticks between block breaks (feature setting; 0 = instant). Humanized variance on
     * top comes only from the global Timing Profile. The key keeps its old name so saved values
     * still load; the removed "Max Delay" key is ignored by ConfigManager.
     */
    private final SliderSetting minDelay = new SliderSetting("Min Delay", 1, 0, 5, 0)
            .setDescription("Minimum ticks between finishing one block and starting the next (vanilla waits 5). 0 starts the next block immediately; Extra Delay is added on top.");
    /** Static so the static break-cooldown hook can read it; there is only ever one NoBreakDelay. */
    private static final SliderSetting extraDelay = new SliderSetting("Extra Delay (ms)", 30, 0, 200, 0)
            .withJitter(10)
            .setDescription("Extra wait added on top of Min Delay after each break, drawn around this value with the StDev below, so the break rhythm is not a fixed tick count. 0 = exactly Min Delay.");

    public NoBreakDelay() {
        // BLATANT: zeroes blockBreakingCooldown (vanilla sets 5 after every break), giving a nuker-style
        // cadence no hand produces. The whole module is the bypass.
        super("NoBreakDelay", "Removes or shortens the 5-tick pause vanilla adds after every broken block, so you can mine one block straight after another. The pause becomes Min Delay plus a varied Extra Delay. Blatant: break rates faster than vanilla are easy for servers to flag.", Category.BLATANT);
        minDelay.setSection("Delays");
        extraDelay.setSection("Delays");
        addSetting(minDelay);
        addSetting(extraDelay);
    }

    /** Base cooldown plus this module's Extra Delay, in ticks. */
    public static int getHumanizedDelay(int base) {
        return Math.max(0, base) + ModuleDelays.msToTicks(extraDelay.sample());
    }

    @Override
    public void onTick() {
        if (mc.player == null || mc.interactionManager == null) {
            return;
        }

        if (mc.interactionManager instanceof ClientPlayerInteractionManagerMixin.ClientPlayerInteractionManagerAccessor accessor) {
            int delay = getHumanizedDelay((int) minDelay.getValue());
            accessor.setBlockBreakingCooldown(delay);
        }
    }

}
