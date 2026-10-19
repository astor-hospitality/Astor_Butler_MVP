package museon_online.astor_butler.model;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
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
import java.util.Optional;
import java.util.regex.Pattern;

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
 *
 * <p>Function calling ({@link ToolCallingModel}) uses the OpenAI {@code tools} / {@code tool_calls} fields. The
 * agent loop runs one tool per step, so only the first of several calls in one answer is taken (the rest are counted
 * in {@code tool_calls_ignored}); its result goes back as a {@code tool} message under the call's id. A failed call
 * says only the HTTP status and the service's error code: the body may quote the guest's words. Every {@code chat}
 * call is written to {@code model_interaction_audit} when the repository is wired, as for GigaChat.
 */
@Slf4j
@Service
@ConditionalOnProperty(prefix = "astor.model", name = "provider", havingValue = "openai-compatible", matchIfMissing = false)
public class OpenAiCompatibleModelGateway implements ModelGateway, ToolCallingModel {

    static final String PROVIDER = "openai-compatible";
    static final String ENV_PREFIX = "OPENAI_COMPATIBLE";
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final TypeReference<Map<String, Object>> JSON_OBJECT = new TypeReference<>() {
    };
    /** An error field the logs may keep: a short identifier, never free text. */
    private static final Pattern SAFE_ERROR_FIELD = Pattern.compile("[\\w.-]{1,64}");

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
    private Boolean parallelToolCalls;   // null: not sent, the service's default applies
    private ModelInteractionAuditRepository auditRepository;

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

    @Autowired(required = false)
    public void setAuditRepository(ModelInteractionAuditRepository auditRepository) {
        this.auditRepository = auditRepository;
    }

    /**
     * {@code parallel_tool_calls} of tool requests ({@code OPENAI_COMPATIBLE_PARALLEL_TOOL_CALLS}): blank leaves it
     * out, {@code false} asks for one call per answer. Not every model behind the service accepts it.
     */
    @Autowired(required = false)
    public void setParallelToolCalls(@Value("${astor.model.openai-compatible.parallel-tool-calls:}") String value) {
        String flag = blankToNull(value);
        this.parallelToolCalls = flag == null ? null : Boolean.valueOf(flag);
    }

    /**
     * One chat completion with optional tools. {@code request.model()} (the agent's {@code ASTOR_AGENT_MODEL}) must be
     * a name from this service's {@code GET /models}; without it the profile's configured model answers.
     *
     * @throws ModelUnavailableException on 401, 402, 403, 408, 429, 5xx, a timeout or an I/O failure
     */
    @Override
    public ModelChatResponse chat(ModelChatRequest request) {
        String model = request.model() != null ? request.model().trim() : textModel(request.profile());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("messages", chatMessages(request.messages()));
        body.put("max_tokens", request.maxTokens() != null ? request.maxTokens() : maxTokens);
        if (temperature >= 0) {
            body.put("temperature", Math.min(2.0, temperature));
        }
        body.put("stream", false);
        if (!request.tools().isEmpty()) {
            body.put("tools", request.tools().stream().map(OpenAiCompatibleModelGateway::tool).toList());
            // "none" is not honoured by every model behind the service (gigachat-3-pro then refuses the history's
            // calls as undeclared), so an answer-only step offers the tools too and ignores a new call below.
            body.put("tool_choice", "auto");
            if (parallelToolCalls != null) {
                body.put("parallel_tool_calls", parallelToolCalls);
            }
        }
        long startedAt = System.nanoTime();
        try {
            ResponseEntity<Map> exchanged = chatExchange(body);
            Map<?, ?> response = exchanged.getBody() == null ? Map.of() : exchanged.getBody();
            Duration latency = Duration.ofNanos(System.nanoTime() - startedAt);
            Map<String, Object> metadata = completionMetadata(response);
            Double cost = charged(exchanged);
            if (cost != null) {
                metadata.put("costRub", cost);
            }
            Optional<ModelToolCall> toolCall = readToolCall(response, metadata);
            if (request.answerOnly() && toolCall.isPresent()) {
                metadata.put("tool_call_ignored", toolCall.get().name());
                toolCall = Optional.empty();
            }
            ModelChatResponse result = new ModelChatResponse(
                    readText(response), toolCall, provider, model, latency, Map.copyOf(metadata));
            ModelChatAudit.record(auditRepository, provider, request, model, result, latency, null);
            log.debug("ModelGateway chat provider={} model={} purpose={} toolCall={} latencyMs={}",
                    provider, model, request.purpose(), toolCall.map(ModelToolCall::name).orElse(""), latency.toMillis());
            return result;
        } catch (RuntimeException error) {
            ModelChatAudit.record(auditRepository, provider, request, model, null,
                    Duration.ofNanos(System.nanoTime() - startedAt), error);
            throw error;
        }
    }

