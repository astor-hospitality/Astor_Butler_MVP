package museon_online.astor_butler.model;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.restclient.RestTemplateBuilder;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Model provider for any service that speaks the OpenAI HTTP API: {@code POST /chat/completions}
 * and {@code POST /embeddings} under one base URL, with a bearer key.
 *
 * <p>Selected with {@code ASTOR_MODEL_PROVIDER=openai-compatible}. Model names belong to the
 * service ({@code GET /models} lists them), so none is assumed: text needs
 * {@code OPENAI_COMPATIBLE_MODEL}; images and embeddings answer with a fallback until their own
 * model is set. Model names that callers pass for another provider are ignored.
 *
 * <p>The guest-facing persona that the Yandex AI Studio agent used to carry lives here in a file:
 * {@code OPENAI_COMPATIBLE_SYSTEM_PROMPT_FILE}. Its text is read once at startup and sent as the
 * {@code system} message of every free-text generation. Structured understanding (JSON intent and
 * slot calls) is sent exactly as the caller built it, without the persona, the same split the
 * agent runtime kept so the FSM sees the same answers whichever provider is behind it.
 */
@Slf4j
@Service
@ConditionalOnProperty(prefix = "astor.model", name = "provider", havingValue = "openai-compatible", matchIfMissing = false)
public class OpenAiCompatibleModelGateway implements ModelGateway {

    static final String PROVIDER = "openai-compatible";
    static final String ENV_PREFIX = "OPENAI_COMPATIBLE";

    private final String provider;
    private final String envPrefix;
    private final RestTemplate restTemplate;
    private final String baseUrl;
    private final String apiKey;
    private final String frontlineModel;
    private final String qualityModel;
    private final String visionModel;
    private final String embeddingModel;
    private final int maxTokens;
    private final double temperature;
    private final boolean jsonMode;
    private final String systemPrompt;   // null when no persona file is configured or readable

    @Autowired
    public OpenAiCompatibleModelGateway(
            RestTemplateBuilder restTemplateBuilder,
            @Value("${astor.model.openai-compatible.base-url:}") String baseUrl,
            @Value("${astor.model.openai-compatible.api-key:}") String apiKey,
            @Value("${astor.model.openai-compatible.model:}") String frontlineModel,
            @Value("${astor.model.openai-compatible.quality-model:}") String qualityModel,
            @Value("${astor.model.openai-compatible.vision-model:}") String visionModel,
            @Value("${astor.model.openai-compatible.embedding-model:}") String embeddingModel,
            @Value("${astor.model.openai-compatible.timeout-ms:15000}") int timeoutMs,
            @Value("${astor.model.openai-compatible.max-tokens:256}") int maxTokens,
            @Value("${astor.model.openai-compatible.temperature:0.1}") double temperature,
            @Value("${astor.model.openai-compatible.json-mode:true}") boolean jsonMode,
            @Value("${astor.model.openai-compatible.system-prompt-file:}") String systemPromptFile
    ) {
        this(PROVIDER, ENV_PREFIX, restTemplateBuilder, baseUrl, apiKey, frontlineModel, qualityModel, visionModel,
                embeddingModel, timeoutMs, maxTokens, temperature, jsonMode, systemPromptFile);
    }

    /**
     * Shared by every provider that speaks the OpenAI HTTP API under its own name and settings,
     * for example {@link CloudRuModelGateway}. {@code envPrefix} names the variables in error messages.
     */
    protected OpenAiCompatibleModelGateway(
            String provider,
            String envPrefix,
            RestTemplateBuilder restTemplateBuilder,
            String baseUrl,
            String apiKey,
            String frontlineModel,
            String qualityModel,
            String visionModel,
            String embeddingModel,
            int timeoutMs,
            int maxTokens,
            double temperature,
            boolean jsonMode
    ) {
        this(provider, envPrefix, restTemplateBuilder, baseUrl, apiKey, frontlineModel, qualityModel,
                visionModel, embeddingModel, timeoutMs, maxTokens, temperature, jsonMode, "");
    }

