package interstellarTikiTiks.apexx.client.module.implementation;

import interstellarTikiTiks.apexx.client.ApexxAuth;
import interstellarTikiTiks.apexx.client.module.BooleanSetting;
import interstellarTikiTiks.apexx.client.module.Category;
import interstellarTikiTiks.apexx.client.module.ColorSetting;
import interstellarTikiTiks.apexx.client.module.ModeSetting;
import interstellarTikiTiks.apexx.client.module.Module;
import interstellarTikiTiks.apexx.client.module.Setting;
import interstellarTikiTiks.apexx.client.module.SliderSetting;
import interstellarTikiTiks.apexx.client.module.StringSetting;

import java.awt.Color;
import java.util.HashMap;
import java.util.Locale;
import interstellarTikiTiks.apexx.client.gui.gpuui.framework.UiThemeMaterial;
import java.util.Map;

public class ClickGUIModule extends Module implements interstellarTikiTiks.apexx.client.module.hooks.SsProofProvider {

    public static ModeSetting theme;
    /** Mist layer: None or the classic vapour. The other atmospheres stack on top of it. */
    public static ModeSetting backgroundOverlay;
    /** Material behind the menu (Interstellar's surfaces): Theme keeps the theme's own surface. */
    public static ModeSetting background;
    public static SliderSetting surfaceDetail;
    public static SliderSetting surfaceGrain;
    public static SliderSetting surfaceMotion;
    public static BooleanSetting atmWater;
    public static BooleanSetting atmAurora;
    public static BooleanSetting atmStarfield;
    public static SliderSetting auroraOpacity;
    public static SliderSetting starfieldOpacity;
    // Flowing-liquid "Water" atmosphere (shown only while Water is on).
    public static SliderSetting waterColours;
    public static ColorSetting waterColour1;
    public static ColorSetting waterColour2;
    public static ColorSetting waterColour3;
    public static SliderSetting waterOpacity;
    public static SliderSetting waterFlowSpeed;
    public static BooleanSetting waterMouseRipple;
    // Water dye simulation (WebGL-fluid port) tuning.
    public static ModeSetting waterQuality;
    public static ModeSetting waterColourMode;
    public static SliderSetting waterCurl;
    public static SliderSetting waterSplatRadius;
    public static SliderSetting waterSplatForce;
    public static SliderSetting waterDyeDissipation;
    public static SliderSetting waterVelocityDissipation;
    public static SliderSetting waterPressureIterations;
    public static BooleanSetting waterShading;
    public static BooleanSetting waterBloom;
    public static BooleanSetting waterRandomSplats;
    public static BooleanSetting gradientMenus;
    public static BooleanSetting customHighlight;
    public static BooleanSetting highlightGradient;
    public static ColorSetting highlightColor1;
    public static ColorSetting highlightColor2;
    public static BooleanSetting blurBackground;
    public static SliderSetting blurStrength;
    public static BooleanSetting separator;
    public static BooleanSetting sidebarSeparator;
    public static BooleanSetting logoSeparator;
    public static BooleanSetting hover;
    public static SliderSetting menuScale;
    /** Auto sizes the menu from the monitor's physical size and density; Manual uses screen height only. */
    public static ModeSetting menuScaleMode;
    /** Which desktop menu opens: the classic three-pane window, or the free-floating Orbit layout. */
    public static ModeSetting menuLayout;
    public static SliderSetting fontSize;
    public static ModeSetting fontFamily;
    public static SliderSetting globalCurvature;
    public static SliderSetting shaderQuality;
    public static BooleanSetting outerStroke;
    public static BooleanSetting customHeaderColour;
    public static ColorSetting customTextColor;
    public static SliderSetting hoverStrength;
    public static SliderSetting glassBlur;
    public static SliderSetting glassOpacity;
    public static SliderSetting panelOpacity;
    public static ColorSetting panelTint;
    public static ColorSetting cardFillColor;
    public static ColorSetting cardBorderColor;
    public static ColorSetting textColour;
    public static ColorSetting mutedTextColour;
    public static ColorSetting toggleOnColour;
    public static ColorSetting toggleOffColour;
    public static ColorSetting sliderFillColour;
    public static ColorSetting selectionColour;
    public static ColorSetting rimColour;
    public static BooleanSetting ssProof;
    public static BooleanSetting ssProofFailOpen;
    /** Set only while the fail-open recovery lowers SS Proof; the write guard otherwise refuses non-user disables. */
    private static boolean ssProofGuardBypass = false;
    public static BooleanSetting signOut;
    private static boolean signingOut = false;
    /** Never registered: kept only so older loader code that reads the field still links. */
    public static BooleanSetting hardwareClickSim;
    public static BooleanSetting gcdRotations;
    public static BooleanSetting subTickVariation;
    public static ModeSetting executionMode;
    // Self-Destruct options: the one set, shown by the Self-Destruct top tab and also used by the hidden
    // SelfDestruct module (SelfDestruct.runConfigured), so a key-bound trigger and the tab do the same thing.
    public static BooleanSetting sdOceanSafe;
    public static BooleanSetting sdWipeConfig;
    public static BooleanSetting sdDeleteJar;
    public static BooleanSetting sdReplaceJar;
    public static StringSetting sdDecoyUrl;
    /** Never shown in the menu: set by the Bedwars notice's "Don't show again" so it persists and syncs. */
    public static BooleanSetting bedwarsNoticeHidden;
    // Menu sounds (gui/sound/UiSounds).
    public static BooleanSetting uiSounds;
    public static ModeSetting soundPack;
    public static SliderSetting soundVolume;
    public static SliderSetting soundPitchVariation;
    public static BooleanSetting soundHover;
    public static BooleanSetting soundClick;
    public static BooleanSetting soundToggle;
    public static BooleanSetting soundOpenClose;
    public static BooleanSetting soundSlider;
    public static BooleanSetting soundKeybind;
    /** Hidden: "Theme=strength;..." so each theme keeps the Blur Strength last chosen while it was active. */
    public static StringSetting themeBlurMemory;
    private static boolean recallingThemeBlur;
    private static Map<String, ThemeColors> themes = new HashMap<>();
    private static volatile boolean themesInitialized = false;

    private static final int[] colorCacheKeys = new int[256];
    private static final Color[] colorCacheVals = new Color[256];