    /**
     * {@link #post} for chat: "cannot be used now" answers become {@link ModelUnavailableException}, any other
     * rejection an {@link IllegalStateException}. Neither carries the response body; the cause keeps only the
     * status, so the call log still names it (e.g. {@code TooManyRequests}).
     */
    private ResponseEntity<Map> chatExchange(Map<String, Object> body) {
        try {
            return exchange("/chat/completions", body);
        } catch (HttpClientErrorException error) {
            int status = error.getStatusCode().value();
            HttpClientErrorException bare = HttpClientErrorException.create(
                    error.getStatusCode(), error.getStatusText(), costOnly(error.getResponseHeaders()), new byte[0], null);
            String message = provider + " chat failed: HTTP " + status + errorHint(error.getResponseBodyAsString());
            if (status == 401 || status == 402 || status == 403 || status == 408 || status == 429) {
                throw new ModelUnavailableException(message, bare);
            }
            throw new IllegalStateException(message, bare);
        } catch (HttpServerErrorException error) {
            throw new ModelUnavailableException(
                    provider + " chat failed: HTTP " + error.getStatusCode().value() + errorHint(error.getResponseBodyAsString()),
                    HttpServerErrorException.create(error.getStatusCode(), error.getStatusText(), costOnly(error.getResponseHeaders()),
                            new byte[0], null));
        } catch (ResourceAccessException error) {
            throw new ModelUnavailableException(provider + " did not answer in time or could not be reached", error);
        }
    }

