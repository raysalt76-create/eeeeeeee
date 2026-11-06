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
import interstellarTikiTiks.apexx.client.util.render.TrailMath;
import interstellarTikiTiks.apexx.client.util.render.types.RenderTypes;
import net.minecraft.client.network.AbstractClientPlayerEntity;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.util.BufferAllocator;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.util.math.Vec3d;
import org.joml.Matrix4f;

import java.awt.Color;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Draws an expanding ring on the ground where you (and optionally other players) jump off and land.
 * Client-side visual only; memory only.
 */
public final class JumpCircles extends Module {
    private static final String TRIGGERS = "Triggers";
    private static final String LOOK = "Look";
    private static final String COLOR = "Color";
    private static final int MAX_RINGS = 48;
    private static final double OTHERS_RANGE = 64.0;

    private final BooleanSetting onJump = new BooleanSetting("On Jump", true)
            .setDescription("Ring where you leave the ground when you jump.")
            .setSection(TRIGGERS);
    private final BooleanSetting onLand = new BooleanSetting("On Land", true)
            .setDescription("Ring where you touch down after a fall.")
            .setSection(TRIGGERS);
    private final SliderSetting minFall = new SliderSetting("Min Fall", 0.6, 0.2, 4.0, 1)
            .setDescription("Blocks you must fall before a landing ring appears, so stepping down a slab does not trigger it.")
            .setDependsOn("On Land")
            .setSection(TRIGGERS);
    private final BooleanSetting others = new BooleanSetting("Others", false)
            .setDescription("Also show rings for other players' jumps and landings within 64 blocks.")
            .setSection(TRIGGERS);

    private final SliderSetting size = new SliderSetting("Size", 1.2, 0.3, 4.0, 2)
            .setDescription("Radius in blocks the ring grows to.")
            .setSection(LOOK);
    private final SliderSetting duration = new SliderSetting("Duration", 0.8, 0.15, 3.0, 2)
            .setDescription("Seconds a ring takes to expand and disappear.")
            .setSection(LOOK);
    private final SliderSetting thickness = new SliderSetting("Thickness", 0.10, 0.02, 0.5, 2)
            .setDescription("Width of the ring band in blocks.")
            .setSection(LOOK);
    private final BooleanSetting fade = new BooleanSetting("Fade", true)
            .setDescription("Fades the ring out as it expands; off keeps it solid until it ends.")
            .setSection(LOOK);
    private final BooleanSetting innerGlow = new BooleanSetting("Inner Glow", true)
            .setDescription("Fills the inside of the ring with a faint glow.")
            .setSection(LOOK);
    private final SliderSetting opacity = new SliderSetting("Opacity", 0.85, 0.1, 1.0, 2)
            .setDescription("Overall opacity of the rings.")
            .setSection(LOOK);
    private final BooleanSetting throughWalls = new BooleanSetting("Through Walls", false)
            .setDescription("Draws rings even when blocks are in front of them.")
            .setSection(LOOK);

    private final ModeSetting colorMode = new ModeSetting("Color", ThemeColors.THEME, ThemeColors.THEME,
            ThemeColors.CUSTOM, ThemeColors.RAINBOW)
            .setDescription("Theme follows the GUI accent colour.")
            .setSection(COLOR);
    private final ColorSetting customColor = new ColorSetting("Custom Color", new Color(120, 200, 255))
            .setDescription("Ring colour when Color is Custom.")
            .setDependsOn("Color:Custom")
            .setSection(COLOR);

    private final List<Ring> rings = new ArrayList<>();
    private final Map<Integer, Track> tracks = new HashMap<>();
    private ClientWorld lastWorld;
    private boolean wasOnGround = true;
    private double groundY;
    private double peakY;
    private int tickStamp;

    public JumpCircles() {
        super("JumpCircles", "Draws an expanding ring on the ground where you jump and where you land, optionally for "
                + "other players too. Visible only to you.", Category.RENDER);
        addSetting(onJump);
        addSetting(onLand);
        addSetting(minFall);
        addSetting(others);
        addSetting(size);
        addSetting(duration);
        addSetting(thickness);
        addSetting(fade);
        addSetting(innerGlow);
        addSetting(opacity);
        addSetting(throughWalls);
        addSetting(colorMode);
        addSetting(customColor);
    }

    @Override
    public boolean rendersInWorld() {
        return true;
    }

    @Override
    public void onEnable() {
        clear();
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
        rings.clear();
        tracks.clear();
        lastWorld = null;
        wasOnGround = true;
    }

    @Override
    public void onTick() {
        if (mc.player == null || mc.world == null) return;
        if (mc.world != lastWorld) {
            clear();
            lastWorld = mc.world;
            groundY = peakY = mc.player.getY();
            wasOnGround = mc.player.isOnGround();
        }
        long now = System.currentTimeMillis();
        tickStamp++;

        boolean ground = mc.player.isOnGround();
        double y = mc.player.getY();
        boolean airborneMotion = !mc.player.isClimbing() && !mc.player.isTouchingWater()
                && !mc.player.getAbilities().flying && !mc.player.hasVehicle() && !mc.player.isGliding();
        if (ground) {
            if (!wasOnGround && onLand.isEnabled() && airborneMotion && peakY - y >= minFall.getValue()) {
                spawn(mc.player.getX(), y, mc.player.getZ(), now, landScale(peakY - y));
            }
            groundY = y;
            peakY = y;
        } else {
            if (wasOnGround && onJump.isEnabled() && airborneMotion && mc.player.getVelocity().y > 0.1) {
                spawn(mc.player.getX(), groundY, mc.player.getZ(), now, 1.0f);
            }
            peakY = Math.max(peakY, y);
        }
        wasOnGround = ground;

        if (others.isEnabled()) {
            tickOthers(now);
        } else if (!tracks.isEmpty()) {
            tracks.clear();
        }
    }

