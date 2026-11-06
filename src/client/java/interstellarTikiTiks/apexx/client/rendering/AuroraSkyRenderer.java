package interstellarTikiTiks.apexx.client.rendering;

import interstellarTikiTiks.apexx.client.module.hooks.AmbienceSkyProvider;
import interstellarTikiTiks.apexx.client.theme.AccentState;
import interstellarTikiTiks.apexx.client.util.render.types.RenderTypes;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.util.BufferAllocator;
import net.minecraft.client.util.math.MatrixStack;
import org.joml.Matrix4f;

import java.awt.Color;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Procedural celestial skybox: multi-layer starfield (dust, small, medium, large with
 * harmonic twinkle scintillation), cosmic nebula cloud veil, and flowing multi-harmonic
 * aurora curtains.
 */
public final class AuroraSkyRenderer {
    private static final int SEGMENTS = 72;
    private static final float DOME_RADIUS = 96.0f;
    private static final float STAR_RADIUS = 98.0f;
    private static final int TOTAL_STARS = 1200;

    private static final List<StarData> STARS = generateStars();

    private AuroraSkyRenderer() {
    }

    private static List<StarData> generateStars() {
        Random random = new Random(424242L);
        List<StarData> list = new ArrayList<>(TOTAL_STARS);
        for (int i = 0; i < TOTAL_STARS; i++) {
            // Uniform point distribution across upper hemisphere
            float u = random.nextFloat();
            float v = random.nextFloat();
            float theta = u * 2.0f * (float) Math.PI;
            // Elevation: biased towards higher sky dome (y > 0)
            float phi = (float) Math.asin(0.08f + 0.92f * v);

            float x = (float) (Math.cos(phi) * Math.cos(theta)) * STAR_RADIUS;
            float y = (float) Math.sin(phi) * STAR_RADIUS;
            float z = (float) (Math.cos(phi) * Math.sin(theta)) * STAR_RADIUS;

            // Classify star type
            int type;
            float roll = random.nextFloat();
            if (roll < 0.55f) type = 0; // Dust
            else if (roll < 0.82f) type = 1; // Small
            else if (roll < 0.95f) type = 2; // Medium
            else type = 3; // Large

            // Spectral color temperature
            int color;
            float tintRoll = random.nextFloat();
            if (tintRoll < 0.60f) color = 0xFFFFFF; // Pure white
            else if (tintRoll < 0.80f) color = 0xB8E0FF; // Ice blue
            else if (tintRoll < 0.92f) color = 0xFFE4B8; // Warm amber
            else color = 0xFFB8DA; // Rose quartz

            float twinkleFreq = 1.0f + random.nextFloat() * 3.5f;
            float twinklePhase = random.nextFloat() * (float) Math.PI * 2.0f;
            float baseSize = type == 0 ? 0.35f : (type == 1 ? 0.65f : (type == 2 ? 1.05f : 1.60f));

            list.add(new StarData(x, y, z, type, color, twinkleFreq, twinklePhase, baseSize));
        }
        return list;
    }

    public static void render(MatrixStack matrices, AmbienceSkyProvider ambience) {
        if (ambience == null || (!ambience.isAuroraSkyEnabled() && !ambience.isShaderSkyboxEnabled())) return;

        float time = (System.nanoTime() / 1_000_000_000.0f);
        float auroraSpeed = ambience.getAuroraSpeed();
        float opacity = ambience.getAuroraOpacity();
        Palette palette = resolvePalette(ambience);

        try (BufferAllocator allocator = interstellarTikiTiks.apexx.client.util.render.PooledBufferAllocator.acquire(65_536)) {
            VertexConsumerProvider.Immediate immediate = VertexConsumerProvider.immediate(allocator);
            VertexConsumer buffer = immediate.getBuffer(RenderTypes.TRIANGLES_NO_DEPTH);
            Matrix4f matrix = matrices.peek().getPositionMatrix();

            // 1. Procedural Starfield with Twinkle
            if (ambience.isShaderSkyboxEnabled()) {
                drawStarfield(buffer, matrix, time, ambience);
                drawNebula(buffer, matrix, time, ambience, palette);
            }

            // 2. Flowing Multi-Harmonic Aurora Curtains
            if (ambience.isAuroraSkyEnabled()) {
                float aTime = time * auroraSpeed;
                drawCurtain(buffer, matrix, aTime, 0.0f, 0.96f, 0.30f, opacity * 0.42f, palette);
                drawCurtain(buffer, matrix, aTime, 1.87f, 0.73f, 0.46f, opacity * 0.64f, palette);
                drawCurtain(buffer, matrix, aTime, 4.21f, 0.54f, 0.59f, opacity * 0.88f, palette);
                if (ambience.isShaderSkyboxEnabled()) {
                    drawCurtain(buffer, matrix, aTime, 6.12f, 0.38f, 0.68f, opacity * 0.52f, palette);
                }
            }

            immediate.draw(RenderTypes.TRIANGLES_NO_DEPTH);
        }
    }

