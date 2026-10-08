package museon_online.astor_butler.speech;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import museon_online.astor_butler.model.GigaChatTrust;

import javax.net.ssl.SSLContext;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Sber SaluteSpeech synthesis over REST. The production voice of the stack.
 *
 * <p>Access tokens come from the same NGW OAuth gateway GigaChat uses: {@code Authorization: Basic
 * <SALUTE_AUTH_KEY>}, a fresh {@code RqUID}, body {@code scope=SALUTE_SPEECH_PERS|_B2B|_CORP}. A token
 * lives 30 minutes; it is cached, renewed a minute before it expires, and once more when the speech
 * endpoint answers 401. The speech call is {@code POST /rest/v1/text:synthesize?format=..&voice=..}
 * with the text as the body ({@code application/text}, or {@code application/ssml} when it is an SSML
 * document) and the audio bytes as the answer.
 *
 * <p>Both hosts are signed by the Russian Trusted Root CA; {@code SALUTE_CA_CERT_PATH} (or the GigaChat
 * one) adds that PEM to the JVM trust through {@link GigaChatTrust}. TLS verification is never relaxed.
 */
public final class SaluteSpeechTextToSpeech implements TextToSpeech {

    public static final String PROVIDER = "salute";
    public static final String DEFAULT_OAUTH_URL = "https://ngw.devices.sberbank.ru:9443/api/v2/oauth";
    public static final String DEFAULT_TTS_URL = "https://smartspeech.sber.ru/rest/v1/text:synthesize";
    public static final String DEFAULT_SCOPE = "SALUTE_SPEECH_PERS";
    public static final String DEFAULT_VOICE = "Nec_24000";
    public static final String DEFAULT_FORMAT = "wav16";
    public static final int DEFAULT_TIMEOUT_MS = 10_000;
    /** SaluteSpeech refuses a body longer than this, SSML markup included. */
    public static final int TEXT_LIMIT = 4000;
    /** Tokens are renewed this long before SaluteSpeech says they expire. */
    static final Duration TOKEN_REFRESH_MARGIN = Duration.ofSeconds(60);
    static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(3);
    /** Voices read in a male register: Борис, Тарас, Сергей. Наталья, Марфа, Александра, Kira are female. */
    private static final Set<String> MALE_VOICE_PREFIXES = Set.of("bys", "tur", "pon");
    private static final Map<String, String> MIME_BY_FORMAT = Map.of(
            "wav16", "audio/wav",
            "pcm16", "audio/x-pcm",
            "opus", "audio/ogg",
            "alaw", "audio/x-alaw"
    );
    private static final ObjectMapper JSON = new ObjectMapper();

    /**
     * @param authKey    Base64 authorization key of the SaluteSpeech project (developers.sber.ru)
     * @param scope      {@code SALUTE_SPEECH_PERS}, {@code SALUTE_SPEECH_B2B} or {@code SALUTE_SPEECH_CORP}
     * @param voice      e.g. {@code Nec_24000}, {@code Bys_24000}; 8 kHz variants end in {@code _8000}
     * @param format     {@code wav16}, {@code pcm16}, {@code opus} or {@code alaw}
     * @param caCertPath PEM with the Russian Trusted Root CA chain, or blank when the JVM already trusts it
     * @param oauthUrl   token endpoint
     * @param ttsUrl     synthesis endpoint, without query
     * @param timeout    read timeout of one call
     */
    public record Settings(String authKey, String scope, String voice, String format, String caCertPath,
                           String oauthUrl, String ttsUrl, Duration timeout) {
        public Settings {
            authKey = nullToEmpty(authKey).trim();
            scope = blankToDefault(scope, DEFAULT_SCOPE);
            voice = blankToDefault(voice, DEFAULT_VOICE);
            format = blankToDefault(format, DEFAULT_FORMAT).toLowerCase(Locale.ROOT);
            caCertPath = nullToEmpty(caCertPath).trim();
            oauthUrl = blankToDefault(oauthUrl, DEFAULT_OAUTH_URL);
            ttsUrl = blankToDefault(ttsUrl, DEFAULT_TTS_URL);
            timeout = timeout == null || timeout.isNegative() || timeout.isZero() ? Duration.ofMillis(DEFAULT_TIMEOUT_MS) : timeout;
        }
    }

    private final HttpClient client;
    private final URI oauthUrl;
    private final URI ttsUrl;
    private final String authKey;
    private final String scope;
    private final String voice;
    private final String format;
    private final Duration timeout;
    private final Object tokenLock = new Object();
    private volatile AccessToken token;

    /** Production constructor: its own JDK client, with the CA bundle from {@code caCertPath} when one is set. */
    public SaluteSpeechTextToSpeech(Settings settings) {
        this(httpClient(settings.caCertPath()), settings);
    }

    public SaluteSpeechTextToSpeech(HttpClient client, Settings settings) {
        this.client = client;
        this.oauthUrl = URI.create(settings.oauthUrl());
        this.authKey = settings.authKey();
        this.scope = settings.scope();
        this.voice = settings.voice();
        this.format = settings.format();
        this.timeout = settings.timeout();
        this.ttsUrl = URI.create(settings.ttsUrl() + (settings.ttsUrl().contains("?") ? "&" : "?")
                + "format=" + encode(format) + "&voice=" + encode(voice));
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
        String prefix = voice.toLowerCase(Locale.ROOT);
        int underscore = prefix.indexOf('_');
        if (underscore > 0) prefix = prefix.substring(0, underscore);
        return MALE_VOICE_PREFIXES.contains(prefix) ? "male" : "female";
    }

