package interstellarTikiTiks.apexx.client.util.render;

import net.minecraft.client.render.OverlayTexture;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.util.math.MathHelper;
import org.joml.Matrix4f;

import java.awt.Color;

/** Allocation-free emit helpers for the textured entity vertex format used by {@link ProceduralTextures}. */
public final class VisualGeometry {
    public static final int FULL_BRIGHT = 0xF000F0;

    private VisualGeometry() {}

    public static void vertex(VertexConsumer buffer, Matrix4f m, double x, double y, double z,
                              float u, float v, int argb, int light) {
        buffer.vertex(m, (float) x, (float) y, (float) z)
                .color(((argb >> 16) & 255) / 255f, ((argb >> 8) & 255) / 255f, (argb & 255) / 255f, ((argb >>> 24) & 255) / 255f)
                .texture(u, v)
                .overlay(OverlayTexture.DEFAULT_UV)
                .light(light)
                .normal(0f, 1f, 0f);
    }

    /**
     * Quad with corners a(u0,v0) b(u1,v0) c(u1,v1) d(u0,v1) and a colour per corner.
     * Pipelines run with culling disabled, so winding does not matter.
     */
    public static void quad(VertexConsumer buf, Matrix4f m,
                            double ax, double ay, double az, double bx, double by, double bz,
                            double cx, double cy, double cz, double dx, double dy, double dz,
                            float u0, float v0, float u1, float v1,
                            int ca, int cb, int cc, int cd, int light) {
        vertex(buf, m, ax, ay, az, u0, v0, ca, light);
        vertex(buf, m, bx, by, bz, u1, v0, cb, light);
        vertex(buf, m, cx, cy, cz, u1, v1, cc, light);
        vertex(buf, m, ax, ay, az, u0, v0, ca, light);
        vertex(buf, m, cx, cy, cz, u1, v1, cc, light);
        vertex(buf, m, dx, dy, dz, u0, v1, cd, light);
    }

    /**
     * Triangle with corners a, b, c and per-vertex colours.
     */
    public static void triangle(VertexConsumer buf, Matrix4f m,
                                double ax, double ay, double az,
                                double bx, double by, double bz,
                                double cx, double cy, double cz,
                                float u0, float v0, float u1, float v1, float u2, float v2,
                                int ca, int cb, int cc, int light) {
        vertex(buf, m, ax, ay, az, u0, v0, ca, light);
        vertex(buf, m, bx, by, bz, u1, v1, cb, light);
        vertex(buf, m, cx, cy, cz, u2, v2, cc, light);
    }

    /**
     * Ribbon quad for cross-section textures (BOLT, PLANE-like): a->b spans the width (v 0..1) and a->d runs along the
     * length (u 0..1), so the texture's profile lands across the ribbon rather than along it.
     */
    public static void quadCross(VertexConsumer buf, Matrix4f m,
                                 double ax, double ay, double az, double bx, double by, double bz,
                                 double cx, double cy, double cz, double dx, double dy, double dz,
                                 int ca, int cb, int cc, int cd, int light) {
        vertex(buf, m, ax, ay, az, 0f, 0f, ca, light);
        vertex(buf, m, bx, by, bz, 0f, 1f, cb, light);
        vertex(buf, m, cx, cy, cz, 1f, 1f, cc, light);
        vertex(buf, m, ax, ay, az, 0f, 0f, ca, light);
        vertex(buf, m, cx, cy, cz, 1f, 1f, cc, light);
        vertex(buf, m, dx, dy, dz, 1f, 0f, cd, light);
    }

    /** Camera-facing sprite, rotated by {@code rot} radians, with half extents in world units. */
    public static void sprite(VertexConsumer buf, Matrix4f m, double cx, double cy, double cz,
                              double rightX, double rightY, double rightZ,
                              double upX, double upY, double upZ,
                              double halfW, double halfH, float rot,
                              float u0, float v0, float u1, float v1, int argb, int light) {
        double cos = Math.cos(rot), sin = Math.sin(rot);
        double ax = (rightX * cos + upX * sin) * halfW, ay = (rightY * cos + upY * sin) * halfW, az = (rightZ * cos + upZ * sin) * halfW;
        double bx = (upX * cos - rightX * sin) * halfH, by = (upY * cos - rightY * sin) * halfH, bz = (upZ * cos - rightZ * sin) * halfH;
        quad(buf, m,
                cx - ax + bx, cy - ay + by, cz - az + bz,
                cx + ax + bx, cy + ay + by, cz + az + bz,
                cx + ax - bx, cy + ay - by, cz + az - bz,
                cx - ax - bx, cy - ay - by, cz - az - bz,
                u0, v0, u1, v1, argb, argb, argb, argb, light);
    }

    public static int argb(float a, float r, float g, float b) {
        return (MathHelper.clamp((int) (a * 255f + 0.5f), 0, 255) << 24)
                | (MathHelper.clamp((int) (r * 255f + 0.5f), 0, 255) << 16)
                | (MathHelper.clamp((int) (g * 255f + 0.5f), 0, 255) << 8)
                | MathHelper.clamp((int) (b * 255f + 0.5f), 0, 255);
    }

    public static int color(Color c, float alpha) {
        return argb(alpha, c.getRed() / 255f, c.getGreen() / 255f, c.getBlue() / 255f);
    }

    /** Scales the alpha channel of an ARGB colour. */
    public static int fade(int argb, float mul) {
        int a = MathHelper.clamp((int) (((argb >>> 24) & 255) * mul + 0.5f), 0, 255);
        return (a << 24) | (argb & 0x00FFFFFF);
    }

    /** Multiplies RGB (not alpha) by {@code k}. */
    public static int shade(int argb, float k) {
        int r = MathHelper.clamp((int) (((argb >> 16) & 255) * k), 0, 255);
        int g = MathHelper.clamp((int) (((argb >> 8) & 255) * k), 0, 255);
        int b = MathHelper.clamp((int) ((argb & 255) * k), 0, 255);
        return (argb & 0xFF000000) | (r << 16) | (g << 8) | b;
    }

    public static int mix(int a, int b, float t) {
        t = MathHelper.clamp(t, 0f, 1f);
        int aa = (int) (((a >>> 24) & 255) + (((b >>> 24) & 255) - ((a >>> 24) & 255)) * t);
        int ar = (int) (((a >> 16) & 255) + (((b >> 16) & 255) - ((a >> 16) & 255)) * t);
        int ag = (int) (((a >> 8) & 255) + (((b >> 8) & 255) - ((a >> 8) & 255)) * t);
        int ab = (int) ((a & 255) + ((b & 255) - (a & 255)) * t);
        return (aa << 24) | (ar << 16) | (ag << 8) | ab;
    }
}
