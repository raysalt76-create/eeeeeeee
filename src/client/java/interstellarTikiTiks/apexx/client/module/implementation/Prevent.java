package interstellarTikiTiks.apexx.client.module.implementation;

import interstellarTikiTiks.apexx.client.module.BooleanSetting;
import interstellarTikiTiks.apexx.client.module.Category;
import interstellarTikiTiks.apexx.client.module.Module;
import interstellarTikiTiks.apexx.client.module.hooks.PreventStateProvider;
import interstellarTikiTiks.apexx.client.module.SliderSetting;

public class Prevent extends Module implements PreventStateProvider {

    private final BooleanSetting preventEscape = new BooleanSetting("Prevent Escape", false)
            .setDescription("Menus only close when you press Escape twice within Double Click Time, so a stray Escape mid-fight never closes your inventory.");
    private final SliderSetting doubleClickTime = new SliderSetting("Double Click Time", 300, 100, 1000, 0)
            .setDescription("How quickly, in milliseconds, the second Escape must follow the first to close a menu.")
            .setDependsOn("Prevent Escape");

    private final BooleanSetting tabOut = new BooleanSetting("TabOut", false)
            .setDescription("Keeps the game acting as if focused when the window loses focus (no pause menu, no released keys), so an accidental alt-tab doesn't drop your inputs.");

    private long lastEscapePress = 0;
    private boolean allowNextEscape = false;

    public Prevent() {
        super("Prevent", "Stops accidental menu closes and tab-outs from costing you a fight: optionally require a double Escape to close menus, and keep the game focused when the window loses focus.", Category.PLAYER);
        addSetting(preventEscape);
        addSetting(doubleClickTime);
        addSetting(tabOut);
    }

    public boolean shouldPreventEscape() {
        if (!isEnabled() || !preventEscape.isEnabled()) {
            return false;
        }

        long currentTime = System.currentTimeMillis();
        long timeSinceLastPress = currentTime - lastEscapePress;

        if (timeSinceLastPress <= (long) doubleClickTime.getPreciseValue()) {
            allowNextEscape = true;
            lastEscapePress = 0;
            return false;
        }

        lastEscapePress = currentTime;
        allowNextEscape = false;
        return true;
    }

    public boolean shouldPreventTabOut() {
        return isEnabled() && tabOut.isEnabled();
    }

    @Override
    public void onDisable() {
        lastEscapePress = 0;
        allowNextEscape = false;
    }

    @Override
    protected void onRuntimeStateReleased() {
        lastEscapePress = 0;
        allowNextEscape = false;
    }
}
