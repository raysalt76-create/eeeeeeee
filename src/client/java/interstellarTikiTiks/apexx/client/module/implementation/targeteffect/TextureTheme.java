package interstellarTikiTiks.apexx.client.module.implementation.targeteffect;

import interstellarTikiTiks.apexx.client.module.hooks.TextureThemeHook;
import net.minecraft.util.Identifier;

import java.awt.*;

public enum TextureTheme implements TextureThemeHook {
    SOLID("Solid", null, false, null),
    LIQUID_METAL("Liquid Metal", null, false, "liquid_metal"),
    LAVA("Lava", null, false, "lava"),
    WATER("Water", null, false, "water");

    private final String displayName;
    private final Identifier textureId;
    private final boolean animated;
    private final String shaderName;

    TextureTheme(String displayName, Identifier textureId, boolean animated, String shaderName) {
        this.displayName = displayName;
        this.textureId = textureId;
        this.animated = animated;
        this.shaderName = shaderName;
    }

    public String getDisplayName() {
        return displayName;
    }

    public Identifier getTextureId() {
        return textureId;
    }

    public Identifier getTexture() {
        return textureId;
    }

    @Deprecated
    public boolean isAnimated() {
        return animated;
    }

    @Override
    public String getShaderName() {
        return shaderName;
    }

    @Override
    public boolean usesShader() {
        return shaderName != null;
    }

    public boolean hasTexture() {
        return usesShader() || textureId != null;
    }

    @Deprecated
    public float[] getAnimatedUVs(long time, double speedMultiplier) {
        return new float[]{0, 0};
    }

    @Override
    public Color applyThemeTint(Color baseColor) {
        switch (this) {
            case LIQUID_METAL:
                float[] hsb = Color.RGBtoHSB(baseColor.getRed(), baseColor.getGreen(), baseColor.getBlue(), null);
                hsb[1] *= 0.8f;
                hsb[2] *= 1.1f;
                return Color.getHSBColor(hsb[0], hsb[1], Math.min(1.0f, hsb[2]));

            case LAVA:
                hsb = Color.RGBtoHSB(baseColor.getRed(), baseColor.getGreen(), baseColor.getBlue(), null);
                hsb[1] = Math.min(1.0f, hsb[1] * 1.2f);
                hsb[2] *= 1.3f;
                return Color.getHSBColor(hsb[0], hsb[1], Math.min(1.0f, hsb[2]));

            case WATER:
                hsb = Color.RGBtoHSB(baseColor.getRed(), baseColor.getGreen(), baseColor.getBlue(), null);
                hsb[0] = (hsb[0] + 0.55f) % 1.0f;
                hsb[1] *= 0.9f;
                return Color.getHSBColor(hsb[0], hsb[1], hsb[2]);

            default:
                return baseColor;
        }
    }

    public static TextureTheme fromString(String name) {
        for (TextureTheme theme : values()) {
            if (theme.displayName.equalsIgnoreCase(name)) {
                return theme;
            }
        }
        return SOLID;
    }

    public static String[] getDisplayNames() {
        TextureTheme[] themes = values();
        String[] names = new String[themes.length];
        for (int i = 0; i < themes.length; i++) {
            names[i] = themes[i].displayName;
        }
        return names;
    }
}
