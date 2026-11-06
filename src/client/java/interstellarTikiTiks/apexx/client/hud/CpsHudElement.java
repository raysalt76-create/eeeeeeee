package interstellarTikiTiks.apexx.client.hud;

import interstellarTikiTiks.apexx.client.mixin.KeyBindingAccessor;
import interstellarTikiTiks.apexx.client.module.BooleanSetting;
import interstellarTikiTiks.apexx.client.module.ColorSetting;
import interstellarTikiTiks.apexx.client.module.Setting;
import interstellarTikiTiks.apexx.client.module.SliderSetting;
import interstellarTikiTiks.apexx.client.util.InputPulseManager;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;

import java.awt.Color;
import java.util.List;

/**
 * Clicks-per-second counter. This class samples input and holds settings; the glass widget itself is
 * drawn by {@link GpuHudRenderer} like the other modern widgets. Click timestamps live in primitive ring
 * buffers so sampling allocates nothing per frame.
 */
public class CpsHudElement extends HUDElement {
    public static final int HISTORY = 30;
    private static final int RING = 512;

    private final BooleanSetting showLeft    = new BooleanSetting("Show Left",     true);
    private final BooleanSetting showRight   = new BooleanSetting("Show Right",    true);
    private final BooleanSetting showGraph   = new BooleanSetting("Show Graph",    false);
    private final SliderSetting  trackWindow = new SliderSetting("Track Window",  1.0, 0.5, 5.0, 1);
    private final SliderSetting  fontSize    = new SliderSetting("Text Size",     11.0, 8.0, 18.0, 0);
    private final SliderSetting  cornerRadius = new SliderSetting("Corner Radius", 10.0, 0.0, 20.0, 0);
    private final SliderSetting  bgOpacity   = new SliderSetting("BG Opacity",    0.85, 0.0,  1.0, 2);
    private final BooleanSetting showBorder  = new BooleanSetting("Show Border",   true);
    // Shown under "Use Own Colours" (HUDElement); otherwise left = HUD accent, right = HUD text.
    private final ColorSetting   leftColor  = new ColorSetting("Left Color", new Color(10, 132, 255))
            .setDescription("Left-click value colour when Use Own Colours is on.");
    private final ColorSetting   rightColor = new ColorSetting("Right Color", new Color(50, 215, 75))
            .setDescription("Right-click value colour when Use Own Colours is on.");

    private final long[] leftClicks = new long[RING];
    private final long[] rightClicks = new long[RING];
    private int leftHead, leftCount, rightHead, rightCount;

    private final float[] leftHist  = new float[HISTORY];
    private final float[] rightHist = new float[HISTORY];
    private int histHead;
    private long lastHistoryMs;
    private long lastSampleMs;
    private float leftCps, rightCps;

    private boolean prevL;
    private boolean prevR;
    private int prevAttackTimes;
    private int prevUseTimes;

    public CpsHudElement(String name, double defaultX, double defaultY) {
        super(name, defaultX, defaultY);
        setWidth(96);
        setHeight(24);
    }

    @Override
    protected List<Setting> elementSettings() {
        return List.of(showLeft, showRight, showGraph, trackWindow, fontSize, cornerRadius, bgOpacity,
                showBorder);
    }

    private final List<Setting> ownColourSettings = List.of(leftColor, rightColor);

    @Override
    protected List<? extends Setting> ownColourSettings() {
        return ownColourSettings;
    }

    public boolean showLeft() { return showLeft.isEnabled(); }
    public boolean showRight() { return showRight.isEnabled(); }
    public boolean showGraph() { return showGraph.isEnabled(); }
    public float fontSize() { return (float) fontSize.getPreciseValue(); }
    public float cornerRadius() { return (float) cornerRadius.getPreciseValue(); }
    public float bgOpacity() { return (float) bgOpacity.getPreciseValue(); }
    public boolean showBorder() { return showBorder.isEnabled(); }
    public int leftColour() { return usesOwnColours() ? HudTheme.argb(leftColor.getValue()) : HudTheme.accent(); }
    public int rightColour() { return usesOwnColours() ? HudTheme.argb(rightColor.getValue()) : HudTheme.text(); }
    public float leftCps() { return leftCps; }
    public float rightCps() { return rightCps; }
    /** History sample {@code i} steps after the oldest one (0 = oldest). */
    public float leftHistory(int i) { return leftHist[(histHead + i) % HISTORY]; }
    public float rightHistory(int i) { return rightHist[(histHead + i) % HISTORY]; }

    /** Counts presses since the last frame. Called once per in-game HUD frame while the widget is enabled. */
    public void sample() {
        MinecraftClient mc = MinecraftClient.getInstance();
        long now = System.currentTimeMillis();
        boolean inGameInput = mc.player != null && mc.currentScreen == null;

        int attackTimes = inGameInput && mc.options.attackKey instanceof KeyBindingAccessor acc ? acc.getTimesPressed() : 0;
        int useTimes = inGameInput && mc.options.useKey instanceof KeyBindingAccessor acc ? acc.getTimesPressed() : 0;
        boolean curL = inGameInput && mc.options.attackKey.isPressed();
        boolean curR = inGameInput && mc.options.useKey.isPressed();
        int injectedL = InputPulseManager.drainAttackPulses();
        int injectedR = InputPulseManager.drainUsePulses();

        // After a gap (widget just enabled, a screen was open) discard stale pulse backlogs.
        boolean stale = now - lastSampleMs > 1000L;
        lastSampleMs = now;
        if (!stale) {
            // One real press can show up as an edge, a timesPressed increment and a pulse at once;
            // take the largest so a single click is never counted twice.
            int l = Math.max(Math.max(curL && !prevL ? 1 : 0, Math.max(0, attackTimes - prevAttackTimes)), injectedL);
            int r = Math.max(Math.max(curR && !prevR ? 1 : 0, Math.max(0, useTimes - prevUseTimes)), injectedR);
            for (int i = 0; i < l; i++) push(true, now);
            for (int i = 0; i < r; i++) push(false, now);
        }
        prevL = curL;
        prevR = curR;
        prevAttackTimes = attackTimes;
        prevUseTimes = useTimes;

        double window = Math.max(0.1, trackWindow.getPreciseValue());
        long windowMs = (long) (window * 1000);
        leftCps = countSince(true, now - windowMs) / (float) window;
        rightCps = countSince(false, now - windowMs) / (float) window;

        if (now - lastHistoryMs > 100) {
            leftHist[histHead] = leftCps;
            rightHist[histHead] = rightCps;
            histHead = (histHead + 1) % HISTORY;
            lastHistoryMs = now;
        }
    }

    private void push(boolean left, long time) {
        if (left) {
            leftClicks[leftHead] = time;
            leftHead = (leftHead + 1) % RING;
            leftCount = Math.min(RING, leftCount + 1);
        } else {
            rightClicks[rightHead] = time;
            rightHead = (rightHead + 1) % RING;
            rightCount = Math.min(RING, rightCount + 1);
        }
    }

    /** Counts entries newer than {@code since}; older ones are dropped from the live count. */
    private int countSince(boolean left, long since) {
        long[] ring = left ? leftClicks : rightClicks;
        int head = left ? leftHead : rightHead;
        int count = left ? leftCount : rightCount;
        int alive = 0;
        for (int i = 1; i <= count; i++) {
            if (ring[(head - i + RING) % RING] <= since) break;
            alive++;
        }
        if (left) leftCount = alive; else rightCount = alive;
        return alive;
    }

    /** Rendering is batched by GpuHudRenderer so all HUD geometry shares one display list. */
    @Override
    public void render(DrawContext context, float delta) { }
}
