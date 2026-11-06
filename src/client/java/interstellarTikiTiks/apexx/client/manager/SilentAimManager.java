package interstellarTikiTiks.apexx.client.manager;

import interstellarTikiTiks.apexx.client.Apexx;
import interstellarTikiTiks.apexx.client.config.ConfigManager;
import interstellarTikiTiks.apexx.client.module.BooleanSetting;
import interstellarTikiTiks.apexx.client.module.Module;
import interstellarTikiTiks.apexx.client.module.Setting;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * Central manager for all Silent Aim capabilities in Apexx Client.
 *
 * <p>Discovers modules that have an explicit {@code "Silent Aim"} setting, provides
 * unified state inspection and mutation, and manages global batch operations (ALL ON / ALL OFF).
 */
public final class SilentAimManager {

    private static final SilentAimManager INSTANCE = new SilentAimManager();

    public static final String SILENT_AIM_SETTING_NAME = "Silent Aim";

    private SilentAimManager() {
    }

    public static SilentAimManager getInstance() {
        return INSTANCE;
    }

    /**
     * Checks if a setting is considered a silent-related setting (silent aim, rotation mode, packet clicks, etc.).
     */
    public static boolean isSilentSetting(Setting s) {
        if (s == null) return false;
        String name = s.getName().toLowerCase();
        if (name.equals("silent mode")) {
            return false;
        }
        if (name.contains("silent") || name.equals("packet clicks") || name.equals("surface pitch") || name.equals("head bob")) {
            return true;
        }
        if (s instanceof interstellarTikiTiks.apexx.client.module.ModeSetting m) {
            for (String mode : m.getModes()) {
                if ("silent".equalsIgnoreCase(mode)) return true;
            }
        }
        return false;
    }

    /**
     * Retrieves the primary {@link BooleanSetting} responsible for Silent Aim on the given module,
     * or {@code null} if none exists.
     */
    public BooleanSetting getSilentSetting(Module module) {
        if (module == null) return null;
        for (Setting s : module.getSettings()) {
            if (s instanceof BooleanSetting b && SILENT_AIM_SETTING_NAME.equalsIgnoreCase(b.getName())) {
                return b;
            }
        }
        for (Setting s : module.getSettings()) {
            if (s instanceof BooleanSetting b && isSilentToggle(b)) {
                return b;
            }
        }
        return null;
    }

    /** The real Silent Aim toggle: a boolean whose name says "silent". Unrelated look settings (Head Bob, Surface Pitch) and the separate Packet Clicks toggle are excluded. */
    private static boolean isSilentToggle(Setting s) {
        return s instanceof BooleanSetting && isSilentSetting(s) && s.getName().toLowerCase().contains("silent");
    }

    /**
     * Checks whether the module supports Silent Aim or silent interactions.
     */
    public boolean hasSilentAim(Module module) {
        if (module == null) return false;
        for (Setting s : module.getSettings()) {
            if (isSilentSetting(s)) return true;
        }
        return false;
    }

    /**
     * Returns a list of all client modules that support Silent Aim or silent interactions.
     */
    public List<Module> getSilentModules() {
        if (Apexx.getInstance() == null || Apexx.getInstance().getModuleManager() == null) {
            return Collections.emptyList();
        }
        List<Module> all = Apexx.getInstance().getModuleManager().getModules();
        List<Module> result = new ArrayList<>();
        for (Module m : all) {
            if (hasSilentAim(m)) {
                result.add(m);
            }
        }
        // Sort by category first, then module name
        result.sort(Comparator.comparing((Module m) -> m.getCategory().ordinal())
                .thenComparing(Module::getName));
        return result;
    }

    /**
     * Checks if Silent Aim or a silent rotation/interaction is currently enabled on the specified module.
     */
    public boolean isSilent(Module module) {
        if (module == null) return false;
        for (Setting s : module.getSettings()) {
            if (!isSilentSetting(s)) continue;
            if (s instanceof BooleanSetting b && isSilentToggle(b) && b.isEnabled()) return true;
            if (s instanceof interstellarTikiTiks.apexx.client.module.ModeSetting m && "silent".equalsIgnoreCase(m.getCurrentMode())) return true;
        }
        return false;
    }

    /**
     * Checks if Silent Aim is currently enabled on a module by name.
     */
    public boolean isSilent(String moduleName) {
        if (moduleName == null) return false;
        Module m = findModule(moduleName);
        return isSilent(m);
    }

    /**
     * Sets the Silent Aim state for a module and saves config.
     * For BooleanSettings, sets the value to enabled.
     * For ModeSettings, sets to "Silent" if enabled, or the preferred non-silent mode if disabled.
     */
    public void setSilent(Module module, boolean enabled) {
        if (module == null) return;
        boolean changed = false;
        for (Setting s : module.getSettings()) {
            if (!isSilentSetting(s)) continue;
            if (s instanceof BooleanSetting b) {
                if (!isSilentToggle(b)) continue;
                b.setEnabled(enabled);
                changed = true;
            } else if (s instanceof interstellarTikiTiks.apexx.client.module.ModeSetting m) {
                if (enabled) {
                    for (String mode : m.getModes()) {
                        if ("silent".equalsIgnoreCase(mode)) {
                            m.setCurrentMode(mode);
                            changed = true;
                            break;
                        }
                    }
                } else {
                    String target = null;
                    for (String pref : new String[]{"Legit", "LEGIT", "Smooth", "SMOOTH", "Client", "CLIENT", "None", "NONE", "Off", "OFF"}) {
                        for (String mode : m.getModes()) {
                            if (pref.equalsIgnoreCase(mode)) {
                                target = mode;
                                break;
                            }
                        }
                        if (target != null) break;
                    }
                    if (target == null) {
                        for (String mode : m.getModes()) {
                            if (!"silent".equalsIgnoreCase(mode)) {
                                target = mode;
                                break;
                            }
                        }
                    }
                    if (target != null) {
                        m.setCurrentMode(target);
                        changed = true;
                    }
                }
            }
        }
        if (changed) {
            persistConfig();
        }
    }

