package museon_online.astor_butler.max.client;

/**
 * A failed MAX Bot API call. {@code status} is the HTTP status (0 when the API did not answer); {@code code} is the
 * API's own error code from the JSON body when there is one. Messages never contain the token.
 */
public class MaxApiException extends RuntimeException {

    private final int status;
    private final String code;

    public MaxApiException(int status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code == null ? "" : code;
    }

    public MaxApiException(int status, String message, Throwable cause) {
        super(message, cause);
        this.status = status;
        this.code = "";
    }

    public int status() {
        return status;
    }

    public String code() {
        return code;
    }

    /** 429 or 5xx: worth another attempt later; 4xx otherwise means the request itself is wrong. */
    public boolean retryable() {
        return status == 0 || status == 429 || status >= 500;
    }
}
