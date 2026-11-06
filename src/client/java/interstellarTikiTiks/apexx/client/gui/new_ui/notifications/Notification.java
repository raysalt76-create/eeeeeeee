package interstellarTikiTiks.apexx.client.gui.new_ui.notifications;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;

public class Notification {

    public enum NotificationType {
        SUCCESS,
        WARNING,
        ERROR,
        INFO
    }

    private final String title;
    private final String message;
    private final NotificationType type;
    private final long creationTime;
    private final long duration;

    private final double width;
    private final double height;

    private static final double ANIMATION_TIME = 400.0;

    public Notification(String title, String message, NotificationType type, long duration) {
        this.title = title;
        this.message = message;
        this.type = type;
        this.duration = duration;
        this.creationTime = System.currentTimeMillis();

        TextRenderer textRenderer = MinecraftClient.getInstance().textRenderer;

        int titleWidth = textRenderer.getWidth(title);
        int messageWidth = textRenderer.getWidth(message);
        int maxTextWidth = Math.max(titleWidth, messageWidth);

        this.width = 40 + maxTextWidth + 24;

        this.height = message.isEmpty() ? 40 : 56;
    }

    public Notification(String title, NotificationType type, long duration) {
        this(title, "", type, duration);
    }

    public boolean isExpired() {
        return System.currentTimeMillis() > creationTime + duration + (ANIMATION_TIME * 2);
    }

    public double getAniProg() {
        long currentTime = System.currentTimeMillis();
        long timeSinceCreation = currentTime - creationTime;

        if (timeSinceCreation < ANIMATION_TIME) {

            double linear = (double) timeSinceCreation / ANIMATION_TIME;
            return easeOutCubic(linear);
        } else if (currentTime > creationTime + duration + ANIMATION_TIME) {

            long timeIntoFadeOut = (long) (currentTime - (creationTime + duration + ANIMATION_TIME));
            double linear = 1.0 - Math.min((double) timeIntoFadeOut / ANIMATION_TIME, 1.0);
            return easeInCubic(linear);
        } else {

            return 1.0;
        }
    }

    private double easeOutCubic(double x) {
        return 1 - Math.pow(1 - x, 3);
    }

    private double easeInCubic(double x) {
        return Math.pow(x, 3);
    }

    public String getTitle() {
        return title;
    }

    public String getMessage() {
        return message;
    }

    public NotificationType getType() {
        return type;
    }

    public double getWidth() {
        return width;
    }

    public double getHeight() {
        return height;
    }

    public long getCreationTime() {
        return creationTime;
    }

    public long getDuration() {
        return duration;
    }
}
