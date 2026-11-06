package interstellarTikiTiks.apexx.client.module.implementation.render;

import interstellarTikiTiks.apexx.client.module.BooleanSetting;
import interstellarTikiTiks.apexx.client.module.Category;
import interstellarTikiTiks.apexx.client.module.ColorSetting;
import interstellarTikiTiks.apexx.client.module.ModeSetting;
import interstellarTikiTiks.apexx.client.module.Module;
import interstellarTikiTiks.apexx.client.module.SliderSetting;
import interstellarTikiTiks.apexx.client.module.hooks.ExplosionVisualProvider;
import interstellarTikiTiks.apexx.client.theme.AccentState;
import interstellarTikiTiks.apexx.client.util.render.ProceduralTextures;
import interstellarTikiTiks.apexx.client.util.render.ProceduralTextures.Blend;
import interstellarTikiTiks.apexx.client.util.render.VisualGeometry;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.client.render.Camera;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.util.BufferAllocator;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.decoration.EndCrystalEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.awt.Color;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Replaces the vanilla explosion burst of end crystals and respawn anchors with a clean expanding ring,
 * a short flash and a few sparks. Purely visual and client-side: the explosion sound, damage and knockback
 * are untouched. A crystal explosion is recognised by its centre matching an end crystal that is still in
 * (or was just removed from) the client world; an anchor explosion by a respawn anchor at its centre block.
 */
public final class CrystalFX extends Module implements ExplosionVisualProvider {
    private static final int MAX_BURSTS = 16;
    private static final int MAX_SPARKS = 480;
    /** How long a crystal stays matchable after it disappeared client-side (removal can precede the packet). */
    private static final long CRYSTAL_MEMORY_MS = 750L;
    private static final double CRYSTAL_MATCH_SQ = 0.5 * 0.5;
    private static final double CRYSTAL_SCAN_RANGE = 96.0;

    private final BooleanSetting crystals = new BooleanSetting("Crystals", true)
            .setDescription("Replace the explosion of end crystals.");
    private final BooleanSetting anchors = new BooleanSetting("Anchors", true)
            .setDescription("Replace the explosion of respawn anchors.");
    private final BooleanSetting allExplosions = new BooleanSetting("All Explosions", false)
            .setDescription("Also replace every other explosion (TNT, creepers, beds, wind charges). Off keeps those vanilla.");
    private final ModeSetting ringStyle = new ModeSetting("Ring", "Both", "Flat", "Facing", "Both")
            .setDescription("Flat draws a shock ring on the ground plane; Facing draws a ring that always faces you; Both draws both.");
    private final SliderSetting size = new SliderSetting("Size", 1.0, 0.3, 3.0, 2)
            .setDescription("Size of the ring and flash. At 1.0 the ring grows to about 3 blocks.");
    private final SliderSetting lifetime = new SliderSetting("Lifetime (ms)", 450, 120, 2000, 0)
            .setDescription("How long the effect lasts before it has fully faded.");
    private final SliderSetting sparks = new SliderSetting("Sparks", 10, 0, 40, 0)
            .setDescription("Number of sparks thrown out by each explosion. 0 shows none.");
    private final SliderSetting opacity = new SliderSetting("Opacity", 0.85, 0.1, 1.0, 2)
            .setDescription("Overall brightness of the effect.");
    private final BooleanSetting flash = new BooleanSetting("Flash", true)
            .setDescription("A short soft glow at the centre when the explosion goes off.");
    private final ModeSetting colorMode = new ModeSetting("Color Mode", "Custom", "Custom", "Theme Sync")
            .setDescription("Custom uses the colour below; Theme Sync uses the ClickGUI accent.");
    private final ColorSetting color = new ColorSetting("Color", new Color(170, 120, 255))
            .setDescription("Effect colour when Color Mode is Custom.")
            .setDependsOn("Color Mode:Custom");

    private final List<Burst> bursts = new ArrayList<>();
    private final List<Spark> sparkList = new ArrayList<>();
    private final Map<Integer, RecentCrystal> recentCrystals = new HashMap<>();
    private final Random random = new Random();
    private ClientWorld lastWorld;
    private long lastNanos = -1L;

    public CrystalFX() {
        super("CrystalFX", "Swaps the big smoky explosion of end crystals and respawn anchors for a clean expanding "
                + "ring, a soft flash and a few sparks, with your own colour, size and lifetime. Visible only to you; "
                + "the explosion itself is unchanged.", Category.RENDER);
        addSetting(crystals);
        addSetting(anchors);
        addSetting(allExplosions);
        addSetting(ringStyle);
        addSetting(size);
        addSetting(lifetime);
        addSetting(sparks);
        addSetting(opacity);
        addSetting(flash);
        addSetting(colorMode);
        addSetting(color);
    }

