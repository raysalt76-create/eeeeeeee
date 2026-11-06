package interstellarTikiTiks.apexx.client.module.implementation.render;

import interstellarTikiTiks.apexx.client.module.BooleanSetting;
import interstellarTikiTiks.apexx.client.module.Category;
import interstellarTikiTiks.apexx.client.module.ColorSetting;
import interstellarTikiTiks.apexx.client.module.ModeSetting;
import interstellarTikiTiks.apexx.client.module.Module;
import interstellarTikiTiks.apexx.client.module.SliderSetting;
import interstellarTikiTiks.apexx.client.util.render.PooledBufferAllocator;
import interstellarTikiTiks.apexx.client.util.render.RibbonRenderer;
import interstellarTikiTiks.apexx.client.util.render.ThemeColors;
import interstellarTikiTiks.apexx.client.util.render.TrailBuffer;
import interstellarTikiTiks.apexx.client.util.render.TrailMath;
import interstellarTikiTiks.apexx.client.util.render.types.RenderTypes;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.util.BufferAllocator;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.util.math.Vec3d;

import java.awt.Color;

/**
 * Leaves a fading line along the path you walked. With "Pause When Standing" the trail's clock stops
 * while you stand still, so it does not fade away while you wait. Client-side visual only; memory only.
 */
public final class Breadcrumbs extends Module {
    private static final String LOOK = "Look";
    private static final String BEHAVIOUR = "Behaviour";
    private static final String COLOR = "Color";
    private static final double SPACING = 0.12;
    private static final double TELEPORT_DISTANCE = 8.0;

    private final SliderSetting width = new SliderSetting("Width", 0.05, 0.01, 0.3, 2)
            .setDescription("Width of the trail in blocks.")
            .setSection(LOOK);
    private final SliderSetting fadeTime = new SliderSetting("Fade Time", 15.0, 1.0, 120.0, 0)
            .setDescription("Seconds a point of the trail lasts before it has faded out.")
            .setSection(LOOK);
    private final SliderSetting maxPoints = new SliderSetting("Max Points", 600, 50, 3000, 0)
            .setDescription("Most points kept; the oldest are dropped first. Limits the trail length.")
            .setSection(LOOK);
    private final SliderSetting yOffset = new SliderSetting("Y Offset", 0.1, 0.0, 2.0, 2)
            .setDescription("Height of the trail above your feet.")
            .setSection(LOOK);
    private final BooleanSetting taper = new BooleanSetting("Taper", false)
            .setDescription("Narrows the trail toward its old end.")
            .setSection(LOOK);
    private final SliderSetting opacity = new SliderSetting("Opacity", 0.8, 0.1, 1.0, 2)
            .setDescription("Opacity of the trail.")
            .setSection(LOOK);
    private final BooleanSetting throughWalls = new BooleanSetting("Through Walls", false)
            .setDescription("Draws the trail even when blocks are in front of it.")
            .setSection(LOOK);

    private final BooleanSetting pauseStanding = new BooleanSetting("Pause When Standing", true)
            .setDescription("Stops recording and stops the fade while you stand still.")
            .setSection(BEHAVIOUR);

    private final ModeSetting colorMode = new ModeSetting("Color", ThemeColors.THEME, ThemeColors.THEME,
            ThemeColors.CUSTOM, ThemeColors.RAINBOW, ThemeColors.GRADIENT)
            .setDescription("Theme follows the GUI accent; Gradient blends the accent at the new end into the Gradient End colour.")
            .setSection(COLOR);
    private final ColorSetting customColor = new ColorSetting("Custom Color", new Color(120, 200, 255))
            .setDescription("Trail colour when Color is Custom.")
            .setDependsOn("Color:Custom")
            .setSection(COLOR);
    private final ColorSetting gradientEnd = new ColorSetting("Gradient End", new Color(255, 110, 200))
            .setDescription("Colour at the old end of the trail when Color is Gradient.")
            .setDependsOn("Color:Gradient")
            .setSection(COLOR);

    private final TrailBuffer trail = new TrailBuffer(600);
    private ClientWorld lastWorld;
    private long clockMs;
    private long lastFrameNanos;
    private double lastX = Double.NaN;
    private double lastY;
    private double lastZ;

