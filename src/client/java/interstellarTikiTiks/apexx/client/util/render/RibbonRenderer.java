package interstellarTikiTiks.apexx.client.util.render;

import net.minecraft.client.render.VertexConsumer;
import org.joml.Matrix4f;

/**
 * Thick, camera-facing line geometry for POSITION_COLOR triangle layers ({@code RenderTypes.TRIANGLES} /
 * {@code TRIANGLES_NO_DEPTH}). GL line width is not available on the core profile, so every "line" is a
 * quad whose width vector is perpendicular to both the line and the view ray.
 *
 * <p>All coordinates are camera-relative (world minus camera position), which is what the world-render
 * matrix stack expects. Every method only emits vertices; it never allocates.</p>
 */
public final class RibbonRenderer {
    private RibbonRenderer() {
    }

    /** Colour of trail point {@code index}; {@code headT} is 0 at the oldest point and 1 at the newest. */
    @FunctionalInterface
    public interface PointColor {
        int argb(int index, float headT, float lifeFraction);
    }

    /**
     * Draws a fading ribbon through a trail. Each point's alpha is the colour's alpha times the age fade
     * and a near-camera fade (so a trail starting at your own feet does not fill the screen).
     *
     * @param halfWidth   half width in blocks at a fresh point
     * @param taper       narrow the ribbon as points age
     * @param nearFade    distance (blocks) under which points fade out; 0 disables
     */
    public static void trail(VertexConsumer buffer, Matrix4f matrix, TrailBuffer trail,
                             double camX, double camY, double camZ, float halfWidth, long now, long lifeMs,
                             boolean taper, float nearFade, PointColor color) {
        int n = trail.size();
        if (n < 2) return;
        float prevLx = 0, prevLy = 0, prevLz = 0, prevRx = 0, prevRy = 0, prevRz = 0;
        int prevArgb = 0;
        float sideX = 0, sideY = 1, sideZ = 0;
        for (int i = 0; i < n; i++) {
            float px = (float) (trail.x(i) - camX);
            float py = (float) (trail.y(i) - camY);
            float pz = (float) (trail.z(i) - camZ);
            int a = Math.max(0, i - 1);
            int b = Math.min(n - 1, i + 1);
            float tx = (float) (trail.x(b) - trail.x(a));
            float ty = (float) (trail.y(b) - trail.y(a));
            float tz = (float) (trail.z(b) - trail.z(a));
            // side = tangent x view
            float sx = ty * pz - tz * py;
            float sy = tz * px - tx * pz;
            float sz = tx * py - ty * px;
            float len = (float) Math.sqrt(sx * sx + sy * sy + sz * sz);
            if (len > 1.0e-6f) {
                sideX = sx / len;
                sideY = sy / len;
                sideZ = sz / len;
            }
            float life = TrailMath.lifeFraction(now - trail.time(i), lifeMs);
            float headT = (float) i / (float) (n - 1);
            float fade = 1.0f - TrailMath.smoothstep(0.0f, 1.0f, life);
            if (nearFade > 0.0f) {
                float dist = (float) Math.sqrt(px * px + py * py + pz * pz);
                fade *= TrailMath.smoothstep(nearFade * 0.35f, nearFade, dist);
            }
            int argb = TrailMath.scaleAlpha(color.argb(i, headT, life), fade);
            float w = halfWidth * (taper ? (1.0f - 0.85f * life) * (0.35f + 0.65f * headT) : 1.0f);
            float lx = px - sideX * w, ly = py - sideY * w, lz = pz - sideZ * w;
            float rx = px + sideX * w, ry = py + sideY * w, rz = pz + sideZ * w;
            if (i > 0 && (TrailMath.alpha(argb) > 0 || TrailMath.alpha(prevArgb) > 0)) {
                quad(buffer, matrix, prevLx, prevLy, prevLz, prevArgb, prevRx, prevRy, prevRz, prevArgb,
                        rx, ry, rz, argb, lx, ly, lz, argb);
            }
            prevLx = lx; prevLy = ly; prevLz = lz;
            prevRx = rx; prevRy = ry; prevRz = rz;
            prevArgb = argb;
        }
    }

