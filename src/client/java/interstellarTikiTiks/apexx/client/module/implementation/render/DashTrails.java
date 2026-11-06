package interstellarTikiTiks.apexx.client.module.implementation.render;

import interstellarTikiTiks.apexx.client.Apexx;
import interstellarTikiTiks.apexx.client.module.BooleanSetting;
import interstellarTikiTiks.apexx.client.module.Category;
import interstellarTikiTiks.apexx.client.module.ColorSetting;
import interstellarTikiTiks.apexx.client.module.ModeSetting;
import interstellarTikiTiks.apexx.client.module.Module;
import interstellarTikiTiks.apexx.client.module.SliderSetting;
import interstellarTikiTiks.apexx.client.theme.AccentState;
import interstellarTikiTiks.apexx.client.util.render.ProceduralTextures;
import interstellarTikiTiks.apexx.client.util.render.ProceduralTextures.Blend;
import interstellarTikiTiks.apexx.client.util.render.VisualGeometry;
import interstellarTikiTiks.apexx.client.util.render.types.TexturedRenderLayers;
import net.minecraft.client.render.Camera;
import net.minecraft.client.render.OverlayTexture;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.util.BufferAllocator;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.entity.projectile.ProjectileEntity;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.awt.Color;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Random;

public class DashTrails extends Module {

    public static DashTrails INSTANCE;

    private static final Identifier BLOOM = Identifier.of(Apexx.MOD_ID, "textures/particle/bloom.png");
    private static final Identifier SPARK = Identifier.of(Apexx.MOD_ID, "textures/particle/spark.png");

    private static final long FRAME_DURATION_MS = 45L;
    private static final int DASH_FRAME_COUNT = 21;
    private static final int MAX_SPRITES = 750;
    private static final double PROJECTILE_SCAN_RADIUS = 48.0d;

    private final SliderSetting lifetime = new SliderSetting("Lifetime", 0.9, 0.35, 2.5, 2)
            .setDescription("How many seconds each trail piece stays before fading out; longer leaves a longer streak.");
    private final SliderSetting density = new SliderSetting("Density", 12.0, 4.0, 20.0, 1)
            .setDescription("How many trail pieces are spawned along your path; higher is a denser, smoother trail.");
    private final SliderSetting scale = new SliderSetting("Scale", 1.0, 0.55, 1.8, 2)
            .setDescription("Size of the trail pieces.");
    private final ModeSetting trailStyle = new ModeSetting("Trail Style", "Particles",
            "Particles", "Plane", "Firefly Tail", "Particles + Plane")
            .setDescription("Look of the trail: Particles leaves glowing dash motes, Plane drags a smooth ribbon of light, Firefly Tail leaves softly pulsing orbs along your path.");
    private final SliderSetting lineHeight = new SliderSetting("Plane Height", 1.00, 0.35, 2.20, 2)
            .setDescription("Height of the Plane ribbon relative to your body.");
    private final BooleanSetting jumpBurst = new BooleanSetting("Jump Burst", true)
            .setDescription("Releases a ring of glowing particles each time you jump off the ground.");
    private final BooleanSetting projectileTrails = new BooleanSetting("Projectile Trails", true)
            .setDescription("Also leaves glowing trails behind your ender pearls, arrows and wind charges while they fly.");
    private final ModeSetting colorMode = new ModeSetting("Color", "Theme Sync",
            "Theme Sync", "Apexx Silver", "Neon Cyan", "Aurora", "Rainbow", "Custom")
            .setDescription("Colour scheme of the trail.");
    private final ColorSetting customColor = new ColorSetting("Custom Color", new Color(175, 215, 255))
            .setDescription("Trail colour when Color is Custom.");

    private final List<Sprite> sprites = new ArrayList<>();
    private final List<PlaneNode> planeNodes = new ArrayList<>();
    private final Random random = new Random();
    private Vec3d lastPosition;
    private Vec3d lastNodePos;
    private Vec3d headBinormal = new Vec3d(0.0d, 1.0d, 0.0d);
    private boolean wasOnGround = true;

    public DashTrails() {
        super("DashTrails", "Leaves an animated glowing trail behind you as you move, with jump bursts and optional trails on your projectiles. Several styles and colour schemes; visible only to you.", Category.RENDER);
        INSTANCE = this;
        addSetting(lifetime);
        addSetting(density);
        addSetting(scale);
        addSetting(trailStyle);
        addSetting(lineHeight);
        addSetting(jumpBurst);
        addSetting(projectileTrails);
        addSetting(colorMode);
        addSetting(customColor);
        trailStyle.onChange(this::clearAll);
    }

    @Override
    public void onDisable() {
        clearAll();
    }

    @Override
    protected void onRuntimeStateReleased() {
        clearAll();
    }

    private void clearAll() {
        sprites.clear();
        planeNodes.clear();
        lastPosition = null;
        lastNodePos = null;
        headBinormal = new Vec3d(0.0d, 1.0d, 0.0d);
        wasOnGround = true;
    }

