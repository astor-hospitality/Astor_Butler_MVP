package museon_online.astor_butler.speech;

import java.util.Locale;

/**
 * Which speech-to-text backend a runtime uses. {@code cloudru} is the production default (paid cloud
 * model, no ML on the VM); {@code local} keeps the faster-whisper subprocess selectable for rollback.
 */
public enum SpeechToTextProvider {
    /** faster-whisper subprocess: {@code ASTOR_STT_COMMAND} in the bot, {@code glasses_stt.py} in the glasses runtime. */
    LOCAL("local"),
    /** Cloud.ru Evolution Foundation Models, {@code POST /v1/audio/transcriptions} with {@code openai/whisper-large-v3}. */
    CLOUDRU("cloudru");

    public static final String DEFAULT = "cloudru";

    private final String key;

    SpeechToTextProvider(String key) {
        this.key = key;
    }

    public String key() {
        return key;
    }

    /** @param variable the environment variable the value came from, named in the error so the fix is obvious. */
    public static SpeechToTextProvider parse(String value, String variable) {
        String normalized = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        for (SpeechToTextProvider provider : values()) {
            if (provider.key.equals(normalized)) {
                return provider;
            }
        }
        throw new IllegalStateException(variable + " must be local or cloudru, got '" + value + "'");
    }
}
