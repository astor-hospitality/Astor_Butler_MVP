package museon_online.astor_butler.api.glasses;

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

/**
 * Astor's own voice: the answer is synthesized on the server instead of by whatever voice the phone
 * happens to have. One male premium voice, chosen in the server environment. Off by default; when it
 * is off or fails, the caller simply gets no audio and the phone reads the text itself.
 *
 * The text leaves for the speech provider, so this is only for what Astor says — never guest data
 * the assistant was not going to speak aloud anyway. Nothing is logged.
 */
@Component
public class GlassesSpeech {
    static final int TEXT_LIMIT = 600;
    static final int AUDIO_LIMIT = 2 * 1024 * 1024;
    private final boolean enabled;
    private final URI endpoint;
    private final String apiKey;
    private final String folder;
    private final String voice;
    private final String role;
    private final double speed;
    private final HttpClient client;
    private volatile Instant readyUntil = Instant.MIN;

    public GlassesSpeech(@Value("${astor.glasses.tts-enabled:false}") boolean enabled,
                         @Value("${astor.glasses.tts-endpoint:https://tts.api.cloud.yandex.net/speech/v1/tts:synthesize}") String endpoint,
                         @Value("${astor.glasses.tts-api-key:}") String apiKey,
                         @Value("${astor.glasses.tts-folder:}") String folder,
                         @Value("${astor.glasses.tts-voice:filipp}") String voice,
                         @Value("${astor.glasses.tts-role:neutral}") String role,
                         @Value("${astor.glasses.tts-speed:0.95}") double speed) {
        this.enabled = enabled;
        this.endpoint = URI.create(endpoint);
        this.apiKey = apiKey;
        this.folder = folder;
        this.voice = voice;
        this.role = role;
        this.speed = Math.max(0.5, Math.min(2.0, speed));
        this.client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3))
                .followRedirects(HttpClient.Redirect.NEVER).build();
    }

    GlassesSpeech(HttpClient client, boolean enabled, String voice) {
        this.enabled = enabled;
        this.endpoint = URI.create("https://tts.api.cloud.yandex.net/speech/v1/tts:synthesize");
        this.apiKey = "unit-key";
        this.folder = "unit-folder";
        this.voice = voice;
        this.role = "neutral";
        this.speed = 0.95;
        this.client = client;
    }

    public static GlassesSpeech disabled() { return new GlassesSpeech(HttpClient.newHttpClient(), false, "filipp"); }

    boolean configured() { return enabled && !apiKey.isBlank() && !folder.isBlank() && !voice.isBlank(); }
    boolean ready() { return configured() && Instant.now().isBefore(readyUntil); }
    String voiceName() { return voice; }

    /** MP3 of a male premium voice, or null when speech is off, the text does not fit, or the provider fails. */
    byte[] synthesize(String text) {
        if (!configured() || text == null) return null;
        String trimmed = text.strip();
        if (trimmed.isEmpty() || trimmed.codePointCount(0, trimmed.length()) > TEXT_LIMIT) return null;
        try {
            String form = "text=" + encode(trimmed) + "&lang=ru-RU&voice=" + encode(voice) + "&role=" + encode(role)
                    + "&speed=" + speed + "&format=mp3&folderId=" + encode(folder);
            var request = HttpRequest.newBuilder(endpoint).timeout(Duration.ofSeconds(8))
                    .header("Authorization", "Api-Key " + apiKey)
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString(form, StandardCharsets.UTF_8)).build();
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
