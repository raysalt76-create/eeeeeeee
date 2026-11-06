package interstellarTikiTiks.apexx.client.module.implementation.player;

import interstellarTikiTiks.apexx.client.mixin.MinecraftClientAccessor;
import interstellarTikiTiks.apexx.client.module.BooleanSetting;
import interstellarTikiTiks.apexx.client.module.Category;
import interstellarTikiTiks.apexx.client.module.Module;
import interstellarTikiTiks.apexx.client.module.SliderSetting;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;

public class FastExp extends Module implements interstellarTikiTiks.apexx.client.module.hooks.ClickSimulating {

    // (min, max, default) transposition again -- this clamped Speed to 12, i.e. the
    // 1-tick delay at line 51, rather than the intended default of 4.
    private final SliderSetting clockSpeed = new SliderSetting("Speed", 4, 1, 12, 1).withJitter(1)
            .setDescription("Throw rate while you hold right-click with experience bottles. 1-9 stay at or below vanilla's held-click rate (one bottle every 4 ticks); 10-12 throw faster than vanilla allows (every 3, 2 or 1 tick) and are easy for anticheats to flag.")
            .setBlatant(true);
    private final BooleanSetting autoExp = new BooleanSetting("Auto EXP", true)
            .setDescription("When you hold right-click without experience bottles in either hand, selects the first hotbar slot that has them.");

    private final SliderSetting swapDelay = new SliderSetting("Swap Delay (ms)", 60, 0, 300, 0).withJitter(20)
            .setSection("Delays")
            .setDescription("After Auto EXP selects your bottles, wait this long before throwing resumes, in milliseconds.");

    private int clock = 0;
    private int delayTicks = -1;

    public FastExp() {
        super("FastExp", "Speeds up throwing experience bottles while you hold right-click, for repairing Mending gear quickly, and can pick the bottles from your hotbar for you. Speeds above 9 are faster than vanilla.", Category.PLAYER);
        addSetting(clockSpeed);
        addSetting(autoExp);
        addSetting(swapDelay);
    }

    @Override
    public void onDisable() {
        clock = 0;
    }

    @Override
    protected void onRuntimeStateReleased() {
        clock = 0;
    }

    @Override
    public void onTick() {
        if (mc.player == null || mc.world == null || mc.currentScreen != null) {
            return;
        }

        if (mc.options.useKey.isPressed()) {
            boolean mainHasXP = mc.player.getMainHandStack().isOf(Items.EXPERIENCE_BOTTLE);
            boolean offHasXP = mc.player.getOffHandStack().isOf(Items.EXPERIENCE_BOTTLE);
            boolean hasXP = mainHasXP || offHasXP;

            if (!hasXP && autoExp.isEnabled()) {
                for (int i = 0; i < 9; i++) {
                    ItemStack stack = mc.player.getInventory().getStack(i);
                    if (stack.isOf(Items.EXPERIENCE_BOTTLE)) {
                        if (!interstellarTikiTiks.apexx.client.util.combat.TickActionOrder.canSwitchSlot()) {
                            return;
                        }
                        interstellarTikiTiks.apexx.client.util.combat.SlotSync.select(mc, i);
                        // Hold vanilla's own use cooldown across the swap delay so the throw waits for it.
                        int waitTicks = interstellarTikiTiks.apexx.client.util.combat.ModuleDelays.msToTicks(swapDelay.sample());
                        MinecraftClientAccessor accessor = (MinecraftClientAccessor) mc;
                        if (waitTicks > accessor.getItemUseCooldown()) accessor.setItemUseCooldown(waitTicks);
                        clock = 0;
                        hasXP = true;
                        break;
                    }
                }
            }

            if (!hasXP) {
                return;
            }

            // The throw rhythm is resampled after every throw (Speed StDev), so it never ticks like a metronome.
            if (delayTicks < 0) delayTicks = nextDelayTicks();

            clock++;
            if (clock >= delayTicks) {
                ((MinecraftClientAccessor) mc).setItemUseCooldown(0);
                clock = 0;
                delayTicks = nextDelayTicks();
            }
        } else {
            clock = 0;
            delayTicks = -1;
        }
    }

    private int nextDelayTicks() {
        int speed = (int) Math.round(Math.max(1.0, Math.min(12.0, clockSpeed.sample())));
        // Jitter never carries a vanilla-rate setting (9 or below) into the faster-than-vanilla range.
        if (clockSpeed.getValue() <= 9.0) speed = Math.min(speed, 9);
        return Math.max(1, 13 - speed);
    }
}