    protected OpenAiCompatibleModelGateway(String provider, String envPrefix, RestTemplateBuilder restTemplateBuilder,
            String baseUrl, String apiKey, String frontlineModel, String qualityModel, String visionModel,
            String embeddingModel, int timeoutMs, int maxTokens, double temperature, boolean jsonMode,
            String systemPromptFile) {
        this.provider = provider;
        this.envPrefix = envPrefix;
        Duration timeout = Duration.ofMillis(Math.max(1, timeoutMs));
        this.restTemplate = restTemplateBuilder
                .connectTimeout(timeout)
                .readTimeout(timeout)
                .build();
        this.baseUrl = blankToNull(baseUrl) == null ? null : baseUrl.trim().replaceFirst("/+$", "");
        this.apiKey = blankToNull(apiKey);
        this.frontlineModel = blankToNull(frontlineModel);
        this.qualityModel = blankToNull(qualityModel);
        this.visionModel = blankToNull(visionModel);
        this.embeddingModel = blankToNull(embeddingModel);
        this.maxTokens = Math.max(1, maxTokens);
        this.temperature = temperature;
        this.jsonMode = jsonMode;
        this.systemPrompt = readSystemPrompt(blankToNull(systemPromptFile));

        List<String> missing = new ArrayList<>();
        if (this.baseUrl == null) {
            missing.add(envPrefix + "_BASE_URL");
        }
        if (this.apiKey == null) {
            missing.add(envPrefix + "_API_KEY");
        }
        if (this.frontlineModel == null) {
            missing.add(envPrefix + "_MODEL");
        }
        if (!missing.isEmpty()) {
            log.error("Model provider {} is selected but not configured, every model call will fail: missing {}",
                    provider, missing);
        }
    }

    /** The persona text, or null. A configured file that cannot be read is said once and never retried. */
    static String readSystemPrompt(String file) {
        if (file == null) {
            return null;
        }
        try {
            String text = Files.readString(Path.of(file), StandardCharsets.UTF_8).strip();
            if (text.isEmpty()) {
                log.error("System prompt file {} is empty; free-text generation runs without a persona", file);
                return null;
            }
            return text;
        } catch (Exception e) {
            // The path is operator configuration, not a secret; the reason is a file-system one.
            log.error("System prompt file {} cannot be read ({}); free-text generation runs without a persona",
                    file, e.getClass().getSimpleName());
            return null;
        }
    }

    boolean hasSystemPrompt() {
        return systemPrompt != null;
    }

