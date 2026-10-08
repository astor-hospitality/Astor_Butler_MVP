package museon_online.astor_butler.model;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.restclient.RestTemplateBuilder;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;

import javax.net.ssl.SSLContext;
import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Sber GigaChat API, called directly: OAuth2 access tokens (about 30 minutes) from the NGW gateway,
 * then {@code /chat/completions}, {@code /embeddings} and {@code /files} on the GigaChat host.
 *
 * <p>Selected with {@code ASTOR_MODEL_PROVIDER=gigachat}. {@code GIGACHAT_AUTH_KEY} is the Base64
 * authorization key from the GigaChat API cabinet, sent as {@code Authorization: Basic}; the scope
 * ({@code GIGACHAT_API_PERS}, {@code GIGACHAT_API_B2B} or {@code GIGACHAT_API_CORP}) must match the
 * contract the key was issued for. Sber endpoints are signed by the Russian Trusted Root CA, which
 * {@code GIGACHAT_CA_CERT_PATH} adds to the JVM trust (see {@link GigaChatTrust}); TLS is never relaxed.
 *
 * <p>Images go through GigaChat's file store: the photo is uploaded once and attached to the chat
 * message by id, as the API has no inline data URLs. GigaChat has no {@code response_format}; prompts
 * that expect JSON rely on their own instructions.
 *
 * <p>Outside Spring (the isolated glasses runtime) the explicit constructor builds the same client; it can
 * bound photo answers separately from text answers.
 */
@Slf4j
@Service
@ConditionalOnProperty(prefix = "astor.model", name = "provider", havingValue = "gigachat", matchIfMissing = false)
public class GigaChatModelGateway implements ModelGateway {

    static final String PROVIDER = "gigachat";
    static final String DEFAULT_OAUTH_URL = "https://ngw.devices.sberbank.ru:9443/api/v2/oauth";
    static final String DEFAULT_BASE_URL = "https://gigachat.devices.sberbank.ru/api/v1";
    /** Tokens are refreshed this long before GigaChat says they expire. */
    static final Duration TOKEN_REFRESH_MARGIN = Duration.ofSeconds(60);

    private final RestTemplate restTemplate;
    private final String oauthUrl;
    private final String baseUrl;
    private final String authKey;
    private final String scope;
    private final String frontlineModel;
    private final String qualityModel;
    private final String visionModel;
    private final String embeddingModel;
    private final int maxTokens;
    private final int visionMaxTokens;
    private final double temperature;
    private final Object tokenLock = new Object();
    private volatile AccessToken token;

    @Autowired
    public GigaChatModelGateway(
            RestTemplateBuilder restTemplateBuilder,
            @Value("${astor.model.gigachat.oauth-url:" + DEFAULT_OAUTH_URL + "}") String oauthUrl,
            @Value("${astor.model.gigachat.base-url:" + DEFAULT_BASE_URL + "}") String baseUrl,
            @Value("${astor.model.gigachat.auth-key:}") String authKey,
            @Value("${astor.model.gigachat.scope:GIGACHAT_API_PERS}") String scope,
            @Value("${astor.model.gigachat.model:GigaChat}") String frontlineModel,
            @Value("${astor.model.gigachat.quality-model:}") String qualityModel,
            @Value("${astor.model.gigachat.vision-model:}") String visionModel,
            @Value("${astor.model.gigachat.embedding-model:Embeddings}") String embeddingModel,
            @Value("${astor.model.gigachat.ca-cert-path:}") String caCertPath,
            @Value("${astor.model.gigachat.timeout-ms:15000}") int timeoutMs,
            @Value("${astor.model.gigachat.max-tokens:256}") int maxTokens,
            @Value("${astor.model.gigachat.temperature:0.1}") double temperature
    ) {
        this(restTemplateBuilder, oauthUrl, baseUrl, authKey, scope, frontlineModel, qualityModel, visionModel, embeddingModel,
                caCertPath, timeoutMs, maxTokens, maxTokens, temperature);
    }

