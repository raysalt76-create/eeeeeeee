package interstellarTikiTiks.apexx.client.module.implementation.render;

import interstellarTikiTiks.apexx.client.module.BooleanSetting;
import interstellarTikiTiks.apexx.client.module.Category;
import interstellarTikiTiks.apexx.client.module.ColorSetting;
import interstellarTikiTiks.apexx.client.module.ModeSetting;
import interstellarTikiTiks.apexx.client.module.Module;
import interstellarTikiTiks.apexx.client.module.SliderSetting;
import interstellarTikiTiks.apexx.client.module.hooks.CrosshairProvider;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.option.AttackIndicator;

import java.awt.Color;
import java.util.ArrayList;
import java.util.List;

/** Replaces the vanilla crosshair with a configurable one drawn on the HUD. Purely visual. */
public final class CustomCrosshair extends Module implements CrosshairProvider {
    private static final String SHAPE = "Shape";
    private static final String COLORS = "Colors";
    private static final String DYNAMIC = "Dynamic Gap";

    private final ModeSetting style = new ModeSetting("Style", "Cross", "Cross", "Dot", "Circle", "T", "Plus")
            .setDescription("Cross: four arms around an open centre. Dot: a single square dot. Circle: a ring. "
                    + "T: a cross without the top arm. Plus: four arms that meet in a solid centre.")
            .setSection(SHAPE);
    private final SliderSetting size = new SliderSetting("Size", 5, 1, 20, 0)
            .setDescription("Length of each arm in HUD pixels (for Circle, how wide the ring is across).")
            .setDependsOn("Style:Cross|Circle|T|Plus")
            .setSection(SHAPE);
    private final SliderSetting gap = new SliderSetting("Gap", 2, 0, 15, 0)
            .setDescription("Empty space between the centre and the start of each arm (for Circle, it widens the ring). "
                    + "Ignored by Plus, whose arms always meet.")
            .setDependsOn("Style:Cross|Circle|T")
            .setSection(SHAPE);
    private final SliderSetting thickness = new SliderSetting("Thickness", 1, 1, 6, 0)
            .setDescription("Width of the arms or ring line in HUD pixels.")
            .setDependsOn("Style:Cross|Circle|T|Plus")
            .setSection(SHAPE);
    private final SliderSetting dotSize = new SliderSetting("Dot Size", 2, 1, 8, 0)
            .setDescription("Side length of the dot, used by the Dot style and the Center Dot option.")
            .setSection(SHAPE);
    private final BooleanSetting centerDot = new BooleanSetting("Center Dot", false)
            .setDescription("Adds a dot in the middle of the Cross, T or Circle styles.")
            .setDependsOn("Style:Cross|Circle|T")
            .setSection(SHAPE);

    private final ColorSetting color = new ColorSetting("Color", new Color(255, 255, 255))
            .setDescription("Main crosshair colour.")
            .setSection(COLORS);
    private final BooleanSetting outline = new BooleanSetting("Outline", true)
            .setDescription("Draws a border around every part of the crosshair so it stays visible on bright and dark backgrounds.")
            .setSection(COLORS);
    private final ColorSetting outlineColor = new ColorSetting("Outline Color", new Color(0, 0, 0))
            .setDescription("Colour of the border drawn when Outline is on.")
            .setDependsOn("Outline")
            .setSection(COLORS);
    private final SliderSetting outlineThickness = new SliderSetting("Outline Thickness", 1, 1, 3, 0)
            .setDescription("Width of the border in HUD pixels.")
            .setDependsOn("Outline")
            .setSection(COLORS);