    @Override
    public void onTick() {
        if (mc.player == null || mc.world == null) {
            clearAll();
            return;
        }

        Vec3d current = new Vec3d(mc.player.getX(), mc.player.getY(), mc.player.getZ());
        String style = trailStyle.getCurrentMode();
        boolean usesParticles = "Particles".equals(style) || "Particles + Plane".equals(style);
        boolean usesSpline = "Plane".equals(style) || "Firefly Tail".equals(style) || "Particles + Plane".equals(style);

        if (lastPosition != null) {
            if (usesSpline) {
                addPlaneNode(current);
            }
            if (usesParticles) {
                spawnBetween(lastPosition, current);
            }
        }
        lastPosition = current;

        // Jump burst trigger (ported from Combatant Trails)
        boolean nowOnGround = mc.player.isOnGround();
        if (jumpBurst.isEnabled() && wasOnGround && !nowOnGround && mc.player.getVelocity().y > 0.1d && !mc.player.isSpectator()) {
            spawnJumpBurst(current);
        }
        wasOnGround = nowOnGround;

        // Projectile trail trigger (ported from Combatant Trails)
        if (projectileTrails.isEnabled()) {
            spawnProjectileTrails();
        }

        Iterator<Sprite> it = sprites.iterator();
        while (it.hasNext()) {
            if (it.next().tick()) {
                it.remove();
            }
        }
        while (sprites.size() > MAX_SPRITES) {
            sprites.remove(0);
        }

        long now = System.nanoTime();
        long lifeNanos = (long) (lifetime.getValue() * 1.0e9d);
        planeNodes.removeIf(n -> now - n.bornNanos > lifeNanos);
    }

    @Override
    public void onWorldRender(MatrixStack matrices, float tickDelta) {
        if (mc.player == null || mc.world == null) {
            return;
        }

        Camera camera = mc.gameRenderer.getCamera();
        Vec3d cameraPos = camera.getCameraPos();
        float time = (System.nanoTime() / 1.0e9f) % 4000f;
        Color tintColor = resolveColor(0f, time);
        String style = trailStyle.getCurrentMode();

        if ("Plane".equals(style) || "Particles + Plane".equals(style)) {
            renderPlane(matrices, tickDelta, cameraPos, tintColor, time);
        } else if ("Firefly Tail".equals(style)) {
            renderFireflyTail(matrices, tickDelta, camera, cameraPos, time);
        }

        if (sprites.isEmpty()) {
            return;
        }

        Quaternionf camRot = camera.getRotation();

        Vec3d up = toVec3d(new Vector3f(0.0f, 1.0f, 0.0f).rotate(camRot)).normalize();
        Vec3d left = toVec3d(new Vector3f(-1.0f, 0.0f, 0.0f).rotate(camRot)).normalize();
        Vec3d forward = toVec3d(new Vector3f(0.0f, 0.0f, 1.0f).rotate(camRot)).normalize();

        Matrix4f matrix = matrices.peek().getPositionMatrix();
        long frame = System.currentTimeMillis() / FRAME_DURATION_MS;

        // Calculate active sprite frames to render
        List<SpriteFrame> frames = new ArrayList<>(sprites.size());
        for (int idx = 0; idx < sprites.size(); idx++) {
            Sprite sprite = sprites.get(idx);
            float progress = sprite.progress(tickDelta);
            float alpha = sprite.alpha(tickDelta);
            Vec3d center = sprite.previous.lerp(sprite.position, tickDelta).subtract(cameraPos);
            if (!sprite.ignoreNearFade) {
                alpha *= smoothstep(0.7f, 2.0f, (float) center.length());
            }
            int alphaInt = MathHelper.clamp((int) (255.0f * alpha), 0, 255);

            if (alphaInt > 1) {
                Color particleCol = resolveColor(progress + (idx % 11) * 0.06f, time);
                int red = particleCol.getRed();
                int green = particleCol.getGreen();
                int blue = particleCol.getBlue();

                float rotation = ((float) Math.toDegrees(Math.atan2(sprite.facing.dotProduct(up), sprite.facing.dotProduct(left)))) + sprite.roll;
                double radians = Math.toRadians(rotation);
                double cos = Math.cos(radians);
                double sin = Math.sin(radians);

                Vec3d axisX = left.multiply(cos).add(up.multiply(-sin));
                Vec3d axisY = left.multiply(sin).add(up.multiply(cos));

                double width = sprite.width * (1.0f - (progress * 0.18f));
                double height = sprite.height * (1.08f - (progress * 0.15f));
                int frameIndex = (int) (((frame + sprite.frameOffset) + (progress * 6.0f)) % 21.0f);

                int core = color(brighten(red, 0.30f), brighten(green, 0.30f), brighten(blue, 0.30f), (int) (alphaInt * 0.95f));
                int glow = color(red, green, blue, (int) (alphaInt * 0.55f));
                int dark = color((int) (red * 0.45f), (int) (green * 0.45f), (int) (blue * 0.45f), (int) (alphaInt * 0.7f));
                int main = color(red, green, blue, alphaInt);

                frames.add(new SpriteFrame(center, axisX, axisY, width, height, progress, frameIndex, glow, dark, main, core));
            }
        }

        if (frames.isEmpty()) {
            return;
        }

        try (BufferAllocator allocator = interstellarTikiTiks.apexx.client.util.render.PooledBufferAllocator.acquire(16384)) {
            VertexConsumerProvider.Immediate immediate = VertexConsumerProvider.immediate(allocator);

            // Layer 1: Outer soft bloom
            VertexConsumer bloomConsumer = immediate.getBuffer(TexturedRenderLayers.getTexturedLayer(BLOOM, true));
            for (SpriteFrame f : frames) {
                double bloomW = f.width * (1.85d - (f.progress * 0.2d));
                double bloomH = f.width * (1.85d - (f.progress * 0.2d));
                billboard(bloomConsumer, matrix, f.center, f.axisX, f.axisY, forward, 0.0d, bloomW, bloomH, f.glow, f.glow, f.glow, f.glow);
            }

            // Layer 2: Main spark sprite + inner high-intensity core
            VertexConsumer sparkConsumer = immediate.getBuffer(TexturedRenderLayers.getTexturedLayer(SPARK, true));
            for (SpriteFrame f : frames) {
                billboard(sparkConsumer, matrix, f.center, f.axisX, f.axisY, forward, 0.002d + (f.frameIndex * 1.0E-6d), f.width, f.height, f.dark, f.main, f.main, f.dark);
                billboard(sparkConsumer, matrix, f.center, f.axisX, f.axisY, forward, 0.004d + (f.frameIndex * 1.0E-6d), f.width * 0.78d, f.height * 0.78d, f.core, f.core, f.core, f.core);
            }

            immediate.draw();
        }
    }

