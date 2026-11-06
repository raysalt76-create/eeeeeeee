package interstellarTikiTiks.apexx.client.module.implementation.render;

import interstellarTikiTiks.apexx.client.friend.FriendManager;
import interstellarTikiTiks.apexx.client.mixin.GameRendererAccessor;
import interstellarTikiTiks.apexx.client.module.BooleanSetting;
import interstellarTikiTiks.apexx.client.module.Category;
import interstellarTikiTiks.apexx.client.module.ColorSetting;
import interstellarTikiTiks.apexx.client.module.ModeSetting;
import interstellarTikiTiks.apexx.client.module.Module;
import interstellarTikiTiks.apexx.client.module.SliderSetting;
import interstellarTikiTiks.apexx.client.util.render.PooledBufferAllocator;
import interstellarTikiTiks.apexx.client.util.render.ProjectionUtil;
import interstellarTikiTiks.apexx.client.util.render.RibbonRenderer;
import interstellarTikiTiks.apexx.client.util.render.ThemeColors;
import interstellarTikiTiks.apexx.client.util.render.TrailBuffer;
import interstellarTikiTiks.apexx.client.util.render.TrailMath;
import interstellarTikiTiks.apexx.client.util.render.types.RenderTypes;
import interstellarTikiTiks.apexx.client.util.simulation.PearlSimulation;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.render.Camera;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.util.BufferAllocator;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.projectile.thrown.EnderPearlEntity;
import net.minecraft.util.math.Vec3d;
import org.joml.Matrix4f;

import java.awt.Color;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Predicts where thrown ender pearls will land by flying them through the existing pearl simulation
 * ({@link PearlSimulation}, the same physics the pearl modules use) every tick, and marks the spot with a
 * player-sized box, a ring and an optional path line, plus the time left until it lands.
 * Client-side visual only; memory only.
 */
public final class PearlLandingMarker extends Module {
    private static final String WHOSE = "Whose";
    private static final String DISPLAY = "Display";
    private static final String COLOR = "Color";
    private static final int MAX_SIM_TICKS = 200;
    private static final float HALF_WIDTH = 0.3f;
    private static final float HEIGHT = 1.8f;

    private final BooleanSetting enemies = new BooleanSetting("Enemies", true)
            .setDescription("Mark pearls thrown by other players (or with an unknown thrower).").setSection(WHOSE);
    private final BooleanSetting ownPearls = new BooleanSetting("Own", false)
            .setDescription("Also mark your own pearls.").setSection(WHOSE);
    private final BooleanSetting excludeFriends = new BooleanSetting("Exclude Friends", true)
            .setDescription("Skip pearls thrown by players on your friends list.").setSection(WHOSE);
    private final SliderSetting range = new SliderSetting("Range", 128, 16, 256, 0)
            .setDescription("Pearls farther than this many blocks from you are ignored.").setSection(WHOSE);

    private final BooleanSetting box = new BooleanSetting("Box", true)
            .setDescription("Player-sized box where the thrower will appear.").setSection(DISPLAY);
    private final BooleanSetting ring = new BooleanSetting("Ring", true)
            .setDescription("Pulsing ring on the landing spot.").setSection(DISPLAY);
    private final BooleanSetting pathLine = new BooleanSetting("Path Line", false)
            .setDescription("Line along the predicted flight path.").setSection(DISPLAY);
    private final BooleanSetting timeLabel = new BooleanSetting("Time Label", true)
            .setDescription("Seconds until the pearl lands, above the box.").setSection(DISPLAY);
    private final BooleanSetting ownerName = new BooleanSetting("Owner Name", true)
            .setDescription("Adds the thrower's name to the label.").setDependsOn("Time Label").setSection(DISPLAY);
    private final SliderSetting thickness = new SliderSetting("Thickness", 2.0, 0.5, 6.0, 1)
            .setDescription("Line thickness of the box and path, roughly in pixels.").setSection(DISPLAY);
    private final SliderSetting fillOpacity = new SliderSetting("Fill Opacity", 0.12, 0.0, 0.6, 2)
            .setDescription("Opacity of the box fill; 0 draws only the outline.").setDependsOn("Box").setSection(DISPLAY);
    private final BooleanSetting throughWalls = new BooleanSetting("Through Walls", true)
            .setDescription("Draws the marker even when blocks are in front of it.").setSection(DISPLAY);

