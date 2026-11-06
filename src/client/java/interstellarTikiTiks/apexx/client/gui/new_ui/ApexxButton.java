package interstellarTikiTiks.apexx.client.gui.new_ui;

import interstellarTikiTiks.apexx.client.module.implementation.ClickGUIModule;
import interstellarTikiTiks.apexx.client.rendering.font.MSDFFontRenderer;
import interstellarTikiTiks.apexx.client.util.GuiShaderStyle;
import interstellarTikiTiks.apexx.client.util.VanillaRendererImpl;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.narration.NarrationMessageBuilder;
import net.minecraft.client.gui.widget.ClickableWidget;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

import java.awt.Color;
import java.util.function.Consumer;

public class ApexxButton extends ClickableWidget {

    private static final Identifier FONT = Identifier.of("dynamic_fps", "sans");
    private static final float FONT_SIZE = 1.4f;

    /**
     * Visual style. THEMED = the original theme-colored pill (default, used by the ClickGUI).
     * ARMORY_PRIMARY / ARMORY_GHOST = the flat, sharp, off-white "tactical armory" look used by the
     * sign-in screen so it mirrors the website login (radius 0, 1px lines, no gradients/glows).
     */
    public enum Style { THEMED, ARMORY_PRIMARY, ARMORY_GHOST, ARMORY_TEXT }

    // Armory monochrome palette (full black, silver, titanium white).
    private static final Color AX_INK0   = new Color(255, 255, 255);
    private static final Color AX_INK1   = new Color(161, 161, 170);
    private static final Color AX_BG0    = new Color(9, 10, 14);
    private static final Color AX_LINE1  = new Color(255, 255, 255, 38);   // ~15% hairline
    private static final Color AX_LINE2  = new Color(255, 255, 255, 180);  // ~70% hover highlight

    private final VanillaRendererImpl renderer;
    private final Consumer<ApexxButton> onPress;
    private float hoverAnim = 0.0f;
    private float clickAnim = 0.0f;
    private Style style = Style.THEMED;

    public ApexxButton(int x, int y, int width, int height, String label,
                        VanillaRendererImpl renderer, Consumer<ApexxButton> onPress) {
        super(x, y, width, height, Text.literal(label));
        this.renderer = renderer;
        this.onPress = onPress;
    }

    /** Fluent style setter (returns this so it can be chained at construction). */
    public ApexxButton style(Style s) {
        this.style = s == null ? Style.THEMED : s;
        return this;
    }

    @Override
    public void onClick(Click click, boolean doubled) {
        if (this.active && onPress != null) {
            clickAnim = 1.0f;
            onPress.accept(this);
        }
    }

    @Override
    public void renderWidget(DrawContext context, int mouseX, int mouseY, float delta) {
        float hoverTarget = (this.isHovered() && this.active) ? 1.0f : 0.0f;
        hoverAnim += (hoverTarget - hoverAnim) * Math.min(1.0f, delta * 0.20f);
        clickAnim *= (float) Math.pow(0.85, delta);

        if (style != Style.THEMED) {
            renderArmory(context);
            return;
        }

        float press = clickAnim;
        float maxRadius = Math.max(4.0f, Math.min(getWidth(), getHeight()) * 0.5f);
        float radius = Math.max(4.0f, Math.min(maxRadius, ClickGUIModule.getGuiCornerRadiusScaled(0.42f)));

        Color bgBase = withAlpha(ClickGUIModule.getSearchBoxBackground(), 0.86);
        Color bgHover = withAlpha(ClickGUIModule.getSearchBoxFocusedBackground(), 0.96);
        Color accentA = withAlpha(ClickGUIModule.getModuleEnabledGradientStart(), 0.75);
        Color accentB = withAlpha(ClickGUIModule.getModuleEnabledGradientEnd(), 0.88);
        Color textIdle = withAlpha(ClickGUIModule.getModuleDisabledText(), 0.92);
        Color textHover = withAlpha(ClickGUIModule.getModuleEnabledText(), 1.0);

        Color bg = this.active
                ? lerpColor(bgBase, bgHover, hoverAnim)
                : withAlpha(bgBase, 0.45);

        Color border = this.active
                ? lerpColor(accentA, accentB, hoverAnim)
                : withAlpha(accentA, 0.42);

        int textRGB = this.active
                ? lerpColor(textIdle, textHover, hoverAnim).getRGB()
                : withAlpha(textIdle, 0.5).getRGB();

        float pixelRatio = 1.0f;
        MinecraftClient client = MinecraftClient.getInstance();
        if (client != null) {
            pixelRatio = (float) (client.getWindow().getScaleFactor() * ClickGUIModule.getUiResolutionScale());
        }
        renderer.beginFrame(context.getScaledWindowWidth(), context.getScaledWindowHeight(), pixelRatio);

        renderer.drawRoundedRectStyled(context, getX(), getY(), getWidth(), getHeight(), radius, bg,
                GuiShaderStyle.CONTROL);

        if (this.active && hoverAnim > 0.01f) {
            renderer.drawRoundedRectStyled(context,
                    getX() + 1, getY() + 1,
                    getWidth() - 2, getHeight() / 2 - 1,
                    Math.max(2.0f, radius - 2.0f),
                    withAlpha(ClickGUIModule.getModuleEnabledGradientStart(), 0.16 * hoverAnim),
                    GuiShaderStyle.ROW_ENABLED);
        }

        if (press > 0.05f) {
            renderer.drawRoundedRectStyled(context,
                    getX(), getY(), getWidth(), getHeight(), radius,
                    withAlpha(ClickGUIModule.getModuleEnabledGradientEnd(), 0.26 * press), GuiShaderStyle.GLOW);
        }

        renderer.drawRoundedRectOutline(context, getX(), getY(), getWidth(), getHeight(), radius, 1, border);

        renderer.endFrame();

        if (client != null && client.textRenderer != null) {
            String label = getMessage().getString();
            int textW = client.textRenderer.getWidth(label);
            int textX = getX() + (getWidth() - textW) / 2;
            int textY = getY() + (getHeight() - 8) / 2;
            context.drawText(client.textRenderer, label, textX, textY, textRGB, false);
        }
    }

