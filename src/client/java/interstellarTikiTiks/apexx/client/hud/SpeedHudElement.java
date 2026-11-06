package interstellarTikiTiks.apexx.client.hud;

import interstellarTikiTiks.apexx.client.gui.gpuui.framework.UiDisplayList;
import interstellarTikiTiks.apexx.client.gui.gpuui.framework.UiDrawCommand;
import interstellarTikiTiks.apexx.client.module.BooleanSetting;
import interstellarTikiTiks.apexx.client.module.ModeSetting;
import interstellarTikiTiks.apexx.client.module.Setting;
import interstellarTikiTiks.apexx.client.module.SliderSetting;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;

import java.util.List;
import java.util.Locale;

/** Horizontal movement speed (blocks per second or km/h), sampled per client tick and smoothed. */
public final class SpeedHudElement extends GpuWidget {
    /** Larger per-tick jumps are teleports / respawns, not movement. */
    private static final double TELEPORT_PER_TICK = 10.0;
    /** Full scale of the bar: roughly elytra cruising speed. */
    private static final double BAR_MAX_BPS = 40.0;

    private static volatile SpeedHudElement instance;
    private static double prevX, prevZ;
    private static boolean hasPrev;
    private static Object prevPlayer;
    private static volatile double smoothedBps;

    private final ModeSetting unit = new ModeSetting("Unit", "b/s", "b/s", "km/h")
            .setDescription("Blocks per second, or kilometres per hour (1 block = 1 metre).");
    private final SliderSetting smoothing = new SliderSetting("Smoothing", 0.6, 0.0, 0.95, 2)
            .setDescription("Higher values steady the number; 0 shows the raw per-tick speed.");
    private final SliderSetting decimals = new SliderSetting("Decimals", 1.0, 0.0, 2.0, 0);
    private final BooleanSetting showBar = new BooleanSetting("Speed Bar", true)
            .setDescription("A bar under the number that fills toward 40 b/s.");
    private final SliderSetting textSize = new SliderSetting("Text Size", 11.0, 8.0, 18.0, 0);

    public SpeedHudElement(String name, double defaultX, double defaultY) {
        super(name, defaultX, defaultY);
        setWidth(90);
        setHeight(24);
        instance = this;
    }

    @Override
    protected List<Setting> elementSettings() {
        return List.of(unit, smoothing, decimals, showBar, textSize);
    }

    /** Client thread, once per tick. */
    public static void tick(MinecraftClient mc) {
        ClientPlayerEntity player = mc == null ? null : mc.player;
        if (player == null) {
            hasPrev = false;
            prevPlayer = null;
            smoothedBps = 0.0;
            return;
        }
        double x = player.getX(), z = player.getZ();
        if (!hasPrev || prevPlayer != player) {
            prevX = x;
            prevZ = z;
            hasPrev = true;
            prevPlayer = player;
            return;
        }
        double dx = x - prevX, dz = z - prevZ;
        prevX = x;
        prevZ = z;
        double perTick = Math.sqrt(dx * dx + dz * dz);
        if (!Double.isFinite(perTick) || perTick > TELEPORT_PER_TICK) return;
        double bps = perTick * 20.0;
        SpeedHudElement cfg = instance;
        double s = cfg == null ? 0.6 : cfg.smoothing.getPreciseValue();
        s = Double.isFinite(s) ? Math.max(0.0, Math.min(0.95, s)) : 0.6;
        double next = smoothedBps * s + bps * (1.0 - s);
        smoothedBps = next < 0.005 ? 0.0 : next;
    }

    @Override
    public void appendGpu(UiDisplayList.Builder b, int layoutWidth, int layoutHeight, float delta,
                          float opacity, boolean editorPreview) {
        MinecraftClient mc = MinecraftClient.getInstance();
        boolean live = mc != null && mc.player != null;
        double bps = live ? smoothedBps : (editorPreview ? 5.6 : 0.0);
        boolean kmh = unit.is("km/h");
        double shown = kmh ? bps * 3.6 : bps;
        int places = (int) Math.max(0, Math.min(2, Math.round(decimals.getPreciseValue())));
        String value = String.format(Locale.ROOT, "%." + places + "f", shown);
        String suffix = kmh ? "km/h" : "b/s";

        float font = (float) Math.max(8.0, Math.min(18.0, textSize.getPreciseValue()));
        float label = Math.max(6.5f, font - 2.5f);
        float pad = 10f;
        boolean bar = showBar.isEnabled();
        float rowHeight = font + 12f;
        float width = Math.max(84f, pad * 2 + textWidth("SPEED", label) + 6f + textWidth(value, font) + 4f
                + textWidth(suffix, label));
        float height = rowHeight + (bar ? 5f : 0f);
        float x = (float) getX(), y = (float) getY();
        panel(b, x, y, width, height, opacity);
        float cx = x + pad, ty = y + (rowHeight - font) * .5f;
        b.text(cx, ty + 1.5f, label, tint(HudTheme.muted(), opacity), "SPEED", UiDrawCommand.TextWeight.SEMIBOLD);
        cx += textWidth("SPEED", label) + 6f;
        b.text(cx, ty, font, tint(HudTheme.text(), opacity), value, UiDrawCommand.TextWeight.SEMIBOLD);
        cx += textWidth(value, font) + 4f;
        b.text(cx, ty + 1.5f, label, tint(HudTheme.subtle(), opacity), suffix);
        if (!bar) return;
        float bx = x + pad, by = y + rowHeight - 2f, bw = width - pad * 2;
        b.rect(bx, by, bw, 2.5f, 1.25f, tint(HudTheme.rim(0x40), opacity));
        float ratio = (float) Math.max(0.0, Math.min(1.0, bps / BAR_MAX_BPS));
        if (ratio > 0.005f) b.rect(bx, by, Math.max(1f, bw * ratio), 2.5f, 1.25f, tint(HudTheme.accent(), opacity));
    }
}
