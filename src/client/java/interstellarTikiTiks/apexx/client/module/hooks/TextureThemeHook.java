package interstellarTikiTiks.apexx.client.module.hooks;

import java.awt.Color;






public interface TextureThemeHook {

    boolean usesShader();

    String getShaderName();

    Color applyThemeTint(Color baseColor);
}
