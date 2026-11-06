package interstellarTikiTiks.apexx.client.theme;

import interstellarTikiTiks.apexx.client.gui.gpuui.framework.UiThemeMaterial;

import java.awt.Color;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.List;

public class ThemeManager {
    private static ThemeManager instance;
    private final List<Theme> themes = new CopyOnWriteArrayList<>();
    private Theme currentTheme;

    private ThemeManager() {
        // Register modern GPU UI themes
        for (UiThemeMaterial.Theme mat : UiThemeMaterial.themes()) {
            Color accent = new Color(mat.accent(), true);
            Color base = new Color(mat.panelBase(), true);
            Color text = new Color(mat.text(), true);
            Color muted = new Color(mat.muted(), true);
            Color deep = new Color(mat.deep(), true);
            Color toggleOff = new Color(mat.toggleOff(), true);
            themes.add(new Theme(mat.name(),
                    base, deep, accent, muted, text,
                    accent, toggleOff, accent, deep));
        }

        // Legacy palette themes for backward compatibility
        themes.add(new Theme("Apexx Silver",
                new Color(13, 14, 16, 220),
                new Color(220, 225, 235, 240),
                new Color(240, 245, 255), Color.LIGHT_GRAY, Color.WHITE,
                new Color(220, 225, 235, 90),
                new Color(40, 42, 48, 120),
                new Color(60, 64, 72, 220),
                new Color(245, 248, 255, 240)
        ));
        themes.add(new Theme("Apexx Fire",
                new Color(10, 10, 10, 200),
                new Color(255, 60, 0, 220),
                Color.CYAN, Color.GRAY, Color.WHITE,
                new Color(0, 170, 170, 80),
                new Color(80, 80, 80, 60),
                new Color(180, 30, 0, 220),
                new Color(255, 100, 60, 220)
        ));
        themes.add(new Theme("Dark",
                new Color(21, 21, 21, 200),
                new Color(31, 31, 31, 220),
                new Color(0, 170, 170), Color.GRAY, Color.WHITE,
                new Color(0, 170, 170, 80),
                new Color(80, 80, 80, 60),
                new Color(0x00008B),
                Color.CYAN
        ));
        themes.add(new Theme("Light",
                new Color(90, 90, 90, 200),
                new Color(200, 200, 200, 220),
                new Color(0, 128, 255), new Color(80, 80, 80), Color.BLACK,
                new Color(0, 128, 255, 80),
                new Color(150, 150, 150, 60),
                new Color(0, 90, 180, 80),
                new Color(80, 170, 255, 80)
        ));

        currentTheme = themes.get(0);
    }

    public static ThemeManager getInstance() {
        if (instance == null) {
            instance = new ThemeManager();
        }
        return instance;
    }

    public Theme getCurrentTheme() {
        return currentTheme;
    }

    public void setCurrentTheme(String name) {
        if (name == null || name.isBlank()) return;
        String trimmed = name.trim();
        for (Theme theme : themes) {
            if (theme.name.equalsIgnoreCase(trimmed)) {
                this.currentTheme = theme;
                return;
            }
        }
        // Normalize against ClickGUIModule normalization if applicable
        String normalized = interstellarTikiTiks.apexx.client.module.implementation.ClickGUIModule.normalizeThemeName(trimmed);
        for (Theme theme : themes) {
            if (theme.name.equalsIgnoreCase(normalized)) {
                this.currentTheme = theme;
                return;
            }
        }
        // Dynamic fallback entry so custom or external theme names are never discarded
        Theme fallback = new Theme(trimmed,
                new Color(13, 14, 16, 220),
                new Color(220, 225, 235, 240),
                Color.WHITE, Color.LIGHT_GRAY, Color.WHITE,
                Color.GRAY, Color.DARK_GRAY, Color.WHITE, Color.GRAY);
        themes.add(fallback);
        this.currentTheme = fallback;
    }

    public List<Theme> getThemes() {
        return themes;
    }
}