    @Override
    public void onDisable() {
        clearState();
    }

    @Override
    protected void onRuntimeStateReleased() {
        clearState();
    }

    private void clearState() {
        bursts.clear();
        sparkList.clear();
        recentCrystals.clear();
        lastWorld = null;
        lastNanos = -1L;
    }

    // ---- crystal memory -----------------------------------------------------------------------------

    @Override
    public void onTick() {
        if (mc.player == null || mc.world == null) {
            clearState();
            return;
        }
        if (mc.world != lastWorld) {
            clearState();
            lastWorld = mc.world;
        }
        long now = System.currentTimeMillis();
        Box area = mc.player.getBoundingBox().expand(CRYSTAL_SCAN_RANGE);
        for (EndCrystalEntity crystal : mc.world.getEntitiesByClass(EndCrystalEntity.class, area, e -> true)) {
            RecentCrystal rc = recentCrystals.computeIfAbsent(crystal.getId(), id -> new RecentCrystal());
            rc.pos = crystal.getEntityPos();
            rc.seenAt = now;
        }
        recentCrystals.values().removeIf(rc -> now - rc.seenAt > CRYSTAL_MEMORY_MS);
    }

    // ---- explosion hook -----------------------------------------------------------------------------

    @Override
    public boolean replaceExplosionVisuals(Vec3d center, float radius) {
        if (mc.world == null || mc.player == null || center == null) return false;
        if (!(allExplosions.isEnabled()
                || (crystals.isEnabled() && isCrystalExplosion(center))
                || (anchors.isEnabled() && isAnchorExplosion(center)))) {
            return false;
        }
        if (!ProceduralTextures.ensure()) return false; // textures unavailable: keep vanilla particles
        spawn(center);
        return true;
    }

    private boolean isCrystalExplosion(Vec3d center) {
        Box near = new Box(center, center).expand(0.75);
        for (EndCrystalEntity crystal : mc.world.getEntitiesByClass(EndCrystalEntity.class, near, e -> true)) {
            if (crystal.getEntityPos().squaredDistanceTo(center) <= CRYSTAL_MATCH_SQ) return true;
        }
        long now = System.currentTimeMillis();
        for (RecentCrystal rc : recentCrystals.values()) {
            if (rc.pos != null && now - rc.seenAt <= CRYSTAL_MEMORY_MS && rc.pos.squaredDistanceTo(center) <= CRYSTAL_MATCH_SQ) {
                return true;
            }
        }
        // Fallback for a crystal spawned and destroyed between two client ticks (never seen by onTick):
        // crystals explode at their feet, the bottom centre of the block above obsidian or bedrock.
        BlockPos feet = BlockPos.ofFloored(center);
        if (Math.abs(center.x - (feet.getX() + 0.5)) > 0.01 || Math.abs(center.y - feet.getY()) > 0.01
                || Math.abs(center.z - (feet.getZ() + 0.5)) > 0.01) {
            return false;
        }
        BlockState base = mc.world.getBlockState(feet.down());
        return (base.isOf(Blocks.OBSIDIAN) || base.isOf(Blocks.BEDROCK)) && mc.world.getBlockState(feet).isReplaceable();
    }

    /** Anchors explode at their block centre; the block is normally still there client-side when the packet lands. */
    private boolean isAnchorExplosion(Vec3d center) {
        BlockPos pos = BlockPos.ofFloored(center);
        if (Math.abs(center.x - (pos.getX() + 0.5)) > 0.01 || Math.abs(center.y - (pos.getY() + 0.5)) > 0.01
                || Math.abs(center.z - (pos.getZ() + 0.5)) > 0.01) {
            return false;
        }
        BlockState state = mc.world.getBlockState(pos);
        return state.isOf(Blocks.RESPAWN_ANCHOR);
    }

    private void spawn(Vec3d center) {
        Burst burst = new Burst();
        burst.center = center;
        burst.life = (float) (lifetime.getValue() / 1000.0);
        burst.scale = (float) size.getValue();
        burst.color = currentColor();
        bursts.add(burst);
        while (bursts.size() > MAX_BURSTS) bursts.remove(0);

        int count = (int) Math.round(sparks.getValue());
        for (int i = 0; i < count; i++) {
            double az = random.nextDouble() * Math.PI * 2;
            double out = 0.4 + random.nextDouble() * 0.6;
            Vec3d dir = new Vec3d(Math.cos(az) * out, 0.35 + random.nextDouble() * 0.9, Math.sin(az) * out).normalize();
            double speed = (5.0 + random.nextDouble() * 7.0) * burst.scale;
            Spark s = new Spark();
            s.x = center.x; s.y = center.y + 0.2; s.z = center.z;
            s.vx = dir.x * speed; s.vy = dir.y * speed; s.vz = dir.z * speed;
            s.life = burst.life * (0.6f + random.nextFloat() * 0.5f);
            s.width = (0.035f + random.nextFloat() * 0.03f) * Math.max(0.6f, burst.scale);
            s.color = burst.color;
            sparkList.add(s);
        }
        while (sparkList.size() > MAX_SPARKS) sparkList.remove(0);
    }

