package museon_online.astor_butler.speech;

/**
 * Cloud.ru speech-to-text failed. {@link #status()} is the HTTP status the service answered with, or 0 when
 * the request never got an answer (configuration, timeout, transport) or the answer could not be read.
 * The message never contains the audio, the transcript or the API key.
 */
public class CloudRuWhisperException extends RuntimeException {

    private final int status;

    public CloudRuWhisperException(int status, String message) {
        super(message);
        this.status = status;
    }

    public CloudRuWhisperException(int status, String message, Throwable cause) {
        super(message, cause);
        this.status = status;
    }

    public int status() {
        return status;
    }

    /** True for 4xx answers: the request itself was rejected, so the same audio will not succeed on retry. */
    public boolean clientError() {
        return status >= 400 && status < 500;
    }
}
