package museon_online.astor_butler.speech;

/**
 * A synthesis that did not produce audio. The message names the provider and the HTTP status, never
 * the key, the token or the text that was being read.
 */
public class TextToSpeechException extends RuntimeException {

    /** HTTP status the provider answered with, or 0 when the request never got an answer. */
    private final int status;

    public TextToSpeechException(String message) {
        this(message, 0, null);
    }

    public TextToSpeechException(String message, int status) {
        this(message, status, null);
    }

    public TextToSpeechException(String message, int status, Throwable cause) {
        super(message, cause);
        this.status = status;
    }

    public int status() {
        return status;
    }
}
