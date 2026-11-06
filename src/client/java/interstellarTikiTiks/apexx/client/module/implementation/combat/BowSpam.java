package interstellarTikiTiks.apexx.client.module.implementation.combat;

import interstellarTikiTiks.apexx.client.event.EventBus;
import interstellarTikiTiks.apexx.client.event.EventTarget;
import interstellarTikiTiks.apexx.client.event.impl.TickEvent;
import interstellarTikiTiks.apexx.client.module.BooleanSetting;
import interstellarTikiTiks.apexx.client.module.Category;
import interstellarTikiTiks.apexx.client.module.SubCategory;
import interstellarTikiTiks.apexx.client.module.Module;
import interstellarTikiTiks.apexx.client.module.SliderSetting;
import interstellarTikiTiks.apexx.client.util.SystemInputSimulator;
import interstellarTikiTiks.apexx.client.util.combat.ModuleDelays;
import net.minecraft.item.BowItem;
import net.minecraft.item.ItemStack;
import net.minecraft.util.Hand;

import java.util.concurrent.ThreadLocalRandom;

/**
 * BowSpam - while you hold use with a bow, draws and looses arrows on a varied human rhythm: each draw's charge,
 * each gap between shots and the first reaction are drawn around the configured means, a small hesitation
 * chance occasionally holds a draw a little longer, and a fatigue model slowly lengthens charges over a long
 * volley. The vanilla-impossible "every tick at minimum charge" cadence is only available as a Blatant option.
 */
public class BowSpam extends Module implements interstellarTikiTiks.apexx.client.module.hooks.ClickSimulating {

    private static final int MIN_CHARGE_TICKS = 3;

    private final SliderSetting chargeMs = new SliderSetting("Charge Time (ms)", 400, 150, 1000, 0)
            .withJitter(60)
            .setDescription("How long each draw is held before the arrow is loosed. 150 ms (3 ticks) is the shortest draw that still fires an arrow; around 400 ms looks like a quick but human spam. Higher values hit harder and fly further.")
            .setSection("Delays");
    private final SliderSetting releaseGapMs = new SliderSetting("Release Gap (ms)", 120, 0, 800, 0)
            .withJitter(35)
            .setDescription("Pause after loosing an arrow before the next draw starts. Rounded to whole ticks; the next draw is never in the same tick as the release, so 0 redraws on the next tick.")
            .setSection("Delays");
    private final SliderSetting reactionMs = new SliderSetting("Reaction Delay (ms)", 60, 0, 400, 0)
            .withJitter(20)
            .setDescription("Delay between you pressing use and the first draw, so the volley does not start on the exact frame of your click.")
            .setSection("Delays");
    private final SliderSetting hesitationChance = new SliderSetting("Hesitation Chance %", 6, 0, 30, 0)
            .setDescription("Chance per draw to hold it noticeably longer (by the Hesitation time), the way a player occasionally lines up a shot.")
            .setSection("Humanization");
    private final SliderSetting hesitationMs = new SliderSetting("Hesitation (ms)", 200, 0, 1000, 0)
            .withJitter(60)
            .setDescription("Extra draw time added when a hesitation happens (see Hesitation Chance).")
            .setSection("Delays");
    private final BooleanSetting fatigue = new BooleanSetting("Fatigue", true)
            .setDescription("Over a long volley the charge time slowly creeps up (up to the Fatigue Max), then recovers once you stop shooting.")
            .setSection("Humanization");
    private final SliderSetting fatigueMax = new SliderSetting("Fatigue Max %", 15, 0, 50, 0)
            .setDependsOn("Fatigue")
            .setDescription("The most the fatigue model can lengthen a charge, as a percentage of Charge Time.")
            .setSection("Humanization");
    private final BooleanSetting onlyWhenHoldingUse = new BooleanSetting("Only on Hold", true)
            .setDescription("Only fires while you are holding the use key yourself. Off: keeps drawing whenever a bow is in hand.");
    private final BooleanSetting minimumChargeSpam = new BooleanSetting("Minimum Charge Spam", false)
            .setBlatant(true)
            .setDescription("BLATANT: ignores the timings above and looses at the 3-tick minimum with a 1-tick gap. No player can repeat that cadence and strict servers flag it.");

    private int nextActionTick = 0;
    private int requiredChargeTicks = MIN_CHARGE_TICKS;
    private boolean reactionArmed = false;
    private long reactionReadyAtMs = 0L;
    private int volleyShots = 0;
    private long lastShotAtMs = 0L;

