package interstellarTikiTiks.apexx.client.module.implementation.render;

import interstellarTikiTiks.apexx.client.module.BooleanSetting;
import interstellarTikiTiks.apexx.client.module.Category;
import interstellarTikiTiks.apexx.client.module.ColorSetting;
import interstellarTikiTiks.apexx.client.module.ModeSetting;
import interstellarTikiTiks.apexx.client.module.Module;
import interstellarTikiTiks.apexx.client.module.SliderSetting;
import interstellarTikiTiks.apexx.client.module.hooks.AmbienceSkyProvider;
import interstellarTikiTiks.apexx.client.module.hooks.WorldSceneVisualProvider;
import interstellarTikiTiks.apexx.client.render.visuals.WorldSceneVisuals;
import interstellarTikiTiks.apexx.client.rendering.AuroraSkyRenderer;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.util.math.MatrixStack;

import java.awt.Color;

@Environment(EnvType.CLIENT)
public class Ambience extends Module implements AmbienceSkyProvider, WorldSceneVisualProvider {

    public static Ambience INSTANCE;

    private final ColorSetting fogColor = new ColorSetting("Fog Color", new Color(160, 200, 255))
            .setDescription("Colour of the distance fog that hides far terrain.");
    private final SliderSetting fogStart = new SliderSetting("Fog Start", 0.1, 0.0, 1.0, 2)
            .setDescription("Where the fog starts, as a fraction of your render distance (0 = right in front of you, 1 = at the render edge).");
    private final SliderSetting fogEnd = new SliderSetting("Fog End", 0.8, 0.1, 2.0, 2)
            .setDescription("Where the fog becomes fully opaque, as a fraction of your render distance. Keep it above Fog Start.");
    private final SliderSetting depthFogDensity = new SliderSetting("Depth Fog Density", 0.018, 0.002, 0.08, 3)
            .setDescription("Thickness of the height-aware atmospheric haze, which is densest near the ground and the horizon. Higher values make the world look misty.");
    private final SliderSetting globeStrength = new SliderSetting("Floating Globes", 0.0, 0.0, 1.0, 2)
            .setDescription("Strength of soft glowing orbs fixed in the sky. They bob gently in place and stay put as you move; 0 turns them off.");
    private final SliderSetting groundFilm = new SliderSetting("Ground Film", 0.0, 0.0, 1.0, 2)
            .setDescription("Strength of a thin iridescent sheen over block surfaces that subtly shifts their colours; 0 turns it off.");
    private final SliderSetting groundScale = new SliderSetting("Ground Detail Scale", 1.0, 0.25, 8.0, 2)
            .setDescription("Size of the colour pattern in Ground Film: low values make broad bands, high values make fine detail.");
    private final BooleanSetting overrideSky = new BooleanSetting("Override Sky", true)
            .setDescription("Replaces the vanilla sky and horizon colours with Sky Color (or the theme accent with Theme Sync).");
    private final BooleanSetting shaderSky = new BooleanSetting("Shader Skybox", true)
            .setDescription("Draws a procedural night-sky dome with stars, a nebula veil and an optional aurora in place of the vanilla skybox.");
    private final BooleanSetting themeSync = new BooleanSetting("Theme Sync", true)
            .setDescription("Tints the sky, stars and aurora with the current ClickGUI theme accent instead of the fixed colours.");
    private final ColorSetting skyColor = new ColorSetting("Sky Color", new Color(100, 160, 255))
            .setDescription("Base colour of the clear sky when Override Sky is on and Theme Sync is off.");
    private final BooleanSetting noMoonSun = new BooleanSetting("No Moon/Sun", false)
            .setDescription("Hides the sun and moon so only the sky colour and shader effects remain.");
    private final SliderSetting starDensity = new SliderSetting("Star Density", 1.2, 0.2, 3.0, 1)
            .setDescription("How many stars the shader skybox draws; higher fills the sky more densely.");
    private final SliderSetting starBrightness = new SliderSetting("Star Brightness", 1.35, 0.2, 3.0, 2)
            .setDescription("How bright the shader skybox stars are.");
    private final SliderSetting twinkleSpeed = new SliderSetting("Twinkle Speed", 1.2, 0.1, 4.0, 1)
            .setDescription("How fast the stars twinkle.");
    private final SliderSetting nebulaGlow = new SliderSetting("Nebula Glow", 0.45, 0.0, 1.0, 2)
            .setDescription("Strength of the soft coloured nebula clouds behind the stars; 0 hides them.");
    private final BooleanSetting auroraSky = new BooleanSetting("Aurora Sky", true)
            .setDescription("Adds slowly moving northern-lights curtains across the sky.");
    private final ModeSetting auroraPalette = new ModeSetting("Aurora Palette", "Arctic", "Arctic", "Nebula", "Ember", "Cosmic Violet")
            .setDescription("Aurora colours: Arctic is icy cyan and violet, Nebula saturated magenta, Ember warm gold, Cosmic Violet deep purple.");
    private final SliderSetting auroraOpacity = new SliderSetting("Aurora Opacity", 0.72, 0.1, 1.0, 2)
            .setDescription("How strongly the aurora shows against the sky; lower is more subtle.");
    private final SliderSetting auroraSpeed = new SliderSetting("Aurora Speed", 0.55, 0.1, 2.0, 2)
            .setDescription("How fast the aurora curtains drift and change shape.");

