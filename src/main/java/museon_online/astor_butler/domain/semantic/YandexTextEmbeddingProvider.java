package museon_online.astor_butler.domain.semantic;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * {@code ASTOR_SEMANTIC_EMBEDDINGS_PROVIDER=yandex}: Yandex AI Studio text embeddings called directly, independent of
 * {@code ASTOR_MODEL_PROVIDER}. The chat model can be GigaChat or Cloud.ru while vectors come from Yandex (GigaChat
 * {@code /embeddings} is not part of the free package and answers 402).
 *
 * <p>{@code POST {base}/foundationModels/v1/textEmbedding}, {@code Authorization: Api-Key <key>}, body
 * {@code {"modelUri":"emb://<folder>/text-search-doc/latest","text":"..."}}; documents (RAG chunks, intent examples)
 * use the document model, {@link #embedQuery} the query model. Both return 256 floats, which must match
 * {@code ASTOR_SEMANTIC_EMBEDDING_DIMENSION}. The key is {@code ASTOR_EMBEDDINGS_YANDEX_API_KEY}, falling back to
 * {@code YANDEX_SPEECHKIT_API_KEY} and then {@code YANDEX_API_KEY}; the folder is {@code ASTOR_EMBEDDINGS_YANDEX_FOLDER_ID},
 * then {@code YANDEX_FOLDER_ID}, then {@code YANDEX_SPEECHKIT_FOLDER_ID}. The fallbacks are resolved here rather than with
 * nested placeholders because compose passes unset variables as empty strings, which a placeholder default ignores.
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "astor.semantic-memory.embeddings", name = "provider", havingValue = "yandex")
public class YandexTextEmbeddingProvider implements EmbeddingProvider {

    static final String PATH = "/foundationModels/v1/textEmbedding";
    static final String DEFAULT_BASE_URL = "https://llm.api.cloud.yandex.net";
    static final String DEFAULT_DOCUMENT_MODEL = "text-search-doc/latest";
    static final String DEFAULT_QUERY_MODEL = "text-search-query/latest";

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final HttpClient httpClient;
    private final URI endpoint;
    private final String apiKey;
    private final String folderId;
    private final String documentModel;
    private final String queryModel;
    private final int expectedDimension;
    private final Duration timeout;
    private final long throttleNanos;
    private final int maxAttempts;
    private final Duration retryDelay;
    private final Object throttleLock = new Object();
    private long nextRequestAtNanos;

    @Autowired
    public YandexTextEmbeddingProvider(
            @Value("${astor.semantic-memory.embeddings.yandex.base-url:}") String baseUrl,
            @Value("${yandex.ai.base-url:}") String yandexBaseUrl,
            @Value("${astor.semantic-memory.embeddings.yandex.api-key:}") String apiKey,
            @Value("${astor.semantic-memory.embeddings.yandex.speechkit-api-key:}") String speechKitApiKey,
            @Value("${yandex.ai.api-key:}") String yandexApiKey,
            @Value("${astor.semantic-memory.embeddings.yandex.folder-id:}") String folderId,
            @Value("${yandex.ai.folder-id:}") String yandexFolderId,
            @Value("${astor.semantic-memory.embeddings.yandex.speechkit-folder-id:}") String speechKitFolderId,
            @Value("${astor.semantic-memory.embeddings.model:" + DEFAULT_DOCUMENT_MODEL + "}") String documentModel,
            @Value("${astor.semantic-memory.embeddings.query-model:" + DEFAULT_QUERY_MODEL + "}") String queryModel,
            @Value("${astor.semantic-memory.pgvector.embedding-dimension:1536}") int expectedDimension,
            @Value("${astor.semantic-memory.embeddings.yandex.timeout-ms:8000}") long timeoutMs,
            @Value("${astor.semantic-memory.embeddings.throttle-ms:0}") long throttleMs,
            @Value("${yandex.ai.embedding-max-attempts:6}") int maxAttempts,
            @Value("${yandex.ai.embedding-retry-delay-ms:5000}") long retryDelayMs
    ) {
        this(
                firstNonBlank(baseUrl, yandexBaseUrl, DEFAULT_BASE_URL),
                firstNonBlank(apiKey, speechKitApiKey, yandexApiKey),
                firstNonBlank(folderId, yandexFolderId, speechKitFolderId),
                documentModel,
                queryModel,
                expectedDimension,
                Duration.ofMillis(Math.max(1, timeoutMs)),
                Duration.ofMillis(Math.max(0, throttleMs)),
                maxAttempts,
                Duration.ofMillis(Math.max(250, retryDelayMs))
        );
        log.info(
                "Semantic embeddings provider=yandex endpoint={} documentModel={} queryModel={} dimension={} apiKey={} folderId={}",
                endpoint,
                this.documentModel,
                this.queryModel,
                this.expectedDimension,
                this.apiKey == null ? "MISSING" : "set",
                this.folderId == null ? "MISSING" : "set"
        );
        if (this.apiKey == null) {
            log.warn("Yandex embeddings have no API key: set ASTOR_EMBEDDINGS_YANDEX_API_KEY "
                    + "(or YANDEX_SPEECHKIT_API_KEY / YANDEX_API_KEY); semantic search stays off until then");
        }
    }

