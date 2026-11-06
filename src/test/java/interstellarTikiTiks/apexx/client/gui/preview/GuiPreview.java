package interstellarTikiTiks.apexx.client.gui.preview;

import interstellarTikiTiks.apexx.client.gui.gpuui.GpuClickGuiScreen;
import interstellarTikiTiks.apexx.client.gui.gpuui.framework.UiDisplayList;
import interstellarTikiTiks.apexx.client.manager.ModuleManager;
import interstellarTikiTiks.apexx.client.module.BooleanSetting;
import interstellarTikiTiks.apexx.client.module.Category;
import interstellarTikiTiks.apexx.client.module.ModeSetting;
import interstellarTikiTiks.apexx.client.module.Module;
import interstellarTikiTiks.apexx.client.module.SliderSetting;
import interstellarTikiTiks.apexx.client.module.implementation.ClickGUIModule;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Offline GUI preview: renders the real click GUI layout code to PNGs without starting Minecraft.
 * Run: gradlew :1.21.11:test --tests "*GuiPreview*" -Dapexx.preview=true  (output: build/gui-preview).
 * Skipped in normal test runs.
 */
@Tag("preview")
class GuiPreview {

    /** One window configuration: framebuffer size and Minecraft GUI scale. */
    record Viewport(String name, int fbWidth, int fbHeight, int guiScale) {
        int guiWidth() { return (int) Math.ceil(fbWidth / (double) guiScale); }
        int guiHeight() { return (int) Math.ceil(fbHeight / (double) guiScale); }
    }

    static final List<Viewport> VIEWPORTS = List.of(
            new Viewport("1080p-gui2", 1920, 1080, 2),
            new Viewport("1080p-gui3", 1920, 1080, 3),
            new Viewport("720p-gui2", 1280, 720, 2));

    @Test
    void renderAll() throws Exception {
        if (!Boolean.getBoolean("apexx.preview")) return;
        installStubClient();
        File out = new File("build/gui-preview");
        out.mkdirs();
        for (File old : out.listFiles()) old.delete();
        List<String> notes = new ArrayList<>();
        for (Viewport vp : VIEWPORTS) {
            shoot(out, vp, "modules", s -> { }, notes);
            shoot(out, vp, "modules-inspector", s -> s.openInspector("AimAssist"), notes);
            shoot(out, vp, "all-panels", s -> { s.openInspector("AimAssist"); s.set("settingsDrawerOpen", true); }, notes);
            shoot(out, vp, "configs", s -> { s.fakeConfigs(); s.view("CONFIGS"); }, notes);
            shoot(out, vp, "hub", s -> { s.fakeConfigs(); s.view("CONFIGS"); s.fakeHub(); }, notes);
            shoot(out, vp, "people", s -> { s.view("FRIENDS"); s.fakePeople(); }, notes);
            shoot(out, vp, "macros", s -> { s.view("MACROS"); s.fakeMacros(); }, notes);
            shoot(out, vp, "scripts", s -> { s.view("SCRIPTS"); s.fakeScripts(0, false); }, notes);
            shoot(out, vp, "scripts-problem-api", s -> { s.view("SCRIPTS"); s.fakeScripts(1, true); }, notes);
            shoot(out, vp, "resized", s -> { s.setStatic("windowW", 760f); s.setStatic("windowH", 470f); }, notes);
            shoot(out, vp, "drawer-client", s -> { s.set("settingsDrawerOpen", true); s.setEnum("settingsTab", "CLIENT_CONTROLS"); }, notes);
            shoot(out, vp, "drawer-appearance", s -> { s.set("settingsDrawerOpen", true); s.setEnum("settingsTab", "APPEARANCE"); }, notes);
            shoot(out, vp, "drawer-binds", s -> { s.fakeConfigs(); s.set("settingsDrawerOpen", true); s.setEnum("settingsTab", "CONFIG_BINDS"); }, notes);
        }
        System.out.println("[gui-preview] wrote " + out.getAbsolutePath());
        notes.stream().distinct().forEach(n -> System.out.println("[gui-preview] approx: " + n));
    }

    interface Setup { void apply(PreviewScreen screen) throws Exception; }

    /**
     * vanilla Screen's constructor reads MinecraftClient.getInstance(); give it an inert mock. Every
     * accessor returns null, so game-only paths (resource manager, window) fall back or skip.
     */
    static void installStubClient() throws Exception {
        if (net.minecraft.client.MinecraftClient.getInstance() != null) return;
        var client = org.mockito.Mockito.mock(net.minecraft.client.MinecraftClient.class);
        for (Field f : net.minecraft.client.MinecraftClient.class.getDeclaredFields()) {
            if (java.lang.reflect.Modifier.isStatic(f.getModifiers())
                    && f.getType() == net.minecraft.client.MinecraftClient.class) {
                f.setAccessible(true);
                f.set(null, client);
                return;
            }
        }
        throw new IllegalStateException("MinecraftClient instance field not found");
    }

