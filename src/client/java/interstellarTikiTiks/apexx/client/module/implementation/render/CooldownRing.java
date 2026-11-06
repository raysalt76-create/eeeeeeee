package interstellarTikiTiks.apexx.client.module.implementation.render;

import interstellarTikiTiks.apexx.client.module.BooleanSetting;
import interstellarTikiTiks.apexx.client.module.Category;
import interstellarTikiTiks.apexx.client.module.ColorSetting;
import interstellarTikiTiks.apexx.client.module.ModeSetting;
import interstellarTikiTiks.apexx.client.module.Module;
import interstellarTikiTiks.apexx.client.module.SliderSetting;
import interstellarTikiTiks.apexx.client.theme.AccentState;
import interstellarTikiTiks.apexx.client.util.combat.AttackCooldownMath;
import net.minecraft.client.gui.DrawContext;
import org.joml.Matrix3x2fStack;

import java.awt.Color;

/**
 * Attack cooldown as a ring around the crosshair. Drawn from the HUD callback, which runs after
 * vanilla's crosshair and after Custom Crosshair (both draw inside {@code InGameHud.renderCrosshair}),
 * so the ring always sits on top of whichever crosshair is active.
 *
 * <p>The ring is rasterised in real framebuffer pixels (the GUI matrix is scaled down by the GUI scale)
 * and each pixel row of the annulus is merged into runs, so a ring costs a few dozen fills per frame
 * and stays round at any GUI scale.</p>
 */
public final class CooldownRing extends Module {
    private static final String SHAPE = "Shape";
    private static final String COLORS = "Colors";

    private final SliderSetting radius = new SliderSetting("Radius", 9, 4, 30, 0)
            .setDescription("Outer radius of the ring in GUI pixels.")
            .setSection(SHAPE);
    private final SliderSetting thickness = new SliderSetting("Thickness", 1.5, 0.5, 6, 1)
            .setDescription("Width of the ring in GUI pixels.")
            .setSection(SHAPE);
    private final BooleanSetting track = new BooleanSetting("Track", true)
            .setDescription("Draws the unfilled part of the ring faintly so you can see how much is left.")
            .setSection(SHAPE);
    private final BooleanSetting hideWhenCharged = new BooleanSetting("Hide When Charged", true)
            .setDescription("Fades the ring out shortly after your attack is fully charged.")
            .setSection(SHAPE);
    private final SliderSetting fadeDelay = new SliderSetting("Fade Delay", 250, 0, 2000, 0)
            .setDescription("Milliseconds the full ring stays visible before fading when Hide When Charged is on.")
            .setDependsOn("Hide When Charged")
            .setSection(SHAPE);

    private final ModeSetting colorMode = new ModeSetting("Color Mode", "Theme", "Theme", "Custom")
            .setDescription("Theme uses the ClickGUI accent for the charging ring; Custom uses Custom Color.")
            .setSection(COLORS);
    private final ColorSetting customColor = new ColorSetting("Custom Color", new Color(120, 200, 255))
            .setDescription("Charging colour when Color Mode is Custom.")
            .setDependsOn("Color Mode:Custom")
            .setSection(COLORS);
    private final BooleanSetting stageColors = new BooleanSetting("Stage Colors", true)
            .setDescription("Shifts colour at the half-swing point (84.8%, a usable pressure hit) and again at full charge.")
            .setSection(COLORS);
    private final ColorSetting halfSwingColor = new ColorSetting("Half-Swing Color", new Color(255, 205, 70))
            .setDescription("Ring colour once the cooldown passes 84.8%.")
            .setDependsOn("Stage Colors")
            .setSection(COLORS);
    private final ColorSetting fullColor = new ColorSetting("Full Color", new Color(90, 255, 120))
            .setDescription("Ring colour at full charge.")
            .setDependsOn("Stage Colors")
            .setSection(COLORS);
    private final SliderSetting opacity = new SliderSetting("Opacity", 0.9, 0.1, 1.0, 2)
            .setDescription("Ring opacity.")
            .setSection(COLORS);

    private long fullSince = -1L;

    public CooldownRing() {
        super("CooldownRing", "Shows your attack cooldown as a ring around the crosshair that fills as it charges and "
                + "changes colour at the half-swing point and at full charge. Works with Custom Crosshair. "
                + "Visible only to you.", Category.RENDER);
        addSetting(radius);
        addSetting(thickness);
        addSetting(track);
        addSetting(hideWhenCharged);
        addSetting(fadeDelay);
        addSetting(colorMode);
        addSetting(customColor);
        addSetting(stageColors);
        addSetting(halfSwingColor);
        addSetting(fullColor);
        addSetting(opacity);
    }

    @Override
    public boolean rendersInWorld() {
        return false;
    }

