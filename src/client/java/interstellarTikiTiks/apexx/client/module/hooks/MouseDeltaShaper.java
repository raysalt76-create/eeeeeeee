package interstellarTikiTiks.apexx.client.module.hooks;

/**
 * Reshapes the player's look input for one frame, after {@link MouseSensitivityModifier}.
 * Unlike a scalar sensitivity multiplier, a shaper may apply direction-dependent gain. It must
 * never create motion from zero input.
 */
public interface MouseDeltaShaper {
    /**
     * @param lookDelta {yaw, pitch} look change this frame in degrees, as vanilla is about to
     *                  apply it; modify in place
     */
    void shapeLookDelta(double[] lookDelta);
}