    public ClickGUIModule() {
        super("ClickGUI", "Theme and styling controls for the Apexx menu", Category.CLIENT);

        ensureThemesInitialized();

        setKeyCode(org.lwjgl.glfw.GLFW.GLFW_KEY_RIGHT_SHIFT);
        addSetting(new interstellarTikiTiks.apexx.client.module.implementation.KeybindSetting("GUI Key", this)
                .setDescription("Key that opens and closes this menu."));
        // The base Module adds its own "Keybind" row; it edits the same key as GUI Key, so it is hidden here.
        for (Setting existing : getSettings()) {
            if (existing instanceof interstellarTikiTiks.apexx.client.module.implementation.KeybindSetting
                    && "Keybind".equals(existing.getName())) {
                existing.setVisibility(() -> false);
            }
        }

        // One picker: the colour themes, then the material backgrounds (glass, stone, carbon, metal, sky, water),
        // which use the Apexx palette and paint their own surface behind the menu.
        java.util.List<String> themeChoices = new java.util.ArrayList<>(java.util.List.of(UiThemeMaterial.names()));
        for (interstellarTikiTiks.apexx.client.gui.gpuui.framework.UiSurface surfaceChoice
                : interstellarTikiTiks.apexx.client.gui.gpuui.framework.UiSurface.values()) {
            if (!surfaceChoice.isTheme()) themeChoices.add(surfaceChoice.displayName());
        }
        theme = new ModeSetting("Theme", themeChoices.toArray(String[]::new)) {
            @Override
            public void setCurrentMode(String modeName) {
                // Configs saved before the rename still say "Black Granite".
                super.setCurrentMode("Black Granite".equalsIgnoreCase(modeName) ? "Granite" : modeName);
            }
        };
        // Default a fresh install to the Apexx theme (config load overrides this for existing users).
        theme.setCurrentMode("Apexx");
        // Off/On rather than the old None/Mist (which read as "Mist: Mist"). Saved None/Mist/Deep Mist values,
        // and the true/false the config loader passes as None/Mist, all map onto Off/On.
        backgroundOverlay = new ModeSetting("Mist", "Off", "On") {
            @Override
            public void setCurrentMode(String modeName) {
                String mode = modeName == null ? "" : modeName.trim();
                if (mode.equalsIgnoreCase("None") || mode.equalsIgnoreCase("false")) mode = "Off";
                else if (mode.equalsIgnoreCase("Mist") || mode.equalsIgnoreCase("Deep Mist")
                        || mode.equalsIgnoreCase("true")) mode = "On";
                super.setCurrentMode(mode);
            }
        }
                .setDescription("Soft mist drifting behind the panels.");
        // Kept only so configs saved with a separate Background still load: a saved material moves into Theme.
        background = new ModeSetting("Background", interstellarTikiTiks.apexx.client.gui.gpuui.framework.UiSurface.names()) {
            @Override
            public void setCurrentMode(String modeName) {
                interstellarTikiTiks.apexx.client.gui.gpuui.framework.UiSurface saved =
                        interstellarTikiTiks.apexx.client.gui.gpuui.framework.UiSurface.byName(modeName);
                if (!saved.isTheme() && theme != null) theme.setCurrentMode(saved.displayName());
                super.setCurrentMode("Theme");
            }
        };
        background.setVisibility(() -> false);
        surfaceDetail = new SliderSetting("Surface Detail", 60, 0, 100, 0)
                .setDescription("Amount of fine structure in the background: weave pitch, veining, sheen and fracture.");
        surfaceGrain = new SliderSetting("Film Grain", 35, 0, 100, 0)
                .setDescription("Fine grain laid over the background. It also hides banding in dark gradients.");
        surfaceMotion = new SliderSetting("Surface Motion", 100, 0, 200, 0)
                .setDescription("Drift speed of the backgrounds that move: sky, water and gas. 0 freezes them.");
        surfaceDetail.setVisibility(() -> !activeSurface().isTheme());
        surfaceGrain.setVisibility(() -> !activeSurface().isTheme());
        surfaceMotion.setVisibility(() -> {
            interstellarTikiTiks.apexx.client.gui.gpuui.framework.UiSurface s = activeSurface();
            return s == interstellarTikiTiks.apexx.client.gui.gpuui.framework.UiSurface.AURORA
                    || s == interstellarTikiTiks.apexx.client.gui.gpuui.framework.UiSurface.DEEP_SEA
                    || s == interstellarTikiTiks.apexx.client.gui.gpuui.framework.UiSurface.NEBULA
                    || s == interstellarTikiTiks.apexx.client.gui.gpuui.framework.UiSurface.MESH;
        });
        // Stackable atmospheres: any combination draws at once, each as its own layer tinted by the accent.
        atmWater = new BooleanSetting("Water", false)
                .setDescription("Interactive flowing-liquid field your cursor pushes through.");
        atmAurora = new BooleanSetting("Aurora", false)
                .setDescription("Waving northern-lights curtains tinted by your accent.");
        auroraOpacity = new SliderSetting("Aurora Opacity", 60, 0, 100, 0)
                .setDependsOn("Aurora");
        auroraOpacity.setDescription("How strong the aurora is (%).");
        atmStarfield = new BooleanSetting("Starfield", false)
                .setDescription("Drifting, twinkling stars.");
        starfieldOpacity = new SliderSetting("Starfield Opacity", 70, 0, 100, 0)
                .setDependsOn("Starfield");
        starfieldOpacity.setDescription("How bright the stars are (%).");
        waterColours = new SliderSetting("Water Colors", 3, 1, 3, 0)
                .setDependsOn("Water");
        waterColours.setDescription("How many colors flow through the liquid.");
        waterColour1 = new ColorSetting("Water Color 1", new Color(32, 120, 255));
        waterColour1.setDependsOn("Water");
        waterColour2 = new ColorSetting("Water Color 2", new Color(38, 224, 198));
        waterColour2.setDependsOn("Water");
        waterColour3 = new ColorSetting("Water Color 3", new Color(168, 110, 255));
        waterColour3.setDependsOn("Water");
        waterOpacity = new SliderSetting("Water Opacity", 70, 0, 100, 0)
                .setDependsOn("Water");
        waterOpacity.setDescription("How strong the flowing liquid is (%).");
        waterFlowSpeed = new SliderSetting("Water Flow Speed", 100, 10, 300, 0)
                .setDependsOn("Water");
        waterFlowSpeed.setDescription("How fast the liquid moves (%).");
        waterMouseRipple = new BooleanSetting("Mouse Ripple", true)
                .setDependsOn("Water");
        waterMouseRipple.setDescription("Your cursor pushes the liquid aside and leaves a wake.");
        waterQuality = new ModeSetting("Water Quality", "Low", "Medium", "High").setDependsOn("Water");
        boolean mobileDefaults = false;
        try { mobileDefaults = interstellarTikiTiks.apexx.client.util.MobilePlatform.isMobile(); } catch (Throwable ignored) { }
        waterQuality.setCurrentMode(mobileDefaults ? "Low" : "High");
        waterQuality.setDescription("Simulation and dye resolution. Drops automatically if the frame rate gets low.");
        waterColourMode = new ModeSetting("Water Color Mode", "Rainbow", "My Colors") {
            @Override
            public void setCurrentMode(String modeName) {
                super.setCurrentMode("My Colours".equalsIgnoreCase(modeName) ? "My Colors" : modeName);
            }
        }.setDependsOn("Water");
        waterColourMode.setDescription("Rainbow splats, or the water colors you picked above.");
        waterCurl = new SliderSetting("Water Curl", 30, 0, 50, 0).setDependsOn("Water");
        waterCurl.setDescription("Vorticity: how much the liquid swirls and curls.");
        waterSplatRadius = new SliderSetting("Splat Radius", 0.25, 0.05, 1.0, 2).setDependsOn("Water");
        waterSplatRadius.setDescription("Size of the dye splat your cursor leaves.");
        waterSplatForce = new SliderSetting("Splat Force", 6000, 500, 12000, 0).setDependsOn("Water");
        waterSplatForce.setDescription("How hard cursor movement pushes the liquid.");
        waterDyeDissipation = new SliderSetting("Dye Dissipation", 1.0, 0.0, 4.0, 2).setDependsOn("Water");
        waterDyeDissipation.setDescription("How fast the color fades. 0 keeps it until it is pushed away.");
        waterVelocityDissipation = new SliderSetting("Velocity Dissipation", 0.2, 0.0, 4.0, 2).setDependsOn("Water");
        waterVelocityDissipation.setDescription("How fast the motion settles.");
        waterPressureIterations = new SliderSetting("Pressure Iterations", 20, 5, 50, 0).setDependsOn("Water");
        waterPressureIterations.setDescription("Solver steps per frame. More is smoother and slower.");
        waterShading = new BooleanSetting("Water Shading", true).setDependsOn("Water");
        waterShading.setDescription("Lights the dye like a surface, from its gradient.");
        waterBloom = new BooleanSetting("Water Bloom", true).setDependsOn("Water");
        waterBloom.setDescription("Glow around the brightest dye.");
        waterRandomSplats = new BooleanSetting("Random Splats", true).setDependsOn("Water");
        waterRandomSplats.setDescription("Drops a burst of color when the menu opens.");
        gradientMenus = new BooleanSetting("Gradient Menus", false)
                .setDescription("Washes every panel with a diagonal gradient from the accent to its second color.");
        customHighlight = new BooleanSetting("Custom Highlight", false)
                .setDescription("Use your own accent color instead of the theme's.");
        highlightGradient = new BooleanSetting("Highlight Gradient", true)
                .setDescription("Accent elements (toggles, sliders, the selected option, enabled bars) blend from Highlight Color 1 to Highlight Color 2.");
        highlightColor1 = new ColorSetting("Highlight Color 1", new Color(76, 132, 255, 245));
        highlightColor2 = new ColorSetting("Highlight Color 2", new Color(126, 210, 255, 245));
        // Default ON: makes the panel translucent (via body.bg-blur in the page) and cranks MC's
        // menu blur while in-game, so the backdrop is the blurred game instead of a flat themed wash.
        blurBackground = new BooleanSetting("Backdrop Blur", true)
                .setDescription("Blurs the game behind the menu. Panel Blur is the separate blur seen through the panels.");
        blurStrength = new SliderSetting("Backdrop Blur Strength", 16.0, 0.0, 30.0, 1)
                .setDependsOn("Backdrop Blur");
        blurStrength.setDescription("How strongly the game behind the menu is blurred.");
        globalCurvature = new SliderSetting("Global Curvature", 10.0, 0.0, 64.0, 1)
                .setDescription("Corner radius of every panel, card and control. 10 is the default rounding; 0 is square.");
        // Shader Quality used to be exposed here but nothing in the menu reads it, so it is no longer
        // registered (an old config's value is simply ignored). The field stays for API compatibility.
        shaderQuality = new SliderSetting("Shader Quality", 0.3, 0.0, 1.0, 2);
        outerStroke = new BooleanSetting("Outer Stroke", true)
                .setDescription("Draws the bright rim around every panel and glass surface.");
        separator = new BooleanSetting("Separator", true)
                .setDescription("Hairlines between setting rows and under section headers.");
        sidebarSeparator = new BooleanSetting("Sidebar Separator", true)
                .setDescription("Line between the category rail and the module list.");
        logoSeparator = new BooleanSetting("Logo Separator", true)
                .setDescription("Line under the header strip that holds the logo and the top navigation.");
        hover = new BooleanSetting("Hover", true)
                .setDescription("Highlight and lift what is under the pointer.");
        hoverStrength = new SliderSetting("Hover Strength", 100, 0, 200, 0)
                .setDependsOn("Hover");
        hoverStrength.setDescription("How strong hover highlights and the card lift are (%).");
        // ---- Glass ----
        // (Custom BG Color / Background Color / Background Opacity were a second tint for the same glass; Panel Tint
        // with Glass Opacity is now the only one.)
        // Defaults to the maximum: a strongly blurred backdrop keeps text readable on glass.
        glassBlur = new SliderSetting("Panel Blur", 30, 0, 30, 0)
                .setDescription("Blur of what you see through the panels themselves. Backdrop Blur is the blur behind the whole menu.");
        glassOpacity = new SliderSetting("Glass Opacity", 100, 0, 200, 0)
                .setDescription("How much the glass body is tinted (%). Lower is clearer glass, higher is more frosted.");
        panelOpacity = new SliderSetting("Panel Opacity", 100, 0, 100, 0)
                .setDescription("Opacity of the fills laid over the panels: sidebar, list, cards (%).");
        // ---- Colours: each follows the active theme until you change it (right-click resets). ----
        panelTint = colour("Panel Tint", new Color(5, 8, 12, 0x44), "Color of the glass panels; its alpha is how much of the scene it covers. Glass Opacity scales it.");
        cardFillColor = colour("Card Fill", new Color(0, 0, 0, 0x47), "Fill of module cards and setting cards.");
        cardBorderColor = colour("Card Border", new Color(255, 255, 255, 0x29), "Hairline around cards.");
        textColour = colour("Text Color", new Color(0xf2, 0xf4, 0xf5), "Main text color.");
        mutedTextColour = colour("Muted Text Color", new Color(0x9c, 0xa1, 0xa6), "Descriptions, hints and inactive labels.");
        toggleOnColour = colour("Toggle On", new Color(0x26, 0xe0, 0xc6), "Track of a switch that is on.");
        toggleOffColour = colour("Toggle Off", new Color(0x2a, 0x2c, 0x2f), "Track of a switch that is off.");
        sliderFillColour = colour("Slider Fill", new Color(0x26, 0xe0, 0xc6), "Filled part of sliders.");
        selectionColour = colour("Selection Color", new Color(255, 255, 255), "Hover highlights, the selected card ring and the keyboard focus ring.");
        rimColour = colour("Rim Color", new Color(255, 255, 255, 0x30), "Rim and sheen of panels and glass.");
        menuLayout = new ModeSetting("Menu Layout", "Classic", "Orbit")
                .setDescription("Classic is the single window. Orbit spreads the menu into floating panels: a category dial, loose module tiles and a settings stack next to the selected module. Applies the next time you open the menu.");
        menuScaleMode = new ModeSetting("Menu Scale Mode", "Auto", "Manual")
                .setDescription("Auto keeps the menu the same size on any monitor: it reads your screen's physical size and pixel density (1080p, 1440p, 4K, laptops). Manual sizes it from the screen height only. Menu Scale fine-tunes either one.");
        menuScale = new SliderSetting("Menu Scale", 150, 60, 200, 0)
                .setDescription("Size of the menu as a percentage of the automatic size for your screen. You can also drag the window edges to resize it.");
        fontSize = new SliderSetting("Font Size", 100, 80, 130, 0)
                .setDescription("Text size everywhere in the menu, independent of Menu Scale -- useful if the menu is sized right but the text is hard to read.");
        fontFamily = new ModeSetting("Font Family", "Default", "Minecraft", "Serif", "Sans-Serif")
                .setDescription("Typeface used everywhere in the menu.");
        customHeaderColour = new BooleanSetting("Custom Header Color", false)
                .setDescription("Draw titles and headings in the Custom Text color.");
        customTextColor = new ColorSetting("Custom Text", new Color(238, 238, 238))
                .setDescription("Color of titles and headings while Custom Header Color is on. Other text uses Text Color.");
        addSetting(theme);
        addSetting(backgroundOverlay);
        addSetting(background);
        addSetting(surfaceDetail);
        addSetting(surfaceGrain);
        addSetting(surfaceMotion);
        addSetting(atmWater);
        addSetting(atmAurora);
        addSetting(auroraOpacity);
        addSetting(atmStarfield);
        addSetting(starfieldOpacity);
        addSetting(waterColours);
        addSetting(waterColour1);
        addSetting(waterColour2);
        addSetting(waterColour3);
        addSetting(waterOpacity);
        addSetting(waterFlowSpeed);
        addSetting(waterMouseRipple);
        addSetting(waterQuality);
        addSetting(waterColourMode);
        addSetting(waterCurl);
        addSetting(waterSplatRadius);
        addSetting(waterSplatForce);
        addSetting(waterDyeDissipation);
        addSetting(waterVelocityDissipation);
        addSetting(waterPressureIterations);
        addSetting(waterShading);
        addSetting(waterBloom);
        addSetting(waterRandomSplats);
        addSetting(gradientMenus);
        addSetting(customHighlight);
        addSetting(highlightGradient);
        addSetting(highlightColor1);
        addSetting(highlightColor2);
        addSetting(blurBackground);
        addSetting(blurStrength);
        addSetting(globalCurvature);
        addSetting(outerStroke);
        addSetting(separator);
        addSetting(sidebarSeparator);
        addSetting(logoSeparator);
        addSetting(hover);
        addSetting(hoverStrength);
        addSetting(glassBlur);
        addSetting(glassOpacity);
        addSetting(panelOpacity);
        for (ColorSetting c : new ColorSetting[] { panelTint, cardFillColor, cardBorderColor, textColour, mutedTextColour,
                toggleOnColour, toggleOffColour, sliderFillColour, selectionColour, rimColour }) addSetting(c);
        addSetting(menuLayout);
        addSetting(menuScaleMode);
        addSetting(menuScale);
        addSetting(fontSize);
        fontSize.onChange(() -> interstellarTikiTiks.apexx.client.gui.gpuui.render.GpuUiFontMetrics
                .setTextScale((float) (fontSize.getValue() / 100.0)));
        addSetting(fontFamily);
        fontFamily.onChange(() -> interstellarTikiTiks.apexx.client.gui.gpuui.render.GpuUiFontMetrics
                .setFamily(switch (fontFamily.getCurrentMode()) {
                    case "Minecraft" -> interstellarTikiTiks.apexx.client.gui.gpuui.render.GpuUiFontMetrics.Family.MINECRAFT;
                    case "Serif" -> interstellarTikiTiks.apexx.client.gui.gpuui.render.GpuUiFontMetrics.Family.SERIF;
                    case "Sans-Serif" -> interstellarTikiTiks.apexx.client.gui.gpuui.render.GpuUiFontMetrics.Family.SANS_SERIF;
                    default -> interstellarTikiTiks.apexx.client.gui.gpuui.render.GpuUiFontMetrics.Family.DEFAULT;
                }));
        addSetting(customHeaderColour);
        addSetting(customTextColor);
        // ---- Menu sounds (synthesised in memory, see gui/sound/UiSounds) ----
        uiSounds = new BooleanSetting("UI Sounds", false)
                .setDescription("Soft sounds for hovering, clicking, switches, sliders, keybinds and opening or closing the menu. Uses your Master and User Interface volume.");
        uiSounds.setSection("Sounds");
        soundPack = new ModeSetting("Sound Pack", "Creamy", "Clacky", "Soft")
                .setDescription("Creamy is a deep, muted thock. Clacky is a bright, crisp switch. Soft is a gentle pop.");
        soundVolume = new SliderSetting("Sound Volume", 60, 0, 100, 0);
        soundVolume.setDescription("Loudness of the menu sounds (%), on top of the game's volume sliders.");
        soundPitchVariation = new SliderSetting("Pitch Variation", 8, 0, 30, 0);
        soundPitchVariation.setDescription("Random pitch change per sound (%), so repeated clicks do not sound identical.");
        soundHover = new BooleanSetting("Hover Sound", true).setDescription("A quiet tick when the pointer moves onto something clickable.");
        soundClick = new BooleanSetting("Click Sound", true).setDescription("Pressing buttons, tabs and options.");
        soundToggle = new BooleanSetting("Toggle Sound", true).setDescription("Turning a module or switch on or off (rising on, falling off).");
        soundOpenClose = new BooleanSetting("Open/Close Sound", true).setDescription("Opening and closing the menu.");
        soundSlider = new BooleanSetting("Slider Sound", true).setDescription("Ticks while dragging a slider.");
        soundKeybind = new BooleanSetting("Keybind Sound", true).setDescription("Confirms a newly set keybind.");
        for (Setting s : new Setting[] { soundPack, soundVolume, soundPitchVariation, soundHover, soundClick,
                soundToggle, soundOpenClose, soundSlider, soundKeybind }) {
            s.setDependsOn("UI Sounds");
            s.setSection("Sounds");
        }
        for (Setting s : new Setting[] { uiSounds, soundPack, soundVolume, soundPitchVariation, soundHover, soundClick,
                soundToggle, soundOpenClose, soundSlider, soundKeybind }) addSetting(s);
        uiSounds.onChange(interstellarTikiTiks.apexx.client.gui.sound.UiSounds::prewarm);
        soundPack.onChange(interstellarTikiTiks.apexx.client.gui.sound.UiSounds::prewarm);
        interstellarTikiTiks.apexx.client.gui.sound.UiSounds.prewarm();
        // Blur strength remembered per theme: never shown, carried in the config like any other setting.
        themeBlurMemory = new StringSetting("Theme Blur Memory", "");
        themeBlurMemory.setVisibility(() -> false);
        addSetting(themeBlurMemory);
        blurStrength.onChange(ClickGUIModule::rememberThemeBlur);
        ssProof = new SsProofSetting();
        ssProof.setDescription("Screenshare proof mode: excludes the client interface from screen capture, OBS, Discord, and screenshots."
                + (interstellarTikiTiks.apexx.client.util.GlfwPlatform.isWayland()
                        ? " Unavailable on Wayland (no overlay window is possible there); use an X11 session." : ""));
        addSetting(ssProof);
        ssProof.onChange(ClickGUIModule::syncSsProof);
        ssProofFailOpen = new SsProofFailOpenSetting();
        ssProofFailOpen.setDescription("If the menu can't be confirmed hidden from capture 3 times in a row, turn SS Proof off and show a notice (the notice can be recorded). Off keeps the menu hidden instead.");
        addSetting(ssProofFailOpen);
        signOut = new BooleanSetting("Sign Out", false)
                .setDescription("Clears your saved account session and returns to the sign-in screen.");
        addSetting(signOut);
        signOut.onChange(() -> {
            if (signingOut || !signOut.isEnabled()) return;
            signingOut = true;
            try {
                signOut.setEnabled(false);
                ApexxAuth.signOutAndPrompt("You signed out. Sign in to continue.");
            } finally {
                signingOut = false;
            }
        });
        // ── Input-path switch (client-wide, not appearance) ──────────────────────
        executionMode = new ModeSetting("Execution Mode",
                interstellarTikiTiks.apexx.client.util.ExecutionMode.LEGIT,
                interstellarTikiTiks.apexx.client.util.ExecutionMode.DIRECT)
                .setDescription("How modules click after switching hotbar slots. Legit waits a short, varied moment after the swap before clicking. Direct clicks right after the swap, a few milliseconds later in the same tick.");
        addSetting(executionMode);
        executionMode.onChange(() ->
                interstellarTikiTiks.apexx.client.util.ExecutionMode.set(executionMode.getCurrentMode()));
        interstellarTikiTiks.apexx.client.util.ExecutionMode.set(executionMode.getCurrentMode());


        // Old public field kept for loader compatibility only; never registered or shown.
        hardwareClickSim = new BooleanSetting("Keystrokes Compatibility", false);

        // Snaps every emitted rotation to a whole number of mouse steps. A hand cannot
        // produce an angle off that lattice, so leaving this off is one of the easiest
        // ways for a detector to separate this client from a player.
        gcdRotations = new BooleanSetting("GCD Rotations", true);
        addSetting(gcdRotations);
        gcdRotations.onChange(() ->
                interstellarTikiTiks.apexx.client.util.RotationManager.setGcdQuantization(gcdRotations.isEnabled()));
        interstellarTikiTiks.apexx.client.util.RotationManager.setGcdQuantization(gcdRotations.isEnabled());

        // ── Sub-Tick Human Variation (Client-Wide Input Pacing) ─────────────
        subTickVariation = new BooleanSetting("Sub-Tick Variation", true)
                .setDescription("Adds small, uneven pauses to hotbar swaps, clicks and inventory moves, all inside the same tick. Legit mode only.");

        addSetting(subTickVariation);

        Runnable syncSubTick = () ->
                interstellarTikiTiks.apexx.client.util.combat.SubTickSequencer.setVariationEnabled(subTickVariation.isEnabled());
        subTickVariation.onChange(syncSubTick);
        syncSubTick.run();

        // ── Self-Destruct (surfaced by the Self-Destruct top tab, not the module list) ──────
        sdOceanSafe = new BooleanSetting("Ocean Safe", false)
                .setDescription("Only shuts the client off in memory. No files are deleted or changed, so the disk keeps no record of a cleanup. The options below are skipped.");
        sdWipeConfig = new BooleanSetting("Wipe Config", true)
                .setDescription("Deletes Apexx config folders, the saved sign-in and device files, and empties the game's latest.log.")
                .setDependsOn("!Ocean Safe");
        sdDeleteJar = new BooleanSetting("Delete Jar", false)
                .setDescription("Deletes the mod jar once the game closes.")
                .setDependsOn("!Ocean Safe");
        sdReplaceJar = new BooleanSetting("Replace Jar", true)
                .setDescription("If the jar is not deleted, swaps it for a harmless mod file with the same name and dates.")
                .setDependsOn("!Ocean Safe");
        sdDecoyUrl = new StringSetting("Decoy URL",
                "https://cdn.modrinth.com/data/1IjD5062/versions/mX1iknM1/continuity-3.0.1-beta.1%2B1.21.11.jar")
                .setDescription("Download link of the harmless mod jar Replace Jar puts in its place.")
                .setDependsOn("Replace Jar");
        addSetting(sdOceanSafe);
        addSetting(sdWipeConfig);
        addSetting(sdDeleteJar);
        addSetting(sdReplaceJar);
        addSetting(sdDecoyUrl);
        bedwarsNoticeHidden = new BooleanSetting("Hide Bedwars Notice", false);
        bedwarsNoticeHidden.setVisibility(() -> false);
        addSetting(bedwarsNoticeHidden);

        // Configs are cloud-only (Firestore via the website); nothing is written to the
        // user's disk, so there is no "Save Local Config" toggle.

        theme.onChange(() -> {
            recallThemeBlur();
            invalidateThemeCache();
            try {
                interstellarTikiTiks.apexx.client.theme.ThemeManager.getInstance().setCurrentTheme(theme.getCurrentMode());
            } catch (Throwable ignored) {}
        });
        customHighlight.onChange(ClickGUIModule::invalidateThemeCache);
        highlightGradient.onChange(ClickGUIModule::invalidateThemeCache);
        highlightColor1.onChange(ClickGUIModule::invalidateThemeCache);
        highlightColor2.onChange(ClickGUIModule::invalidateThemeCache);
        customHeaderColour.onChange(ClickGUIModule::invalidateThemeCache);
        customTextColor.onChange(ClickGUIModule::invalidateThemeCache);
        globalCurvature.onChange(ClickGUIModule::invalidateThemeCache);
    }

