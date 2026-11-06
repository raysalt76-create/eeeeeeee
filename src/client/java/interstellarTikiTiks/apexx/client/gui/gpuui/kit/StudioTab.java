package interstellarTikiTiks.apexx.client.gui.gpuui.kit;

import interstellarTikiTiks.apexx.client.gui.gpuui.framework.UiDisplayList;
import interstellarTikiTiks.apexx.client.module.hooks.StudioProvider;
import interstellarTikiTiks.apexx.client.util.ModuleAccess;

/**
 * Loader shell for the Macros and Scripts tabs. The editors themselves are Premium and live in the payload
 * ({@link StudioProvider}). Free sessions never reach this tab: the menu hides its nav entries entirely
 * ({@link interstellarTikiTiks.apexx.client.util.PremiumGate}), so without a panel it draws nothing.
 */
public final class StudioTab {

    public enum Kind { MACROS, SCRIPTS }

    private final Kind kind;
    /** GUI preview only: a panel drawn regardless of the account. */
    private StudioProvider.Panel previewPanel;

    public StudioTab(Kind kind) {
        this.kind = kind;
    }

    /** The payload's panel when this account may use it, else null. */
    private StudioProvider.Panel panel() {
        if (previewPanel != null) return previewPanel;
        StudioProvider studio;
        try {
            studio = ModuleAccess.getHook(StudioProvider.class);
        } catch (Throwable t) {
            return null;
        }
        if (studio == null || !studio.studioAvailable()) return null;
        return kind == Kind.MACROS ? studio.macroPanel() : studio.scriptPanel();
    }

    /** GUI preview: draw this panel instead of resolving the studio. */
    public void previewWith(StudioProvider.Panel panel) {
        this.previewPanel = panel;
    }

    public void refresh() {
        StudioProvider.Panel p = panel();
        if (p != null) p.refresh();
    }

    public void flush() {
        StudioProvider.Panel p = panel();
        if (p != null) p.flush();
    }

    public boolean scroll(double mx, double my, double amount, long now) {
        StudioProvider.Panel p = panel();
        return p != null && p.scroll(mx, my, amount, now);
    }

    public void draw(UiDisplayList.Builder b, UiHost host, float x, float y, float w, float h, float s,
                     float mx, float my) {
        StudioProvider.Panel p = panel();
        if (p != null) p.draw(b, host, x, y, w, h, s, mx, my);
    }
}
