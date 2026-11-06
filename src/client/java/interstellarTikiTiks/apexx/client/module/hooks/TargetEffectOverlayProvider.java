package interstellarTikiTiks.apexx.client.module.hooks;

import net.minecraft.client.render.entity.state.PlayerEntityRenderState;

import java.awt.Color;

public interface TargetEffectOverlayProvider {

    boolean shouldHideModel(PlayerEntityRenderState state);

    

    

    

    TextureThemeHook getOverlayTheme();

    Color getOverlayColor(long now, int seed);

    boolean isOverlayOpaque();

    float getOverlayScale();

    boolean isOverlayGlowEnabled();

    float getOverlayGlowSize();

    float getOverlayGlowOpacity();
}
