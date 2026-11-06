package interstellarTikiTiks.apexx.client.exception;

public class IntegrityException extends ApexxException {
    public IntegrityException(String message) {
        super(message);
    }

    public IntegrityException(String message, Throwable cause) {
        super(message, cause);
    }
}
