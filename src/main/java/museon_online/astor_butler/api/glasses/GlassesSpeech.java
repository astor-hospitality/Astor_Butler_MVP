package museon_online.astor_butler.api.glasses;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Astor's own voice: the answer is synthesized on the server instead of by whatever voice the phone
 * happens to have. One Russian voice, chosen in the server environment. Off by default; when it is
 * off, misconfigured or failing, the caller gets no audio and the phone reads the text itself.
 *
 * Yandex SpeechKit, API v1 (`POST /speech/v1/tts:synthesize`, form-encoded), as the service documents it:
 * a service-account API key in `Authorization: Api-Key …`, no `folderId` with a service account (the
 * folder is the one the account lives in), `emotion` for the tone where the voice supports one, `speed`
 * 0.1–3.0, `format=mp3`, text up to 5000 characters in a body of at most 15 KB.
 *
 * The text leaves for the speech provider, so this is only for what Astor says — never guest data the
 * assistant was not going to speak aloud anyway. Nothing is logged.
 */
@Component
public class GlassesSpeech {
    static final int TEXT_LIMIT = 600;
    static final int AUDIO_LIMIT = 2 * 1024 * 1024;
    static final String DEFAULT_VOICE = "filipp";

    /** Russian voices SpeechKit serves through API v1, with the emotions each one accepts. */
    static final Map<String, List<String>> V1_VOICES = Map.of(
            "filipp", List.of(),
            "ermil", List.of("neutral", "good"),
            "zahar", List.of("neutral", "good"),
            "madi_ru", List.of(),
            "jane", List.of("neutral", "good", "evil"),
            "omazh", List.of("neutral", "evil"),
            "marina", List.of("neutral", "whisper", "friendly"));
    static final List<String> MALE_VOICES = List.of("filipp", "ermil", "zahar", "madi_ru");

    private static final Logger log = LoggerFactory.getLogger(GlassesSpeech.class);
    private final boolean enabled;
    private final URI endpoint;
    private final String apiKey;
    private final String voice;
    private final String emotion;      // empty when the voice has none
    private final String speed;        // already clamped and formatted for the form
    private final String misconfiguration; // null when the settings make sense
    private final HttpClient client;
    private volatile Instant readyUntil = Instant.MIN;

    @Autowired
    public GlassesSpeech(@Value("${astor.glasses.tts-enabled:false}") boolean enabled,
                         @Value("${astor.glasses.tts-endpoint:https://tts.api.cloud.yandex.net/speech/v1/tts:synthesize}") String endpoint,
                         @Value("${astor.glasses.tts-api-key:}") String apiKey,
                         @Value("${astor.glasses.tts-voice:filipp}") String voice,
                         @Value("${astor.glasses.tts-emotion:}") String emotion,
                         @Value("${astor.glasses.tts-speed:0.95}") double speed) {
        this(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3))
                .followRedirects(HttpClient.Redirect.NEVER).build(), enabled, endpoint, apiKey, voice, emotion, speed);
    }

    GlassesSpeech(HttpClient client, boolean enabled, String voice) {
        this(client, enabled, "https://tts.api.cloud.yandex.net/speech/v1/tts:synthesize", "unit-key", voice, "", 0.95);
    }

    GlassesSpeech(HttpClient client, boolean enabled, String voice, String emotion) {
        this(client, enabled, "https://tts.api.cloud.yandex.net/speech/v1/tts:synthesize", "unit-key", voice, emotion, 0.95);
    }

    private GlassesSpeech(HttpClient client, boolean enabled, String endpoint, String apiKey, String voice,
                          String emotion, double speed) {
        this.client = client;
        this.enabled = enabled;
        this.endpoint = URI.create(endpoint);
        this.apiKey = apiKey == null ? "" : apiKey.strip();
        this.voice = voice == null ? "" : voice.strip().toLowerCase(Locale.ROOT);
        String tone = emotion == null ? "" : emotion.strip().toLowerCase(Locale.ROOT);
        this.speed = String.format(Locale.ROOT, "%.2f", Math.max(0.1, Math.min(3.0, speed)));

        String problem = null;
        List<String> emotions = V1_VOICES.get(this.voice);
        if (this.voice.isEmpty()) problem = "no voice";
        else if (emotions == null) problem = "voice is not served by SpeechKit API v1: " + this.voice;
        else if (!tone.isEmpty() && !emotions.contains(tone)) problem = "voice " + this.voice + " has no emotion " + tone;
        if (!this.apiKey.isEmpty() && this.apiKey.length() < 20) problem = "API key looks truncated";
        this.emotion = problem == null ? tone : "";
        this.misconfiguration = problem;
        if (enabled && problem != null) {
            // Said once at startup, without the key: a wrong voice should not be a silent fallback forever.
            log.warn("Server speech stays off: {}", problem);
        }
    }

    public static GlassesSpeech disabled() { return new GlassesSpeech(HttpClient.newHttpClient(), false, DEFAULT_VOICE); }

    boolean configured() { return enabled && !apiKey.isEmpty() && misconfiguration == null; }
    boolean ready() { return configured() && Instant.now().isBefore(readyUntil); }
    String voiceName() { return voice; }
    String misconfiguration() { return misconfiguration; }

    /** MP3 in the configured voice, or null when speech is off, the text does not fit, or the provider fails. */
    byte[] synthesize(String text) {
        if (!configured() || text == null) return null;
        String trimmed = text.strip();
        if (trimmed.isEmpty() || trimmed.codePointCount(0, trimmed.length()) > TEXT_LIMIT) return null;
        try {
            var form = new StringBuilder("text=").append(encode(trimmed))
                    .append("&lang=ru-RU&voice=").append(encode(voice))
                    .append("&speed=").append(speed)
                    .append("&format=mp3");
            if (!emotion.isEmpty()) form.append("&emotion=").append(encode(emotion));
            var request = HttpRequest.newBuilder(endpoint).timeout(Duration.ofSeconds(8))
                    .header("Authorization", "Api-Key " + apiKey)
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString(form.toString(), StandardCharsets.UTF_8)).build();
            var response = client.send(request, HttpResponse.BodyHandlers.ofByteArray());
            byte[] audio = response.body();
            if (response.statusCode() != 200 || audio == null || audio.length == 0 || audio.length > AUDIO_LIMIT) {
                readyUntil = Instant.MIN;
                return null;
            }
            readyUntil = Instant.now().plusSeconds(300);
            return audio;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            readyUntil = Instant.MIN;
            return null;
        } catch (Exception e) {
            // A speech failure is never an assist failure: the phone reads the text instead. No diagnostics leak.
            readyUntil = Instant.MIN;
            return null;
        }
    }

    private static String encode(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8); }
}
