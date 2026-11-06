package interstellarTikiTiks.apexx.client.gui.new_ui;

import interstellarTikiTiks.apexx.client.module.ColorSetting;
import interstellarTikiTiks.apexx.client.module.implementation.ClickGUIModule;
import interstellarTikiTiks.apexx.client.theme.Theme;
import interstellarTikiTiks.apexx.client.theme.ThemeManager;
import interstellarTikiTiks.apexx.client.util.IApexxRenderer;
import interstellarTikiTiks.apexx.client.util.Renderer;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.input.KeyInput;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.util.math.Rect2i;
import net.minecraft.text.Text;
import org.lwjgl.glfw.GLFW;

import java.awt.Color;
import java.util.function.Consumer;

public class ColorPickerScreen extends Screen {

    private final Screen parent;
    private final ColorSetting colorSetting;
    private final Consumer<Color> onColorChange;
    private final Theme theme;

    private float hue, saturation, brightness, alpha;

    private Rect2i panelBounds;
    private Rect2i sbBox;
    private Rect2i hueStrip;
    private Rect2i alphaStrip;
    private Rect2i previewBox;

    private boolean draggingSB, draggingHue, draggingAlpha;

    public ColorPickerScreen(Screen parent, ColorSetting setting, Consumer<Color> onColorChange) {
        super(Text.literal("Select Color"));
        this.parent = parent;
        this.colorSetting = setting;
        this.onColorChange = onColorChange;
        this.theme = ThemeManager.getInstance().getCurrentTheme();

        Color initialColor = setting.getValue();
        float[] hsb = Color.RGBtoHSB(initialColor.getRed(), initialColor.getGreen(), initialColor.getBlue(), null);
        this.hue = hsb[0];
        this.saturation = hsb[1];
        this.brightness = hsb[2];
        this.alpha = initialColor.getAlpha() / 255f;
    }

    @Override
    protected void init() {
        int panelW = 260;
        int panelH = 200;
        int x = (this.width - panelW) / 2;
        int y = (this.height - panelH) / 2;

        this.panelBounds = new Rect2i(x, y, panelW, panelH);

        int padding = 15;
        int stripWidth = 15;
        int bottomHeight = 25;
        int alphaHeight = 8;

        int sbWidth = panelW - (padding * 3) - stripWidth;
        int sbHeight = panelH - (padding * 3) - alphaHeight - bottomHeight - 10;

        this.sbBox = new Rect2i(x + padding, y + padding + 15, sbWidth, sbHeight);

        this.hueStrip = new Rect2i(x + padding + sbWidth + padding, y + padding + 15, stripWidth, sbHeight);

        this.alphaStrip = new Rect2i(x + padding, sbBox.getY() + sbHeight + 10, panelW - (padding * 2), alphaHeight);

        this.previewBox = new Rect2i(x + padding, alphaStrip.getY() + alphaHeight + 15, panelW - (padding * 2), 15);
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        IApexxRenderer renderer = Renderer.get();
        renderer.beginFrame(this.width, this.height, (float) this.client.getWindow().getScaleFactor());

        renderer.drawRect(context, 0, 0, width, height, new Color(0, 0, 0, 100));

        drawPanel(renderer, context);
        drawSatBriBox(renderer, context);
        drawHueStrip(renderer, context);
        drawAlphaStrip(renderer, context);
        drawPreviewAndText(renderer, context);

        renderer.endFrame();
    }

    private void drawPanel(IApexxRenderer renderer, DrawContext context) {

        Color bg = new Color(25, 25, 25, 250);
        Color border = new Color(60, 60, 60);

        renderer.drawRoundedRect(context, panelBounds.getX(), panelBounds.getY(),
                panelBounds.getWidth(), panelBounds.getHeight(), 8f, bg);

        renderer.drawRoundedRectOutline(context, panelBounds.getX(), panelBounds.getY(),
                panelBounds.getWidth(), panelBounds.getHeight(), 8f, 1.0f, border);

        renderer.drawCenteredText(context, "Color Picker",
                panelBounds.getX() + panelBounds.getWidth() / 2f,
                panelBounds.getY() + 8, 10f, Color.LIGHT_GRAY, ClickGUIModule.useShadows());
    }

    private void drawSatBriBox(IApexxRenderer renderer, DrawContext context) {
        float x = sbBox.getX();
        float y = sbBox.getY();
        float w = sbBox.getWidth();
        float h = sbBox.getHeight();

        Color hueColor = Color.getHSBColor(hue, 1f, 1f);

        renderer.drawRect(context, x, y, w, h, Color.WHITE);

        renderer.drawRoundedRectGradient(context, x, y, w, h, 0,
                new Color(255, 255, 255, 0), hueColor, false);

        renderer.drawRoundedRectGradient(context, x, y, w, h, 0,
                new Color(0, 0, 0, 0), Color.BLACK, true);

        renderer.drawRectOutline(context, x, y, w, h, 1f, new Color(0,0,0, 100));

        float cursorX = x + (saturation * w);
        float cursorY = y + ((1 - brightness) * h);
        float r = 2.5f;

        renderer.drawRoundedRect(context, cursorX - r, cursorY - r, r*2, r*2, r, Color.WHITE);
        renderer.drawRoundedRectOutline(context, cursorX - r, cursorY - r, r*2, r*2, r, 1.0f, Color.BLACK);
    }

