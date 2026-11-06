package interstellarTikiTiks.apexx.client.hud;

import interstellarTikiTiks.apexx.client.gui.gpuui.framework.UiDisplayList;
import interstellarTikiTiks.apexx.client.gui.gpuui.framework.UiDrawCommand;
import interstellarTikiTiks.apexx.client.module.BooleanSetting;
import interstellarTikiTiks.apexx.client.module.Setting;
import interstellarTikiTiks.apexx.client.module.SliderSetting;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ServerInfo;

import java.util.ArrayList;
import java.util.List;

/** Server address (optionally hidden) and online player count. Time on the server lives in Session Stats. */
public final class ServerInfoHudElement extends GpuWidget {
    private final BooleanSetting showAddress = new BooleanSetting("Address", true);
    private final BooleanSetting hideAddress = new BooleanSetting("Hide Address", false)
            .setDescription("Shows \"Hidden\" instead of the server address, e.g. while streaming.")
            .setVisibility(this.showAddress::isEnabled);
    private final BooleanSetting showPlayers = new BooleanSetting("Players", true)
            .setDescription("Players in the tab list.");
    private final SliderSetting textSize = new SliderSetting("Text Size", 9.0, 7.0, 14.0, 0);

    public ServerInfoHudElement(String name, double defaultX, double defaultY) {
        super(name, defaultX, defaultY);
        setWidth(150);
        setHeight(60);
    }

    @Override
    protected List<Setting> elementSettings() {
        return List.of(showAddress, hideAddress, showPlayers, textSize);
    }

    @Override
    public void appendGpu(UiDisplayList.Builder b, int layoutWidth, int layoutHeight, float delta,
                          float opacity, boolean editorPreview) {
        MinecraftClient mc = MinecraftClient.getInstance();
        boolean live = mc != null && mc.player != null && mc.getNetworkHandler() != null;
        List<String[]> rows = new ArrayList<>(3);
        if (showAddress.isEnabled()) {
            String address;
            if (hideAddress.isEnabled()) address = "Hidden";
            else if (!live) address = editorPreview ? "play.example.net" : "";
            else address = address(mc);
            if (!address.isEmpty()) rows.add(new String[]{"SERVER", shorten(address, 28)});
        }
        if (showPlayers.isEnabled()) {
            int players = live ? mc.getNetworkHandler().getPlayerList().size() : (editorPreview ? 24 : -1);
            if (players >= 0) rows.add(new String[]{"PLAYERS", Integer.toString(players)});
        }
        if (rows.isEmpty()) {
            if (!editorPreview) {
                setWidth(0);
                setHeight(0);
                return;
            }
            rows.add(new String[]{"STATUS", "NOTHING SHOWN"});
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
        caption(b, x + pad, y + 6f, "SERVER INFO", opacity);
        b.rect(x + pad, y + 17f, width - pad * 2, 1f, 0, tint(HudTheme.rim(0x30), opacity));
        float cy = y + 21f;
        for (String[] row : rows) {
            b.text(x + pad, cy + 2f, labelFont, tint(HudTheme.muted(), opacity), row[0],
                    UiDrawCommand.TextWeight.SEMIBOLD);
            float vw = textWidth(row[1], font);
            int colour = "SERVER".equals(row[0]) ? HudTheme.accent() : HudTheme.text();
            b.text(x + width - pad - vw, cy + 1f, font, tint(colour, opacity), row[1],
                    UiDrawCommand.TextWeight.SEMIBOLD);
            cy += rowHeight;
        }
    }

    private static String address(MinecraftClient mc) {
        if (mc.isInSingleplayer()) return "Singleplayer";
        ServerInfo server = mc.getCurrentServerEntry();
        if (server == null || server.address == null || server.address.isBlank()) return "Unknown";
        return server.isLocal() ? "LAN" : server.address;
    }

    private static String shorten(String value, int max) {
        return value.length() <= max ? value : value.substring(0, max - 1) + "…";
    }
}