    public BowSpam() {
        super("BowSpam", "Keeps a bow firing while you hold use: draws, looses and redraws on a varied human rhythm with randomized charge, gaps, hesitation and fatigue, so a volley of quick shots never sits on a fixed beat.", Category.COMBAT);
        setSubCategory(SubCategory.SMP);
        addSetting(chargeMs);
        addSetting(releaseGapMs);
        addSetting(reactionMs);
        addSetting(hesitationChance);
        addSetting(hesitationMs);
        addSetting(fatigue);
        addSetting(fatigueMax);
        addSetting(onlyWhenHoldingUse);
        addSetting(minimumChargeSpam);
    }

    @Override
    public void onEnable() {
        EventBus.register(this);
        resetState();
    }

    @Override
    public void onDisable() {
        EventBus.unregister(this);
        releaseSimulatedUse();
        resetState();
    }

    @Override
    protected void onRuntimeStateReleased() {
        releaseSimulatedUse();
        resetState();
    }

    private void releaseSimulatedUse() {
        if (mc != null && mc.options != null && SystemInputSimulator.isSimulatedPressed(mc.options.useKey)) {
            SystemInputSimulator.releaseUse();
        }
    }

    @EventTarget
    public void onTick(TickEvent event) {
        if (!isEnabled() || mc.player == null || mc.world == null || mc.interactionManager == null) {
            bailOut();
            return;
        }
        if (mc.currentScreen != null || !mc.player.isAlive() || getBowHand() == null) {
            bailOut();
            return;
        }

        // The player's own hold: the physical key, not the binding this module presses itself.
        boolean physicallyHeld = mc.options != null && SystemInputSimulator.isPhysicallyPressed(mc.options.useKey);
        if (onlyWhenHoldingUse.isEnabled() && !physicallyHeld) {
            bailOut();
            return;
        }

        long now = System.currentTimeMillis();
        if (now - lastShotAtMs > 1500L) volleyShots = 0;

        if (mc.player.isUsingItem()) {
            ItemStack activeStack = mc.player.getActiveItem();
            if (activeStack.getItem() instanceof BowItem && mc.player.getItemUseTime() >= requiredChargeTicks) {
                // The release packet is recorded by the send seam when it actually leaves.
                SystemInputSimulator.releaseUse();
                volleyShots++;
                lastShotAtMs = now;
                nextActionTick = mc.player.age + gapTicks();
                requiredChargeTicks = nextChargeTicks();
            }
            return;
        }

        if (mc.player.age < nextActionTick) return;
        if (!minimumChargeSpam.isEnabled() && volleyShots == 0) {
            if (!reactionArmed) {
                reactionArmed = true;
                reactionReadyAtMs = now + ModuleDelays.clampMs(reactionMs.sample());
            }
            if (now < reactionReadyAtMs) return;
        }
        if (volleyShots == 0) requiredChargeTicks = nextChargeTicks();
        // Vanilla's use handling sends the draw; the send seam records it.
        SystemInputSimulator.pressUse();
        nextActionTick = mc.player.age + 1;
    }

    private int gapTicks() {
        if (minimumChargeSpam.isEnabled()) return 1;
        return Math.max(1, ModuleDelays.msToTicks(releaseGapMs.sample()));
    }

    private int nextChargeTicks() {
        if (minimumChargeSpam.isEnabled()) return MIN_CHARGE_TICKS;
        double ms = chargeMs.sample();
        if (fatigue.isEnabled()) {
            double ramp = Math.min(1.0, volleyShots / 12.0);
            ms *= 1.0 + ramp * fatigueMax.getValue() / 100.0;
        }
        if (ThreadLocalRandom.current().nextDouble() * 100.0 < hesitationChance.getValue()) {
            ms += ModuleDelays.clampMs(hesitationMs.sample());
        }
        return Math.max(MIN_CHARGE_TICKS, ModuleDelays.msToTicks(ms));
    }

    private Hand getBowHand() {
        if (mc.player == null) return null;
        if (mc.player.getMainHandStack().getItem() instanceof BowItem) return Hand.MAIN_HAND;
        if (mc.player.getOffHandStack().getItem() instanceof BowItem) return Hand.OFF_HAND;
        return null;
    }

    /** Every bail-out lets go of a draw this module is holding, then clears the rhythm. */
    private void bailOut() {
        releaseSimulatedUse();
        resetState();
    }

    private void resetState() {
        nextActionTick = 0;
        requiredChargeTicks = MIN_CHARGE_TICKS;
        reactionArmed = false;
        reactionReadyAtMs = 0L;
    }
}
