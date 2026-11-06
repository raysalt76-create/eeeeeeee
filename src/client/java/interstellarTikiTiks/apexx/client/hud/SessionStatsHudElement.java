package interstellarTikiTiks.apexx.client.hud;

import interstellarTikiTiks.apexx.client.gui.gpuui.framework.UiDisplayList;
import interstellarTikiTiks.apexx.client.gui.gpuui.framework.UiDrawCommand;
import interstellarTikiTiks.apexx.client.module.BooleanSetting;
import interstellarTikiTiks.apexx.client.module.Setting;
import interstellarTikiTiks.apexx.client.module.SliderSetting;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Kills, deaths, K/D, totem pops and time played for the current server session ({@link SessionStats}). */
public final class SessionStatsHudElement extends GpuWidget {
    private final BooleanSetting showKills = new BooleanSetting("Kills", true);
    private final BooleanSetting showDeaths = new BooleanSetting("Deaths", true);
    private final BooleanSetting showKd = new BooleanSetting("K/D", true);
    private final BooleanSetting showPops = new BooleanSetting("Totem Pops", true)
            .setDescription("Your own pops and the pops of players you hit.");
    private final BooleanSetting showTime = new BooleanSetting("Session Time", true);
    private final BooleanSetting countMobs = new BooleanSetting("Count Mobs", false)
            .setDescription("Also credit kills on mobs, not only players.");
    private final SliderSetting creditWindow = new SliderSetting("Kill Credit Window", 3.0, 1.0, 10.0, 1)
            .setDescription("Seconds after your last hit that the target's death still counts as your kill.");
    private final SliderSetting textSize = new SliderSetting("Text Size", 9.0, 7.0, 14.0, 0);

    public SessionStatsHudElement(String name, double defaultX, double defaultY) {
        super(name, defaultX, defaultY);
        setWidth(140);
        setHeight(80);
        SessionStats.config = this;
    }

    boolean countMobs() { return countMobs.isEnabled(); }

    long creditWindowMs() {
        double s = creditWindow.getPreciseValue();
        return Double.isFinite(s) ? Math.round(Math.max(1.0, Math.min(10.0, s)) * 1000.0) : 3000L;
    }

    @Override
    protected List<Setting> elementSettings() {
        return List.of(showKills, showDeaths, showKd, showPops, showTime, countMobs, creditWindow, textSize);
    }

    @Override
    public void appendGpu(UiDisplayList.Builder b, int layoutWidth, int layoutHeight, float delta,
                          float opacity, boolean editorPreview) {
        int kills = SessionStats.kills(), deaths = SessionStats.deaths();
        List<String[]> rows = new ArrayList<>(5);
        List<Integer> colours = new ArrayList<>(5);
        if (showKills.isEnabled()) { rows.add(new String[]{"KILLS", Integer.toString(kills)}); colours.add(0xFF67DCA8); }
        if (showDeaths.isEnabled()) { rows.add(new String[]{"DEATHS", Integer.toString(deaths)}); colours.add(0xFFFF7772); }
        if (showKd.isEnabled()) {
            double kd = deaths == 0 ? kills : kills / (double) deaths;
            rows.add(new String[]{"K/D", String.format(Locale.ROOT, "%.2f", kd)});
            colours.add(HudTheme.accent());
        }
        if (showPops.isEnabled()) {
            rows.add(new String[]{"POPS", SessionStats.selfPops() + " / " + SessionStats.enemyPops()});
            colours.add(0xFFFFC45C);
        }
        if (showTime.isEnabled()) {
            rows.add(new String[]{"TIME", SessionStats.formatDuration(SessionStats.sessionMillis())});
            colours.add(HudTheme.text());
        }
        if (rows.isEmpty()) {
            if (!editorPreview) {
                setWidth(0);
                setHeight(0);
                return;
            }
            rows.add(new String[]{"STATUS", "NOTHING SHOWN"});
            colours.add(HudTheme.muted());
        }

        float font = (float) Math.max(7.0, Math.min(14.0, textSize.getPreciseValue()));
        float labelFont = Math.max(6.5f, font - 1.5f);
        float rowHeight = font + 7f;
        float pad = 8f;
        float width = 120f;
        for (String[] row : rows) {
            width = Math.max(width, pad * 2 + textWidth(row[0], labelFont) + 16f + textWidth(row[1], font));
        }
        float height = 22f + rows.size() * rowHeight + pad - 2f;
        float x = (float) getX(), y = (float) getY();
        panel(b, x, y, width, height, opacity);
        caption(b, x + pad, y + 6f, "SESSION", opacity);
        b.rect(x + pad, y + 17f, width - pad * 2, 1f, 0, tint(HudTheme.rim(0x30), opacity));
        float cy = y + 21f;
        for (int i = 0; i < rows.size(); i++) {
            String[] row = rows.get(i);
            b.text(x + pad, cy + 2f, labelFont, tint(HudTheme.muted(), opacity), row[0],
                    UiDrawCommand.TextWeight.SEMIBOLD);
            float vw = textWidth(row[1], font);
            b.text(x + width - pad - vw, cy + 1f, font, tint(colours.get(i), opacity), row[1],
                    UiDrawCommand.TextWeight.SEMIBOLD);
            cy += rowHeight;
        }
    }
}