    private static Palette resolvePalette(AmbienceSkyProvider ambience) {
        if (ambience.isThemeSyncSky()) {
            Color accent = AccentState.accentColor();
            int bright = (accent.getRed() << 16) | (accent.getGreen() << 8) | accent.getBlue();
            int deep = ((accent.getRed() / 3) << 16) | ((accent.getGreen() / 3) << 8) | (accent.getBlue() / 2);
            return new Palette(bright, deep);
        }
        return Palette.forName(ambience.getAuroraPalette());
    }

    private static void drawStarfield(VertexConsumer buffer, Matrix4f matrix, float time, AmbienceSkyProvider ambience) {
        float density = ambience.getStarDensity();
        float brightness = ambience.getStarBrightness();
        float twinkleSpeed = ambience.getTwinkleSpeed();
        int maxStars = Math.min(STARS.size(), Math.round(STARS.size() * Math.min(density, 1.5f)));

        for (int i = 0; i < maxStars; i++) {
            StarData star = STARS.get(i);
            float twinkle = 0.65f + 0.35f * (float) Math.sin(time * star.twinkleFreq * twinkleSpeed + star.twinklePhase);
            float alpha = star.type == 0 ? 0.35f : (star.type == 1 ? 0.68f : 0.95f);
            alpha = Math.max(0.0f, Math.min(1.0f, alpha * twinkle * brightness));
            if (alpha < 0.02f) continue;

            float sz = star.baseSize * (0.85f + 0.15f * twinkle);
            drawStarQuad(buffer, matrix, star.x, star.y, star.z, sz, star.color, alpha);
        }
    }

    private static void drawStarQuad(VertexConsumer buffer, Matrix4f matrix, float x, float y, float z,
                                     float size, int color, float alpha) {
        // Billboard quad facing celestial center: tangent basis perpendicular to the radial direction.
        float half = size * 0.5f;
        float len = (float) Math.sqrt(x * x + y * y + z * z);
        if (len < 1e-4f) return;
        float nx = x / len, ny = y / len, nz = z / len;
        // right = normalize(up x n), falling back to X axis near the poles
        float rx = nz, ry = 0.0f, rz = -nx;
        float rl = (float) Math.sqrt(rx * rx + rz * rz);
        if (rl < 1e-3f) { rx = 1.0f; rz = 0.0f; rl = 1.0f; }
        rx /= rl; rz /= rl;
        // up2 = n x right
        float ux = ny * rz - nz * ry, uy = nz * rx - nx * rz, uz = nx * ry - ny * rx;
        float ax = (-rx - ux) * half, ay = (-ry - uy) * half, az = (-rz - uz) * half; // (-,-)
        float bx = (rx - ux) * half, by = (ry - uy) * half, bz = (rz - uz) * half;    // (+,-)
        float cx = (rx + ux) * half, cy = (ry + uy) * half, cz = (rz + uz) * half;    // (+,+)
        float dx = (-rx + ux) * half, dy = (-ry + uy) * half, dz = (-rz + uz) * half; // (-,+)
        vertex(buffer, matrix, x + ax, y + ay, z + az, color, alpha);
        vertex(buffer, matrix, x + bx, y + by, z + bz, color, alpha);
        vertex(buffer, matrix, x + cx, y + cy, z + cz, color, alpha);

        vertex(buffer, matrix, x + ax, y + ay, z + az, color, alpha);
        vertex(buffer, matrix, x + cx, y + cy, z + cz, color, alpha);
        vertex(buffer, matrix, x + dx, y + dy, z + dz, color, alpha);
    }

    private static void drawNebula(VertexConsumer buffer, Matrix4f matrix, float time, AmbienceSkyProvider ambience, Palette palette) {
        float glow = ambience.getNebulaGlow();
        if (glow <= 0.01f) return;

        int nebulaSegments = 32;
        float radius = DOME_RADIUS * 0.95f;
        for (int ring = 0; ring < 3; ring++) {
            float elevLow = 0.55f + ring * 0.22f;
            float elevHigh = elevLow + 0.24f;
            float phase = ring * 2.1f;
            float ringAlpha = glow * (0.16f - ring * 0.035f);

            for (int i = 0; i < nebulaSegments; i++) {
                float u0 = i / (float) nebulaSegments;
                float u1 = (i + 1) / (float) nebulaSegments;
                float a0 = u0 * (float) Math.PI * 2.0f;
                float a1 = u1 * (float) Math.PI * 2.0f;

                float wave0 = (float) Math.sin(a0 * 3.0f + time * 0.15f + phase) * 0.08f;
                float wave1 = (float) Math.sin(a1 * 3.0f + time * 0.15f + phase) * 0.08f;

                float x0 = (float) (Math.cos(elevLow + wave0) * Math.cos(a0)) * radius;
                float y0 = (float) Math.sin(elevLow + wave0) * radius;
                float z0 = (float) (Math.cos(elevLow + wave0) * Math.sin(a0)) * radius;

                float x1 = (float) (Math.cos(elevHigh + wave0) * Math.cos(a0)) * radius;
                float y1 = (float) Math.sin(elevHigh + wave0) * radius;
                float z1 = (float) (Math.cos(elevHigh + wave0) * Math.sin(a0)) * radius;

                float x2 = (float) (Math.cos(elevHigh + wave1) * Math.cos(a1)) * radius;
                float y2 = (float) Math.sin(elevHigh + wave1) * radius;
                float z2 = (float) (Math.cos(elevHigh + wave1) * Math.sin(a1)) * radius;

                float x3 = (float) (Math.cos(elevLow + wave1) * Math.cos(a1)) * radius;
                float y3 = (float) Math.sin(elevLow + wave1) * radius;
                float z3 = (float) (Math.cos(elevLow + wave1) * Math.sin(a1)) * radius;

                int col = ring % 2 == 0 ? palette.deep : palette.bright;
                vertex(buffer, matrix, x0, y0, z0, col, ringAlpha * 0.1f);
                vertex(buffer, matrix, x1, y1, z1, col, ringAlpha);
                vertex(buffer, matrix, x2, y2, z2, col, ringAlpha);

                vertex(buffer, matrix, x0, y0, z0, col, ringAlpha * 0.1f);
                vertex(buffer, matrix, x2, y2, z2, col, ringAlpha);
                vertex(buffer, matrix, x3, y3, z3, col, ringAlpha * 0.1f);
            }
        }
    }

