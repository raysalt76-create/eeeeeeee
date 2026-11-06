package interstellarTikiTiks.apexx.client.module.implementation.render;

import interstellarTikiTiks.apexx.client.module.BooleanSetting;
import interstellarTikiTiks.apexx.client.module.Category;
import interstellarTikiTiks.apexx.client.module.ColorSetting;
import interstellarTikiTiks.apexx.client.module.ModeSetting;
import interstellarTikiTiks.apexx.client.module.Module;
import interstellarTikiTiks.apexx.client.module.SliderSetting;
import interstellarTikiTiks.apexx.client.module.hooks.AttackExecutionAware;
import interstellarTikiTiks.apexx.client.module.hooks.WorldSceneVisualProvider;
import interstellarTikiTiks.apexx.client.render.visuals.WorldSceneVisuals;
import interstellarTikiTiks.apexx.client.theme.AccentState;
import interstellarTikiTiks.apexx.client.util.combat.CombatItemUtil;
import interstellarTikiTiks.apexx.client.util.render.ProceduralTextures;
import interstellarTikiTiks.apexx.client.util.render.ProceduralTextures.Blend;
import interstellarTikiTiks.apexx.client.util.render.VisualGeometry;
import interstellarTikiTiks.apexx.client.util.render.types.RenderTypes;
import net.minecraft.block.BlockState;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.render.Camera;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.WorldRenderer;
import net.minecraft.client.util.BufferAllocator;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.Entity;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.world.RaycastContext;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.awt.Color;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Random;

/**
 * Cinematic mace smash. When a falling mace hit lands, the point on the ground under the target gets:
 * a terrain-conforming shock ripple (with delayed echoes), a branching fracture network that races outward
 * glowing hot and then cools, a scorch mark, lit dust and a rising plume, tumbling block-coloured debris,
 * hot sparks, a glassy pressure dome and a screen-space refraction pulse. The Lightning style adds a railgun
 * strike a moment after impact: a perfectly straight, over-bright beam from the sky with ring coils racing up
 * it, crackling side arcs, a ground flash and an afterglow column.
 *
 * <p>Purely visual, driven by accepted vanilla attacks. The scale follows the fall distance of the hit.
 */
public final class MaceImpact extends Module implements AttackExecutionAware, WorldSceneVisualProvider {
    private static final int HM_ANGLES = 72;
    private static final float HM_STEP = 0.25f;
    private static final int MAX_IMPACTS = 4;
    private static final int MAX_PARTICLES = 700;
    private static final int MAX_CHUNKS = 160;
    private static final float STRIKE_DELAY = 0.14f;
    private static final float BEAM_HEIGHT = 80f;

    private final ModeSetting style = new ModeSetting("Style", "Smash", "Smash", "Lightning")
            .setDescription("Smash plays a ground ripple with fractures, dust and debris; Lightning adds a bolt striking the impact point right after.");
    private final SliderSetting intensity = new SliderSetting("Intensity", 1.0, 0.4, 2.0, 2)
            .setDescription("How many particles and debris pieces are spawned.");
    private final SliderSetting size = new SliderSetting("Size", 1.0, 0.6, 2.0, 2)
            .setDescription("Overall size of the effect; it also grows with how far you fell before the smash.");
    private final SliderSetting minFall = new SliderSetting("Min Fall Distance", 1.5, 1.5, 12.0, 1)
            .setDescription("Smallest fall in blocks that plays the effect. Vanilla needs more than 1.5 blocks of fall for a smash attack.");
    private final BooleanSetting ripple = new BooleanSetting("Ground Ripple", true)
            .setDescription("Shock ripples that roll out across the ground, following the terrain.");
    private final BooleanSetting fractures = new BooleanSetting("Fractures", true)
            .setDescription("Glowing cracks that branch out from the impact and cool down.");
    private final BooleanSetting debris = new BooleanSetting("Debris", true)
            .setDescription("Block fragments, in the colour of the ground you hit, flying out and tumbling.");
    private final BooleanSetting dust = new BooleanSetting("Dust", true)
            .setDescription("A ring of dust and a rising dust plume.");
    private final BooleanSetting dome = new BooleanSetting("Pressure Dome", true)
            .setDescription("A brief see-through shock dome over the impact.");
    private final BooleanSetting distortion = new BooleanSetting("Distortion", true)
            .setDescription("A short wave that bends the view around the impact.");
    private final BooleanSetting flash = new BooleanSetting("Screen Flash", true)
            .setDescription("Briefly flashes the screen white on impact; stronger for Lightning and softer when the impact is far away.");
    private final BooleanSetting rays = new BooleanSetting("Impact Star", true)
            .setDescription("Bright rays that streak across the ground from the impact and a short pillar of light.");
    private final BooleanSetting sound = new BooleanSetting("Sound", true)
            .setDescription("Plays a deep impact thump, and thunder in the Lightning style. Heard only by you.");
    private final ModeSetting colorMode = new ModeSetting("Color", "Natural", "Natural", "Theme Sync", "Custom")
            .setDescription("Natural uses hot orange for Smash and blue-white for Lightning; Theme Sync uses the ClickGUI accent; Custom uses the colour below.");
    private final ColorSetting customColor = new ColorSetting("Custom Color", new Color(255, 170, 80))
            .setDescription("Effect colour when Color is Custom.")
            .setDependsOn("Color:Custom");

    private final List<Impact> impacts = new ArrayList<>();
    private final List<Particle> particles = new ArrayList<>();
    private final List<Chunk> chunks = new ArrayList<>();
    private final Random random = new Random();
    private ClientWorld lastWorld;
    private long lastNanos = -1L;
    private double recentFall;
    private int recentFallTicks;

    public MaceImpact() {
        super("MaceImpact", "Plays a cinematic ground impact when your mace smash attack lands: ripples, glowing cracks, debris, dust, a shock dome and an optional lightning strike, scaled by your fall. Visible only to you.", Category.RENDER);
        addSetting(style);
        addSetting(intensity);
        addSetting(size);
        addSetting(minFall);
        addSetting(ripple);
        addSetting(fractures);
        addSetting(debris);
        addSetting(dust);
        addSetting(dome);
        addSetting(distortion);
        addSetting(flash);
        addSetting(rays);
        addSetting(sound);
        addSetting(colorMode);
        addSetting(customColor);
    }

    @Override
    public void onDisable() {
        clearState();
    }

    @Override
    protected void onRuntimeStateReleased() {
        clearState();
    }

    private BufferAllocator frameAllocator;

    private void clearState() {
        if (frameAllocator != null) {
            frameAllocator.close();
            frameAllocator = null;
        }
        impacts.clear();
        particles.clear();
        chunks.clear();
        lastNanos = -1L;
        lastWorld = null;
        recentFall = 0f;
        recentFallTicks = 0;
    }

    // ---- trigger ------------------------------------------------------------------------------------

    @Override
    public void onTick() {
        if (mc.player == null) return;
        double fall = mc.player.fallDistance;
        if (fall > recentFall) {
            recentFall = fall;
            recentFallTicks = 0;
        } else if (++recentFallTicks > 5) {
            recentFall = fall;
        }
        // A landing resets fallDistance; a stale remembered fall must not turn the next ground hit into a smash.
        if (mc.player.isOnGround() && fall <= 0.0) {
            recentFall = 0;
            recentFallTicks = 0;
        }
    }

    @Override
    public void onVanillaEntityAttack(Entity target, Vec3d hitPosition) {
        if (target == null || mc.player == null || mc.world == null) return;
        if (!CombatItemUtil.isMace(mc.player.getMainHandStack()) || mc.player.isGliding()) return;
        double fall = Math.max(mc.player.fallDistance, recentFall);
        if (fall < minFall.getValue() || fall <= 1.5f) return;
        spawnImpact(target, hitPosition, fall);
    }

