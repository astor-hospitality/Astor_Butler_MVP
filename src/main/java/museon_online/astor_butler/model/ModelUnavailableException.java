package museon_online.astor_butler.model;

/** The model cannot be used now; the caller takes its fallback path. */
public class ModelUnavailableException extends RuntimeException {

    public ModelUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
