package museon_online.astor_butler.api.glasses;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import museon_online.astor_butler.model.ModelTextRequest;
import museon_online.astor_butler.model.ModelVisionRequest;
import museon_online.astor_butler.speech.RecordingStubServer;
import museon_online.astor_butler.speech.RecordingStubServer.Answer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The glasses on the GigaChat API against a local stub of the NGW OAuth gateway and the GigaChat host. */
class GigaChatGlassesGatewayTest {
    private static final String OAUTH = "/api/v2/oauth";
    private static final String CHAT = "/api/v1/chat/completions";
    private static final String FILES = "/api/v1/files";
    private static final String GLASSES_KEY = "Z2xhc3Nlcy1jbGllbnQ6c2VjcmV0";
    private static final String BOT_KEY = "Ym90LWNsaWVudDpzZWNyZXQ=";
    private static final ObjectMapper JSON = new ObjectMapper();

    private RecordingStubServer stub;

    @BeforeEach
    void start() throws Exception {
        stub = RecordingStubServer.start();
        stub.route(OAUTH, call -> RecordingStubServer.token("tok-1", 30 * 60_000));
        stub.route(CHAT, call -> answer("stop", "  Стол пять у окна, на четверых.  "));
        stub.route(FILES, call -> Answer.json(200, "{\"id\":\"file-42\",\"object\":\"file\"}"));
    }

    @AfterEach
    void stop() {
        stub.close();
    }

    private static Answer answer(String finishReason, String content) {
        return Answer.json(200, "{\"choices\":[{\"finish_reason\":\"" + finishReason + "\",\"message\":{\"role\":\"assistant\","
                + "\"content\":\"" + content + "\"}}],\"usage\":{\"total_tokens\":12}}");
    }

    private Map<String, String> env() {
        Map<String, String> env = new HashMap<>();
        env.put("GIGACHAT_OAUTH_URL", stub.url(OAUTH));
        env.put("GIGACHAT_API_URL", stub.url("/api/v1"));
        env.put("ASTOR_GLASSES_GIGACHAT_AUTH_KEY", GLASSES_KEY);
        env.put("GIGACHAT_AUTH_KEY", BOT_KEY);
        env.put("GIGACHAT_SCOPE", "GIGACHAT_API_PERS");
        // Left over from the Cloud.ru setup: the catalogue prefix is not part of the GigaChat API name.
        env.put("ASTOR_GLASSES_TEXT_MODEL", "GigaChat/GigaChat-2-Max");
        return env;
    }

    @Test
    void aTextQuestionGoesThroughOauthAndChatCompletionsAndOnlyTheAnswerComesBack() throws Exception {
        var gateway = GigaChatGlassesGateway.fromEnvironment(env()::get);
        var service = new GlassesAssistService(gateway, true, 5000);

        String answer = service.assist("Где стол пять?");

        assertThat(answer).isEqualTo("Стол пять у окна, на четверых.");
        var oauth = stub.calls(OAUTH).getFirst();
        assertThat(oauth.header("Authorization")).isEqualTo("Basic " + GLASSES_KEY);
        assertThat(oauth.header("RqUID")).matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
        assertThat(oauth.text()).isEqualTo("scope=GIGACHAT_API_PERS");
        var chat = stub.calls(CHAT).getFirst();
        assertThat(chat.header("Authorization")).isEqualTo("Bearer tok-1");
        JsonNode body = JSON.readTree(chat.body());
        assertThat(body.path("model").asText()).isEqualTo("GigaChat-2-Max");
        assertThat(body.path("max_tokens").asInt()).isEqualTo(256);
        assertThat(body.path("temperature").asDouble()).isEqualTo(0.1);
        assertThat(body.path("stream").asBoolean()).isFalse();
        assertThat(body.path("messages").get(0).path("content").asText()).contains("Где стол пять?");
        assertThat(stub.calls(FILES)).isEmpty();

        // The token is cached: a second question costs one more completion and no OAuth call.
        gateway.generateText(ModelTextRequest.of("Ещё вопрос", "GLASSES_INFORMATIONAL", "READ_ONLY", "staff-informational"));
        assertThat(stub.calls(OAUTH)).hasSize(1);
        assertThat(stub.calls(CHAT)).hasSize(2);
    }

