package interstellarTikiTiks.apexx.client.hud;

import interstellarTikiTiks.apexx.client.module.Setting;
import interstellarTikiTiks.apexx.client.module.SliderSetting;
import interstellarTikiTiks.apexx.client.module.hooks.SchematicHudProvider;
import interstellarTikiTiks.apexx.client.util.ModuleAccess;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;

import java.util.List;

/**
 * Schematic builder materials and progress panel. The rows come from the enabled module that
 * implements {@link SchematicHudProvider}; with nothing loaded the widget draws nothing but keeps a
 * box so it can still be placed in the HUD editor.
 */
public class SchematicHudElement extends HUDElement {

    private static final int PAD = 4;
    private static final int LINE = 10;

    private final SliderSetting bgOpacity = new SliderSetting("BG Opacity", 0.69, 0.0, 1.0, 2)
            .setDescription("Opacity of the panel background.");

    public SchematicHudElement(String name, double defaultX, double defaultY) {
        super(name, defaultX, defaultY);
        setWidth(140);
        setHeight(24);
    }

    @Override
    protected List<Setting> elementSettings() {
        return List.of(bgOpacity);
    }

    @Override
    public void render(DrawContext context, float delta) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc == null || mc.textRenderer == null || mc.player == null) return;
        SchematicHudProvider provider = ModuleAccess.getEnabledHook(SchematicHudProvider.class);
        List<SchematicHudProvider.Row> rows = provider == null ? List.of() : provider.schematicHudRows();
        if (rows == null || rows.isEmpty()) {
            setWidth(140);
            setHeight(24);
            return;
        }
        int textW = 0;
        for (SchematicHudProvider.Row row : rows) {
            textW = Math.max(textW, mc.textRenderer.getWidth(row.text()));
        }
        int w = textW + PAD * 2;
        int h = rows.size() * LINE + PAD * 2 - 1;
        setWidth(w);
        setHeight(h);
        int x = (int) Math.round(getX());
        int y = (int) Math.round(getY());
        int alpha = (int) Math.round(Math.max(0.0, Math.min(1.0, bgOpacity.getValue())) * 255.0);
        // HUD colours (ClickGUI theme, HUD Custom, or this widget's own): background card + accent rule.
        context.fill(x, y, x + w, y + h, alpha << 24 | HudTheme.backgroundRgb());
        context.fill(x, y, x + w, y + 1, 0xFF000000 | HudTheme.accent());
        int ty = y + PAD;
        for (SchematicHudProvider.Row row : rows) {
            context.drawTextWithShadow(mc.textRenderer, row.text(), x + PAD, ty, row.color());
            ty += LINE;
        }
    }
}
