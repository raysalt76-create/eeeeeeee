package interstellarTikiTiks.apexx.client.hud;

import interstellarTikiTiks.apexx.client.gui.gpuui.framework.UiDisplayList;
import interstellarTikiTiks.apexx.client.gui.gpuui.framework.UiDrawCommand;
import interstellarTikiTiks.apexx.client.module.BooleanSetting;
import interstellarTikiTiks.apexx.client.module.Setting;
import interstellarTikiTiks.apexx.client.module.SliderSetting;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.Camera;

import java.util.List;

/** Horizontal compass ribbon: cardinal/intercardinal labels and degree ticks scrolling with your view. */
public final class CompassHudElement extends GpuWidget {
    private static final String[] POINTS = {"N", "NE", "E", "SE", "S", "SW", "W", "NW"};

    private final SliderSetting ribbonWidth = new SliderSetting("Width", 200.0, 120.0, 420.0, 0);
    private final SliderSetting fieldOfView = new SliderSetting("Field Of View", 150.0, 60.0, 300.0, 0)
            .setDescription("How many degrees of heading the ribbon spans from edge to edge.");
    private final BooleanSetting showDegrees = new BooleanSetting("Degrees", true)
            .setDescription("Degree numbers between the letters (30, 60, 120...), and your exact heading under the marker.");
    private final BooleanSetting intercardinals = new BooleanSetting("NE / SE / SW / NW", true);

    public CompassHudElement(String name, double defaultX, double defaultY) {
        super(name, defaultX, defaultY);
        setWidth(200);
        setHeight(28);
    }

    @Override
    protected List<Setting> elementSettings() {
        return List.of(ribbonWidth, fieldOfView, showDegrees, intercardinals);
    }

    /** Compass heading in degrees, 0 = north, 90 = east; null without a player (outside the editor). */
    static Float heading(MinecraftClient mc, float delta) {
        if (mc == null || mc.player == null) return null;
        return Float.valueOf(wrap360(viewYaw(mc, delta) + 180f));
    }

    /** The yaw the player sees (the camera, so silent rotations do not spin the HUD). */
    static float viewYaw(MinecraftClient mc, float delta) {
        Camera camera = mc.gameRenderer == null ? null : mc.gameRenderer.getCamera();
        if (camera != null && camera.isReady()) {
            // Third-person front view looks back at you: the camera faces the opposite way.
            boolean front = mc.options != null && mc.options.getPerspective().isFrontView();
            return front ? camera.getYaw() + 180f : camera.getYaw();
        }
        float t = Float.isFinite(delta) ? Math.max(0f, Math.min(1f, delta)) : 1f;
        return mc.player.getYaw(t);
    }

    static float wrap360(float degrees) {
        float d = degrees % 360f;
        return d < 0f ? d + 360f : d;
    }

    @Override
    public void appendGpu(UiDisplayList.Builder b, int layoutWidth, int layoutHeight, float delta,
                          float opacity, boolean editorPreview) {
        MinecraftClient mc = MinecraftClient.getInstance();
        Float live = heading(mc, delta);
        if (live == null && !editorPreview) {
            setWidth(0);
            setHeight(0);
            return;
        }
        float heading = live == null ? 32f : live;
        float width = (float) Math.max(120.0, Math.min(420.0, ribbonWidth.getPreciseValue()));
        float fov = (float) Math.max(60.0, Math.min(300.0, fieldOfView.getPreciseValue()));
        boolean degrees = showDegrees.isEnabled();
        float height = degrees ? 30f : 22f;
        float x = (float) getX(), y = (float) getY();
        panel(b, x, y, width, height, opacity);

        float inset = 6f;
        float left = x + inset, right = x + width - inset, centre = x + width * .5f;
        float pxPerDeg = (right - left) / fov;
        b.clipPush(left, y, right - left, height, 0f);
        int first = (int) Math.floor((heading - fov * .5f) / 15f) * 15;
        int last = (int) Math.ceil((heading + fov * .5f) / 15f) * 15;
        for (int deg = first; deg <= last; deg += 15) {
            float sx = centre + (deg - heading) * pxPerDeg;
            if (sx < left - 12f || sx > right + 12f) continue;
            // Fade toward the ribbon ends.
            float edge = Math.abs(sx - centre) / ((right - left) * .5f);
            float fade = opacity * Math.max(0f, Math.min(1f, 1.25f - edge));
            int norm = Math.floorMod(deg, 360);
            if (norm % 45 == 0) {
                String point = POINTS[norm / 45];
                boolean cardinal = norm % 90 == 0;
                if (!cardinal && !intercardinals.isEnabled()) {
                    b.rect(sx - .5f, y + 6f, 1f, 5f, 0, tint(HudTheme.subtle(), fade));
                    continue;
                }
                float size = cardinal ? 9.5f : 7.5f;
                int colour = norm == 0 ? 0xFFFF6B6B : cardinal ? HudTheme.text() : HudTheme.muted();
                b.text(sx - textWidth(point, size) * .5f, y + (cardinal ? 4f : 5f), size, tint(colour, fade), point,
                        UiDrawCommand.TextWeight.SEMIBOLD);
            } else {
                b.rect(sx - .5f, y + 6f, 1f, 4f, 0, tint(HudTheme.subtle(), fade));
                if (degrees && norm % 15 == 0 && norm % 45 != 0 && norm % 30 == 0) {
                    String n = Integer.toString(norm);
                    b.text(sx - textWidth(n, 6f) * .5f, y + 12f, 6f, tint(HudTheme.subtle(), fade * .8f), n);
                }
            }
        }
        b.clipPop();
        // Centre marker.
        b.rect(centre - .75f, y + 1.5f, 1.5f, 3f, .5f, tint(HudTheme.accent(), opacity));
        if (degrees) {
            String exact = Integer.toString(Math.round(heading) % 360) + "°";
            b.text(centre - textWidth(exact, 7f) * .5f, y + height - 10f, 7f, tint(HudTheme.accent(), opacity), exact,
                    UiDrawCommand.TextWeight.SEMIBOLD);
        }
    }
}
