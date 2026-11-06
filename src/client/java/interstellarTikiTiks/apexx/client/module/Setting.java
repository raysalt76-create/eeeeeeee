package interstellarTikiTiks.apexx.client.module;

public abstract class Setting {
    private String name;

    private Runnable changeListener;

    // Optional GUI metadata. All default to "not set" so existing settings render exactly
    // as before unless a module opts in.
    private boolean advanced = false;
    private boolean blatant = false;
    private String description = null;
    private String dependsOn = null;
    private String section = null;

    public Setting(String name) {
        this.name = name;
        this.changeListener = null;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public Setting onChange(Runnable listener) {
        this.changeListener = listener;
        return this;
    }

    /**
     * Set only by the menu's sound bracket around one click (render thread), so the click can sound like what it
     * changed (a switch flipping on or off). Null the rest of the time; it never alters a value.
     */
    public static volatile java.util.function.Consumer<Setting> pressObserver;

    protected void callListener() {
        java.util.function.Consumer<Setting> observer = pressObserver;
        if (observer != null) {
            try {
                observer.accept(this);
            } catch (Throwable ignored) {
                // cosmetic only
            }
        }
        if (changeListener != null) {
            changeListener.run();
        }
    }

    /** True places this setting inside the module panel's collapsed "Advanced Settings" dropdown. */
    public boolean isAdvanced() {
        return advanced;
    }

    // Self-returning (not just Setting) so these chain at the field-declaration site without
    // losing the concrete subtype, e.g. `new BooleanSetting(...).setAdvanced(true)` assigned to
    // a `BooleanSetting` field. Safe here because no subclass overrides these three methods, so
    // the runtime type at any call site always matches the inferred T.
    @SuppressWarnings("unchecked")
    public <T extends Setting> T setAdvanced(boolean advanced) {
        this.advanced = advanced;
        return (T) this;
    }

    /**
     * Marks a single setting whose value is impossible in vanilla, inside a module that is
     * otherwise fine — an above-vanilla reach slider, a cooldown bypass, a rate no hand reaches.
     *
     * <p>A whole module that cannot exist in vanilla belongs in {@link Category#BLATANT} instead;
     * this flag is for the case where only one knob crosses the line, so the module keeps its real
     * category and the GUI puts a BLATANT badge on the row. Purely informational — it changes no
     * behaviour and gates nothing.
     */
    public boolean isBlatant() {
        return blatant;
    }

    @SuppressWarnings("unchecked")
    public <T extends Setting> T setBlatant(boolean blatant) {
        this.blatant = blatant;
        return (T) this;
    }

    /** Shown as a hover tooltip over the setting's row in the GUI. */
    public String getDescription() {
        return description;
    }

    @SuppressWarnings("unchecked")
    public <T extends Setting> T setDescription(String description) {
        this.description = description;
        return (T) this;
    }

    /**
     * Conditions in the same module that gate this setting's visibility in the GUI. A bare
     * BooleanSetting name requires it to be on, a bare SliderSetting name requires its value to
     * exceed its minimum, {@code !Name} negates either condition, and {@code Mode:Choice} requires
     * a matching ModeSetting choice; {@code Mode:A|B} matches any of the listed choices.
     * Comma-separated conditions are combined with AND.
     */
    public String getDependsOn() {
        return dependsOn;
    }

    @SuppressWarnings("unchecked")
    public <T extends Setting> T setDependsOn(String dependsOn) {
        this.dependsOn = dependsOn;
        return (T) this;
    }

    /** Logical section/group name in the module settings ledger (e.g. 'Targeting & Reach'). */
    public String getSection() {
        return section;
    }

    @SuppressWarnings("unchecked")
    public <T extends Setting> T setSection(String section) {
        this.section = section;
        return (T) this;
    }

    /** Records the current value as this setting's default. Called once when the module registers it. */
    public void captureDefault() {
    }

    /** Restores the value recorded by {@link #captureDefault()}; settings without one ignore it. */
    public void resetToDefault() {
    }

    /** True when the setting differs from its recorded default (false when none was recorded). */
    public boolean isModified() {
        return false;
    }

    private java.util.function.Supplier<Boolean> visibility = null;

    public boolean isVisible() {
        return visibility == null || visibility.get();
    }

    @SuppressWarnings("unchecked")
    public <T extends Setting> T setVisibility(java.util.function.Supplier<Boolean> visibility) {
        this.visibility = visibility;
        return (T) this;
    }
}
