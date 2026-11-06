package interstellarTikiTiks.apexx.client.module.hooks;


public interface NotificationSink {

    void show(String title, String message, String type);

    default void success(String title, String message) {
        show(title, message, "SUCCESS");
    }

    default void warn(String title, String message) {
        show(title, message, "WARNING");
    }

    default void error(String title, String message) {
        show(title, message, "ERROR");
    }

    default void info(String title, String message) {
        show(title, message, "INFO");
    }
}
