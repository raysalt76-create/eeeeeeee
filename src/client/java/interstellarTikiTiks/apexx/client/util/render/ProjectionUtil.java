package interstellarTikiTiks.apexx.client.util.render;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.Camera;
import net.minecraft.util.math.Vec3d;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector4f;

/**
 * High-performance 3D world-to-2D screen coordinate projection utility.
 * Uses exact JOML matrix transformations with conjugate camera orientation
 * to prevent axis inversion and perspective jitter.
 */
public final class ProjectionUtil {

    private static final MinecraftClient mc = MinecraftClient.getInstance();

    private ProjectionUtil() {}

    /**
     * Projects a 3D world space coordinate onto 2D GUI screen coordinates.
     *
     * @param worldPos  The 3D world coordinate to project
     * @param camera    The active rendering camera
     * @param activeFov The current camera FOV in degrees
     * @return Vec3d containing (screenX, screenY, depthZ) in GUI coordinates, or null if behind camera / out of bounds
     */
    public static Vec3d worldToScreen(Vec3d worldPos, Camera camera, float activeFov) {
        if (mc.getWindow() == null || camera == null) return null;

        Vec3d camPos = camera.getCameraPos();
        float relX = (float) (worldPos.x - camPos.x);
        float relY = (float) (worldPos.y - camPos.y);
        float relZ = (float) (worldPos.z - camPos.z);

        int fbWidth = mc.getWindow().getFramebufferWidth();
        int fbHeight = mc.getWindow().getFramebufferHeight();
        if (fbWidth <= 0 || fbHeight <= 0) return null;

        float aspect = (float) fbWidth / (float) fbHeight;
        float nearPlane = 0.05f;
        float farPlane = mc.gameRenderer.getViewDistanceBlocks() * 16.0f;

        Matrix4f proj = new Matrix4f().perspective(
                (float) Math.toRadians(activeFov),
                aspect,
                nearPlane,
                farPlane
        );

        Quaternionf cameraRot = new Quaternionf(camera.getRotation()).conjugate();
        Matrix4f view = new Matrix4f().rotation(cameraRot);

        Matrix4f viewProj = new Matrix4f(proj).mul(view);
        Vector4f clip = new Vector4f(relX, relY, relZ, 1.0f);
        viewProj.transform(clip);

        // Point is behind the camera near plane
        if (clip.w <= 0.0f) {
            return null;
        }

        float ndcX = clip.x / clip.w;
        float ndcY = clip.y / clip.w;

        // Outside NDC frustum
        if (Math.abs(ndcX) > 1.0f || Math.abs(ndcY) > 1.0f) {
            return null;
        }

        double scaledWidth = mc.getWindow().getScaledWidth();
        double scaledHeight = mc.getWindow().getScaledHeight();

        double screenX = (ndcX + 1.0) * 0.5 * scaledWidth;
        double screenY = (1.0 - ndcY) * 0.5 * scaledHeight;

        return new Vec3d(screenX, screenY, clip.z / clip.w);
    }
}