    private static ColorSetting colour(String name, Color def, String description) {
        return new ColorSetting(name, def).setDescription(description + " Follows the theme until you change it; right-click to reset.");
    }

    /** The colour the user picked, or {@code fallback} (the theme value) while the setting is untouched. */
    public static int colourOr(ColorSetting setting, int fallback) {
        return setting != null && setting.isModified() && setting.getValue() != null ? setting.getValue().getRGB() : fallback;
    }

    /** Percentage slider as a 0..max factor (100% = 1). */
    public static float percent(SliderSetting setting, float fallback) {
        return setting == null ? fallback : (float) Math.max(0.0, setting.getValue() / 100.0);
    }

    private static void ensureThemesInitialized() {
        if (themesInitialized) {
            return;
        }
        synchronized (ClickGUIModule.class) {
            if (themesInitialized) {
                return;
            }
            initializeThemes();
            themesInitialized = true;
        }
    }

    private static void initializeThemes() {
        for (UiThemeMaterial.Theme palette : UiThemeMaterial.themes()) {
            themes.put(palette.name(), buildPaletteTheme(palette));
        }
    }

    /** True when the name is one of the seven menu themes. */
    /** The material background picked in Theme, or THEME when a colour theme is selected. */
    public static interstellarTikiTiks.apexx.client.gui.gpuui.framework.UiSurface activeSurface() {
        return theme == null ? interstellarTikiTiks.apexx.client.gui.gpuui.framework.UiSurface.THEME
                : interstellarTikiTiks.apexx.client.gui.gpuui.framework.UiSurface.byName(theme.getCurrentMode());
    }