    /**
     * Explicit settings for a runtime without Spring Boot configuration. Blank optional models switch the
     * capability off as in the Spring constructor; a negative {@code temperature} is not sent.
     *
     * @param visionMaxTokens {@code max_tokens} of photo answers; text answers use {@code maxTokens}
     */
    public GigaChatModelGateway(RestTemplateBuilder restTemplateBuilder, String oauthUrl, String baseUrl, String authKey,
                                String scope, String frontlineModel, String qualityModel, String visionModel,
                                String embeddingModel, String caCertPath, int timeoutMs, int maxTokens, int visionMaxTokens,
                                double temperature) {
        Duration timeout = Duration.ofMillis(Math.max(1, timeoutMs));
        // The JDK client carries both timeouts itself; the builder's own timeout settings do not apply to it.
        this.restTemplate = restTemplateBuilder
                .requestFactory(() -> requestFactory(blankToNull(caCertPath), timeout))
                .build();
        this.oauthUrl = blankToNull(oauthUrl) == null ? DEFAULT_OAUTH_URL : oauthUrl.trim();
        this.baseUrl = (blankToNull(baseUrl) == null ? DEFAULT_BASE_URL : baseUrl.trim()).replaceFirst("/+$", "");
        this.authKey = blankToNull(authKey);
        this.scope = blankToNull(scope) == null ? "GIGACHAT_API_PERS" : scope.trim();
        this.frontlineModel = blankToNull(frontlineModel) == null ? "GigaChat" : frontlineModel.trim();
        this.qualityModel = blankToNull(qualityModel);
        this.visionModel = blankToNull(visionModel);
        this.embeddingModel = blankToNull(embeddingModel);
        this.maxTokens = Math.max(1, maxTokens);
        this.visionMaxTokens = Math.max(1, visionMaxTokens);
        this.temperature = temperature;

        if (this.authKey == null) {
            log.error("Model provider {} is selected but not configured, every model call will fail: missing GIGACHAT_AUTH_KEY",
                    PROVIDER);
        }
        if (blankToNull(caCertPath) == null) {
            log.warn("GIGACHAT_CA_CERT_PATH is not set: Sber endpoints are trusted only if the Russian Trusted Root CA "
                    + "is already in the JVM trust store");
        }
    }

    @Override
    public ModelTextResponse generateText(ModelTextRequest request) {
        String model = textModel(request.profile());
        Map<String, Object> body = completionBody(model, Map.of("role", "user", "content", nullToEmpty(request.prompt())), maxTokens);
        long startedAt = System.nanoTime();

        Map<?, ?> response = postJson("/chat/completions", body);

        Duration latency = Duration.ofNanos(System.nanoTime() - startedAt);
        log.debug(
                "ModelGateway text generation provider={} profile={} model={} scenario={} state={} purpose={} latencyMs={}",
                PROVIDER, request.profile(), model, request.scenario(), request.state(), request.purpose(), latency.toMillis());
        return new ModelTextResponse(
                readText(response),
                PROVIDER,
                model,
                ModelCapability.TEXT_GENERATION,
                latency,
                false,
                completionMetadata(response)
        );
    }

