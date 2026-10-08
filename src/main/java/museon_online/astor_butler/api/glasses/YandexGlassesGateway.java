package museon_online.astor_butler.api.glasses;

import com.fasterxml.jackson.databind.ObjectMapper;
import museon_online.astor_butler.model.*;

import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.*;

/**
 * Tool-free OpenAI-style chat completions; no guest agent, persistence, embeddings or domain actions.
 * Built for Yandex AI Studio; {@link #cloudRu} points the same request shape at Cloud.ru Foundation Models
 * (Sber), which differ only in the authorization header and plain model names.
 */
public final class YandexGlassesGateway implements ModelGateway {
    static final String CLOUDRU_ENDPOINT = "https://foundation-models.api.cloud.ru/v1/chat/completions";

    private final HttpClient client;
    private final ObjectMapper mapper;
    private final URI endpoint;
    private final String providerName;
    private final String authorization;
    private final String projectHeader;
    private final String modelPrefix;
    private final String textModel;
    private final String visionModel;

    public YandexGlassesGateway(String endpoint, String apiKey, String folder, String textModel, String visionModel) {
        this(endpoint, "yandex-glasses",
                apiKey.isBlank() || folder.isBlank() ? "" : "Api-Key " + apiKey,
                folder, "gpt://" + folder + "/", textModel, visionModel);
    }

    /** Cloud.ru Foundation Models: bearer API key from the Cloud.ru console, model names as in its catalogue. */
    public static YandexGlassesGateway cloudRu(String endpoint, String apiKey, String textModel, String visionModel) {
        return new YandexGlassesGateway(endpoint == null || endpoint.isBlank() ? CLOUDRU_ENDPOINT : endpoint,
                "cloudru-glasses", apiKey.isBlank() ? "" : "Bearer " + apiKey, null, "", textModel, visionModel);
    }

    private YandexGlassesGateway(String endpoint, String providerName, String authorization, String projectHeader,
                                 String modelPrefix, String textModel, String visionModel) {
        this.endpoint = URI.create(endpoint);
        this.providerName = providerName;
        this.authorization = authorization;
        this.projectHeader = projectHeader;
        this.modelPrefix = modelPrefix;
        this.textModel = textModel;
        this.visionModel = visionModel;
        this.mapper = new ObjectMapper();
        this.client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3))
                .followRedirects(HttpClient.Redirect.NEVER).build();
    }

    @Override public ModelTextResponse generateText(ModelTextRequest request) {
        return ModelTextResponse.text(complete(textModel, request.prompt(), null), providerName, textModel, Duration.ZERO);
    }

    @Override public ModelVisionResponse analyzeImage(ModelVisionRequest request) {
        return ModelVisionResponse.vision(complete(visionModel, request.prompt(), request.imageBase64()),
                providerName, visionModel, Duration.ZERO);
    }

    private String complete(String model, String prompt, String image) {
        if (authorization.isBlank() || model.isBlank()) throw new IllegalStateException("Provider unavailable");
        try {
            List<Map<String, Object>> content = new ArrayList<>();
            content.add(Map.of("type", "text", "text", prompt));
            if (image != null) content.add(Map.of("type", "image_url", "image_url",
                    Map.of("url", "data:image/jpeg;base64," + image)));
            Map<String, Object> body = new LinkedHashMap<>(Map.of("model", modelPrefix + model, "messages",
                    List.of(Map.of("role", "user", "content", content)), "max_tokens", image == null ? 256 : 1024, "stream", false));
            if (image != null) body.put("chat_template_kwargs", Map.of("enable_thinking", false));
            var builder = HttpRequest.newBuilder(endpoint).timeout(Duration.ofSeconds(20))
                    .header("Authorization", authorization)
                    .header("Content-Type", "application/json").header("x-data-logging-enabled", "false");
            if (projectHeader != null) builder.header("OpenAI-Project", projectHeader);
            var http = builder.POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body))).build();
            var response = client.send(http, HttpResponse.BodyHandlers.limiting(HttpResponse.BodyHandlers.ofByteArray(), 65536));
            {
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
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Provider unavailable");
        } catch (Exception e) {
            throw new IllegalStateException("Provider unavailable");
        }
    }

    @Override public ModelEmbeddingResponse generateEmbedding(ModelEmbeddingRequest request) {
        throw new UnsupportedOperationException("Embeddings outside glasses scope");
    }
}