    /**
     * Sets the Silent Aim state for a module by name and saves config.
     */
    public void setSilent(String moduleName, boolean enabled) {
        Module m = findModule(moduleName);
        if (m != null) {
            setSilent(m, enabled);
        }
    }

    /**
     * Sets Silent Aim state across all supported modules simultaneously.
     */
    public void setAll(boolean enabled) {
        for (Module m : getSilentModules()) {
            setSilent(m, enabled);
        }
    }

    /**
     * Sets Silent Aim state across all rotation modules specifically.
     */
    public void setAllRotations(boolean enabled) {
        for (Module m : getSilentModules()) {
            for (Setting s : m.getSettings()) {
                if (s instanceof interstellarTikiTiks.apexx.client.module.ModeSetting md) {
                    boolean hasSilent = false;
                    for (String mode : md.getModes()) {
                        if ("silent".equalsIgnoreCase(mode)) {
                            hasSilent = true;
                            break;
                        }
                    }
                    if (hasSilent) {
                        if (enabled) {
                            for (String mode : md.getModes()) {
                                if ("silent".equalsIgnoreCase(mode)) {
                                    md.setCurrentMode(mode);
                                    break;
                                }
                            }
                        } else {
                            for (String pref : new String[]{"Legit", "LEGIT", "Smooth", "SMOOTH", "Client", "CLIENT", "None", "NONE", "Off", "OFF"}) {
                                boolean found = false;
                                for (String mode : md.getModes()) {
                                    if (pref.equalsIgnoreCase(mode)) {
                                        md.setCurrentMode(mode);
                                        found = true;
                                        break;
                                    }
                                }
                                if (found) break;
                            }
                        }
                    }
                } else if (s instanceof BooleanSetting b && SILENT_AIM_SETTING_NAME.equalsIgnoreCase(b.getName())) {
                    b.setEnabled(enabled);
                }
            }
        }
        persistConfig();
    }

    /**
     * Sets Silent Aim state across all packet-click modules specifically.
     */
    public void setAllPackets(boolean enabled) {
        for (Module m : getSilentModules()) {
            for (Setting s : m.getSettings()) {
                if (s instanceof BooleanSetting b && "Packet Clicks".equalsIgnoreCase(b.getName())) {
                    b.setEnabled(enabled);
                }
            }
        }
        persistConfig();
    }

    /**
     * Returns the count of modules currently having Silent Aim enabled.
     */
    public int getActiveCount() {
        int count = 0;
        for (Module m : getSilentModules()) {
            if (isSilent(m)) count++;
        }
        return count;
    }

    /**
     * Returns the total count of modules supporting Silent Aim.
     */
    public int getTotalCount() {
        return getSilentModules().size();
    }

    public interstellarTikiTiks.apexx.client.util.RotationManager.AntiCheatProfile getAntiCheatProfile() {
        return interstellarTikiTiks.apexx.client.util.RotationManager.getAntiCheatProfile();
    }

    public void setAntiCheatProfile(String modeName) {
        if (modeName == null) return;
        for (interstellarTikiTiks.apexx.client.util.RotationManager.AntiCheatProfile p : interstellarTikiTiks.apexx.client.util.RotationManager.AntiCheatProfile.values()) {
            if (p.name().equalsIgnoreCase(modeName) || p.displayName.toLowerCase().contains(modeName.toLowerCase())) {
                interstellarTikiTiks.apexx.client.util.RotationManager.setAntiCheatProfile(p);
                persistConfig();
                return;
            }
        }
    }

    public boolean isSprintSafetyGuard() {
        return interstellarTikiTiks.apexx.client.util.RotationManager.isSprintSafetyGuard();
    }

    public void setSprintSafetyGuard(boolean enabled) {
        interstellarTikiTiks.apexx.client.util.RotationManager.setSprintSafetyGuard(enabled);
        persistConfig();
    }

    public boolean isSmoothDecay() {
        return interstellarTikiTiks.apexx.client.util.RotationManager.isSmoothDecayEnabled();
    }

    public void setSmoothDecay(boolean enabled) {
        interstellarTikiTiks.apexx.client.util.RotationManager.setSmoothDecayEnabled(enabled);
        persistConfig();
    }

    private Module findModule(String name) {
        if (Apexx.getInstance() == null || Apexx.getInstance().getModuleManager() == null) {
            return null;
        }
        for (Module m : Apexx.getInstance().getModuleManager().getModules()) {
            if (m.getName().equalsIgnoreCase(name) || m.getName().replace(" ", "").equalsIgnoreCase(name.replace(" ", ""))) {
                return m;
            }
        }
        return null;
    }

    private void persistConfig() {
        try {
            ConfigManager cm = ConfigManager.getInstance();
            if (cm != null) {
                String active = cm.getLastLoadedConfigName();
                if (active != null && !active.isEmpty()) {
                    cm.save(active);
                }
            }
        } catch (Throwable ignored) {
        }
    }
}
