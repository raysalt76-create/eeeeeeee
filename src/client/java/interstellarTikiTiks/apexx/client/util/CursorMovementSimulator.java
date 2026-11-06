package interstellarTikiTiks.apexx.client.util;

import net.minecraft.client.MinecraftClient;
import org.lwjgl.glfw.GLFW;

/**
 * Moves the visible cursor inside the Minecraft window. Call {@link #moveToScaled} from the
 * client thread on each frame while a GUI target is active; no worker or sleep is involved.
 * Each instance owns its motion state so a caller can cancel it when its screen or target changes.
 */
public final class CursorMovementSimulator {
    private static final double MAX_FRAME_SECONDS = 0.05;
    private static final long PAUSE_RESET_NANOS = 250_000_000L;
    private static final double FIRST_FRAME_SECONDS = 1.0 / 60.0;
    private final double[] cursorX = new double[1];
    private final double[] cursorY = new double[1];

    private long windowHandle;
    private long lastUpdateNanos;
    private int scaledWidth;
    private int scaledHeight;
    private int windowWidth;
    private int windowHeight;
    private double velocityX;
    private double velocityY;

    /** Drops velocity and timing; the next move starts from the cursor's current position. */
    public void cancel() {
        this.windowHandle = 0L;
        this.lastUpdateNanos = 0L;
        this.velocityX = 0.0;
        this.velocityY = 0.0;
    }

    /**
     * Advances toward a Minecraft screen coordinate and returns true only after a cursor readback
     * is within one window pixel of the destination. Speed is the existing 1..100 GUI setting.
     * A caller that needs a hovered slot must also check the screen's focused slot before acting.
     */
    public boolean moveToScaled(int scaledWidth, int scaledHeight, double targetX, double targetY,
                                double speed, String mode) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc == null || mc.getWindow() == null || scaledWidth <= 0 || scaledHeight <= 0
                || !Double.isFinite(targetX) || !Double.isFinite(targetY)) {
            this.cancel();
            return false;
        }
        long handle = mc.getWindow().getHandle();
        if (handle == 0L || GLFW.glfwGetWindowAttrib(handle, GLFW.GLFW_FOCUSED) == GLFW.GLFW_FALSE) {
            this.cancel();
            return false;
        }
        int windowWidth = mc.getWindow().getWidth();
        int windowHeight = mc.getWindow().getHeight();
        if (windowWidth <= 0 || windowHeight <= 0) {
            this.cancel();
            return false;
        }
        if (this.windowHandle != handle || this.scaledWidth != scaledWidth || this.scaledHeight != scaledHeight
                || this.windowWidth != windowWidth || this.windowHeight != windowHeight) {
            this.cancel();
            this.windowHandle = handle;
            this.scaledWidth = scaledWidth;
            this.scaledHeight = scaledHeight;
            this.windowWidth = windowWidth;
            this.windowHeight = windowHeight;
        }

        if (GlfwPlatform.isWayland()) {
            // The pointer cannot be warped on Wayland: no glide, report arrival (as Instant would after one frame).
            return true;
        }

        targetX = Math.max(0.0, Math.min(scaledWidth - 1.0, targetX));
        targetY = Math.max(0.0, Math.min(scaledHeight - 1.0, targetY));
        GLFW.glfwGetCursorPos(handle, this.cursorX, this.cursorY);
        double currentX = this.cursorX[0] * scaledWidth / windowWidth;
        double currentY = this.cursorY[0] * scaledHeight / windowHeight;
        double dx = targetX - currentX;
        double dy = targetY - currentY;
        double toleranceX = Math.max(0.5, (double) scaledWidth / windowWidth);
        double toleranceY = Math.max(0.5, (double) scaledHeight / windowHeight);
        if (Math.abs(dx) <= toleranceX && Math.abs(dy) <= toleranceY) {
            this.velocityX = 0.0;
            this.velocityY = 0.0;
            return true;
        }

        if ("Instant".equalsIgnoreCase(mode)) {
            this.velocityX = 0.0;
            this.velocityY = 0.0;
            this.lastUpdateNanos = 0L;
            this.setCursor(handle, targetX, targetY);
            return false;
        }

        long now = System.nanoTime();
        double dt = this.lastUpdateNanos == 0L || now - this.lastUpdateNanos > PAUSE_RESET_NANOS
                ? FIRST_FRAME_SECONDS
                : Math.min(MAX_FRAME_SECONDS, Math.max(0L, now - this.lastUpdateNanos) * 1.0E-9);
        if (this.lastUpdateNanos != 0L && now - this.lastUpdateNanos > PAUSE_RESET_NANOS) {
            this.velocityX = 0.0;
            this.velocityY = 0.0;
        }
        if (dt < 0.001) {
            return false;
        }
        this.lastUpdateNanos = now;

        // A critically damped second-order controller gives a quick start and a smooth stop.
        // Work in scaled GUI pixels so the saved speed has the same feel at different GUI scales.
        double setting = Double.isFinite(speed) ? Math.max(1.0, Math.min(100.0, speed)) : 18.0;
        double distance = Math.hypot(dx, dy);
        double heuristic = "Heuristic".equalsIgnoreCase(mode)
                ? 1.0 + 0.45 * Math.max(0.0, Math.min(1.0, (distance - 40.0) / 160.0))
                : 1.0;
        double omega = (14.0 + 0.38 * setting) * heuristic;
        double maxSpeed = (1500.0 + 60.0 * setting) * heuristic;
        double maxAcceleration = (60000.0 + 2000.0 * setting) * heuristic;
        // Keep the integration stable at high speed or after a slow frame. Intermediate steps
        // update only the controller state; GLFW still receives just one move per rendered frame.
        int steps = Math.max(1, (int) Math.ceil(dt / Math.min(1.0 / 120.0, 0.25 / omega)));
        double stepTime = dt / steps;
        double nextX = currentX;
        double nextY = currentY;
        for (int i = 0; i < steps; i++) {
            double errorX = targetX - nextX;
            double errorY = targetY - nextY;
            double accelerationX = omega * omega * errorX - 2.0 * omega * this.velocityX;
            double accelerationY = omega * omega * errorY - 2.0 * omega * this.velocityY;
            double acceleration = Math.hypot(accelerationX, accelerationY);
            if (acceleration > maxAcceleration) {
                accelerationX *= maxAcceleration / acceleration;
                accelerationY *= maxAcceleration / acceleration;
            }
            this.velocityX += accelerationX * stepTime;
            this.velocityY += accelerationY * stepTime;
            double velocity = Math.hypot(this.velocityX, this.velocityY);
            if (velocity > maxSpeed) {
                this.velocityX *= maxSpeed / velocity;
                this.velocityY *= maxSpeed / velocity;
            }
            double movedX = nextX + this.velocityX * stepTime;
            double movedY = nextY + this.velocityY * stepTime;
            if (errorX > 0.0 && movedX > targetX || errorX < 0.0 && movedX < targetX) {
                movedX = targetX;
                this.velocityX = 0.0;
            }
            if (errorY > 0.0 && movedY > targetY || errorY < 0.0 && movedY < targetY) {
                movedY = targetY;
                this.velocityY = 0.0;
            }
            nextX = movedX;
            nextY = movedY;
        }
        this.setCursor(handle, nextX, nextY);
        return false;
    }

    private void setCursor(long handle, double x, double y) {
        GLFW.glfwSetCursorPos(handle, x * this.windowWidth / this.scaledWidth,
                y * this.windowHeight / this.scaledHeight);
    }
}
