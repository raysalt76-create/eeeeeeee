package interstellarTikiTiks.apexx.client.module.implementation.targeteffect;

import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.math.Vec3d;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.awt.*;
import java.util.ArrayList;
import java.util.List;

public class RenderHelpers {

    public static void renderContinuousTube(MatrixStack matrices, VertexConsumer buffer,
                                            List<Vec3d> points, double radius,
                                            Color color, float alpha, int sides) {
        if (points.size() < 2) return;

        Matrix4f matrix = matrices.peek().getPositionMatrix();
        float r = color.getRed() / 255f;
        float g = color.getGreen() / 255f;
        float b = color.getBlue() / 255f;

        List<Vector3f[]> allRings = new ArrayList<>();

        for (int i = 0; i < points.size(); i++) {
            Vec3d current = points.get(i);
            Vec3d tangent;

            if (i == 0) {
                tangent = points.get(1).subtract(current).normalize();
            } else if (i == points.size() - 1) {
                tangent = current.subtract(points.get(i - 1)).normalize();
            } else {
                Vec3d prev = points.get(i - 1);
                Vec3d next = points.get(i + 1);
                tangent = next.subtract(prev).normalize();
            }

            Vec3d up = Math.abs(tangent.y) > 0.9 ? new Vec3d(1, 0, 0) : new Vec3d(0, 1, 0);
            Vec3d right = tangent.crossProduct(up).normalize();
            up = right.crossProduct(tangent).normalize();

            Vector3f[] ring = new Vector3f[sides];
            for (int j = 0; j < sides; j++) {
                double angle = 2 * Math.PI * j / sides;
                double c = Math.cos(angle) * radius;
                double s = Math.sin(angle) * radius;

                Vec3d offset = right.multiply(c).add(up.multiply(s));
                Vec3d pos = current.add(offset);
                ring[j] = new Vector3f((float) pos.x, (float) pos.y, (float) pos.z);
            }
            allRings.add(ring);
        }

        for (int i = 0; i < allRings.size() - 1; i++) {
            Vector3f[] ring1 = allRings.get(i);
            Vector3f[] ring2 = allRings.get(i + 1);

            float progress = (float) i / allRings.size();
            float pulse = (float) Math.sin(progress * 10 - (System.currentTimeMillis() / 100.0));
            float pulseAlpha = alpha * (0.7f + 0.3f * pulse);

            for (int j = 0; j < sides; j++) {
                int nextJ = (j + 1) % sides;

                Vector3f v0 = ring1[j];
                Vector3f v1 = ring1[nextJ];
                Vector3f v2 = ring2[nextJ];
                Vector3f v3 = ring2[j];

                buffer.vertex(matrix, v0.x, v0.y, v0.z).color(r, g, b, pulseAlpha);
                buffer.vertex(matrix, v1.x, v1.y, v1.z).color(r, g, b, pulseAlpha);
                buffer.vertex(matrix, v2.x, v2.y, v2.z).color(r, g, b, pulseAlpha);

                buffer.vertex(matrix, v0.x, v0.y, v0.z).color(r, g, b, pulseAlpha);
                buffer.vertex(matrix, v2.x, v2.y, v2.z).color(r, g, b, pulseAlpha);
                buffer.vertex(matrix, v3.x, v3.y, v3.z).color(r, g, b, pulseAlpha);
            }
        }
    }

