package interstellarTikiTiks.apexx.client.util.render;

import interstellarTikiTiks.apexx.client.util.render.types.RenderTypes;
import interstellarTikiTiks.apexx.client.util.render.types.ShaderRenderLayers;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import org.joml.Matrix4f;

import java.awt.*;

public class RenderUtils {

    public static void drawBox(MatrixStack matrices, VertexConsumerProvider vertexConsumers, Box box, Color color, float alpha, boolean seeThrough) {
        drawBox(matrices, vertexConsumers, box, color, alpha, seeThrough, false);
    }

    public static void drawBox(MatrixStack matrices, VertexConsumerProvider vertexConsumers, Box box, Color color, float alpha,
            boolean seeThrough, boolean useEspShader) {

        Matrix4f matrix = matrices.peek().getPositionMatrix();

        float r = color.getRed() / 255f;
        float g = color.getGreen() / 255f;
        float b = color.getBlue() / 255f;
        float a = alpha;

        float minX = (float) box.minX;
        float minY = (float) box.minY;
        float minZ = (float) box.minZ;
        float maxX = (float) box.maxX;
        float maxY = (float) box.maxY;
        float maxZ = (float) box.maxZ;

        if (useEspShader) {
            if (seeThrough) {
                VertexConsumer throughBuffer = vertexConsumers.getBuffer(ShaderRenderLayers.getEspLayer(true));
                emitBoxFaces(throughBuffer, matrix, minX, minY, minZ, maxX, maxY, maxZ, r, g, b, a * 0.72f);
            }
            VertexConsumer depthBuffer = vertexConsumers.getBuffer(ShaderRenderLayers.getEspLayer(false));
            emitBoxFaces(depthBuffer, matrix, minX, minY, minZ, maxX, maxY, maxZ, r, g, b, a);
            return;
        }

        VertexConsumer buffer = vertexConsumers.getBuffer(RenderTypes.TRIANGLES);
        if (seeThrough) {
            emitBoxFaces(buffer, matrix, minX, minY, minZ, maxX, maxY, maxZ, r, g, b, a);
        }
        emitBoxFaces(buffer, matrix, minX, minY, minZ, maxX, maxY, maxZ, r, g, b, a);
    }

    public static void drawBoxEsp(MatrixStack matrices, VertexConsumerProvider vertexConsumers, Box box, Color color, float alpha, boolean seeThrough) {
        drawBox(matrices, vertexConsumers, box, color, alpha, seeThrough, true);
    }

    /**
     * Wireframe box outline (12 edges) instead of 6 filled faces. This is what "Box ESP"
     * conventionally means — {@link #drawBoxEsp} draws a solid translucent cube, which reads as a
     * glowing blob rather than a crisp outline, especially with several overlapping players.
     */
    public static void drawBoxOutlineEsp(MatrixStack matrices, VertexConsumerProvider vertexConsumers, Box box, Color color, float alpha, boolean seeThrough) {
        VertexConsumer buffer = vertexConsumers.getBuffer(ShaderRenderLayers.getEspLineLayer(seeThrough));
        Matrix4f matrix = matrices.peek().getPositionMatrix();

        float r = color.getRed() / 255f;
        float g = color.getGreen() / 255f;
        float b = color.getBlue() / 255f;

        float minX = (float) box.minX;
        float minY = (float) box.minY;
        float minZ = (float) box.minZ;
        float maxX = (float) box.maxX;
        float maxY = (float) box.maxY;
        float maxZ = (float) box.maxZ;

        // Bottom face (4 edges)
        edge(buffer, matrix, minX, minY, minZ, maxX, minY, minZ, r, g, b, alpha);
        edge(buffer, matrix, maxX, minY, minZ, maxX, minY, maxZ, r, g, b, alpha);
        edge(buffer, matrix, maxX, minY, maxZ, minX, minY, maxZ, r, g, b, alpha);
        edge(buffer, matrix, minX, minY, maxZ, minX, minY, minZ, r, g, b, alpha);
        // Top face (4 edges)
        edge(buffer, matrix, minX, maxY, minZ, maxX, maxY, minZ, r, g, b, alpha);
        edge(buffer, matrix, maxX, maxY, minZ, maxX, maxY, maxZ, r, g, b, alpha);
        edge(buffer, matrix, maxX, maxY, maxZ, minX, maxY, maxZ, r, g, b, alpha);
        edge(buffer, matrix, minX, maxY, maxZ, minX, maxY, minZ, r, g, b, alpha);
        // Vertical edges connecting the two faces
        edge(buffer, matrix, minX, minY, minZ, minX, maxY, minZ, r, g, b, alpha);
        edge(buffer, matrix, maxX, minY, minZ, maxX, maxY, minZ, r, g, b, alpha);
        edge(buffer, matrix, maxX, minY, maxZ, maxX, maxY, maxZ, r, g, b, alpha);
        edge(buffer, matrix, minX, minY, maxZ, minX, maxY, maxZ, r, g, b, alpha);
    }

