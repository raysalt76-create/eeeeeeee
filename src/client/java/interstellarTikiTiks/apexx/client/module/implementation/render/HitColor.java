package interstellarTikiTiks.apexx.client.module.implementation.render;

import interstellarTikiTiks.apexx.client.module.BooleanSetting;
import interstellarTikiTiks.apexx.client.module.Category;
import interstellarTikiTiks.apexx.client.module.ColorSetting;
import interstellarTikiTiks.apexx.client.module.Module;
import interstellarTikiTiks.apexx.client.module.SliderSetting;
import interstellarTikiTiks.apexx.client.module.hooks.HitColorProvider;
import net.minecraft.client.render.entity.state.LivingEntityRenderState;
import net.minecraft.client.render.entity.state.PlayerEntityRenderState;

import java.awt.Color;

/** Replaces the red damage flash on hurt entities with a colour of your choice. Purely visual. */
public final class HitColor extends Module implements HitColorProvider {
    private final ColorSetting color = new ColorSetting("Color", new Color(90, 160, 255))
            .setDescription("Colour hurt entities are tinted with instead of vanilla's red flash.");
    private final SliderSetting intensity = new SliderSetting("Intensity", 0.6, 0.1, 1.0, 2)
            .setDescription("How strongly the colour tints the entity: low keeps most of the skin visible, 1 tints it fully.");
    private final BooleanSetting playersOnly = new BooleanSetting("Players Only", false)
            .setDescription("Only recolours players; mobs keep the vanilla red flash.");
    private final BooleanSetting includeSelf = new BooleanSetting("Include Self", true)
            .setDescription("Also recolours your own model (third person and the inventory preview).");

    public HitColor() {
        super("HitColor", "Changes the red flash entities show when they take damage to a colour and strength you "
                + "pick, for players only or every mob. Visible only to you.", Category.RENDER);
        addSetting(color);
        addSetting(intensity);
        addSetting(playersOnly);
        addSetting(includeSelf);
    }

    @Override
    public boolean rendersInWorld() {
        return false;
    }

    @Override
    public int hitColorArgb(LivingEntityRenderState state) {
        if (state == null) return 0;
        boolean player = state instanceof PlayerEntityRenderState;
        if (playersOnly.isEnabled() && !player) return 0;
        if (!includeSelf.isEnabled() && player && mc.player != null
                && ((PlayerEntityRenderState) state).id == mc.player.getId()) {
            return 0;
        }
        Color c = color.getValue();
        if (c == null) return 0;
        float k = (float) Math.max(0.0, Math.min(1.0, intensity.getValue()));
        int r = Math.round(255 + (c.getRed() - 255) * k);
        int g = Math.round(255 + (c.getGreen() - 255) * k);
        int b = Math.round(255 + (c.getBlue() - 255) * k);
        // Opaque alpha so the multiply never changes the model's own transparency.
        return 0xFF000000 | (clamp(r) << 16) | (clamp(g) << 8) | clamp(b);
    }

    private static int clamp(int v) {
        return Math.max(0, Math.min(255, v));
    }
}