    private Color currentColor() {
        if (colorMode.is("Theme Sync")) return AccentState.accentColor();
        Color c = color.getValue();
        return c == null ? new Color(170, 120, 255) : c;
    }

    // ---- frame update and render --------------------------------------------------------------------

    @Override
    public void onWorldRender(MatrixStack matrices, float tickDelta) {
        if (mc.player == null || mc.world == null) {
            clearState();
            return;
        }
        if (mc.world != lastWorld) {
            clearState();
            lastWorld = mc.world;
        }
        long now = System.nanoTime();
        float dt = lastNanos < 0 ? 0f : MathHelper.clamp((now - lastNanos) / 1.0e9f, 0f, 0.05f);
        lastNanos = now;

        for (Iterator<Burst> it = bursts.iterator(); it.hasNext(); ) {
            Burst b = it.next();
            b.age += dt;
            if (b.age >= b.life) it.remove();
        }
        for (Iterator<Spark> it = sparkList.iterator(); it.hasNext(); ) {
            Spark s = it.next();
            s.age += dt;
            if (s.age >= s.life) { it.remove(); continue; }
            s.vy -= 14.0 * dt;
            double damp = Math.exp(-1.6 * dt);
            s.vx *= damp; s.vy *= damp; s.vz *= damp;
            s.x += s.vx * dt; s.y += s.vy * dt; s.z += s.vz * dt;
        }
        if (bursts.isEmpty() && sparkList.isEmpty()) return;
        if (!ProceduralTextures.ensure()) return;
        render(matrices);
    }

    private void render(MatrixStack matrices) {
        Camera camera = mc.gameRenderer.getCamera();
        if (camera == null) return;
        Vec3d cam = camera.getCameraPos();
        Quaternionf camRot = camera.getRotation();
        Vector3f right = new Vector3f(1f, 0f, 0f).rotate(camRot);
        Vector3f up = new Vector3f(0f, 1f, 0f).rotate(camRot);
        Matrix4f m = matrices.peek().getPositionMatrix();
        float alphaMul = (float) opacity.getValue();
        boolean flat = !ringStyle.is("Facing");
        boolean facing = !ringStyle.is("Flat");
        int light = VisualGeometry.FULL_BRIGHT;

        try (BufferAllocator allocator = interstellarTikiTiks.apexx.client.util.render.PooledBufferAllocator.acquire(1 << 16)) {
            VertexConsumerProvider.Immediate imm = VertexConsumerProvider.immediate(allocator);

            if (!bursts.isEmpty()) {
                VertexConsumer ring = imm.getBuffer(ProceduralTextures.layer(ProceduralTextures.RING, Blend.ADDITIVE, true));
                for (Burst b : bursts) {
                    float t = b.age / b.life;
                    float grow = 1f - (float) Math.pow(1f - t, 3.0);
                    float fade = (float) Math.pow(1f - t, 1.4) * alphaMul;
                    if (fade < 0.004f) continue;
                    // The ring texture's bright band sits at ~0.8 of its radius.
                    double cx = b.center.x - cam.x, cy = b.center.y - cam.y, cz = b.center.z - cam.z;
                    if (flat) {
                        double half = (0.3 + 2.7 * grow) * b.scale / 0.8;
                        double y = cy + 0.05;
                        int col = VisualGeometry.color(b.color, fade);
                        VisualGeometry.quad(ring, m, cx - half, y, cz - half, cx + half, y, cz - half,
                                cx + half, y, cz + half, cx - half, y, cz + half, 0f, 0f, 1f, 1f, col, col, col, col, light);
                    }
                    if (facing) {
                        double half = (0.2 + 1.9 * grow) * b.scale / 0.8;
                        int col = VisualGeometry.color(b.color, fade * 0.75f);
                        VisualGeometry.sprite(ring, m, cx, cy + 0.5 * b.scale, cz, right.x, right.y, right.z,
                                up.x, up.y, up.z, half, half, 0f, 0f, 0f, 1f, 1f, col, light);
                    }
                }
                imm.draw();

                if (flash.isEnabled()) {
                    VertexConsumer glow = imm.getBuffer(ProceduralTextures.layer(ProceduralTextures.GLOW, Blend.ADDITIVE, true));
                    for (Burst b : bursts) {
                        float t = MathHelper.clamp(b.age / Math.max(0.05f, b.life * 0.45f), 0f, 1f);
                        if (t >= 1f) continue;
                        float fade = (1f - t) * (1f - t) * alphaMul;
                        double half = (0.9 + 1.4 * t) * b.scale;
                        Color white = mix(b.color, Color.WHITE, 0.55f);
                        VisualGeometry.sprite(glow, m, b.center.x - cam.x, b.center.y - cam.y + 0.5 * b.scale, b.center.z - cam.z,
                                right.x, right.y, right.z, up.x, up.y, up.z, half, half, 0f, 0f, 0f, 1f, 1f,
                                VisualGeometry.color(white, fade), light);
                    }
                    imm.draw();
                }
            }

            if (!sparkList.isEmpty()) {
                VertexConsumer streak = imm.getBuffer(ProceduralTextures.layer(ProceduralTextures.STREAK, Blend.ADDITIVE, true));
                for (Spark s : sparkList) {
                    float t = s.age / s.life;
                    float fade = (1f - t * t) * alphaMul;
                    if (fade < 0.004f) continue;
                    drawStreak(streak, m, cam, s, VisualGeometry.color(mix(s.color, Color.WHITE, 0.35f * (1f - t)), fade));
                }
                imm.draw();
            }
        } catch (Throwable t) {
            // Keep these visuals isolated from the rest of the world render.
            interstellarTikiTiks.apexx.client.util.ClientInternalLog.warn("CrystalFX", "render failed", t);
        }
    }