    private Color resolveColor(float phaseOffset, float time) {
        return switch (colorMode.getCurrentMode()) {
            case "Apexx Silver" -> new Color(225, 230, 240);
            case "Neon Cyan" -> new Color(50, 230, 255);
            case "Aurora" -> {
                float w1 = 0.5f + 0.5f * MathHelper.sin(time * 2.0f + phaseOffset * 4.5f);
                float w2 = 0.5f + 0.5f * MathHelper.cos(time * 1.3f - phaseOffset * 3.2f);
                int r = (int) MathHelper.lerp(w2 * 0.65f, MathHelper.lerp(w1, 45f, 55f), 185f);
                int g = (int) MathHelper.lerp(w2 * 0.65f, MathHelper.lerp(w1, 255f, 185f), 95f);
                int b = (int) MathHelper.lerp(w2 * 0.65f, MathHelper.lerp(w1, 190f, 255f), 255f);
                yield new Color(MathHelper.clamp(r, 0, 255), MathHelper.clamp(g, 0, 255), MathHelper.clamp(b, 0, 255));
            }
            case "Rainbow" -> {
                float hue = (time * 0.22f + phaseOffset * 0.35f) % 1.0f;
                if (hue < 0f) hue += 1.0f;
                yield Color.getHSBColor(hue, 0.76f, 1.0f);
            }
            case "Custom" -> customColor.getValue();
            default -> AccentState.accentColor();
        };
    }

