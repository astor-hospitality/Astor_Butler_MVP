package museon_online.astor_butler.api.glasses;

import museon_online.astor_butler.speech.SaluteSpeechTextToSpeech;
import museon_online.astor_butler.speech.SpeechKitTextToSpeech;
import museon_online.astor_butler.speech.TextToSpeech;
import museon_online.astor_butler.speech.TextToSpeechProviders;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.http.HttpClient;
import java.time.Duration;
import java.time.Instant;

/**
 * Astor's own voice: the answer is synthesized on the server instead of by whatever voice the phone
 * happens to have. One premium voice, chosen in the server environment. Off by default; when it
 * is off or fails, the caller simply gets no audio and the phone reads the text itself.
 *
 * The provider is {@code ASTOR_GLASSES_TTS_PROVIDER}: {@code yandex} (SpeechKit, the pilot's original
 * voice and the rollback, {@code ASTOR_GLASSES_TTS_*}) or {@code salute} (Sber SaluteSpeech, {@code SALUTE_*}).
 * The text leaves for the speech provider, so this is only for what Astor says — never guest data
 * the assistant was not going to speak aloud anyway. Nothing is logged.
 */
@Component
public class GlassesSpeech {
    static final int TEXT_LIMIT = 600;
    /** 600 characters of 24 kHz 16-bit WAV is about 2 MiB; MP3 and Opus are far smaller. */
    static final int AUDIO_LIMIT = 4 * 1024 * 1024;
    private final boolean enabled;
    private final TextToSpeech speech;
    private volatile Instant readyUntil = Instant.MIN;

    @Autowired
    public GlassesSpeech(@Value("${astor.glasses.tts-enabled:false}") boolean enabled,
                         @Value("${astor.glasses.tts-provider:yandex}") String provider,
                         @Value("${astor.glasses.tts-endpoint:}") String endpoint,
                         @Value("${astor.glasses.tts-api-key:}") String apiKey,
                         @Value("${astor.glasses.tts-folder:}") String folder,
                         @Value("${astor.glasses.tts-voice:}") String voice,
                         @Value("${astor.glasses.tts-emotion:${astor.glasses.tts-role:}}") String role,
                         @Value("${astor.glasses.tts-speed:0.95}") double speed,
                         @Value("${salute.auth-key:}") String saluteAuthKey,
                         @Value("${salute.scope:}") String saluteScope,
                         @Value("${salute.tts-voice:}") String saluteVoice,
                         @Value("${salute.tts-format:}") String saluteFormat,
                         @Value("${salute.ca-cert-path:${gigachat.ca-cert-path:}}") String saluteCaCertPath,
                         @Value("${salute.oauth-url:}") String saluteOauthUrl,
                         @Value("${salute.tts-url:}") String saluteTtsUrl,
                         @Value("${salute.tts-timeout-ms:10000}") int saluteTimeoutMs) {
        this(enabled, TextToSpeechProviders.select(provider,
                new SpeechKitTextToSpeech.Settings(endpoint, apiKey, folder, voice, role, speed),
                new SaluteSpeechTextToSpeech.Settings(saluteAuthKey, saluteScope, saluteVoice, saluteFormat, saluteCaCertPath,
                        saluteOauthUrl, saluteTtsUrl, Duration.ofMillis(saluteTimeoutMs))));
    }

    GlassesSpeech(boolean enabled, TextToSpeech speech) {
        this.enabled = enabled;
        this.speech = speech;
    }

    /** The pilot's SpeechKit request on the given client, for tests of the glasses contract. */
    GlassesSpeech(HttpClient client, boolean enabled, String voice) {
        this(enabled, new SpeechKitTextToSpeech(client,
                new SpeechKitTextToSpeech.Settings(null, "unit-key", "unit-folder", voice, null, 0.95)));
    }

    public static GlassesSpeech disabled() { return new GlassesSpeech(HttpClient.newHttpClient(), false, "filipp"); }

    boolean configured() { return enabled && speech.configured() && !speech.voice().isBlank(); }
    boolean ready() { return configured() && Instant.now().isBefore(readyUntil); }
    String provider() { return speech.provider(); }
    String voiceName() { return speech.voice(); }
    String voiceGender() { return speech.voiceGender(); }
    /** MIME type of what {@link #synthesize} returns: {@code audio/mpeg} for SpeechKit, by format for SaluteSpeech. */
    String mimeType() { return speech.mimeType(); }

    /** Audio of the server voice, or null when speech is off, the text does not fit, or the provider fails. */
    byte[] synthesize(String text) {
        if (!configured() || text == null) return null;
        String trimmed = text.strip();
        if (trimmed.isEmpty() || trimmed.codePointCount(0, trimmed.length()) > TEXT_LIMIT) return null;
        try {
            byte[] audio = speech.synthesize(trimmed);
            if (audio == null || audio.length == 0 || audio.length > AUDIO_LIMIT) {
                readyUntil = Instant.MIN;
                return null;
            }
            readyUntil = Instant.now().plusSeconds(300);
            return audio;
        } catch (RuntimeException e) {
            // A speech failure is never an assist failure: the phone reads the text instead. No diagnostics leak.
            readyUntil = Instant.MIN;
            return null;
        }
    }
}