    /** A camera-facing thick segment between two camera-relative points, colour blended along it. */
    public static void segment(VertexConsumer buffer, Matrix4f matrix, float x1, float y1, float z1,
                               float x2, float y2, float z2, float halfWidth, int argb1, int argb2) {
        float dx = x2 - x1, dy = y2 - y1, dz = z2 - z1;
        float mx = (x1 + x2) * 0.5f, my = (y1 + y2) * 0.5f, mz = (z1 + z2) * 0.5f;
        float sx = dy * mz - dz * my;
        float sy = dz * mx - dx * mz;
        float sz = dx * my - dy * mx;
        float len = (float) Math.sqrt(sx * sx + sy * sy + sz * sz);
        if (len < 1.0e-7f) return;
        float k = halfWidth / len;
        sx *= k;
        sy *= k;
        sz *= k;
        quad(buffer, matrix, x1 - sx, y1 - sy, z1 - sz, argb1, x1 + sx, y1 + sy, z1 + sz, argb1,
                x2 + sx, y2 + sy, z2 + sz, argb2, x2 - sx, y2 - sy, z2 - sz, argb2);
    }

    /**
     * Flat horizontal ring (annulus) centred on a camera-relative point. The band runs from
     * {@code radius - halfBand} to {@code radius + halfBand}; both edges take {@code edgeAlpha} times the colour
     * alpha (0 gives a soft glowing band, 1 a hard flat band).
     */
    public static void ring(VertexConsumer buffer, Matrix4f matrix, float cx, float cy, float cz, float radius,
                            float halfBand, int segments, int argb, float edgeAlpha) {
        dashedRing(buffer, matrix, cx, cy, cz, radius, halfBand, segments, 1, 1.0f, 0.0f, argb, edgeAlpha);
    }

    /**
     * Horizontal ring split into {@code dashes} arcs, each covering {@code duty} (0..1) of its slot, rotated by
     * {@code rotation} radians. {@code dashes = 1, duty = 1} is a closed ring.
     */
    public static void dashedRing(VertexConsumer buffer, Matrix4f matrix, float cx, float cy, float cz, float radius,
                                  float halfBand, int segments, int dashes, float duty, float rotation, int argb,
                                  float edgeAlpha) {
        if (radius <= 0.0f || TrailMath.alpha(argb) == 0) return;
        int dashCount = Math.max(1, dashes);
        int perDash = Math.max(2, segments / dashCount);
        float slot = (float) (Math.PI * 2.0) / dashCount;
        float arc = slot * TrailMath.clamp01(duty);
        float inner = Math.max(0.0f, radius - halfBand);
        float outer = radius + halfBand;
        int edge = TrailMath.scaleAlpha(argb, edgeAlpha);
        for (int d = 0; d < dashCount; d++) {
            float start = rotation + d * slot;
            for (int s = 0; s < perDash; s++) {
                float a0 = start + arc * s / perDash;
                float a1 = start + arc * (s + 1) / perDash;
                float c0 = (float) Math.cos(a0), s0 = (float) Math.sin(a0);
                float c1 = (float) Math.cos(a1), s1 = (float) Math.sin(a1);
                // inner half: inner edge -> mid line
                quad(buffer, matrix,
                        cx + c0 * inner, cy, cz + s0 * inner, edge,
                        cx + c0 * radius, cy, cz + s0 * radius, argb,
                        cx + c1 * radius, cy, cz + s1 * radius, argb,
                        cx + c1 * inner, cy, cz + s1 * inner, edge);
                // outer half: mid line -> outer edge
                quad(buffer, matrix,
                        cx + c0 * radius, cy, cz + s0 * radius, argb,
                        cx + c0 * outer, cy, cz + s0 * outer, edge,
                        cx + c1 * outer, cy, cz + s1 * outer, edge,
                        cx + c1 * radius, cy, cz + s1 * radius, argb);
            }
        }
    }

    /** Filled horizontal disc, colour blended from centre to rim. */
    public static void disc(VertexConsumer buffer, Matrix4f matrix, float cx, float cy, float cz, float radius,
                            int segments, int centerArgb, int rimArgb) {
        if (radius <= 0.0f) return;
        int segs = Math.max(6, segments);
        for (int s = 0; s < segs; s++) {
            double a0 = Math.PI * 2.0 * s / segs;
            double a1 = Math.PI * 2.0 * (s + 1) / segs;
            buffer.vertex(matrix, cx, cy, cz).color(centerArgb);
            buffer.vertex(matrix, cx + (float) Math.cos(a0) * radius, cy, cz + (float) Math.sin(a0) * radius).color(rimArgb);
            buffer.vertex(matrix, cx + (float) Math.cos(a1) * radius, cy, cz + (float) Math.sin(a1) * radius).color(rimArgb);
        }
    }