    private static void shoot(File dir, Viewport vp, String name, Setup setup, List<String> notes) throws Exception {
        PreviewScreen screen = new PreviewScreen(FakeModules.build());
        screen.guiScale = vp.guiScale();
        for (String f : new String[]{"windowW", "windowH", "windowOffX", "windowOffY"}) screen.setStatic(f, 0f);
        screen.width = vp.guiWidth();
        screen.height = vp.guiHeight();
        setup.apply(screen);
        // Step the clock so open/drawer/inspector animations settle, like a menu opened a while ago.
        UiDisplayList frame = null;
        float mx = vp.fbWidth() * 0.5f, my = vp.fbHeight() - 4f;
        for (int i = 0; i < 80; i++) {
            screen.clock += 50_000_000L;
            frame = screen.frame(mx, my);
        }
        SoftwareUiRasterizer raster = new SoftwareUiRasterizer(Backdrop.scene(vp.fbWidth(), vp.fbHeight()), vp.guiScale());
        raster.render(frame);
        ImageIO.write(raster.image(), "png", new File(dir, vp.name() + "_" + name + ".png"));
        notes.addAll(raster.approximations());
    }

    /** The real screen with a deterministic clock and fake module data. */
    static final class PreviewScreen extends GpuClickGuiScreen {
        long clock = 1_000_000_000L;
        int guiScale = 2;
        private final Map<Category, List<Module>> modules;
        private final ClickGUIModule style = new ClickGUIModule();

        PreviewScreen(Map<Category, List<Module>> modules) { this.modules = modules; }

        @Override protected long nanoTime() { return clock; }
        @Override protected float pixelsPerGuiUnit() { return guiScale; }
        @Override protected ModuleManager moduleManager() { return null; }
        @Override protected ClickGUIModule clickGuiModule() { return style; }
        // The preview renders the Premium pages too.
        @Override protected boolean studioUnlocked() { return true; }
        @Override protected boolean cosmeticsUnlocked() { return true; }
        @Override protected List<Module> modulesForCategory(Category requested) {
            return modules.getOrDefault(requested, List.of());
        }

        UiDisplayList frame(float mx, float my) { return buildFrame(mx, my); }

        void fakeConfigs() throws Exception {
            set("savedConfigs", List.of("default", "Apexx", "Mace main", "Crystal duel", "SMP kit"));
            var map = new java.util.HashMap<String, interstellarTikiTiks.apexx.client.config.hub.PrivateConfigManager.PrivateConfigInfo>();
            map.put("apexx", new interstellarTikiTiks.apexx.client.config.hub.PrivateConfigManager.PrivateConfigInfo("Apexx", "Other", true, 1L));
            map.put("mace main", new interstellarTikiTiks.apexx.client.config.hub.PrivateConfigManager.PrivateConfigInfo("Mace main", "Mace", false, 1L));
            map.put("crystal duel", new interstellarTikiTiks.apexx.client.config.hub.PrivateConfigManager.PrivateConfigInfo("Crystal duel", "Crystal", false, 1L));
            set("cloudProfiles", map);
        }

        void fakeScripts(int select, boolean api) {
            String ok = String.join("\n",
                    "# Auto swing",
                    "module \"Auto Swing\" {",
                    "  category combat",
                    "  description \"Attacks the nearest enemy\"",
                    "",
                    "  setting slider reach = 4 range 1..6 step 0.5",
                    "  setting toggle onlyWeapon = true",
                    "  var ticks = 0",
                    "",
                    "  on tick {",
                    "    let e = nearestEnemy(reach)",
                    "    if e and (not onlyWeapon or isHoldingWeapon()) {",
                    "      aimAtEntity(e, 1)",
                    "      if attackReady(0) {",
                    "        attack()",
                    "      }",
                    "    }",
                    "    ticks = ticks + 1",
                    "  }",
                    "}", "");
            String bad = String.join("\n",
                    "module \"Broken\" {",
                    "  on tick {",
                    "    let e = nearestEnemy(4)",
                    "    shootLasers(e)",
                    "  }",
                    "}", "");
            var sv = new interstellarTikiTiks.apexx.client.module.implementation.studio.script.ScriptView();
            scriptView().previewWith(sv);
            sv.preview(List.of(
                    new interstellarTikiTiks.apexx.client.module.implementation.studio.script.ScriptManager.LocalScript("Auto swing", ok, true),
                    new interstellarTikiTiks.apexx.client.module.implementation.studio.script.ScriptManager.LocalScript("Broken", bad, false),
                    new interstellarTikiTiks.apexx.client.module.implementation.studio.script.ScriptManager.LocalScript("Pearl helper", ok, false)), select, api);
        }

