package museon_online.astor_butler.speech;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;

/**
 * Speech-to-text on Yandex SpeechKit API v1, synchronous recognition: {@code POST /speech/v1/stt:recognize
 * ?lang=ru-RU&format=oggopus} with {@code Authorization: Api-Key <key>} and the raw audio as the body; the answer
 * is {@code {"result": "..."}}. The key is a service account's, so no {@code folderId} is sent (SpeechKit takes
 * the folder from the account). Plain JDK {@link HttpClient}, no Spring.
 *
 * <p>Sync limits are 1 MB, 30 seconds and one channel. The size is checked before anything leaves the process;
 * the duration is the service's to refuse. Only Ogg Opus goes out, which is what Telegram voice notes are:
 * v1 also takes headerless LPCM, which no caller here produces, and nothing else, so other encodings are refused
 * locally; the glasses runtime transcodes its MP4/AAC to Ogg Opus with ffmpeg before calling this adapter.
 *
 * <p>Retries: none on 4xx (the audio will not get better), one more attempt after a 5xx answer or a timeout.
 * Every failure is a {@link SpeechToTextException} with the HTTP status (0 when the service did not answer or
 * the audio was refused before sending) and a message without the audio, the text or the key.
 */
public final class YandexSpeechKitSpeechToText {

    public static final String PROVIDER = "yandex";
    public static final String DEFAULT_ENDPOINT = "https://stt.api.cloud.yandex.net/speech/v1/stt:recognize";
    /** Documented sync limit: 1 MB per request (and 30 seconds of audio, which only the service can check). */
    public static final long MAX_SYNC_BYTES = 1024L * 1024;
    private static final int ERROR_SNIPPET = 200;
    /** Two-letter hints as {@code ASTOR_STT_LANGUAGE} has them, mapped to SpeechKit's language codes. */
    private static final Map<String, String> LOCALES = Map.of(
            "ru", "ru-RU", "en", "en-US", "kk", "kk-KZ", "uz", "uz-UZ", "tr", "tr-TR", "de", "de-DE");
    private static final ObjectMapper JSON = new ObjectMapper();

    /**
     * @param endpoint {@code YANDEX_SPEECHKIT_STT_ENDPOINT}; blank means {@link #DEFAULT_ENDPOINT}
     * @param apiKey   {@code Api-Key} of a service account with the {@code ai.speechkit-stt.user} role
     * @param language {@code ru}, {@code ru-RU}, ...; blank sends no hint (SpeechKit then assumes ru-RU)
     * @param timeout  whole request, upload included
     */
    public record Settings(String endpoint, String apiKey, String language, Duration timeout) {
        public Settings {
            endpoint = endpoint == null || endpoint.isBlank() ? DEFAULT_ENDPOINT : endpoint.trim();
            apiKey = apiKey == null ? "" : apiKey.trim();
            language = locale(language);
            timeout = timeout == null || timeout.isNegative() || timeout.isZero() ? Duration.ofSeconds(30) : timeout;
        }
    }

    private final HttpClient client;
    private final URI endpoint;
    private final String apiKey;
    private final String language;
    private final Duration timeout;