    private static void edge(VertexConsumer buffer, Matrix4f matrix,
                              float x1, float y1, float z1, float x2, float y2, float z2,
                              float r, float g, float b, float a) {
        buffer.vertex(matrix, x1, y1, z1).color(r, g, b, a);
        buffer.vertex(matrix, x2, y2, z2).color(r, g, b, a);
    }

    public static void drawFilledBox(MatrixStack matrices, VertexConsumerProvider vertexConsumers,
                                     Box box, Color color, float alpha) {

        VertexConsumer buffer = vertexConsumers.getBuffer(RenderTypes.TRIANGLES);
        Matrix4f matrix = matrices.peek().getPositionMatrix();

        float r = color.getRed() / 255f;
        float g = color.getGreen() / 255f;
        float b = color.getBlue() / 255f;

        float minX = (float) box.minX;
        float minY = (float) box.minY;
        float minZ = (float) box.minZ;
        float maxX = (float) box.maxX;
        float maxY = (float) box.maxY;
        float maxZ = (float) box.maxZ;

        drawQuad(buffer, matrix, minX, minY, minZ, maxX, minY, minZ, maxX, minY, maxZ, minX, minY, maxZ, r, g, b, alpha);
        drawQuad(buffer, matrix, minX, maxY, minZ, minX, maxY, maxZ, maxX, maxY, maxZ, maxX, maxY, minZ, r, g, b, alpha);
        drawQuad(buffer, matrix, minX, minY, minZ, minX, maxY, minZ, maxX, maxY, minZ, maxX, minY, minZ, r, g, b, alpha);
        drawQuad(buffer, matrix, minX, minY, maxZ, maxX, minY, maxZ, maxX, maxY, maxZ, minX, maxY, maxZ, r, g, b, alpha);
        drawQuad(buffer, matrix, minX, minY, minZ, minX, minY, maxZ, minX, maxY, maxZ, minX, maxY, minZ, r, g, b, alpha);
        drawQuad(buffer, matrix, maxX, minY, minZ, maxX, maxY, minZ, maxX, maxY, maxZ, maxX, minY, maxZ, r, g, b, alpha);
    }

    public static void drawSphere(MatrixStack matrices, VertexConsumerProvider vertexConsumers,
                                  Vec3d center, double radius, Color color, float alpha, int segments, boolean ignored) {
        VertexConsumer buffer = vertexConsumers.getBuffer(RenderTypes.TRIANGLES);
        drawSphereInternal(matrices, buffer, center, radius, color, alpha, segments);
    }

    public static void drawSphereEsp(MatrixStack matrices, VertexConsumerProvider vertexConsumers,
                                     Vec3d center, double radius, Color color, float alpha, int segments, boolean seeThrough) {
        VertexConsumer buffer = vertexConsumers.getBuffer(ShaderRenderLayers.getEspLayer(seeThrough));
        drawSphereInternal(matrices, buffer, center, radius, color, alpha, segments);
    }