    private void drawHueStrip(IApexxRenderer renderer, DrawContext context) {
        float x = hueStrip.getX();
        float y = hueStrip.getY();
        float w = hueStrip.getWidth();
        float h = hueStrip.getHeight();

        Color[] rainbow = {
                Color.RED, Color.YELLOW, Color.GREEN, Color.CYAN, Color.BLUE, Color.MAGENTA, Color.RED
        };

        float segmentH = h / (rainbow.length - 1);

        for (int i = 0; i < rainbow.length - 1; i++) {
            float curY = y + (i * segmentH);
            renderer.drawRoundedRectGradient(context, x, curY, w, segmentH + 1, 0,
                    rainbow[i], rainbow[i+1], true);
        }

        renderer.drawRectOutline(context, x, y, w, h, 1f, new Color(0,0,0, 100));

        float cursorY = y + (hue * h);
        renderer.scissor(context, x, y, w, h);
        renderer.drawRect(context, x, cursorY - 1.0f, w, 2.0f, new Color(0,0,0, 150));
        renderer.drawRect(context, x, cursorY - 0.5f, w, 1.0f, Color.WHITE);
        renderer.resetScissor();
    }

    private void drawAlphaStrip(IApexxRenderer renderer, DrawContext context) {
        float x = alphaStrip.getX();
        float y = alphaStrip.getY();
        float w = alphaStrip.getWidth();
        float h = alphaStrip.getHeight();

        Color fullColor = Color.getHSBColor(hue, saturation, brightness);
        Color transColor = new Color(fullColor.getRed(), fullColor.getGreen(), fullColor.getBlue(), 0);

        renderer.drawRoundedRectGradient(context, x, y, w, h, 2f, transColor, fullColor, false);

        float cursorX = Math.max(x + 0.5f, Math.min(x + w - 0.5f, x + (alpha * w)));
        renderer.scissor(context, x, y, w, h);
        renderer.drawRect(context, cursorX - 0.5f, y, 1.0f, h, Color.WHITE);
        renderer.resetScissor();
    }

    private void drawPreviewAndText(IApexxRenderer renderer, DrawContext context) {
        Color c = getColor();

        String hex = String.format("#%02x%02x%02x%02x", c.getAlpha(), c.getRed(), c.getGreen(), c.getBlue()).toUpperCase();
        renderer.drawText(context, hex, previewBox.getX(), previewBox.getY() - 9, 8f, Color.GRAY, ClickGUIModule.useShadows());

        renderer.drawText(context, "Alpha", alphaStrip.getX(), alphaStrip.getY() - 8, 7f, Color.GRAY, ClickGUIModule.useShadows());

        renderer.drawRoundedRect(context, previewBox.getX(), previewBox.getY(), previewBox.getWidth(), previewBox.getHeight(), 4f, new Color(50, 50, 50));
        renderer.drawRoundedRect(context, previewBox.getX(), previewBox.getY(), previewBox.getWidth(), previewBox.getHeight(), 4f, c);

        renderer.drawRoundedRectOutline(context, previewBox.getX(), previewBox.getY(), previewBox.getWidth(), previewBox.getHeight(), 4f, 1.0f, new Color(100, 100, 100));
    }

    private Color getColor() {
        Color c = Color.getHSBColor(hue, saturation, brightness);
        return new Color(c.getRed(), c.getGreen(), c.getBlue(), (int)(alpha * 255));
    }

    private void updateColor() {
        onColorChange.accept(getColor());
    }

    @Override
    public boolean mouseClicked(Click click, boolean doubleClick) {
        double mouseX = click.x();
        double mouseY = click.y();
        int button = click.button();

        if (button == 0) {
            if (sbBox.contains((int)mouseX, (int)mouseY)) {
                draggingSB = true;
                updateSB(mouseX, mouseY);
                return true;
            }
            if (hueStrip.contains((int)mouseX, (int)mouseY)) {
                draggingHue = true;
                updateHue(mouseY);
                return true;
            }
            if (alphaStrip.contains((int)mouseX, (int)mouseY)) {
                draggingAlpha = true;
                updateAlpha(mouseX);
                return true;
            }
        }
        return super.mouseClicked(click, doubleClick);
    }

    @Override
    public boolean mouseDragged(Click click, double deltaX, double deltaY) {
        double mouseX = click.x();
        double mouseY = click.y();

        if (draggingSB) updateSB(mouseX, mouseY);
        else if (draggingHue) updateHue(mouseY);
        else if (draggingAlpha) updateAlpha(mouseX);
        return super.mouseDragged(click, deltaX, deltaY);
    }

    @Override
    public boolean mouseReleased(Click click) {
        draggingSB = false;
        draggingHue = false;
        draggingAlpha = false;
        return super.mouseReleased(click);
    }

    private void updateSB(double mouseX, double mouseY) {
        this.saturation = (float) ((mouseX - sbBox.getX()) / sbBox.getWidth());
        this.brightness = 1.0f - (float) ((mouseY - sbBox.getY()) / sbBox.getHeight());
        clamp();
        updateColor();
    }

    private void updateHue(double mouseY) {
        this.hue = (float) ((mouseY - hueStrip.getY()) / hueStrip.getHeight());
        clamp();
        updateColor();
    }

    private void updateAlpha(double mouseX) {
        this.alpha = (float) ((mouseX - alphaStrip.getX()) / alphaStrip.getWidth());
        clamp();
        updateColor();
    }

    private void clamp() {
        hue = Math.max(0, Math.min(1, hue));
        saturation = Math.max(0, Math.min(1, saturation));
        brightness = Math.max(0, Math.min(1, brightness));
        alpha = Math.max(0, Math.min(1, alpha));
    }

    @Override
    public boolean keyPressed(KeyInput keyInput) {
        int keyCode = keyInput.key();
        if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
            this.close();
            return true;
        }
        return super.keyPressed(keyInput);
    }

    @Override
    public void close() {
        this.client.setScreen(parent);
    }
}
