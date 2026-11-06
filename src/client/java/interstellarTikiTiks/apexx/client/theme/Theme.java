package interstellarTikiTiks.apexx.client.theme;

import java.awt.Color;

public class Theme {
    public String name;
    public Color panelBg;
    public Color headerBg;
    public Color moduleEnabledText;
    public Color moduleDisabledText;
    public Color textColor;
    public Color moduleEnabledBg;
    public Color moduleDisabledBg;
    public Color grad1;
    public Color grad2;

    public Theme(String name, Color panelBg, Color headerBg, Color moduleEnabledText, Color moduleDisabledText, Color textColor, Color moduleEnabledBg, Color moduleDisabledBg, Color grad1, Color grad2) {
        this.name = name;
        this.panelBg = panelBg;
        this.headerBg = headerBg;
        this.moduleEnabledText = moduleEnabledText;
        this.moduleDisabledText = moduleDisabledText;
        this.textColor = textColor;
        this.moduleEnabledBg = moduleEnabledBg;
        this.moduleDisabledBg = moduleDisabledBg;
        this.grad1 = grad1;
        this.grad2 = grad2;
    }

    public Color getPanelBackgroundColor() {
        return panelBg;
    }

    public Color getAccentColor() {
        return grad1;
    }

    public Color getHeaderTextColor() {
        return textColor;
    }

    public String getName() {
        return name;
    }

    public Color getHeaderBackgroundColor() {
        return headerBg;
    }

    public Color getModuleEnabledTextColor() {
        return moduleEnabledText;
    }

    public Color getModuleDisabledTextColor() {
        return moduleDisabledText;
    }

    public Color getModuleEnabledBackgroundColor() {
        return moduleEnabledBg;
    }

    public Color getModuleDisabledBackgroundColor() {
        return moduleDisabledBg;
    }

    public Color getSecondaryAccentColor() {
        return grad2;
    }
}
