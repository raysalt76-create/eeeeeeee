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
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.util.BufferAllocator;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.Entity;
import net.minecraft.entity.projectile.AbstractWindChargeEntity;
import net.minecraft.entity.projectile.ArrowEntity;
import net.minecraft.entity.projectile.ProjectileEntity;
import net.minecraft.entity.projectile.SpectralArrowEntity;
import net.minecraft.entity.projectile.TridentEntity;
import net.minecraft.entity.projectile.thrown.EnderPearlEntity;
import net.minecraft.entity.projectile.thrown.SnowballEntity;
import net.minecraft.util.math.Vec3d;
import org.joml.Matrix4f;

import java.awt.Color;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * Fading trails behind arrows, ender pearls, wind charges, tridents and snowballs in flight, with per-type
 * toggles and colours and separate switches for your own and other players' projectiles. A trail keeps
 * fading after its projectile lands or disappears. Client-side visual only; memory only.
 */
public final class ProjectileTrails extends Module {
    private static final String TYPES = "Types";
    private static final String OWNERS = "Owners";
    private static final String LOOK = "Look";
    private static final String COLOR = "Color";
    private static final String PER_TYPE = "Per Type";
    private static final double SPACING = 0.15;
    private static final int TRAIL_CAPACITY = 256;
    private static final int MAX_TRAILS = 96;

    private enum Kind { ARROW, PEARL, WIND_CHARGE, TRIDENT, SNOWBALL }

    private final BooleanSetting arrows = new BooleanSetting("Arrows", true)
            .setDescription("Trails behind arrows and spectral arrows.").setSection(TYPES);
    private final BooleanSetting pearls = new BooleanSetting("Ender Pearls", true)
            .setDescription("Trails behind thrown ender pearls.").setSection(TYPES);
    private final BooleanSetting windCharges = new BooleanSetting("Wind Charges", true)
            .setDescription("Trails behind wind charges.").setSection(TYPES);
    private final BooleanSetting tridents = new BooleanSetting("Tridents", true)
            .setDescription("Trails behind thrown tridents.").setSection(TYPES);
    private final BooleanSetting snowballs = new BooleanSetting("Snowballs", true)
            .setDescription("Trails behind snowballs.").setSection(TYPES);

    private final BooleanSetting own = new BooleanSetting("Own", true)
            .setDescription("Trails behind projectiles you shot or threw.").setSection(OWNERS);
    private final BooleanSetting others = new BooleanSetting("Others", true)
            .setDescription("Trails behind projectiles from anyone else (or with an unknown owner).").setSection(OWNERS);

    private final SliderSetting fadeLength = new SliderSetting("Fade Length", 1.2, 0.2, 5.0, 1)
            .setDescription("Seconds each part of a trail lasts; longer gives a longer trail.").setSection(LOOK);
    private final SliderSetting width = new SliderSetting("Width", 0.08, 0.02, 0.4, 2)
            .setDescription("Width of the trail in blocks.").setSection(LOOK);
    private final BooleanSetting taper = new BooleanSetting("Taper", true)
            .setDescription("Narrows the trail toward its old end.").setSection(LOOK);
    private final SliderSetting opacity = new SliderSetting("Opacity", 0.85, 0.1, 1.0, 2)
            .setDescription("Opacity of the trails.").setSection(LOOK);
    private final SliderSetting range = new SliderSetting("Range", 128, 16, 256, 0)
            .setDescription("Projectiles farther than this many blocks get no trail.").setSection(LOOK);
    private final BooleanSetting throughWalls = new BooleanSetting("Through Walls", false)
            .setDescription("Draws trails even when blocks are in front of them.").setSection(LOOK);

    private final ModeSetting colorMode = new ModeSetting("Color", ThemeColors.THEME, ThemeColors.THEME,
            PER_TYPE, ThemeColors.RAINBOW)
            .setDescription("Theme follows the GUI accent; Per Type uses the colours below.").setSection(COLOR);
    private final ColorSetting arrowColor = new ColorSetting("Arrow Color", new Color(235, 235, 235))
            .setDependsOn("Color:Per Type").setSection(COLOR);
    private final ColorSetting pearlColor = new ColorSetting("Pearl Color", new Color(170, 80, 255))
            .setDependsOn("Color:Per Type").setSection(COLOR);
    private final ColorSetting windChargeColor = new ColorSetting("Wind Charge Color", new Color(170, 230, 255))
            .setDependsOn("Color:Per Type").setSection(COLOR);
    private final ColorSetting tridentColor = new ColorSetting("Trident Color", new Color(60, 220, 200))
            .setDependsOn("Color:Per Type").setSection(COLOR);
    private final ColorSetting snowballColor = new ColorSetting("Snowball Color", new Color(255, 255, 255))
            .setDependsOn("Color:Per Type").setSection(COLOR);

    /** Keyed by entity id. Client thread only. */
    private final Map<Integer, Trail> trails = new HashMap<>();
    private ClientWorld lastWorld;

    public ProjectileTrails() {
        super("ProjectileTrails", "Draws fading trails behind arrows, ender pearls, wind charges, tridents and "
                + "snowballs, yours and other players'. Visible only to you.", Category.RENDER);
        addSetting(arrows);
        addSetting(pearls);
        addSetting(windCharges);
        addSetting(tridents);
        addSetting(snowballs);
        addSetting(own);
        addSetting(others);
        addSetting(fadeLength);
        addSetting(width);
        addSetting(taper);
        addSetting(opacity);
        addSetting(range);
        addSetting(throughWalls);
        addSetting(colorMode);
        addSetting(arrowColor);
        addSetting(pearlColor);
        addSetting(windChargeColor);
        addSetting(tridentColor);
        addSetting(snowballColor);
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
        trails.clear();
        lastWorld = null;
    }

