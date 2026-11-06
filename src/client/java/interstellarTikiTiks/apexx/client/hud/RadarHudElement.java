package interstellarTikiTiks.apexx.client.hud;

import interstellarTikiTiks.apexx.client.friend.FriendManager;
import interstellarTikiTiks.apexx.client.gui.gpuui.framework.UiDisplayList;
import interstellarTikiTiks.apexx.client.gui.gpuui.framework.UiDrawCommand;
import interstellarTikiTiks.apexx.client.module.BooleanSetting;
import interstellarTikiTiks.apexx.client.module.ColorSetting;
import interstellarTikiTiks.apexx.client.module.ModeSetting;
import interstellarTikiTiks.apexx.client.module.Setting;
import interstellarTikiTiks.apexx.client.module.SliderSetting;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.math.Vec3d;

import java.awt.Color;
import java.util.List;

/**
 * Top-down mini radar of nearby players, rotated so your view direction points up. Friends
 * ({@link FriendManager}) get their own colour. Players outside the range can be pinned to the edge.
 */
public final class RadarHudElement extends GpuWidget {
    private final SliderSetting size = new SliderSetting("Size", 110.0, 70.0, 180.0, 0);
    private final SliderSetting range = new SliderSetting("Range", 48.0, 8.0, 160.0, 0)
            .setDescription("Blocks from you to the radar's edge.");
    private final ModeSetting shape = new ModeSetting("Shape", "Circle", "Circle", "Square");
    private final BooleanSetting clampToEdge = new BooleanSetting("Pin Out-Of-Range", true)
            .setDescription("Players beyond the range stay on the edge (dimmed) instead of disappearing.");
    private final BooleanSetting showNames = new BooleanSetting("Names", false);
    private final BooleanSetting showFriends = new BooleanSetting("Show Friends", true);
    private final ColorSetting enemyColour = new ColorSetting("Player Color", new Color(255, 119, 114));
    private final ColorSetting friendColour = new ColorSetting("Friend Color", new Color(103, 220, 168));

    public RadarHudElement(String name, double defaultX, double defaultY) {
        super(name, defaultX, defaultY);
        setWidth(110);
        setHeight(110);
    }

    @Override
    protected List<Setting> elementSettings() {
        return List.of(size, range, shape, clampToEdge, showNames, showFriends, enemyColour, friendColour);
    }

