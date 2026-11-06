package interstellarTikiTiks.apexx.client.util;

import net.minecraft.util.Identifier;

public enum GuiShaderStyle {
    DEFAULT(Identifier.of("dynamic_fps", "curve")),
    PANEL(Identifier.of("dynamic_fps", "gui_panel")),
    PANEL_GLASS(Identifier.of("dynamic_fps", "gui_panel_glass")),
    HEADER(Identifier.of("dynamic_fps", "gui_header")),
    HEADER_GLASS(Identifier.of("dynamic_fps", "gui_header_glass")),
    ROW(Identifier.of("dynamic_fps", "gui_row")),
    ROW_ACTIVE(Identifier.of("dynamic_fps", "gui_row_active")),
    ROW_ENABLED(Identifier.of("dynamic_fps", "gui_row_enabled")),
    ROW_LAST(Identifier.of("dynamic_fps", "gui_row_last")),
    SEARCH(Identifier.of("dynamic_fps", "gui_search")),
    CONTROL(Identifier.of("dynamic_fps", "gui_control")),
    ICON(Identifier.of("dynamic_fps", "gui_icon")),
    TOOLTIP(Identifier.of("dynamic_fps", "gui_tooltip")),
    HUD(Identifier.of("dynamic_fps", "gui_hud")),
    GLOW(Identifier.of("dynamic_fps", "gui_glow")),
    PARTICLE(Identifier.of("dynamic_fps", "gui_particle")),
    PARTICLE_GLOW(Identifier.of("dynamic_fps", "gui_particle_glow"));

    private final Identifier pipelineId;

    GuiShaderStyle(Identifier pipelineId) {
        this.pipelineId = pipelineId;
    }

    public Identifier pipelineId() {
        return pipelineId;
    }
}