    private final BooleanSetting expandOnMove = new BooleanSetting("Expand On Move", false)
            .setDescription("Opens the gap while you are moving, like a spread indicator.")
            .setSection(DYNAMIC);
    private final BooleanSetting expandOnCooldown = new BooleanSetting("Expand On Cooldown", false)
            .setDescription("Opens the gap right after you attack and closes it as your attack cooldown recharges, "
                    + "so a closed crosshair means a fully charged hit.")
            .setSection(DYNAMIC);
    private final SliderSetting expandAmount = new SliderSetting("Expand Amount", 3, 1, 10, 0)
            .setDescription("How many HUD pixels the gap opens at most.")
            .setSection(DYNAMIC);
    private final BooleanSetting attackIndicator = new BooleanSetting("Attack Indicator", true)
            .setDescription("Draws a small attack-cooldown bar under the crosshair while it recharges. Only shown when "
                    + "Minecraft's own Attack Indicator option is set to Crosshair, same as vanilla.")
            .setSection(DYNAMIC);

    private final List<int[]> rects = new ArrayList<>();
    private float smoothExpand;
    private long lastFrameNanos = -1L;

    public CustomCrosshair() {
        super("CustomCrosshair", "Replaces the vanilla crosshair with your own: cross, dot, circle, T or plus, "
                + "with size, gap, thickness, colours, an outline and an optional gap that reacts to movement and "
                + "attack cooldown. Visible only to you.", Category.RENDER);
        addSetting(style);
        addSetting(size);
        addSetting(gap);
        addSetting(thickness);
        addSetting(dotSize);
        addSetting(centerDot);
        addSetting(color);
        addSetting(outline);
        addSetting(outlineColor);
        addSetting(outlineThickness);
        addSetting(expandOnMove);
        addSetting(expandOnCooldown);
        addSetting(expandAmount);
        addSetting(attackIndicator);
        expandAmount.setVisibility(() -> expandOnMove.isEnabled() || expandOnCooldown.isEnabled());
        dotSize.setVisibility(() -> style.is("Dot") || (centerDot.isEnabled() && !style.is("Plus")));
    }

    @Override
    public void onDisable() {
        resetSmoothing();
    }

    @Override
    protected void onRuntimeStateReleased() {
        resetSmoothing();
    }

    private void resetSmoothing() {
        smoothExpand = 0.0f;
        lastFrameNanos = -1L;
    }

    @Override
    public boolean rendersInWorld() {
        return false;
    }

    @Override
    public boolean renderCrosshair(DrawContext context, float tickDelta) {
        if (context == null || mc.player == null) return false;

        int cx = context.getScaledWindowWidth() / 2;
        int cy = context.getScaledWindowHeight() / 2;
        int t = Math.max(1, (int) Math.round(thickness.getValue()));
        int len = Math.max(1, (int) Math.round(size.getValue()));
        int g = (int) Math.round(gap.getValue()) + Math.round(updateExpand(tickDelta));
        int dot = Math.max(1, (int) Math.round(dotSize.getValue()));

        rects.clear();
        String mode = style.getCurrentMode();
        switch (mode) {
            case "Dot" -> addSquare(cx, cy, dot);
            case "Circle" -> {
                addRing(cx, cy, g + len / 2.0f, t);
                if (centerDot.isEnabled()) addSquare(cx, cy, dot);
            }
            case "Plus" -> addArms(cx, cy, t, len, 0, true, true);
            case "T" -> {
                addArms(cx, cy, t, len, g, false, false);
                if (centerDot.isEnabled()) addSquare(cx, cy, dot);
            }
            default -> {
                addArms(cx, cy, t, len, g, true, false);
                if (centerDot.isEnabled()) addSquare(cx, cy, dot);
            }
        }

        context.createNewRootLayer();
        if (outline.isEnabled()) {
            int o = Math.max(1, (int) Math.round(outlineThickness.getValue()));
            int oc = opaque(outlineColor.getValue());
            for (int[] r : rects) context.fill(r[0] - o, r[1] - o, r[2] + o, r[3] + o, oc);
        }
        int fc = opaque(color.getValue());
        for (int[] r : rects) context.fill(r[0], r[1], r[2], r[3], fc);

        if (attackIndicator.isEnabled() && mc.options != null
                && mc.options.getAttackIndicator().getValue() == AttackIndicator.CROSSHAIR) {
            int extent = "Dot".equals(mode) ? dot / 2 : ("Circle".equals(mode) ? (int) Math.ceil(g + len / 2.0f) + t : len + g + t);
            drawCooldownBar(context, cx, cy + extent + 4, tickDelta);
        }
        return true;
    }