    @Override
    public ModelEmbeddingResponse generateEmbedding(ModelEmbeddingRequest request) {
        if (embeddingModel == null) {
            return new ModelEmbeddingResponse(List.of(), PROVIDER, "", ModelCapability.EMBEDDING, Duration.ZERO, true,
                    Map.of("reason", "No embedding model is configured: set GIGACHAT_EMBEDDING_MODEL"));
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", embeddingModel);
        body.put("input", List.of(nullToEmpty(request.text())));
        long startedAt = System.nanoTime();

        Map<?, ?> response = postJson("/embeddings", body);

        Duration latency = Duration.ofNanos(System.nanoTime() - startedAt);
        List<Double> embedding = readEmbedding(response);
        return new ModelEmbeddingResponse(
                embedding,
                PROVIDER,
                embeddingModel,
                ModelCapability.EMBEDDING,
                latency,
                embedding.isEmpty(),
                Map.of("dimension", embedding.size())
        );
    }

    @Override
    public ModelVisionResponse analyzeImage(ModelVisionRequest request) {
        if (visionModel == null) {
            return new ModelVisionResponse("", PROVIDER, "", ModelCapability.IMAGE_UNDERSTANDING, Duration.ZERO, true,
                    Map.of("reason", "No vision model is configured: set GIGACHAT_VISION_MODEL"));
        }
        long startedAt = System.nanoTime();
        String fileId = uploadImage(request);
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("role", "user");
        message.put("content", nullToEmpty(request.prompt()));
        message.put("attachments", List.of(fileId));

        Map<?, ?> response = postJson("/chat/completions", completionBody(visionModel, message, visionMaxTokens));

        Duration latency = Duration.ofNanos(System.nanoTime() - startedAt);
        String text = readText(response);
        Map<String, Object> metadata = completionMetadata(response);
        metadata.put("fileId", fileId);
        return new ModelVisionResponse(
                text,
                PROVIDER,
                visionModel,
                ModelCapability.IMAGE_UNDERSTANDING,
                latency,
                text.isBlank(),
                metadata
        );
    }

    private String uploadImage(ModelVisionRequest request) {
        String mimeType = blankToNull(request.mimeType()) == null ? "image/jpeg" : request.mimeType().trim();
        byte[] bytes = request.imageBase64() == null ? new byte[0] : Base64.getDecoder().decode(request.imageBase64());
        String extension = mimeType.toLowerCase(Locale.ROOT).contains("png") ? "png" : "jpg";
        ByteArrayResource file = new ByteArrayResource(bytes) {
            @Override
            public String getFilename() {
                return "image." + extension;
            }
        };
        HttpHeaders fileHeaders = new HttpHeaders();
        fileHeaders.setContentType(MediaType.parseMediaType(mimeType));
        MultiValueMap<String, Object> form = new LinkedMultiValueMap<>();
        form.add("file", new HttpEntity<>(file, fileHeaders));
        form.add("purpose", "general");

        Map<?, ?> response = exchange("/files", form, MediaType.MULTIPART_FORM_DATA);
        Object id = response.get("id");
        if (id == null || id.toString().isBlank()) {
            throw new IllegalStateException("GigaChat file upload returned no id");
        }
        return id.toString();
    }

    private String textModel(ModelProfile profile) {
        return profile == ModelProfile.QUALITY && qualityModel != null ? qualityModel : frontlineModel;
    }

    private Map<String, Object> completionBody(String model, Map<String, Object> message, int tokens) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("messages", List.of(message));
        body.put("max_tokens", tokens);
        if (temperature >= 0) {
            body.put("temperature", Math.min(2.0, temperature));
        }
        body.put("stream", false);
        return body;
    }

    private Map<?, ?> postJson(String path, Map<String, Object> body) {
        return exchange(path, body, MediaType.APPLICATION_JSON);
    }

    /** One call with the cached token; a 401 drops the token and retries once with a fresh one. */
    private Map<?, ?> exchange(String path, Object body, MediaType contentType) {
        String bearer = accessToken(false);
        try {
            return send(path, body, contentType, bearer);
        } catch (HttpClientErrorException error) {
            if (error.getStatusCode() != HttpStatus.UNAUTHORIZED) {
                throw error;
            }
            log.info("GigaChat rejected the access token, requesting a new one");
            return send(path, body, contentType, accessToken(true));
        }
    }

