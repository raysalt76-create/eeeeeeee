package interstellarTikiTiks.apexx.client.hud;

import interstellarTikiTiks.apexx.client.module.BooleanSetting;
import interstellarTikiTiks.apexx.client.module.ColorSetting;
import interstellarTikiTiks.apexx.client.module.Setting;
import interstellarTikiTiks.apexx.client.module.SliderSetting;
import interstellarTikiTiks.apexx.client.module.hooks.AttackGatingProvider;
import interstellarTikiTiks.apexx.client.util.ColorUtils;
import interstellarTikiTiks.apexx.client.util.GuiShaderStyle;
import interstellarTikiTiks.apexx.client.util.InputPulseManager;
import interstellarTikiTiks.apexx.client.util.InputTracker;
import interstellarTikiTiks.apexx.client.util.ModuleAccess;
import interstellarTikiTiks.apexx.client.util.Renderer;
import interstellarTikiTiks.apexx.client.util.SystemInputSimulator;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import interstellarTikiTiks.apexx.client.mixin.KeyBindingAccessor;
import interstellarTikiTiks.apexx.client.gui.new_ui.HudEditorScreen;
import org.lwjgl.glfw.GLFW;

import java.awt.Color;
import java.util.ArrayList;
import java.util.List;

public class KeystrokeHudElement extends HUDElement {

    private static final Color KEY_BG_OFF = new Color(28,  28,  30, 220);
    private static final Color KEY_BG_ON  = new Color(10, 132, 255, 220);
    private static final Color LABEL_ON   = new Color(242, 242, 247, 255);
    private static final Color LABEL_OFF  = new Color(174, 174, 178, 180);

    private final BooleanSetting showWASD   = new BooleanSetting("Show WASD",   true);
    private final BooleanSetting showMouse  = new BooleanSetting("Show Mouse",  true);
    private final BooleanSetting showSprint = new BooleanSetting("Show Sprint", false);
    private final BooleanSetting showSpace  = new BooleanSetting("Show Space",  true);
    private final SliderSetting  keySize    = new SliderSetting("Key Size",     22.0, 14.0, 36.0, 0);
    private final SliderSetting  keySpacing = new SliderSetting("Key Spacing",   3.0,  1.0,  8.0, 0);
    private final SliderSetting  cornerRadius = new SliderSetting("Corner Radius", 5.0, 0.0, 12.0, 0);
    private final BooleanSetting showOuterBg = new BooleanSetting("Outer BG",  false);
    private final SliderSetting  bgOpacity  = new SliderSetting("BG Opacity",  0.65,  0.0,  1.0, 2);
    private final ColorSetting   keyOffColor = new ColorSetting("Key Off Color", KEY_BG_OFF);
    private final ColorSetting   keyOnColor  = new ColorSetting("Key On Color", KEY_BG_ON);
    private final ColorSetting   textOffColor = new ColorSetting("Text Off Color", LABEL_OFF);
    private final ColorSetting   textOnColor  = new ColorSetting("Text On Color", LABEL_ON);
    // Shown under "Use Own Colours" (HUDElement); otherwise pressed keys use the HUD accent and off keys,
    // labels and rims follow the HUD colours.
    private final List<Setting> ownColourSettings = List.of(keyOffColor, keyOnColor, textOffColor, textOnColor);

    private final double[] anim = new double[8];
    // Theme-derived key colours, rebuilt only when the HUD palette changes.
    private int cachedAccent, cachedText, cachedMuted, cachedRim, cachedBackground = -1;
    private Color themeKeyOn = KEY_BG_ON, themeKeyOff = KEY_BG_OFF, themeTextOn = LABEL_ON, themeTextOff = LABEL_OFF;

    public KeystrokeHudElement(String name, double defaultX, double defaultY) {
        super(name, defaultX, defaultY);
    }

