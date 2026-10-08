package museon_online.astor_butler.speech;

/**
 * A cloud speech-to-text call failed. {@link #status()} is the HTTP status the service answered with, or 0 when
 * the request never got an answer (configuration, timeout, transport), the answer could not be read, or the
 * adapter refused the audio before sending it. The message never contains the audio, the transcript or a key.
 */
public class SpeechToTextException extends RuntimeException {

    private final int status;

    public SpeechToTextException(int status, String message) {
        super(message);
        this.status = status;
    }

    public SpeechToTextException(int status, String message, Throwable cause) {
        super(message, cause);
        this.status = status;
    }

    public int status() {
        return status;
    }
}