    private void spawnImpact(Entity target, Vec3d hitPos, double fall) {
        float energy = MathHelper.clamp((float) ((fall - 1.5) / 11.0), 0f, 1f);
        float scale = (0.72f + 0.88f * energy) * (float) size.getValue();
        float maxRadius = (2.7f + 4.3f * energy) * (float) size.getValue();

        Vec3d feet = new Vec3d(target.getX(), target.getY(), target.getZ());
        BlockHitResult ground = findGround(feet);

        Impact im = new Impact();
        im.energy = energy;
        im.scale = scale;
        im.maxRadius = maxRadius;
        im.lightning = style.is("Lightning");
        im.seed = random.nextLong();
        if (ground != null) {
            im.hasGround = true;
            im.center = new Vec3d(feet.x, ground.getPos().y, feet.z);
            BlockPos bp = ground.getBlockPos();
            BlockState state = mc.world.getBlockState(bp);
            im.surfaceColor = surfaceColor(state, bp);
            buildHeightMap(im);
            if (fractures.isEnabled()) buildFractures(im);
        } else {
            im.hasGround = false;
            im.center = hitPos != null ? hitPos : feet.add(0, target.getHeight() * 0.5, 0);
            im.surfaceColor = new Color(190, 185, 175);
        }
        Vec3d at = im.center;

        // Opening flash and spark burst.
        Particle flash = new Particle();
        flash.kind = K_GLOW; flash.set(at.add(0, 0.25 * scale, 0), Vec3d.ZERO);
        flash.life = 0.16f; flash.s0 = 2.8f * scale; flash.s1 = 4.6f * scale;
        flash.setColor(im.lightning ? new Color(200, 225, 255) : new Color(255, 232, 190), 0.95f);
        add(flash);

        int sparkCount = (int) ((26 + 46 * energy) * intensity.getValue());
        for (int i = 0; i < sparkCount; i++) {
            double az = random.nextDouble() * Math.PI * 2;
            double spread = 0.25 + random.nextDouble() * 0.9;
            Vec3d dir = new Vec3d(Math.cos(az) * spread, 0.55 + random.nextDouble() * 1.0, Math.sin(az) * spread).normalize();
            Particle p = new Particle();
            p.kind = K_SPARK;
            p.set(at.add(0, 0.08, 0), dir.multiply(rand(5.5, 15.0) * (0.7 + energy * 0.7)));
            p.life = rand(0.5, 1.2); p.s0 = p.s1 = rand(0.03, 0.06) * scale;
            p.stretch = 0.05f; p.drag = 0.7f; p.grav = 20f; p.collide = true; p.bounce = 0.4f; p.cooling = true;
            p.setColor(Color.WHITE, 1f);
            add(p);
        }

        if (dust.isEnabled()) spawnDust(im);
        if (debris.isEnabled() && im.hasGround) {
            spawnChunks(im);
            spawnBoulders(im);
        }
        spawnEmbers(im);
        im.rayAngles = new float[18];
        for (int i = 0; i < im.rayAngles.length; i++) im.rayAngles[i] = (float) (Math.PI * 2 * (i + random.nextFloat() * 0.7) / im.rayAngles.length);
        im.rayLengths = new float[18];
        for (int i = 0; i < im.rayLengths.length; i++) im.rayLengths[i] = (1.5f + random.nextFloat() * 2.8f) * scale;

        impacts.add(im);
        while (impacts.size() > MAX_IMPACTS) impacts.remove(0);

        if (sound.isEnabled() && mc.world != null) {
            float vol = 0.8f + 0.7f * energy;
            mc.world.playSoundClient(at.x, at.y, at.z, SoundEvents.ENTITY_GENERIC_EXPLODE.value(), SoundCategory.PLAYERS,
                    vol * 0.30f, 1.55f - 0.55f * energy, false);
        }
    }

    private void spawnDust(Impact im) {
        Vec3d at = im.center;
        Color base = mixColor(lighten(im.surfaceColor, 0.45f), new Color(150, 148, 144), 0.45f);
        int ring = (int) ((20 + 26 * im.energy) * intensity.getValue());
        for (int i = 0; i < ring; i++) {
            double az = random.nextDouble() * Math.PI * 2;
            double speed = rand(2.4, 6.2) * (0.6 + im.energy * 0.9);
            Particle p = new Particle();
            p.kind = K_SMOKE;
            p.set(at.add(Math.cos(az) * 0.3, 0.12, Math.sin(az) * 0.3),
                    new Vec3d(Math.cos(az) * speed, rand(0.2, 1.3), Math.sin(az) * speed));
            p.life = rand(1.8, 3.4); p.s0 = 0.5f * im.scale; p.s1 = rand(1.6, 2.7) * im.scale;
            p.drag = 2.5f; p.buoy = 0.45f; p.turb = 0.9f; p.lit = true;
            p.setColor(jitter(base, 14), rand(0.38, 0.62));
            add(p);
        }
        int plume = (int) ((10 + 10 * im.energy) * intensity.getValue());
        for (int i = 0; i < plume; i++) {
            double az = random.nextDouble() * Math.PI * 2;
            Particle p = new Particle();
            p.kind = K_SMOKE;
            p.set(at.add(Math.cos(az) * 0.25, 0.1, Math.sin(az) * 0.25),
                    new Vec3d(Math.cos(az) * rand(0.2, 1.2), rand(3.0, 8.0) * (0.7 + im.energy * 0.8), Math.sin(az) * rand(0.2, 1.2)));
            p.life = rand(1.6, 2.8); p.s0 = 0.7f * im.scale; p.s1 = rand(1.8, 3.0) * im.scale;
            p.drag = 2.2f; p.buoy = 0.55f; p.turb = 1.2f; p.lit = true;
            p.setColor(jitter(mixColor(base, new Color(120, 118, 114), 0.4f), 12), rand(0.35, 0.55));
            add(p);
        }
    }

    private void spawnBoulders(Impact im) {
        int count = (int) ((4 + 7 * im.energy) * intensity.getValue());
        for (int i = 0; i < count && chunks.size() < MAX_CHUNKS + 20; i++) {
            double az = random.nextDouble() * Math.PI * 2;
            double r = rand(0.5, 1.6) * im.scale;
            Chunk c = new Chunk();
            c.x = im.center.x + Math.cos(az) * r;
            c.z = im.center.z + Math.sin(az) * r;
            c.y = im.center.y + 0.1;
            double out = rand(0.8, 2.6) * (0.6 + im.energy);
            c.vx = Math.cos(az) * out; c.vz = Math.sin(az) * out;
            c.vy = rand(3.2, 7.0) * (0.6 + im.energy * 0.7);
            c.size = rand(0.20, 0.36) * (0.85f + 0.3f * im.scale);
            c.aspect = rand(0.5, 0.95);
            c.axis = new Vector3f((float) random.nextGaussian(), (float) random.nextGaussian(), (float) random.nextGaussian());
            if (c.axis.lengthSquared() < 1e-4f) c.axis.set(0, 1, 0);
            c.axis.normalize();
            c.spin = rand(1.5, 5.0);
            float shade = rand(0.45, 0.85);
            Color s = im.surfaceColor;
            c.color = new Color(MathHelper.clamp((int) (s.getRed() * shade), 0, 255),
                    MathHelper.clamp((int) (s.getGreen() * shade), 0, 255), MathHelper.clamp((int) (s.getBlue() * shade), 0, 255));
            c.big = true;
            chunks.add(c);
        }
    }

    private void spawnEmbers(Impact im) {
        Color hot = im.lightning ? new Color(150, 210, 255) : new Color(255, 150, 60);
        int n = (int) ((22 + 30 * im.energy) * intensity.getValue());
        for (int i = 0; i < n; i++) {
            double az = random.nextDouble() * Math.PI * 2;
            double out = rand(0.6, 3.6);
            Particle p = new Particle();
            p.kind = K_GLOW;
            p.set(im.center.add(Math.cos(az) * 0.3, 0.2, Math.sin(az) * 0.3),
                    new Vec3d(Math.cos(az) * out, rand(2.0, 7.0) * (0.6 + im.energy), Math.sin(az) * out));
            p.life = rand(1.1, 2.8); p.s0 = rand(0.07, 0.13); p.s1 = 0.03f;
            p.drag = 1.8f; p.buoy = 1.6f; p.turb = 2.0f;
            p.setColor(hot, 1f);
            add(p);
        }
    }

    private void spawnChunks(Impact im) {
        int count = (int) ((12 + 28 * im.energy) * intensity.getValue());
        for (int i = 0; i < count && chunks.size() < MAX_CHUNKS; i++) {
            double az = random.nextDouble() * Math.PI * 2;
            double r = rand(0.15, 0.9) * im.scale;
            Chunk c = new Chunk();
            c.x = im.center.x + Math.cos(az) * r;
            c.z = im.center.z + Math.sin(az) * r;
            c.y = im.center.y + 0.12;
            double out = rand(1.4, 4.6) * (0.6 + im.energy);
            c.vx = Math.cos(az) * out;
            c.vz = Math.sin(az) * out;
            c.vy = rand(5.0, 11.5) * (0.6 + im.energy * 0.8);
            c.size = (float) rand(0.045, 0.13) * (0.85f + 0.3f * im.scale);
            c.aspect = (float) rand(0.55, 1.0);
            c.axis = new Vector3f((float) random.nextGaussian(), (float) random.nextGaussian(), (float) random.nextGaussian());
            if (c.axis.lengthSquared() < 1e-4f) c.axis.set(0, 1, 0);
            c.axis.normalize();
            c.spin = (float) rand(4.0, 14.0);
            float shade = (float) rand(0.55, 1.12);
            Color s = im.surfaceColor;
            c.color = new Color(MathHelper.clamp((int) (s.getRed() * shade), 0, 255),
                    MathHelper.clamp((int) (s.getGreen() * shade), 0, 255), MathHelper.clamp((int) (s.getBlue() * shade), 0, 255));
            chunks.add(c);
        }
    }

    // ---- ground sampling ----------------------------------------------------------------------------

    private BlockHitResult findGround(Vec3d feet) {
        Vec3d from = new Vec3d(feet.x, feet.y + 0.7, feet.z);
        Vec3d to = new Vec3d(feet.x, feet.y - 8.0, feet.z);
        HitResult hit = mc.world.raycast(new RaycastContext(from, to, RaycastContext.ShapeType.COLLIDER,
                RaycastContext.FluidHandling.NONE, mc.player));
        return hit instanceof BlockHitResult b && hit.getType() == HitResult.Type.BLOCK ? b : null;
    }

    private Color surfaceColor(BlockState state, BlockPos pos) {
        try {
            int rgb = state.getMapColor(mc.world, pos).color;
            return new Color((rgb >> 16) & 255, (rgb >> 8) & 255, rgb & 255);
        } catch (Throwable t) {
            return new Color(130, 120, 108);
        }
    }

    /** Surface height at (x, z) searching down from {@code startY}; NaN when nothing solid is found. */
    private double surfaceY(double x, double startY, double z, int depthBlocks) {
        BlockPos.Mutable pos = new BlockPos.Mutable();
        int top = MathHelper.floor(startY);
        for (int by = top; by >= top - depthBlocks; by--) {
            pos.set(MathHelper.floor(x), by, MathHelper.floor(z));
            BlockState state = mc.world.getBlockState(pos);
            if (state.isAir()) continue;
            VoxelShape shape = state.getCollisionShape(mc.world, pos);
            if (shape.isEmpty()) continue;
            double y = by + shape.getMax(Direction.Axis.Y);
            if (y <= startY + 1.0) return y;
        }
        return Double.NaN;
    }