    public static boolean isMaterialTheme(String name) {
        for (UiThemeMaterial.Theme palette : UiThemeMaterial.themes()) {
            if (palette.name().equalsIgnoreCase(name)) return true;
        }
        return false;
    }

    @Override
    public int getKeyCode() {
        int key = super.getKeyCode();
        if (key == -1 || key == 0) {
            return org.lwjgl.glfw.GLFW.GLFW_KEY_RIGHT_SHIFT;
        }
        return key;
    }

    @Override
    public void onEnable() {
        // Screen routing is handled by the client; this module stores shared menu settings.
        setEnabled(false);
    }

    private static ThemeColors getCurrentTheme() {
        ensureThemesInitialized();
        String currentName = theme != null ? theme.getCurrentMode() : UiThemeMaterial.APEXX.name();
        ThemeColors result = themes.getOrDefault(currentName, themes.get(UiThemeMaterial.APEXX.name()));
        if (result == null) result = buildEmergencyTheme();
        return result;
    }

    public static String normalizeThemeName(String name) {
        if (name == null || name.isBlank()) return UiThemeMaterial.APEXX.name();
        String trimmed = name.trim();
        for (String valid : UiThemeMaterial.names()) {
            if (valid.equalsIgnoreCase(trimmed)) return valid;
        }
        // Names from before the theme rebuild map onto the closest current material.
        return switch (trimmed.toLowerCase(Locale.ROOT)) {
            case "black granite", "noir marble", "noir", "obsidian", "dark", "onyx", "silver", "onyx silver" -> "Granite";
            case "deep ocean", "ocean blue", "neon circuit", "frost", "lapis", "lapis lazuli", "blue marble" -> "Teal";
            case "solar", "sakura", "ember", "lava", "fire", "apexx fire", "rosso", "rosso levanto", "red marble" -> "Ruby";
            case "mocha", "macchiato" -> "Catppuccin";
            case "violet night", "amethyst", "dark purple" -> "Purple";
            case "forest green", "forest", "green", "verde", "verde alpi", "green marble" -> "Emerald";
            default -> UiThemeMaterial.APEXX.name();
        };
    }