    public YandexSpeechKitSpeechToText(Settings settings) {
        this(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).followRedirects(HttpClient.Redirect.NEVER).build(),
                settings);
    }

    public YandexSpeechKitSpeechToText(HttpClient client, Settings settings) {
        this.client = client;
        this.apiKey = settings.apiKey();
        this.language = settings.language();
        this.timeout = settings.timeout();
        String url = settings.endpoint();
        String query = (language.isEmpty() ? "" : "lang=" + URLEncoder.encode(language, StandardCharsets.UTF_8) + "&")
                + "format=oggopus";
        this.endpoint = URI.create(url + (url.contains("?") ? "&" : "?") + query);
    }

    public URI endpoint() {
        return endpoint;
    }

    public String language() {
        return language;
    }

    public boolean configured() {
        return !apiKey.isEmpty();
    }

    /** Transcribes a file on disk; the encoding is read from the bytes, not from the extension. */
    public String transcribe(Path audioFile) {
        if (audioFile == null) {
            throw new SpeechToTextException(0, "Audio file is missing");
        }
        byte[] audio;
        try {
            long size = Files.size(audioFile);
            if (size > MAX_SYNC_BYTES) {
                throw tooLarge(size);
            }
            audio = Files.readAllBytes(audioFile);
        } catch (IOException e) {
            throw new SpeechToTextException(0, "Cannot read audio file: " + e.getClass().getSimpleName(), e);
        }
        return transcribe(audio);
    }

    /**
     * Transcribes in-memory Ogg Opus. Returns the trimmed transcript; an empty string means SpeechKit heard no
     * speech, which the caller decides how to report.
     */
    public String transcribe(byte[] audio) {
        if (!configured()) {
            throw new SpeechToTextException(0, "YANDEX_SPEECHKIT_API_KEY is not set; SpeechKit recognition cannot authenticate");
        }
        if (audio == null || audio.length == 0) {
            throw new SpeechToTextException(0, "Audio is empty");
        }
        if (audio.length > MAX_SYNC_BYTES) {
            throw tooLarge(audio.length);
        }
        if (!isOgg(audio)) {
            throw new SpeechToTextException(0, "SpeechKit sync recognition takes Ogg Opus; this audio is " + describe(audio)
                    + ", which it does not accept and which is not transcoded here");
        }
        HttpRequest request = HttpRequest.newBuilder(endpoint)
                .timeout(timeout)
                .header("Authorization", "Api-Key " + apiKey)
                .header("Accept", "application/json")
                .header("Content-Type", "application/octet-stream")
                .POST(HttpRequest.BodyPublishers.ofByteArray(audio))
                .build();
        for (int attempt = 1; ; attempt++) {
            boolean lastAttempt = attempt >= 2;
            try {
                HttpResponse<byte[]> response = client.send(request, HttpResponse.BodyHandlers.ofByteArray());
                int status = response.statusCode();
                if (status >= 200 && status < 300) {
                    return parse(response.body());
                }
                if (status >= 500 && !lastAttempt) {
                    continue;
                }
                throw new SpeechToTextException(status, "SpeechKit recognition answered HTTP " + status
                        + (status >= 500 ? " twice" : "") + snippet(response.body()));
            } catch (HttpTimeoutException e) {
                if (!lastAttempt) {
                    continue;
                }
                throw new SpeechToTextException(0, "SpeechKit recognition timed out twice after " + timeout.toMillis() + " ms", e);
            } catch (IOException e) {
                throw new SpeechToTextException(0, "SpeechKit recognition request failed: " + e.getClass().getSimpleName(), e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new SpeechToTextException(0, "SpeechKit recognition request interrupted", e);
            }
        }
    }

    private String parse(byte[] body) {
        JsonNode root;
        try {
            root = JSON.readTree(body == null ? new byte[0] : body);
        } catch (IOException e) {
            throw new SpeechToTextException(0, "SpeechKit recognition answered with a body that is not JSON", e);
        }
        JsonNode result = root == null ? null : root.get("result");
        if (result == null || !result.isTextual()) {
            throw new SpeechToTextException(0, "SpeechKit recognition answered without a \"result\" field");
        }
        return result.textValue().trim();
    }

    /** True when the bytes start with an Ogg page header, the only encoding {@link #transcribe(byte[])} sends. */
    public static boolean isOgg(byte[] audio) {
        return audio.length >= 4 && audio[0] == 'O' && audio[1] == 'g' && audio[2] == 'g' && audio[3] == 'S';
    }

    private static String describe(byte[] audio) {
        if (audio.length >= 8 && new String(audio, 4, 4, StandardCharsets.ISO_8859_1).equals("ftyp")) {
            return "an MP4/M4A (AAC) container";
        }
        if (audio.length >= 4 && new String(audio, 0, 4, StandardCharsets.ISO_8859_1).equals("RIFF")) return "a WAV file";
        if (audio.length >= 3 && (new String(audio, 0, 3, StandardCharsets.ISO_8859_1).equals("ID3")
                || ((audio[0] & 0xFF) == 0xFF && (audio[1] & 0xE0) == 0xE0))) {
            return "MP3";
        }
        return "an unrecognized encoding";
    }

    /** {@code ru} becomes {@code ru-RU}; full codes pass as they are; blank stays blank. */
    static String locale(String language) {
        String value = language == null ? "" : language.trim();
        if (value.isEmpty() || value.contains("-")) return value;
        return LOCALES.getOrDefault(value.toLowerCase(Locale.ROOT), value);
    }

    private static SpeechToTextException tooLarge(long size) {
        return new SpeechToTextException(0, "Audio is " + size + " bytes, above the SpeechKit sync limit of 1 MB ("
                + MAX_SYNC_BYTES + " bytes)");
    }

    private static String snippet(byte[] body) {
        if (body == null || body.length == 0) {
            return "";
        }
        String text = new String(body, 0, Math.min(body.length, ERROR_SNIPPET), StandardCharsets.UTF_8)
                .replaceAll("\\s+", " ").trim();
        return text.isEmpty() ? "" : ": " + text;
    }
}