    private Kind kindOf(Entity entity) {
        if (entity instanceof TridentEntity) return Kind.TRIDENT;
        if (entity instanceof ArrowEntity || entity instanceof SpectralArrowEntity) return Kind.ARROW;
        if (entity instanceof EnderPearlEntity) return Kind.PEARL;
        if (entity instanceof AbstractWindChargeEntity) return Kind.WIND_CHARGE;
        if (entity instanceof SnowballEntity) return Kind.SNOWBALL;
        return null;
    }

    private boolean typeEnabled(Kind kind) {
        return switch (kind) {
            case ARROW -> arrows.isEnabled();
            case PEARL -> pearls.isEnabled();
            case WIND_CHARGE -> windCharges.isEnabled();
            case TRIDENT -> tridents.isEnabled();
            case SNOWBALL -> snowballs.isEnabled();
        };
    }

    private int typeColor(Kind kind) {
        String mode = colorMode.getCurrentMode();
        if (!PER_TYPE.equals(mode)) return ThemeColors.resolve(mode, null, kind.ordinal() * 0.2f);
        Color c = switch (kind) {
            case ARROW -> arrowColor.getValue();
            case PEARL -> pearlColor.getValue();
            case WIND_CHARGE -> windChargeColor.getValue();
            case TRIDENT -> tridentColor.getValue();
            case SNOWBALL -> snowballColor.getValue();
        };
        return ThemeColors.opaque(c);
    }

    /** Discovers projectiles once a tick and marks tracked ones that are gone; sampling happens per frame. */
    @Override
    public void onTick() {
        if (mc.player == null || mc.world == null) return;
        if (mc.world != lastWorld) {
            clear();
            lastWorld = mc.world;
        }
        for (Trail trail : trails.values()) trail.seen = false;
        double rangeSq = range.getValue() * range.getValue();
        for (Entity entity : mc.world.getEntities()) {
            if (!(entity instanceof ProjectileEntity projectile)) continue;
            Kind kind = kindOf(entity);
            if (kind == null || !typeEnabled(kind)) continue;
            Trail trail = trails.get(entity.getId());
            if (trail != null && trail.entity != entity) trail = null; // id reused after the old one left
            if (trail == null) {
                if (entity.squaredDistanceTo(mc.player) > rangeSq || trails.size() >= MAX_TRAILS) continue;
                boolean mine = projectile.getOwner() == mc.player;
                if (mine ? !own.isEnabled() : !others.isEnabled()) continue;
                trail = new Trail(kind, entity);
                trails.put(entity.getId(), trail);
            }
            trail.seen = true;
            if (entity.getVelocity().lengthSquared() < 1.0E-6) trail.stillTicks++; else trail.stillTicks = 0;
        }
        for (Trail trail : trails.values()) {
            if (trail.entity != null && trail.stillTicks >= 10) trail.entity = null; // stuck projectile: retire so the trail fades out
            if (!trail.seen || trail.entity.isRemoved()) trail.entity = null;
        }
    }

    @Override
    public void onWorldRender(MatrixStack matrices, float tickDelta) {
        if (trails.isEmpty() || mc.player == null || mc.world == null || mc.world != lastWorld) return;
        long now = System.currentTimeMillis();
        long life = Math.max(50L, Math.round(fadeLength.getValue() * 1000.0));
        Vec3d cam = mc.gameRenderer.getCamera().getCameraPos();

        int vertexEstimate = 0;
        Iterator<Trail> it = trails.values().iterator();
        while (it.hasNext()) {
            Trail trail = it.next();
            if (trail.entity != null) {
                if (!typeEnabled(trail.kind)) {
                    it.remove();
                    continue;
                }
                Vec3d pos = trail.entity.getLerpedPos(tickDelta);
                double cy = pos.y + trail.entity.getHeight() * 0.5;
                if (trail.points.distanceSqToNewest(pos.x, cy, pos.z) >= SPACING * SPACING) {
                    trail.points.add(pos.x, cy, pos.z, now);
                }
            }
            trail.points.pruneOlderThan(now - life);
            if (trail.entity == null && trail.points.isEmpty()) {
                it.remove();
                continue;
            }
            vertexEstimate += trail.points.size() * 6;
        }
        if (vertexEstimate == 0) return;

        float alpha = (float) opacity.getValue();
        float halfWidth = (float) width.getValue() * 0.5f;
        boolean tapered = taper.isEnabled();
        try (BufferAllocator allocator = PooledBufferAllocator.acquire(Math.max(4096, vertexEstimate * 16))) {
            VertexConsumerProvider.Immediate immediate = VertexConsumerProvider.immediate(allocator);
            VertexConsumer buffer = immediate.getBuffer(throughWalls.isEnabled() ? RenderTypes.TRIANGLES_NO_DEPTH : RenderTypes.TRIANGLES);
            Matrix4f matrix = matrices.peek().getPositionMatrix();
            for (Trail trail : trails.values()) {
                if (trail.points.size() < 2) continue;
                int color = TrailMath.withAlpha(typeColor(trail.kind), alpha);
                int head = TrailMath.brighten(color, 0.25f);
                RibbonRenderer.trail(buffer, matrix, trail.points, cam.x, cam.y, cam.z, halfWidth, now, life,
                        tapered, 0.6f, (index, headT, lifeFrac) -> TrailMath.lerpArgb(color, head, headT));
            }
            immediate.draw();
        }
    }

    private static final class Trail {
        final Kind kind;
        final TrailBuffer points = new TrailBuffer(TRAIL_CAPACITY);
        Entity entity;
        boolean seen;
        int stillTicks;

        Trail(Kind kind, Entity entity) {
            this.kind = kind;
            this.entity = entity;
        }
    }
}