    /** Arms of a cross. {@code top=false} drops the top arm (T); {@code solid} fills the centre (Plus). */
    private void addArms(int cx, int cy, int t, int len, int g, boolean top, boolean solid) {
        int x0 = cx - t / 2;
        int y0 = cy - t / 2;
        int x1 = x0 + t;
        int y1 = y0 + t;
        rects.add(new int[]{x1 + g, y0, x1 + g + len, y1});
        rects.add(new int[]{x0 - g - len, y0, x0 - g, y1});
        rects.add(new int[]{x0, y1 + g, x1, y1 + g + len});
        if (top) rects.add(new int[]{x0, y0 - g - len, x1, y0 - g});
        if (solid) rects.add(new int[]{x0, y0, x1, y1});
    }

    private void addSquare(int cx, int cy, int side) {
        int x0 = cx - side / 2;
        int y0 = cy - side / 2;
        rects.add(new int[]{x0, y0, x0 + side, y0 + side});
    }

    /** A ring approximated by small squares along its circumference (fills have no curves). */
    private void addRing(int cx, int cy, float radius, int t) {
        float r = Math.max(1.0f, radius);
        int steps = Math.max(12, Math.min(160, (int) Math.ceil(2.0 * Math.PI * r * 1.5)));
        int lastX = Integer.MIN_VALUE;
        int lastY = Integer.MIN_VALUE;
        for (int i = 0; i < steps; i++) {
            double a = (Math.PI * 2.0 * i) / steps;
            int px = Math.round(cx + (float) Math.cos(a) * r) - t / 2;
            int py = Math.round(cy + (float) Math.sin(a) * r) - t / 2;
            if (px == lastX && py == lastY) continue;
            lastX = px;
            lastY = py;
            rects.add(new int[]{px, py, px + t, py + t});
        }
    }

    private void drawCooldownBar(DrawContext context, int cx, int y, float tickDelta) {
        float progress = mc.player.getAttackCooldownProgress(tickDelta);
        if (progress >= 1.0f) return;
        int width = 16;
        int x0 = cx - width / 2;
        int filled = Math.round(width * Math.max(0.0f, Math.min(1.0f, progress)));
        context.fill(x0 - 1, y - 1, x0 + width + 1, y + 3, 0xA0000000);
        if (filled > 0) context.fill(x0, y, x0 + filled, y + 2, opaque(color.getValue()));
    }

    /** Smoothed extra gap in HUD pixels from movement and attack cooldown. */
    private float updateExpand(float tickDelta) {
        float target = 0.0f;
        float max = (float) expandAmount.getValue();
        if (expandOnMove.isEnabled() && mc.player != null) {
            double dx = mc.player.getX() - mc.player.lastX;
            double dz = mc.player.getZ() - mc.player.lastZ;
            double speed = Math.sqrt(dx * dx + dz * dz);
            target = Math.max(target, (float) Math.min(1.0, speed / 0.28) * max);
        }
        if (expandOnCooldown.isEnabled() && mc.player != null) {
            float progress = Math.max(0.0f, Math.min(1.0f, mc.player.getAttackCooldownProgress(tickDelta)));
            target = Math.max(target, (1.0f - progress) * max);
        }
        long now = System.nanoTime();
        float dt = lastFrameNanos < 0 ? 1.0f : Math.min(0.25f, (now - lastFrameNanos) / 1.0e9f);
        lastFrameNanos = now;
        float k = 1.0f - (float) Math.exp(-dt * 18.0f);
        smoothExpand += (target - smoothExpand) * k;
        if (!Float.isFinite(smoothExpand)) smoothExpand = 0.0f;
        return smoothExpand;
    }

    private static int opaque(Color c) {
        if (c == null) return 0xFFFFFFFF;
        int alpha = c.getAlpha() == 0 ? 255 : c.getAlpha();
        return (alpha << 24) | (c.getRGB() & 0x00FFFFFF);
    }
}
