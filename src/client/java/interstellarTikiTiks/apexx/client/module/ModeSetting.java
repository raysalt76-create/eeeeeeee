package interstellarTikiTiks.apexx.client.module;

import java.util.Arrays;
import java.util.List;

public class ModeSetting extends Setting {
    private final List<String> modes;
    private int currentIndex;

    public ModeSetting(String name, String... modes) {
        super(name);
        this.modes = Arrays.stream(modes)
                .distinct()
                .collect(java.util.stream.Collectors.toCollection(java.util.ArrayList::new));
        this.currentIndex = 0;
    }

    public String getCurrentMode() {
        if (modes.isEmpty()) {
            return "";
        }
        return modes.get(currentIndex);
    }

    public boolean is(String modeName) {
        return getCurrentMode().equalsIgnoreCase(modeName);
    }

    public void setCurrentMode(String modeName) {
        if (modes.isEmpty() || modeName == null) {
            return;
        }
        int newIndex = -1;
        // Exact match first
        for (int i = 0; i < modes.size(); i++) {
            if (modes.get(i).equals(modeName)) {
                newIndex = i;
                break;
            }
        }
        // Case-insensitive / trimmed fallback
        if (newIndex == -1) {
            String trimmed = modeName.trim();
            for (int i = 0; i < modes.size(); i++) {
                if (modes.get(i).equalsIgnoreCase(trimmed)) {
                    newIndex = i;
                    break;
                }
            }
        }
        // Normalized alphanumeric fallback (e.g. "Packet-Fly" vs "packetfly")
        if (newIndex == -1) {
            String norm = modeName.replaceAll("[^a-zA-Z0-9]", "").toLowerCase();
            for (int i = 0; i < modes.size(); i++) {
                if (modes.get(i).replaceAll("[^a-zA-Z0-9]", "").toLowerCase().equals(norm)) {
                    newIndex = i;
                    break;
                }
            }
        }
        // If mode is not recognized, do not overwrite/reset to 0! Keep current index.
        if (newIndex == -1) {
            return;
        }

        if (this.currentIndex != newIndex) {
            this.currentIndex = newIndex;
            callListener();
        }
    }

    public void cycle() {
        if (modes.isEmpty()) {
            return;
        }
        currentIndex = (currentIndex + 1) % modes.size();
        callListener();
    }

    public List<String> getModes() {
        return modes;
    }

    /**
     * Replaces the option list at runtime (e.g. a file picker whose entries come from disk) and
     * selects {@code select} when present, else the first option. Does not fire the change
     * listener: only a user pick does. Call on the client thread, where the GUI reads the list.
     */
    public void setModes(List<String> next, String select) {
        modes.clear();
        if (next != null) {
            for (String mode : next) {
                if (mode != null && !modes.contains(mode)) modes.add(mode);
            }
        }
        int index = select == null ? -1 : modes.indexOf(select);
        currentIndex = Math.max(0, index);
    }

    private String defaultMode;

    @Override
    public void captureDefault() {
        defaultMode = getCurrentMode();
    }

    @Override
    public void resetToDefault() {
        if (defaultMode != null) setCurrentMode(defaultMode);
    }

    /** The mode recorded by {@link #captureDefault()}, or the current mode when none was recorded. */
    public String getDefaultMode() {
        return defaultMode != null ? defaultMode : getCurrentMode();
    }

    @Override
    public boolean isModified() {
        return defaultMode != null && !defaultMode.equals(getCurrentMode());
    }
}
