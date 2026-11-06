package interstellarTikiTiks.apexx.client.render.visuals;

import com.mojang.blaze3d.pipeline.BlendFunction;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.platform.DepthTestFunction;
import com.mojang.blaze3d.vertex.VertexFormat;
import interstellarTikiTiks.apexx.client.Apexx;
import net.minecraft.client.gl.RenderPipelines;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.RenderSetup;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexFormats;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import org.joml.Matrix4f;

import java.util.Arrays;

/**
 * Geometry and pass owner for projectile trajectory visuals. The Trajectory module decides what to show
 * (samples, colours, sizes); this class owns the additive/translucent pipelines and turns that intent into
 * soft camera-facing ribbons, surface rings, beams and orbs. Softness comes from per-vertex alpha profiles,
 * so every pass uses the vanilla position/colour shader and works on any render backend.
 */
public final class TrajectoryVisualRenderer {
    private static final Identifier POSITION_COLOR = Identifier.of("minecraft", "core/position_color");

    public static final RenderLayer GLOW_DEPTH = layer("trajectory_glow_depth", BlendFunction.LIGHTNING, DepthTestFunction.LEQUAL_DEPTH_TEST);
    public static final RenderLayer GLOW_XRAY = layer("trajectory_glow_xray", BlendFunction.LIGHTNING, DepthTestFunction.NO_DEPTH_TEST);
    public static final RenderLayer FILL_DEPTH = layer("trajectory_fill_depth", BlendFunction.TRANSLUCENT, DepthTestFunction.LEQUAL_DEPTH_TEST);
    public static final RenderLayer FILL_XRAY = layer("trajectory_fill_xray", BlendFunction.TRANSLUCENT, DepthTestFunction.NO_DEPTH_TEST);

    /** Cross-section of a neon tube: transparent rim, saturated body, white-hot centre line. */
    public static final Profile TUBE = new Profile(
            new float[] { -1f, -0.42f, 0f, 0.42f, 1f },
            new float[] { 0f, 1f, 1f, 1f, 0f },
            new float[] { 0f, 0.05f, 0.7f, 0.05f, 0f });
    /** Soft bloom halo laid under the tube with additive blending. */
    public static final Profile HALO = new Profile(
            new float[] { -1f, -0.3f, 0f, 0.3f, 1f },
            new float[] { 0f, 0.55f, 1f, 0.55f, 0f },
            new float[] { 0f, 0f, 0f, 0f, 0f });

    private TrajectoryVisualRenderer() { }

    private static RenderLayer layer(String name, BlendFunction blend, DepthTestFunction depth) {
        RenderPipeline pipeline = interstellarTikiTiks.apexx.client.util.render.SafePipelines.register(RenderPipeline.builder(
                        RenderPipelines.TRANSFORMS_AND_PROJECTION_SNIPPET, RenderPipelines.GLOBALS_SNIPPET)
                .withLocation(Identifier.of(Apexx.MOD_ID, "pipeline/" + name))
                .withVertexShader(POSITION_COLOR)
                .withFragmentShader(POSITION_COLOR)
                .withVertexFormat(VertexFormats.POSITION_COLOR, VertexFormat.DrawMode.TRIANGLES)
                .withCull(false)
                .withBlend(blend)
                .withDepthTestFunction(depth)
                .withDepthWrite(false)
                .build());
        return RenderLayer.of("apexx_" + name, RenderSetup.builder(pipeline).expectedBufferSize(4096).translucent().build());
    }

    public static RenderLayer glow(boolean throughWalls) { return throughWalls ? GLOW_XRAY : GLOW_DEPTH; }

    public static RenderLayer fill(boolean throughWalls) { return throughWalls ? FILL_XRAY : FILL_DEPTH; }

    /** Across-ribbon offsets (-1..1) with matching opacity and white-mix weights. */
    public record Profile(float[] offsets, float[] alpha, float[] white) { }

    /** Reusable, growable sample buffer: one position, colour and width scale per path sample. */
    public static final class Path {
        double[] x = new double[256], y = new double[256], z = new double[256];
        float[] r = new float[256], g = new float[256], b = new float[256], a = new float[256], w = new float[256];
        int size;

        public void clear() { size = 0; }

        public int size() { return size; }

        public Vec3d positionAt(int i) { return new Vec3d(x[i], y[i], z[i]); }

        public float redAt(int i) { return r[i]; }

        public float greenAt(int i) { return g[i]; }

        public float blueAt(int i) { return b[i]; }

        public float alphaAt(int i) { return a[i]; }

