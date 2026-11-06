package interstellarTikiTiks.apexx.client.module.implementation.render;

import interstellarTikiTiks.apexx.client.module.BooleanSetting;
import interstellarTikiTiks.apexx.client.module.Category;
import interstellarTikiTiks.apexx.client.module.ModeSetting;
import interstellarTikiTiks.apexx.client.module.Module;
import interstellarTikiTiks.apexx.client.module.hooks.HudRestyleProvider;

/**
 * Restyles vanilla's scoreboard sidebar and boss bars as rounded translucent cards in the HUD theme
 * (drawn by InGameHudMixin / BossBarHudMixin through HudFill). Vanilla still draws all the text.
 */
public final class HudRestyle extends Module implements HudRestyleProvider {
    private final BooleanSetting scoreboard = new BooleanSetting("Scoreboard", true)
            .setDescription("Rounded card behind the sidebar scoreboard instead of vanilla's flat grey boxes.");
    private final BooleanSetting accentLine = new BooleanSetting("Title Accent Line", true)
            .setDescription("A thin accent-coloured line under the scoreboard title.")
            .setVisibility(this.scoreboard::isEnabled);
    private final BooleanSetting bossBars = new BooleanSetting("Boss Bars", true)
            .setDescription("Rounded card and smooth progress bar instead of vanilla's textured boss bars.");
    private final ModeSetting barColour = new ModeSetting("Boss Bar Color", "Accent", "Accent", "Original")
            .setDescription("Accent uses the HUD theme colour; Original keeps each boss bar's own colour.")
            .setVisibility(this.bossBars::isEnabled);

    public HudRestyle() {
        super("HudRestyle", "Gives the vanilla scoreboard sidebar and boss bars rounded, translucent cards in your "
                + "HUD theme colours; their opacity follows Glass Opacity in the HUD editor's Colours. "
                + "The text stays vanilla. Turns itself back to vanilla while SS Proof is on.",
                Category.RENDER);
        addSetting(scoreboard);
        addSetting(accentLine);
        addSetting(bossBars);
        addSetting(barColour);
    }

    @Override public boolean restyleScoreboard() { return scoreboard.isEnabled(); }
    @Override public boolean restyleBossBars() { return bossBars.isEnabled(); }
    @Override public boolean restyleAccentLine() { return accentLine.isEnabled(); }
    @Override public boolean restyleAccentBossBars() { return barColour.is("Accent"); }
}
