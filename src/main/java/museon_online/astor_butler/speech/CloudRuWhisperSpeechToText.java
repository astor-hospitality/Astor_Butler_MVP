package museon_online.astor_butler.speech;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.UUID;

/**
 * Speech-to-text on Cloud.ru Evolution Foundation Models: {@code POST {base}/audio/transcriptions}, the
 * OpenAI-compatible multipart endpoint, with {@code openai/whisper-large-v3} by default. Plain JDK
 * {@link HttpClient}, no Spring, so the Telegram bot and the isolated glasses runtime share it.
 *
 * <p>Retries: none on 4xx (the request is wrong, the audio will not get better), one retry on a 5xx
 * answer or a timeout. Every failure is a {@link CloudRuWhisperException} with the HTTP status (0 when
 * the service did not answer) and a message that names the cause without the audio, the text or the key.
 */
public final class CloudRuWhisperSpeechToText {

    public static final String DEFAULT_BASE_URL = "https://foundation-models.api.cloud.ru/v1";
    public static final String DEFAULT_MODEL = "openai/whisper-large-v3";
    /** Documented Cloud.ru limit for one upload; checked before anything leaves the process. */
    public static final long MAX_FILE_BYTES = 25L * 1024 * 1024;
    static final String TRANSCRIPTIONS_PATH = "/audio/transcriptions";
    private static final int ERROR_SNIPPET = 200;

    private final HttpClient client;
    private final URI endpoint;
    private final String apiKey;
    private final String keyVariable;
    private final String model;
    private final String language;
    private final Duration timeout;
    private final ObjectMapper mapper = new ObjectMapper();

