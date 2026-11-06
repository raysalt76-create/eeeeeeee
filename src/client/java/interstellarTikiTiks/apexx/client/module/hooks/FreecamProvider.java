package interstellarTikiTiks.apexx.client.module.hooks;

import net.minecraft.util.math.Vec3d;

/**
 * Implemented by the Freecam module. The camera and mouse seams ask it where the detached camera is
 * and to turn it instead of the player.
 */
public interface FreecamProvider {

    /** The camera (eye) position for this frame, interpolated by {@code tickDelta}. */
    Vec3d getFreecamPosition(float tickDelta);

    /** The camera look {yaw, pitch} for this frame, interpolated by {@code tickDelta}. */
    float[] getFreecamRotation(float tickDelta);

    /**
     * Mouse look: turns the camera instead of the player. Same units as the entity's own turn.
     *
     * @return true when consumed (the player must not turn)
     */
    boolean turnFreecam(double cursorDeltaX, double cursorDeltaY);
}
