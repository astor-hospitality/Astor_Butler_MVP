package museon_online.astor_butler.speech;

import java.util.Locale;

/**
 * The one place that turns a provider name into an adapter, shared by the Spring application
 * ({@code ASTOR_TTS_PROVIDER}) and the isolated glasses runtime ({@code ASTOR_GLASSES_TTS_PROVIDER}).
 * {@code salute} is the production voice, {@code yandex} the rollback; anything else is a misspelt
 * deployment and fails at startup rather than at the first spoken line.
 */
public final class TextToSpeechProviders {

    public static final String YANDEX = SpeechKitTextToSpeech.PROVIDER;
    public static final String SALUTE = SaluteSpeechTextToSpeech.PROVIDER;

    private TextToSpeechProviders() {
    }

    public static String normalize(String provider) {
        String name = provider == null ? "" : provider.trim().toLowerCase(Locale.ROOT);
        if (!name.equals(YANDEX) && !name.equals(SALUTE)) {
            throw new IllegalStateException("TTS provider must be " + YANDEX + " or " + SALUTE + ", got '" + name + "'");
        }
        return name;
    }

    public static TextToSpeech select(String provider, SpeechKitTextToSpeech.Settings yandex, SaluteSpeechTextToSpeech.Settings salute) {
        return switch (normalize(provider)) {
            case SALUTE -> new SaluteSpeechTextToSpeech(salute);
            default -> new SpeechKitTextToSpeech(yandex);
        };
    }
}
