package interstellarTikiTiks.apexx.client.render.msdf;

import interstellarTikiTiks.apexx.client.mixin.GameRendererAccessor;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.Camera;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector4f;

/**
 * World-space to screen-space projection.
 *
 * <h2>Why this exists</h2>
 * The obvious implementation — project the 8 corners of a box, divide by w, take the bounding
 * rect — is wrong the moment any corner is behind the camera. A corner with {@code w <= 0}
 * produces a divide that mirrors the point to the opposite side of the screen, so the rect
 * explodes to cover everything or inverts entirely. Merely testing {@code w > 0} and skipping is
 * also wrong: it discards boxes that are genuinely half on screen.
 *
 * <p>The fix is to clip in CLIP SPACE, before the perspective divide, along all <b>12 edges</b> of
 * the box. Any edge crossing the near plane is cut at the crossing point and the surviving
 * fragment is projected. That keeps partially-behind boxes correct instead of exploding.
 *
 * <p>The projection and view matrices are rebuilt exactly the way Minecraft builds them —
 * {@code GameRenderer.getBasicProjectionMatrix(fov)} plus the camera's conjugated rotation
 * quaternion — rather than hand-rolled from FOV and aspect, which is easy to get subtly wrong.
 */
public final class WorldProjector {

    private WorldProjector() {
    }

    /** Screen-space rect plus whether any part of it is actually visible. */
    public record Projected(float minX, float minY, float maxX, float maxY, boolean visible) {
        public float centerX() { return (minX + maxX) * 0.5f; }
        public float width()   { return maxX - minX; }
        public float height()  { return maxY - minY; }
    }

    private static final float NEAR_EPSILON = 0.05f;

    // Flat (from, to) pairs. A 2D int[][] literal makes ProGuard's preverifier throw
    // StackTypeException in <clinit> (it mistypes the nested aastore), failing the release build
    // intermittently -- the same fix applied to AnchorAimSearch.
    private static final int[] EDGES = {
            0, 1, 1, 2, 2, 3, 3, 0,
            4, 5, 5, 6, 6, 7, 7, 4,
            0, 4, 1, 5, 2, 6, 3, 7
    };

    /** Combined view-projection for this frame, camera-relative. */
    public static Matrix4f viewProjection(float tickDelta) {
        MinecraftClient mc = MinecraftClient.getInstance();
        Camera camera = mc.gameRenderer.getCamera();
        float fov = ((GameRendererAccessor) mc.gameRenderer).callGetFov(camera, tickDelta, true);

        Matrix4f proj = mc.gameRenderer.getBasicProjectionMatrix(fov);
        Matrix4f view = new Matrix4f().rotation(camera.getRotation().conjugate(new Quaternionf()));
        return new Matrix4f(proj).mul(view);
    }

    /**
     * Project a world-space box to a screen rect, in framebuffer pixels.
     *
     * @param viewProj combined matrix from {@link #viewProjection(float)}
     */
    public static Projected project(Matrix4f viewProj, Box box, int screenWidth, int screenHeight) {
        MinecraftClient mc = MinecraftClient.getInstance();
        Vec3d cam = mc.gameRenderer.getCamera().getCameraPos();

        Vector4f[] clip = new Vector4f[8];
        int i = 0;
        for (int xi = 0; xi < 2; xi++) {
            double x = xi == 0 ? box.minX : box.maxX;
            for (int yi = 0; yi < 2; yi++) {
                double y = yi == 0 ? box.minY : box.maxY;
                for (int zi = 0; zi < 2; zi++) {
                    double z = zi == 0 ? box.minZ : box.maxZ;
                    Vector4f v = new Vector4f(
                            (float) (x - cam.x), (float) (y - cam.y), (float) (z - cam.z), 1.0f);
                    // Clip space: NOT divided by w yet. That is the whole point.
                    clip[i++] = v.mul(viewProj);
                }
            }
        }

        float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE;
        float maxX = -Float.MAX_VALUE, maxY = -Float.MAX_VALUE;
        boolean visible = false;

        for (int e = 0; e < EDGES.length; e += 2) {
            Vector4f a = clip[EDGES[e]];
            Vector4f b = clip[EDGES[e + 1]];
            boolean aIn = a.w > NEAR_EPSILON;
            boolean bIn = b.w > NEAR_EPSILON;
            if (!aIn && !bIn) continue;   // whole edge behind the camera

            Vector4f ca = a;
            Vector4f cb = b;
            if (!aIn) {
                ca = lerp(a, b, (NEAR_EPSILON - a.w) / (b.w - a.w));
            } else if (!bIn) {
                cb = lerp(a, b, (NEAR_EPSILON - a.w) / (b.w - a.w));
            }
            visible = true;

            float ax = (ca.x / ca.w * 0.5f + 0.5f) * screenWidth;
            float ay = (0.5f - ca.y / ca.w * 0.5f) * screenHeight;
            float bx = (cb.x / cb.w * 0.5f + 0.5f) * screenWidth;
            float by = (0.5f - cb.y / cb.w * 0.5f) * screenHeight;

            minX = Math.min(minX, Math.min(ax, bx));
            maxX = Math.max(maxX, Math.max(ax, bx));
            minY = Math.min(minY, Math.min(ay, by));
            maxY = Math.max(maxY, Math.max(ay, by));
        }

        if (!visible) {
            return new Projected(0, 0, 0, 0, false);
        }
        return new Projected(minX, minY, maxX, maxY, true);
    }

    private static Vector4f lerp(Vector4f a, Vector4f b, float t) {
        return new Vector4f(
                a.x + (b.x - a.x) * t,
                a.y + (b.y - a.y) * t,
                a.z + (b.z - a.z) * t,
                a.w + (b.w - a.w) * t);
    }
}
