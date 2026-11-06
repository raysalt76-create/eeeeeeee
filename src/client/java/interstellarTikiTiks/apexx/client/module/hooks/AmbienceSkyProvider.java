package interstellarTikiTiks.apexx.client.module.hooks;

import java.awt.Color;
import net.minecraft.client.util.math.MatrixStack;









public interface AmbienceSkyProvider {

    
    boolean isSkyOverrideEnabled();

    
    Color getSkyOverrideColor();

    
    Color getFogOverrideColor();

    
    boolean isMoonSunHidden();

    /** Whether the animated aurora skydome should be rendered. */
    boolean isAuroraSkyEnabled();

    /** Aurora palette selected in the Ambience module. */
    String getAuroraPalette();

    /** Overall aurora opacity, in the inclusive range [0, 1]. */
    float getAuroraOpacity();

    /** Animation speed multiplier for the aurora curtains. */
    float getAuroraSpeed();

    /** Draws the payload-owned aurora after the vanilla sky canvas is in place. */
    void renderAurora(MatrixStack matrices);

    /** Whether the full procedural shader skybox (stars, nebula, aurora) is enabled. */
    default boolean isShaderSkyboxEnabled() { return false; }

    /** Multiplier for star brightness. */
    default float getStarBrightness() { return 1.0f; }

    /** Multiplier for star density across the celestial dome. */
    default float getStarDensity() { return 1.0f; }

    /** Animation speed for star twinkle/scintillation. */
    default float getTwinkleSpeed() { return 1.0f; }

    /** Opacity and intensity of the cosmic nebula veil. */
    default float getNebulaGlow() { return 0.4f; }

    /** Whether the sky colors synchronize with the client theme accent. */
    default boolean isThemeSyncSky() { return false; }
}