    @Override
    public void appendGpu(UiDisplayList.Builder b, int layoutWidth, int layoutHeight, float delta,
                          float opacity, boolean editorPreview) {
        MinecraftClient mc = MinecraftClient.getInstance();
        boolean live = mc != null && mc.player != null && mc.world != null;
        if (!live && !editorPreview) {
            setWidth(0);
            setHeight(0);
            return;
        }
        float s = (float) Math.max(70.0, Math.min(180.0, size.getPreciseValue()));
        float x = (float) getX(), y = (float) getY();
        boolean circle = shape.is("Circle");
        float radius = circle ? s * .5f : 8f;
        setWidth(s);
        setHeight(s);
        if (isEnabled()) {
            GpuHudRenderer.glassPane(b, x, y, s, s, radius, opacity, 1f);
        } else {
            GpuHudRenderer.untoggledPane(b, x, y, s, s, radius);
        }
        float cx = x + s * .5f, cy = y + s * .5f;
        float half = s * .5f - 6f;
        int grid = tint(HudTheme.rim(0x22), opacity);
        b.rect(cx - .5f, y + 6f, 1f, s - 12f, 0, grid);
        b.rect(x + 6f, cy - .5f, s - 12f, 1f, 0, grid);
        if (circle) {
            b.border(cx - half * .5f, cy - half * .5f, half, half, half * .5f, .7f, grid);
            b.border(cx - half, cy - half, half * 2f, half * 2f, half, .7f, grid);
        } else {
            b.border(cx - half * .5f, cy - half * .5f, half, half, 2f, .7f, grid);
        }

        double rangeBlocks = Math.max(8.0, Math.min(160.0, range.getPreciseValue()));
        float scale = (float) (half / rangeBlocks);
        int enemy = colour(enemyColour.getValue());
        int friend = colour(friendColour.getValue());

        b.clipPush(x, y, s, s, radius);
        if (live) {
            float t = Float.isFinite(delta) ? Math.max(0f, Math.min(1f, delta)) : 1f;
            double yaw = Math.toRadians(CompassHudElement.viewYaw(mc, t));
            double sin = Math.sin(yaw), cos = Math.cos(yaw);
            Vec3d self = mc.player.getLerpedPos(t);
            FriendManager friends = FriendManager.getInstance();
            for (PlayerEntity other : mc.world.getPlayers()) {
                if (other == null || other == mc.player || other.isRemoved() || other.isSpectator()) continue;
                boolean isFriend = friends != null && friends.isFriend(other.getUuid());
                if (isFriend && !showFriends.isEnabled()) continue;
                Vec3d pos = other.getLerpedPos(t);
                double dx = pos.x - self.x, dz = pos.z - self.z;
                // View direction up: right = (-cos, -sin), forward = (-sin, cos) in world XZ.
                float sx = (float) (-dx * cos - dz * sin) * scale;
                float sy = (float) (dx * sin - dz * cos) * scale;
                dot(b, cx, cy, half, circle, sx, sy, isFriend ? friend : enemy, opacity,
                        showNames.isEnabled() ? other.getName().getString() : null);
            }
        } else {
            dot(b, cx, cy, half, circle, half * .45f, -half * .35f, enemy, opacity, showNames.isEnabled() ? "Opponent" : null);
            dot(b, cx, cy, half, circle, -half * .3f, half * .5f, friend, opacity, showNames.isEnabled() ? "Friend" : null);
            dot(b, cx, cy, half, circle, half * 1.4f, half * .2f, enemy, opacity, null);
        }
        b.clipPop();
        // You: a small upward marker in the centre.
        b.rect(cx - 1.5f, cy - 4f, 3f, 8f, 1.5f, tint(HudTheme.accent(), opacity));
        b.circle(cx, cy - 4f, 2.2f, tint(HudTheme.accent(), opacity));
        String rangeText = rangeLabel(rangeBlocks);
        b.text(cx - textWidth(rangeText, 6.5f) * .5f, y + s - 13f, 6.5f, tint(HudTheme.subtle(), opacity * .8f),
                rangeText, UiDrawCommand.TextWeight.SEMIBOLD);
    }

    private void dot(UiDisplayList.Builder b, float cx, float cy, float half, boolean circle, float sx, float sy,
                     int colour, float opacity, String name) {
        float alpha = opacity;
        float dist = circle ? (float) Math.hypot(sx, sy) : Math.max(Math.abs(sx), Math.abs(sy));
        if (dist > half) {
            if (!clampToEdge.isEnabled()) return;
            float k = half / dist;
            sx *= k;
            sy *= k;
            alpha *= .45f;
        }
        float px = cx + sx, py = cy + sy;
        b.circle(px, py, 2.6f, tint(colour, alpha));
        b.circle(px, py, 1.1f, tint(0xFFFFFFFF, alpha * .8f));
        if (name != null && !name.isEmpty()) {
            String label = name.length() > 12 ? name.substring(0, 11) + "…" : name;
            b.text(px - textWidth(label, 6f) * .5f, py + 3.5f, 6f, tint(HudTheme.text(), alpha * .9f), label);
        }
    }

    private static String rangeLabel(double blocks) {
        return Math.round(blocks) + "m";
    }

    private static int colour(Color c) {
        if (c == null) return 0xFFFFFFFF;
        return 0xFF000000 | (c.getRed() << 16) | (c.getGreen() << 8) | c.getBlue();
    }
}