    @Override
    public String mimeType() {
        return MIME_BY_FORMAT.getOrDefault(format, "application/octet-stream");
    }

    @Override
    public boolean configured() {
        return !authKey.isBlank();
    }

    /** The synthesis URL including {@code format} and {@code voice}; what the stub in tests sees. */
    URI synthesisUri() {
        return ttsUrl;
    }

    @Override
    public byte[] synthesize(String text) {
        if (!configured()) {
            throw new TextToSpeechException("SaluteSpeech is not configured: set SALUTE_AUTH_KEY");
        }
        if (text == null || text.isBlank()) {
            throw new TextToSpeechException("SaluteSpeech needs a non-empty line");
        }
        if (text.codePointCount(0, text.length()) > TEXT_LIMIT) {
            throw new TextToSpeechException("SaluteSpeech reads at most " + TEXT_LIMIT + " characters per call");
        }
        HttpResponse<byte[]> response = speak(text, accessToken(false));
        if (response.statusCode() == 401) {
            // The token was revoked or expired early: one fresh token, one more attempt, then give up.
            response = speak(text, accessToken(true));
        }
        if (response.statusCode() != 200) {
            throw new TextToSpeechException("SaluteSpeech synthesis answered " + response.statusCode(), response.statusCode());
        }
        byte[] audio = response.body();
        if (audio == null || audio.length == 0) {
            throw new TextToSpeechException("SaluteSpeech returned no audio", 200);
        }
        return audio;
    }

    private HttpResponse<byte[]> speak(String text, String bearer) {
        String contentType = isSsml(text) ? "application/ssml" : "application/text";
        HttpRequest request = HttpRequest.newBuilder(ttsUrl).timeout(timeout)
                .header("Authorization", "Bearer " + bearer)
                .header("Content-Type", contentType)
                .POST(HttpRequest.BodyPublishers.ofString(text, StandardCharsets.UTF_8)).build();
        return send(request, "synthesis");
    }

    private String accessToken(boolean forceRefresh) {
        AccessToken current = token;
        if (!forceRefresh && current != null && current.usableAt(Instant.now())) {
            return current.value();
        }
        synchronized (tokenLock) {
            current = token;
            if (!forceRefresh && current != null && current.usableAt(Instant.now())) {
                return current.value();
            }
            AccessToken fresh = requestToken();
            token = fresh;
            return fresh.value();
        }
    }

    private AccessToken requestToken() {
        HttpRequest request = HttpRequest.newBuilder(oauthUrl).timeout(timeout)
                .header("Authorization", "Basic " + authKey)
                .header("RqUID", UUID.randomUUID().toString())
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("scope=" + encode(scope), StandardCharsets.UTF_8)).build();
        HttpResponse<byte[]> response = send(request, "OAuth");
        if (response.statusCode() == 401) {
            throw new TextToSpeechException("SaluteSpeech OAuth rejected the authorization key (401): "
                    + "check SALUTE_AUTH_KEY and that SALUTE_SCOPE matches the contract", 401);
        }
        if (response.statusCode() != 200) {
            throw new TextToSpeechException("SaluteSpeech OAuth answered " + response.statusCode(), response.statusCode());
        }
        JsonNode body;
        try {
            body = JSON.readTree(response.body() == null ? new byte[0] : response.body());
        } catch (Exception e) {
            throw new TextToSpeechException("SaluteSpeech OAuth response is not JSON", 200, e);
        }
        JsonNode accessToken = body == null ? null : body.get("access_token");
        if (accessToken == null || !accessToken.isTextual() || accessToken.textValue().isBlank()) {
            throw new TextToSpeechException("SaluteSpeech OAuth response has no access_token", 200);
        }
        JsonNode expiresAt = body.get("expires_at");
        Instant expiry = expiresAt != null && expiresAt.isNumber()
                ? Instant.ofEpochMilli(expiresAt.longValue())
                : Instant.now().plus(Duration.ofMinutes(25));
        return new AccessToken(accessToken.textValue(), expiry);
    }

    private HttpResponse<byte[]> send(HttpRequest request, String what) {
        try {
            return client.send(request, HttpResponse.BodyHandlers.ofByteArray());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new TextToSpeechException("SaluteSpeech " + what + " call was interrupted", 0, e);
        } catch (Exception e) {
            throw new TextToSpeechException("SaluteSpeech " + what + " endpoint is unreachable", 0, e);
        }
    }

    private static boolean isSsml(String text) {
        return text.stripLeading().startsWith("<speak");
    }

    private static HttpClient httpClient(String caCertPath) {
        HttpClient.Builder builder = HttpClient.newBuilder()
                .connectTimeout(CONNECT_TIMEOUT)
                .followRedirects(HttpClient.Redirect.NEVER);
        if (!caCertPath.isBlank()) {
            Path path = Path.of(caCertPath);
            if (!Files.isReadable(path)) {
                throw new IllegalStateException("SALUTE_CA_CERT_PATH is not a readable file: " + path);
            }
            try {
                SSLContext sslContext = GigaChatTrust.sslContext(path);
                builder.sslContext(sslContext);
            } catch (Exception e) {
                throw new IllegalStateException("SALUTE_CA_CERT_PATH could not be loaded: " + path, e);
            }
        }
        return builder.build();
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

    record AccessToken(String value, Instant expiresAt) {
        boolean usableAt(Instant now) {
            return now.plus(TOKEN_REFRESH_MARGIN).isBefore(expiresAt);
        }
    }
}
