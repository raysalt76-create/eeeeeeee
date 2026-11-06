package interstellarTikiTiks.apexx.client.module;

public class StringSetting extends Setting {
    private String value;
    private boolean isEditing = false;

    public StringSetting(String name, String defaultValue) {
        super(name);
        this.value = defaultValue;
    }

    public String getValue() { return value; }
    public void setValue(String value) {
        String next = value == null ? "" : value;
        if (!next.equals(this.value)) {
            this.value = next;
            callListener();
            return;
        }
        this.value = next;
    }

    public boolean isEditing() { return isEditing; }
    public void setEditing(boolean editing) { this.isEditing = editing; }

    private String defaultValue;

    @Override
    public void captureDefault() {
        defaultValue = value == null ? "" : value;
    }

    @Override
    public void resetToDefault() {
        if (defaultValue != null) setValue(defaultValue);
    }

    @Override
    public boolean isModified() {
        return defaultValue != null && !defaultValue.equals(value == null ? "" : value);
    }
}
