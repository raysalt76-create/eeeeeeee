package interstellarTikiTiks.apexx.client.hud;

import interstellarTikiTiks.apexx.client.module.BooleanSetting;
import interstellarTikiTiks.apexx.client.module.Setting;
import interstellarTikiTiks.apexx.client.module.StringSetting;
import net.minecraft.client.gui.DrawContext;

import java.util.List;

/** Configurable Apexx branding strip; rendering is shared with the GPU HUD renderer. */
public final class BrandingHudElement extends HUDElement {
    private final StringSetting label = new StringSetting("Label", "Apexx");
    private final BooleanSetting showUsername = new BooleanSetting("Username", true);
    private final BooleanSetting showClock = new BooleanSetting("Clock", false);

    public BrandingHudElement(String name, double defaultX, double defaultY) {
        super(name, defaultX, defaultY);
        setWidth(138);
        setHeight(28);
    }

    public StringSetting getLabelSetting() { return label; }
    public BooleanSetting getShowUsername() { return showUsername; }
    public BooleanSetting getShowClock() { return showClock; }

    @Override
    protected List<Setting> elementSettings() {
        return List.of(label, showUsername, showClock);
    }

    @Override
    public void render(DrawContext context, float delta) {
        // The GPU renderer owns this element's visual and measured bounds.
    }
}