    private Map<?, ?> send(String path, Object body, MediaType contentType, String bearer) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(contentType);
        headers.setAccept(List.of(MediaType.APPLICATION_JSON));
        headers.setBearerAuth(bearer);
        ResponseEntity<Map> response = restTemplate.exchange(
                baseUrl + path, HttpMethod.POST, new HttpEntity<>(body, headers), Map.class);
        return response.getBody() == null ? Map.of() : response.getBody();
    }

    private String accessToken(boolean forceRefresh) {
        if (authKey == null) {
            throw new IllegalStateException("GigaChat authorization key is not configured: set GIGACHAT_AUTH_KEY");
        }
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
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
        headers.setAccept(List.of(MediaType.APPLICATION_JSON));
        headers.set(HttpHeaders.AUTHORIZATION, "Basic " + authKey);
        headers.set("RqUID", UUID.randomUUID().toString());
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("scope", scope);
        ResponseEntity<Map> response = restTemplate.exchange(
                oauthUrl, HttpMethod.POST, new HttpEntity<>(form, headers), Map.class);
        Map<?, ?> body = response.getBody() == null ? Map.of() : response.getBody();
        Object accessToken = body.get("access_token");
        if (!(accessToken instanceof String value) || value.isBlank()) {
            throw new IllegalStateException("GigaChat OAuth response has no access_token");
        }
        Instant expiresAt = body.get("expires_at") instanceof Number millis
                ? Instant.ofEpochMilli(millis.longValue())
                : Instant.now().plus(Duration.ofMinutes(25));
        log.debug("GigaChat access token obtained, expires at {}", expiresAt);
        return new AccessToken(value, expiresAt);
    }

    private static JdkClientHttpRequestFactory requestFactory(String caCertPath, Duration timeout) {
        HttpClient.Builder builder = HttpClient.newBuilder()
                .connectTimeout(timeout)
                .followRedirects(HttpClient.Redirect.NEVER);
        if (caCertPath != null) {
            Path path = Path.of(caCertPath);
            if (!Files.isReadable(path)) {
                throw new IllegalStateException("GIGACHAT_CA_CERT_PATH is not a readable file: " + path);
            }
            try {
                SSLContext sslContext = GigaChatTrust.sslContext(path);
                builder.sslContext(sslContext);
            } catch (Exception e) {
                throw new IllegalStateException("GIGACHAT_CA_CERT_PATH could not be loaded: " + path, e);
            }
        }
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(builder.build());
        factory.setReadTimeout(timeout);
        return factory;
    }

    private Map<?, ?> firstChoice(Map<?, ?> response) {
        if (response.get("choices") instanceof List<?> choices
                && !choices.isEmpty()
                && choices.getFirst() instanceof Map<?, ?> choice) {
            return choice;
        }
        return Map.of();
    }

    private String readText(Map<?, ?> response) {
        if (firstChoice(response).get("message") instanceof Map<?, ?> message
                && message.get("content") instanceof String text) {
            return text;
        }
        return "";
    }

    private Map<String, Object> completionMetadata(Map<?, ?> response) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("usage", response.get("usage") instanceof Map<?, ?> usage ? usage : Map.of());
        Object finishReason = firstChoice(response).get("finish_reason");
        metadata.put("finishReason", finishReason == null ? "" : finishReason.toString());
        return metadata;
    }

    private List<Double> readEmbedding(Map<?, ?> response) {
        if (!(response.get("data") instanceof List<?> data)
                || data.isEmpty()
                || !(data.getFirst() instanceof Map<?, ?> first)
                || !(first.get("embedding") instanceof List<?> values)) {
            return List.of();
        }
        List<Double> embedding = new ArrayList<>(values.size());
        for (Object value : values) {
            if (value instanceof Number number) {
                embedding.add(number.doubleValue());
            }
        }
        return embedding;
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    record AccessToken(String value, Instant expiresAt) {
        boolean usableAt(Instant now) {
            return now.plus(TOKEN_REFRESH_MARGIN).isBefore(expiresAt);
        }
    }
}