    /**
     * Twelve thick, screen-constant edges of a camera-relative box. {@code thickness} is roughly in pixels.
     * Vertices are pulled a hair toward the camera so the camera-facing quads are not half buried in the faces.
     */
    public static void boxEdges(VertexConsumer buffer, Matrix4f matrix, float minX, float minY, float minZ,
                                float maxX, float maxY, float maxZ, float thickness, int argbBottom, int argbTop) {
        edge(buffer, matrix, minX, minY, minZ, maxX, minY, minZ, thickness, argbBottom, argbBottom);
        edge(buffer, matrix, maxX, minY, minZ, maxX, minY, maxZ, thickness, argbBottom, argbBottom);
        edge(buffer, matrix, maxX, minY, maxZ, minX, minY, maxZ, thickness, argbBottom, argbBottom);
        edge(buffer, matrix, minX, minY, maxZ, minX, minY, minZ, thickness, argbBottom, argbBottom);
        edge(buffer, matrix, minX, maxY, minZ, maxX, maxY, minZ, thickness, argbTop, argbTop);
        edge(buffer, matrix, maxX, maxY, minZ, maxX, maxY, maxZ, thickness, argbTop, argbTop);
        edge(buffer, matrix, maxX, maxY, maxZ, minX, maxY, maxZ, thickness, argbTop, argbTop);
        edge(buffer, matrix, minX, maxY, maxZ, minX, maxY, minZ, thickness, argbTop, argbTop);
        edge(buffer, matrix, minX, minY, minZ, minX, maxY, minZ, thickness, argbBottom, argbTop);
        edge(buffer, matrix, maxX, minY, minZ, maxX, maxY, minZ, thickness, argbBottom, argbTop);
        edge(buffer, matrix, maxX, minY, maxZ, maxX, maxY, maxZ, thickness, argbBottom, argbTop);
        edge(buffer, matrix, minX, minY, maxZ, minX, maxY, maxZ, thickness, argbBottom, argbTop);
    }

    /** One screen-constant thick edge (camera-relative), pulled slightly toward the camera. */
    public static void edge(VertexConsumer buffer, Matrix4f matrix, float x1, float y1, float z1,
                            float x2, float y2, float z2, float thickness, int argb1, int argb2) {
        final float pull = 0.9985f;
        float mx = (x1 + x2) * 0.5f, my = (y1 + y2) * 0.5f, mz = (z1 + z2) * 0.5f;
        double dist = Math.sqrt(mx * mx + my * my + mz * mz);
        segment(buffer, matrix, x1 * pull, y1 * pull, z1 * pull, x2 * pull, y2 * pull, z2 * pull,
                TrailMath.screenConstantHalfWidth(thickness, dist), argb1, argb2);
    }

    /** Six faces of a camera-relative box, colour blended bottom to top. */
    public static void boxFill(VertexConsumer buffer, Matrix4f matrix, float minX, float minY, float minZ,
                               float maxX, float maxY, float maxZ, int argbBottom, int argbTop) {
        quad(buffer, matrix, minX, minY, minZ, argbBottom, maxX, minY, minZ, argbBottom, maxX, minY, maxZ, argbBottom, minX, minY, maxZ, argbBottom);
        quad(buffer, matrix, minX, maxY, minZ, argbTop, minX, maxY, maxZ, argbTop, maxX, maxY, maxZ, argbTop, maxX, maxY, minZ, argbTop);
        quad(buffer, matrix, minX, minY, minZ, argbBottom, minX, maxY, minZ, argbTop, maxX, maxY, minZ, argbTop, maxX, minY, minZ, argbBottom);
        quad(buffer, matrix, minX, minY, maxZ, argbBottom, maxX, minY, maxZ, argbBottom, maxX, maxY, maxZ, argbTop, minX, maxY, maxZ, argbTop);
        quad(buffer, matrix, minX, minY, minZ, argbBottom, minX, minY, maxZ, argbBottom, minX, maxY, maxZ, argbTop, minX, maxY, minZ, argbTop);
        quad(buffer, matrix, maxX, minY, minZ, argbBottom, maxX, maxY, minZ, argbTop, maxX, maxY, maxZ, argbTop, maxX, minY, maxZ, argbBottom);
    }

    /** Two triangles over the quad (1, 2, 3, 4) with per-vertex colours. */
    public static void quad(VertexConsumer buffer, Matrix4f matrix,
                            float x1, float y1, float z1, int c1,
                            float x2, float y2, float z2, int c2,
                            float x3, float y3, float z3, int c3,
                            float x4, float y4, float z4, int c4) {
        buffer.vertex(matrix, x1, y1, z1).color(c1);
        buffer.vertex(matrix, x2, y2, z2).color(c2);
        buffer.vertex(matrix, x3, y3, z3).color(c3);
        buffer.vertex(matrix, x1, y1, z1).color(c1);
        buffer.vertex(matrix, x3, y3, z3).color(c3);
        buffer.vertex(matrix, x4, y4, z4).color(c4);
    }
}