    public static void invalidateThemeCache() {
        for (int i = 0; i < colorCacheVals.length; i++) {
            colorCacheVals[i] = null;
            colorCacheKeys[i] = 0;
        }
    }

    private static ThemeColors buildEmergencyTheme() {
        return new ThemeColors(
                new Color(25, 25, 25, 220),
                new Color(255, 180, 255, 200),
                new Color(215, 138, 255, 255),
                new Color(215, 138, 255, 255),
                new Color(30, 30, 30, 255),
                new Color(35, 35, 35, 255),
                new Color(215, 138, 255, 150),
                new Color(255, 255, 255, 255),
                new Color(100, 100, 100, 255),
                new Color(40, 45, 55, 140),
                new Color(255, 255, 255, 12),
                new Color(255, 255, 255, 255),
                new Color(170, 170, 170, 255),
                new Color(0, 255, 128, 255),
                new Color(255, 50, 50, 255),
                new Color(25, 25, 25, 180),
                new Color(255, 255, 255, 200),
                new Color(20, 20, 20, 230),
                new Color(215, 138, 255, 180),
                new Color(255, 255, 255, 255),
                new Color(255, 105, 180, 255),
                new Color(135, 206, 250, 255));
    }

    private static ThemeColors buildPaletteTheme(UiThemeMaterial.Theme palette) {
        Color base = withAlpha(new Color(palette.panelBase()), 220);
        Color text = withAlpha(new Color(palette.text()), 255);
        Color accent = withAlpha(new Color(palette.accent()), 255);

        Color panelBg = withAlpha(base, 220);
        Color panelGlow = withAlpha(accent, 180);
        Color panelParticles = withAlpha(accent, 210);
        Color searchBg = withAlpha(darken(base, 0.14), 170);
        Color searchFocusedBg = withAlpha(darken(base, 0.06), 208);
        Color searchBorder = withAlpha(accent, 165);
        Color moduleBg = withAlpha(darken(base, 0.18), 150);
        Color moduleHoverBg = withAlpha(darken(base, 0.10), 188);
        Color moduleDisabledText = withAlpha(blend(text, base, 0.35), 230);
        Color moduleDisabledDot = withAlpha(blend(text, base, 0.55), 200);
        Color settingsBg = withAlpha(darken(base, 0.12), 160);
        Color tooltipBg = withAlpha(darken(base, 0.20), 235);
        Color tooltipBorder = withAlpha(accent, 170);
        Color placeholder = withAlpha(blend(text, base, 0.50), 210);
        Color gradStart = brighten(accent, 0.12);
        Color gradEnd = darken(accent, 0.20);

        return new ThemeColors(
                panelBg,
                panelGlow,
                text,
                panelParticles,
                searchBg,
                searchFocusedBg,
                searchBorder,
                text,
                placeholder,
                moduleBg,
                moduleHoverBg,
                text,
                moduleDisabledText,
                accent,
                moduleDisabledDot,
                settingsBg,
                text,
                tooltipBg,
                tooltipBorder,
                text,
                gradStart,
                gradEnd);
    }

