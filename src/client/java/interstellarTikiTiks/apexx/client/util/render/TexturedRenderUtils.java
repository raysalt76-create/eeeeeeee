package interstellarTikiTiks.apexx.client.util.render;

import net.minecraft.client.render.VertexConsumer;
import net.minecraft.util.math.Vec3d;
import org.joml.Matrix4f;

import java.awt.*;

public class TexturedRenderUtils {

    public static void drawTexturedSphere(VertexConsumer buffer, Matrix4f matrix,
                                          Vec3d center, double radius, Color color,
                                          float alpha, int segments, float uvOffsetU, float uvOffsetV) {
        float r = color.getRed() / 255f;
        float g = color.getGreen() / 255f;
        float b = color.getBlue() / 255f;

        for (int i = 0; i < segments; i++) {
            double lat0 = Math.PI * (-0.5 + (double) i / segments);
            double lat1 = Math.PI * (-0.5 + (double) (i + 1) / segments);

            double y0 = Math.sin(lat0) * radius;
            double y1 = Math.sin(lat1) * radius;
            double r0 = Math.cos(lat0) * radius;
            double r1 = Math.cos(lat1) * radius;

            for (int j = 0; j < segments; j++) {
                double lng0 = 2 * Math.PI * (double) j / segments;
                double lng1 = 2 * Math.PI * (double) (j + 1) / segments;

                float x0 = (float) (Math.cos(lng0) * r0);
                float z0 = (float) (Math.sin(lng0) * r0);
                float x1 = (float) (Math.cos(lng1) * r0);
                float z1 = (float) (Math.sin(lng1) * r0);

                float x2 = (float) (Math.cos(lng0) * r1);
                float z2 = (float) (Math.sin(lng0) * r1);
                float x3 = (float) (Math.cos(lng1) * r1);
                float z3 = (float) (Math.sin(lng1) * r1);

                float u0 = (float) j / segments + uvOffsetU;
                float u1 = (float) (j + 1) / segments + uvOffsetU;
                float v0 = (float) i / segments + uvOffsetV;
                float v1 = (float) (i + 1) / segments + uvOffsetV;

                buffer.vertex(matrix, x0, (float) y0, z0).color(r, g, b, alpha).texture(u0, v0);
                buffer.vertex(matrix, x1, (float) y0, z1).color(r, g, b, alpha).texture(u1, v0);
                buffer.vertex(matrix, x2, (float) y1, z2).color(r, g, b, alpha).texture(u0, v1);

                buffer.vertex(matrix, x1, (float) y0, z1).color(r, g, b, alpha).texture(u1, v0);
                buffer.vertex(matrix, x3, (float) y1, z3).color(r, g, b, alpha).texture(u1, v1);
                buffer.vertex(matrix, x2, (float) y1, z2).color(r, g, b, alpha).texture(u0, v1);
            }
        }
    }

    public static void drawTexturedCylinder(VertexConsumer buffer, Matrix4f matrix,
                                            double radius, double height, Color color,
                                            float alpha, int segments, float uvOffsetU, float uvOffsetV) {
        float r = color.getRed() / 255f;
        float g = color.getGreen() / 255f;
        float b = color.getBlue() / 255f;

        for (int i = 0; i < segments; i++) {
            double angle0 = 2 * Math.PI * i / segments;
            double angle1 = 2 * Math.PI * (i + 1) / segments;

            float x0 = (float) (Math.cos(angle0) * radius);
            float z0 = (float) (Math.sin(angle0) * radius);
            float x1 = (float) (Math.cos(angle1) * radius);
            float z1 = (float) (Math.sin(angle1) * radius);

            float u0 = (float) i / segments + uvOffsetU;
            float u1 = (float) (i + 1) / segments + uvOffsetU;

            buffer.vertex(matrix, x0, 0, z0).color(r, g, b, alpha).texture(u0, uvOffsetV);
            buffer.vertex(matrix, x0, (float) height, z0).color(r, g, b, alpha).texture(u0, 1 + uvOffsetV);
            buffer.vertex(matrix, x1, (float) height, z1).color(r, g, b, alpha).texture(u1, 1 + uvOffsetV);

            buffer.vertex(matrix, x0, 0, z0).color(r, g, b, alpha).texture(u0, uvOffsetV);
            buffer.vertex(matrix, x1, (float) height, z1).color(r, g, b, alpha).texture(u1, 1 + uvOffsetV);
            buffer.vertex(matrix, x1, 0, z1).color(r, g, b, alpha).texture(u1, uvOffsetV);
        }

        for (int i = 0; i < segments; i++) {
            double angle0 = 2 * Math.PI * i / segments;
            double angle1 = 2 * Math.PI * (i + 1) / segments;

            float x0 = (float) (Math.cos(angle0) * radius);
            float z0 = (float) (Math.sin(angle0) * radius);
            float x1 = (float) (Math.cos(angle1) * radius);
            float z1 = (float) (Math.sin(angle1) * radius);

            float u0 = (float) (Math.cos(angle0) * 0.5 + 0.5);
            float v0 = (float) (Math.sin(angle0) * 0.5 + 0.5);
            float u1 = (float) (Math.cos(angle1) * 0.5 + 0.5);
            float v1 = (float) (Math.sin(angle1) * 0.5 + 0.5);

            buffer.vertex(matrix, 0, 0, 0).color(r, g, b, alpha).texture(0.5f, 0.5f);
            buffer.vertex(matrix, x0, 0, z0).color(r, g, b, alpha).texture(u0, v0);
            buffer.vertex(matrix, x1, 0, z1).color(r, g, b, alpha).texture(u1, v1);

            buffer.vertex(matrix, 0, (float) height, 0).color(r, g, b, alpha).texture(0.5f, 0.5f);
            buffer.vertex(matrix, x1, (float) height, z1).color(r, g, b, alpha).texture(u1, v1);
            buffer.vertex(matrix, x0, (float) height, z0).color(r, g, b, alpha).texture(u0, v0);
        }
    }

    public static void drawTexturedQuad(VertexConsumer buffer, Matrix4f matrix,
                                        float x1, float y1, float z1,
                                        float x2, float y2, float z2,
                                        float x3, float y3, float z3,
                                        float x4, float y4, float z4,
                                        float r, float g, float b, float a,
                                        float u0, float v0, float u1, float v1) {
        buffer.vertex(matrix, x1, y1, z1).color(r, g, b, a).texture(u0, v0);
        buffer.vertex(matrix, x2, y2, z2).color(r, g, b, a).texture(u1, v0);
        buffer.vertex(matrix, x3, y3, z3).color(r, g, b, a).texture(u1, v1);

        buffer.vertex(matrix, x1, y1, z1).color(r, g, b, a).texture(u0, v0);
        buffer.vertex(matrix, x3, y3, z3).color(r, g, b, a).texture(u1, v1);
        buffer.vertex(matrix, x4, y4, z4).color(r, g, b, a).texture(u0, v1);
    }
}