    private static void drawCurtain(VertexConsumer buffer, Matrix4f matrix, float time, float phase,
                                    float span, float elevationOffset, float opacity, Palette palette) {
        for (int i = 0; i < SEGMENTS; i++) {
            float u0 = i / (float) SEGMENTS;
            float u1 = (i + 1) / (float) SEGMENTS;
            CurtainPoint low0 = point(u0, time, phase, span, elevationOffset, false);
            CurtainPoint high0 = point(u0, time, phase, span, elevationOffset, true);
            CurtainPoint low1 = point(u1, time, phase, span, elevationOffset, false);
            CurtainPoint high1 = point(u1, time, phase, span, elevationOffset, true);

            float glow0 = 0.55f + 0.45f * (float) Math.sin(u0 * Math.PI * 6.0 + time * 1.4 + phase);
            float glow1 = 0.55f + 0.45f * (float) Math.sin(u1 * Math.PI * 6.0 + time * 1.4 + phase);
            vertex(buffer, matrix, low0.x, low0.y, low0.z, palette.deep, opacity * 0.04f);
            vertex(buffer, matrix, high0.x, high0.y, high0.z, palette.bright, opacity * glow0);
            vertex(buffer, matrix, high1.x, high1.y, high1.z, palette.bright, opacity * glow1);
            vertex(buffer, matrix, low0.x, low0.y, low0.z, palette.deep, opacity * 0.04f);
            vertex(buffer, matrix, high1.x, high1.y, high1.z, palette.bright, opacity * glow1);
            vertex(buffer, matrix, low1.x, low1.y, low1.z, palette.deep, opacity * 0.04f);
        }
    }

    private static CurtainPoint point(float u, float time, float phase, float span, float elevationOffset, boolean high) {
        float angle = u * ((float) Math.PI * 2.0f);
        float waves = (float) (Math.sin(angle * 3.0f + time + phase) * 0.11
                + Math.sin(angle * 7.0f - time * 0.63f + phase * 1.7f) * 0.045);
        float lowElevation = 0.18f + elevationOffset * 0.08f + waves;
        float height = 0.38f + span * 0.26f
                + (float) Math.sin(angle * 2.0f - time * 0.74f + phase) * 0.12f;
        float elevation = high ? lowElevation + height : lowElevation;
        float horizontal = (float) Math.cos(elevation) * DOME_RADIUS;
        return new CurtainPoint((float) Math.cos(angle) * horizontal,
                (float) Math.sin(elevation) * DOME_RADIUS,
                (float) Math.sin(angle) * horizontal);
    }

    private static void vertex(VertexConsumer buffer, Matrix4f matrix, float x, float y, float z, int color, float alpha) {
        float a = Math.max(0.0f, Math.min(1.0f, alpha));
        buffer.vertex(matrix, x, y, z)
                .color(((color >> 16) & 0xFF) / 255.0f, ((color >> 8) & 0xFF) / 255.0f,
                        (color & 0xFF) / 255.0f, a);
    }

    private record StarData(float x, float y, float z, int type, int color, float twinkleFreq, float twinklePhase, float baseSize) {}

    private record CurtainPoint(float x, float y, float z) {}

    private record Palette(int bright, int deep) {
        private static Palette forName(String name) {
            return switch (name) {
                case "Nebula" -> new Palette(0xF25CFF, 0x4937B8);
                case "Ember" -> new Palette(0xFFD36B, 0xC13E7C);
                case "Cosmic Violet" -> new Palette(0x9E5CFF, 0x361668);
                default -> new Palette(0x75FFE0, 0x4F72FF);
            };
        }
    }
}