    public Ambience() {
        super("Ambience", "Restyles the world's atmosphere: recolours and reshapes fog, replaces the sky with a procedural star, nebula and aurora dome, and can add sky orbs and an iridescent ground sheen. Purely visual and client-side.", Category.RENDER);
        INSTANCE = this;
        addSetting(fogColor);
        addSetting(fogStart);
        addSetting(fogEnd);
        addSetting(depthFogDensity);
        addSetting(globeStrength);
        addSetting(groundFilm);
        addSetting(groundScale);
        addSetting(overrideSky);
        addSetting(shaderSky);
        addSetting(themeSync);
        addSetting(skyColor);
        addSetting(noMoonSun);
        addSetting(starDensity);
        addSetting(starBrightness);
        addSetting(twinkleSpeed);
        addSetting(nebulaGlow);
        addSetting(auroraSky);
        addSetting(auroraPalette);
        addSetting(auroraOpacity);
        addSetting(auroraSpeed);
    }

    public static Color getFogColor() {
        if (INSTANCE == null || !INSTANCE.isEnabled()) return null;
        return INSTANCE.fogColor.getValue();
    }

    public static float getFogStart(float renderDistance) {
        if (INSTANCE == null || !INSTANCE.isEnabled()) return renderDistance * 0.25f;
        return renderDistance * (float) INSTANCE.fogStart.getValue();
    }

    public static float getFogEnd(float renderDistance) {
        if (INSTANCE == null || !INSTANCE.isEnabled()) return renderDistance;
        return Math.max(renderDistance * (float) INSTANCE.fogEnd.getValue(), getFogStart(renderDistance) + 1.0f);
    }

    public static boolean shouldOverrideSky() {
        return INSTANCE != null && INSTANCE.isEnabled() && INSTANCE.overrideSky.isEnabled();
    }

    public static Color getSkyColor() {
        if (INSTANCE == null) return null;
        return INSTANCE.skyColor.getValue();
    }

    public static boolean shouldHideMoonSun() {
        return INSTANCE != null && INSTANCE.isEnabled() && INSTANCE.noMoonSun.isEnabled();
    }

    


    @Override
    public boolean isSkyOverrideEnabled() {
        return isEnabled() && overrideSky.isEnabled();
    }

    @Override
    public Color getSkyOverrideColor() {
        return skyColor.getValue();
    }

    @Override
    public Color getFogOverrideColor() {
        if (!isEnabled()) return null;
        return fogColor.getValue();
    }

    @Override
    public boolean isMoonSunHidden() {
        return isEnabled() && noMoonSun.isEnabled();
    }

    @Override
    public boolean isAuroraSkyEnabled() {
        return isEnabled() && auroraSky.isEnabled();
    }

    @Override
    public boolean isShaderSkyboxEnabled() {
        return isEnabled() && shaderSky.isEnabled();
    }

    @Override
    public float getStarBrightness() {
        return (float) starBrightness.getValue();
    }

    @Override
    public float getStarDensity() {
        return (float) starDensity.getValue();
    }

    @Override
    public float getTwinkleSpeed() {
        return (float) twinkleSpeed.getValue();
    }

    @Override
    public float getNebulaGlow() {
        return (float) nebulaGlow.getValue();
    }

    @Override
    public boolean isThemeSyncSky() {
        return themeSync.isEnabled();
    }

    @Override
    public String getAuroraPalette() {
        return auroraPalette.getCurrentMode();
    }

    @Override
    public float getAuroraOpacity() {
        return (float) auroraOpacity.getValue();
    }

    @Override
    public float getAuroraSpeed() {
        return (float) auroraSpeed.getValue();
    }

    @Override
    public void renderAurora(MatrixStack matrices) {
        AuroraSkyRenderer.render(matrices, this);
    }

    @Override
    public void onWorldRender(MatrixStack matrices, float tickDelta) {
        // Scene-space effects are composited after the world frame graph.
    }

    @Override
    public boolean rendersInWorld() {
        return false;
    }

    @Override
    public void contributeWorldSceneVisuals(WorldSceneVisuals frame, long nowMillis) {
        if (!isEnabled() || mc.player == null) return;
        float renderDistance = mc.options.getViewDistance().getValue() * 16.0f;
        frame.setDepthFog(fogColor.getValue(), renderDistance * (float) fogStart.getValue(),
                Math.max(renderDistance * (float) fogEnd.getValue(), renderDistance * (float) fogStart.getValue() + 1.0f), (float) depthFogDensity.getValue());
        frame.setFloatingGlobes((float) globeStrength.getValue());
        frame.setGroundFilm((float) groundFilm.getValue(), (float) groundScale.getValue());
    }
}
