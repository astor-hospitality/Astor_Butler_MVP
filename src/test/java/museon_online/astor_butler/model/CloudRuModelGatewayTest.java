package museon_online.astor_butler.model;

import org.junit.jupiter.api.Test;
import org.springframework.boot.restclient.RestTemplateBuilder;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;

import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class CloudRuModelGatewayTest {

    private static final String API_KEY = "cloudru-key-fixture";

    @Test
    void defaultsToTheFoundationModelsEndpointAndAnswersUnderItsOwnProviderName() {
        AtomicReference<MockRestServiceServer> serverRef = new AtomicReference<>();
        CloudRuModelGateway gateway = new CloudRuModelGateway(
                new RestTemplateBuilder(restTemplate -> serverRef.set(MockRestServiceServer.bindTo(restTemplate).build())),
                "", API_KEY, "GigaChat/GigaChat-2-Max", "", "", "BAAI/bge-m3", 8000, 128, 0.1, true);
        serverRef.get().expect(once(), requestTo("https://foundation-models.api.cloud.ru/v1/chat/completions"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "Bearer " + API_KEY))
                .andExpect(jsonPath("$.model").value("GigaChat/GigaChat-2-Max"))
                .andExpect(jsonPath("$.response_format.type").value("json_object"))
                .andRespond(withSuccess(
                        "{\"choices\": [{\"finish_reason\": \"stop\", \"message\": {\"content\": \"{\\\"intent\\\":\\\"LUNCH\\\"}\"}}]}",
                        MediaType.APPLICATION_JSON));
        serverRef.get().expect(once(), requestTo("https://foundation-models.api.cloud.ru/v1/embeddings"))
                .andExpect(jsonPath("$.model").value("BAAI/bge-m3"))
                .andRespond(withSuccess("{\"data\": [{\"index\": 0, \"embedding\": [1, 2]}]}", MediaType.APPLICATION_JSON));

        ModelTextResponse text = gateway.generateText(ModelTextRequest.of(
                "Верни JSON", "LLM_UNDERSTANDING", "READY_FOR_DIALOG", "intent-slots-json"));
        ModelEmbeddingResponse embedding = gateway.generateEmbedding(ModelEmbeddingRequest.of(
                "ланч", "text-search-doc/latest", "SemanticMemory", null, "embedding-document"));

        assertThat(text.provider()).isEqualTo("cloudru");
        assertThat(text.text()).isEqualTo("{\"intent\":\"LUNCH\"}");
        assertThat(embedding.provider()).isEqualTo("cloudru");
        assertThat(embedding.embedding()).containsExactly(1.0, 2.0);
        serverRef.get().verify();
    }

    @Test
    void missingSettingsAreNamedWithTheCloudRuVariables() {
        AtomicReference<MockRestServiceServer> serverRef = new AtomicReference<>();
        CloudRuModelGateway noKey = new CloudRuModelGateway(
                new RestTemplateBuilder(restTemplate -> serverRef.set(MockRestServiceServer.bindTo(restTemplate).build())),
                "https://models.test/v1", "", "some-model", "", "", "", 8000, 128, 0.1, true);
        CloudRuModelGateway noModel = new CloudRuModelGateway(
                new RestTemplateBuilder(), "https://models.test/v1", API_KEY, "", "", "", "", 8000, 128, 0.1, true);
        ModelTextRequest request = ModelTextRequest.of("Привет", "RECOVERY", "READY_FOR_DIALOG", "guest-reply");

        assertThatThrownBy(() -> noKey.generateText(request))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("CLOUDRU_API_KEY");
        assertThatThrownBy(() -> noModel.generateText(request))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("CLOUDRU_MODEL");
        assertThat(noKey.generateEmbedding(ModelEmbeddingRequest.of("x", "m", "SemanticMemory", null, "embedding-document"))
                .metadata().get("reason").toString()).contains("CLOUDRU_EMBEDDING_MODEL");
        assertThat(noKey.analyzeImage(ModelVisionRequest.of("?", "aGVsbG8=", "image/png", null, "GLASSES", null, "check"))
                .metadata().get("reason").toString()).contains("CLOUDRU_VISION_MODEL");
        serverRef.get().verify();
    }
}
