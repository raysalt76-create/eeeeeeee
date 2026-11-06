package interstellarTikiTiks.apexx.client.exception;

public class ApexxException extends RuntimeException {
    public ApexxException(String message) {
        super(message);
    }

    public ApexxException(String message, Throwable cause) {
        super(message, cause);
    }
}
