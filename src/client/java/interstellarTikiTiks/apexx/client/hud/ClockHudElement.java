package interstellarTikiTiks.apexx.client.hud;

import interstellarTikiTiks.apexx.client.gui.gpuui.framework.UiDisplayList;
import interstellarTikiTiks.apexx.client.gui.gpuui.framework.UiDrawCommand;
import interstellarTikiTiks.apexx.client.module.BooleanSetting;
import interstellarTikiTiks.apexx.client.module.ModeSetting;
import interstellarTikiTiks.apexx.client.module.Setting;
import interstellarTikiTiks.apexx.client.module.SliderSetting;
import net.minecraft.client.MinecraftClient;

import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;

/** Real (computer) time in 12 or 24 hour format, and the in-game time of day. */
public final class ClockHudElement extends GpuWidget {
    private final BooleanSetting realTime = new BooleanSetting("Real Time", true)
            .setDescription("Your computer's local time.");
    private final ModeSetting format = new ModeSetting("Format", "24h", "24h", "12h");
    private final BooleanSetting seconds = new BooleanSetting("Seconds", false)
            .setVisibility(this.realTime::isEnabled);
    private final BooleanSetting gameTime = new BooleanSetting("In-Game Time", true)
            .setDescription("The world's time of day (day starts at 06:00), plus the day number.");
    private final SliderSetting textSize = new SliderSetting("Text Size", 10.0, 8.0, 16.0, 0);

    public ClockHudElement(String name, double defaultX, double defaultY) {
        super(name, defaultX, defaultY);
        setWidth(90);
        setHeight(24);
    }

    @Override
    protected List<Setting> elementSettings() {
        return List.of(realTime, format, seconds, gameTime, textSize);
    }

    @Override
    public void appendGpu(UiDisplayList.Builder b, int layoutWidth, int layoutHeight, float delta,
                          float opacity, boolean editorPreview) {
        boolean twelve = format.is("12h");
        List<String[]> parts = new ArrayList<>(2);
        if (realTime.isEnabled()) {
            LocalTime now = LocalTime.now();
            parts.add(new String[]{"TIME", formatTime(now.getHour(), now.getMinute(),
                    seconds.isEnabled() ? now.getSecond() : -1, twelve)});
        }
        if (gameTime.isEnabled()) {
            MinecraftClient mc = MinecraftClient.getInstance();
            if (mc != null && mc.world != null) {
                long ticks = mc.world.getTimeOfDay();
                long dayTicks = Math.floorMod(ticks, 24000L);
                int hour = (int) ((dayTicks / 1000L + 6L) % 24L);
                int minute = (int) (dayTicks % 1000L * 60L / 1000L);
                long day = Math.floorDiv(ticks, 24000L) + 1L;
                parts.add(new String[]{"DAY " + day, formatTime(hour, minute, -1, twelve)});
            } else if (editorPreview) {
                parts.add(new String[]{"DAY 3", formatTime(14, 30, -1, twelve)});
            }
        }
        if (parts.isEmpty()) {
            if (!editorPreview) {
                setWidth(0);
                setHeight(0);
                return;
            }
            parts.add(new String[]{"CLOCK", "OFF"});
        }

        float font = (float) Math.max(8.0, Math.min(16.0, textSize.getPreciseValue()));
        float label = Math.max(6.5f, font - 2.5f);
        float pad = 10f, gap = 14f;
        float width = pad * 2;
        for (int i = 0; i < parts.size(); i++) {
            width += textWidth(parts.get(i)[0], label) + 5f + textWidth(parts.get(i)[1], font);
            if (i > 0) width += gap;
        }
        float height = font + 12f;
        float x = (float) getX(), y = (float) getY();
        panel(b, x, y, width, height, opacity);
        float cx = x + pad, ty = y + (height - font) * .5f;
        for (int i = 0; i < parts.size(); i++) {
            if (i > 0) cx += gap;
            String[] part = parts.get(i);
            b.text(cx, ty + 1.5f, label, tint(HudTheme.muted(), opacity), part[0], UiDrawCommand.TextWeight.SEMIBOLD);
            cx += textWidth(part[0], label) + 5f;
            b.text(cx, ty, font, tint(i == 0 ? HudTheme.text() : HudTheme.accent(), opacity), part[1],
                    UiDrawCommand.TextWeight.SEMIBOLD);
            cx += textWidth(part[1], font);
        }
    }

    static String formatTime(int hour, int minute, int second, boolean twelveHour) {
        StringBuilder out = new StringBuilder(11);
        int h = hour;
        if (twelveHour) {
            h = hour % 12;
            if (h == 0) h = 12;
        }
        if (!twelveHour && h < 10) out.append('0');
        out.append(h).append(':');
        if (minute < 10) out.append('0');
        out.append(minute);
        if (second >= 0) {
            out.append(':');
            if (second < 10) out.append('0');
            out.append(second);
        }
        if (twelveHour) out.append(hour < 12 ? " AM" : " PM");
        return out.toString();
    }
}
