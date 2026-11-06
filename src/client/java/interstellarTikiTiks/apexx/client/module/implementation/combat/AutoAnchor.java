package interstellarTikiTiks.apexx.client.module.implementation.combat;

import interstellarTikiTiks.apexx.client.module.BooleanSetting;
import interstellarTikiTiks.apexx.client.module.SliderSetting;
import interstellarTikiTiks.apexx.client.util.combat.ModuleDelays;

/**
 * AutoAnchor - fills and explodes respawn anchors (ported from Mochi's Auto Anchor).
 *
 * <p>It takes the anchor under the crosshair, or one you just placed, charges it with glowstone and
 * explodes it with a non-glowstone item on the next tick (a totem first with Use Totem). The safe
 * sequence - charge, glowstone shield, explode - is {@link SafeAnchor}; this module is the plain
 * fill/explode loop of {@link AnchorSequenceModule}.</p>
 *
 * <p>With Aim on, the look turns toward the anchor face at up to Aim Speed x 60 degrees a second and a
 * click is made only on the tick the live ray from that look is on the anchor. With Aim off every
 * click goes where your own crosshair already is. Silent Aim (off by default) keeps the turns off your
 * camera.</p>
 */
public final class AutoAnchor extends AnchorSequenceModule {

    /** Mochi's Aim Speed unit: degrees a second per point of the setting. */
    private static final double DEG_PER_SEC_PER_POINT = 60.0;

    /**
     * 1450 deg/s is 72.5 degrees a tick, under Intave's RotationModuloResetHeuristic (a yaw jump over 100
     * degrees onto an acquired target); the same ceiling SafeAnchor keeps.
     */
    private static final double MAX_DEG_PER_SEC = 1450.0;

    private final BooleanSetting aim = new BooleanSetting("Aim", true)
            .setDescription("Turns your look onto the anchor before each click so the click always lands on it. Off: every click goes wherever your crosshair already points.");
    private final SliderSetting aimSpeed = new SliderSetting("Aim Speed", 10.0, 1.0, 24.0, 0)
            .setDescription("How fast the look turns onto the anchor: 60 degrees a second per point, so 24 is 1440 deg/s, the fastest turn that stays under per-tick rotation checks.");
    private final BooleanSetting silentAim = new BooleanSetting("Silent Aim", false)
            .setDescription("Sends the turn onto the anchor only to the server and keeps your screen still. Off (default) moves your real camera, which looks most natural.");
    private final SliderSetting fillDelay = new SliderSetting("Fill Delay (ms)", 30, 0, 250, 0)
            .withJitter(6)
            .setDescription("Minimum wait before the glowstone charge click, in ms, measured from when the anchor was "
                    + "picked up or the previous click. Values under one tick (50 ms) keep the fastest legal cadence; "
                    + "raise it for a slower, hand-like rhythm.")
            .setSection("Delays");
    private final SliderSetting explodeDelay = new SliderSetting("Explode Delay (ms)", 30, 0, 250, 0)
            .withJitter(6)
            .setDescription("Minimum wait between the charge and the explosion click, in ms. The charge and explosion "
                    + "never share a tick anyway, so values under 50 ms keep the fastest cadence.")
            .setSection("Delays");
    private final SliderSetting cooldown = new SliderSetting("Cooldown (ms)", 50, 0, 1000, 0)
            .withJitter(15)
            .setDescription("Pause after an explosion before the next anchor is taken; your own anchor clicks are "
                    + "held back meanwhile. 0 chains the next anchor as soon as vanilla's own use delay allows.")
            .setSection("Delays");

    public AutoAnchor() {
        super("AutoAnchor", "After you place a respawn anchor, charges it with glowstone and detonates it for you, "
                + "each as a real slot switch and click on the anchor, then puts your hotbar slot back. "
                + "Hold right click with anchors to chain one after another.");
        setHoldMode(false);
        addSetting(aim);
        addSetting(aimSpeed);
        addSetting(silentAim);
        addSetting(fillDelay);
        addSetting(explodeDelay);
        addSetting(cooldown);
        aimSpeed.setVisibility(aim::isEnabled);
        silentAim.setVisibility(aim::isEnabled);
    }

    @Override
    protected int cooldownTicks() {
        // Hold right click on the anchor slot and the next anchor goes out once this pause has passed and
        // vanilla's own use delay allows (4 ticks after the placement).
        return ModuleDelays.msToTicks(cooldown.sample());
    }

    @Override
    protected long chargeGapMs() {
        return ModuleDelays.clampMs(fillDelay.sample());
    }

    @Override
    protected long explodeGapMs() {
        return ModuleDelays.clampMs(explodeDelay.sample());
    }

    @Override
    protected boolean waitsForRotationRelease() {
        // The next anchor takes the look straight from the glide back of the last one.
        return false;
    }

    @Override
    protected boolean isAimEnabled() {
        return aim.isEnabled();
    }

    @Override
    protected boolean isSilentAim() {
        return aim.isEnabled() && silentAim.isEnabled();
    }

    @Override
    protected double aimSpeedValue() {
        return Math.min(aimSpeed.getValue() * DEG_PER_SEC_PER_POINT, MAX_DEG_PER_SEC);
    }
}
