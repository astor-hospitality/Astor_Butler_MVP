package museon_online.astor_butler.api.glasses;

import com.fasterxml.jackson.databind.ObjectMapper;
import museon_online.astor_butler.model.*;

import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.*;

/**
 * Tool-free chat completions for the glasses: one text model, one vision model, no guest agent,
 * persistence, embeddings or domain actions. The wire format is OpenAI's; what differs between
 * providers is only how the request is signed and how the model is named, so the provider is an
 * explicit setting rather than something guessed from the key.
 *
 * <ul>
 *   <li>{@link Provider#YANDEX} — Yandex AI Studio: {@code Authorization: Api-Key}, {@code OpenAI-Project}
 *       folder, model {@code gpt://<folder>/<model>}, {@code x-data-logging-enabled: false}.</li>
 *   <li>{@link Provider#OPENAI_COMPATIBLE} — any OpenAI-compatible endpoint, Cloud.ru Foundation Models
 *       among them: {@code Authorization: Bearer}, the model id as the provider lists it
 *       (for example {@code Qwen/Qwen3.6-35B-A3B}).</li>
 * </ul>
 */
public final class GlassesCompletionsGateway implements ModelGateway {
    public enum Provider {
        YANDEX("yandex-glasses"), OPENAI_COMPATIBLE("openai-compatible-glasses");
        final String label;
        Provider(String label) { this.label = label; }

        /** The setting as the environment spells it; anything unknown is a configuration error, not a default. */
        public static Provider parse(String value) {
            return switch (value == null ? "" : value.strip().toLowerCase(Locale.ROOT)) {
                case "yandex" -> YANDEX;
                case "openai-compatible", "cloudru", "cloud.ru" -> OPENAI_COMPATIBLE;
                default -> throw new IllegalStateException("Unknown glasses AI provider: " + value);
            };
        }
    }

    public static final String YANDEX_ENDPOINT = "https://ai.api.cloud.yandex.net/v1/chat/completions";
    public static final String CLOUDRU_ENDPOINT = "https://foundation-models.api.cloud.ru/v1/chat/completions";

    private final HttpClient client;
    private final ObjectMapper mapper;
    private final Provider provider;
    private final URI endpoint;
    private final String apiKey;
    private final String project;   // Yandex folder; empty for an OpenAI-compatible provider
    private final String textModel;
    private final String visionModel;

    public static GlassesCompletionsGateway yandex(String endpoint, String apiKey, String folder, String textModel, String visionModel) {
        return new GlassesCompletionsGateway(Provider.YANDEX, endpoint, apiKey, folder, textModel, visionModel);
    }

    public static GlassesCompletionsGateway openAiCompatible(String endpoint, String apiKey, String textModel, String visionModel) {
        return new GlassesCompletionsGateway(Provider.OPENAI_COMPATIBLE, endpoint, apiKey, "", textModel, visionModel);
    }

    private GlassesCompletionsGateway(Provider provider, String endpoint, String apiKey, String project, String textModel, String visionModel) {
        this.provider = provider;
        this.endpoint = URI.create(endpoint);
        this.apiKey = apiKey == null ? "" : apiKey.strip();
        this.project = project == null ? "" : project.strip();
        this.textModel = textModel == null ? "" : textModel.strip();
        this.visionModel = visionModel == null ? "" : visionModel.strip();
        this.mapper = new ObjectMapper();
        this.client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3))
                .followRedirects(HttpClient.Redirect.NEVER).build();
    }

    public Provider provider() { return provider; }

    @Override public ModelTextResponse generateText(ModelTextRequest request) {
        return ModelTextResponse.text(complete(textModel, request.prompt(), null), provider.label, textModel, Duration.ZERO);
    }

    @Override public ModelVisionResponse analyzeImage(ModelVisionRequest request) {
        return ModelVisionResponse.vision(complete(visionModel, request.prompt(), request.imageBase64()),
                provider.label, visionModel, Duration.ZERO);
    }

    private boolean configured(String model) {
        if (apiKey.isBlank() || model.isBlank()) return false;
        return provider != Provider.YANDEX || !project.isBlank();
    }

    private String modelName(String model) {
        return provider == Provider.YANDEX ? "gpt://" + project + "/" + model : model;
    }

    private HttpRequest.Builder signed(HttpRequest.Builder builder) {
        return switch (provider) {
            case YANDEX -> builder.header("Authorization", "Api-Key " + apiKey).header("OpenAI-Project", project)
                    .header("x-data-logging-enabled", "false");
            case OPENAI_COMPATIBLE -> builder.header("Authorization", "Bearer " + apiKey);
        };
    }

    private String complete(String model, String prompt, String image) {
        if (!configured(model)) throw new IllegalStateException("Provider unavailable");
        try {
            List<Map<String, Object>> content = new ArrayList<>();
            content.add(Map.of("type", "text", "text", prompt));
            if (image != null) content.add(Map.of("type", "image_url", "image_url",
                    Map.of("url", "data:image/jpeg;base64," + image)));
            Map<String, Object> body = new LinkedHashMap<>(Map.of("model", modelName(model), "messages",
                    List.of(Map.of("role", "user", "content", content)), "max_tokens", image == null ? 256 : 1024, "stream", false));
            // Qwen-family vision models think out loud unless told not to; the glasses want the answer only.
            if (image != null) body.put("chat_template_kwargs", Map.of("enable_thinking", false));
            var http = signed(HttpRequest.newBuilder(endpoint).timeout(Duration.ofSeconds(20)))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body))).build();
            var response = client.send(http, HttpResponse.BodyHandlers.limiting(HttpResponse.BodyHandlers.ofByteArray(), 65536));
            if (response.statusCode() != 200) throw new IllegalStateException("Provider unavailable");
            byte[] bytes = response.body();
            if (bytes.length > 65536) throw new IllegalStateException("Provider unavailable");
            var choices = mapper.readTree(bytes).path("choices");
            if (!choices.isArray() || choices.isEmpty()) throw new IllegalStateException("Provider unavailable");
            var choice = choices.get(0);
            if (!"stop".equals(choice.path("finish_reason").asText())) throw new IllegalStateException("Provider unavailable");
            String answer = choice.path("message").path("content").asText("").trim();
            if (answer.isBlank()) throw new IllegalStateException("Provider unavailable");
            return answer;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Provider unavailable");
        } catch (Exception e) {
            // No provider diagnostics leave this class: the phone sees "unavailable", the key stays on the server.
            throw new IllegalStateException("Provider unavailable");
        }
    }

    @Override public ModelEmbeddingResponse generateEmbedding(ModelEmbeddingRequest request) {
        throw new UnsupportedOperationException("Embeddings outside glasses scope");
    }
}
