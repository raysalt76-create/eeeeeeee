package interstellarTikiTiks.apexx.client.gui.preview;

import interstellarTikiTiks.apexx.client.gui.gpuui.OrbitGpuClickGuiScreen;
import interstellarTikiTiks.apexx.client.gui.gpuui.framework.UiDisplayList;
import interstellarTikiTiks.apexx.client.manager.ModuleManager;
import interstellarTikiTiks.apexx.client.module.Category;
import interstellarTikiTiks.apexx.client.module.Module;
import interstellarTikiTiks.apexx.client.module.implementation.ClickGUIModule;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.io.File;
import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;

/**
 * Offline preview of the Orbit menu layout. Run: gradlew :1.21.11:test --tests "*OrbitPreview*" -Dapexx.preview=true
 * (output: build/gui-preview/*_orbit_*.png). Skipped in normal test runs.
 */
@Tag("preview")
class OrbitPreview {
    @Test
    void renderOrbit() throws Exception {
        if (!Boolean.getBoolean("apexx.preview")) return;
        GuiPreview.installStubClient();
        File out = new File("build/gui-preview");
        out.mkdirs();
        for (GuiPreview.Viewport vp : GuiPreview.VIEWPORTS) {
            shoot(out, vp, "orbit_idle", s -> { }, 0.35f, 0.5f);
            shoot(out, vp, "orbit_settings", s -> s.set("selected", s.find("AimAssist")), 0.52f, 0.6f);
            shoot(out, vp, "orbit_movement", s -> s.set("category", Category.MOVEMENT), 0.6f, 0.4f);
            shoot(out, vp, "orbit_search", s -> { s.set("search", "au"); s.set("searchFocused", true); }, 0.4f, 0.5f);
        }
    }

    interface Setup { void apply(PreviewScreen screen) throws Exception; }

    private static void shoot(File dir, GuiPreview.Viewport vp, String name, Setup setup, float mxFrac, float myFrac) throws Exception {
        PreviewScreen screen = new PreviewScreen(GuiPreview.FakeModules.build());
        screen.width = vp.guiWidth();
        screen.height = vp.guiHeight();
        setup.apply(screen);
        UiDisplayList frame = null;
        int mx = Math.round(vp.fbWidth() * mxFrac), my = Math.round(vp.fbHeight() * myFrac);
        screen.clock = 2_000_000_000L;
        for (int i = 0; i < 90; i++) {
            screen.clock += 50_000_000L;
            frame = screen.frame(mx, my, vp.fbWidth(), vp.fbHeight());
        }
        SoftwareUiRasterizer raster = new SoftwareUiRasterizer(GuiPreview.Backdrop.scene(vp.fbWidth(), vp.fbHeight()), vp.guiScale());
        raster.render(frame);
        ImageIO.write(raster.image(), "png", new File(dir, vp.name() + "_" + name + ".png"));
    }

    static final class PreviewScreen extends OrbitGpuClickGuiScreen {
        long clock = 1_000_000_000L;
        private final Map<Category, List<Module>> modules;
        private final ClickGUIModule style = new ClickGUIModule();

        PreviewScreen(Map<Category, List<Module>> modules) { this.modules = modules; }

        @Override protected long nanoTime() { return clock; }
        @Override protected float pixelsPerGuiUnit() { return 2f; }
        @Override protected ModuleManager moduleManager() { return null; }
        @Override protected ClickGUIModule clickGuiModule() { return style; }
        @Override protected List<Module> modulesForCategory(Category requested) {
            return modules.getOrDefault(requested, List.of());
        }

        UiDisplayList frame(int mx, int my, float sw, float sh) { return orbitFrame(mx, my, sw, sh); }

        Module find(String name) {
            return modules.values().stream().flatMap(List::stream).filter(m -> m.getName().equals(name)).findFirst().orElseThrow();
        }

        void set(String field, Object value) throws Exception {
            Field f = OrbitGpuClickGuiScreen.class.getDeclaredField(field);
            f.setAccessible(true);
            if (value instanceof Boolean v && f.getType() == boolean.class) f.setBoolean(this, v);
            else f.set(this, value);
        }
    }
}
