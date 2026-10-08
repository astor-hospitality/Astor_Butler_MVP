package museon_online.astor_butler.speech;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Locale;
import java.util.Set;

/**
 * Yandex SpeechKit TTS v1, exactly the request the glasses runtime has sent since the pilot: one
 * form-encoded POST with the API key in the header, MP3 back. Kept as the rollback provider
 * ({@code *_TTS_PROVIDER=yandex}); the behaviour is unchanged on purpose.
 */
public final class SpeechKitTextToSpeech implements TextToSpeech {

    public static final String PROVIDER = "yandex";
    public static final String DEFAULT_ENDPOINT = "https://tts.api.cloud.yandex.net/speech/v1/tts:synthesize";
    public static final String DEFAULT_VOICE = "filipp";
    public static final String DEFAULT_ROLE = "neutral";
    public static final double DEFAULT_SPEED = 0.95;
    static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(3);
    static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(8);
    /** Yandex premium voices that read in a male register; everything else is reported as female. */
    private static final Set<String> MALE_VOICES = Set.of("filipp", "ermil", "zahar", "madirus", "kirill", "anton", "lev", "oleg");

    /**
     * @param endpoint SpeechKit synthesize URL
     * @param apiKey   {@code Api-Key} of a service account with the synthesis role
     * @param folder   Yandex Cloud folder id billed for the call
     * @param voice    SpeechKit voice name
     * @param role     emotional role supported by that voice
     * @param speed    0.5..2.0
     */
    public record Settings(String endpoint, String apiKey, String folder, String voice, String role, double speed) {
        public Settings {
            endpoint = blankToDefault(endpoint, DEFAULT_ENDPOINT);
            apiKey = nullToEmpty(apiKey).trim();
            folder = nullToEmpty(folder).trim();
            voice = blankToDefault(voice, DEFAULT_VOICE);
            role = blankToDefault(role, DEFAULT_ROLE);
            speed = Math.max(0.5, Math.min(2.0, speed));
        }
    }

    private final HttpClient client;
    private final URI endpoint;
    private final String apiKey;
    private final String folder;
    private final String voice;
    private final String role;
    private final double speed;

    public SpeechKitTextToSpeech(Settings settings) {
        this(HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).followRedirects(HttpClient.Redirect.NEVER).build(),
                settings);
    }

    public SpeechKitTextToSpeech(HttpClient client, Settings settings) {
        this.client = client;
        this.endpoint = URI.create(settings.endpoint());
        this.apiKey = settings.apiKey();
        this.folder = settings.folder();
        this.voice = settings.voice();
        this.role = settings.role();
        this.speed = settings.speed();
    }

    @Override
    public String provider() {
        return PROVIDER;
    }

    @Override
    public String voice() {
        return voice;
    }

    @Override
    public String voiceGender() {
        return MALE_VOICES.contains(voice.toLowerCase(Locale.ROOT)) ? "male" : "female";
    }

    @Override
    public String mimeType() {
        return "audio/mpeg";
    }

    @Override
    public boolean configured() {
        return !apiKey.isBlank() && !folder.isBlank() && !voice.isBlank();
    }

    @Override
    public byte[] synthesize(String text) {
        if (!configured()) {
            throw new TextToSpeechException("SpeechKit TTS is not configured: API key, folder and voice are required");
        }
        String form = "text=" + encode(text) + "&lang=ru-RU&voice=" + encode(voice) + "&role=" + encode(role)
                + "&speed=" + speed + "&format=mp3&folderId=" + encode(folder);
        HttpRequest request = HttpRequest.newBuilder(endpoint).timeout(REQUEST_TIMEOUT)
                .header("Authorization", "Api-Key " + apiKey)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form, StandardCharsets.UTF_8)).build();
        HttpResponse<byte[]> response;
        try {
            response = client.send(request, HttpResponse.BodyHandlers.ofByteArray());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new TextToSpeechException("SpeechKit TTS call was interrupted", 0, e);
        } catch (Exception e) {
            throw new TextToSpeechException("SpeechKit TTS is unreachable", 0, e);
        }
        byte[] audio = response.body();
        if (response.statusCode() != 200) {
            throw new TextToSpeechException("SpeechKit TTS answered " + response.statusCode(), response.statusCode());
        }
        if (audio == null || audio.length == 0) {
            throw new TextToSpeechException("SpeechKit TTS returned no audio", 200);
        }
        return audio;
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private static String blankToDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }
}