    /** Flat, sharp, titanium-monochrome rendering for NVIDIA-style armory buttons. */
    private void renderArmory(DrawContext context) {
        float pixelRatio = 1.0f;
        MinecraftClient client = MinecraftClient.getInstance();
        if (client != null) {
            pixelRatio = (float) (client.getWindow().getScaleFactor() * ClickGUIModule.getUiResolutionScale());
        }
        renderer.beginFrame(context.getScaledWindowWidth(), context.getScaledWindowHeight(), pixelRatio);

        Color textColor;
        if (style == Style.ARMORY_PRIMARY) {
            // High-contrast Solid Titanium White button with pure black text
            Color fill = lerpColor(new Color(244, 244, 245), Color.WHITE, hoverAnim);
            Color bg = this.active ? fill : withAlpha(new Color(244, 244, 245), 0.35);
            textColor = this.active ? AX_BG0 : withAlpha(AX_BG0, 0.6);
            renderer.drawRoundedRectStyled(context, getX(), getY(), getWidth(), getHeight(), 0.0f, bg,
                    GuiShaderStyle.CONTROL);
            if (this.active) {
                // Top subtle specular shine
                renderer.drawRoundedRectOutline(context, getX(), getY(), getWidth(), getHeight(), 0.0f, 1.0f,
                        lerpColor(new Color(255, 255, 255, 200), Color.WHITE, hoverAnim));
            }
        } else if (style == Style.ARMORY_TEXT) {
            // Text only — no fill, no border (subtle inline link e.g. FORGOT, SHOW/HIDE)
            textColor = this.active ? lerpColor(AX_INK1, AX_INK0, hoverAnim) : withAlpha(AX_INK1, 0.45);
        } else {
            // Ghost: dark transparent matte fill with a hairline silver frame that brightens on hover
            Color bg = new Color(14, 15, 20, this.active ? (int) (180 + 40 * hoverAnim) : 100);
            Color border = this.active ? lerpColor(new Color(60, 64, 80), Color.WHITE, hoverAnim) : new Color(38, 40, 50);
            textColor = this.active ? lerpColor(AX_INK1, AX_INK0, hoverAnim) : withAlpha(AX_INK1, 0.5);
            renderer.drawRoundedRectStyled(context, getX(), getY(), getWidth(), getHeight(), 0.0f, bg,
                    GuiShaderStyle.CONTROL);
            renderer.drawRoundedRectOutline(context, getX(), getY(), getWidth(), getHeight(), 0.0f, 1.0f, border);
        }

        renderer.endFrame();

        if (client != null && client.textRenderer != null) {
            String label = getMessage().getString();
            int textW = client.textRenderer.getWidth(label);
            int textX = getX() + (getWidth() - textW) / 2;
            int textY = getY() + (getHeight() - 8) / 2;
            context.drawText(client.textRenderer, label, textX, textY, textColor.getRGB(), false);
        }
    }

    @Override
    protected void appendClickableNarrations(NarrationMessageBuilder builder) {
        appendDefaultNarrations(builder);
    }

    private Color lerpColor(Color a, Color b, float t) {
        t = Math.max(0f, Math.min(1f, t));
        return new Color(
                (int)(a.getRed()   + (b.getRed()   - a.getRed())   * t),
                (int)(a.getGreen() + (b.getGreen() - a.getGreen()) * t),
                (int)(a.getBlue()  + (b.getBlue()  - a.getBlue())  * t),
                (int)(a.getAlpha() + (b.getAlpha() - a.getAlpha()) * t)
        );
    }

    private Color withAlpha(Color color, double alphaMul) {
        double clamped = Math.max(0.0, Math.min(1.0, alphaMul));
        int alpha = Math.max(0, Math.min(255, (int) Math.round(color.getAlpha() * clamped)));
        return new Color(color.getRed(), color.getGreen(), color.getBlue(), alpha);
    }
}
