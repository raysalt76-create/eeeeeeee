package interstellarTikiTiks.apexx.client.util.render;

import interstellarTikiTiks.apexx.client.mixin.GameRendererAccessor;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.Camera;
import net.minecraft.util.math.Vec3d;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Quaternionf;

/**
 * Allocation-free world -> GUI projection for HUD overlays (2D ESP, damage numbers, rings' labels).
 *
 * <p>{@link #begin(float)} builds the frame's view-projection once (same perspective and camera
 * rotation {@link ProjectionUtil} uses); {@link #toGui} / {@link #boxToGui} then project any number of
 * points with no per-call allocation. Render thread only: the frame state and scratch arrays are
 * shared statics.</p>
 *
 * <p>The {@code project*} statics are pure (JOML only) so they are unit tested.</p>
 */
public final class ScreenProjector {

    /** Points closer than this to the eye plane (clip w) are treated as behind the camera. */
    public static final float NEAR_W = 0.05f;

    private static final Matrix4f VIEW_PROJ = new Matrix4f();
    private static final Quaternionf ROTATION = new Quaternionf();
    private static final float[] CLIP = new float[32];

    private static double camX;
    private static double camY;
    private static double camZ;
    private static float guiWidth;
    private static float guiHeight;
    private static float fovDegrees = 70.0f;
    private static boolean ready;

    private ScreenProjector() {
    }

