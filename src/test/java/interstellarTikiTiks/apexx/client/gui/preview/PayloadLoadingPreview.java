package interstellarTikiTiks.apexx.client.gui.preview;

import interstellarTikiTiks.apexx.client.ApexxAuth;
import interstellarTikiTiks.apexx.client.auth.PayloadStatus;
import interstellarTikiTiks.apexx.client.gui.gpuui.framework.UiDisplayList;
import interstellarTikiTiks.apexx.client.gui.mobile.PayloadLoadingScreen;
import interstellarTikiTiks.apexx.client.util.MobilePlatform;
import net.minecraft.client.MinecraftClient;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.mockito.Mockito.*;

/** Software rendering of the actual display list; GPU effects and Minecraft runtime remain unverified. */
class PayloadLoadingPreview {
    private static void set(Object target, String name, Object value) throws Exception {
        var field = PayloadLoadingScreen.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    @Test void renderLoadingStages() throws Exception {
        if (!Boolean.getBoolean("apexx.preview")) return;
        Path output = Path.of("build/gui-preview/payload-loading");
        Files.createDirectories(output);
        try (var auth = mockStatic(ApexxAuth.class); var minecraft = mockStatic(MinecraftClient.class);
             var platform = mockStatic(MobilePlatform.class)) {
            auth.when(ApexxAuth::isAuthorized).thenReturn(true);
            minecraft.when(MinecraftClient::getInstance).thenReturn(mock(MinecraftClient.class));
            for (String stage : new String[]{"download", "initialization", "cached", "release-unavailable"}) {
                PayloadStatus.beginAttempt();
                switch (stage) {
                    case "download" -> PayloadStatus.progress(8, 24);
                    case "initialization" -> PayloadStatus.phase("Initializing modules", 83, 195, true);
                    case "cached" -> PayloadStatus.phase("Initializing modules", 142, 195, false);
                    default -> PayloadStatus.set(PayloadStatus.State.NO_MATCHING_RELEASE, "test");
                }
                var screen = new PayloadLoadingScreen(null);
                screen.width = 734;
                screen.height = 502;
                set(screen, "openedAt", System.nanoTime() - 5_000_000_000L);
                var builder = UiDisplayList.builder();
                set(screen, "list", builder);
                var draw = PayloadLoadingScreen.class.getDeclaredMethod("draw", int.class, int.class);
                draw.setAccessible(true);
                draw.invoke(screen, -1, -1);
                var raster = new SoftwareUiRasterizer(new BufferedImage(734, 502, BufferedImage.TYPE_INT_RGB), 1);
                raster.render(builder.build());
                ImageIO.write(raster.image(), "png", output.resolve(stage + ".png").toFile());
            }
        } finally { PayloadStatus.set(PayloadStatus.State.NOT_STARTED, ""); }
    }
}