    public static Color getPanelBackground() {
        return applyBackgroundOpacity(getCurrentTheme().panelBackground);
    }

    public static Color getHeaderBackground() {
        Color panelBg = getPanelBackground();
        if (customHeaderColour == null || !customHeaderColour.isEnabled()) {
            return panelBg;
        }
        return withAlpha(darken(panelBg, 0.06), panelBg.getAlpha());
    }

    public static boolean useCustomHeaderColour() {
        return customHeaderColour != null && customHeaderColour.isEnabled();
    }

    /** Blur Strength changed: remember it for the active theme. */
    private static void rememberThemeBlur() {
        if (recallingThemeBlur || themeBlurMemory == null || theme == null || blurStrength == null) return;
        Map<String, String> memory = parseThemeBlur(themeBlurMemory.getValue());
        memory.put(theme.getCurrentMode(), String.valueOf(blurStrength.getValue()));
        StringBuilder out = new StringBuilder();
        for (Map.Entry<String, String> entry : memory.entrySet()) {
            if (out.length() > 0) out.append(';');
            out.append(entry.getKey()).append('=').append(entry.getValue());
        }
        themeBlurMemory.setValue(out.toString());
    }

    /** Theme changed: bring back the Blur Strength last used with it (unchanged if it has none yet). */
    private static void recallThemeBlur() {
        if (themeBlurMemory == null || theme == null || blurStrength == null) return;
        String stored = parseThemeBlur(themeBlurMemory.getValue()).get(theme.getCurrentMode());
        if (stored == null) return;
        try {
            double value = Double.parseDouble(stored);
            if (!Double.isFinite(value)) return;
            recallingThemeBlur = true;
            blurStrength.setValue(Math.max(blurStrength.getMin(), Math.min(blurStrength.getMax(), value)));
        } catch (NumberFormatException ignored) {
            // a damaged entry is ignored
        } finally {
            recallingThemeBlur = false;
        }
    }

    private static Map<String, String> parseThemeBlur(String raw) {
        Map<String, String> memory = new java.util.LinkedHashMap<>();
        if (raw == null || raw.isBlank()) return memory;
        for (String part : raw.split(";")) {
            int eq = part.lastIndexOf('=');
            if (eq <= 0 || eq == part.length() - 1) continue;
            memory.put(part.substring(0, eq), part.substring(eq + 1));
        }
        return memory;
    }

    public static boolean shouldExposeSetting(Setting setting) {
        if (setting == null) {
            return false;
        }
        if (setting == auroraOpacity) return atmAurora != null && atmAurora.isEnabled();
        if (setting == starfieldOpacity) return atmStarfield != null && atmStarfield.isEnabled();
        if (setting == waterColours || setting == waterColour1 || setting == waterColour2 || setting == waterColour3
                || setting == waterOpacity || setting == waterFlowSpeed || setting == waterMouseRipple
                || setting == waterQuality || setting == waterColourMode || setting == waterCurl
                || setting == waterSplatRadius || setting == waterSplatForce || setting == waterDyeDissipation
                || setting == waterVelocityDissipation || setting == waterPressureIterations
                || setting == waterShading || setting == waterBloom || setting == waterRandomSplats) {
            if (atmWater == null || !atmWater.isEnabled()) return false;
            if (setting == waterColour2) return waterColours.getValue() >= 2;
            if (setting == waterColour3) return waterColours.getValue() >= 3;
            return true;
        }
        if (setting == blurStrength) {
            return useBlurBackground();
        }
        if (setting == highlightGradient || setting == highlightColor1) {
            return useCustomHighlight();
        }
        if (setting == highlightColor2) {
            return useCustomHighlight() && useHighlightGradient();
        }
        if (setting == customTextColor) {
            return customHeaderColour != null && customHeaderColour.isEnabled();
        }
        return true;
    }

    public static Color getPanelTitleGlow() {
        return applyBackgroundOpacity(getCurrentTheme().panelTitleGlow);
    }

    public static Color getPanelTitleText() {
        return getCurrentTheme().panelTitleText;
    }

    public static Color getPanelParticles() {
        return applyBackgroundOpacity(getCurrentTheme().panelParticles);
    }

    public static Color getSearchBoxBackground() {
        return applyBackgroundOpacity(getCurrentTheme().searchBoxBackground);
    }

    public static Color getSearchBoxFocusedBackground() {
        return applyBackgroundOpacity(getCurrentTheme().searchBoxFocusedBackground);
    }

    public static Color getSearchBoxBorder() {
        return applyBackgroundOpacity(getCurrentTheme().searchBoxBorder);
    }

    public static Color getSearchBoxText() {
        return getCurrentTheme().searchBoxText;
    }

    public static Color getSearchBoxPlaceholder() {
        return getCurrentTheme().searchBoxPlaceholder;
    }

    public static Color getModuleButtonBackground() {
        return applyBackgroundOpacity(getCurrentTheme().moduleButtonBackground);
    }

    public static Color getModuleButtonHoverBackground() {
        return applyBackgroundOpacity(getCurrentTheme().moduleButtonHoverBackground);
    }

    public static Color getModuleEnabledText() {
        return getCurrentTheme().moduleEnabledText;
    }

    public static Color getModuleDisabledText() {
        return getCurrentTheme().moduleDisabledText;
    }

    public static Color getModuleEnabledDot() {
        return getCurrentTheme().moduleEnabledDot;
    }

    public static Color getModuleDisabledDot() {
        return getCurrentTheme().moduleDisabledDot;
    }

    public static Color getSettingsBackground() {
        return applyBackgroundOpacity(getCurrentTheme().settingsBackground);
    }

    public static Color getSettingsText() {
        return getCurrentTheme().settingsText;
    }

