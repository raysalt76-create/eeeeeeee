package interstellarTikiTiks.apexx.client.module.implementation.render;

import interstellarTikiTiks.apexx.client.mixin.SimpleOptionAccessor;
import interstellarTikiTiks.apexx.client.module.BooleanSetting;
import interstellarTikiTiks.apexx.client.module.Category;
import interstellarTikiTiks.apexx.client.module.Module;
import interstellarTikiTiks.apexx.client.module.SliderSetting;
import net.minecraft.client.option.SimpleOption;
import net.minecraft.util.math.MathHelper;

public class FullBright extends Module {

    private final SliderSetting brightness = new SliderSetting("Brightness", 16.0, 1.0, 16.0, 1)
            .setDescription("How bright the world is drawn. 1 is vanilla's brightest setting; 16 removes darkness entirely so caves and night look like day.");
    private final BooleanSetting fade = new BooleanSetting("Fade", false)
            .setDescription("Eases the brightness in and out over a moment when the module is toggled instead of switching instantly.");

    private double originalGamma = 1.0;
    private boolean captured = false;

    public FullBright() {
        super("FullBright", "Raises the game's brightness far past the vanilla maximum so you can see clearly in caves, at night and underwater without torches or Night Vision. Purely visual.", Category.RENDER);
        addSetting(brightness);
        addSetting(fade);
    }

    @Override
    public void onEnable() {
        if (mc.options != null) {
            this.originalGamma = mc.options.getGamma().getValue();
            this.captured = true;
        }
    }

    @Override
    public void onDisable() {
        if (mc.options != null && captured && !fade.isEnabled()) {
            forceSetGamma(this.originalGamma);
            captured = false;
        }
    }

    @Override
    public void onTick() {
        if (mc.options == null) return;
        if (!isEnabled() && !captured) return;

        double targetGamma = this.isEnabled() ? brightness.getValue() : this.originalGamma;
        double currentGamma = mc.options.getGamma().getValue();

        if (Math.abs(currentGamma - targetGamma) < 0.01) {
            if (!isEnabled()) {
                forceSetGamma(this.originalGamma);
                captured = false;
            }
            return;
        }

        if (fade.isEnabled()) {
            forceSetGamma(MathHelper.lerp(0.2, currentGamma, targetGamma));
        } else {
            forceSetGamma(targetGamma);
        }
    }

    /** Keeps ticking after disable so the Fade setting can ease the gamma back instead of snapping. */
    @Override
    public boolean shouldTickWhenDisabled() {
        return captured && fade.isEnabled();
    }

    private void forceSetGamma(double gamma) {
        SimpleOption<Double> gammaOption = mc.options.getGamma();
        if (gammaOption != null) {
            ((SimpleOptionAccessor) (Object) gammaOption).forceSetValue(gamma);
        }
    }

    @Override
    public boolean rendersInWorld() {
        return false;
    }
}
