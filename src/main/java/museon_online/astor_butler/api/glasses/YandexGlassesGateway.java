package museon_online.astor_butler.api.glasses;

import com.fasterxml.jackson.databind.ObjectMapper;
import museon_online.astor_butler.model.*;

import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.*;

/** Tool-free AI Studio Completions; no guest agent, persistence, embeddings or domain actions. */
public final class YandexGlassesGateway implements ModelGateway {
    private final HttpClient client;
    private final ObjectMapper mapper;
    private final URI endpoint;
    private final String apiKey;
    private final String folder;
    private final String textModel;
    private final String visionModel;

    public YandexGlassesGateway(String endpoint, String apiKey, String folder, String textModel, String visionModel) {
        this.endpoint = URI.create(endpoint);
        this.apiKey = apiKey;
        this.folder = folder;
        this.textModel = textModel;
        this.visionModel = visionModel;
        this.mapper = new ObjectMapper();
        this.client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3))
                .followRedirects(HttpClient.Redirect.NEVER).build();
    }

    @Override public ModelTextResponse generateText(ModelTextRequest request) {
        return ModelTextResponse.text(complete(textModel, request.prompt(), null), "yandex-glasses", textModel, Duration.ZERO);
    }

    @Override public ModelVisionResponse analyzeImage(ModelVisionRequest request) {
        return ModelVisionResponse.vision(complete(visionModel, request.prompt(), request.imageBase64()),
                "yandex-glasses", visionModel, Duration.ZERO);
    }

    private String complete(String model, String prompt, String image) {
        if (apiKey.isBlank() || folder.isBlank() || model.isBlank()) throw new IllegalStateException("Provider unavailable");
        try {
            List<Map<String, Object>> content = new ArrayList<>();
            content.add(Map.of("type", "text", "text", prompt));
            if (image != null) content.add(Map.of("type", "image_url", "image_url",
                    Map.of("url", "data:image/jpeg;base64," + image)));
            Map<String, Object> body = new LinkedHashMap<>(Map.of("model", "gpt://" + folder + "/" + model, "messages",
                    List.of(Map.of("role", "user", "content", content)), "max_tokens", image == null ? 256 : 1024, "stream", false));
            if (image != null) body.put("chat_template_kwargs", Map.of("enable_thinking", false));
            var http = HttpRequest.newBuilder(endpoint).timeout(Duration.ofSeconds(20))
                    .header("Authorization", "Api-Key " + apiKey).header("OpenAI-Project", folder)
                    .header("Content-Type", "application/json").header("x-data-logging-enabled", "false")
                    .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body))).build();
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