    @Override
    public void onDisable() {
        fullSince = -1L;
    }

    @Override
    protected void onRuntimeStateReleased() {
        fullSince = -1L;
    }

    @Override
    public void onRender(DrawContext context, float tickDelta) {
        if (context == null || mc.player == null || mc.options == null || mc.currentScreen != null) return;
        if (!mc.options.getPerspective().isFirstPerson() || mc.player.isSpectator()) return;

        float progress = AttackCooldownMath.ringFraction(mc.player.getAttackCooldownProgress(tickDelta));
        int stage = AttackCooldownMath.stage(progress);

        float fade = 1.0f;
        long now = System.currentTimeMillis();
        if (stage == AttackCooldownMath.STAGE_FULL) {
            if (fullSince < 0L) fullSince = now;
            if (hideWhenCharged.isEnabled()) {
                long shown = now - fullSince - Math.round(fadeDelay.getValue());
                if (shown > 0L) fade = Math.max(0.0f, 1.0f - shown / 200.0f);
            }
        } else {
            fullSince = -1L;
        }
        if (fade <= 0.0f) return;

        int scale = Math.max(1, mc.getWindow().getScaleFactor());
        float outer = (float) radius.getValue() * scale;
        float inner = Math.max(0.0f, outer - (float) thickness.getValue() * scale);
        float alpha = (float) opacity.getValue() * fade;

        int fill = withAlpha(ringColor(progress, stage), alpha);
        int trackColor = track.isEnabled() ? withAlpha(0xFF000000 | (fill & 0x00FFFFFF), alpha * 0.22f) : 0;

        // Centre in framebuffer pixels; the GUI centre is width/2 in scaled units.
        float cx = context.getScaledWindowWidth() / 2.0f * scale;
        float cy = context.getScaledWindowHeight() / 2.0f * scale;

        Matrix3x2fStack m = context.getMatrices();
        m.pushMatrix();
        m.scale(1.0f / scale, 1.0f / scale);
        try {
            drawRing(context, cx, cy, inner, outer, (float) (progress * Math.PI * 2.0), fill, trackColor);
        } finally {
            m.popMatrix();
        }
    }

    private int ringColor(float progress, int stage) {
        int base = 0xFF000000 | ((colorMode.is("Custom") ? customColor.getValue() : AccentState.accentColor()).getRGB() & 0x00FFFFFF);
        if (!stageColors.isEnabled()) return base;
        int half = 0xFF000000 | (halfSwingColor.getValue().getRGB() & 0x00FFFFFF);
        int full = 0xFF000000 | (fullColor.getValue().getRGB() & 0x00FFFFFF);
        return switch (stage) {
            case AttackCooldownMath.STAGE_FULL -> full;
            case AttackCooldownMath.STAGE_HALF_SWING -> AttackCooldownMath.lerpArgb(half, full,
                    (progress - AttackCooldownMath.HALF_SWING) / (1.0f - AttackCooldownMath.HALF_SWING));
            default -> base;
        };
    }

    /**
     * Rasterises an annulus clockwise from 12 o'clock: pixels inside the sweep get {@code fill}, the rest
     * {@code trackColor} (0 skips). Consecutive pixels of one colour on a row become one fill call.
     */
    private static void drawRing(DrawContext context, float cx, float cy, float inner, float outer, float sweep,
                                 int fill, int trackColor) {
        int r = (int) Math.ceil(outer);
        float outerSq = outer * outer;
        float innerSq = inner * inner;
        int x0 = (int) Math.floor(cx) - r - 1;
        int y0 = (int) Math.floor(cy) - r - 1;
        int size = 2 * r + 2;
        double twoPi = Math.PI * 2.0;
        for (int row = 0; row <= size; row++) {
            int py = y0 + row;
            float dy = py + 0.5f - cy;
            int runStart = -1;
            int runColor = 0;
            for (int col = 0; col <= size + 1; col++) {
                int px = x0 + col;
                int color = 0;
                if (col <= size) {
                    float dx = px + 0.5f - cx;
                    float d2 = dx * dx + dy * dy;
                    if (d2 <= outerSq && d2 >= innerSq) {
                        double angle = Math.atan2(dx, -dy);
                        if (angle < 0.0) angle += twoPi;
                        color = angle <= sweep ? fill : trackColor;
                    }
                }
                if (color != runColor) {
                    if (runColor != 0 && runStart >= 0) context.fill(runStart, py, px, py + 1, runColor);
                    runStart = px;
                    runColor = color;
                }
            }
        }
    }

    private static int withAlpha(int argb, float alpha) {
        int a = Math.round(Math.max(0.0f, Math.min(1.0f, alpha)) * ((argb >>> 24) & 0xFF));
        return (a << 24) | (argb & 0x00FFFFFF);
    }
}
