package interstellarTikiTiks.apexx.client.module.hooks;

/** Vanilla boss bar / scoreboard sidebar restyle, read by InGameHudMixin and BossBarHudMixin. */
public interface HudRestyleProvider {
    boolean restyleScoreboard();

    boolean restyleBossBars();

    // Card opacity and corner rounding are not set here: they follow the HUD editor's Colours (HudFill).

    /** Accent underline below the scoreboard title. */
    boolean restyleAccentLine();

    /** True: boss bars use the HUD accent; false: the bar's own colour. */
    boolean restyleAccentBossBars();
}