        void fakePeople() {
            var id = java.util.UUID.randomUUID();
            var pv = interstellarTikiTiks.apexx.client.gui.gpuui.kit.PeopleView.Person.class;
            java.util.function.BiFunction<String, int[], interstellarTikiTiks.apexx.client.gui.gpuui.kit.PeopleView.Person> mk =
                    (n, f) -> new interstellarTikiTiks.apexx.client.gui.gpuui.kit.PeopleView.Person(n, java.util.UUID.nameUUIDFromBytes(n.getBytes()), f[0] == 1, f[1] == 1);
            screenPeople(java.util.List.of(mk.apply("Swight", new int[]{1, 0}), mk.apply("Voyeo", new int[]{1, 0}), mk.apply("DreadZ", new int[]{0, 1}), mk.apply("Marlow", new int[]{0, 1})),
                    java.util.List.of(mk.apply("Intel", new int[]{0, 0}), mk.apply("Swight", new int[]{1, 0}), mk.apply("Orbitorn", new int[]{0, 0}), mk.apply("Xicz", new int[]{0, 1})));
        }

        private void screenPeople(java.util.List<interstellarTikiTiks.apexx.client.gui.gpuui.kit.PeopleView.Person> saved,
                                  java.util.List<interstellarTikiTiks.apexx.client.gui.gpuui.kit.PeopleView.Person> nearby) {
            peopleView().preview(saved, nearby);
        }

        void fakeMacros() {
            var list = new ArrayList<interstellarTikiTiks.apexx.client.module.implementation.studio.macro.MacroDefinition>();
            String[] names = {"Pearl catch", "Totem swap", "Anchor chain", "Shield break"};
            for (int i = 0; i < names.length; i++) {
                var m = new interstellarTikiTiks.apexx.client.module.implementation.studio.macro.MacroDefinition();
                m.setId("m" + i); m.setName(names[i]); m.setKeyCode(70 + i); m.setEnabled(i != 2);
                var st = m.getStep(0);
                st.setItemId("minecraft:ender_pearl");
                if (i == 0) { m.addStepAfter(0); m.getStep(1).setItemId("minecraft:wind_charge"); m.addStepAfter(1); }
                list.add(m);
            }
            var mv = new interstellarTikiTiks.apexx.client.module.implementation.studio.macro.MacroView();
            mv.preview(list, 0);
            macroView().previewWith(mv);
        }

        void fakeHub() {
            var list = new ArrayList<interstellarTikiTiks.apexx.client.config.hub.RemoteConfig>();
            String[][] rows = {
                    {"Test_1", "theprobeast", "Other", "Smooth legit aim with low reach and human click timing."},
                    {"Mace Dive Pro", "Voyeo", "Mace", "Wind charge catch into density swap. Tuned for 1.21.11 practice servers with strict validation enabled."},
                    {"Crystal Duel", "Marlow", "Crystal", "Hit crystal and anchor timings with safe totem handling."},
                    {"SMP Kit", "Swight", "SMP", ""},
                    {"Cart PvP", "Intel", "Cart", "Rail and TNT cart reactions."}};
            for (int i = 0; i < rows.length; i++) {
                var c = new interstellarTikiTiks.apexx.client.config.hub.RemoteConfig();
                c.id = "id" + i; c.name = rows[i][0]; c.author = rows[i][1]; c.gameMode = rows[i][2]; c.description = rows[i][3];
                c.owned = i == 0 || i == 3; c.isPublic = i != 3;
                list.add(c);
            }
            configsView().showCommunity(list, list.get(1));
        }

        void setStatic(String field, float value) throws Exception {
            Field f = GpuClickGuiScreen.class.getDeclaredField(field);
            f.setAccessible(true);
            f.setFloat(null, value);
        }

        void set(String field, Object value) throws Exception {
            Field f = GpuClickGuiScreen.class.getDeclaredField(field);
            f.setAccessible(true);
            f.set(this, value);
        }

        void view(String name) throws Exception {
            setEnum("view", name);
        }

        @SuppressWarnings({"unchecked", "rawtypes"})
        void setEnum(String field, String name) throws Exception {
            Field f = GpuClickGuiScreen.class.getDeclaredField(field);
            f.setAccessible(true);
            f.set(this, Enum.valueOf((Class) f.getType(), name));
        }