    @Override
    protected List<Setting> elementSettings() {
        List<Setting> s = new ArrayList<>();
        s.add(showWASD); s.add(showMouse); s.add(showSprint); s.add(showSpace);
        s.add(keySize); s.add(keySpacing); s.add(cornerRadius);
        s.add(showOuterBg); s.add(bgOpacity);
        return s;
    }

    @Override
    protected List<? extends Setting> ownColourSettings() {
        return ownColourSettings;
    }

    @Override
    public void render(DrawContext context, float delta) {
        MinecraftClient mc = MinecraftClient.getInstance();

        boolean inGame = mc.player != null && (mc.currentScreen == null || mc.currentScreen instanceof HudEditorScreen);
        boolean w   = inGame && mc.options.forwardKey.isPressed();
        boolean a   = inGame && mc.options.leftKey.isPressed();
        boolean s   = inGame && mc.options.backKey.isPressed();
        boolean d   = inGame && mc.options.rightKey.isPressed();
        boolean inGameInput = mc.currentScreen == null;
        int jumpTimes = (mc.options.jumpKey instanceof KeyBindingAccessor acc) ? acc.getTimesPressed() : 0;
        boolean spr = inGame && mc.options.sprintKey.isPressed();
        int attackTimes = (mc.options.attackKey instanceof KeyBindingAccessor acc) ? acc.getTimesPressed() : 0;
        int useTimes = (mc.options.useKey instanceof KeyBindingAccessor acc) ? acc.getTimesPressed() : 0;
        boolean manualLmb = mc.options.attackKey.isPressed()
                || (inGameInput && attackTimes > 0)
                || InputTracker.isMouseButtonPressed(GLFW.GLFW_MOUSE_BUTTON_LEFT);
        boolean simulatedLmb = SystemInputSimulator.isMouseButtonSimulatedPressed(GLFW.GLFW_MOUSE_BUTTON_LEFT)
                || InputPulseManager.isRecentAttack(50);
        boolean lmb = inGame && (manualLmb || simulatedLmb);
        boolean rmb = inGame && (mc.options.useKey.isPressed()
                || (inGameInput && useTimes > 0)
                || InputTracker.isMouseButtonPressed(GLFW.GLFW_MOUSE_BUTTON_RIGHT)
                || SystemInputSimulator.isMouseButtonSimulatedPressed(GLFW.GLFW_MOUSE_BUTTON_RIGHT)
                || InputPulseManager.isRecentUse(50));
        boolean jmp = inGame && (mc.options.jumpKey.isPressed()
                || (inGameInput && jumpTimes > 0)
                || SystemInputSimulator.isSimulatedPressed(mc.options.jumpKey)
                || InputPulseManager.isRecentJump(50));

        boolean[] states = {w, a, s, d, lmb, rmb, spr, jmp};
        double dt = Math.max(0.001, Math.min(frameSeconds(), 0.05));
        double speed = 1.0 - Math.exp(-22.0 * dt);
        for (int i = 0; i < anim.length; i++) {
            anim[i] += ((states[i] ? 1.0 : 0.0) - anim[i]) * speed;
        }

        float ks  = (float) keySize.getValue();
        float gap = (float) keySpacing.getValue();
        float r   = (float) cornerRadius.getValue();

        float totalW = 3 * ks + 2 * gap;
        float totalH = computeHeight(ks, gap);

        setWidth(totalW);
        setHeight(totalH);

        float bx = (float) getX();
        float by = (float) getY();
        float fy = by;

        if (showOuterBg.isEnabled()) {
            int outerRgb = usesOwnColours() ? 0 : HudTheme.backgroundRgb();
            Color outer = new Color((outerRgb >> 16) & 0xFF, (outerRgb >> 8) & 0xFF, outerRgb & 0xFF,
                    (int)(bgOpacity.getValue() * 255));
            Renderer.get().drawRoundedRectStyled(context, bx - 4, by - 4, totalW + 8, totalH + 8, r + 3,
                    outer, GuiShaderStyle.HUD);
        }

        float fontSize = Math.max(7f, ks * 0.4f);

        if (showWASD.isEnabled()) {

            drawKey(context, "W",  bx + ks + gap, fy, ks, ks, r, anim[0], fontSize);
            fy += ks + gap;

            drawKey(context, "A",  bx,              fy, ks, ks, r, anim[1], fontSize);
            drawKey(context, "S",  bx + ks + gap,   fy, ks, ks, r, anim[2], fontSize);
            drawKey(context, "D",  bx + 2*(ks+gap), fy, ks, ks, r, anim[3], fontSize);
            fy += ks + gap;
        }

        if (showMouse.isEnabled()) {
            float half = (totalW - gap) / 2f;
            drawKey(context, "LMB", bx,            fy, half, ks, r, anim[4], fontSize);
            drawKey(context, "RMB", bx + half + gap, fy, half, ks, r, anim[5], fontSize);
            fy += ks + gap;
        }

        if (showSprint.isEnabled()) {
            drawKey(context, "CTRL", bx, fy, totalW, ks, r, anim[6], fontSize);
            fy += ks + gap;
        }

        if (showSpace.isEnabled()) {
            drawKey(context, "SPACE", bx, fy, totalW, ks * 0.65f, r, anim[7], fontSize);
        }

    }

