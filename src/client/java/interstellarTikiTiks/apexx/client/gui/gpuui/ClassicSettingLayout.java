package interstellarTikiTiks.apexx.client.gui.gpuui;

import interstellarTikiTiks.apexx.client.module.BooleanSetting;
import interstellarTikiTiks.apexx.client.module.ColorSetting;
import interstellarTikiTiks.apexx.client.module.ConfigKeybindListSetting;
import interstellarTikiTiks.apexx.client.module.ModeSetting;
import interstellarTikiTiks.apexx.client.module.Module;
import interstellarTikiTiks.apexx.client.module.Setting;
import interstellarTikiTiks.apexx.client.module.SliderSetting;
import interstellarTikiTiks.apexx.client.module.StringSetting;
import interstellarTikiTiks.apexx.client.module.implementation.KeybindSetting;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Mace setting types and metadata used by the GPU click GUI. */
@Environment(EnvType.CLIENT)
public final class ClassicSettingLayout {
    private ClassicSettingLayout() {}

    public enum Kind { BOOLEAN, SLIDER, MODE, STRING, COLOR, KEYBIND, ACTION }

    public record Row(Setting setting, Kind kind, String name, String description,
                      String section, String dependsOn, boolean visible,
                      List<String> choices, String valueLabel) {
        public boolean hasDescription() {
            return description != null && !description.isBlank();
        }

        public int minimumHeightPx(int modeLines) {
            if (kind == Kind.MODE) return 53 + Math.max(0, modeLines - 1) * 29;
            return kind == Kind.STRING ? 51 : 43;
        }
    }

    public static List<Row> rows(Module module) {
        if (module == null) return List.of();
        List<Row> result = new ArrayList<>();
        for (Setting setting : module.getSettings()) {
            Row row = row(module, setting);
            if (row != null) result.add(row);
        }
        return List.copyOf(result);
    }

    public static List<Row> visibleRows(Module module) {
        return rows(module).stream().filter(Row::visible).toList();
    }

    public static Row row(Module module, Setting setting) {
        if (setting == null) return null;
        Kind kind;
        List<String> choices = List.of();
        String valueLabel = "";

        if (setting instanceof BooleanSetting bool) {
            kind = Kind.BOOLEAN;
            valueLabel = bool.isEnabled() ? "On" : "Off";
        } else if (setting instanceof SliderSetting slider) {
            kind = Kind.SLIDER;
            valueLabel = slider.getValueAsString();
        } else if (setting instanceof ModeSetting mode) {
            kind = Kind.MODE;
            choices = List.copyOf(mode.getModes());
            valueLabel = mode.getCurrentMode();
        } else if (setting instanceof StringSetting string) {
            kind = Kind.STRING;
            valueLabel = string.getValue();
        } else if (setting instanceof ColorSetting color) {
            kind = Kind.COLOR;
            valueLabel = String.format(Locale.ROOT, "#%06X", color.getValue().getRGB() & 0xffffff);
        } else if (setting instanceof KeybindSetting bind) {
            kind = Kind.KEYBIND;
            valueLabel = bind.getModule() == null ? "None" : bind.getModule().getKeyDisplayName();
        } else if (setting instanceof ConfigKeybindListSetting) {
            kind = Kind.ACTION;
            valueLabel = "Manage config hotkeys";
        } else {
            // Recode-only controls and future unknown types are omitted without rendering
            // inert or misleading controls.
            return null;
        }

        boolean visible = setting.isVisible() && isVisibleForDependency(module, setting, new HashSet<>());
        return new Row(setting, kind, setting.getName(), setting.getDescription(),
                setting.getSection(), setting.getDependsOn(), visible, choices, valueLabel);
    }

    private static boolean isVisibleForDependency(Module module, Setting setting, Set<Setting> path) {
        if (setting == null || !path.add(setting)) return false;
        try {
            String dependsOn = setting.getDependsOn();
            if (dependsOn == null || dependsOn.isBlank()) return true;

            for (String rawCondition : dependsOn.split(",", -1)) {
                String condition = rawCondition.trim();
                if (condition.isEmpty()) return false;

                boolean negated = condition.charAt(0) == '!';
                if (negated) condition = condition.substring(1).trim();

                int modeSeparator = condition.indexOf(':');
                String parentName = (modeSeparator < 0 ? condition : condition.substring(0, modeSeparator)).trim();
                if (parentName.isEmpty()) return false;

                Setting parent = module.getSetting(parentName);
                if (parent == null || !isVisibleForDependency(module, parent, path)) return false;

                boolean matches;
                if (modeSeparator >= 0) {
                    String expectedMode = condition.substring(modeSeparator + 1).trim();
                    if (expectedMode.isEmpty() || !(parent instanceof ModeSetting mode)) return false;
                    matches = false;
                    for (String alternative : expectedMode.split("\\|", -1)) {
                        if (mode.getCurrentMode().equalsIgnoreCase(alternative.trim())) {
                            matches = true;
                            break;
                        }
                    }
                } else if (parent instanceof BooleanSetting bool) {
                    matches = bool.isEnabled();
                } else if (parent instanceof SliderSetting slider) {
                    matches = slider.getValue() > slider.getMin();
                } else {
                    return false;
                }

                if (negated == matches) return false;
            }
            return true;
        } finally {
            path.remove(setting);
        }
    }
}