    YandexTextEmbeddingProvider(String baseUrl, String apiKey, String folderId, String documentModel, String queryModel,
                                int expectedDimension, Duration timeout, Duration throttle, int maxAttempts,
                                Duration retryDelay) {
        this.endpoint = URI.create(firstNonBlank(baseUrl, DEFAULT_BASE_URL).replaceFirst("/+$", "") + PATH);
        this.apiKey = blankToNull(apiKey);
        this.folderId = blankToNull(folderId);
        this.documentModel = firstNonBlank(documentModel, DEFAULT_DOCUMENT_MODEL);
        this.queryModel = firstNonBlank(queryModel, DEFAULT_QUERY_MODEL);
        this.expectedDimension = expectedDimension;
        this.timeout = timeout;
        this.throttleNanos = Math.max(0, throttle.toNanos());
        this.maxAttempts = Math.max(1, maxAttempts);
        this.retryDelay = retryDelay;
        this.httpClient = HttpClient.newBuilder().connectTimeout(timeout).build();
        this.nextRequestAtNanos = System.nanoTime();
    }

    /** Label stored in {@code embedding_model}: the document model, so rows from another model are recognisable. */
    @Override
    public String model() {
        return documentModel;
    }

    @Override
    public List<Double> embed(String text) {
        return embed(text, documentModel);
    }

    @Override
    public List<Double> embedQuery(String text) {
        return embed(text, queryModel);
    }

    String modelUri(String model) {
        if (model.startsWith("emb://")) {
            return model;
        }
        if (folderId == null) {
            throw new IllegalStateException("Yandex embeddings need a folder for model '" + model
                    + "': set YANDEX_FOLDER_ID or ASTOR_EMBEDDINGS_YANDEX_FOLDER_ID, or give the full emb:// URI");
        }
        return "emb://" + folderId + "/" + model.replaceFirst("^/+", "");
    }