    public static Color getTooltipBackground() {
        return applyBackgroundOpacity(getCurrentTheme().tooltipBackground);
    }

    public static Color getTooltipBorder() {
        return applyBackgroundOpacity(getCurrentTheme().tooltipBorder);
    }

    public static Color getTooltipText() {
        return getCurrentTheme().tooltipText;
    }

    public static Color getModuleEnabledGradientStart() {
        if (useCustomHighlight() && highlightColor1 != null) {
            return highlightColor1.getValue();
        }
        return getCurrentTheme().moduleEnabledGradientStart;
    }

    public static Color getModuleEnabledGradientEnd() {
        if (useCustomHighlight()) {
            if (!useHighlightGradient() || highlightColor2 == null) {
                return highlightColor1 != null ? highlightColor1.getValue()
                        : getCurrentTheme().moduleEnabledGradientStart;
            }
            return highlightColor2.getValue();
        }
        return getCurrentTheme().moduleEnabledGradientEnd;
    }

    public static float getCornerRadius() {
        if (globalCurvature == null) {
            return 10.0f;
        }
        return (float) Math.max(0.0, Math.min(64.0, globalCurvature.getValue()));
    }

    public static float getCornerRadiusScaled(double factor) {
        return (float) Math.max(0.0, getCornerRadius() * factor);
    }

    public static float getGuiCornerRadius() {
        return Math.max(0.0f, Math.min(64.0f, getCornerRadius()));
    }

    public static float getGuiCornerRadiusScaled(double factor) {
        return (float) Math.max(0.0, getGuiCornerRadius() * factor);
    }

    public static String getIconColorMode() {
        return "White";
    }

    public static boolean useBlackIcons() {
        return false;
    }

    public static boolean useGradientMenus() {
        return gradientMenus != null && gradientMenus.isEnabled();
    }

    public static boolean useCustomHighlight() {
        return customHighlight != null && customHighlight.isEnabled();
    }

    public static boolean useHighlightGradient() {
        return highlightGradient == null || highlightGradient.isEnabled();
    }

    public static boolean useBlurBackground() {
        return blurBackground != null && blurBackground.isEnabled();
    }

    public static float getBlurStrength() {
        if (blurStrength == null) {
            return 12.0f;
        }
        return (float) Math.max(0.0, blurStrength.getValue());
    }

    public static boolean useSeparator() {
        return separator == null || separator.isEnabled();
    }

    public static boolean useSidebarSeparator() {
        return sidebarSeparator == null || sidebarSeparator.isEnabled();
    }

    public static boolean useLogoSeparator() {
        return logoSeparator == null || logoSeparator.isEnabled();
    }

    public static boolean useHover() {
        return hover == null || hover.isEnabled();
    }

    public static boolean useSearch() {
        return true;
    }

    public static double getGuiSpacingScale() {
        return 1.0;
    }

    public static boolean useSquareGui() {
        return false;
    }

    public static boolean useOldGui() {
        return false;
    }

    public static boolean useShadows() {
        return true;
    }

    public static boolean useOuterStroke() {
        return outerStroke == null || outerStroke.isEnabled();
    }

    public static boolean isSsProofFailOpen() {
        return ssProofFailOpen != null && ssProofFailOpen.isEnabled();
    }

    /** Fail-open recovery only (opt-in flag checked by the caller): lowers SS Proof past the write guard. */
    public static void disableSsProofForFailOpen() {
        if (ssProof == null) return;
        ssProofGuardBypass = true;
        try {
            ssProof.setEnabled(false);
        } finally {
            ssProofGuardBypass = false;
        }
    }

    /**
     * Effective SS Proof state. On Wayland it is always off (the overlay cannot exist there), while the saved switch
     * keeps its value for an X11 session; the reason is in the setting's description and a one-time notice.
     */
    private static final boolean SS_PROOF_PLATFORM = interstellarTikiTiks.apexx.client.gui.overlay.SsProofPolicy
            .isSupportedPlatform(System.getProperty("os.name", ""));

    public static boolean isSsProof() {
        if (ssProof == null || !ssProof.isEnabled()) return false;
        if (!SS_PROOF_PLATFORM) {
            interstellarTikiTiks.apexx.client.gui.overlay.OverlayCompositor.noteUnsupportedPlatform();
            return false;
        }
        if (interstellarTikiTiks.apexx.client.util.GlfwPlatform.isWayland()) {
            interstellarTikiTiks.apexx.client.gui.overlay.OverlayCompositor.noteWaylandUnavailable();
            return false;
        }
        return true;
    }

    /**
     * SS Proof switch with write protection. It can be switched OFF only by a direct user action (an Apexx
     * screen is open on the client thread and no config/cloud-sync code is on the call stack), so loading,
     * importing, syncing or resetting a config can never silently turn the protection off. Turning it ON is
     * always allowed, except that an interactive attempt on a platform where it cannot work is refused (so
     * the user is not locked out of a menu that would then be hidden); a config-loaded "on" stays on and
     * fails closed.
     */
    private static final class SsProofSetting extends BooleanSetting {
        SsProofSetting() {
            super("SS Proof", true);
        }

        private static boolean fromConfigCode() {
            try {
                return StackWalker.getInstance().walk(frames ->
                        frames.anyMatch(f -> f.getClassName().contains(".client.config.")));
            } catch (Throwable t) {
                return true; // cannot prove it is a user action: treat as config
            }
        }

        private static boolean userAction() {
            try {
                net.minecraft.client.MinecraftClient mc = net.minecraft.client.MinecraftClient.getInstance();
                if (mc == null || !mc.isOnThread() || mc.currentScreen == null) return false;
                return interstellarTikiTiks.apexx.client.gui.overlay.SsProofPolicy.isApexxScreenClass(
                        mc.currentScreen.getClass().getName(),
                        interstellarTikiTiks.apexx.client.gui.overlay.OverlayCompositor.class.getPackageName())
                        && !fromConfigCode();
            } catch (Throwable t) {
                return false;
            }
        }

        @Override
        public void setEnabled(boolean value) {
            if (!value && isEnabled() && !ssProofGuardBypass && !userAction()) {
                return; // config / script / sync cannot lower the protection
            }
            if (value && !isEnabled() && userAction()
                    && !interstellarTikiTiks.apexx.client.gui.overlay.SsProofPolicy.isSupportedPlatform(
                    System.getProperty("os.name", ""))) {
                try {
                    net.minecraft.client.MinecraftClient mc = net.minecraft.client.MinecraftClient.getInstance();
                    if (mc != null && mc.inGameHud != null) {
                        mc.inGameHud.setOverlayMessage(net.minecraft.text.Text.literal(
                                interstellarTikiTiks.apexx.client.gui.overlay.SsProofPolicy.noticeText(
                                        interstellarTikiTiks.apexx.client.gui.overlay.SsProofPolicy.Reason.UNSUPPORTED_PLATFORM)
                                        .replace("; interface hidden.", ".")), false);
                    }
                } catch (Throwable ignored) {
                }
                return;
            }
            super.setEnabled(value);
        }

        @Override
        public void toggle() {
            setEnabled(!isEnabled());
        }
    }

    /** Fail-open can only be switched ON by a direct user action, never by a loaded/synced/imported config. */
    private static final class SsProofFailOpenSetting extends BooleanSetting {
        SsProofFailOpenSetting() {
            super("SS Proof Fail Open", false);
        }

        @Override
        public void setEnabled(boolean value) {
            if (value && !isEnabled() && !SsProofSetting.userAction()) {
                return;
            }
            super.setEnabled(value);
        }

        @Override
        public void toggle() {
            setEnabled(!isEnabled());
        }
    }