    private final ModeSetting colorMode = new ModeSetting("Color", ThemeColors.THEME, ThemeColors.THEME,
            ThemeColors.CUSTOM, ThemeColors.RAINBOW)
            .setDescription("Theme follows the GUI accent colour.").setSection(COLOR);
    private final ColorSetting customColor = new ColorSetting("Custom Color", new Color(190, 90, 255))
            .setDescription("Marker colour when Color is Custom.").setDependsOn("Color:Custom").setSection(COLOR);

    /** Keyed by pearl entity id. Client thread only. */
    private final Map<Integer, Marker> markers = new HashMap<>();
    private ClientWorld lastWorld;
    private int tickStamp;

    public PearlLandingMarker() {
        super("PearlLandingMarker", "Shows where thrown ender pearls will land, with a box, ring, optional path "
                + "line and the time until landing. Visible only to you.", Category.RENDER);
        addSetting(enemies);
        addSetting(ownPearls);
        addSetting(excludeFriends);
        addSetting(range);
        addSetting(box);
        addSetting(ring);
        addSetting(pathLine);
        addSetting(timeLabel);
        addSetting(ownerName);
        addSetting(thickness);
        addSetting(fillOpacity);
        addSetting(throughWalls);
        addSetting(colorMode);
        addSetting(customColor);
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
        markers.clear();
        lastWorld = null;
    }

    @Override
    public void onTick() {
        if (mc.player == null || mc.world == null) return;
        if (mc.world != lastWorld) {
            clear();
            lastWorld = mc.world;
        }
        tickStamp++;
        long now = System.currentTimeMillis();
        double rangeSq = range.getValue() * range.getValue();
        for (Entity entity : mc.world.getEntities()) {
            if (!(entity instanceof EnderPearlEntity pearl) || pearl.isRemoved()) continue;
            if (pearl.squaredDistanceTo(mc.player) > rangeSq) continue;
            Entity owner = pearl.getOwner();
            boolean mine = owner == mc.player;
            if (mine ? !ownPearls.isEnabled() : !enemies.isEnabled()) continue;
            if (!mine && excludeFriends.isEnabled() && owner instanceof PlayerEntity player
                    && FriendManager.getInstance().isFriend(player.getUuid())) {
                continue;
            }
            Marker marker = markers.computeIfAbsent(pearl.getId(), id -> new Marker());
            marker.seen = tickStamp;
            marker.owner = owner == null ? "?" : owner.getName().getString();
            simulate(pearl, marker, now);
        }
        markers.values().removeIf(m -> m.seen != tickStamp);
    }

    private static void simulate(EnderPearlEntity pearl, Marker marker, long now) {
        PearlSimulation sim = new PearlSimulation(pearl);
        marker.path.clear();
        Vec3d start = pearl.getEntityPos();
        marker.path.add(start.x, start.y, start.z, now);
        int ticks = 0;
        while (ticks < MAX_SIM_TICKS && !sim.hasHit()) {
            sim.simulateTick();
            ticks++;
            Vec3d p = sim.getPosition();
            marker.path.add(p.x, p.y, p.z, now);
        }
        marker.landed = sim.hasHit();
        marker.landing = sim.getPosition();
        marker.ticks = ticks;
        marker.computedAt = now;
    }

    private int color(int index) {
        return ThemeColors.resolve(colorMode.getCurrentMode(), customColor.getValue(), index * 0.17f);
    }