    private void tickOthers(long now) {
        double rangeSq = OTHERS_RANGE * OTHERS_RANGE;
        for (AbstractClientPlayerEntity player : mc.world.getPlayers()) {
            if (player == mc.player || player.isRemoved() || player.isSpectator()
                    || player.squaredDistanceTo(mc.player) > rangeSq) {
                continue;
            }
            boolean ground = player.isOnGround();
            double y = player.getY();
            Track track = tracks.get(player.getId());
            if (track == null) {
                track = new Track(ground, y);
                tracks.put(player.getId(), track);
            } else {
                boolean motion = !player.isClimbing() && !player.isTouchingWater() && !player.hasVehicle()
                        && !player.isGliding();
                if (ground) {
                    if (!track.onGround && onLand.isEnabled() && motion && track.peakY - y >= minFall.getValue()) {
                        spawn(player.getX(), y, player.getZ(), now, landScale(track.peakY - y));
                    }
                    track.peakY = y;
                    track.groundY = y;
                } else {
                    if (track.onGround && onJump.isEnabled() && motion && y > track.y + 0.05) {
                        spawn(player.getX(), track.groundY, player.getZ(), now, 1.0f);
                    }
                    track.peakY = Math.max(track.peakY, y);
                }
                track.onGround = ground;
                track.y = y;
            }
            track.seen = tickStamp;
        }
        tracks.values().removeIf(t -> t.seen != tickStamp);
    }

    /** Bigger falls give a slightly bigger landing ring. */
    private static float landScale(double fall) {
        return (float) Math.max(0.8, Math.min(1.5, 0.8 + fall * 0.12));
    }

    private void spawn(double x, double y, double z, long now, float scale) {
        if (rings.size() >= MAX_RINGS) rings.remove(0);
        rings.add(new Ring(x, y, z, now, scale));
    }

    @Override
    public void onWorldRender(MatrixStack matrices, float tickDelta) {
        if (rings.isEmpty() || mc.player == null || mc.world == null || mc.world != lastWorld) return;
        long now = System.currentTimeMillis();
        long life = Math.max(50L, Math.round(duration.getValue() * 1000.0));
        rings.removeIf(r -> now - r.born >= life);
        if (rings.isEmpty()) return;

        Vec3d cam = mc.gameRenderer.getCamera().getCameraPos();
        int base = ThemeColors.resolve(colorMode.getCurrentMode(), customColor.getValue(), 0.0f);
        float maxAlpha = (float) opacity.getValue();
        float band = (float) thickness.getValue() * 0.5f;
        float maxRadius = (float) size.getValue();
        boolean fading = fade.isEnabled();
        boolean glow = innerGlow.isEnabled();
        boolean rainbow = ThemeColors.RAINBOW.equals(colorMode.getCurrentMode());

        try (BufferAllocator allocator = PooledBufferAllocator.acquire(16384)) {
            VertexConsumerProvider.Immediate immediate = VertexConsumerProvider.immediate(allocator);
            VertexConsumer buffer = immediate.getBuffer(throughWalls.isEnabled() ? RenderTypes.TRIANGLES_NO_DEPTH : RenderTypes.TRIANGLES);
            Matrix4f matrix = matrices.peek().getPositionMatrix();
            for (int i = 0; i < rings.size(); i++) {
                Ring ring = rings.get(i);
                float p = TrailMath.lifeFraction(now - ring.born, life);
                float radius = TrailMath.ringRadius(p, maxRadius * ring.scale);
                float alpha = maxAlpha * (fading ? 1.0f - TrailMath.smoothstep(0.25f, 1.0f, p)
                        : 1.0f - TrailMath.smoothstep(0.88f, 1.0f, p));
                if (alpha <= 0.004f || radius <= 0.001f) continue;
                int color = rainbow ? ThemeColors.resolve(ThemeColors.RAINBOW, null, i * 0.13f) : base;
                float x = (float) (ring.x - cam.x);
                float y = (float) (ring.y - cam.y) + 0.02f;
                float z = (float) (ring.z - cam.z);
                float halfBand = band * (1.0f - 0.45f * p);
                int segments = Math.max(24, Math.min(96, (int) (radius * 48.0f)));
                if (glow) {
                    RibbonRenderer.disc(buffer, matrix, x, y - 0.002f, z, Math.max(0.0f, radius - halfBand), segments,
                            TrailMath.withAlpha(color, alpha * 0.04f), TrailMath.withAlpha(color, alpha * 0.22f));
                }
                // Soft glow band, then a bright crisp core on the centre line.
                RibbonRenderer.ring(buffer, matrix, x, y, z, radius, halfBand * 1.8f, segments,
                        TrailMath.withAlpha(color, alpha * 0.45f), 0.0f);
                RibbonRenderer.ring(buffer, matrix, x, y + 0.001f, z, radius, halfBand * 0.45f, segments,
                        TrailMath.withAlpha(TrailMath.brighten(color, 0.35f), alpha), 0.6f);
            }
            immediate.draw();
        }
    }

    private static final class Ring {
        final double x;
        final double y;
        final double z;
        final long born;
        final float scale;

        Ring(double x, double y, double z, long born, float scale) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.born = born;
            this.scale = scale;
        }
    }

    private static final class Track {
        boolean onGround;
        double y;
        double groundY;
        double peakY;
        int seen;

        Track(boolean onGround, double y) {
            this.onGround = onGround;
            this.y = y;
            this.groundY = y;
            this.peakY = y;
        }
    }
}