    @Override
    public boolean isSsProofEnabled() {
        return isSsProof();
    }

    public static void syncSsProof() {
        try {
            net.minecraft.client.MinecraftClient mc = net.minecraft.client.MinecraftClient.getInstance();
            if (mc != null && mc.getWindow() != null && mc.currentScreen instanceof interstellarTikiTiks.apexx.client.gui.gpuui.GpuClickGuiScreen) {
                int mode = isSsProof() ? org.lwjgl.glfw.GLFW.GLFW_CURSOR_HIDDEN : org.lwjgl.glfw.GLFW.GLFW_CURSOR_NORMAL;
                double x = mc.mouse != null ? mc.mouse.getX() : 0.0;
                double y = mc.mouse != null ? mc.mouse.getY() : 0.0;
                net.minecraft.client.util.InputUtil.setCursorParameters(mc.getWindow(), mode, x, y);
            }
            if (isSsProof()) {
                interstellarTikiTiks.apexx.client.gui.overlay.OverlayWindow.ensureCreated();
            } else {
                interstellarTikiTiks.apexx.client.gui.overlay.OverlayWindow.hide();
            }
        } catch (Throwable ignored) {
        }
    }

    public static void applyWindowCaptureExclusion(boolean enabled) {
        // Obsolete: Capture exclusion is now applied exclusively to the secondary OverlayWindow
        // so the main Minecraft window remains 100% visible and streaming uninterrupted on OBS.
        if (!enabled) {
            try {
                interstellarTikiTiks.apexx.client.gui.overlay.OverlayWindow.hide();
            } catch (Throwable ignored) {
            }
        }
    }

    public static float getGradientQualityFactor() {
        if (shaderQuality == null) {
            return 0.3f;
        }
        return (float) Math.max(0.1, Math.min(0.75, shaderQuality.getValue()));
    }

    public static float getUiResolutionScale() {
        

        return 1.0f;
    }

    /** Legacy palette getters return opaque colors (what the removed Background Opacity did at its 100% default). */
    private static Color applyBackgroundOpacity(Color color) {
        if (color == null) {
            color = new Color(25, 25, 25, 220);
        }
        return withAlpha(color, 255);
    }

public static Color getColor(Color color, double alpha) {
    if (color == null) color = new Color(255, 255, 255, 255);
    int computedAlpha = Math.max(0, Math.min(255, (int) Math.round(color.getAlpha() * alpha)));

    int key = (color.getRGB() & 0x00FFFFFF) | (computedAlpha << 24);
    int slot = key & 0xFF;
    if (colorCacheKeys[slot] == key && colorCacheVals[slot] != null) {
        return colorCacheVals[slot];
    }
    Color result = new Color(color.getRed(), color.getGreen(), color.getBlue(), computedAlpha);
    colorCacheKeys[slot] = key;
    colorCacheVals[slot] = result;
    return result;
}

    private static Color withAlpha(Color color, int alpha) {
        return new Color(color.getRed(), color.getGreen(), color.getBlue(), Math.max(0, Math.min(255, alpha)));
    }

    private static Color blend(Color a, Color b, double t) {
        double clamped = Math.max(0.0, Math.min(1.0, t));
        int r = (int) Math.round(a.getRed() + (b.getRed() - a.getRed()) * clamped);
        int g = (int) Math.round(a.getGreen() + (b.getGreen() - a.getGreen()) * clamped);
        int bl = (int) Math.round(a.getBlue() + (b.getBlue() - a.getBlue()) * clamped);
        int al = (int) Math.round(a.getAlpha() + (b.getAlpha() - a.getAlpha()) * clamped);
        return new Color(
                Math.max(0, Math.min(255, r)),
                Math.max(0, Math.min(255, g)),
                Math.max(0, Math.min(255, bl)),
                Math.max(0, Math.min(255, al)));
    }

    private static Color darken(Color color, double amount) {
        double clamped = Math.max(0.0, Math.min(1.0, amount));
        int r = (int) Math.round(color.getRed() * (1.0 - clamped));
        int g = (int) Math.round(color.getGreen() * (1.0 - clamped));
        int b = (int) Math.round(color.getBlue() * (1.0 - clamped));
        return new Color(
                Math.max(0, Math.min(255, r)),
                Math.max(0, Math.min(255, g)),
                Math.max(0, Math.min(255, b)),
                color.getAlpha());
    }

    private static Color brighten(Color color, double amount) {
        double clamped = Math.max(0.0, Math.min(1.0, amount));
        int r = (int) Math.round(color.getRed() + (255 - color.getRed()) * clamped);
        int g = (int) Math.round(color.getGreen() + (255 - color.getGreen()) * clamped);
        int b = (int) Math.round(color.getBlue() + (255 - color.getBlue()) * clamped);
        return new Color(
                Math.max(0, Math.min(255, r)),
                Math.max(0, Math.min(255, g)),
                Math.max(0, Math.min(255, b)),
                color.getAlpha());
    }

    private static class ThemeColors {
        public final Color panelBackground;
        public final Color panelTitleGlow;
        public final Color panelTitleText;
        public final Color panelParticles;
        public final Color searchBoxBackground;
        public final Color searchBoxFocusedBackground;
        public final Color searchBoxBorder;
        public final Color searchBoxText;
        public final Color searchBoxPlaceholder;
        public final Color moduleButtonBackground;
        public final Color moduleButtonHoverBackground;
        public final Color moduleEnabledText;
        public final Color moduleDisabledText;
        public final Color moduleEnabledDot;
        public final Color moduleDisabledDot;
        public final Color settingsBackground;
        public final Color settingsText;
        public final Color tooltipBackground;
        public final Color tooltipBorder;
        public final Color tooltipText;
        public final Color moduleEnabledGradientStart;
        public final Color moduleEnabledGradientEnd;

        public ThemeColors(Color panelBackground, Color panelTitleGlow, Color panelTitleText,
                Color panelParticles, Color searchBoxBackground, Color searchBoxFocusedBackground,
                Color searchBoxBorder, Color searchBoxText, Color searchBoxPlaceholder,
                Color moduleButtonBackground, Color moduleButtonHoverBackground,
                Color moduleEnabledText, Color moduleDisabledText, Color moduleEnabledDot,
                Color moduleDisabledDot, Color settingsBackground, Color settingsText,
                Color tooltipBackground, Color tooltipBorder, Color tooltipText,
                Color moduleEnabledGradientStart, Color moduleEnabledGradientEnd) {
            this.panelBackground = panelBackground;
            this.panelTitleGlow = panelTitleGlow;
            this.panelTitleText = panelTitleText;
            this.panelParticles = panelParticles;
            this.searchBoxBackground = searchBoxBackground;
            this.searchBoxFocusedBackground = searchBoxFocusedBackground;
            this.searchBoxBorder = searchBoxBorder;
            this.searchBoxText = searchBoxText;
            this.searchBoxPlaceholder = searchBoxPlaceholder;
            this.moduleButtonBackground = moduleButtonBackground;
            this.moduleButtonHoverBackground = moduleButtonHoverBackground;
            this.moduleEnabledText = moduleEnabledText;
            this.moduleDisabledText = moduleDisabledText;
            this.moduleEnabledDot = moduleEnabledDot;
            this.moduleDisabledDot = moduleDisabledDot;
            this.settingsBackground = settingsBackground;
            this.settingsText = settingsText;
            this.tooltipBackground = tooltipBackground;
            this.tooltipBorder = tooltipBorder;
            this.tooltipText = tooltipText;
            this.moduleEnabledGradientStart = moduleEnabledGradientStart;
            this.moduleEnabledGradientEnd = moduleEnabledGradientEnd;
        }
    }
}
