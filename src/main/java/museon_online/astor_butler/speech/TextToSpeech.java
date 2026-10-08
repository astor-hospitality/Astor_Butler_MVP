package museon_online.astor_butler.speech;

/**
 * One line of text in, audio bytes out. The port behind every place Astor speaks with its own
 * server voice: the glasses runtime ({@code GlassesSpeech}), the CLIO web chat
 * ({@code POST /api/chat/speak}) and, when they appear, Telegram voice replies.
 *
 * <p>Adapters are paid cloud voices only ({@link SpeechKitTextToSpeech} for Yandex SpeechKit,
 * {@link SaluteSpeechTextToSpeech} for Sber SaluteSpeech); no local model ever runs here. Which one is
 * used is a deployment decision, {@code ASTOR_TTS_PROVIDER} / {@code ASTOR_GLASSES_TTS_PROVIDER}, see
 * {@link TextToSpeechProviders}. The text that is passed in leaves for the provider, so callers only
 * send what Astor was going to say aloud anyway; nothing here logs it.
 */
public interface TextToSpeech {

    /** {@code yandex} or {@code salute}: the name the operator selected. */
    String provider();

    /** The provider's own voice identifier, e.g. {@code filipp} or {@code Nec_24000}. */
    String voice();

    /** {@code male} or {@code female}, as the client reports it next to the audio. */
    String voiceGender();

    /** MIME type of the bytes {@link #synthesize} returns, e.g. {@code audio/mpeg} or {@code audio/wav}. */
    String mimeType();

    /** True when every credential the provider needs is present; false means every call would fail. */
    boolean configured();

    /**
     * Audio for one line, never null or empty.
     *
     * @throws TextToSpeechException when the provider is not configured, rejects the request or is unreachable
     */
    byte[] synthesize(String text);
}
