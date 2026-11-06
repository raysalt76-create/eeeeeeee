package interstellarTikiTiks.apexx.client.hud;

import interstellarTikiTiks.apexx.client.gui.gpuui.framework.UiDisplayList;
import interstellarTikiTiks.apexx.client.gui.gpuui.framework.UiDrawCommand;
import interstellarTikiTiks.apexx.client.module.BooleanSetting;
import interstellarTikiTiks.apexx.client.module.Setting;
import interstellarTikiTiks.apexx.client.module.SliderSetting;
import net.minecraft.client.MinecraftClient;

import java.util.List;
import java.util.Locale;

/**
 * Your ping and the server's estimated tick rate, with a one-minute history graph (one sample per
 * second, see {@link ServerTps}). TPS is measured from world-time packet intervals.
 */
public final class PingTpsHudElement extends GpuWidget {
    private static final int PING_COLOUR = 0xFF6FB8FF;
    private static final int TPS_GOOD = 0xFF67DCA8;
    private static final int TPS_MID = 0xFFFFC45C;
    private static final int TPS_BAD = 0xFFFF7772;

    private final BooleanSetting showPing = new BooleanSetting("Ping", true)
            .setDescription("Your latency to the server from the player list.");
    private final BooleanSetting showTps = new BooleanSetting("TPS", true)
            .setDescription("Server tick rate, estimated from how fast the server's clock advances (20 = healthy).");
    private final BooleanSetting showGraph = new BooleanSetting("Graph", true)
            .setDescription("History of the last minute, one sample per second.");
    private final SliderSetting graphHeight = new SliderSetting("Graph Height", 26.0, 14.0, 60.0, 0)
            .setVisibility(this.showGraph::isEnabled);
    private final SliderSetting textSize = new SliderSetting("Text Size", 10.0, 8.0, 16.0, 0);

    public PingTpsHudElement(String name, double defaultX, double defaultY) {
        super(name, defaultX, defaultY);
        setWidth(150);
        setHeight(60);
    }

    @Override
    protected List<Setting> elementSettings() {
        return List.of(showPing, showTps, showGraph, graphHeight, textSize);
    }

    @Override
    public void appendGpu(UiDisplayList.Builder b, int layoutWidth, int layoutHeight, float delta,
                          float opacity, boolean editorPreview) {
        boolean ping = showPing.isEnabled(), tps = showTps.isEnabled();
        if (!ping && !tps) {
            if (!editorPreview) {
                setWidth(0);
                setHeight(0);
                return;
            }
            ping = true;
        }
        MinecraftClient mc = MinecraftClient.getInstance();
        boolean live = mc != null && mc.player != null;
        boolean placeholder = !live && editorPreview;
        int pingValue = live ? ServerTps.ping(mc) : (placeholder ? 42 : -1);
        double tpsValue = live ? ServerTps.INSTANCE.tps() : (placeholder ? 19.8 : -1.0);

        float font = (float) Math.max(8.0, Math.min(16.0, textSize.getPreciseValue()));
        float label = Math.max(6.5f, font - 2.5f);
        String pingText = pingValue < 0 ? "--" : pingValue + " ms";
        String tpsText = tpsValue < 0 ? "--" : String.format(Locale.ROOT, "%.1f", tpsValue);
        int pingColour = pingValue < 0 ? HudTheme.muted() : pingValue < 80 ? TPS_GOOD : pingValue < 160 ? TPS_MID : TPS_BAD;
        int tpsColour = tpsColour(tpsValue);

        float pad = 9f;
        float rowWidth = 0f;
        if (ping) rowWidth += textWidth("PING", label) + 5f + textWidth(pingText, font);
        if (ping && tps) rowWidth += 14f;
        if (tps) rowWidth += textWidth("TPS", label) + 5f + textWidth(tpsText, font);
        boolean graph = showGraph.isEnabled();
        float width = Math.max(rowWidth + pad * 2, graph ? 140f : 0f);
        float rowHeight = font + 12f;
        float gh = graph ? (float) Math.max(14.0, Math.min(60.0, graphHeight.getPreciseValue())) : 0f;
        float height = rowHeight + (graph ? gh + 7f : 0f);
        float x = (float) getX(), y = (float) getY();
        panel(b, x, y, width, height, opacity);

        float cx = x + pad, ty = y + (rowHeight - font) * .5f;
        if (ping) {
            b.text(cx, ty + 1.5f, label, tint(HudTheme.muted(), opacity), "PING", UiDrawCommand.TextWeight.SEMIBOLD);
            cx += textWidth("PING", label) + 5f;
            b.text(cx, ty, font, tint(pingColour, opacity), pingText, UiDrawCommand.TextWeight.SEMIBOLD);
            cx += textWidth(pingText, font) + 14f;
        }
        if (tps) {
            b.text(cx, ty + 1.5f, label, tint(HudTheme.muted(), opacity), "TPS", UiDrawCommand.TextWeight.SEMIBOLD);
            cx += textWidth("TPS", label) + 5f;
            b.text(cx, ty, font, tint(tpsColour, opacity), tpsText, UiDrawCommand.TextWeight.SEMIBOLD);
        }
        if (!graph) return;

        float gx = x + 8f, gy = y + rowHeight, gw = width - 16f;
        b.rect(gx, gy, gw, gh, 3f, tint(0x40000000, opacity));
        b.border(gx, gy, gw, gh, 3f, .7f, tint(HudTheme.rim(0x26), opacity));
        int count = placeholder ? ServerTps.HISTORY : ServerTps.INSTANCE.historySize();
        if (count <= 0) {
            String waiting = "COLLECTING";
            b.text(gx + (gw - textWidth(waiting, 7f)) * .5f, gy + (gh - 7f) * .5f, 7f,
                    tint(HudTheme.subtle(), opacity), waiting, UiDrawCommand.TextWeight.SEMIBOLD);
            return;
        }
        // Newest sample at the right edge; the bars fill from the right as history accumulates.
        float step = gw / ServerTps.HISTORY;
        float maxPing = 150f;
        for (int i = 0; i < count; i++) {
            float p = placeholder ? 38f + 14f * (float) Math.sin(i * .35f) : ServerTps.INSTANCE.pingAt(i);
            maxPing = Math.max(maxPing, p);
        }
        float inner = gh - 3f;
        for (int i = 0; i < count; i++) {
            float bx = gx + gw - (count - i) * step;
            if (ping) {
                float p = placeholder ? 38f + 14f * (float) Math.sin(i * .35f) : ServerTps.INSTANCE.pingAt(i);
                float bh = Math.max(1f, Math.min(1f, p / maxPing) * inner);
                b.rect(bx, gy + gh - 1.5f - bh, Math.max(.5f, step - .5f), bh, 0, tint(PING_COLOUR, opacity * .45f));
            }
            if (tps) {
                float t = placeholder ? 19.6f + .4f * (float) Math.cos(i * .5f) : ServerTps.INSTANCE.tpsAt(i);
                float ty2 = gy + 1.5f + (1f - Math.max(0f, Math.min(20f, t)) / 20f) * (inner - 1.5f);
                b.rect(bx, ty2, Math.max(.8f, step), 1.5f, 0, tint(tpsColour(t), opacity * .95f));
            }
        }
    }

    private static int tpsColour(double tps) {
        if (tps < 0) return HudTheme.muted();
        return tps >= 18.0 ? TPS_GOOD : tps >= 14.0 ? TPS_MID : TPS_BAD;
    }
}
