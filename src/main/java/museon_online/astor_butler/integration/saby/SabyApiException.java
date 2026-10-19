package museon_online.astor_butler.integration.saby;

/**
 * Safe-to-log Saby call failure: carries the failure kind and HTTP status, never credentials or response bodies.
 * {@link #errorShape()} tells what the error answer held by field names and code values only ({@link SabyErrorShape}).
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
    private final String errorShape;

    public SabyApiException(Kind kind, int httpStatus, String message) {
        this(kind, httpStatus, message, "");
    }

    public SabyApiException(Kind kind, int httpStatus, String message, String errorShape) {
        super(message);
        this.kind = kind;
        this.httpStatus = httpStatus;
        this.errorShape = errorShape == null ? "" : errorShape;
    }

    public Kind kind() {
        return kind;
    }

    public int httpStatus() {
        return httpStatus;
    }

    /** Field names and code values of Saby's error answer, without free text; blank when there was no answer. */
    public String errorShape() {
        return errorShape;
    }
}
