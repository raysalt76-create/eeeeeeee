package interstellarTikiTiks.apexx.client.gui;

import interstellarTikiTiks.apexx.client.gui.gpuui.GpuClickGuiScreen;
import interstellarTikiTiks.apexx.client.gui.gpuui.OrbitGpuClickGuiScreen;
import interstellarTikiTiks.apexx.client.module.implementation.ClickGUIModule;
import interstellarTikiTiks.apexx.client.gui.mobile.MobileClickGuiScreen;
import interstellarTikiTiks.apexx.client.util.MobilePlatform;
import net.minecraft.client.gui.screen.Screen;

/** Picks the menu for the platform: the desktop GPU menu, or the touch menu under an Android launcher. */
public final class ClickGuiRouter {
    private ClickGuiRouter() {}

    /**
     * True when the open menu already received (and so handled) this key press, is capturing a bind, or is
     * taking text input. The tick-edge close paths in MinecraftClientMixin must not also close the menu then.
     */
    public static boolean menuOwnsKey(Screen screen, int key) {
        if (screen instanceof GpuClickGuiScreen g) {
            return g.isCapturingKeybind() || g.capturedKeyRecently(key, 500L) || g.sawKeyRecently(key, 300L);
        }
        if (screen instanceof MobileClickGuiScreen m) return m.ownsKey(key);
        return false;
    }

    public static Screen create() {
        return create(null);
    }

    public static Screen create(Screen parent) {
        if (!interstellarTikiTiks.apexx.client.Apexx.isMenuReady()) {
            return new interstellarTikiTiks.apexx.client.gui.mobile.PayloadLoadingScreen(parent);
        }
        if (interstellarTikiTiks.apexx.client.util.PlatformPreset.touchUi()) return new MobileClickGuiScreen(parent);
        boolean orbit = ClickGUIModule.menuLayout != null && ClickGUIModule.menuLayout.is("Orbit");
        GpuClickGuiScreen menu = orbit ? new OrbitGpuClickGuiScreen() : new GpuClickGuiScreen();
        return menu.withReturnScreen(parent);
    }

    /**
     * Screens the GUI key may open the menu over, besides the game itself: the title screen, multiplayer and
     * options pages, and the connecting / loading / downloading-terrain screens. The menu opens with that screen as
     * its parent and returns to it on close. Never while a text field on it has focus (typing a server address).
     */
    public static boolean canOpenOver(Screen screen) {
        if (screen == null || isMenu(screen)) return false;
        if (screen instanceof net.minecraft.client.gui.screen.ChatScreen) return false;
        // The vanilla Controls page is waiting for a key to bind; the GUI key must reach it, not open the menu.
        if (screen instanceof net.minecraft.client.gui.screen.option.KeybindsScreen) return false;
        if (screen.getFocused() instanceof net.minecraft.client.gui.widget.TextFieldWidget) return false;
        if (keepsTicking(screen)) {
            // Only the desktop menu keeps a covered loading screen ticking (tickCoveredScreen); the touch menu and
            // the payload-loading placeholder do not, and covering the screen without its tick would stall a join.
            return interstellarTikiTiks.apexx.client.Apexx.isMenuReady()
                    && !interstellarTikiTiks.apexx.client.util.PlatformPreset.touchUi();
        }
        return screen instanceof net.minecraft.client.gui.screen.TitleScreen
                || screen instanceof net.minecraft.client.gui.screen.multiplayer.MultiplayerScreen
                || screen instanceof net.minecraft.client.gui.screen.option.OptionsScreen
                || screen instanceof net.minecraft.client.gui.screen.option.GameOptionsScreen;
    }

    /**
     * Loading-type screens whose own tick drives the connection or world load. While the menu covers one, the
     * menu ticks it so the join keeps going; when that screen moves on (joined, failed), it replaces the menu.
     */
    public static boolean keepsTicking(Screen screen) {
        // (1.21.11 folded the old downloading-terrain screen into LevelLoadingScreen.)
        return screen instanceof net.minecraft.client.gui.screen.multiplayer.ConnectScreen
                || screen instanceof net.minecraft.client.gui.screen.world.LevelLoadingScreen
                || screen instanceof net.minecraft.client.gui.screen.ProgressScreen
                || screen instanceof net.minecraft.client.gui.screen.MessageScreen;
    }

    /**
     * Client tick hook: while the menu covers a loading screen, tick that screen as vanilla would have. Runs on
     * the client thread at tick head. Any screen change it causes (joined, disconnected) simply replaces the menu.
     */
    public static void tickCoveredScreen(net.minecraft.client.MinecraftClient client) {
        if (client == null || !(client.currentScreen instanceof GpuClickGuiScreen menu)) return;
        Screen covered = menu.returnScreen();
        if (covered == null || !keepsTicking(covered)) return;
        try {
            covered.tick();
        } catch (Throwable ignored) {
            // The covered screen's own failure must not take the menu down; vanilla would show it next tick.
        }
    }

    /** A vanilla toast: unlike Apexx notifications (drawn by the in-game HUD) it also shows on the title screen. */
    public static void toast(String title, String text) {
        try {
            net.minecraft.client.MinecraftClient mc = net.minecraft.client.MinecraftClient.getInstance();
            if (mc == null) return;
            net.minecraft.client.toast.SystemToast.show(mc.getToastManager(),
                    net.minecraft.client.toast.SystemToast.Type.PERIODIC_NOTIFICATION,
                    net.minecraft.text.Text.literal(title), net.minecraft.text.Text.literal(text));
        } catch (Throwable ignored) {
            // best effort
        }
    }

    /** True for either menu, so hooks that react to "the menu is open" cover both. */
    public static boolean isMenu(Screen screen) {
        return screen instanceof GpuClickGuiScreen || screen instanceof MobileClickGuiScreen;
    }
}