    private void buildHeightMap(Impact im) {
        float coverage = Math.max(im.maxRadius * 1.35f, im.lightning ? 9.2f * im.scale : 0f); // lightning arcs reach 9 * scale
        int radial = (int) Math.ceil(coverage / HM_STEP) + 2;
        im.hmRadial = radial;
        im.hm = new float[HM_ANGLES * radial];
        double gy = im.center.y;
        for (int a = 0; a < HM_ANGLES; a++) {
            double ang = Math.PI * 2 * a / HM_ANGLES;
            double cos = Math.cos(ang), sin = Math.sin(ang);
            float last = 0f;
            for (int r = 0; r < radial; r++) {
                double x = im.center.x + cos * r * HM_STEP, z = im.center.z + sin * r * HM_STEP;
                double y = surfaceY(x, gy + 1.3, z, 3);
                float off = Double.isNaN(y) ? last : (float) MathHelper.clamp(y - gy, -1.0, 1.2);
                im.hm[a * radial + r] = off;
                last = off;
            }
        }
    }

    private static float heightAt(Impact im, float radius, float angle) {
        if (im.hm == null) return 0f;
        float af = (angle / (float) (Math.PI * 2)) * HM_ANGLES;
        af = af - (float) Math.floor(af / HM_ANGLES) * HM_ANGLES;
        int a0 = (int) af % HM_ANGLES, a1 = (a0 + 1) % HM_ANGLES;
        float fa = af - (int) af;
        float rf = MathHelper.clamp(radius / HM_STEP, 0f, im.hmRadial - 1.001f);
        int r0 = (int) rf;
        float fr = rf - r0;
        float h00 = im.hm[a0 * im.hmRadial + r0], h01 = im.hm[a0 * im.hmRadial + r0 + 1];
        float h10 = im.hm[a1 * im.hmRadial + r0], h11 = im.hm[a1 * im.hmRadial + r0 + 1];
        float h0 = h00 + (h01 - h00) * fr, h1 = h10 + (h11 - h10) * fr;
        return h0 + (h1 - h0) * fa;
    }

    private void buildFractures(Impact im) {
        Random rng = new Random(im.seed);
        int mains = 9 + rng.nextInt(5);
        float reach = im.maxRadius * (0.50f + 0.12f * im.energy);
        for (int i = 0; i < mains; i++) {
            float angle = (float) (Math.PI * 2 * (i + rng.nextFloat() * 0.6) / mains);
            growCrack(im, rng, 0f, 0f, angle, reach * (0.55f + rng.nextFloat() * 0.45f), 1f, 0);
        }
    }

    private void growCrack(Impact im, Random rng, float sx, float sz, float angle, float length, float width, int depth) {
        int steps = Math.max(3, (int) (length / 0.38f));
        float[] xs = new float[steps + 1], zs = new float[steps + 1];
        xs[0] = sx; zs[0] = sz;
        float total = 0f;
        float[] cum = new float[steps + 1];
        float step = length / steps;
        for (int i = 1; i <= steps; i++) {
            angle += (rng.nextFloat() - 0.5f) * 0.62f;
            xs[i] = xs[i - 1] + (float) Math.cos(angle) * step;
            zs[i] = zs[i - 1] + (float) Math.sin(angle) * step;
            total += step;
            cum[i] = total;
            if (depth < 2 && i > 1 && i < steps - 1 && rng.nextFloat() < 0.20f) {
                float side = rng.nextBoolean() ? 1f : -1f;
                growCrack(im, rng, xs[i], zs[i], angle + side * (0.45f + rng.nextFloat() * 0.55f),
                        length * (0.28f + rng.nextFloat() * 0.25f) * (1f - cum[i] / length * 0.4f), width * 0.6f, depth + 1);
            }
        }
        Crack c = new Crack();
        c.xs = xs; c.zs = zs; c.cum = cum; c.total = total; c.width = width;
        c.delay = depth * 0.06f + (sx * sx + sz * sz > 0 ? (float) Math.sqrt(sx * sx + sz * sz) / 28f : 0f);
        im.cracks.add(c);
    }

    // ---- frame update -------------------------------------------------------------------------------

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

        for (int i = impacts.size() - 1; i >= 0; i--) {
            Impact im = impacts.get(i);
            im.age += dt;
            if (!im.strikeFired && im.lightning && im.age >= STRIKE_DELAY) {
                im.strikeFired = true;
                onStrike(im);
            }
            if (im.age > im.lifetime()) impacts.remove(i);
        }
        if (impacts.isEmpty() && particles.isEmpty() && chunks.isEmpty()) return;
        if (!ProceduralTextures.ensure()) return;

