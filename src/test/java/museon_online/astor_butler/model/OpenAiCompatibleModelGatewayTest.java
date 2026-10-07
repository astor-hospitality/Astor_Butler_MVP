package museon_online.astor_butler.model;

import org.junit.jupiter.api.Test;
import org.springframework.boot.restclient.RestTemplateBuilder;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.HttpClientErrorException;

import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class OpenAiCompatibleModelGatewayTest {

    private static final String BASE_URL = "https://models.test/v1";
    private static final String COMPLETIONS = BASE_URL + "/chat/completions";
    private static final String API_KEY = "key-fixture";
    private static final String ANSWER = """
            {
              "choices": [
                {"finish_reason": "stop", "message": {"role": "assistant", "content": "{\\"intent\\":\\"TABLE_BOOKING\\"}"}}
              ],
              "usage": {"prompt_tokens": 42, "completion_tokens": 8, "total_tokens": 50}
            }
            """;

    @Test
    void textGoesToChatCompletionsWithTheBearerKeyAndJsonMode() {
        Fixture fixture = fixture(BASE_URL + "/", API_KEY, "fast-model", "smart-model", "", "", 0.1, true);
        fixture.server().expect(once(), requestTo(COMPLETIONS))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "Bearer " + API_KEY))
                .andExpect(jsonPath("$.model").value("fast-model"))
                .andExpect(jsonPath("$.messages[0].role").value("user"))
                .andExpect(jsonPath("$.messages[0].content").value("Верни JSON"))
                .andExpect(jsonPath("$.max_tokens").value(128))
                .andExpect(jsonPath("$.temperature").value(0.1))
                .andExpect(jsonPath("$.stream").value(false))
                .andExpect(jsonPath("$.response_format.type").value("json_object"))
                .andRespond(withSuccess(ANSWER, MediaType.APPLICATION_JSON));

        ModelTextResponse response = fixture.gateway().generateText(new ModelTextRequest(
                "Верни JSON", "LLM_UNDERSTANDING", "READY_FOR_DIALOG", "intent-slots-json", ModelProfile.FRONTLINE, Map.of()));

        assertThat(response.text()).isEqualTo("{\"intent\":\"TABLE_BOOKING\"}");
        assertThat(response.provider()).isEqualTo("openai-compatible");
        assertThat(response.model()).isEqualTo("fast-model");
        assertThat(response.fallback()).isFalse();
        assertThat(response.metadata()).containsEntry("finishReason", "stop");
        assertThat(response.metadata().get("usage")).isEqualTo(
                Map.of("prompt_tokens", 42, "completion_tokens", 8, "total_tokens", 50));
        fixture.server().verify();
    }

    @Test
    void qualityProfileUsesTheQualityModelAndPlainTextAsksForNoJson() {
        Fixture fixture = fixture(BASE_URL, API_KEY, "fast-model", "smart-model", "", "", 0.1, true);
        fixture.server().expect(once(), requestTo(COMPLETIONS))
                .andExpect(jsonPath("$.model").value("smart-model"))
                .andExpect(jsonPath("$.response_format").doesNotExist())
                .andRespond(withSuccess("""
                        {"choices": [{"finish_reason": "stop", "message": {"content": [
                          {"type": "text", "text": "Добрый "}, {"type": "text", "text": "вечер."}
                        ]}}]}
                        """, MediaType.APPLICATION_JSON));

        ModelTextResponse response = fixture.gateway().generateText(
                ModelTextRequest.quality("Поздоровайся", "RECOVERY", "READY_FOR_DIALOG", "guest-reply"));

        assertThat(response.text()).isEqualTo("Добрый вечер.");
        assertThat(response.model()).isEqualTo("smart-model");
        fixture.server().verify();
    }

    @Test
    void qualityProfileFallsBackToTheMainModelAndSwitchesCanTurnJsonModeAndTemperatureOff() {
        Fixture fixture = fixture(BASE_URL, API_KEY, "fast-model", "", "", "", -1, false);
        fixture.server().expect(once(), requestTo(COMPLETIONS))
                .andExpect(jsonPath("$.model").value("fast-model"))
                .andExpect(jsonPath("$.temperature").doesNotExist())
                .andExpect(jsonPath("$.response_format").doesNotExist())
                .andRespond(withSuccess(ANSWER, MediaType.APPLICATION_JSON));

        fixture.gateway().generateText(new ModelTextRequest(
                "Верни JSON", "LLM_UNDERSTANDING", "READY_FOR_DIALOG", "intent-slots-json", ModelProfile.QUALITY, Map.of()));

        fixture.server().verify();
    }

    @Test
    void anAnswerWithoutChoicesIsEmptyText() {
        Fixture fixture = fixture(BASE_URL, API_KEY, "fast-model", "", "", "", 0.1, true);
        fixture.server().expect(once(), requestTo(COMPLETIONS))
                .andRespond(withSuccess("{\"choices\": []}", MediaType.APPLICATION_JSON));

        ModelTextResponse response = fixture.gateway().generateText(
                ModelTextRequest.of("Привет", "RECOVERY", "READY_FOR_DIALOG", "guest-reply"));

        assertThat(response.text()).isEmpty();
        fixture.server().verify();
    }

    @Test
    void missingSettingsFailWithTheNameOfTheSettingAndWithoutACall() {
        ModelTextRequest request = ModelTextRequest.of("Привет", "RECOVERY", "READY_FOR_DIALOG", "guest-reply");
        Fixture noModel = fixture(BASE_URL, API_KEY, " ", "", "", "", 0.1, true);
        Fixture noKey = fixture(BASE_URL, "", "fast-model", "", "", "", 0.1, true);
        Fixture noUrl = fixture("", API_KEY, "fast-model", "", "", "", 0.1, true);

        assertThatThrownBy(() -> noModel.gateway().generateText(request))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("OPENAI_COMPATIBLE_MODEL");
        assertThatThrownBy(() -> noKey.gateway().generateText(request))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("OPENAI_COMPATIBLE_API_KEY");
        assertThatThrownBy(() -> noUrl.gateway().generateText(request))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("OPENAI_COMPATIBLE_BASE_URL");
        noModel.server().verify();
        noKey.server().verify();
        noUrl.server().verify();
    }

    @Test
    void aRejectedKeyFailsWithoutLeakingIt() {
        Fixture fixture = fixture(BASE_URL, API_KEY, "fast-model", "", "", "", 0.1, true);
        fixture.server().expect(once(), requestTo(COMPLETIONS))
                .andRespond(withStatus(HttpStatus.UNAUTHORIZED)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"error\": {\"message\": \"invalid key\"}}"));

        assertThatThrownBy(() -> fixture.gateway().generateText(
                ModelTextRequest.of("Привет", "RECOVERY", "READY_FOR_DIALOG", "guest-reply")))
                .isInstanceOf(HttpClientErrorException.class)
                .satisfies(error -> assertThat(error.getMessage()).doesNotContain(API_KEY));
        fixture.server().verify();
    }

    @Test
    void embeddingsNeedTheirOwnModelAndIgnoreAModelNameMeantForAnotherProvider() {
        Fixture withoutModel = fixture(BASE_URL, API_KEY, "fast-model", "", "", "", 0.1, true);
        Fixture withModel = fixture(BASE_URL, API_KEY, "fast-model", "", "", "embed-model", 0.1, true);
        withModel.server().expect(once(), requestTo(BASE_URL + "/embeddings"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "Bearer " + API_KEY))
                .andExpect(jsonPath("$.model").value("embed-model"))
                .andExpect(jsonPath("$.input").value("стол у окна"))
                .andRespond(withSuccess("{\"data\": [{\"index\": 0, \"embedding\": [0.25, -1, 3]}]}", MediaType.APPLICATION_JSON));
        ModelEmbeddingRequest request = ModelEmbeddingRequest.of(
                "стол у окна", "text-search-doc/latest", "SemanticMemory", null, "embedding-document");

        ModelEmbeddingResponse skipped = withoutModel.gateway().generateEmbedding(request);
        ModelEmbeddingResponse embedded = withModel.gateway().generateEmbedding(request);

        assertThat(skipped.embedding()).isEmpty();
        assertThat(skipped.fallback()).isTrue();
        assertThat(embedded.embedding()).containsExactly(0.25, -1.0, 3.0);
        assertThat(embedded.model()).isEqualTo("embed-model");
        assertThat(embedded.fallback()).isFalse();
        assertThat(embedded.metadata()).containsEntry("dimension", 3);
        withoutModel.server().verify();
        withModel.server().verify();
    }

    @Test
    void imagesNeedAVisionModelAndAreSentAsADataUrl() {
        Fixture withoutModel = fixture(BASE_URL, API_KEY, "fast-model", "", "", "", 0.1, true);
        Fixture withModel = fixture(BASE_URL, API_KEY, "fast-model", "", "eye-model", "", 0.1, true);
        withModel.server().expect(once(), requestTo(COMPLETIONS))
                .andExpect(jsonPath("$.model").value("eye-model"))
                .andExpect(jsonPath("$.messages[0].content[0].type").value("text"))
                .andExpect(jsonPath("$.messages[0].content[0].text").value("Что на столе?"))
                .andExpect(jsonPath("$.messages[0].content[1].type").value("image_url"))
                .andExpect(jsonPath("$.messages[0].content[1].image_url.url").value("data:image/png;base64,aGVsbG8="))
                .andRespond(withSuccess(
                        "{\"choices\": [{\"finish_reason\": \"stop\", \"message\": {\"content\": \"Два прибора.\"}}]}",
                        MediaType.APPLICATION_JSON));
        ModelVisionRequest request = ModelVisionRequest.of(
                "Что на столе?", "aGVsbG8=", "image/png", "yandex-vision", "GLASSES", null, "table-check");

        ModelVisionResponse skipped = withoutModel.gateway().analyzeImage(request);
        ModelVisionResponse seen = withModel.gateway().analyzeImage(request);

        assertThat(skipped.fallback()).isTrue();
        assertThat(skipped.text()).isEmpty();
        assertThat(seen.text()).isEqualTo("Два прибора.");
        assertThat(seen.model()).isEqualTo("eye-model");
        assertThat(seen.fallback()).isFalse();
        withoutModel.server().verify();
        withModel.server().verify();
    }

    private static Fixture fixture(
            String baseUrl,
            String apiKey,
            String model,
            String qualityModel,
            String visionModel,
            String embeddingModel,
            double temperature,
            boolean jsonMode
    ) {
        AtomicReference<MockRestServiceServer> serverRef = new AtomicReference<>();
        OpenAiCompatibleModelGateway gateway = new OpenAiCompatibleModelGateway(
                new RestTemplateBuilder(restTemplate ->
                        serverRef.set(MockRestServiceServer.bindTo(restTemplate).build())),
                baseUrl,
                apiKey,
                model,
                qualityModel,
                visionModel,
                embeddingModel,
                8000,
                128,
                temperature,
                jsonMode
        );
        return new Fixture(gateway, serverRef.get());
    }

    private record Fixture(OpenAiCompatibleModelGateway gateway, MockRestServiceServer server) {
    }
}