    @Override
    public void onWorldRender(MatrixStack matrices, float tickDelta) {
        if (markers.isEmpty() || mc.player == null || mc.world == null || mc.world != lastWorld) return;
        Vec3d cam = mc.gameRenderer.getCamera().getCameraPos();
        long now = System.currentTimeMillis();
        float pulse = TrailMath.pulse(ThemeColors.seconds(), 1.2);
        float lineThickness = (float) thickness.getValue();
        float fill = (float) fillOpacity.getValue();

        try (BufferAllocator allocator = PooledBufferAllocator.acquire(16384)) {
            VertexConsumerProvider.Immediate immediate = VertexConsumerProvider.immediate(allocator);
            VertexConsumer buffer = immediate.getBuffer(throughWalls.isEnabled() ? RenderTypes.TRIANGLES_NO_DEPTH : RenderTypes.TRIANGLES);
            Matrix4f matrix = matrices.peek().getPositionMatrix();
            int index = 0;
            for (Marker marker : markers.values()) {
                int base = color(index++);
                if (pathLine.isEnabled() && marker.path.size() >= 2) {
                    int lineColor = TrailMath.withAlpha(base, 0.7f);
                    for (int i = 1; i < marker.path.size(); i++) {
                        RibbonRenderer.edge(buffer, matrix,
                                (float) (marker.path.x(i - 1) - cam.x), (float) (marker.path.y(i - 1) - cam.y), (float) (marker.path.z(i - 1) - cam.z),
                                (float) (marker.path.x(i) - cam.x), (float) (marker.path.y(i) - cam.y), (float) (marker.path.z(i) - cam.z),
                                lineThickness * 0.75f, lineColor, lineColor);
                    }
                }
                if (!marker.landed || marker.landing == null) continue;
                float x = (float) (marker.landing.x - cam.x);
                float y = (float) (marker.landing.y - cam.y);
                float z = (float) (marker.landing.z - cam.z);
                if (box.isEnabled()) {
                    if (fill > 0.0f) {
                        RibbonRenderer.boxFill(buffer, matrix, x - HALF_WIDTH, y, z - HALF_WIDTH, x + HALF_WIDTH, y + HEIGHT,
                                z + HALF_WIDTH, TrailMath.withAlpha(base, fill), TrailMath.withAlpha(base, fill * 0.25f));
                    }
                    RibbonRenderer.boxEdges(buffer, matrix, x - HALF_WIDTH, y, z - HALF_WIDTH, x + HALF_WIDTH, y + HEIGHT,
                            z + HALF_WIDTH, lineThickness, TrailMath.withAlpha(base, 0.95f), TrailMath.withAlpha(base, 0.45f));
                }
                if (ring.isEnabled()) {
                    float radius = 0.45f + 0.12f * pulse;
                    RibbonRenderer.ring(buffer, matrix, x, y + 0.03f, z, radius, 0.09f, 48,
                            TrailMath.withAlpha(base, 0.55f + 0.35f * pulse), 0.0f);
                    RibbonRenderer.ring(buffer, matrix, x, y + 0.031f, z, radius, 0.02f, 48,
                            TrailMath.withAlpha(TrailMath.brighten(base, 0.3f), 0.95f), 0.7f);
                }
            }
            immediate.draw();
        }
    }

    @Override
    public void onRender(DrawContext context, float tickDelta) {
        if (!timeLabel.isEnabled() || markers.isEmpty() || context == null || mc.player == null || mc.world == null
                || mc.world != lastWorld || mc.textRenderer == null || mc.gameRenderer == null) {
            return;
        }
        Camera camera = mc.gameRenderer.getCamera();
        float fov = ((GameRendererAccessor) mc.gameRenderer).callGetFov(camera, tickDelta, true);
        long now = System.currentTimeMillis();
        int index = 0;
        for (Marker marker : markers.values()) {
            int rgb = color(index++);
            if (!marker.landed || marker.landing == null) continue;
            Vec3d screen = ProjectionUtil.worldToScreen(marker.landing.add(0.0, HEIGHT + 0.35, 0.0), camera, fov);
            if (screen == null) continue;
            double seconds = Math.max(0.0, (marker.ticks * 50L - (now - marker.computedAt)) / 1000.0);
            String text = String.format(Locale.ROOT, "%.1fs", seconds);
            if (ownerName.isEnabled()) text = marker.owner + " " + text;
            int w = mc.textRenderer.getWidth(text);
            int x = (int) Math.round(screen.x) - w / 2;
            int y = (int) Math.round(screen.y) - mc.textRenderer.fontHeight;
            context.fill(x - 2, y - 2, x + w + 2, y + mc.textRenderer.fontHeight + 1, 0x90000000);
            context.drawTextWithShadow(mc.textRenderer, text, x, y, 0xFF000000 | (rgb & 0x00FFFFFF));
        }
    }

    private static final class Marker {
        final TrailBuffer path = new TrailBuffer(MAX_SIM_TICKS + 2);
        Vec3d landing;
        boolean landed;
        int ticks;
        long computedAt;
        String owner = "?";
        int seen;
    }
}