        simulate(dt);
        render(matrices);
    }

    private void onStrike(Impact im) {
        Vec3d at = im.center;
        for (int i = 0; i < (int) (34 * intensity.getValue()); i++) {
            double az = random.nextDouble() * Math.PI * 2;
            Vec3d dir = new Vec3d(Math.cos(az) * rand(0.2, 1.0), rand(0.5, 1.6), Math.sin(az) * rand(0.2, 1.0)).normalize();
            Particle p = new Particle();
            p.kind = K_SPARK;
            p.set(at.add(0, 0.1, 0), dir.multiply(rand(7.0, 20.0)));
            p.life = rand(0.4, 0.95); p.s0 = p.s1 = rand(0.03, 0.07) * im.scale;
            p.stretch = 0.055f; p.drag = 0.6f; p.grav = 18f; p.collide = true; p.bounce = 0.35f;
            p.setColor(new Color(190, 225, 255), 1f);
            add(p);
        }
        Particle flash = new Particle();
        flash.kind = K_GLOW; flash.set(at.add(0, 0.5, 0), Vec3d.ZERO);
        flash.life = 0.22f; flash.s0 = 4.5f * im.scale; flash.s1 = 7.5f * im.scale;
        flash.setColor(new Color(210, 232, 255), 1f);
        add(flash);
        if (sound.isEnabled() && mc.world != null) {
            mc.world.playSoundClient(at.x, at.y, at.z, SoundEvents.ENTITY_LIGHTNING_BOLT_IMPACT, SoundCategory.WEATHER,
                    2.2f, 1.25f, false);
            mc.world.playSoundClient(at.x, at.y, at.z, SoundEvents.ENTITY_LIGHTNING_BOLT_THUNDER, SoundCategory.WEATHER,
                    3.5f, 1.05f + random.nextFloat() * 0.25f, false);
        }
    }

    private void add(Particle p) {
        particles.add(p);
        if (particles.size() > MAX_PARTICLES) particles.remove(0);
    }

    private void simulate(float dt) {
        float t = (System.nanoTime() / 1.0e9f) % 1000f;
        if (dust.isEnabled()) {
            for (Impact im : impacts) {
                if (!im.hasGround) continue;
                float dur = 0.95f + 0.55f * im.energy;
                float tt = im.age / dur;
                if (tt >= 0.7f) continue;
                float crest = im.maxRadius * (1f - (float) Math.pow(1f - tt, 3.0));
                float speed = Math.max(1.5f, im.maxRadius * 3f * (float) Math.pow(1f - tt, 2.0) / dur);
                im.waveAcc += dt * 110f * (float) intensity.getValue();
                Color dustCol = mixColor(lighten(im.surfaceColor, 0.45f), new Color(150, 148, 144), 0.45f);
                while (im.waveAcc >= 1f) {
                    im.waveAcc -= 1f;
                    double az = random.nextDouble() * Math.PI * 2;
                    double wx = im.center.x + Math.cos(az) * crest, wz = im.center.z + Math.sin(az) * crest;
                    double wy = im.center.y + heightAt(im, crest, (float) az) + 0.1;
                    Particle p = new Particle();
                    p.kind = K_SMOKE;
                    p.set(new Vec3d(wx, wy, wz), new Vec3d(Math.cos(az) * speed * 0.35, rand(0.4, 1.6), Math.sin(az) * speed * 0.35));
                    p.life = rand(0.9, 1.8); p.s0 = 0.35f * im.scale; p.s1 = rand(1.0, 1.8) * im.scale;
                    p.drag = 3.2f; p.buoy = 0.5f; p.turb = 0.8f; p.lit = true;
                    p.setColor(jitter(dustCol, 12), rand(0.28, 0.46));
                    add(p);
                }
            }
        }
        for (int i = particles.size() - 1; i >= 0; i--) {
            Particle p = particles.get(i);
            p.age += dt;
            if (p.age >= p.life) { particles.remove(i); continue; }
            if (p.turb > 0f) {
                p.vx += p.turb * (Math.sin(p.y * 2.3 + t * 1.7 + p.seed) + Math.sin(p.z * 1.9 - t * 1.3 + p.seed * 2)) * dt;
                p.vy += p.turb * 0.5 * Math.sin(p.x * 2.1 + t * 1.1 + p.seed * 3) * dt;
                p.vz += p.turb * (Math.sin(p.x * 1.7 - t * 1.9 + p.seed) + Math.sin(p.y * 2.7 + t * 0.9 + p.seed)) * dt;
            }
            p.vy += (p.buoy - p.grav) * dt;
            double damp = Math.exp(-p.drag * dt);
            p.vx *= damp; p.vy *= damp; p.vz *= damp;
            double nx = p.x + p.vx * dt, ny = p.y + p.vy * dt, nz = p.z + p.vz * dt;
            if (p.collide && p.vy < 0) {
                double floor = surfaceY(nx, ny + 0.4, nz, 2);
                if (!Double.isNaN(floor) && ny < floor) {
                    ny = floor + 0.01;
                    if (-p.vy * p.bounce < 0.6) { p.vx = p.vy = p.vz = 0; p.collide = false; p.grav = 0; }
                    else { p.vy = -p.vy * p.bounce; p.vx *= 0.55; p.vz *= 0.55; }
                }
            }
            p.x = nx; p.y = ny; p.z = nz;
            p.rot += p.rotVel * dt;
        }

        for (int i = chunks.size() - 1; i >= 0; i--) {
            Chunk c = chunks.get(i);
            c.age += dt;
            if (c.age > c.life()) { chunks.remove(i); continue; }
            if (c.resting) continue;
            c.vy -= 26.0 * dt;
            c.x += c.vx * dt; c.y += c.vy * dt; c.z += c.vz * dt;
            c.rotation.rotateAxis(c.spin * dt, c.axis);
            double floor = surfaceY(c.x, c.y + 0.5, c.z, 3);
            if (!Double.isNaN(floor) && c.y - c.size < floor && c.vy < 0) {
                c.y = floor + c.size * 0.9;
                if (-c.vy < 2.2) {
                    c.resting = true;
                    c.restAge = c.age;
                } else {
                    c.vy = -c.vy * 0.34;
                    c.vx *= 0.58; c.vz *= 0.58;
                    c.spin *= 0.6f;
                }
            }
            // Trailing dust while a fragment is still flying fast.
            if (!c.resting && c.age < 0.9f && random.nextFloat() < dt * 22f) {
                Particle p = new Particle();
                p.kind = K_SMOKE;
                p.set(new Vec3d(c.x, c.y, c.z), new Vec3d(c.vx * 0.08, c.vy * 0.05, c.vz * 0.08));
                p.life = rand(0.5, 0.9); p.s0 = 0.12f; p.s1 = rand(0.35, 0.6);
                p.drag = 3.0f; p.lit = true;
                p.setColor(lighten(c.color, 0.35f), rand(0.22, 0.34));
                add(p);
            }
        }
    }

    // ---- rendering ----------------------------------------------------------------------------------

    private void render(MatrixStack matrices) {
        Camera camera = mc.gameRenderer.getCamera();
        if (camera == null) return;
        Vec3d cam = camera.getCameraPos();
        Quaternionf camRot = camera.getRotation();
        Vector3f right = new Vector3f(1f, 0f, 0f).rotate(camRot);
        Vector3f up = new Vector3f(0f, 1f, 0f).rotate(camRot);
        Matrix4f m = matrices.peek().getPositionMatrix();

        // Reused across frames: the shared pool drops allocators above 512 KB, which meant a native malloc/free per frame.
        if (frameAllocator == null) frameAllocator = new BufferAllocator(1 << 20);
        BufferAllocator allocator = frameAllocator;
        try {
            VertexConsumerProvider.Immediate imm = VertexConsumerProvider.immediate(allocator);

            // 1. Dark ground marks: scorch and fracture fissures.
            VertexConsumer scorch = imm.getBuffer(ProceduralTextures.layer(ProceduralTextures.SCORCH, Blend.ALPHA, true));
            for (Impact im : impacts) drawScorch(scorch, m, cam, im);
            imm.draw();
            VertexConsumer fissure = imm.getBuffer(ProceduralTextures.layer(ProceduralTextures.FISSURE, Blend.ALPHA, true));
            for (Impact im : impacts) drawFractures(fissure, m, cam, im, false);
            imm.draw();

            // 2. Solid debris.
            if (!chunks.isEmpty()) {
                VertexConsumer solid = imm.getBuffer(RenderTypes.TRIANGLES);
                for (Chunk c : chunks) drawChunk(solid, m, cam, c);
                imm.draw();
            }

            // 3. Dust and smoke, far to near.
            drawSmoke(imm, m, cam, right, up);

            // 4. Additive ground light: ripples and hot fractures.
            VertexConsumer ringBuf = imm.getBuffer(ProceduralTextures.layer(ProceduralTextures.RING, Blend.ADDITIVE, true));
            for (Impact im : impacts) drawRipples(ringBuf, m, cam, im);
            imm.draw();
            VertexConsumer glowCracks = imm.getBuffer(ProceduralTextures.layer(ProceduralTextures.BOLT, Blend.ADDITIVE, true));
            for (Impact im : impacts) {
                drawFractures(glowCracks, m, cam, im, true);
                if (rays.isEnabled()) {
                    drawRays(glowCracks, m, cam, im);
                    drawPillar(glowCracks, m, cam, im);
                }
            }
            imm.draw();

            // 5. Pressure dome.
            if (dome.isEnabled()) {
                VertexConsumer domeBuf = imm.getBuffer(RenderTypes.TRIANGLES);
                for (Impact im : impacts) drawDome(domeBuf, m, cam, im);
                imm.draw();
            }

            // 6. Additive sprites, then the railgun on top.
            drawLightSprites(imm, m, cam, right, up);
            for (Impact im : impacts) {
                if (im.lightning && im.strikeFired) drawRailgun(imm, m, cam, im);
            }
        } catch (Throwable t) {
            // Keep impact visuals isolated from the rest of the world render.
            interstellarTikiTiks.apexx.client.util.ClientInternalLog.warn("MaceImpact", "render failed", t);
        } finally {
            allocator.clear();
        }
    }

    /** Depth-bias that grows with distance so decals on the ground do not z-fight far away. */
    private static float lift(double dist) {
        return (float) (0.014 + 0.0007 * dist);
    }

    private static double distance(Vec3d a, Vec3d b) {
        return Math.sqrt(a.squaredDistanceTo(b));
    }

    private Color heat(Impact im, float age) {
        if (colorMode.is("Custom")) return customColor.getValue() == null ? new Color(255, 170, 80) : customColor.getValue();
        if (colorMode.is("Theme Sync")) return AccentState.accentColor();
        if (im.lightning) return mixColor(new Color(215, 236, 255), new Color(120, 175, 255), MathHelper.clamp(age / 2.5f, 0f, 1f));
        if (age < 0.25f) return mixColor(new Color(255, 250, 232), new Color(255, 190, 90), age / 0.25f);
        if (age < 1.3f) return mixColor(new Color(255, 190, 90), new Color(255, 120, 40), (age - 0.25f) / 1.05f);
        return mixColor(new Color(255, 120, 40), new Color(170, 36, 12), MathHelper.clamp((age - 1.3f) / 1.8f, 0f, 1f));
    }

    private void drawScorch(VertexConsumer buf, Matrix4f m, Vec3d cam, Impact im) {
        if (!im.hasGround) return;
        float alpha = 0.80f * (1f - smooth(5.0f, 9.0f, im.age)) * smooth(0.0f, 0.12f, im.age);
        if (alpha < 0.01f) return;
        polarGrid(buf, m, cam, im, 1.7f * im.scale, 0f, 1f, 7, 28, VisualGeometry.argb(alpha, 1f, 1f, 1f), 0.010f, 0f);
    }

    private void drawRipples(VertexConsumer buf, Matrix4f m, Vec3d cam, Impact im) {
        if (!ripple.isEnabled() || !im.hasGround) return;
        float[][] waves = {{0f, 1f, 1f}, {0.16f, 0.72f, 0.55f}, {0.34f, 0.50f, 0.32f}};
        float dur = 0.95f + 0.55f * im.energy;
        Color tint = colorMode.is("Natural") ? (im.lightning ? new Color(205, 228, 255) : new Color(255, 236, 206))
                : mixColor(heat(im, 0f), Color.WHITE, 0.55f);
        for (float[] w : waves) {
            float t = (im.age - w[0]) / dur;
            if (t < 0f || t >= 1f) continue;
            float crest = im.maxRadius * w[1] * (1f - (float) Math.pow(1f - t, 3.0));
            float disc = crest / 0.8f;
            if (disc < 0.05f) continue;
            float alpha = (float) Math.pow(1f - t, 1.25) * w[2] * 0.95f;
            polarGrid(buf, m, cam, im, disc, 0.5f, 1f, 22, 96, VisualGeometry.color(tint, alpha), 0.018f, 0.11f * im.scale * (1f - t));
        }
    }

    /**
     * Textured polar patch that follows the sampled terrain. {@code d0..d1} select the annulus in texture radius
     * space (0..1); {@code raise} lifts the d~0.8 crest band so it reads as a travelling wave.
     */
    private void polarGrid(VertexConsumer buf, Matrix4f m, Vec3d cam, Impact im, float disc, float d0, float d1,
                           int radSeg, int angSeg, int color, float baseLift, float raise) {
        float liftY = baseLift + lift(distance(im.center, cam));
        int cols = angSeg + 1;
        int total = (radSeg + 1) * cols;
        float[] px = new float[total], py = new float[total], pz = new float[total], pu = new float[total], pv = new float[total];
        for (int ri = 0; ri <= radSeg; ri++) {
            float d = d0 + (d1 - d0) * ri / radSeg;
            float radius = d * disc;
            float wave = raise > 0f ? raise * (float) Math.exp(-Math.pow((d - 0.8f) / 0.07f, 2)) : 0f;
            for (int ai = 0; ai <= angSeg; ai++) {
                float ang = (float) (Math.PI * 2 * ai / angSeg);
                float cos = MathHelper.cos(ang), sin = MathHelper.sin(ang);
                int i = ri * cols + ai;
                px[i] = (float) (im.center.x + cos * radius - cam.x);
                py[i] = (float) (im.center.y + heightAt(im, radius, ang) + liftY + wave - cam.y);
                pz[i] = (float) (im.center.z + sin * radius - cam.z);
                pu[i] = 0.5f + 0.5f * d * cos;
                pv[i] = 0.5f + 0.5f * d * sin;
            }
        }
        int light = VisualGeometry.FULL_BRIGHT;
        for (int ri = 0; ri < radSeg; ri++) {
            for (int ai = 0; ai < angSeg; ai++) {
                int a = ri * cols + ai, b = a + 1, c = (ri + 1) * cols + ai + 1, d = (ri + 1) * cols + ai;
                VisualGeometry.vertex(buf, m, px[a], py[a], pz[a], pu[a], pv[a], color, light);
                VisualGeometry.vertex(buf, m, px[b], py[b], pz[b], pu[b], pv[b], color, light);
                VisualGeometry.vertex(buf, m, px[c], py[c], pz[c], pu[c], pv[c], color, light);
                VisualGeometry.vertex(buf, m, px[a], py[a], pz[a], pu[a], pv[a], color, light);
                VisualGeometry.vertex(buf, m, px[c], py[c], pz[c], pu[c], pv[c], color, light);
                VisualGeometry.vertex(buf, m, px[d], py[d], pz[d], pu[d], pv[d], color, light);
            }
        }
    }

    private void drawFractures(VertexConsumer buf, Matrix4f m, Vec3d cam, Impact im, boolean glow) {
        if (!fractures.isEnabled() || !im.hasGround) return;
        float liftY = (glow ? 0.022f : 0.017f) + lift(distance(im.center, cam));
        float fadeDark = 1f - smooth(5.5f, 9.5f, im.age);
        Color glowCol = null;
        for (Crack c : im.cracks) {
            float local = im.age - c.delay;
            if (local <= 0f) continue;
            float grow = 1f - (float) Math.pow(1f - Math.min(1f, local / 0.42f), 3.0);
            float revealed = c.total * grow;
            float alpha;
            float baseHalf;
            if (glow) {
                alpha = (float) Math.pow(MathHelper.clamp(1f - local / 3.4f, 0f, 1f), 1.3);
                if (alpha < 0.01f) continue;
                glowCol = heat(im, local);
                baseHalf = (0.20f + 0.14f * c.width) * im.scale;
            } else {
                alpha = 0.95f * fadeDark;
                if (alpha < 0.01f) continue;
                baseHalf = (0.075f + 0.085f * c.width) * im.scale;
            }
            double prevLx = 0, prevLy = 0, prevLz = 0, prevRx = 0, prevRy = 0, prevRz = 0;
            int prevColor = 0;
            boolean havePrev = false;
            for (int i = 0; i <= c.xs.length - 1; i++) {
                float s = c.cum[i];
                float x = c.xs[i], z = c.zs[i];
                if (s > revealed) {
                    // Partial last segment up to the growth front.
                    if (i == 0) break;
                    float segLen = c.cum[i] - c.cum[i - 1];
                    float f = segLen <= 1e-5f ? 0f : (revealed - c.cum[i - 1]) / segLen;
                    x = MathHelper.lerp(f, c.xs[i - 1], c.xs[i]);
                    z = MathHelper.lerp(f, c.zs[i - 1], c.zs[i]);
                    s = revealed;
                }
                // Lateral direction from the local tangent.
                int a = Math.max(0, i - 1), b = Math.min(c.xs.length - 1, i + 1);
                float tx = c.xs[b] - c.xs[a], tz = c.zs[b] - c.zs[a];
                float tl = (float) Math.sqrt(tx * tx + tz * tz);
                if (tl < 1e-5f) { tx = 1f; tz = 0f; tl = 1f; }
                float lx = -tz / tl, lz = tx / tl;
                float taper = 1f - 0.72f * (s / Math.max(c.total, 1e-4f));
                float half = baseHalf * taper;
                float radius = (float) Math.sqrt(x * x + z * z);
                float ang = (float) Math.atan2(z, x);
                double wy = im.center.y + heightAt(im, radius, ang) + liftY;
                double cx = im.center.x + x - cam.x, cz = im.center.z + z - cam.z, cy = wy - cam.y;
                float front = glow ? 1f + 0.9f * (float) Math.exp(-(revealed - s) / 0.35f) * (grow < 1f ? 1f : 0f) : 1f;
                int color = glow
                        ? VisualGeometry.color(glowCol, MathHelper.clamp(alpha * front * (0.55f + 0.45f * taper), 0f, 1f))
                        : VisualGeometry.argb(alpha, 1f, 1f, 1f);
                double lxL = cx + lx * half, lzL = cz + lz * half, lxR = cx - lx * half, lzR = cz - lz * half;
                if (havePrev) {
                    VisualGeometry.quad(buf, m, prevLx, prevLy, prevLz, lxL, cy, lzL, lxR, cy, lzR, prevRx, prevRy, prevRz,
                            0f, 0f, 1f, 1f, prevColor, color, color, prevColor, VisualGeometry.FULL_BRIGHT);
                }
                prevLx = lxL; prevLy = cy; prevLz = lzL; prevRx = lxR; prevRy = cy; prevRz = lzR;
                prevColor = color;
                havePrev = true;
                if (s >= revealed) break;
            }
        }
    }

    /** Hot rays racing across the terrain from the impact point. */
    private void drawRays(VertexConsumer buf, Matrix4f m, Vec3d cam, Impact im) {
        if (!im.hasGround || im.rayAngles == null || im.age > 0.42f) return;
        float grow = 1f - (float) Math.pow(1f - Math.min(1f, im.age / 0.16f), 3.0);
        float fade = (float) Math.pow(1f - im.age / 0.42f, 1.6);
        Color col = heat(im, 0f);
        float liftY = 0.03f + lift(distance(im.center, cam));
        for (int r = 0; r < im.rayAngles.length; r++) {
            float ang = im.rayAngles[r];
            float len = im.rayLengths[r] * grow;
            double cos = Math.cos(ang), sin = Math.sin(ang);
            int segs = 8;
            double prevLx = 0, prevLy = 0, prevLz = 0, prevRx = 0, prevRy = 0, prevRz = 0;
            int prevColor = 0;
            for (int i = 0; i <= segs; i++) {
                float f = i / (float) segs;
                float rad = 0.35f * im.scale + len * f;
                float half = (0.16f * im.scale) * (1f - f * 0.85f);
                double wx = im.center.x + cos * rad - cam.x, wz = im.center.z + sin * rad - cam.z;
                double wy = im.center.y + heightAt(im, rad, ang) + liftY - cam.y;
                int color = VisualGeometry.color(col, fade * (1f - f * 0.8f));
                double lx = wx - sin * half, lz = wz + cos * half, rx = wx + sin * half, rz = wz - cos * half;
                if (i > 0) {
                    VisualGeometry.quadCross(buf, m, prevLx, prevLy, prevLz, prevRx, prevRy, prevRz, rx, wy, rz, lx, wy, lz,
                            prevColor, prevColor, color, color, VisualGeometry.FULL_BRIGHT);
                }
                prevLx = lx; prevLy = wy; prevLz = lz; prevRx = rx; prevRy = wy; prevRz = rz;
                prevColor = color;
            }
        }
    }

    /** Short vertical column of heat/light at the instant of impact. */
    private void drawPillar(VertexConsumer buf, Matrix4f m, Vec3d cam, Impact im) {
        float life = 0.4f;
        if (im.age >= life) return;
        float t = im.age / life;
        float fade = (float) Math.pow(1f - t, 1.5);
        Vec3d base = im.center.subtract(cam);
        float h = (4.5f + 6f * im.energy) * im.scale * (0.35f + 0.65f * (1f - (float) Math.pow(1f - Math.min(1f, t * 3f), 3.0)));
        Color warm = heat(im, 0f);
        beamRibbon(buf, m, base, 0f, h, (0.9f + 0.9f * t) * im.scale, warm, 0.55f * fade, 0f, im.age);
        beamRibbon(buf, m, base, 0f, h, 0.28f * im.scale, Color.WHITE, 0.95f * fade, 0f, im.age);
    }

    private void drawChunk(VertexConsumer buf, Matrix4f m, Vec3d cam, Chunk c) {
        float life = c.life();
        float shrink = 1f - smooth(life - 0.9f, life, c.age);
        if (shrink <= 0.02f) return;
        float hx = c.size * shrink, hy = c.size * c.aspect * shrink, hz = c.size * (0.55f + 0.45f * c.aspect) * shrink;
        Vector3f v = new Vector3f();
        double[][] corner = new double[8][3];
        for (int i = 0; i < 8; i++) {
            v.set((i & 1) == 0 ? -hx : hx, (i & 2) == 0 ? -hy : hy, (i & 4) == 0 ? -hz : hz);
            c.rotation.transform(v);
            corner[i][0] = c.x + v.x - cam.x;
            corner[i][1] = c.y + v.y - cam.y;
            corner[i][2] = c.z + v.z - cam.z;
        }
        int[][] faces = {{0, 1, 3, 2}, {4, 5, 7, 6}, {0, 1, 5, 4}, {2, 3, 7, 6}, {0, 2, 6, 4}, {1, 3, 7, 5}};
        float[][] normals = {{0, 0, -1}, {0, 0, 1}, {0, -1, 0}, {0, 1, 0}, {-1, 0, 0}, {1, 0, 0}};
        float lx = 0.35f, ly = 0.85f, lz = 0.40f;
        float ll = (float) Math.sqrt(lx * lx + ly * ly + lz * lz);
        for (int f = 0; f < 6; f++) {
            v.set(normals[f][0], normals[f][1], normals[f][2]);
            c.rotation.transform(v);
            float lambert = Math.max(0f, (v.x * lx + v.y * ly + v.z * lz) / ll);
            float shade = 0.40f + 0.60f * lambert;
            int color = VisualGeometry.shade(0xFF000000 | c.color.getRGB(), shade);
            int[] q = faces[f];
            posVertex(buf, m, corner[q[0]], color);
            posVertex(buf, m, corner[q[1]], color);
            posVertex(buf, m, corner[q[2]], color);
            posVertex(buf, m, corner[q[0]], color);
            posVertex(buf, m, corner[q[2]], color);
            posVertex(buf, m, corner[q[3]], color);
        }
    }

    private static void posVertex(VertexConsumer buf, Matrix4f m, double[] p, int argb) {
        buf.vertex(m, (float) p[0], (float) p[1], (float) p[2])
                .color(((argb >> 16) & 255) / 255f, ((argb >> 8) & 255) / 255f, (argb & 255) / 255f, ((argb >>> 24) & 255) / 255f);
    }

    private static void posVertex(VertexConsumer buf, Matrix4f m, double x, double y, double z, int argb) {
        buf.vertex(m, (float) x, (float) y, (float) z)
                .color(((argb >> 16) & 255) / 255f, ((argb >> 8) & 255) / 255f, (argb & 255) / 255f, ((argb >>> 24) & 255) / 255f);
    }

    private void drawSmoke(VertexConsumerProvider.Immediate imm, Matrix4f m, Vec3d cam, Vector3f right, Vector3f up) {
        List<Particle> smoke = new ArrayList<>();
        for (Particle p : particles) {
            if (p.kind == K_SMOKE) {
                p.depth = (p.x - cam.x) * (p.x - cam.x) + (p.y - cam.y) * (p.y - cam.y) + (p.z - cam.z) * (p.z - cam.z);
                smoke.add(p);
            }
        }
        if (smoke.isEmpty()) return;
        smoke.sort(Comparator.comparingDouble((Particle p) -> -p.depth));
        VertexConsumer buf = imm.getBuffer(ProceduralTextures.layer(ProceduralTextures.SMOKE, Blend.ALPHA, true));
        BlockPos.Mutable cell = new BlockPos.Mutable();
        for (Particle p : smoke) {
            float t = p.age / p.life;
            float alpha = p.a * smooth(0f, 0.08f, t) * (float) Math.pow(1f - t, 1.3);
            if (alpha < 0.004f) continue;
            float size = MathHelper.lerp(1f - (float) Math.pow(1f - t, 2.0), p.s0, p.s1);
            int light = p.lit ? WorldRenderer.getLightmapCoordinates(mc.world, cell.set(p.x, p.y, p.z)) : VisualGeometry.FULL_BRIGHT;
            float u0 = (p.sprite & 1) * 0.5f, v0 = (p.sprite >> 1) * 0.5f;
            VisualGeometry.sprite(buf, m, p.x - cam.x, p.y - cam.y, p.z - cam.z, right.x, right.y, right.z, up.x, up.y, up.z,
                    size * 0.5, size * 0.5, p.rot, u0, v0, u0 + 0.5f, v0 + 0.5f,
                    VisualGeometry.argb(alpha, p.r, p.g, p.b), light);
        }
        imm.draw();
    }

    private void drawLightSprites(VertexConsumerProvider.Immediate imm, Matrix4f m, Vec3d cam, Vector3f right, Vector3f up) {
        boolean anySpark = false, anyGlow = false;
        for (Particle p : particles) {
            if (p.kind == K_SPARK) anySpark = true; else if (p.kind == K_GLOW) anyGlow = true;
        }
        if (anySpark) {
            VertexConsumer buf = imm.getBuffer(ProceduralTextures.layer(ProceduralTextures.STREAK, Blend.ADDITIVE, true));
            for (Particle p : particles) {
                if (p.kind != K_SPARK) continue;
                float t = p.age / p.life;
                float alpha = p.a * (1f - t * t * t) * smooth(0f, 0.03f, t);
                if (alpha < 0.004f) continue;
                float r = p.r, g = p.g, b = p.b;
                if (p.cooling) {
                    float[] bb = blackbody(t);
                    r *= bb[0]; g *= bb[1]; b *= bb[2];
                }
                drawStreak(buf, m, cam, p, VisualGeometry.argb(alpha, r, g, b));
            }
            imm.draw();
        }
        if (anyGlow) {
            VertexConsumer buf = imm.getBuffer(ProceduralTextures.layer(ProceduralTextures.GLOW, Blend.ADDITIVE, true));
            for (Particle p : particles) {
                if (p.kind != K_GLOW) continue;
                float t = p.age / p.life;
                float alpha = p.a * (1f - t) * (1f - t);
                if (alpha < 0.004f) continue;
                float size = MathHelper.lerp(t, p.s0, p.s1);
                VisualGeometry.sprite(buf, m, p.x - cam.x, p.y - cam.y, p.z - cam.z, right.x, right.y, right.z, up.x, up.y, up.z,
                        size * 0.5, size * 0.5, 0f, 0f, 0f, 1f, 1f, VisualGeometry.argb(alpha, p.r, p.g, p.b), VisualGeometry.FULL_BRIGHT);
            }
            imm.draw();
        }
    }

    private void drawStreak(VertexConsumer buf, Matrix4f m, Vec3d cam, Particle p, int color) {
        double speed = Math.sqrt(p.vx * p.vx + p.vy * p.vy + p.vz * p.vz);
        double dx = 0, dy = -1, dz = 0;
        if (speed > 1e-3) { dx = p.vx / speed; dy = p.vy / speed; dz = p.vz / speed; }
        double length = Math.max(p.s0 * 2.0, speed * p.stretch + p.s0);
        double cx = p.x - cam.x, cy = p.y - cam.y, cz = p.z - cam.z;
        // Side vector perpendicular to both the travel direction and the view ray.
        double sx = dy * -cz - dz * -cy, sy = dz * -cx - dx * -cz, sz = dx * -cy - dy * -cx;
        double sl = Math.sqrt(sx * sx + sy * sy + sz * sz);
        if (sl < 1e-9) return;
        double half = p.s0 * 0.5 / sl;
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

    /** Translucent hemispherical shock dome, brightest at grazing angles (fresnel) so it reads as glass-like pressure. */
    private void drawDome(VertexConsumer buf, Matrix4f m, Vec3d cam, Impact im) {
        float life = 0.55f;
        float t = im.age / life;
        if (t < 0f || t >= 1f) return;
        float radius = im.maxRadius * 0.85f * (1f - (float) Math.pow(1f - Math.min(1f, t * 1.2f), 3.0));
        if (radius < 0.1f) return;
        float base = (float) Math.pow(1f - t, 2.2) * 0.55f;
        int lon = 32, lat = 10;
        Vec3d c = im.center.add(0, im.hasGround ? 0.02 : 0, 0);
        float[][] verts = new float[(lat + 1) * (lon + 1)][4];
        for (int i = 0; i <= lat; i++) {
            float phi = (float) (Math.PI * 0.5 * i / lat);
            float ring = MathHelper.cos(phi), y = MathHelper.sin(phi);
            for (int j = 0; j <= lon; j++) {
                float th = (float) (Math.PI * 2 * j / lon);
                float nx = ring * MathHelper.cos(th), nz = ring * MathHelper.sin(th);
                double wx = c.x + nx * radius, wy = c.y + y * radius, wz = c.z + nz * radius;
                double vx = cam.x - wx, vy = cam.y - wy, vz = cam.z - wz;
                double vl = Math.sqrt(vx * vx + vy * vy + vz * vz);
                double facing = vl < 1e-6 ? 0 : Math.abs((nx * vx + y * vy + nz * vz) / vl);
                float fres = (float) Math.pow(1.0 - facing, 2.0) + 0.04f;
                float edgeFade = i == 0 ? 0.35f : 1f;
                int idx = i * (lon + 1) + j;
                verts[idx][0] = (float) (wx - cam.x); verts[idx][1] = (float) (wy - cam.y); verts[idx][2] = (float) (wz - cam.z);
                verts[idx][3] = MathHelper.clamp(base * fres * edgeFade, 0f, 1f);
            }
        }
        Color tint = im.lightning ? new Color(190, 220, 255) : new Color(255, 240, 220);
        for (int i = 0; i < lat; i++) {
            for (int j = 0; j < lon; j++) {
                int a = i * (lon + 1) + j, b = a + 1, d = (i + 1) * (lon + 1) + j, e = d + 1;
                domeVertex(buf, m, verts[a], tint); domeVertex(buf, m, verts[b], tint); domeVertex(buf, m, verts[e], tint);
                domeVertex(buf, m, verts[a], tint); domeVertex(buf, m, verts[e], tint); domeVertex(buf, m, verts[d], tint);
            }
        }
    }

    private static void domeVertex(VertexConsumer buf, Matrix4f m, float[] v, Color tint) {
        buf.vertex(m, v[0], v[1], v[2]).color(tint.getRed() / 255f, tint.getGreen() / 255f, tint.getBlue() / 255f, v[3]);
    }

    // ---- railgun ------------------------------------------------------------------------------------

    private static final float[] BEAM_STOPS = {0f, 0.5f, 1.3f, 2.6f, 4.6f, 7.5f, 11f, 15.5f, 21f, 28f, 36f, 46f, 58f, 70f, BEAM_HEIGHT};

    private void drawRailgun(VertexConsumerProvider.Immediate imm, Matrix4f m, Vec3d cam, Impact im) {
        float a = im.age - STRIKE_DELAY;
        if (a < 0f || a > 2.2f) return;
        float scale = im.scale;
        Vec3d base = im.center.subtract(cam);

        float reveal = MathHelper.clamp(a / 0.045f, 0f, 1f);
        float headH = BEAM_HEIGHT * (1f - reveal);
        float hold = a < 0.18f ? 1f : (float) Math.exp(-(a - 0.18f) * 3.0f);
        float bloomHold = a < 0.18f ? 1f : (float) Math.exp(-(a - 0.18f) * 1.5f);
        float flicker = a < 0.7f ? 0.82f + 0.18f * (float) Math.sin(a * 170.0 + im.seed % 7) : 1f;
        float thin = 0.40f + 0.60f * hold;

        Color outer = new Color(90, 150, 255), mid = new Color(185, 222, 255), core = Color.WHITE;
        if (colorMode.is("Theme Sync")) {
            Color acc = AccentState.accentColor();
            outer = acc; mid = lighten(acc, 0.55f); core = lighten(acc, 0.9f);
        } else if (colorMode.is("Custom") && customColor.getValue() != null) {
            Color cc = customColor.getValue();
            outer = cc; mid = lighten(cc, 0.55f); core = lighten(cc, 0.9f);
        }

        VertexConsumer beam = imm.getBuffer(ProceduralTextures.layer(ProceduralTextures.BOLT, Blend.ADDITIVE, true));
        // Layered over-exposure: wide atmospheric bloom, glow body, hot body, then a doubled white needle.
        beamRibbon(beam, m, base, headH, BEAM_HEIGHT, 7.5f * scale * (0.55f + 0.45f * bloomHold), outer, 0.45f * bloomHold * flicker, 0.10f * scale, a);
        beamRibbon(beam, m, base, headH, BEAM_HEIGHT, 3.1f * scale * thin, outer, 0.85f * hold * flicker, 0.04f * scale, a);
        beamRibbon(beam, m, base, headH, BEAM_HEIGHT, 1.5f * scale * thin, mid, 1.0f * hold * flicker, 0f, a);
        beamRibbon(beam, m, base, headH, BEAM_HEIGHT, 0.55f * scale * thin, core, 1.0f * hold * flicker, 0f, a);
        beamRibbon(beam, m, base, headH, BEAM_HEIGHT, 0.55f * scale * thin, core, 1.0f * hold * flicker, 0f, a);

        // Plasma helix: two strands corkscrewing up the beam.
        if (a < 1.1f && reveal >= 1f) {
            float helixAlpha = (float) Math.pow(1f - a / 1.1f, 0.8) * flicker;
            for (int strand = 0; strand < 2; strand++) {
                List<Vec3d> helix = new ArrayList<>();
                for (int i = 0; i <= 48; i++) {
                    double h = i * 0.9;
                    double ph = h * 0.95 - a * 34.0 + strand * Math.PI;
                    double rad = (0.45 + 0.08 * Math.sin(h * 0.7 + a * 11.0)) * scale;
                    helix.add(base.add(Math.cos(ph) * rad, h, Math.sin(ph) * rad));
                }
                strip(beam, m, helix, 0.20f * scale, mid, 0.9f * helixAlpha);
            }
        }

        // Side arcs: regenerated ~28 times a second so they crackle.
        if (a < 0.9f) {
            int slot = (int) (a * 28f);
            float arcAlpha = (float) Math.pow(1f - a / 0.9f, 0.7) * flicker;
            for (int k = 0; k < 9; k++) {
                Random rng = new Random(im.seed * 31L + slot * 131L + k * 17L);
                if (rng.nextFloat() > 0.82f) continue;
                float startH = rng.nextFloat() * 26f;
                if (startH < headH) continue;
                double az = rng.nextDouble() * Math.PI * 2;
                float reach = (2.0f + rng.nextFloat() * 5.2f) * scale;
                double endH = Math.max(0.0, startH - (3.0 + rng.nextFloat() * 11.0));
                Vec3d p0 = base.add(0, startH, 0);
                Vec3d p1 = base.add(Math.cos(az) * reach, endH, Math.sin(az) * reach);
                List<Vec3d> path = jagged(p0, p1, rng, 5, 0.17f);
                strip(beam, m, path, 0.34f * scale, mid, 1.0f * arcAlpha);
                if (rng.nextFloat() < 0.7f) {
                    Vec3d from = path.get(path.size() / 2 + rng.nextInt(path.size() / 3));
                    double az2 = az + (rng.nextBoolean() ? 1 : -1) * (0.6 + rng.nextDouble() * 0.9);
                    Vec3d fork = from.add(Math.cos(az2) * reach * 0.5, -(1.0 + rng.nextFloat() * 4.0), Math.sin(az2) * reach * 0.5);
                    strip(beam, m, jagged(from, fork, rng, 4, 0.2f), 0.2f * scale, mid, 0.85f * arcAlpha);
                }
            }
        }

        // Ground tendrils: electricity crawling over the terrain.
        if (im.hasGround && a < 1.0f) {
            int slot = (int) (a * 24f);
            float tendrilAlpha = (float) Math.pow(1f - a, 0.9) * flicker;
            float liftY = 0.10f + lift(distance(im.center, cam));
            for (int k = 0; k < 8; k++) {
                Random rng = new Random(im.seed * 17L + slot * 71L + k * 13L);
                if (rng.nextFloat() > 0.8f) continue;
                double az = rng.nextDouble() * Math.PI * 2;
                float reach = (3.0f + rng.nextFloat() * 6.0f) * scale;
                int n = 16;
                double lat = 0;
                List<Vec3d> path = new ArrayList<>();
                for (int i = 0; i <= n; i++) {
                    float f = i / (float) n;
                    float rad = 0.3f + reach * f;
                    lat += (rng.nextDouble() - 0.5) * 0.5 * scale;
                    double wx = im.center.x + Math.cos(az) * rad - Math.sin(az) * lat;
                    double wz = im.center.z + Math.sin(az) * rad + Math.cos(az) * lat;
                    double wy = im.center.y + heightAt(im, rad, (float) az) + liftY;
                    path.add(new Vec3d(wx - cam.x, wy - cam.y, wz - cam.z));
                }
                strip(beam, m, path, 0.26f * scale, mid, 0.95f * tendrilAlpha);
            }
        }
        imm.draw();

        // Ring coils racing up the beam, and the expanding ground flash rings.
        VertexConsumer rings = imm.getBuffer(ProceduralTextures.layer(ProceduralTextures.RING, Blend.ADDITIVE, true));
        for (int k = 0; k < 16; k++) {
            float rk = a - k * 0.016f;
            if (rk < 0f || rk > 0.6f) continue;
            float tk = rk / 0.6f;
            double h = 130.0 * rk * (1.0 - 0.35 * tk);
            if (h > BEAM_HEIGHT || h < headH) continue;
            float radius = (0.42f + 2.9f * tk) * scale;
            float alpha = (float) Math.pow(1f - tk, 1.5) * 0.85f;
            int col = VisualGeometry.color(k % 2 == 0 ? mid : core, alpha);
            double half = radius / 0.8f;
            VisualGeometry.quad(rings, m,
                    base.x - half, base.y + h, base.z - half, base.x + half, base.y + h, base.z - half,
                    base.x + half, base.y + h, base.z + half, base.x - half, base.y + h, base.z + half,
                    0f, 0f, 1f, 1f, col, col, col, col, VisualGeometry.FULL_BRIGHT);
        }
        if (im.hasGround && a < 0.6f) {
            float t = a / 0.6f;
            float crest = 7.5f * scale * (1f - (float) Math.pow(1f - t, 3.0));
            polarGrid(rings, m, cam, im, crest / 0.8f, 0.5f, 1f, 18, 96, VisualGeometry.color(mid, (float) Math.pow(1f - t, 1.4)), 0.03f, 0.1f * scale * (1f - t));
        }
        imm.draw();
    }

    /** Camera-facing vertical ribbon from {@code headH} to the top of the beam. */
    private void beamRibbon(VertexConsumer buf, Matrix4f m, Vec3d base, float headH, float topH, float halfWidth, Color color, float alpha, float sway, float time) {
        if (alpha < 0.004f || halfWidth <= 0f) return;
        for (int i = 0; i < BEAM_STOPS.length - 1; i++) {
            float h0 = Math.max(BEAM_STOPS[i], headH), h1 = Math.min(BEAM_STOPS[i + 1], topH);
            if (h1 <= h0) continue;
            double s0 = sway * Math.sin(h0 * 0.35 + time * 7.0), s1 = sway * Math.sin(h1 * 0.35 + time * 7.0);
            double x0 = base.x + s0, z0 = base.z + s0 * 0.6, x1 = base.x + s1, z1 = base.z + s1 * 0.6;
            double mx = (x0 + x1) * 0.5, mz = (z0 + z1) * 0.5;
            // widthDir = up x toCamera, flattened horizontal.
            double wx = mz, wz = -mx;
            double wl = Math.sqrt(wx * wx + wz * wz);
            if (wl < 1e-6) continue;
            wx = wx / wl * halfWidth; wz = wz / wl * halfWidth;
            float a0 = alpha * (1f - 0.45f * h0 / topH), a1 = alpha * (1f - 0.45f * h1 / topH);
            int c0 = VisualGeometry.color(color, a0), c1 = VisualGeometry.color(color, a1);
            VisualGeometry.quadCross(buf, m,
                    x0 - wx, base.y + h0, z0 - wz, x0 + wx, base.y + h0, z0 + wz,
                    x1 + wx, base.y + h1, z1 + wz, x1 - wx, base.y + h1, z1 - wz,
                    c0, c0, c1, c1, VisualGeometry.FULL_BRIGHT);
        }
    }

    /** Midpoint-displaced polyline between two camera-relative points. */
    private static List<Vec3d> jagged(Vec3d a, Vec3d b, Random rng, int levels, float jitter) {
        List<Vec3d> pts = new ArrayList<>();
        pts.add(a);
        pts.add(b);
        double amp = a.distanceTo(b) * jitter;
        for (int level = 0; level < levels; level++) {
            List<Vec3d> next = new ArrayList<>(pts.size() * 2);
            for (int i = 0; i < pts.size() - 1; i++) {
                Vec3d p = pts.get(i), q = pts.get(i + 1);
                Vec3d mid = p.add(q).multiply(0.5).add((rng.nextDouble() - 0.5) * amp, (rng.nextDouble() - 0.5) * amp * 0.6, (rng.nextDouble() - 0.5) * amp);
                next.add(p);
                next.add(mid);
            }
            next.add(pts.get(pts.size() - 1));
            pts = next;
            amp *= 0.55;
        }
        return pts;
    }

    /** Camera-facing ribbon along a camera-relative polyline with a tapering width. */
    private void strip(VertexConsumer buf, Matrix4f m, List<Vec3d> pts, float halfWidth, Color color, float alpha) {
        if (alpha < 0.004f) return;
        int n = pts.size();
        double[] pxs = new double[n], pys = new double[n], pzs = new double[n];
        for (int i = 0; i < n; i++) {
            Vec3d p = pts.get(i);
            Vec3d prev = pts.get(Math.max(0, i - 1)), next = pts.get(Math.min(n - 1, i + 1));
            double tx = next.x - prev.x, ty = next.y - prev.y, tz = next.z - prev.z;
            // side = tangent x (toward camera at origin)
            double sx = ty * -p.z - tz * -p.y, sy = tz * -p.x - tx * -p.z, sz = tx * -p.y - ty * -p.x;
            double sl = Math.sqrt(sx * sx + sy * sy + sz * sz);
            if (sl < 1e-9) { sx = 1; sy = 0; sz = 0; sl = 1; }
            float taper = 1f - 0.6f * i / (float) (n - 1);
            double w = halfWidth * taper / sl;
            pxs[i] = sx * w; pys[i] = sy * w; pzs[i] = sz * w;
        }
        for (int i = 0; i < n - 1; i++) {
            Vec3d p = pts.get(i), q = pts.get(i + 1);
            float a0 = alpha * (1f - 0.5f * i / (float) (n - 1)), a1 = alpha * (1f - 0.5f * (i + 1) / (float) (n - 1));
            int c0 = VisualGeometry.color(color, a0), c1 = VisualGeometry.color(color, a1);
            VisualGeometry.quadCross(buf, m,
                    p.x + pxs[i], p.y + pys[i], p.z + pzs[i], p.x - pxs[i], p.y - pys[i], p.z - pzs[i],
                    q.x - pxs[i + 1], q.y - pys[i + 1], q.z - pzs[i + 1], q.x + pxs[i + 1], q.y + pys[i + 1], q.z + pzs[i + 1],
                    c0, c0, c1, c1, VisualGeometry.FULL_BRIGHT);
        }
    }

    @Override
    public void onRender(DrawContext context, float tickDelta) {
        if (!flash.isEnabled() || impacts.isEmpty() || mc.player == null) return;
        float best = 0f;
        for (Impact im : impacts) {
            double dist = Math.sqrt(mc.player.squaredDistanceTo(im.center));
            float proximity = MathHelper.clamp(1f - (float) dist / 45f, 0.18f, 1f);
            float a = 0f;
            if (im.age < 0.12f) a = 0.22f * (float) Math.pow(1f - im.age / 0.12f, 2.0);
            if (im.lightning && im.strikeFired) {
                float s = im.age - STRIKE_DELAY;
                if (s >= 0f && s < 0.32f) a = Math.max(a, 0.62f * (float) Math.pow(1f - s / 0.32f, 2.0));
            }
            best = Math.max(best, a * proximity);
        }
        int alpha = MathHelper.clamp((int) (best * 255f), 0, 255);
        if (alpha < 2) return;
        context.fill(0, 0, context.getScaledWindowWidth(), context.getScaledWindowHeight(), (alpha << 24) | 0xEAF3FF);
    }

    // ---- compositor refraction ----------------------------------------------------------------------

    @Override
    public void contributeWorldSceneVisuals(WorldSceneVisuals frame, long nowMillis) {
        if (!isEnabled() || !distortion.isEnabled() || impacts.isEmpty()) return;
        Impact im = impacts.get(impacts.size() - 1);
        float life = 0.9f + 0.4f * im.energy;
        if (im.age >= life) return;
        float strength = (0.55f + 0.35f * im.energy) * (float) Math.pow(1f - im.age / life, 1.8);
        Color tint = im.lightning ? new Color(190, 220, 255) : new Color(255, 230, 200);
        frame.setHitRipple(im.center.add(0, 0.1, 0), 0.3f * im.scale, strength, im.age, tint);
    }

    // ---- helpers ------------------------------------------------------------------------------------

    private float rand(double min, double max) {
        return (float) (min + random.nextDouble() * (max - min));
    }

    private static float smooth(float e0, float e1, float x) {
        float t = MathHelper.clamp((x - e0) / (e1 - e0), 0f, 1f);
        return t * t * (3f - 2f * t);
    }

    private static Color lighten(Color c, float k) {
        return new Color(
                MathHelper.clamp((int) (c.getRed() + (255 - c.getRed()) * k), 0, 255),
                MathHelper.clamp((int) (c.getGreen() + (255 - c.getGreen()) * k), 0, 255),
                MathHelper.clamp((int) (c.getBlue() + (255 - c.getBlue()) * k), 0, 255));
    }

    private static Color mixColor(Color a, Color b, float t) {
        t = MathHelper.clamp(t, 0f, 1f);
        return new Color(
                (int) (a.getRed() + (b.getRed() - a.getRed()) * t),
                (int) (a.getGreen() + (b.getGreen() - a.getGreen()) * t),
                (int) (a.getBlue() + (b.getBlue() - a.getBlue()) * t));
    }

    private Color jitter(Color c, int amount) {
        int d = random.nextInt(amount * 2 + 1) - amount;
        return new Color(MathHelper.clamp(c.getRed() + d, 0, 255), MathHelper.clamp(c.getGreen() + d, 0, 255), MathHelper.clamp(c.getBlue() + d, 0, 255));
    }

    /** Incandescent cooling from white-yellow through orange to dull red. */
    private static float[] blackbody(float t) {
        if (t < 0.25f) return mix3(1f, 0.97f, 0.86f, 1f, 0.78f, 0.36f, t / 0.25f);
        if (t < 0.65f) return mix3(1f, 0.78f, 0.36f, 1f, 0.38f, 0.08f, (t - 0.25f) / 0.4f);
        return mix3(1f, 0.38f, 0.08f, 0.55f, 0.08f, 0.02f, (t - 0.65f) / 0.35f);
    }

    private static float[] mix3(float r0, float g0, float b0, float r1, float g1, float b1, float k) {
        return new float[]{r0 + (r1 - r0) * k, g0 + (g1 - g0) * k, b0 + (b1 - b0) * k};
    }

    // ---- state types --------------------------------------------------------------------------------

    private static final int K_SMOKE = 0, K_SPARK = 1, K_GLOW = 2;

    private static final class Impact {
        float age, energy, scale, maxRadius;
        boolean lightning, hasGround, strikeFired;
        long seed;
        Vec3d center;
        Color surfaceColor;
        float[] hm;
        int hmRadial;
        final List<Crack> cracks = new ArrayList<>();
        float[] rayAngles, rayLengths;
        float waveAcc;

        float lifetime() {
            return 9.6f;
        }
    }

    private static final class Crack {
        float[] xs, zs, cum;
        float total, width, delay;
    }

    private static final class Chunk {
        double x, y, z, vx, vy, vz;
        float size, aspect, spin, age, restAge;
        boolean resting, big;
        Vector3f axis;
        final Quaternionf rotation = new Quaternionf().rotateXYZ((float) (Math.random() * 6.28), (float) (Math.random() * 6.28), (float) (Math.random() * 6.28));
        Color color;

        float life() {
            return big ? 7.5f : 4.6f;
        }
    }

    private static final class Particle {
        int kind, sprite;
        double x, y, z, vx, vy, vz, depth;
        float age, life = 1f, s0 = 0.1f, s1 = 0.3f, r = 1f, g = 1f, b = 1f, a = 1f;
        float drag, buoy, grav, turb, stretch, bounce, rot, rotVel, seed;
        boolean collide, cooling, lit;

        Particle() {
            rot = (float) (Math.random() * Math.PI * 2);
            rotVel = (float) ((Math.random() - 0.5) * 1.6);
            sprite = (int) (Math.random() * 4);
            seed = (float) (Math.random() * 100.0);
        }

        void set(Vec3d pos, Vec3d vel) {
            x = pos.x; y = pos.y; z = pos.z;
            vx = vel.x; vy = vel.y; vz = vel.z;
        }

        void setColor(Color c, float alpha) {
            r = c.getRed() / 255f; g = c.getGreen() / 255f; b = c.getBlue() / 255f; a = alpha;
        }
    }
}
