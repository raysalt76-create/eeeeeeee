package interstellarTikiTiks.apexx.client.util.render;

import org.joml.Matrix4f;
import org.joml.Vector3f;

/**
 * Where a held item's blade is during the vanilla swing animation, rebuilt from the transforms vanilla
 * applies (1.21.11): {@code HeldItemRenderer} (first person) and {@code BipedEntityModel.animateArms} plus
 * {@code HeldItemFeatureRenderer} and the handheld item display transforms (third person).
 *
 * <p>The blade is the item's texture diagonal: {@code bladeT = 0} is the pommel corner, {@code 1} the tip.
 * Results are an approximation of the rendered model (hand sway, view bobbing and walk animation are
 * ignored), good enough to anchor a fading trail. Pure math, unit tested.</p>
 */
public final class SwingTipModel {
    private static final float DEG = (float) (Math.PI / 180.0);
    private static final float PI = (float) Math.PI;
    /** Player model scale applied by the player renderer. */
    public static final float PLAYER_SCALE = 0.9375f;

    private SwingTipModel() {
    }

    private static void bladePoint(float bladeT, Vector3f out) {
        // Centered item-model space: the texture spans -0.5..0.5; a sword's pommel sits near the bottom-left
        // corner and its tip at the top-right one.
        float t = TrailMath.clamp01(bladeT);
        float c = -0.42f + 0.92f * t;
        out.set(c, c, 0.0f);
    }

    /**
     * Blade point in first-person view space (x right, y up, -z forward), as drawn with the hand
     * projection. Scale x and y by {@code tan(worldFov/2) / tan(handFov/2)} to place it under the world projection.
     */
    public static Vector3f firstPerson(float swing, boolean rightArm, float bladeT, Matrix4f scratch, Vector3f out) {
        float s = TrailMath.clamp01(swing);
        float i = rightArm ? 1.0f : -1.0f;
        float sq = (float) Math.sqrt(s);
        Matrix4f m = scratch.identity();
        // renderFirstPersonItem: swing translation, equip offset (fully equipped), swing rotation.
        m.translate(i * -0.4f * (float) Math.sin(sq * PI), 0.2f * (float) Math.sin(sq * PI * 2.0f),
                -0.2f * (float) Math.sin(s * PI));
        m.translate(i * 0.56f, -0.52f, -0.72f);
        float f = (float) Math.sin(s * s * PI);
        m.rotateY(i * (45.0f + f * -20.0f) * DEG);
        float g = (float) Math.sin(sq * PI);
        m.rotateZ(i * g * -20.0f * DEG);
        m.rotateX(g * -80.0f * DEG);
        m.rotateY(i * -45.0f * DEG);
        // firstperson_righthand display transform (mirrored for the left hand).
        m.translate(i * 1.13f / 16.0f, 3.2f / 16.0f, 1.13f / 16.0f);
        m.rotateXYZ(0.0f, i * -90.0f * DEG, i * 25.0f * DEG);
        m.scale(0.68f);
        bladePoint(bladeT, out);
        return m.transformPosition(out);
    }

    /**
     * Blade point relative to the player's feet in the body frame (x = body right, y = up, z = body forward),
     * in blocks, for the third-person player model.
     *
     * @param headPitchRad head pitch in radians (positive looks down)
     */
    public static Vector3f thirdPerson(float swing, boolean rightArm, float headPitchRad, boolean sneaking,
                                       float bladeT, Matrix4f scratch, Vector3f out) {
        float s = TrailMath.clamp01(swing);
        float sq = (float) Math.sqrt(s);
        float bodyYaw = (float) Math.sin(sq * PI * 2.0f) * 0.2f;
        if (!rightArm) bodyYaw = -bodyYaw;

        // Model space: x = model left (right arm at -5 px), y down, -z forward. Pivots in pixels.
        float pivotX = rightArm ? -(float) Math.cos(bodyYaw) * 5.0f : (float) Math.cos(bodyYaw) * 5.0f;
        float pivotZ = rightArm ? (float) Math.sin(bodyYaw) * 5.0f : -(float) Math.sin(bodyYaw) * 5.0f;
        float pivotY = sneaking ? 5.2f : 2.0f;
        pivotX += rightArm ? 0.5f : -0.5f; // PlayerEntityModel.setArmAngle shift

        float pitch = -PI / 10.0f + (sneaking ? 0.4f : 0.0f); // holding-item arm pose
        float yaw = bodyYaw;
        float roll = 0.0f;
        if (!rightArm) pitch += bodyYaw;
        float g = 1.0f - s;
        g *= g;
        g *= g;
        g = 1.0f - g;
        float h = (float) Math.sin(g * PI);
        float k = (float) Math.sin(s * PI) * -(headPitchRad - 0.7f) * 0.75f;
        pitch -= h * 1.2f + k;
        yaw += bodyYaw * 2.0f;
        roll += (float) Math.sin(s * PI) * -0.4f;

        float side = rightArm ? 1.0f : -1.0f;
        Matrix4f m = scratch.identity();
        m.translate(pivotX / 16.0f, pivotY / 16.0f, pivotZ / 16.0f);
        m.rotateZYX(roll, yaw, pitch);
        // HeldItemFeatureRenderer
        m.rotateX(-90.0f * DEG);
        m.rotateY(180.0f * DEG);
        m.translate(side / 16.0f, 0.125f, -0.625f);
        // thirdperson_righthand handheld display transform (mirrored for the left hand)
        m.translate(0.0f, 4.0f / 16.0f, 0.5f / 16.0f);
        m.rotateXYZ(0.0f, side * -90.0f * DEG, side * 55.0f * DEG);
        m.scale(0.85f);
        bladePoint(bladeT, out);
        m.transformPosition(out);

        // Model -> body frame: the renderer flips x and y, lifts by 1.501 and the body faces model -z.
        float x = -out.x;
        float y = 1.501f - out.y;
        float z = -out.z;
        out.set(x * PLAYER_SCALE, y * PLAYER_SCALE - (sneaking ? 0.125f : 0.0f), z * PLAYER_SCALE);
        return out;
    }

    /** Factor for view-space x/y so a hand-projection point lands at the same screen spot under the world FOV. */
    public static float fovCorrection(float handFovDeg, float worldFovDeg) {
        double hand = Math.tan(Math.toRadians(Math.max(1.0f, Math.min(179.0f, handFovDeg))) * 0.5);
        double world = Math.tan(Math.toRadians(Math.max(1.0f, Math.min(179.0f, worldFovDeg))) * 0.5);
        return (float) (world / hand);
    }
}