    public static void renderSphereOptimized(MatrixStack matrices, VertexConsumer buffer,
                                             double radius, Color color, float alpha, int segments) {
        Matrix4f matrix = matrices.peek().getPositionMatrix();
        float r = color.getRed() / 255f;
        float g = color.getGreen() / 255f;
        float b = color.getBlue() / 255f;

        int actualSegments = Math.max(4, Math.min(segments, (int)(radius * 100)));

        for (int i = 0; i < actualSegments; i++) {
            double lat0 = Math.PI * (-0.5 + (double) i / actualSegments);
            double lat1 = Math.PI * (-0.5 + (double) (i + 1) / actualSegments);
            double y0 = Math.sin(lat0) * radius;
            double y1 = Math.sin(lat1) * radius;
            double r0 = Math.cos(lat0) * radius;
            double r1 = Math.cos(lat1) * radius;

            for (int j = 0; j < actualSegments; j++) {
                double lng0 = 2 * Math.PI * (double) j / actualSegments;
                double lng1 = 2 * Math.PI * (double) (j + 1) / actualSegments;
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
    }

    public static void renderTorusTextured(MatrixStack matrices, VertexConsumer buffer,
                                           double majorRadius, double minorRadius, double rotation,
                                           Color color, float alpha, int majorSegments, int minorSegments,
                                           float uvOffsetU, float uvOffsetV) {
        matrices.push();
        matrices.multiply(net.minecraft.util.math.RotationAxis.POSITIVE_Y.rotationDegrees((float) Math.toDegrees(rotation)));

        Matrix4f matrix = matrices.peek().getPositionMatrix();

        float r = color.getRed() / 255f;
        float g = color.getGreen() / 255f;
        float b = color.getBlue() / 255f;

        for (int i = 0; i < majorSegments; i++) {
            double theta0 = 2 * Math.PI * i / majorSegments;
            double theta1 = 2 * Math.PI * (i + 1) / majorSegments;

            double cosTheta0 = Math.cos(theta0);
            double sinTheta0 = Math.sin(theta0);
            double cosTheta1 = Math.cos(theta1);
            double sinTheta1 = Math.sin(theta1);

            for (int j = 0; j < minorSegments; j++) {
                double phi0 = 2 * Math.PI * j / minorSegments;
                double phi1 = 2 * Math.PI * (j + 1) / minorSegments;

                double cosPhi0 = Math.cos(phi0);
                double sinPhi0 = Math.sin(phi0);
                double cosPhi1 = Math.cos(phi1);
                double sinPhi1 = Math.sin(phi1);

                float x0 = (float) ((majorRadius + minorRadius * cosPhi0) * cosTheta0);
                float y0 = (float) (minorRadius * sinPhi0);
                float z0 = (float) ((majorRadius + minorRadius * cosPhi0) * sinTheta0);

                float x1 = (float) ((majorRadius + minorRadius * cosPhi0) * cosTheta1);
                float y1 = (float) (minorRadius * sinPhi0);
                float z1 = (float) ((majorRadius + minorRadius * cosPhi0) * sinTheta1);

                float x2 = (float) ((majorRadius + minorRadius * cosPhi1) * cosTheta0);
                float y2 = (float) (minorRadius * sinPhi1);
                float z2 = (float) ((majorRadius + minorRadius * cosPhi1) * sinTheta0);

                float x3 = (float) ((majorRadius + minorRadius * cosPhi1) * cosTheta1);
                float y3 = (float) (minorRadius * sinPhi1);
                float z3 = (float) ((majorRadius + minorRadius * cosPhi1) * sinTheta1);

                float u0 = ((float) i / majorSegments) + uvOffsetU;
                float u1 = ((float) (i + 1) / majorSegments) + uvOffsetU;
                float v0 = ((float) j / minorSegments) + uvOffsetV;
                float v1 = ((float) (j + 1) / minorSegments) + uvOffsetV;

                buffer.vertex(matrix, x0, y0, z0).color(r, g, b, alpha).texture(u0, v0);
                buffer.vertex(matrix, x1, y1, z1).color(r, g, b, alpha).texture(u1, v0);
                buffer.vertex(matrix, x2, y2, z2).color(r, g, b, alpha).texture(u0, v1);

                buffer.vertex(matrix, x1, y1, z1).color(r, g, b, alpha).texture(u1, v0);
                buffer.vertex(matrix, x3, y3, z3).color(r, g, b, alpha).texture(u1, v1);
                buffer.vertex(matrix, x2, y2, z2).color(r, g, b, alpha).texture(u0, v1);
            }
        }

        matrices.pop();
    }

    public static void renderTorusOptimized(MatrixStack matrices, VertexConsumer buffer,
                                            double majorRadius, double minorRadius, double rotation,
                                            Color color, float alpha, int majorSegments, int minorSegments) {
        Matrix4f matrix = matrices.peek().getPositionMatrix();
        float r = color.getRed() / 255f;
        float g = color.getGreen() / 255f;
        float b = color.getBlue() / 255f;

        for (int i = 0; i < majorSegments; i++) {
            double angle0 = (2 * Math.PI * i / majorSegments) + rotation;
            double angle1 = (2 * Math.PI * (i + 1) / majorSegments) + rotation;

            double x0 = Math.cos(angle0) * majorRadius;
            double z0 = Math.sin(angle0) * majorRadius;
            double x1 = Math.cos(angle1) * majorRadius;
            double z1 = Math.sin(angle1) * majorRadius;

            double nx0 = Math.cos(angle0);
            double nz0 = Math.sin(angle0);
            double nx1 = Math.cos(angle1);
            double nz1 = Math.sin(angle1);

            for (int j = 0; j < minorSegments; j++) {
                double tubeAngle0 = 2 * Math.PI * j / minorSegments;
                double tubeAngle1 = 2 * Math.PI * (j + 1) / minorSegments;
                double ty0 = Math.sin(tubeAngle0) * minorRadius;
                double tr0 = Math.cos(tubeAngle0) * minorRadius;
                double ty1 = Math.sin(tubeAngle1) * minorRadius;
                double tr1 = Math.cos(tubeAngle1) * minorRadius;

                float px0 = (float) (x0 + nx0 * tr0);
                float py0 = (float) ty0;
                float pz0 = (float) (z0 + nz0 * tr0);
                float px1 = (float) (x1 + nx1 * tr0);
                float py1 = (float) ty0;
                float pz1 = (float) (z1 + nz1 * tr0);
                float px2 = (float) (x1 + nx1 * tr1);
                float py2 = (float) ty1;
                float pz2 = (float) (z1 + nz1 * tr1);
                float px3 = (float) (x0 + nx0 * tr1);
                float py3 = (float) ty1;
                float pz3 = (float) (z0 + nz0 * tr1);

                buffer.vertex(matrix, px0, py0, pz0).color(r, g, b, alpha);
                buffer.vertex(matrix, px1, py1, pz1).color(r, g, b, alpha);
                buffer.vertex(matrix, px2, py2, pz2).color(r, g, b, alpha);
                buffer.vertex(matrix, px0, py0, pz0).color(r, g, b, alpha);
                buffer.vertex(matrix, px2, py2, pz2).color(r, g, b, alpha);
                buffer.vertex(matrix, px3, py3, pz3).color(r, g, b, alpha);
            }
        }
    }
    public static void renderContinuousTubeTextured(MatrixStack matrices, VertexConsumer buffer,
                                                    List<Vec3d> pathPoints, double tubeRadius,
                                                    Color color, float alpha, int sides,
                                                    float uvOffsetU, float uvOffsetV) {
        if (pathPoints.size() < 2) return;

        Matrix4f matrix = matrices.peek().getPositionMatrix();
        float r = color.getRed() / 255f;
        float g = color.getGreen() / 255f;
        float b = color.getBlue() / 255f;

        double totalLength = 0;
        for (int i = 0; i < pathPoints.size() - 1; i++) {
            totalLength += pathPoints.get(i).distanceTo(pathPoints.get(i + 1));
        }

        double accumulatedLength = 0;

        for (int i = 0; i < pathPoints.size() - 1; i++) {
            Vec3d p1 = pathPoints.get(i);
            Vec3d p2 = pathPoints.get(i + 1);

            Vec3d dir = p2.subtract(p1).normalize();

            Vec3d up = Math.abs(dir.y) < 0.9 ? new Vec3d(0, 1, 0) : new Vec3d(1, 0, 0);
            Vec3d right = dir.crossProduct(up).normalize();
            Vec3d actualUp = right.crossProduct(dir).normalize();

            double segmentLength = p1.distanceTo(p2);
            float v0 = (float)(accumulatedLength / totalLength) + uvOffsetV;
            float v1 = (float)((accumulatedLength + segmentLength) / totalLength) + uvOffsetV;
            accumulatedLength += segmentLength;

            for (int j = 0; j < sides; j++) {
                double angle1 = 2 * Math.PI * j / sides;
                double angle2 = 2 * Math.PI * (j + 1) / sides;

                Vec3d offset1a = right.multiply(Math.cos(angle1) * tubeRadius)
                        .add(actualUp.multiply(Math.sin(angle1) * tubeRadius));
                Vec3d offset1b = right.multiply(Math.cos(angle2) * tubeRadius)
                        .add(actualUp.multiply(Math.sin(angle2) * tubeRadius));

                Vec3d v1a = p1.add(offset1a);
                Vec3d v1b = p1.add(offset1b);
                Vec3d v2a = p2.add(offset1a);
                Vec3d v2b = p2.add(offset1b);

                float u0 = ((float) j / sides) + uvOffsetU;
                float u1 = ((float) (j + 1) / sides) + uvOffsetU;

                buffer.vertex(matrix, (float)v1a.x, (float)v1a.y, (float)v1a.z)
                        .color(r, g, b, alpha).texture(u0, v0);
                buffer.vertex(matrix, (float)v1b.x, (float)v1b.y, (float)v1b.z)
                        .color(r, g, b, alpha).texture(u1, v0);
                buffer.vertex(matrix, (float)v2b.x, (float)v2b.y, (float)v2b.z)
                        .color(r, g, b, alpha).texture(u1, v1);

                buffer.vertex(matrix, (float)v1a.x, (float)v1a.y, (float)v1a.z)
                        .color(r, g, b, alpha).texture(u0, v0);
                buffer.vertex(matrix, (float)v2b.x, (float)v2b.y, (float)v2b.z)
                        .color(r, g, b, alpha).texture(u1, v1);
                buffer.vertex(matrix, (float)v2a.x, (float)v2a.y, (float)v2a.z)
                        .color(r, g, b, alpha).texture(u0, v1);
            }
        }
    }

}