        public float widthAt(int i) { return w[i]; }

        public void add(double px, double py, double pz, float cr, float cg, float cb, float ca, float width) {
            if (size == x.length) grow();
            x[size] = px; y[size] = py; z[size] = pz;
            r[size] = cr; g[size] = cg; b[size] = cb; a[size] = ca; w[size] = width;
            size++;
        }

        private void grow() {
            int n = x.length * 2;
            x = Arrays.copyOf(x, n); y = Arrays.copyOf(y, n); z = Arrays.copyOf(z, n);
            r = Arrays.copyOf(r, n); g = Arrays.copyOf(g, n); b = Arrays.copyOf(b, n);
            a = Arrays.copyOf(a, n); w = Arrays.copyOf(w, n);
        }
    }

    /**
     * Emits a camera-facing ribbon through every sample. The side vector is computed per sample from the
     * averaged tangent, so neighbouring segments share edges and the ribbon has no gaps at bends.
     */
    public static void ribbon(VertexConsumer buf, Matrix4f mat, Vec3d cam, Path path, float halfWidth,
                              Profile profile, float alphaScale) {
        int n = path.size;
        if (n < 2 || alphaScale <= 0f) return;
        int lanes = profile.offsets.length;
        float[] prev = new float[lanes * 3];
        float[] next = new float[lanes * 3];
        sideOffsets(path, 0, cam, halfWidth, profile, prev);
        for (int i = 1; i < n; i++) {
            sideOffsets(path, i, cam, halfWidth, profile, next);
            float a0 = path.a[i - 1] * alphaScale, a1 = path.a[i] * alphaScale;
            if (a0 > 0.003f || a1 > 0.003f) {
                for (int lane = 0; lane < lanes - 1; lane++) {
                    int l0 = lane * 3, l1 = (lane + 1) * 3;
                    float w00 = profile.white[lane], w01 = profile.white[lane + 1];
                    float al0 = profile.alpha[lane], al1 = profile.alpha[lane + 1];
                    vertex(buf, mat, prev, l0, path, i - 1, w00, a0 * al0);
                    vertex(buf, mat, prev, l1, path, i - 1, w01, a0 * al1);
                    vertex(buf, mat, next, l1, path, i, w01, a1 * al1);
                    vertex(buf, mat, prev, l0, path, i - 1, w00, a0 * al0);
                    vertex(buf, mat, next, l1, path, i, w01, a1 * al1);
                    vertex(buf, mat, next, l0, path, i, w00, a1 * al0);
                }
            }
            float[] swap = prev; prev = next; next = swap;
        }
    }

    private static void sideOffsets(Path p, int i, Vec3d cam, float halfWidth, Profile profile, float[] out) {
        int i0 = Math.max(0, i - 1), i1 = Math.min(p.size - 1, i + 1);
        double tx = p.x[i1] - p.x[i0], ty = p.y[i1] - p.y[i0], tz = p.z[i1] - p.z[i0];
        double vx = cam.x - p.x[i], vy = cam.y - p.y[i], vz = cam.z - p.z[i];
        double sx = ty * vz - tz * vy, sy = tz * vx - tx * vz, sz = tx * vy - ty * vx;
        double len = Math.sqrt(sx * sx + sy * sy + sz * sz);
        if (len < 1.0e-9) { sx = 1; sy = 0; sz = 0; len = 1; }
        double scale = halfWidth * p.w[i] / len;
        for (int lane = 0; lane < profile.offsets.length; lane++) {
            double o = profile.offsets[lane] * scale;
            out[lane * 3] = (float) (p.x[i] + sx * o);
            out[lane * 3 + 1] = (float) (p.y[i] + sy * o);
            out[lane * 3 + 2] = (float) (p.z[i] + sz * o);
        }
    }

    private static void vertex(VertexConsumer buf, Matrix4f mat, float[] pos, int o, Path p, int i, float white, float alpha) {
        float r = p.r[i] + (1f - p.r[i]) * white;
        float g = p.g[i] + (1f - p.g[i]) * white;
        float b = p.b[i] + (1f - p.b[i]) * white;
        buf.vertex(mat, pos[o], pos[o + 1], pos[o + 2]).color(r, g, b, clamp01(alpha));
    }