    /**
     * Combatant-inspired Firefly Tail: samples breathing glowing orbs along the Catmull-Rom path
     * with smooth head-to-tail envelope and bright hot core motes.
     */
    private void renderFireflyTail(MatrixStack matrices, float tickDelta, Camera camera, Vec3d cameraPos, float time) {
        if (mc.player == null || !ProceduralTextures.ensure()) {
            return;
        }
        long now = System.nanoTime();
        long lifeNanos = Math.max(1L, (long) (lifetime.getValue() * 1.0e9d));

        Vec3d head = new Vec3d(
                MathHelper.lerp(tickDelta, mc.player.lastRenderX, mc.player.getX()),
                MathHelper.lerp(tickDelta, mc.player.lastRenderY, mc.player.getY()),
                MathHelper.lerp(tickDelta, mc.player.lastRenderZ, mc.player.getZ()));

        int n = planeNodes.size();
        if (n < 1) {
            return;
        }
        Vec3d[] ctrl = new Vec3d[n + 1];
        long[] born = new long[n + 1];
        for (int i = 0; i < n; i++) {
            ctrl[i] = planeNodes.get(i).pos;
            born[i] = planeNodes.get(i).bornNanos;
        }
        ctrl[n] = head;
        born[n] = now;
        if (ctrl[n - 1].squaredDistanceTo(head) < 1.0E-4d) {
            return;
        }

        final int sub = 4;
        int total = n * sub + 1;
        Quaternionf camRot = camera.getRotation();
        Vector3f right = new Vector3f(1f, 0f, 0f).rotate(camRot);
        Vector3f up = new Vector3f(0f, 1f, 0f).rotate(camRot);
        Matrix4f matrix = matrices.peek().getPositionMatrix();

        boolean firstPerson = mc.options.getPerspective().isFirstPerson();
        float nearStart = firstPerson ? 1.6f : 0.6f;
        float nearEnd = firstPerson ? 3.2f : 1.5f;
        double bodyOffset = (mc.player.getHeight() - (mc.player.isSneaking() ? 0.15f : 0.0f)) * 0.48d;
        float baseScale = (float) scale.getValue() * 0.28f;

        try (BufferAllocator allocator = interstellarTikiTiks.apexx.client.util.render.PooledBufferAllocator.acquire(1 << 16)) {
            VertexConsumerProvider.Immediate immediate = VertexConsumerProvider.immediate(allocator);
            VertexConsumer glow = immediate.getBuffer(ProceduralTextures.layer(ProceduralTextures.GLOW, Blend.ADDITIVE, true));

            for (int seg = 0; seg < n; seg++) {
                Vec3d p0 = ctrl[Math.max(0, seg - 1)], p1 = ctrl[seg], p2 = ctrl[seg + 1], p3 = ctrl[Math.min(n, seg + 2)];
                for (int s = 0; s < sub; s++) {
                    float t = s / (float) sub;
                    int idx = seg * sub + s;
                    float u = idx / (float) (total - 1);
                    Vec3d pt = catmullRom(p0, p1, p2, p3, t);
                    float a = MathHelper.lerp(t, (now - born[seg]) / (float) lifeNanos, (now - born[seg + 1]) / (float) lifeNanos);
                    float age = MathHelper.clamp(a, 0f, 1f);

                    Vec3d rel = pt.add(0.0d, bodyOffset, 0.0d).subtract(cameraPos);
                    float distFade = smoothstep(nearStart, nearEnd, (float) rel.length());
                    float envelope = (1.0f - age * age) * smoothstep(0.0f, 0.18f, u) * distFade;
                    if (envelope <= 0.01f) continue;

                    float breathing = 0.90f + 0.10f * MathHelper.sin(u * 21.0f - time * 4.2f);
                    float size = baseScale * breathing * (0.55f + 0.45f * (1.0f - age));
                    Color col = resolveColor(1.0f - u, time);

                    VisualGeometry.sprite(glow, matrix, rel.x, rel.y, rel.z,
                            right.x, right.y, right.z, up.x, up.y, up.z,
                            size, size, 0f, 0f, 0f, 1f, 1f,
                            VisualGeometry.color(col, envelope * 0.72f), VisualGeometry.FULL_BRIGHT);
                    VisualGeometry.sprite(glow, matrix, rel.x, rel.y, rel.z,
                            right.x, right.y, right.z, up.x, up.y, up.z,
                            size * 0.42f, size * 0.42f, 0f, 0f, 0f, 1f, 1f,
                            VisualGeometry.color(Color.WHITE, envelope * 0.88f), VisualGeometry.FULL_BRIGHT);
                }
            }
            immediate.draw();
        }
    }