    /** Which code, type or parameter the service named, e.g. " (code 429, type rate_limit_error)"; never its message. */
    static String errorHint(String responseBody) {
        if (responseBody == null || responseBody.isBlank()) {
            return "";
        }
        try {
            Map<String, Object> parsed = JSON.readValue(responseBody, JSON_OBJECT);
            if (!(parsed.get("error") instanceof Map<?, ?> error)) {
                return "";
            }
            List<String> hints = new ArrayList<>();
            for (String field : List.of("code", "type", "param")) {
                Object value = error.get(field);
                if (value != null && SAFE_ERROR_FIELD.matcher(value.toString()).matches()) {
                    hints.add(field + " " + value);
                }
            }
            return hints.isEmpty() ? "" : " (" + String.join(", ", hints) + ")";
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * Turns as the OpenAI API takes them. A call keeps the provider's id, or gets {@code call00001}-style one (nine
     * letters and digits, accepted by every service seen so far); the result that follows it is sent under that id.
     * A function result with no call before it (never sent by the agent) goes as a user line, not as a {@code tool}
     * message the service would reject.
     */
    static List<Map<String, Object>> chatMessages(List<ModelChatMessage> messages) {
        List<Map<String, Object>> json = new ArrayList<>(messages.size());
        String openCall = null;
        int generated = 0;
        for (ModelChatMessage message : messages) {
            String role = message.role() == null ? "user" : message.role();
            Map<String, Object> turn = new LinkedHashMap<>();
            if (message.functionCall() != null) {
                ModelToolCall call = message.functionCall();
                String id = call.id() != null ? call.id() : "call%05d".formatted(++generated);
                Map<String, Object> function = new LinkedHashMap<>();
                function.put("name", call.name());
                function.put("arguments", argumentsJson(call.arguments()));
                Map<String, Object> toolCall = new LinkedHashMap<>();
                toolCall.put("id", id);
                toolCall.put("type", "function");
                toolCall.put("function", function);
                turn.put("role", "assistant");
                // OpenAI allows no content beside tool calls; an empty string is refused by some services.
                turn.put("content", message.content() == null || message.content().isBlank() ? null : message.content());
                turn.put("tool_calls", List.of(toolCall));
                openCall = id;
            } else if ("function".equals(role) || "tool".equals(role)) {
                if (openCall != null) {
                    turn.put("role", "tool");
                    turn.put("tool_call_id", openCall);
                    turn.put("content", message.content() == null ? "" : message.content());
                    openCall = null;
                } else {
                    turn.put("role", "user");
                    turn.put("content", "Результат " + (message.name() == null ? "инструмента" : message.name()) + ": "
                            + (message.content() == null ? "" : message.content()));
                }
            } else {
                turn.put("role", role);
                turn.put("content", message.content() == null ? "" : message.content());
            }
            json.add(turn);
        }
        return json;
    }

    private static String argumentsJson(Map<String, Object> arguments) {
        try {
            return JSON.writeValueAsString(arguments);
        } catch (Exception e) {
            return "{}";
        }
    }

    private static Map<String, Object> tool(ModelTool tool) {
        Map<String, Object> function = new LinkedHashMap<>();
        function.put("name", tool.name());
        function.put("description", tool.description() == null ? "" : tool.description());
        function.put("parameters", tool.parameters());
        return Map.of("type", "function", "function", function);
    }

    /**
     * The first of {@code tool_calls} (or a legacy {@code function_call}). Its {@code arguments} come as a JSON string
     * or an object; an unreadable one becomes {@code {}} with {@code arguments_unparsed}, and the tool's own
     * validation answers it, as with GigaChat.
     */
    private Optional<ModelToolCall> readToolCall(Map<?, ?> response, Map<String, Object> metadata) {
        if (!(firstChoice(response).get("message") instanceof Map<?, ?> message)) {
            return Optional.empty();
        }
        Map<?, ?> function = null;
        String id = null;
        if (message.get("tool_calls") instanceof List<?> calls && !calls.isEmpty()
                && calls.getFirst() instanceof Map<?, ?> first && first.get("function") instanceof Map<?, ?> called) {
            function = called;
            id = first.get("id") instanceof String text ? text : null;
            if (calls.size() > 1) {
                metadata.put("tool_calls_ignored", calls.size() - 1);
            }
        } else if (message.get("function_call") instanceof Map<?, ?> legacy) {
            function = legacy;
        }
        if (function == null || !(function.get("name") instanceof String name) || name.isBlank()) {
            return Optional.empty();
        }
        Object arguments = function.get("arguments");
        Map<String, Object> parsed = new LinkedHashMap<>();
        if (arguments instanceof Map<?, ?> object) {
            object.forEach((key, value) -> parsed.put(String.valueOf(key), value));
        } else if (arguments instanceof String text && !text.isBlank()) {
            try {
                Map<String, Object> value = JSON.readValue(text, JSON_OBJECT);
                if (value != null) {
                    parsed.putAll(value);
                }
            } catch (Exception e) {
                metadata.put("arguments_unparsed", true);
            }
        } else if (arguments != null && !(arguments instanceof String)) {
            metadata.put("arguments_unparsed", true);
        }
        return Optional.of(new ModelToolCall(name, parsed, id));
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
        if (request.maxTokens() != null && request.maxTokens() > 0) {
            body.put("max_tokens", request.maxTokens());
        }
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
        ResponseEntity<Map> response = exchange(path, body);
        return response.getBody() == null ? Map.of() : response.getBody();
    }

    private ResponseEntity<Map> exchange(String path, Map<String, Object> body) {
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
        return restTemplate.exchange(
                baseUrl + path,
                HttpMethod.POST,
                new HttpEntity<>(body, headers),
                Map.class
        );
    }

    /** Only the service's charge of an error answer, for the agent's budget; no other header leaves the gateway. */
    static HttpHeaders costOnly(HttpHeaders headers) {
        HttpHeaders kept = new HttpHeaders();
        String cost = headers == null ? null : headers.getFirst("x-litellm-response-cost");
        if (cost != null && !cost.isBlank()) {
            kept.set("x-litellm-response-cost", cost.trim());
        }
        return kept;
    }

    /** What the service charged for this answer ({@code x-litellm-response-cost}, rubles on the Sber500 API), or null. */
    static Double charged(ResponseEntity<?> response) {
        String header = response.getHeaders().getFirst("x-litellm-response-cost");
        if (header == null || header.isBlank()) {
            return null;
        }
        try {
            double cost = Double.parseDouble(header.trim());
            return Double.isFinite(cost) && cost >= 0 ? cost : null;
        } catch (NumberFormatException e) {
            return null;
        }
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

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
