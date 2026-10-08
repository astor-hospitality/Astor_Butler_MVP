package museon_online.astor_butler.speech;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Yandex SpeechKit TTS v1, the request the glasses runtime has sent since the pilot: one form-encoded POST
 * with the API key in the header, audio back. Selected with {@code *_TTS_PROVIDER=yandex}. The format is
 * {@code mp3} unless set ({@code YANDEX_TTS_FORMAT} in the bot): {@code oggopus} is what Telegram plays as a
 * voice note. The glasses keep MP3, so their request is unchanged.
 */
public final class SpeechKitTextToSpeech implements TextToSpeech {

    public static final String PROVIDER = "yandex";
    public static final String DEFAULT_ENDPOINT = "https://tts.api.cloud.yandex.net/speech/v1/tts:synthesize";
    public static final String DEFAULT_VOICE = "filipp";
    public static final String DEFAULT_ROLE = "";
    public static final double DEFAULT_SPEED = 0.95;
    public static final String DEFAULT_FORMAT = "mp3";
    /** SpeechKit v1 output formats this adapter offers, with the MIME type of what comes back. */
    private static final Map<String, String> MIME_BY_FORMAT = Map.of("mp3", "audio/mpeg", "oggopus", "audio/ogg");
    static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(3);
    static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(8);
    /** Yandex premium voices that read in a male register; everything else is reported as female. */
    private static final Set<String> MALE_VOICES = Set.of("filipp", "ermil", "zahar", "madi_ru");
    private static final java.util.Map<String, Set<String>> V1_VOICES = java.util.Map.of(
            "filipp", Set.of(), "ermil", Set.of("neutral", "good"),
            "zahar", Set.of("neutral", "good"), "madi_ru", Set.of(),
            "jane", Set.of("neutral", "good", "evil"), "omazh", Set.of("neutral", "evil"),
            "marina", Set.of("neutral", "whisper", "friendly"));

    /**
     * @param endpoint SpeechKit synthesize URL
     * @param apiKey   {@code Api-Key} of a service account with the synthesis role
     * @param folder   Yandex Cloud folder id billed for the call
     * @param voice    SpeechKit voice name
     * @param role     emotional role supported by that voice
     * @param speed    0.5..2.0
     * @param format   {@code mp3} (default) or {@code oggopus} ({@code opus}/{@code ogg} accepted); anything else
     *                 fails here, at startup, rather than at the first spoken line
     */
    public record Settings(String endpoint, String apiKey, String folder, String voice, String role, double speed,
                           String format) {
        public Settings {
            endpoint = blankToDefault(endpoint, DEFAULT_ENDPOINT);
            apiKey = nullToEmpty(apiKey).trim();
            folder = nullToEmpty(folder).trim();
            voice = blankToDefault(voice, DEFAULT_VOICE);
            role = blankToDefault(role, DEFAULT_ROLE);
            speed = Math.max(0.1, Math.min(3.0, speed));
            format = normalizeFormat(format);
        }

        /** MP3, as the glasses have always asked for. */
        public Settings(String endpoint, String apiKey, String folder, String voice, String role, double speed) {
            this(endpoint, apiKey, folder, voice, role, speed, DEFAULT_FORMAT);
        }
    }

    private final HttpClient client;
    private final URI endpoint;
    private final String apiKey;
    private final String folder;
    private final String voice;
    private final String role;
    private final double speed;
    private final String format;

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
        this.format = settings.format();
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
        return MIME_BY_FORMAT.get(format);
    }

    @Override
    public boolean configured() {
        Set<String> emotions = V1_VOICES.get(voice);
        return !apiKey.isBlank() && emotions != null && (role.isBlank() || emotions.contains(role));
    }

    @Override
    public byte[] synthesize(String text) {
        if (!configured()) {
            throw new TextToSpeechException("SpeechKit TTS is not configured: API key, folder and voice are required");
        }
        String form = "text=" + encode(text) + "&lang=ru-RU&voice=" + encode(voice)
                + "&speed=" + speed + "&format=" + format;
        if (!role.isBlank()) form += "&emotion=" + encode(role);
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

    static String normalizeFormat(String format) {
        String name = format == null || format.isBlank() ? DEFAULT_FORMAT : format.trim().toLowerCase(Locale.ROOT);
        if (name.equals("opus") || name.equals("ogg")) name = "oggopus";
        if (!MIME_BY_FORMAT.containsKey(name)) {
            throw new IllegalStateException("YANDEX_TTS_FORMAT must be mp3 or oggopus, got '" + format + "'");
        }
        return name;
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
