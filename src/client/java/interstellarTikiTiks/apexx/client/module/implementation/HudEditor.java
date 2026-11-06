package interstellarTikiTiks.apexx.client.module.implementation;

import interstellarTikiTiks.apexx.client.gui.new_ui.HudEditorScreen;
import interstellarTikiTiks.apexx.client.module.Category;
import interstellarTikiTiks.apexx.client.module.Module;
import net.minecraft.client.gui.screen.Screen;

/**
 * Opens the HUD editor. The desktop menu has a "HUD Editor" button in its top bar, but the touch menu
 * (mobile launchers) has none, so this one-shot entry in the Client tab is how mobile players reach it.
 * It also works on desktop, from the menu or on a keybind. It switches itself straight back off; Done in
 * the editor returns to the screen it was opened from (the menu, or the game).
 */
public final class HudEditor extends Module {

    public HudEditor() {
        super("HUD Editor", "Opens the HUD editor: drag widgets where you want them, scale them, save and load layouts, "
                + "and align or spread several at once. Use it from the Client tab on mobile, or bind a key. "
                + "It turns itself off right away; Done in the editor brings you back.", Category.CLIENT);
    }

    @Override
    public void onEnable() {
        setEnabled(false);
        if (mc == null) return;
        Screen current = mc.currentScreen;
        if (current instanceof HudEditorScreen) return;
        // Only from the menu or in a world; never while a config is being applied at startup or sign-in.
        boolean fromMenu = interstellarTikiTiks.apexx.client.gui.ClickGuiRouter.isMenu(current);
        if (!fromMenu && mc.world == null) return;
        if (interstellarTikiTiks.apexx.client.config.CloudConfigSync.isApplying()) return;
        // Deferred to the next client task so the screen switch never happens inside the menu's own click handler.
        mc.execute(() -> {
            if (mc.currentScreen instanceof HudEditorScreen) return;
            mc.setScreen(new HudEditorScreen(mc.currentScreen));
        });
    }
}
