package museon_online.astor_butler.model;

import org.junit.jupiter.api.Test;
import org.springframework.boot.restclient.RestTemplateBuilder;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;

import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.headerDoesNotExist;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class GigaChatModelGatewayTest {

    private static final String OAUTH = "https://ngw.test:9443/api/v2/oauth";
    private static final String BASE_URL = "https://gigachat.test/api/v1";
    private static final String COMPLETIONS = BASE_URL + "/chat/completions";
    private static final String AUTH_KEY = "YmFzZTY0LWZpeHR1cmU=";
    private static final String ANSWER = """
            {
              "choices": [
                {"finish_reason": "stop", "message": {"role": "assistant", "content": "{\\"intent\\":\\"TABLE_BOOKING\\"}"}}
              ],
              "usage": {"prompt_tokens": 42, "completion_tokens": 8, "total_tokens": 50}
            }
            """;

    private static String tokenJson(String token, long expiresInMs) {
        return "{\"access_token\": \"" + token + "\", \"expires_at\": " + (System.currentTimeMillis() + expiresInMs) + "}";
    }

    @Test
    void firstCallObtainsATokenWithBasicKeyRqUidAndScopeThenReusesIt() {
        Fixture fixture = fixture(AUTH_KEY, "GIGACHAT_API_B2B", "GigaChat", "GigaChat-Max", "", "Embeddings");
        fixture.server().expect(once(), requestTo(OAUTH))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "Basic " + AUTH_KEY))
                .andExpect(header("Content-Type", MediaType.APPLICATION_FORM_URLENCODED_VALUE))
                .andExpect(request -> assertThat(request.getHeaders().getFirst("RqUID")).matches(
                        "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"))
                .andExpect(content().string("scope=GIGACHAT_API_B2B"))
                .andRespond(withSuccess(tokenJson("tok-1", 30 * 60_000), MediaType.APPLICATION_JSON));
        fixture.server().expect(once(), requestTo(COMPLETIONS))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "Bearer tok-1"))
                .andExpect(headerDoesNotExist("RqUID"))
                .andExpect(jsonPath("$.model").value("GigaChat"))
                .andExpect(jsonPath("$.messages[0].role").value("user"))
                .andExpect(jsonPath("$.messages[0].content").value("Верни JSON"))
                .andExpect(jsonPath("$.max_tokens").value(128))
                .andExpect(jsonPath("$.temperature").value(0.1))
                .andExpect(jsonPath("$.stream").value(false))
                .andExpect(jsonPath("$.response_format").doesNotExist())
                .andRespond(withSuccess(ANSWER, MediaType.APPLICATION_JSON));
        fixture.server().expect(once(), requestTo(COMPLETIONS))
                .andExpect(header("Authorization", "Bearer tok-1"))
                .andExpect(jsonPath("$.model").value("GigaChat-Max"))
                .andRespond(withSuccess(ANSWER, MediaType.APPLICATION_JSON));

        ModelTextResponse first = fixture.gateway().generateText(new ModelTextRequest(
                "Верни JSON", "LLM_UNDERSTANDING", "READY_FOR_DIALOG", "intent-slots-json", ModelProfile.FRONTLINE, Map.of()));
        ModelTextResponse second = fixture.gateway().generateText(
                ModelTextRequest.quality("Поздоровайся", "RECOVERY", "READY_FOR_DIALOG", "guest-reply"));

        assertThat(first.text()).isEqualTo("{\"intent\":\"TABLE_BOOKING\"}");
        assertThat(first.provider()).isEqualTo("gigachat");
        assertThat(first.model()).isEqualTo("GigaChat");
        assertThat(first.fallback()).isFalse();
        assertThat(first.metadata()).containsEntry("finishReason", "stop");
        assertThat(first.metadata().get("usage")).isEqualTo(
                Map.of("prompt_tokens", 42, "completion_tokens", 8, "total_tokens", 50));
        assertThat(second.model()).isEqualTo("GigaChat-Max");
        fixture.server().verify();
    }

    @Test
    void anExpiringTokenIsRenewedBeforeUseAndARejectedTokenIsRenewedOnce() {
        Fixture fixture = fixture(AUTH_KEY, "GIGACHAT_API_PERS", "GigaChat", "", "", "");
        // Expires inside the refresh margin: used for nothing, renewed on the next call.
        fixture.server().expect(once(), requestTo(OAUTH))
                .andRespond(withSuccess(tokenJson("short", 10_000), MediaType.APPLICATION_JSON));
        fixture.server().expect(once(), requestTo(COMPLETIONS))
                .andExpect(header("Authorization", "Bearer short"))
                .andRespond(withSuccess(ANSWER, MediaType.APPLICATION_JSON));
        fixture.server().expect(once(), requestTo(OAUTH))
                .andRespond(withSuccess(tokenJson("long", 30 * 60_000), MediaType.APPLICATION_JSON));
        fixture.server().expect(once(), requestTo(COMPLETIONS))
                .andExpect(header("Authorization", "Bearer long"))
                .andRespond(withStatus(HttpStatus.UNAUTHORIZED).contentType(MediaType.APPLICATION_JSON)
                        .body("{\"message\": \"Token has expired\"}"));
        fixture.server().expect(once(), requestTo(OAUTH))
                .andRespond(withSuccess(tokenJson("fresh", 30 * 60_000), MediaType.APPLICATION_JSON));
        fixture.server().expect(once(), requestTo(COMPLETIONS))
                .andExpect(header("Authorization", "Bearer fresh"))
                .andRespond(withSuccess(ANSWER, MediaType.APPLICATION_JSON));

        ModelTextRequest request = ModelTextRequest.of("Привет", "RECOVERY", "READY_FOR_DIALOG", "guest-reply");
        fixture.gateway().generateText(request);
        ModelTextResponse response = fixture.gateway().generateText(request);

        assertThat(response.text()).isNotEmpty();
        fixture.server().verify();
    }

    @Test
    void aMissingKeyFailsByNameWithoutAnyCallAndAnOauthFailureNeverLeaksTheKey() {
        ModelTextRequest request = ModelTextRequest.of("Привет", "RECOVERY", "READY_FOR_DIALOG", "guest-reply");
        Fixture noKey = fixture("", "GIGACHAT_API_PERS", "GigaChat", "", "", "");
        Fixture badKey = fixture(AUTH_KEY, "GIGACHAT_API_PERS", "GigaChat", "", "", "");
        badKey.server().expect(once(), requestTo(OAUTH))
                .andRespond(withStatus(HttpStatus.UNAUTHORIZED).contentType(MediaType.APPLICATION_JSON)
                        .body("{\"message\": \"invalid credentials\"}"));

        assertThatThrownBy(() -> noKey.gateway().generateText(request))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("GIGACHAT_AUTH_KEY");
        assertThatThrownBy(() -> badKey.gateway().generateText(request))
                .satisfies(error -> assertThat(error.getMessage()).doesNotContain(AUTH_KEY));
        noKey.server().verify();
        badKey.server().verify();
    }

    @Test
    void embeddingsSendTheTextAsAListAndReturnTheVector() {
        Fixture fixture = fixture(AUTH_KEY, "GIGACHAT_API_PERS", "GigaChat", "", "", "Embeddings");
        fixture.server().expect(once(), requestTo(OAUTH))
                .andRespond(withSuccess(tokenJson("tok", 30 * 60_000), MediaType.APPLICATION_JSON));
        fixture.server().expect(once(), requestTo(BASE_URL + "/embeddings"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "Bearer tok"))
                .andExpect(jsonPath("$.model").value("Embeddings"))
                .andExpect(jsonPath("$.input[0]").value("стол у окна"))
                .andRespond(withSuccess("{\"data\": [{\"index\": 0, \"embedding\": [0.25, -1, 3]}]}", MediaType.APPLICATION_JSON));

        ModelEmbeddingResponse embedded = fixture.gateway().generateEmbedding(ModelEmbeddingRequest.of(
                "стол у окна", "text-search-doc/latest", "SemanticMemory", null, "embedding-document"));
        ModelEmbeddingResponse skipped = fixture(AUTH_KEY, "GIGACHAT_API_PERS", "GigaChat", "", "", "").gateway()
                .generateEmbedding(ModelEmbeddingRequest.of("стол", "x", "SemanticMemory", null, "embedding-document"));

        assertThat(embedded.embedding()).containsExactly(0.25, -1.0, 3.0);
        assertThat(embedded.model()).isEqualTo("Embeddings");
        assertThat(embedded.fallback()).isFalse();
        assertThat(embedded.metadata()).containsEntry("dimension", 3);
        assertThat(skipped.fallback()).isTrue();
        assertThat(skipped.metadata().get("reason").toString()).contains("GIGACHAT_EMBEDDING_MODEL");
        fixture.server().verify();
    }

    @Test
    void imagesAreUploadedToTheFileStoreAndAttachedById() {
        Fixture fixture = fixture(AUTH_KEY, "GIGACHAT_API_PERS", "GigaChat", "", "GigaChat-Max", "");
        fixture.server().expect(once(), requestTo(OAUTH))
                .andRespond(withSuccess(tokenJson("tok", 30 * 60_000), MediaType.APPLICATION_JSON));
        fixture.server().expect(once(), requestTo(BASE_URL + "/files"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "Bearer tok"))
                .andExpect(content().contentTypeCompatibleWith(MediaType.MULTIPART_FORM_DATA))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("name=\"purpose\"")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("general")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("filename=\"image.png\"")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("hello")))
                .andRespond(withSuccess("{\"id\": \"file-123\", \"object\": \"file\"}", MediaType.APPLICATION_JSON));
        fixture.server().expect(once(), requestTo(COMPLETIONS))
                .andExpect(jsonPath("$.model").value("GigaChat-Max"))
                .andExpect(jsonPath("$.messages[0].content").value("Что на столе?"))
                .andExpect(jsonPath("$.messages[0].attachments[0]").value("file-123"))
                .andRespond(withSuccess(
                        "{\"choices\": [{\"finish_reason\": \"stop\", \"message\": {\"content\": \"Два прибора.\"}}]}",
                        MediaType.APPLICATION_JSON));

        ModelVisionResponse seen = fixture.gateway().analyzeImage(ModelVisionRequest.of(
                "Что на столе?", "aGVsbG8=", "image/png", "yandex-vision", "GLASSES", null, "table-check"));
        ModelVisionResponse skipped = fixture(AUTH_KEY, "GIGACHAT_API_PERS", "GigaChat", "", "", "").gateway()
                .analyzeImage(ModelVisionRequest.of("?", "aGVsbG8=", "image/png", null, "GLASSES", null, "table-check"));

        assertThat(seen.text()).isEqualTo("Два прибора.");
        assertThat(seen.model()).isEqualTo("GigaChat-Max");
        assertThat(seen.fallback()).isFalse();
        assertThat(seen.metadata()).containsEntry("fileId", "file-123");
        assertThat(skipped.fallback()).isTrue();
        fixture.server().verify();
    }

    @Test
    void anUnreadableCaPathFailsAtStartupInsteadOfRelaxingTls() {
        assertThatThrownBy(() -> new GigaChatModelGateway(
                new RestTemplateBuilder(), OAUTH, BASE_URL, AUTH_KEY, "GIGACHAT_API_PERS", "GigaChat", "", "", "",
                "/nonexistent/russian_trusted_root_ca.pem", 8000, 128, 0.1).generateText(
                ModelTextRequest.of("Привет", "RECOVERY", "READY_FOR_DIALOG", "guest-reply")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("GIGACHAT_CA_CERT_PATH");
    }

    private static Fixture fixture(
            String authKey,
            String scope,
            String model,
            String qualityModel,
            String visionModel,
            String embeddingModel
    ) {
        AtomicReference<MockRestServiceServer> serverRef = new AtomicReference<>();
        GigaChatModelGateway gateway = new GigaChatModelGateway(
                new RestTemplateBuilder(restTemplate ->
                        serverRef.set(MockRestServiceServer.bindTo(restTemplate).build())),
                OAUTH,
                BASE_URL + "/",
                authKey,
                scope,
                model,
                qualityModel,
                visionModel,
                embeddingModel,
                "",
                8000,
                128,
                0.1
        );
        return new Fixture(gateway, serverRef.get());
    }

    private record Fixture(GigaChatModelGateway gateway, MockRestServiceServer server) {
    }
}
