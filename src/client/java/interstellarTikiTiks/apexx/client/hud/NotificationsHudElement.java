package interstellarTikiTiks.apexx.client.hud;

import interstellarTikiTiks.apexx.client.gui.gpuui.framework.UiDisplayList;
import interstellarTikiTiks.apexx.client.gui.gpuui.framework.UiDrawCommand;
import interstellarTikiTiks.apexx.client.module.Module;
import interstellarTikiTiks.apexx.client.module.Setting;
import interstellarTikiTiks.apexx.client.util.ModuleAccess;

import java.util.List;

/**
 * The HUD editor's entry for the module on/off cards. The cards themselves are drawn by the Notifications render
 * module (which also owns their corner, size and style), so this widget is only its on/off switch: turning it off
 * here turns that module off, and the other way round. In the editor it shows a sample card where the module's
 * corner setting would put it; in the game it draws nothing itself.
 */
public final class NotificationsHudElement extends GpuWidget {

    public NotificationsHudElement(String name, double defaultX, double defaultY) {
        super(name, defaultX, defaultY);
        setWidth(150);
        setHeight(34);
    }

    private static Module module() {
        return ModuleAccess.getModule("Notifications");
    }

    @Override
    public boolean isEnabled() {
        Module m = module();
        return m != null && m.isEnabled();
    }

    @Override
    public void setEnabled(boolean enabled) {
        Module m = module();
        if (m != null && m.isEnabled() != enabled) m.setEnabled(enabled);
    }

    @Override
    protected List<Setting> elementSettings() {
        return List.of();
    }

    @Override
    public void appendGpu(UiDisplayList.Builder b, int layoutWidth, int layoutHeight, float delta,
                          float opacity, boolean editorPreview) {
        if (!editorPreview) return;
        float x = (float) getX(), y = (float) getY(), w = 150f, h = 34f;
        panel(b, x, y, w, h, opacity);
        int accent = tint(HudTheme.accent(), opacity);
        b.text(x + 10f, y + 7f, 8.5f, tint(HudTheme.text(), opacity), "Notifications", UiDrawCommand.TextWeight.SEMIBOLD);
        b.text(x + 10f, y + 19f, 7.2f, tint(HudTheme.muted(), opacity), "Module on / off cards");
        // A small switch like the one on the real card.
        float sw = 22f, sh = 11f, sx = x + w - 10f - sw, sy = y + (h - sh) * 0.5f;
        boolean on = isEnabled();
        b.rect(sx, sy, sw, sh, sh * 0.5f, on ? accent : tint(HudTheme.muted(), opacity * 0.5f));
        float knob = sh - 3f;
        b.circle(on ? sx + sw - 1.5f - knob * 0.5f : sx + 1.5f + knob * 0.5f, sy + sh * 0.5f, knob * 0.5f,
                tint(0xFFFFFFFF, opacity));
    }
}