    /** Captures this frame's camera. Returns false (and later calls fail) when there is no camera. */
    public static boolean begin(float tickDelta) {
        ready = false;
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc == null || mc.gameRenderer == null || mc.getWindow() == null) return false;
        Camera camera = mc.gameRenderer.getCamera();
        if (camera == null) return false;
        int fbW = mc.getWindow().getFramebufferWidth();
        int fbH = mc.getWindow().getFramebufferHeight();
        if (fbW <= 0 || fbH <= 0) return false;
        float fov;
        try {
            fov = ((GameRendererAccessor) mc.gameRenderer).callGetFov(camera, tickDelta, true);
        } catch (Throwable t) {
            fov = 70.0f;
        }
        fovDegrees = fov;
        buildViewProjection(VIEW_PROJ, fov, (float) fbW / (float) fbH, camera.getRotation());
        Vec3d pos = camera.getCameraPos();
        camX = pos.x;
        camY = pos.y;
        camZ = pos.z;
        guiWidth = mc.getWindow().getScaledWidth();
        guiHeight = mc.getWindow().getScaledHeight();
        ready = guiWidth > 0 && guiHeight > 0;
        return ready;
    }

    public static boolean isReady() {
        return ready;
    }

    public static float guiWidth() {
        return guiWidth;
    }

    public static float guiHeight() {
        return guiHeight;
    }

    public static float fovDegrees() {
        return fovDegrees;
    }

    public static double camX() {
        return camX;
    }

    public static double camY() {
        return camY;
    }

    public static double camZ() {
        return camZ;
    }

    /** Writes the view-projection into {@code dest}: perspective * conjugate(camera rotation). */
    public static Matrix4f buildViewProjection(Matrix4f dest, float fovDegrees, float aspect, Quaternionf cameraRotation) {
        dest.setPerspective((float) Math.toRadians(fovDegrees), aspect, NEAR_W, 1024.0f);
        if (cameraRotation != null) {
            ROTATION.set(cameraRotation).conjugate();
            dest.rotate(ROTATION);
        }
        return dest;
    }

    /**
     * Projects a world point to GUI pixels. {@code out} receives x, y and clip w (a distance proxy).
     * Returns false when the point is behind the camera. On-screen is not required; callers clip.
     */
    public static boolean toGui(double x, double y, double z, float[] out) {
        if (!ready) return false;
        return project(VIEW_PROJ, (float) (x - camX), (float) (y - camY), (float) (z - camZ),
                guiWidth, guiHeight, out);
    }

    /**
     * Projects a world-space box to its GUI-space bounding rect (minX, minY, maxX, maxY) with the
     * twelve edges clipped at the near plane, so a box partly behind the camera still gives a sane rect.
     */
    public static boolean boxToGui(double minX, double minY, double minZ, double maxX, double maxY, double maxZ,
                                   float[] out4) {
        if (!ready) return false;
        return projectBox(VIEW_PROJ,
                (float) (minX - camX), (float) (minY - camY), (float) (minZ - camZ),
                (float) (maxX - camX), (float) (maxY - camY), (float) (maxZ - camZ),
                guiWidth, guiHeight, out4);
    }

    // ---- Pure math ------------------------------------------------------------------------------

    /** Camera-relative point -> screen pixels (y down). False when behind the near plane. */
    public static boolean project(Matrix4fc viewProj, float rx, float ry, float rz, float width, float height,
                                  float[] out) {
        float cx = viewProj.m00() * rx + viewProj.m10() * ry + viewProj.m20() * rz + viewProj.m30();
        float cy = viewProj.m01() * rx + viewProj.m11() * ry + viewProj.m21() * rz + viewProj.m31();
        float cw = viewProj.m03() * rx + viewProj.m13() * ry + viewProj.m23() * rz + viewProj.m33();
        if (cw <= NEAR_W) return false;
        out[0] = (cx / cw * 0.5f + 0.5f) * width;
        out[1] = (0.5f - cy / cw * 0.5f) * height;
        if (out.length > 2) out[2] = cw;
        return true;
    }

    private static final int[] EDGES = {
            0, 1, 1, 3, 3, 2, 2, 0,
            4, 5, 5, 7, 7, 6, 6, 4,
            0, 4, 1, 5, 2, 6, 3, 7
    };

    /** Camera-relative box -> screen rect. False when the whole box is behind the camera. */
    public static boolean projectBox(Matrix4fc vp, float minX, float minY, float minZ,
                                     float maxX, float maxY, float maxZ, float width, float height, float[] out4) {
        float[] c = CLIP;
        for (int i = 0; i < 8; i++) {
            float x = (i & 4) == 0 ? minX : maxX;
            float y = (i & 2) == 0 ? minY : maxY;
            float z = (i & 1) == 0 ? minZ : maxZ;
            int o = i * 4;
            c[o] = vp.m00() * x + vp.m10() * y + vp.m20() * z + vp.m30();
            c[o + 1] = vp.m01() * x + vp.m11() * y + vp.m21() * z + vp.m31();
            c[o + 2] = 0.0f;
            c[o + 3] = vp.m03() * x + vp.m13() * y + vp.m23() * z + vp.m33();
        }
        float lx = Float.MAX_VALUE, ly = Float.MAX_VALUE, hx = -Float.MAX_VALUE, hy = -Float.MAX_VALUE;
        boolean any = false;
        for (int e = 0; e < EDGES.length; e += 2) {
            int a = EDGES[e] * 4;
            int b = EDGES[e + 1] * 4;
            float ax = c[a], ay = c[a + 1], aw = c[a + 3];
            float bx = c[b], by = c[b + 1], bw = c[b + 3];
            boolean aIn = aw > NEAR_W;
            boolean bIn = bw > NEAR_W;
            if (!aIn && !bIn) continue;
            if (!aIn) {
                float t = (NEAR_W - aw) / (bw - aw);
                ax += (bx - ax) * t;
                ay += (by - ay) * t;
                aw = NEAR_W;
            } else if (!bIn) {
                float t = (NEAR_W - bw) / (aw - bw);
                bx += (ax - bx) * t;
                by += (ay - by) * t;
                bw = NEAR_W;
            }
            any = true;
            float sax = (ax / aw * 0.5f + 0.5f) * width;
            float say = (0.5f - ay / aw * 0.5f) * height;
            float sbx = (bx / bw * 0.5f + 0.5f) * width;
            float sby = (0.5f - by / bw * 0.5f) * height;
            lx = Math.min(lx, Math.min(sax, sbx));
            hx = Math.max(hx, Math.max(sax, sbx));
            ly = Math.min(ly, Math.min(say, sby));
            hy = Math.max(hy, Math.max(say, sby));
        }
        if (!any) return false;
        out4[0] = lx;
        out4[1] = ly;
        out4[2] = hx;
        out4[3] = hy;
        return true;
    }

    /** True when a screen rect overlaps the viewport (with a margin in pixels). */
    public static boolean overlapsScreen(float minX, float minY, float maxX, float maxY, float width, float height,
                                         float margin) {
        return maxX >= -margin && maxY >= -margin && minX <= width + margin && minY <= height + margin;
    }

    /**
     * World size of one screen pixel at a given eye distance, for constant-pixel-width world lines.
     * {@code screenHeight} in the same pixel units the width is wanted in.
     */
    public static double worldPerPixel(double distance, float fovDegrees, float screenHeight) {
        if (screenHeight <= 0.0f) return 0.0;
        return 2.0 * Math.max(distance, 0.0) * Math.tan(Math.toRadians(fovDegrees) * 0.5) / screenHeight;
    }
}