    public Breadcrumbs() {
        super("Breadcrumbs", "Leaves a fading line along the path you have walked. Visible only to you.", Category.RENDER);
        addSetting(width);
        addSetting(fadeTime);
        addSetting(maxPoints);
        addSetting(yOffset);
        addSetting(taper);
        addSetting(opacity);
        addSetting(throughWalls);
        addSetting(pauseStanding);
        addSetting(colorMode);
        addSetting(customColor);
        addSetting(gradientEnd);
    }

    @Override
    public boolean rendersInWorld() {
        return true;
    }

    @Override
    public void onDisable() {
        clear();
    }

    @Override
    protected void onRuntimeStateReleased() {
        clear();
    }

    private void clear() {
        trail.clear();
        lastWorld = null;
        lastFrameNanos = 0L;
        lastX = Double.NaN;
    }

    @Override
    public void onWorldRender(MatrixStack matrices, float tickDelta) {
        if (mc.player == null || mc.world == null) return;
        if (mc.world != lastWorld) {
            clear();
            lastWorld = mc.world;
        }
        long nowNanos = System.nanoTime();
        long dtMs = lastFrameNanos == 0L ? 0L : Math.min(250L, (nowNanos - lastFrameNanos) / 1_000_000L);
        lastFrameNanos = nowNanos;

        Vec3d pos = mc.player.getLerpedPos(tickDelta);
        double px = pos.x;
        double py = pos.y + yOffset.getValue();
        double pz = pos.z;
        boolean moving = Double.isNaN(lastX)
                || Math.abs(px - lastX) + Math.abs(py - lastY) + Math.abs(pz - lastZ) > 1.0e-3;
        lastX = px;
        lastY = py;
        lastZ = pz;
        if (moving || !pauseStanding.isEnabled()) clockMs += dtMs;

        trail.setCapacity((int) maxPoints.getValue());
        long life = Math.max(100L, Math.round(fadeTime.getValue() * 1000.0));
        trail.pruneOlderThan(clockMs - life);

        double distSq = trail.distanceSqToNewest(px, py, pz);
        if (distSq != Double.POSITIVE_INFINITY && distSq > TELEPORT_DISTANCE * TELEPORT_DISTANCE) {
            trail.clear();
            distSq = Double.POSITIVE_INFINITY;
        }
        if (distSq >= SPACING * SPACING && (moving || !pauseStanding.isEnabled() || trail.isEmpty())) {
            trail.add(px, py, pz, clockMs);
        }
        if (trail.size() < 2) return;

        Vec3d cam = mc.gameRenderer.getCamera().getCameraPos();
        String mode = colorMode.getCurrentMode();
        int head = ThemeColors.resolve(mode, customColor.getValue(), 0.0f);
        int tail = ThemeColors.opaque(gradientEnd.getValue());
        float alpha = (float) opacity.getValue();
        boolean rainbow = ThemeColors.RAINBOW.equals(mode);
        boolean gradient = ThemeColors.GRADIENT.equals(mode);
        int n = trail.size();

        try (BufferAllocator allocator = PooledBufferAllocator.acquire(Math.max(4096, n * 6 * 16))) {
            VertexConsumerProvider.Immediate immediate = VertexConsumerProvider.immediate(allocator);
            RibbonRenderer.trail(immediate.getBuffer(throughWalls.isEnabled() ? RenderTypes.TRIANGLES_NO_DEPTH : RenderTypes.TRIANGLES),
                    matrices.peek().getPositionMatrix(), trail, cam.x, cam.y, cam.z, (float) width.getValue() * 0.5f,
                    clockMs, life, taper.isEnabled(), 1.2f, (index, headT, lifeFrac) -> {
                        int c;
                        if (rainbow) c = ThemeColors.resolve(ThemeColors.RAINBOW, null, index * 0.004f);
                        else if (gradient) c = TrailMath.lerpArgb(tail, head, headT);
                        else c = head;
                        return TrailMath.withAlpha(c, alpha);
                    });
            immediate.draw();
        }
    }
}