    /** Orthonormal in-plane basis for a surface normal: {tangent, bitangent}. */
    public static Vec3d[] planeBasis(Vec3d normal) {
        Vec3d n = normal == null || normal.lengthSquared() < 1.0e-6 ? new Vec3d(0, 1, 0) : normal.normalize();
        Vec3d helper = Math.abs(n.y) > 0.9 ? new Vec3d(1, 0, 0) : new Vec3d(0, 1, 0);
        Vec3d tangent = helper.crossProduct(n).normalize();
        return new Vec3d[] { tangent, n.crossProduct(tangent) };
    }

    /**
     * A soft-edged band between two radii in the plane of {@code basis}, optionally limited to an arc.
     * Inner and outer edges carry their own opacity, so a pair of bands makes a ring that fades both ways.
     */
    public static void band(VertexConsumer buf, Matrix4f mat, Vec3d center, Vec3d[] basis,
                            double innerRadius, double outerRadius, double startAngle, double sweep, int segments,
                            float r, float g, float b, float innerAlpha, float outerAlpha) {
        if (outerRadius <= 0 || segments < 1 || (innerAlpha <= 0.002f && outerAlpha <= 0.002f)) return;
        Vec3d t = basis[0], s = basis[1];
        float ia = clamp01(innerAlpha), oa = clamp01(outerAlpha);
        for (int i = 0; i < segments; i++) {
            double a0 = startAngle + sweep * i / segments, a1 = startAngle + sweep * (i + 1) / segments;
            double c0 = Math.cos(a0), s0 = Math.sin(a0), c1 = Math.cos(a1), s1 = Math.sin(a1);
            float ix0 = (float) (center.x + (t.x * c0 + s.x * s0) * innerRadius);
            float iy0 = (float) (center.y + (t.y * c0 + s.y * s0) * innerRadius);
            float iz0 = (float) (center.z + (t.z * c0 + s.z * s0) * innerRadius);
            float ix1 = (float) (center.x + (t.x * c1 + s.x * s1) * innerRadius);
            float iy1 = (float) (center.y + (t.y * c1 + s.y * s1) * innerRadius);
            float iz1 = (float) (center.z + (t.z * c1 + s.z * s1) * innerRadius);
            float ox0 = (float) (center.x + (t.x * c0 + s.x * s0) * outerRadius);
            float oy0 = (float) (center.y + (t.y * c0 + s.y * s0) * outerRadius);
            float oz0 = (float) (center.z + (t.z * c0 + s.z * s0) * outerRadius);
            float ox1 = (float) (center.x + (t.x * c1 + s.x * s1) * outerRadius);
            float oy1 = (float) (center.y + (t.y * c1 + s.y * s1) * outerRadius);
            float oz1 = (float) (center.z + (t.z * c1 + s.z * s1) * outerRadius);
            buf.vertex(mat, ix0, iy0, iz0).color(r, g, b, ia);
            buf.vertex(mat, ox0, oy0, oz0).color(r, g, b, oa);
            buf.vertex(mat, ox1, oy1, oz1).color(r, g, b, oa);
            buf.vertex(mat, ix0, iy0, iz0).color(r, g, b, ia);
            buf.vertex(mat, ox1, oy1, oz1).color(r, g, b, oa);
            buf.vertex(mat, ix1, iy1, iz1).color(r, g, b, ia);
        }
    }

    /** A crisp ring of the given width with soft falloff on both sides. */
    public static void softRing(VertexConsumer buf, Matrix4f mat, Vec3d center, Vec3d[] basis, double radius,
                                double halfWidth, double startAngle, double sweep, int segments,
                                float r, float g, float b, float alpha) {
        double inner = Math.max(0, radius - halfWidth);
        band(buf, mat, center, basis, inner, radius, startAngle, sweep, segments, r, g, b, 0f, alpha);
        band(buf, mat, center, basis, radius, radius + halfWidth, startAngle, sweep, segments, r, g, b, alpha, 0f);
    }

    /** A disc that faces the camera, opaque at the centre and transparent at the rim (glow orbs, sparks). */
    public static void orb(VertexConsumer buf, Matrix4f mat, Vec3d cam, Vec3d center, double radius,
                           float r, float g, float b, float alpha) {
        Vec3d view = cam.subtract(center);
        if (view.lengthSquared() < 1.0e-6) return;
        band(buf, mat, center, planeBasis(view), 0, radius, 0, Math.PI * 2, 12, r, g, b, alpha, 0f);
    }