    @Override
    public ModelTextResponse generateText(ModelTextRequest request) {
        String model = textModel(request.profile());
        boolean json = expectsJson(request);
        Map<String, Object> body = completionBody(model, request.prompt() == null ? "" : request.prompt(),
                json ? null : systemPrompt);
        if (jsonMode && json) {
            body.put("response_format", Map.of("type", "json_object"));
        }
        long startedAt = System.nanoTime();

        Map<?, ?> response = post("/chat/completions", body);

        Duration latency = Duration.ofNanos(System.nanoTime() - startedAt);
        log.debug(
                "ModelGateway text generation provider={} profile={} model={} scenario={} state={} purpose={} latencyMs={}",
                provider,
                request.profile(),
                model,
                request.scenario(),
                request.state(),
                request.purpose(),
                latency.toMillis()
        );
        return new ModelTextResponse(
                readText(response),
                provider,
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
            return new ModelEmbeddingResponse(
                    List.of(),
                    provider,
                    "",
                    ModelCapability.EMBEDDING,
                    Duration.ZERO,
                    true,
                    Map.of("reason", "No embedding model is configured: set " + envPrefix + "_EMBEDDING_MODEL")
            );
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", embeddingModel);
        body.put("input", request.text() == null ? "" : request.text());
        long startedAt = System.nanoTime();

        Map<?, ?> response = post("/embeddings", body);

        Duration latency = Duration.ofNanos(System.nanoTime() - startedAt);
        List<Double> embedding = readEmbedding(response);
        return new ModelEmbeddingResponse(
                embedding,
                provider,
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
            return new ModelVisionResponse(
                    "",
                    provider,
                    "",
                    ModelCapability.IMAGE_UNDERSTANDING,
                    Duration.ZERO,
                    true,
                    Map.of("reason", "No vision model is configured: set " + envPrefix + "_VISION_MODEL")
            );
        }
        String mimeType = blankToNull(request.mimeType()) == null ? "image/jpeg" : request.mimeType().trim();
        List<Map<String, Object>> content = List.of(
                Map.of("type", "text", "text", request.prompt() == null ? "" : request.prompt()),
                Map.of("type", "image_url", "image_url",
                        Map.of("url", "data:" + mimeType + ";base64," + (request.imageBase64() == null ? "" : request.imageBase64())))
        );
        Map<String, Object> body = completionBody(visionModel, content, null);
        long startedAt = System.nanoTime();

        Map<?, ?> response = post("/chat/completions", body);

        Duration latency = Duration.ofNanos(System.nanoTime() - startedAt);
        String text = readText(response);
        return new ModelVisionResponse(
                text,
                provider,
                visionModel,
                ModelCapability.IMAGE_UNDERSTANDING,
                latency,
                text.isBlank(),
                completionMetadata(response)
        );
    }

    private String textModel(ModelProfile profile) {
        if (frontlineModel == null) {
            throw new IllegalStateException(provider + " model is not configured: set " + envPrefix + "_MODEL");
        }
        return profile == ModelProfile.QUALITY && qualityModel != null ? qualityModel : frontlineModel;
    }

    private Map<String, Object> completionBody(String model, Object userContent, String system) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        List<Map<String, Object>> messages = new ArrayList<>(2);
        if (system != null) {
            messages.add(Map.of("role", "system", "content", system));
        }
        messages.add(Map.of("role", "user", "content", userContent));
        body.put("messages", messages);
        body.put("max_tokens", maxTokens);
        // Some models accept only their default temperature; a negative setting leaves it out.
        if (temperature >= 0) {
            body.put("temperature", Math.min(2.0, temperature));
        }
        body.put("stream", false);
        return body;
    }

    private Map<?, ?> post(String path, Map<String, Object> body) {
        if (baseUrl == null) {
            throw new IllegalStateException(provider + " base URL is not configured: set " + envPrefix + "_BASE_URL");
        }
        if (apiKey == null) {
            throw new IllegalStateException(provider + " API key is not configured: set " + envPrefix + "_API_KEY");
        }
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setAccept(List.of(MediaType.APPLICATION_JSON));
        headers.setBearerAuth(apiKey);
        ResponseEntity<Map> response = restTemplate.exchange(
                baseUrl + path,
                HttpMethod.POST,
                new HttpEntity<>(body, headers),
                Map.class
        );
        return response.getBody() == null ? Map.of() : response.getBody();
    }

    private boolean expectsJson(ModelTextRequest request) {
        Object metadataFlag = request.metadata().get("jsonObject");
        if (metadataFlag instanceof Boolean flag) {
            return flag;
        }
        String purpose = request.purpose() == null ? "" : request.purpose().toLowerCase(Locale.ROOT);
        return purpose.contains("json");
    }

    private Map<?, ?> firstChoice(Map<?, ?> response) {
        if (response.get("choices") instanceof List<?> choices
                && !choices.isEmpty()
                && choices.getFirst() instanceof Map<?, ?> choice) {
            return choice;
        }
        return Map.of();
    }

    /** The answer text: a plain string, or the text parts of a content list. */
    private String readText(Map<?, ?> response) {
        if (!(firstChoice(response).get("message") instanceof Map<?, ?> message)) {
            return "";
        }
        Object content = message.get("content");
        if (content instanceof String text) {
            return text;
        }
        if (content instanceof List<?> parts) {
            StringBuilder text = new StringBuilder();
            for (Object part : parts) {
                if (part instanceof Map<?, ?> partMap && partMap.get("text") instanceof String partText) {
                    text.append(partText);
                }
            }
            return text.toString();
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

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