    private void renderPlane(MatrixStack matrices, float tickDelta, Vec3d cameraPos, Color tint, float time) {
        if (mc.player == null || !ProceduralTextures.ensure()) {
            return;
        }
        long now = System.nanoTime();
        long lifeNanos = Math.max(1L, (long) (lifetime.getValue() * 1.0e9d));

        Vec3d head = new Vec3d(
                MathHelper.lerp(tickDelta, mc.player.lastRenderX, mc.player.getX()),
                MathHelper.lerp(tickDelta, mc.player.lastRenderY, mc.player.getY()),
                MathHelper.lerp(tickDelta, mc.player.lastRenderZ, mc.player.getZ()));

        int n = planeNodes.size();
        if (n < 1) {
            return;
        }
        Vec3d[] ctrl = new Vec3d[n + 1];
        long[] born = new long[n + 1];
        for (int i = 0; i < n; i++) {
            ctrl[i] = planeNodes.get(i).pos;
            born[i] = planeNodes.get(i).bornNanos;
        }
        ctrl[n] = head;
        born[n] = now;
        if (ctrl[n - 1].squaredDistanceTo(head) < 1.0E-4d) {
            return;
        }

        final int sub = 4;
        int total = n * sub + 1;
        Vec3d[] pts = new Vec3d[total];
        float[] age = new float[total];
        for (int seg = 0; seg < n; seg++) {
            Vec3d p0 = ctrl[Math.max(0, seg - 1)], p1 = ctrl[seg], p2 = ctrl[seg + 1], p3 = ctrl[Math.min(n, seg + 2)];
            for (int s = 0; s < sub; s++) {
                float t = s / (float) sub;
                int idx = seg * sub + s;
                pts[idx] = catmullRom(p0, p1, p2, p3, t);
                float a = MathHelper.lerp(t, (now - born[seg]) / (float) lifeNanos, (now - born[seg + 1]) / (float) lifeNanos);
                age[idx] = MathHelper.clamp(a, 0f, 1f);
            }
        }
        pts[total - 1] = head;
        age[total - 1] = 0f;

        Vec3d[] tangent = new Vec3d[total];
        for (int i = 0; i < total; i++) {
            Vec3d d = pts[Math.min(total - 1, i + 1)].subtract(pts[Math.max(0, i - 1)]);
            tangent[i] = safeNormalize(d);
            if (tangent[i] == Vec3d.ZERO) {
                tangent[i] = i > 0 ? tangent[i - 1] : new Vec3d(0.0d, 0.0d, 1.0d);
            }
        }
        Vec3d desired = new Vec3d(0.0d, 1.0d, 0.0d).subtract(tangent[total - 1].multiply(tangent[total - 1].y));
        if (desired.lengthSquared() > 0.12d) {
            headBinormal = safeNormalize(headBinormal.multiply(0.82d).add(safeNormalize(desired).multiply(0.18d)));
        }
        Vec3d current = headBinormal.subtract(tangent[total - 1].multiply(headBinormal.dotProduct(tangent[total - 1])));
        current = safeNormalize(current) == Vec3d.ZERO ? new Vec3d(0.0d, 1.0d, 0.0d) : safeNormalize(current);
        Vec3d[] binormal = new Vec3d[total];
        binormal[total - 1] = current;
        for (int i = total - 2; i >= 0; i--) {
            Vec3d b = binormal[i + 1].subtract(tangent[i].multiply(binormal[i + 1].dotProduct(tangent[i])));
            Vec3d nb = safeNormalize(b);
            binormal[i] = nb == Vec3d.ZERO ? binormal[i + 1] : nb;
        }

        boolean firstPerson = mc.options.getPerspective().isFirstPerson();
        float nearStart = firstPerson ? 2.3f : 1.2f;
        float nearEnd = firstPerson ? 4.6f : 2.8f;
        float fullHeight = (float) ((mc.player.getHeight() - (mc.player.isSneaking() ? 0.15f : 0.0f)) * lineHeight.getValue() * scale.getValue());
        double center = fullHeight * 0.5d * 0.9d;

        Matrix4f matrix = matrices.peek().getPositionMatrix();

        try (BufferAllocator allocator = interstellarTikiTiks.apexx.client.util.render.PooledBufferAllocator.acquire(1 << 17)) {
            VertexConsumerProvider.Immediate immediate = VertexConsumerProvider.immediate(allocator);
            VertexConsumer sheet = immediate.getBuffer(ProceduralTextures.layer(ProceduralTextures.PLANE, Blend.ADDITIVE, true));

            double[][] top = new double[total][3];
            double[][] bottom = new double[total][3];
            float[] vis = new float[total];
            for (int i = 0; i < total; i++) {
                Vec3d c = pts[i].add(0.0d, center, 0.0d).subtract(cameraPos);
                float ageFade = 1.0f - age[i] * age[i];
                float headFade = smoothstep(0.0f, 0.5f, (float) pts[i].distanceTo(head));
                float distFade = smoothstep(nearStart, nearEnd, (float) c.length());
                vis[i] = MathHelper.clamp(ageFade * headFade * distFade, 0.0f, 1.0f);
                double half = fullHeight * 0.5d * (1.0d - 0.38d * age[i] * age[i]);
                Vec3d t = c.add(binormal[i].multiply(half));
                Vec3d b = c.subtract(binormal[i].multiply(half));
                top[i][0] = t.x; top[i][1] = t.y; top[i][2] = t.z;
                bottom[i][0] = b.x; bottom[i][1] = b.y; bottom[i][2] = b.z;
            }
            for (int i = 0; i < total - 1; i++) {
                float a0 = vis[i], a1 = vis[i + 1];
                if (a0 < 0.004f && a1 < 0.004f) continue;
                Color col0 = resolveColor(age[i], time);
                Color col1 = resolveColor(age[i + 1], time);
                int rgb0 = col0.getRGB() & 0x00FFFFFF;
                int rgb1 = col1.getRGB() & 0x00FFFFFF;
                int rim0 = brightenRgb(rgb0, 0.55f);
                int rim1 = brightenRgb(rgb1, 0.55f);
                int c0 = withAlpha(rim0, a0 * 0.95f);
                int c1 = withAlpha(rim1, a1 * 0.95f);
                int d0 = withAlpha(rgb0, a0 * 0.95f);
                int d1 = withAlpha(rgb1, a1 * 0.95f);
                VisualGeometryBridge.quad(sheet, matrix, top[i], top[i + 1], bottom[i + 1], bottom[i], c0, c1, d1, d0);
            }
            immediate.draw();
        }
    }