    @Test
    void aPhotoIsUploadedThenAttachedByIdWithTheLargerAnswerBound() throws Exception {
        var gateway = GigaChatGlassesGateway.fromEnvironment(env()::get);

        var response = gateway.analyzeImage(ModelVisionRequest.of("Что на столе?", "aGVsbG8=", "image/jpeg", null,
                "GLASSES_INFORMATIONAL", "READ_ONLY", "staff-vision"));

        assertThat(response.text()).isEqualTo("Стол пять у окна, на четверых.");
        var upload = stub.calls(FILES).getFirst();
        assertThat(upload.header("Content-Type")).startsWith("multipart/form-data");
        assertThat(upload.text()).contains("filename=\"image.jpg\"").contains("name=\"purpose\"").contains("hello");
        JsonNode body = JSON.readTree(stub.calls(CHAT).getFirst().body());
        assertThat(body.path("model").asText()).isEqualTo(GigaChatGlassesGateway.DEFAULT_MODEL);
        assertThat(body.path("max_tokens").asInt()).isEqualTo(1024);
        assertThat(body.path("messages").get(0).path("attachments").get(0).asText()).isEqualTo("file-42");
    }

    @Test
    void truncatedFilteredOrFailedAnswersAreUnavailableWithoutProviderDetails() {
        var gateway = GigaChatGlassesGateway.fromEnvironment(env()::get);
        var request = ModelTextRequest.of("Вопрос", "GLASSES_INFORMATIONAL", "READ_ONLY", "staff-informational");

        stub.route(CHAT, call -> answer("length", "Обрезанный отв"));
        assertThatThrownBy(() -> gateway.generateText(request)).isInstanceOf(IllegalStateException.class)
                .hasMessage("Provider unavailable");
        stub.route(CHAT, call -> answer("blacklist", "Не люблю менять тему"));
        assertThatThrownBy(() -> gateway.generateText(request)).hasMessage("Provider unavailable");
        stub.route(CHAT, call -> answer("stop", "   "));
        assertThatThrownBy(() -> gateway.generateText(request)).hasMessage("Provider unavailable");
        stub.route(CHAT, call -> Answer.json(500, "{\"message\":\"secret-diagnostic\"}"));
        assertThatThrownBy(() -> gateway.generateText(request)).hasMessage("Provider unavailable");
        stub.route(OAUTH, call -> Answer.json(401, "{\"message\":\"secret-diagnostic\"}"));
        var rejected = GigaChatGlassesGateway.fromEnvironment(env()::get);
        assertThatThrownBy(() -> rejected.generateText(request)).hasMessage("Provider unavailable");
    }

    @Test
    void theBotKeyIsTheFallbackAndWithoutAnyKeyNothingLeavesTheServer() {
        Map<String, String> env = env();
        env.remove("ASTOR_GLASSES_GIGACHAT_AUTH_KEY");
        GigaChatGlassesGateway.fromEnvironment(env::get)
                .generateText(ModelTextRequest.of("Вопрос", "t", "t", "t"));
        assertThat(stub.calls(OAUTH).getFirst().header("Authorization")).isEqualTo("Basic " + BOT_KEY);

        env.remove("GIGACHAT_AUTH_KEY");
        int before = stub.calls().size();
        var noKey = GigaChatGlassesGateway.fromEnvironment(env::get);
        assertThatThrownBy(() -> noKey.generateText(ModelTextRequest.of("Вопрос", "t", "t", "t")))
                .hasMessage("Provider unavailable");
        assertThat(stub.calls()).hasSize(before);
    }

    @Test
    void modelNamesDefaultAndDropTheCloudRuPrefix() {
        assertThat(GigaChatGlassesGateway.modelName(null)).isEqualTo("GigaChat-2-Max");
        assertThat(GigaChatGlassesGateway.modelName(" gigachat/GigaChat-2-Pro ")).isEqualTo("GigaChat-2-Pro");
        assertThat(GigaChatGlassesGateway.modelName("GigaChat-2")).isEqualTo("GigaChat-2");
        assertThatThrownBy(() -> GigaChatGlassesGateway.fromEnvironment(Map.of("GIGACHAT_TIMEOUT_MS", "soon")::get))
                .hasMessageContaining("GIGACHAT_TIMEOUT_MS");
    }
}