    private List<Double> embed(String text, String model) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        if (apiKey == null) {
            throw new IllegalStateException("Yandex embeddings need an API key: set ASTOR_EMBEDDINGS_YANDEX_API_KEY "
                    + "(or YANDEX_SPEECHKIT_API_KEY / YANDEX_API_KEY)");
        }
        String modelUri = modelUri(model);
        Map<String, String> body = new LinkedHashMap<>();
        body.put("modelUri", modelUri);
        body.put("text", text);
        long startedAt = System.nanoTime();
        List<Double> embedding = readEmbedding(send(body));
        if (embedding.isEmpty()) {
            throw new IllegalStateException("Yandex embeddings returned no vector for " + modelUri);
        }
        if (expectedDimension > 0 && embedding.size() != expectedDimension) {
            throw new IllegalStateException("Yandex embeddings returned " + embedding.size() + " values for " + model
                    + " but ASTOR_SEMANTIC_EMBEDDING_DIMENSION=" + expectedDimension
                    + "; set ASTOR_SEMANTIC_EMBEDDING_DIMENSION=" + embedding.size() + " and re-index");
        }
        log.debug("Semantic embedding provider=yandex model={} dimension={} latencyMs={}",
                model, embedding.size(), Duration.ofNanos(System.nanoTime() - startedAt).toMillis());
        return embedding;
    }

    private String send(Map<String, String> body) {
        String json;
        try {
            json = objectMapper.writeValueAsString(body);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot serialize Yandex embedding request", e);
        }
        for (int attempt = 1; ; attempt++) {
            awaitThrottle();
            HttpRequest request = HttpRequest.newBuilder(endpoint)
                    .timeout(timeout)
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Api-Key " + apiKey)
                    .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> response;
            try {
                response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            } catch (IOException e) {
                if (attempt < maxAttempts) {
                    sleep(backoff(null, attempt));
                    continue;
                }
                throw new IllegalStateException("Yandex embedding request failed after " + attempt + " attempts", e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Yandex embedding request was interrupted", e);
            }
            int status = response.statusCode();
            if (status < 300) {
                return response.body();
            }
            if ((status == 429 || status >= 500) && attempt < maxAttempts) {
                sleep(backoff(response, attempt));
                continue;
            }
            // The body may echo the request; only the status goes to the log.
            throw new IllegalStateException("Yandex embedding request failed with status=" + status
                    + " after " + attempt + " attempt(s)");
        }
    }

    private List<Double> readEmbedding(String responseBody) {
        if (responseBody == null || responseBody.isBlank()) {
            return List.of();
        }
        JsonNode root;
        try {
            root = objectMapper.readTree(responseBody);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot parse Yandex embedding response", e);
        }
        JsonNode values = root.path("embedding");
        if (!values.isArray()) {
            values = root.path("result").path("embedding");
        }
        if (!values.isArray()) {
            return List.of();
        }
        List<Double> embedding = new ArrayList<>(values.size());
        for (JsonNode value : values) {
            embedding.add(value.isNumber() ? value.doubleValue() : Double.parseDouble(value.asText()));
        }
        return embedding;
    }

    /** Keeps at least {@code ASTOR_SEMANTIC_EMBEDDINGS_THROTTLE_MS} between request starts; an idle provider does not wait. */
    private void awaitThrottle() {
        if (throttleNanos <= 0) {
            return;
        }
        long waitNanos;
        synchronized (throttleLock) {
            long now = System.nanoTime();
            long startAt = nextRequestAtNanos - now > 0 ? nextRequestAtNanos : now;
            nextRequestAtNanos = startAt + throttleNanos;
            waitNanos = startAt - now;
        }
        if (waitNanos > 0) {
            sleep(Duration.ofNanos(waitNanos));
        }
    }

    private Duration backoff(HttpResponse<?> response, int attempt) {
        Optional<Duration> retryAfter = response == null ? Optional.empty() : response.headers()
                .firstValue("Retry-After")
                .flatMap(value -> {
                    try {
                        return Optional.of(Duration.ofSeconds(Math.max(1, Long.parseLong(value.trim()))));
                    } catch (NumberFormatException e) {
                        return Optional.empty();
                    }
                });
        // Same schedule as the model-gateway path: delay, 2x, 4x, 8x, then 16x at most.
        return retryAfter.orElse(retryDelay.multipliedBy(1L << Math.min(4, attempt - 1)));
    }

    private void sleep(Duration delay) {
        try {
            Thread.sleep(delay);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Yandex embedding retry interrupted", e);
        }
    }

    static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return null;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