    private void spawnJumpBurst(Vec3d pos) {
        int maxAge = Math.max(10, (int) (lifetime.getValue() * 22.0f));
        int count = 14;
        float s = (float) scale.getValue();
        for (int i = 0; i < count; i++) {
            double angle = (Math.PI * 2.0 * i / count) + (random.nextDouble() - 0.5d) * 0.25d;
            double speed = 0.055d + random.nextDouble() * 0.045d;
            double vx = Math.cos(angle) * speed;
            double vz = Math.sin(angle) * speed;
            double vy = 0.015d + random.nextDouble() * 0.035d;

            Vec3d spawnPos = pos.add(Math.cos(angle) * 0.24d, 0.08d + random.nextDouble() * 0.12d, Math.sin(angle) * 0.24d);
            Vec3d vel = new Vec3d(vx, vy, vz);
            Vec3d facing = safeNormalize(vel);
            if (facing == Vec3d.ZERO) facing = new Vec3d(Math.cos(angle), 0.2d, Math.sin(angle)).normalize();

            float w = s * (0.36f + random.nextFloat() * 0.16f);
            float h = w * (0.28f + random.nextFloat() * 0.10f);
            sprites.add(new Sprite(spawnPos, vel, facing, w, h,
                    (float) ((random.nextDouble() - 0.5d) * 24.0d), random.nextInt(DASH_FRAME_COUNT), maxAge, false));
        }
    }

    private void spawnProjectileTrails() {
        if (mc.player == null || mc.world == null) return;
        Box searchBox = mc.player.getBoundingBox().expand(PROJECTILE_SCAN_RADIUS);
        List<ProjectileEntity> projectiles = mc.world.getEntitiesByClass(
                ProjectileEntity.class,
                searchBox,
                p -> p != null && p.isAlive() && !p.isOnGround() && p.getVelocity().lengthSquared() > 0.012d
        );
        if (projectiles.isEmpty()) return;

        int maxAge = Math.max(8, (int) (lifetime.getValue() * 18.0f));
        float s = (float) scale.getValue();
        int spawnedTotal = 0;

        for (ProjectileEntity proj : projectiles) {
            if (spawnedTotal >= 48) break;
            Vec3d cur = new Vec3d(proj.getX(), proj.getY() + proj.getHeight() * 0.5d, proj.getZ());
            Vec3d prev = new Vec3d(proj.lastRenderX, proj.lastRenderY + proj.getHeight() * 0.5d, proj.lastRenderZ);
            Vec3d delta = cur.subtract(prev);
            Vec3d back = safeNormalize(proj.getVelocity().multiply(-1.0d));
            if (back == Vec3d.ZERO) back = new Vec3d(0.0d, 1.0d, 0.0d);

            int steps = 3;
            for (int i = 0; i < steps; i++) {
                double t = (i + 1) / (double) steps;
                Vec3d base = prev.add(delta.multiply(t));
                Vec3d offset = new Vec3d(
                        (random.nextDouble() - 0.5d) * 0.18d,
                        (random.nextDouble() - 0.5d) * 0.18d,
                        (random.nextDouble() - 0.5d) * 0.18d
                );
                Vec3d vel = back.multiply(0.015d + random.nextDouble() * 0.015d)
                        .add((random.nextDouble() - 0.5d) * 0.01d, (random.nextDouble() - 0.5d) * 0.01d, (random.nextDouble() - 0.5d) * 0.01d);
                float w = s * (0.28f + random.nextFloat() * 0.14f);
                float h = w * 0.32f;
                sprites.add(new Sprite(base.add(offset), vel, back, w, h,
                        (float) ((random.nextDouble() - 0.5d) * 20.0d), random.nextInt(DASH_FRAME_COUNT), maxAge, true));
                spawnedTotal++;
            }
        }
    }

    private static Vec3d catmullRom(Vec3d p0, Vec3d p1, Vec3d p2, Vec3d p3, float t) {
        double t2 = t * t, t3 = t2 * t;
        return new Vec3d(
                0.5d * ((2.0d * p1.x) + (-p0.x + p2.x) * t + (2.0d * p0.x - 5.0d * p1.x + 4.0d * p2.x - p3.x) * t2 + (-p0.x + 3.0d * p1.x - 3.0d * p2.x + p3.x) * t3),
                0.5d * ((2.0d * p1.y) + (-p0.y + p2.y) * t + (2.0d * p0.y - 5.0d * p1.y + 4.0d * p2.y - p3.y) * t2 + (-p0.y + 3.0d * p1.y - 3.0d * p2.y + p3.y) * t3),
                0.5d * ((2.0d * p1.z) + (-p0.z + p2.z) * t + (2.0d * p0.z - 5.0d * p1.z + 4.0d * p2.z - p3.z) * t2 + (-p0.z + 3.0d * p1.z - 3.0d * p2.z + p3.z) * t3));
    }

    private static float smoothstep(float e0, float e1, float x) {
        float t = MathHelper.clamp((x - e0) / (e1 - e0), 0.0f, 1.0f);
        return t * t * (3.0f - 2.0f * t);
    }

