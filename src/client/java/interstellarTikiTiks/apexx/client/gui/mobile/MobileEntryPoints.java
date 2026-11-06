package interstellarTikiTiks.apexx.client.gui.mobile;

import interstellarTikiTiks.apexx.client.ApexxAuth;
import interstellarTikiTiks.apexx.client.util.MobilePlatform;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.minecraft.client.gui.screen.TitleScreen;
import net.minecraft.client.gui.screen.GameMenuScreen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;

/**
 * A touch device has no menu key, and while a world is running a touch drives the camera instead of
 * clicking. The pause and title screens are the places a touch does reach, so each gets an "Apexx"
 * button there. (A launcher's on-screen controls can also be mapped to the menu key, Right Shift by
 * default, which opens the same screen in the world.)
 */
public final class MobileEntryPoints {
    private MobileEntryPoints() {}

    public static void register() {
        if (!MobilePlatform.isMobile()) return;
        ScreenEvents.AFTER_INIT.register((client, screen, scaledWidth, scaledHeight) -> {
            if (!(screen instanceof TitleScreen || screen instanceof GameMenuScreen)) return;
            if (!ApexxAuth.isAuthorized()) return;
            ButtonWidget open = ButtonWidget.builder(Text.literal("Apexx"), button -> client.setScreen(new MobileClickGuiScreen(screen)))
                    .dimensions(6, 6, 90, 28)
                    .build();
            Screens.getButtons(screen).add(open);
        });
    }
}