    private static void drawSphereInternal(MatrixStack matrices, VertexConsumer buffer,
                                           Vec3d center, double radius, Color color, float alpha, int segments) {
        matrices.push();
        matrices.translate(center.x, center.y, center.z);

        float r = color.getRed() / 255f;
        float g = color.getGreen() / 255f;
        float b = color.getBlue() / 255f;

        Matrix4f matrix = matrices.peek().getPositionMatrix();

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

                float x0 = (float) (Math.cos(lng0));
                float z0 = (float) (Math.sin(lng0));
                float x1 = (float) (Math.cos(lng1));
                float z1 = (float) (Math.sin(lng1));

                buffer.vertex(matrix, (float) (x0 * r0), (float) y0, (float) (z0 * r0)).color(r, g, b, alpha);
                buffer.vertex(matrix, (float) (x1 * r0), (float) y0, (float) (z1 * r0)).color(r, g, b, alpha);
                buffer.vertex(matrix, (float) (x0 * r1), (float) y1, (float) (z0 * r1)).color(r, g, b, alpha);

                buffer.vertex(matrix, (float) (x1 * r0), (float) y0, (float) (z1 * r0)).color(r, g, b, alpha);
                buffer.vertex(matrix, (float) (x1 * r1), (float) y1, (float) (z1 * r1)).color(r, g, b, alpha);
                buffer.vertex(matrix, (float) (x0 * r1), (float) y1, (float) (z0 * r1)).color(r, g, b, alpha);
            }
        }

        matrices.pop();
    }

    public static void drawSphere(MatrixStack matrices, VertexConsumerProvider vertexConsumers,
                                  Vec3d center, double radius, Color color, float alpha, int segments) {
        drawSphere(matrices, vertexConsumers, center, radius, color, alpha, segments, false);
    }

    public static void drawCylinder(MatrixStack matrices, VertexConsumerProvider vertexConsumers,
                                    Vec3d base, double radius, double height, Color color, float alpha, int segments) {
        VertexConsumer buffer = vertexConsumers.getBuffer(RenderTypes.TRIANGLES);
        drawCylinderInternal(matrices, buffer, base, radius, height, color, alpha, segments);
    }

    public static void drawCylinderEsp(MatrixStack matrices, VertexConsumerProvider vertexConsumers,
                                       Vec3d base, double radius, double height, Color color, float alpha, int segments, boolean seeThrough) {
        VertexConsumer buffer = vertexConsumers.getBuffer(ShaderRenderLayers.getEspLayer(seeThrough));
        drawCylinderInternal(matrices, buffer, base, radius, height, color, alpha, segments);
    }

    private static void drawCylinderInternal(MatrixStack matrices, VertexConsumer buffer,
                                             Vec3d base, double radius, double height, Color color, float alpha, int segments) {
        matrices.push();
        matrices.translate(base.x, base.y, base.z);

        float r = color.getRed() / 255f;
        float g = color.getGreen() / 255f;
        float b = color.getBlue() / 255f;

        Matrix4f matrix = matrices.peek().getPositionMatrix();

        for (int i = 0; i < segments; i++) {
            double angle0 = 2 * Math.PI * i / segments;
            double angle1 = 2 * Math.PI * (i + 1) / segments;

            float x0 = (float) (Math.cos(angle0) * radius);
            float z0 = (float) (Math.sin(angle0) * radius);
            float x1 = (float) (Math.cos(angle1) * radius);
            float z1 = (float) (Math.sin(angle1) * radius);

            drawQuad(buffer, matrix, x0, 0, z0, x0, (float) height, z0, x1, (float) height, z1, x1, 0, z1, r, g, b, alpha);
        }

        for (int i = 0; i < segments; i++) {
            double angle0 = 2 * Math.PI * i / segments;
            double angle1 = 2 * Math.PI * (i + 1) / segments;

            float x0 = (float) (Math.cos(angle0) * radius);
            float z0 = (float) (Math.sin(angle0) * radius);
            float x1 = (float) (Math.cos(angle1) * radius);
            float z1 = (float) (Math.sin(angle1) * radius);

            buffer.vertex(matrix, 0, 0, 0).color(r, g, b, alpha);
            buffer.vertex(matrix, x0, 0, z0).color(r, g, b, alpha);
            buffer.vertex(matrix, x1, 0, z1).color(r, g, b, alpha);

            buffer.vertex(matrix, 0, (float) height, 0).color(r, g, b, alpha);
            buffer.vertex(matrix, x1, (float) height, z1).color(r, g, b, alpha);
            buffer.vertex(matrix, x0, (float) height, z0).color(r, g, b, alpha);
        }

        matrices.pop();
    }

    public static void drawCone(MatrixStack matrices, VertexConsumerProvider vertexConsumers,
                                Vec3d base, double radius, double height, Color color, float alpha, int segments) {
        VertexConsumer buffer = vertexConsumers.getBuffer(RenderTypes.TRIANGLES);
        drawConeInternal(matrices, buffer, base, radius, height, color, alpha, segments);
    }

    public static void drawConeEsp(MatrixStack matrices, VertexConsumerProvider vertexConsumers,
                                   Vec3d base, double radius, double height, Color color, float alpha, int segments, boolean seeThrough) {
        VertexConsumer buffer = vertexConsumers.getBuffer(ShaderRenderLayers.getEspLayer(seeThrough));
        drawConeInternal(matrices, buffer, base, radius, height, color, alpha, segments);
    }

    private static void drawConeInternal(MatrixStack matrices, VertexConsumer buffer,
                                         Vec3d base, double radius, double height, Color color, float alpha, int segments) {
        matrices.push();
        matrices.translate(base.x, base.y, base.z);

        float r = color.getRed() / 255f;
        float g = color.getGreen() / 255f;
        float b = color.getBlue() / 255f;

        Matrix4f matrix = matrices.peek().getPositionMatrix();

        for (int i = 0; i < segments; i++) {
            double angle0 = 2 * Math.PI * i / segments;
            double angle1 = 2 * Math.PI * (i + 1) / segments;

            float x0 = (float) (Math.cos(angle0) * radius);
            float z0 = (float) (Math.sin(angle0) * radius);
            float x1 = (float) (Math.cos(angle1) * radius);
            float z1 = (float) (Math.sin(angle1) * radius);

            buffer.vertex(matrix, x0, 0, z0).color(r, g, b, alpha);
            buffer.vertex(matrix, 0, (float) height, 0).color(r, g, b, alpha);
            buffer.vertex(matrix, x1, 0, z1).color(r, g, b, alpha);
        }

        for (int i = 0; i < segments; i++) {
            double angle0 = 2 * Math.PI * i / segments;
            double angle1 = 2 * Math.PI * (i + 1) / segments;

            float x0 = (float) (Math.cos(angle0) * radius);
            float z0 = (float) (Math.sin(angle0) * radius);
            float x1 = (float) (Math.cos(angle1) * radius);
            float z1 = (float) (Math.sin(angle1) * radius);

            buffer.vertex(matrix, 0, 0, 0).color(r, g, b, alpha);
            buffer.vertex(matrix, x1, 0, z1).color(r, g, b, alpha);
            buffer.vertex(matrix, x0, 0, z0).color(r, g, b, alpha);
        }

        matrices.pop();
    }

    public static void drawLine(MatrixStack matrices, VertexConsumerProvider vertexConsumers,
                                Vec3d start, Vec3d end, Color color, float alpha) {

        VertexConsumer buffer = vertexConsumers.getBuffer(RenderTypes.LINES);
        Matrix4f matrix = matrices.peek().getPositionMatrix();

        float r = color.getRed() / 255f;
        float g = color.getGreen() / 255f;
        float b = color.getBlue() / 255f;

        buffer.vertex(matrix, (float) start.x, (float) start.y, (float) start.z).color(r, g, b, alpha);
        buffer.vertex(matrix, (float) end.x, (float) end.y, (float) end.z).color(r, g, b, alpha);
    }

    public static void drawCircle(MatrixStack matrices, VertexConsumerProvider vertexConsumers,
                                  Vec3d center, double radius, Color color, float alpha, int segments) {
        VertexConsumer buffer = vertexConsumers.getBuffer(RenderTypes.LINES);
        matrices.push();
        matrices.translate(center.x, center.y, center.z);

        float r = color.getRed() / 255f;
        float g = color.getGreen() / 255f;
        float b = color.getBlue() / 255f;

        Matrix4f matrix = matrices.peek().getPositionMatrix();

        for (int i = 0; i < segments; i++) {
            double angle0 = 2 * Math.PI * i / segments;
            double angle1 = 2 * Math.PI * (i + 1) / segments;

            float x0 = (float) (Math.cos(angle0) * radius);
            float z0 = (float) (Math.sin(angle0) * radius);
            float x1 = (float) (Math.cos(angle1) * radius);
            float z1 = (float) (Math.sin(angle1) * radius);

            buffer.vertex(matrix, x0, 0, z0).color(r, g, b, alpha);
            buffer.vertex(matrix, x1, 0, z1).color(r, g, b, alpha);
        }

        matrices.pop();
    }

    private static void drawQuad(VertexConsumer buffer, Matrix4f matrix,
                                 float x1, float y1, float z1,
                                 float x2, float y2, float z2,
                                 float x3, float y3, float z3,
                                 float x4, float y4, float z4,
                                 float r, float g, float b, float a) {
        buffer.vertex(matrix, x1, y1, z1).color(r, g, b, a);
        buffer.vertex(matrix, x2, y2, z2).color(r, g, b, a);
        buffer.vertex(matrix, x3, y3, z3).color(r, g, b, a);

        buffer.vertex(matrix, x1, y1, z1).color(r, g, b, a);
        buffer.vertex(matrix, x3, y3, z3).color(r, g, b, a);
        buffer.vertex(matrix, x4, y4, z4).color(r, g, b, a);
    }

    private static void emitBoxFaces(VertexConsumer buffer, Matrix4f matrix,
                                     float minX, float minY, float minZ,
                                     float maxX, float maxY, float maxZ,
                                     float r, float g, float b, float a) {
        drawQuad(buffer, matrix, minX, minY, minZ, maxX, minY, minZ, maxX, minY, maxZ, minX, minY, maxZ, r, g, b, a);
        drawQuad(buffer, matrix, minX, maxY, minZ, minX, maxY, maxZ, maxX, maxY, maxZ, maxX, maxY, minZ, r, g, b, a);
        drawQuad(buffer, matrix, minX, minY, minZ, minX, maxY, minZ, maxX, maxY, minZ, maxX, minY, minZ, r, g, b, a);
        drawQuad(buffer, matrix, minX, minY, maxZ, maxX, minY, maxZ, maxX, maxY, maxZ, minX, maxY, maxZ, r, g, b, a);
        drawQuad(buffer, matrix, minX, minY, minZ, minX, minY, maxZ, minX, maxY, maxZ, minX, maxY, minZ, r, g, b, a);
        drawQuad(buffer, matrix, maxX, minY, minZ, maxX, maxY, minZ, maxX, maxY, maxZ, maxX, minY, maxZ, r, g, b, a);
    }
}
