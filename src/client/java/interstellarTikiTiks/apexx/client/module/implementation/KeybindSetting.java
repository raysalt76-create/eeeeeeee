package interstellarTikiTiks.apexx.client.module.implementation;

import interstellarTikiTiks.apexx.client.module.Setting;
import interstellarTikiTiks.apexx.client.module.Module;

public class KeybindSetting extends Setting {

    private final Module module;

    public KeybindSetting(String name, Module module) {
        super(name);
        this.module = module;
    }

    public Module getModule() {
        return module;
    }

    private boolean defaultCaptured;
    private int defaultKey = -1, defaultKey2 = -1;
    private boolean defaultHold;

    /** Records the module's bind (both keys and Toggle/Hold) as this setting's default. */
    @Override
    public void captureDefault() {
        if (module == null) return;
        defaultKey = module.getKeyCode();
        defaultKey2 = module.getKeyCode2();
        defaultHold = module.isHoldMode();
        defaultCaptured = true;
    }

    @Override
    public void resetToDefault() {
        if (!defaultCaptured || module == null) return;
        module.setKeyCode(defaultKey);
        module.setKeyCode2(defaultKey2);
        module.setHoldMode(defaultHold);
    }

    @Override
    public boolean isModified() {
        if (!defaultCaptured || module == null) return false;
        return module.getKeyCode() != defaultKey || module.getKeyCode2() != defaultKey2
                || module.isHoldMode() != defaultHold;
    }
}