        void openInspector(String moduleName) throws Exception {
            Module target = modules.values().stream().flatMap(List::stream)
                    .filter(m -> m.getName().equals(moduleName)).findFirst().orElseThrow();
            Method m = GpuClickGuiScreen.class.getDeclaredMethod("toggleModuleInspector", Module.class);
            m.setAccessible(true);
            m.invoke(this, target);
        }
    }

    /** Realistic module names, descriptions and settings for the catalog and inspector. */
    static final class FakeModules {
        static Map<Category, List<Module>> build() throws Exception {
            Map<Category, List<Module>> map = new EnumMap<>(Category.class);
            add(map, Category.COMBAT, true, "AimAssist", "Helps your crosshair onto targets");
            add(map, Category.COMBAT, false, "AutoCrystal", "Automatically places & breaks crystals with authentic client clicks");
            add(map, Category.COMBAT, true, "AutoTotem", "Keeps a totem in your offhand");
            add(map, Category.COMBAT, false, "AutoMace", "Times mace smashes during a fall");
            add(map, Category.COMBAT, false, "AutoHeal", "Uses healing items when health drops below a threshold");
            add(map, Category.COMBAT, false, "SmartWTap", "After a landed hit, briefly breaks your sprint so the next hit knocks back again.");
            add(map, Category.COMBAT, false, "AutoElytra", "Swaps your elytra for a chestplate on the mace-dive window, then re-equips the wing right after.");
            add(map, Category.COMBAT, false, "TriggerBot", "Attacks when your crosshair is on a target");
            add(map, Category.COMBAT, false, "Backtrack", "Delays incoming entity updates");
            add(map, Category.MOVEMENT, true, "Sprint", "Keeps you sprinting");
            add(map, Category.MOVEMENT, false, "NoSlow", "Removes item-use slowdown");
            add(map, Category.RENDER, true, "ESP", "Highlights players through walls");
            add(map, Category.RENDER, false, "Radar", "Shows nearby players on a minimap");
            add(map, Category.PLAYER, false, "AutoArmor", "Equips the best armor in your inventory");
            add(map, Category.MISC, false, "AntiBot", "Filters server-side bots from targeting");
            return map;
        }

        private static void add(Map<Category, List<Module>> map, Category cat, boolean on, String name, String desc)
                throws Exception {
            Module m = new Module(name, desc, cat) {
                {
                    addSetting(new ModeSetting("Aim Mode", "Normal", "Sensitivity"));
                    addSetting(new SliderSetting("Strength", 55, 0, 100, 0));
                    addSetting(new SliderSetting("Smoothness", 60, 0, 100, 0));
                    addSetting(new SliderSetting("Tracking", 70, 0, 100, 0));
                    addSetting(new BooleanSetting("Detach", true));
                    addSetting(new SliderSetting("Detach Sensitivity", 55, 0, 100, 0));
                    addSetting(new SliderSetting("Re-Engage Time", 300, 0, 1000, 0));
                }
            };
            Field enabled = Module.class.getDeclaredField("enabled");
            enabled.setAccessible(true);
            enabled.set(m, on);
            map.computeIfAbsent(cat, c -> new ArrayList<>()).add(m);
        }
    }

    /** A blocky, sky-and-ground stand-in for the world behind the menu. */
    static final class Backdrop {
        static BufferedImage scene(int w, int h) {
            BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    float fy = y / (float) h;
                    int cell = ((x / 48) * 31 + (y / 48) * 17) % 7;
                    int rgb;
                    if (fy < 0.42f) {
                        float t = fy / 0.42f;
                        rgb = mix(0x6f9fd8, 0xb7d3ee, t);
                    } else if (fy < 0.5f) {
                        rgb = mix(0x3f6b2e, 0x2f5523, cell / 7f);
                    } else {
                        rgb = mix(0x4a3826, 0x6a5238, cell / 7f);
                    }
                    if (x % 48 == 0 || y % 48 == 0) rgb = mix(rgb, 0x000000, 0.15f);
                    img.setRGB(x, y, rgb);
                }
            }
            return img;
        }

        private static int mix(int a, int b, float t) {
            int r = Math.round(((a >> 16) & 255) + (((b >> 16) & 255) - ((a >> 16) & 255)) * t);
            int g = Math.round(((a >> 8) & 255) + (((b >> 8) & 255) - ((a >> 8) & 255)) * t);
            int bl = Math.round((a & 255) + ((b & 255) - (a & 255)) * t);
            return (r << 16) | (g << 8) | bl;
        }
    }
}