    /**
     * A vertical light shaft that always faces the camera around its axis. Opacity fades upward and a
     * brighter scan band climbs the shaft once per {@code scanPeriod} seconds.
     */
    public static void beam(VertexConsumer buf, Matrix4f mat, Vec3d cam, Vec3d base, double height, double halfWidth,
                            float r, float g, float b, float alpha, double timeSeconds, double scanPeriod) {
        double dx = cam.x - base.x, dz = cam.z - base.z;
        double len = Math.sqrt(dx * dx + dz * dz);
        double sx = len < 1.0e-6 ? 1 : -dz / len, sz = len < 1.0e-6 ? 0 : dx / len;
        int rows = 16;
        double scan = scanPeriod > 0 ? (timeSeconds % scanPeriod) / scanPeriod : -1;
        float[] offsets = { -1f, -0.25f, 0f, 0.25f, 1f };
        float[] across = { 0f, 0.65f, 1f, 0.65f, 0f };
        for (int row = 0; row < rows; row++) {
            double v0 = (double) row / rows, v1 = (double) (row + 1) / rows;
            float a0 = alpha * rowAlpha(v0, scan), a1 = alpha * rowAlpha(v1, scan);
            float y0 = (float) (base.y + height * v0), y1 = (float) (base.y + height * v1);
            for (int lane = 0; lane < offsets.length - 1; lane++) {
                float o0 = (float) (offsets[lane] * halfWidth), o1 = (float) (offsets[lane + 1] * halfWidth);
                float x0 = (float) (base.x + sx * o0), z0 = (float) (base.z + sz * o0);
                float x1 = (float) (base.x + sx * o1), z1 = (float) (base.z + sz * o1);
                float c0 = across[lane], c1 = across[lane + 1];
                buf.vertex(mat, x0, y0, z0).color(r, g, b, clamp01(a0 * c0));
                buf.vertex(mat, x1, y0, z1).color(r, g, b, clamp01(a0 * c1));
                buf.vertex(mat, x1, y1, z1).color(r, g, b, clamp01(a1 * c1));
                buf.vertex(mat, x0, y0, z0).color(r, g, b, clamp01(a0 * c0));
                buf.vertex(mat, x1, y1, z1).color(r, g, b, clamp01(a1 * c1));
                buf.vertex(mat, x0, y1, z0).color(r, g, b, clamp01(a1 * c0));
            }
        }
    }

    private static float rowAlpha(double v, double scan) {
        double fade = Math.pow(1.0 - v, 1.6);
        double base = fade * (0.55 + 0.45 * Math.min(1.0, v * 8.0));
        if (scan >= 0) base += Math.exp(-Math.pow((v - scan) / 0.06, 2)) * 0.55 * (1.0 - v);
        return (float) base;
    }

    /** Translucent fill of every face of a box (entity impact highlight). */
    public static void box(VertexConsumer buf, Matrix4f mat, Box box, float r, float g, float b, float a) {
        float x0 = (float) box.minX, y0 = (float) box.minY, z0 = (float) box.minZ;
        float x1 = (float) box.maxX, y1 = (float) box.maxY, z1 = (float) box.maxZ;
        quad(buf, mat, x0, y0, z0, x1, y0, z0, x1, y0, z1, x0, y0, z1, r, g, b, a);
        quad(buf, mat, x0, y1, z0, x0, y1, z1, x1, y1, z1, x1, y1, z0, r, g, b, a);
        quad(buf, mat, x0, y0, z0, x0, y1, z0, x1, y1, z0, x1, y0, z0, r, g, b, a);
        quad(buf, mat, x0, y0, z1, x1, y0, z1, x1, y1, z1, x0, y1, z1, r, g, b, a);
        quad(buf, mat, x0, y0, z0, x0, y0, z1, x0, y1, z1, x0, y1, z0, r, g, b, a);
        quad(buf, mat, x1, y0, z0, x1, y1, z0, x1, y1, z1, x1, y0, z1, r, g, b, a);
    }

    private static void quad(VertexConsumer buf, Matrix4f mat,
                             float x1, float y1, float z1, float x2, float y2, float z2,
                             float x3, float y3, float z3, float x4, float y4, float z4,
                             float r, float g, float b, float a) {
        buf.vertex(mat, x1, y1, z1).color(r, g, b, a);
        buf.vertex(mat, x2, y2, z2).color(r, g, b, a);
        buf.vertex(mat, x3, y3, z3).color(r, g, b, a);
        buf.vertex(mat, x1, y1, z1).color(r, g, b, a);
        buf.vertex(mat, x3, y3, z3).color(r, g, b, a);
        buf.vertex(mat, x4, y4, z4).color(r, g, b, a);
    }

    private static float clamp01(float v) {
        return v < 0f ? 0f : (v > 1f ? 1f : v);
    }
}