    private static int withAlpha(int rgb, float alpha) {
        return (MathHelper.clamp((int) (alpha * 255.0f), 0, 255) << 24) | (rgb & 0x00FFFFFF);
    }

    private static int brightenRgb(int rgb, float amount) {
        int r = brighten((rgb >> 16) & 255, amount);
        int g = brighten((rgb >> 8) & 255, amount);
        int b = brighten(rgb & 255, amount);
        return (r << 16) | (g << 8) | b;
    }

    private void addPlaneNode(Vec3d current) {
        if (mc.player == null || mc.player.isSpectator()) {
            return;
        }
        if (lastNodePos != null && current.squaredDistanceTo(lastNodePos) > 64.0d) {
            planeNodes.clear();
            lastNodePos = null;
        }
        double spacing = MathHelper.clamp(1.4d / density.getValue(), 0.07d, 0.4d);
        if (lastNodePos == null || current.squaredDistanceTo(lastNodePos) >= spacing * spacing) {
            planeNodes.add(new PlaneNode(current, System.nanoTime()));
            lastNodePos = current;
            while (planeNodes.size() > 400) {
                planeNodes.remove(0);
            }
        }
    }

    private static Vec3d safeNormalize(Vec3d v) {
        double len = v.length();
        return len < 1.0E-5d ? Vec3d.ZERO : v.multiply(1.0d / len);
    }

    private static final class VisualGeometryBridge {
        static void quad(VertexConsumer buf, Matrix4f m, double[] a, double[] b, double[] c, double[] d,
                         int ca, int cb, int cc, int cd) {
            interstellarTikiTiks.apexx.client.util.render.VisualGeometry.quad(buf, m,
                    a[0], a[1], a[2], b[0], b[1], b[2], c[0], c[1], c[2], d[0], d[1], d[2],
                    0f, 0f, 1f, 1f, ca, cb, cc, cd, interstellarTikiTiks.apexx.client.util.render.VisualGeometry.FULL_BRIGHT);
        }
    }

    private static void billboard(VertexConsumer buffer, Matrix4f matrix, Vec3d center, Vec3d axisX, Vec3d axisY, Vec3d forward, double bias, double width, double height, int c0, int c1, int c2, int c3) {
        double halfW = width * 0.5d;
        double halfH = height * 0.5d;
        Vec3d base = center.add(forward.multiply(bias));
        quad(buffer, matrix,
                base.add(axisX.multiply(-halfW)).add(axisY.multiply(halfH)),
                base.add(axisX.multiply(halfW)).add(axisY.multiply(halfH)),
                base.add(axisX.multiply(halfW)).add(axisY.multiply(-halfH)),
                base.add(axisX.multiply(-halfW)).add(axisY.multiply(-halfH)),
                c0, c1, c2, c3);
    }

    private static void quad(VertexConsumer buffer, Matrix4f matrix, Vec3d a, Vec3d b, Vec3d c, Vec3d d, int c0, int c1, int c2, int c3) {
        quad(buffer, matrix, a, b, c, d, 0.0f, 0.0f, 1.0f, 1.0f, c0, c1, c2, c3);
    }

    private static void quad(VertexConsumer buffer, Matrix4f matrix, Vec3d a, Vec3d b, Vec3d c, Vec3d d,
                             float u0, float v0, float u1, float v1,
                             int c0, int c1, int c2, int c3) {
        vertex(buffer, matrix, a, u0, v0, c0);
        vertex(buffer, matrix, b, u1, v0, c1);
        vertex(buffer, matrix, c, u1, v1, c2);

        vertex(buffer, matrix, a, u0, v0, c0);
        vertex(buffer, matrix, c, u1, v1, c2);
        vertex(buffer, matrix, d, u0, v1, c3);
    }

    private static void vertex(VertexConsumer buffer, Matrix4f matrix, Vec3d pos, float u, float v, int color) {
        float r = ((color >> 16) & 0xFF) / 255.0f;
        float g = ((color >> 8) & 0xFF) / 255.0f;
        float b = (color & 0xFF) / 255.0f;
        float a = ((color >> 24) & 0xFF) / 255.0f;
        buffer.vertex(matrix, (float) pos.x, (float) pos.y, (float) pos.z)
                .color(r, g, b, a)
                .texture(u, v)
                .overlay(OverlayTexture.DEFAULT_UV)
                .light(0xF000F0)
                .normal(0.0f, 1.0f, 0.0f);
    }

    private static Vec3d toVec3d(Vector3f vector) {
        return new Vec3d(vector.x(), vector.y(), vector.z());
    }

    private static int color(int red, int green, int blue, int alpha) {
        return (MathHelper.clamp(alpha, 0, 255) << 24)
                | (MathHelper.clamp(red, 0, 255) << 16)
                | (MathHelper.clamp(green, 0, 255) << 8)
                | MathHelper.clamp(blue, 0, 255);
    }

