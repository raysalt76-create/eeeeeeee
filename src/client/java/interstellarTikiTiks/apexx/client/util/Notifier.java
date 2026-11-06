package interstellarTikiTiks.apexx.client.util;

import interstellarTikiTiks.apexx.client.module.hooks.NotificationSink;

public final class Notifier {

    private Notifier() {
    }

    public static void show(String title, String message, String type) {
        NotificationSink notificationSink = ModuleAccess.getEnabledHook(NotificationSink.class);
        if (notificationSink != null) {
            notificationSink.show(title, message, type);
        }
    }

    public static void success(String title, String message) {
        NotificationSink notificationSink = ModuleAccess.getEnabledHook(NotificationSink.class);
        if (notificationSink != null) {
            notificationSink.success(title, message);
        }
    }

    public static void warn(String title, String message) {
        NotificationSink notificationSink = ModuleAccess.getEnabledHook(NotificationSink.class);
        if (notificationSink != null) {
            notificationSink.warn(title, message);
        }
    }

    public static void error(String title, String message) {
        NotificationSink notificationSink = ModuleAccess.getEnabledHook(NotificationSink.class);
        if (notificationSink != null) {
            notificationSink.error(title, message);
        }
    }

    public static void info(String title, String message) {
        NotificationSink notificationSink = ModuleAccess.getEnabledHook(NotificationSink.class);
        if (notificationSink != null) {
            notificationSink.info(title, message);
        }
    }
}
