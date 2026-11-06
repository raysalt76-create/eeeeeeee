package interstellarTikiTiks.apexx.client.module.implementation.targeteffect;

import interstellarTikiTiks.apexx.client.module.implementation.targeteffect.TextureTheme;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.entity.model.PlayerEntityModel;
import net.minecraft.client.render.entity.state.PlayerEntityRenderState;
import net.minecraft.entity.player.PlayerEntity;

import java.awt.*;

public class EffectRenderContext {
    public final PlayerEntity player;
    public final String effectType;
    public final Color color;
    public final float alpha;
    public final long time;
    public final double speedMultiplier;
    public final double scaleMultiplier;
    public final int segments;
    public final boolean glowEnabled;
    public final double glowSize;
    public final double glowOpacity;
    public final boolean opaqueMode;

    public final interstellarTikiTiks.apexx.client.module.implementation.targeteffect.TextureTheme theme;
    public final float uvOffsetU;
    public final float uvOffsetV;

    public final VertexConsumerProvider vertexConsumers;
    public final PlayerEntityModel playerModel;
    public final PlayerEntityRenderState renderState;
    public final int light;

    public EffectRenderContext(PlayerEntity player, String effectType, Color color, float alpha,
                               long time, double speedMultiplier, double scaleMultiplier, int segments,
                               boolean glowEnabled, double glowSize, double glowOpacity, boolean opaqueMode,
                               interstellarTikiTiks.apexx.client.module.implementation.targeteffect.TextureTheme theme) {
        this(player, effectType, color, alpha, time, speedMultiplier, scaleMultiplier, segments,
                glowEnabled, glowSize, glowOpacity, opaqueMode, theme, null, null, null, 0);
    }

    public EffectRenderContext(PlayerEntity player, String effectType, Color color, float alpha,
                               long time, double speedMultiplier, double scaleMultiplier, int segments,
                               boolean glowEnabled, double glowSize, double glowOpacity, boolean opaqueMode,
                               TextureTheme theme, VertexConsumerProvider vertexConsumers,
                               PlayerEntityModel playerModel, PlayerEntityRenderState renderState, int light) {
        this.player = player;
        this.effectType = effectType;
        this.color = color;
        this.alpha = alpha;
        this.time = time;
        this.speedMultiplier = speedMultiplier;
        this.scaleMultiplier = scaleMultiplier;
        this.segments = segments;
        this.glowEnabled = glowEnabled;
        this.glowSize = glowSize;
        this.glowOpacity = glowOpacity;
        this.opaqueMode = opaqueMode;
        this.theme = theme;

        float[] uvOffsets = theme.getAnimatedUVs(time, speedMultiplier);
        this.uvOffsetU = uvOffsets[0];
        this.uvOffsetV = uvOffsets[1];

        this.vertexConsumers = vertexConsumers;
        this.playerModel = playerModel;
        this.renderState = renderState;
        this.light = light;
    }

    public Color getThemedColor() {
        return theme.applyThemeTint(color);
    }
}