    private static int brighten(int channel, float amount) {
        return MathHelper.clamp((int) (channel + ((255 - channel) * amount)), 0, 255);
    }

    private void spawnBetween(Vec3d from, Vec3d to) {
        if (from.squaredDistanceTo(to) > 64.0d) return; // teleport, not movement
        Vec3d delta = to.subtract(from);
        double distanceMoved = delta.length();
        if (distanceMoved < 0.02d || mc.player == null || mc.player.isSpectator()) {
            return;
        }

        Vec3d direction = delta.multiply(1.0d / distanceMoved);
        Vec3d back = direction.multiply(-1.0d);
        Vec3d side = new Vec3d(-direction.z, 0.0d, direction.x);
        Vec3d side2 = side.lengthSquared() < 1.0E-4d ? new Vec3d(1.0d, 0.0d, 0.0d) : side.normalize();

        float playerHeight = mc.player.getHeight() - (mc.player.isSneaking() ? 0.15f : 0.0f);
        int bursts = MathHelper.clamp((int) Math.ceil(distanceMoved * density.getValue() * 1.35f), 1, 5);
        int maxAge = Math.max(8, (int) (lifetime.getValue() * 20.0f));
        float distanceFactor = MathHelper.clamp((float) (distanceMoved * 6.0d), 0.0f, 1.3f);

        for (int burst = 0; burst < bursts; burst++) {
            double t = bursts == 1 ? 1.0d : (double) burst / (bursts - 1);
            Vec3d base = from.add(delta.multiply(t)).add(0.0d, playerHeight * 0.18d, 0.0d);
            int count = 3 + random.nextInt(2) + (distanceFactor > 0.75f ? 1 : 0);

            for (int i = 0; i < count; i++) {
                double vertical = (random.nextDouble() - 0.1d) * playerHeight * 0.85d;
                double sideways = (random.nextDouble() - 0.5d) * (0.28d + (distanceFactor * 0.15d));
                double backward = random.nextDouble() * (0.2d + (distanceFactor * 0.18d));

                Vec3d position = base.add(0.0d, vertical, 0.0d)
                        .add(side2.multiply(sideways))
                        .add(back.multiply(backward));

                Vec3d facing = back.add(side2.multiply((random.nextDouble() - 0.5d) * 0.35d))
                        .add(0.0d, (random.nextDouble() - 0.5d) * 0.18d, 0.0d);
                Vec3d facing2 = facing.lengthSquared() < 1.0E-4d ? back : facing.normalize();

                Vec3d velocity = back.multiply(0.012d + (distanceFactor * 0.02d) + (random.nextDouble() * 0.01d))
                        .add(side2.multiply((random.nextDouble() - 0.5d) * 0.008d))
                        .add(0.0d, ((random.nextDouble() - 0.5d) * 0.008d) + 0.002d, 0.0d);

                float particleWidth = (float) scale.getValue() * (0.34f + (random.nextFloat() * 0.18f) + (distanceFactor * 0.08f));
                float particleHeight = particleWidth * (0.24f + (random.nextFloat() * 0.08f));

                sprites.add(new Sprite(position, velocity, facing2, particleWidth, particleHeight,
                        (float) ((random.nextDouble() - 0.5d) * 18.0d), random.nextInt(DASH_FRAME_COUNT), maxAge, false));
            }
        }
    }

    private record SpriteFrame(Vec3d center, Vec3d axisX, Vec3d axisY, double width, double height,
                               float progress, int frameIndex, int glow, int dark, int main, int core) {}

    private record PlaneNode(Vec3d pos, long bornNanos) {}

    private static final class Sprite {
        private Vec3d previous;
        private Vec3d position;
        private Vec3d velocity;
        private final Vec3d facing;
        private final float width;
        private final float height;
        private final float roll;
        private final int frameOffset;
        private final int maxAge;
        private final boolean ignoreNearFade;
        private int age;

        private Sprite(Vec3d position, Vec3d velocity, Vec3d facing, float width, float height, float roll, int frameOffset, int maxAge, boolean ignoreNearFade) {
            this.position = position;
            this.previous = position;
            this.velocity = velocity;
            this.facing = facing;
            this.width = width;
            this.height = height;
            this.roll = roll;
            this.frameOffset = frameOffset;
            this.maxAge = maxAge;
            this.ignoreNearFade = ignoreNearFade;
        }

        private boolean tick() {
            this.previous = this.position;
            this.position = this.position.add(this.velocity);
            this.velocity = this.velocity.multiply(0.9d).add(0.0d, 0.0015d, 0.0d);
            return ++this.age >= this.maxAge;
        }

        private float progress(float delta) {
            return MathHelper.clamp((this.age + delta) / this.maxAge, 0.0f, 1.0f);
        }

        private float alpha(float delta) {
            float fade = 1.0f - progress(delta);
            return MathHelper.clamp((float) Math.pow(Math.sin(fade * Math.PI * 0.5d), 0.85d), 0.0f, 1.0f);
        }
    }
}