    private float computeHeight(float ks, float gap) {
        float h = 0;
        if (showWASD.isEnabled())   h += 2 * (ks + gap);
        if (showMouse.isEnabled())  h += ks + gap;
        if (showSprint.isEnabled()) h += ks + gap;
        if (showSpace.isEnabled())  h += (ks * 0.65f) + gap;
        return Math.max(1f, h - gap);
    }

    private void drawKey(DrawContext context, String label,
                         float x, float y, float w, float h,
                         float r, double pressAnim, float fontSize) {
        boolean themed = !usesOwnColours();
        if (themed) refreshThemeColours();
        Color bg = ColorUtils.lerpColor(themed ? themeKeyOff : keyOffColor.getValue(),
                themed ? themeKeyOn : keyOnColor.getValue(), pressAnim);
        Renderer.get().drawRoundedRectStyled(context, x, y, w, h, r, bg, GuiShaderStyle.HUD);

        int borderA = (int)(40 + 80 * pressAnim);
        int rim = themed ? HudTheme.rim() : 0xFFFFFF;
        Renderer.get().drawRoundedRectOutline(context, x, y, w, h, r, 1.0f,
                new Color((rim >> 16) & 0xFF, (rim >> 8) & 0xFF, rim & 0xFF, borderA));

        Color textColor = ColorUtils.lerpColor(themed ? themeTextOff : textOffColor.getValue(),
                themed ? themeTextOn : textOnColor.getValue(), pressAnim);
        Renderer.get().drawCenteredText(context, label, x + w / 2, y + h / 2, fontSize, textColor, false);
    }

    private void refreshThemeColours() {
        int accent = HudTheme.accent(), text = HudTheme.text(), muted = HudTheme.muted(), rim = HudTheme.rim();
        int background = HudTheme.backgroundRgb();
        if (accent == cachedAccent && text == cachedText && muted == cachedMuted && rim == cachedRim
                && background == cachedBackground) return;
        cachedAccent = accent;
        cachedText = text;
        cachedMuted = muted;
        cachedRim = rim;
        cachedBackground = background;
        themeKeyOn = new Color((accent & 0x00FFFFFF) | (220 << 24), true);
        themeKeyOff = new Color(HudTheme.mix(0x1C1C1E, HudTheme.mix(background, rim, .08f), .5f) | (220 << 24), true);
        themeTextOn = new Color(HudTheme.onAccent(accent), true);
        themeTextOff = new Color((muted & 0x00FFFFFF) | (200 << 24), true);
    }

}