    /**
     * @param baseUrl     {@code CLOUDRU_BASE_URL}; blank means {@link #DEFAULT_BASE_URL}
     * @param apiKey      bearer key from the Cloud.ru console; checked on every call, not at construction
     * @param keyVariable the variable the key comes from ({@code CLOUDRU_API_KEY} or the glasses one), for the error message
     * @param model       {@code openai/whisper-large-v3} unless the catalogue says otherwise
     * @param language    ISO-639-1 hint ({@code ru}); blank lets the model detect the language
     * @param timeout     whole request, upload included
     */
    public CloudRuWhisperSpeechToText(String baseUrl, String apiKey, String keyVariable, String model, String language,
                                      Duration timeout) {
        this(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).followRedirects(HttpClient.Redirect.NEVER).build(),
                baseUrl, apiKey, keyVariable, model, language, timeout);
    }

    CloudRuWhisperSpeechToText(HttpClient client, String baseUrl, String apiKey, String keyVariable, String model,
                               String language, Duration timeout) {
        this.client = client;
        String base = baseUrl == null || baseUrl.isBlank() ? DEFAULT_BASE_URL : baseUrl.trim();
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        this.endpoint = URI.create(base + TRANSCRIPTIONS_PATH);
        this.apiKey = apiKey == null ? "" : apiKey.trim();
        this.keyVariable = keyVariable == null || keyVariable.isBlank() ? "CLOUDRU_API_KEY" : keyVariable;
        this.model = model == null || model.isBlank() ? DEFAULT_MODEL : model.trim();
        this.language = language == null ? "" : language.trim();
        this.timeout = timeout == null || timeout.isZero() || timeout.isNegative() ? Duration.ofSeconds(30) : timeout;
    }

    public URI endpoint() {
        return endpoint;
    }

    public String model() {
        return model;
    }

    public String language() {
        return language;
    }

    public boolean configured() {
        return !apiKey.isEmpty();
    }

    /** Transcribes a file on disk; the file name and extension tell the service the container (ogg, mp3, m4a...). */
    public String transcribe(Path audioFile) {
        if (audioFile == null) {
            throw new CloudRuWhisperException(0, "Audio file is missing");
        }
        byte[] audio;
        try {
            if (Files.size(audioFile) > MAX_FILE_BYTES) {
                throw tooLarge(Files.size(audioFile));
            }
            audio = Files.readAllBytes(audioFile);
        } catch (IOException e) {
            throw new CloudRuWhisperException(0, "Cannot read audio file: " + e.getClass().getSimpleName(), e);
        }
        return transcribe(audio, audioFile.getFileName().toString(), contentType(audioFile.getFileName().toString()));
    }

    /**
     * Transcribes in-memory audio. Returns the trimmed transcript; an empty string means the service heard no
     * speech, which the caller decides how to report.
     */
    public String transcribe(byte[] audio, String fileName, String contentType) {
        if (!configured()) {
            throw new CloudRuWhisperException(0, keyVariable + " is not set; Cloud.ru speech-to-text cannot authenticate");
        }
        if (audio == null || audio.length == 0) {
            throw new CloudRuWhisperException(0, "Audio is empty");
        }
        if (audio.length > MAX_FILE_BYTES) {
            throw tooLarge(audio.length);
        }
        String boundary = "astor-" + UUID.randomUUID();
        byte[] body = multipart(boundary, audio, fileName == null || fileName.isBlank() ? "audio" : fileName,
                contentType == null || contentType.isBlank() ? "application/octet-stream" : contentType);
        HttpRequest request = HttpRequest.newBuilder(endpoint)
                .timeout(timeout)
                .header("Authorization", "Bearer " + apiKey)
                .header("Accept", "application/json")
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body))
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
                throw new CloudRuWhisperException(status, "Cloud.ru speech-to-text answered HTTP " + status
                        + (status >= 500 ? " twice" : "") + snippet(response.body()));
            } catch (HttpTimeoutException e) {
                if (!lastAttempt) {
                    continue;
                }
                throw new CloudRuWhisperException(0, "Cloud.ru speech-to-text timed out twice after " + timeout.toMillis() + " ms", e);
            } catch (IOException e) {
                throw new CloudRuWhisperException(0, "Cloud.ru speech-to-text request failed: " + e.getClass().getSimpleName(), e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new CloudRuWhisperException(0, "Cloud.ru speech-to-text request interrupted", e);
            }
        }
    }

    private String parse(byte[] body) {
        try {
            JsonNode root = mapper.readTree(body == null ? new byte[0] : body);
            JsonNode text = root == null ? null : root.get("text");
            if (text == null || !text.isTextual()) {
                throw new CloudRuWhisperException(0, "Cloud.ru speech-to-text answered without a \"text\" field");
            }
            return text.textValue().trim();
        } catch (IOException e) {
            throw new CloudRuWhisperException(0, "Cloud.ru speech-to-text answered with a body that is not JSON", e);
        }
    }

    private byte[] multipart(String boundary, byte[] audio, String fileName, String contentType) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(audio.length + 512);
        field(out, boundary, "model", model);
        if (!language.isEmpty()) {
            field(out, boundary, "language", language);
        }
        field(out, boundary, "response_format", "json");
        write(out, "--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"file\"; filename=\"" + fileName.replace("\"", "_") + "\"\r\n"
                + "Content-Type: " + contentType + "\r\n\r\n");
        out.writeBytes(audio);
        write(out, "\r\n--" + boundary + "--\r\n");
        return out.toByteArray();
    }

    private static void field(ByteArrayOutputStream out, String boundary, String name, String value) {
        write(out, "--" + boundary + "\r\nContent-Disposition: form-data; name=\"" + name + "\"\r\n\r\n" + value + "\r\n");
    }

    private static void write(ByteArrayOutputStream out, String text) {
        out.writeBytes(text.getBytes(StandardCharsets.UTF_8));
    }

    private static CloudRuWhisperException tooLarge(long size) {
        return new CloudRuWhisperException(0, "Audio is " + size + " bytes, above the Cloud.ru limit of 25 MB (" + MAX_FILE_BYTES + " bytes)");
    }

    private static String snippet(byte[] body) {
        if (body == null || body.length == 0) {
            return "";
        }
        String text = new String(body, 0, Math.min(body.length, ERROR_SNIPPET), StandardCharsets.UTF_8)
                .replaceAll("\\s+", " ").trim();
        return text.isEmpty() ? "" : ": " + text;
    }

    static String contentType(String fileName) {
        String lower = fileName == null ? "" : fileName.toLowerCase(java.util.Locale.ROOT);
        if (lower.endsWith(".ogg") || lower.endsWith(".oga") || lower.endsWith(".opus")) return "audio/ogg";
        if (lower.endsWith(".mp3")) return "audio/mpeg";
        if (lower.endsWith(".wav")) return "audio/wav";
        if (lower.endsWith(".m4a") || lower.endsWith(".mp4")) return "audio/mp4";
        if (lower.endsWith(".webm")) return "audio/webm";
        if (lower.endsWith(".flac")) return "audio/flac";
        return "application/octet-stream";
    }
}
