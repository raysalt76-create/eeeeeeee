package interstellarTikiTiks.apexx.client.module.implementation.combat;

import interstellarTikiTiks.apexx.client.module.BooleanSetting;
import interstellarTikiTiks.apexx.client.module.Category;
import interstellarTikiTiks.apexx.client.module.Module;
import interstellarTikiTiks.apexx.client.module.SliderSetting;
import interstellarTikiTiks.apexx.client.module.SubCategory;
import interstellarTikiTiks.apexx.client.util.combat.ModuleDelays;

/**
 * AutoSpearReach - the spear-reach control surface for mace smashes. A held spear's attack range is 4.5
 * blocks instead of the vanilla 3.0, so a smash or stun slam that fires from a spear reaches further.
 *
 * <p>This module owns the settings; the timing-critical swap/smash execution belongs to {@link AutoMace}.
 * The reach it reports is capped at the spear's real 4.5 unless the Blatant "Extended Reach" option is on,
 * and is shaved by a small, slowly re-drawn human margin so engagements do not all start at the exact edge.
 */
public class AutoSpearReach extends Module {

    /** 1.21.11's spear attack range. */
    public static final double VANILLA_SPEAR_REACH = 4.5;
    private static final long MARGIN_RESAMPLE_MS = 750L;

    private final BooleanSetting autoSwap = new BooleanSetting("Auto Swap", true)
            .setDescription("With a spear in your hotbar, when the fall forecast puts a target within spear reach soon, the spear is selected and held so the smash swaps from it at the extended reach; your previous slot is restored afterwards.");
    private final BooleanSetting ownSpearReach = new BooleanSetting("Own Spear Reach", true)
            .setDescription("Uses the spear range whenever you are already holding a spear yourself, with no auto-swap. Off: only Auto Swap grants the spear range.");

    private final SliderSetting spearReach = new SliderSetting("Spear Reach", 4.5, 3.0, 4.5, 2)
            .setDescription("Attack reach, in blocks, while the spear range applies. 4.5 is the spear's real range in 1.21.11; lower it to stay further inside the limit.");
    private final BooleanSetting extendedReach = new BooleanSetting("Extended Reach", false)
            .setBlatant(true)
            .setDescription("BLATANT: allows a reach above the spear's real 4.5 (set by Extended Reach Value). Servers that check reach flag every hit from beyond 4.5.");
    private final SliderSetting extendedReachValue = new SliderSetting("Extended Reach Value", 4.8, 4.5, 5.0, 2)
            .setDependsOn("Extended Reach")
            .setBlatant(true)
            .setDescription("BLATANT: the reach used while Extended Reach is on.");

    private final SliderSetting reachMargin = new SliderSetting("Reach Margin", 0.15, 0.0, 0.6, 2)
            .withJitter(0.08)
            .setSection("Humanization")
            .setDescription("Blocks shaved off the reach so engagements start a little inside the limit, the way a player rarely swings at the exact edge. Re-drawn every ~0.75 s.");
    private final SliderSetting swapReactionMs = new SliderSetting("Swap Reaction (ms)", 70, 0, 400, 0)
            .withJitter(25)
            .setSection("Delays")
            .setDescription("Delay between the forecast deciding a spear swap is due and the spear actually being selected, like a player's reaction to an opening.");
    private final SliderSetting swapLookahead = new SliderSetting("Swap Lookahead", 2, 0, 10, 0)
            .withJitter(1)
            .setSection("Auto Swap Tuning")
            .setDescription("Auto Swap: how many ticks ahead the forecast must put a target inside spear reach before the spear is selected (2 = 100 ms).");
    private final SliderSetting holdLookahead = new SliderSetting("Hold Lookahead", 6, 1, 20, 0)
            .setSection("Auto Swap Tuning")
            .setDescription("Auto Swap: while already holding the swapped spear, how far ahead the forecast is checked before giving it up (6 = 300 ms).");
    private final SliderSetting holdMargin = new SliderSetting("Hold Margin", 0.75, 0.0, 2.0, 2)
            .setSection("Auto Swap Tuning")
            .setDescription("Auto Swap: extra blocks of slack before a held spear is released, so a marginal forecast does not flicker the hotbar.");
    private final SliderSetting maxHold = new SliderSetting("Max Hold", 40, 5, 100, 0)
            .setSection("Auto Swap Tuning")
            .setDescription("Auto Swap: the longest a swapped spear is held without a smash before your previous slot is restored (40 ticks = 2 s).");

    private double cachedMargin = 0.15;
    private long marginDrawnAtMs = 0L;
    private int cachedSwapLookahead = 2;
    private long lookaheadDrawnAtMs = 0L;

    public AutoSpearReach() {
        super("AutoSpearReach", "Lets mace smashes start from spear range (4.5 blocks) instead of 3.0, either by briefly selecting a hotbar spear when a target is about to come into range or while you hold one yourself, with a human reach margin and swap reaction.", Category.COMBAT);
        setSubCategory(SubCategory.MACE);
        swapLookahead.setVisibility(autoSwap::isEnabled);
        holdLookahead.setVisibility(autoSwap::isEnabled);
        holdMargin.setVisibility(autoSwap::isEnabled);
        maxHold.setVisibility(autoSwap::isEnabled);
        swapReactionMs.setVisibility(autoSwap::isEnabled);
        addSetting(autoSwap);
        addSetting(ownSpearReach);
        addSetting(spearReach);
        addSetting(extendedReach);
        addSetting(extendedReachValue);
        addSetting(reachMargin);
        addSetting(swapReactionMs);
        addSetting(swapLookahead);
        addSetting(holdLookahead);
        addSetting(holdMargin);
        addSetting(maxHold);
    }

    // -- read by AutoMace --------------------------------------------------------------------

    public boolean autoSwapEnabled() {
        return autoSwap.isEnabled();
    }

    public boolean ownSpearReachEnabled() {
        return ownSpearReach.isEnabled();
    }

    /** The reach to plan with: capped at the spear's real 4.5 unless Extended Reach is on, minus the human margin. */
    public double spearReach() {
        double cap = extendedReach.isEnabled() ? extendedReachValue.getValue()
                : Math.min(spearReach.getValue(), VANILLA_SPEAR_REACH);
        long now = System.currentTimeMillis();
        if (now - marginDrawnAtMs >= MARGIN_RESAMPLE_MS) {
            cachedMargin = Math.max(0.0, reachMargin.sample());
            marginDrawnAtMs = now;
        }
        return Math.max(3.0, cap - cachedMargin);
    }

    /** A freshly sampled delay, in ms, between a swap decision and the spear selection. */
    public long swapReactionMs() {
        return ModuleDelays.clampMs(swapReactionMs.sample());
    }

    public int swapLookaheadTicks() {
        long now = System.currentTimeMillis();
        if (now - lookaheadDrawnAtMs >= MARGIN_RESAMPLE_MS) {
            cachedSwapLookahead = (int) Math.round(swapLookahead.sample());
            lookaheadDrawnAtMs = now;
        }
        return Math.max(0, cachedSwapLookahead);
    }

    public int holdLookaheadTicks() {
        return (int) Math.round(holdLookahead.getValue());
    }

    public double holdMargin() {
        return holdMargin.getValue();
    }

    public int maxHoldTicks() {
        return (int) Math.round(maxHold.getValue());
    }
}
