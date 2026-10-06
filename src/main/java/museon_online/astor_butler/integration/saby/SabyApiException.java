package museon_online.astor_butler.integration.saby;

/**
 * Safe-to-log Saby call failure: carries the failure kind and HTTP status, never credentials or response bodies.
 */
public class SabyApiException extends RuntimeException {

    public enum Kind {
        TIMEOUT,
        HTTP_ERROR,
        AUTH_FAILED,
        INVALID_RESPONSE
    }

    private final Kind kind;
    private final int httpStatus;

    public SabyApiException(Kind kind, int httpStatus, String message) {
        super(message);
        this.kind = kind;
        this.httpStatus = httpStatus;
    }

    public Kind kind() {
        return kind;
    }

    public int httpStatus() {
        return httpStatus;
    }
}