    private static void drawStreak(VertexConsumer buf, Matrix4f m, Vec3d cam, Spark s, int color) {
        double speed = Math.sqrt(s.vx * s.vx + s.vy * s.vy + s.vz * s.vz);
        double dx = 0, dy = -1, dz = 0;
        if (speed > 1e-3) { dx = s.vx / speed; dy = s.vy / speed; dz = s.vz / speed; }
        double length = Math.max(s.width * 2.0, speed * 0.04 + s.width);
        double cx = s.x - cam.x, cy = s.y - cam.y, cz = s.z - cam.z;
        // Side vector perpendicular to both the travel direction and the view ray.
        double sx = dy * -cz - dz * -cy, sy = dz * -cx - dx * -cz, sz = dx * -cy - dy * -cx;
        double sl = Math.sqrt(sx * sx + sy * sy + sz * sz);
        if (sl < 1e-9) return;
        double half = s.width * 0.5 / sl;
        sx *= half; sy *= half; sz *= half;
        double hx = cx + dx * length * 0.5, hy = cy + dy * length * 0.5, hz = cz + dz * length * 0.5;
        double tx = cx - dx * length * 0.5, ty = cy - dy * length * 0.5, tz = cz - dz * length * 0.5;
        int light = VisualGeometry.FULL_BRIGHT;
        VisualGeometry.vertex(buf, m, hx + sx, hy + sy, hz + sz, 1f, 0f, color, light);
        VisualGeometry.vertex(buf, m, hx - sx, hy - sy, hz - sz, 1f, 1f, color, light);
        VisualGeometry.vertex(buf, m, tx - sx, ty - sy, tz - sz, 0f, 1f, color, light);
        VisualGeometry.vertex(buf, m, hx + sx, hy + sy, hz + sz, 1f, 0f, color, light);
        VisualGeometry.vertex(buf, m, tx - sx, ty - sy, tz - sz, 0f, 1f, color, light);
        VisualGeometry.vertex(buf, m, tx + sx, ty + sy, tz + sz, 0f, 0f, color, light);
    }

    private static Color mix(Color a, Color b, float t) {
        t = MathHelper.clamp(t, 0f, 1f);
        return new Color(
                MathHelper.clamp(Math.round(a.getRed() + (b.getRed() - a.getRed()) * t), 0, 255),
                MathHelper.clamp(Math.round(a.getGreen() + (b.getGreen() - a.getGreen()) * t), 0, 255),
                MathHelper.clamp(Math.round(a.getBlue() + (b.getBlue() - a.getBlue()) * t), 0, 255));
    }

    private static final class Burst {
        Vec3d center;
        float age;
        float life;
        float scale;
        Color color;
    }

    private static final class Spark {
        double x, y, z, vx, vy, vz;
        float age, life, width;
        Color color;
    }

    private static final class RecentCrystal {
        Vec3d pos;
        long seenAt;
    }
}
