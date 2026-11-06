package interstellarTikiTiks.apexx.client.module;

public class BooleanSetting extends Setting {
    private boolean enabled;

    // ── Optional per-setting keybind ────────────────────────────────────────────────
    // Most booleans have no key. A setting opts in with setBindable(true); the in-game GUI
    // then shows a bind button next to it and the keybind poll in MinecraftClientMixin
    // watches keyCode. keyHoldMode picks the behaviour: false = press toggles the value,
    // true = the value tracks the key (held = on, released = off). keyCode -1 = unbound.
    private boolean bindable = false;
    private int keyCode = -1;
    private boolean keyHoldMode = false;

    public BooleanSetting(String name, boolean defaultValue) {
        super(name);
        this.enabled = defaultValue;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public boolean getValue() {
        return enabled;
    }

    public void setValue(boolean value) {
        setEnabled(value);
    }

    public void setEnabled(boolean enabled) {
        if (this.enabled != enabled) {
            this.enabled = enabled;
            callListener();
            return;
        }
        this.enabled = enabled;
    }

    public void toggle() {
        this.enabled = !this.enabled;
        callListener();
    }

    private Boolean defaultEnabled;
    private int defaultKeyCode = -1;
    private boolean defaultKeyHoldMode;

    @Override
    public void captureDefault() {
        defaultEnabled = enabled;
        defaultKeyCode = keyCode;
        defaultKeyHoldMode = keyHoldMode;
    }

    /** The value recorded by {@link #captureDefault()}, or the current value when none was recorded. */
    public boolean isDefaultEnabled() {
        return defaultEnabled != null ? defaultEnabled : isEnabled();
    }

    /** Restores the value and, for a bindable toggle, its own key and Toggle/Hold mode. */
    @Override
    public void resetToDefault() {
        if (defaultEnabled == null) return;
        if (bindable) {
            keyCode = defaultKeyCode;
            keyHoldMode = defaultKeyHoldMode;
        }
        setEnabled(defaultEnabled);
    }

    @Override
    public boolean isModified() {
        if (defaultEnabled == null) return false;
        if (defaultEnabled != enabled) return true;
        return bindable && (keyCode != defaultKeyCode || keyHoldMode != defaultKeyHoldMode);
    }

    /** Mark this boolean as bindable so the GUI exposes a key + hold/toggle control for it. */
    public BooleanSetting setBindable(boolean bindable) {
        this.bindable = bindable;
        return this;
    }

    public boolean isBindable() {
        return bindable;
    }

    public int getKeyCode() {
        return keyCode;
    }

    /** 0 is normalised to -1 (unbound) to match the module keybind convention. */
    public void setKeyCode(int keyCode) {
        this.keyCode = (keyCode == 0) ? -1 : keyCode;
    }

    public boolean isKeyHoldMode() {
        return keyHoldMode;
    }

    public void setKeyHoldMode(boolean keyHoldMode) {
        this.keyHoldMode = keyHoldMode;
    }

    /** Human-readable name of the bound key ("NONE" when unbound), for GUI display. */
    public String getKeyDisplayName() {
        if (keyCode == -1 || keyCode == 0) {
            return "NONE";
        }
        return Module.getSingleKeyName(keyCode);
    }
}
