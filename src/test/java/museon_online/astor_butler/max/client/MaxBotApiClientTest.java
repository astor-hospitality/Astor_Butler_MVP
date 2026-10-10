package museon_online.astor_butler.max.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import museon_online.astor_butler.speech.RecordingStubServer;
import museon_online.astor_butler.speech.RecordingStubServer.Answer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The MAX Bot API contract against a local stub: what leaves (paths, query, header, JSON) and what comes back. */
class MaxBotApiClientTest {

    private static final String TOKEN = "unit-max-token";
    private final ObjectMapper json = new ObjectMapper();
    private RecordingStubServer stub;

    @BeforeEach
    void start() throws Exception {
        stub = RecordingStubServer.start();
    }

    @AfterEach
    void stop() {
        stub.close();
    }

    private MaxBotApiClient client(String token) {
        return new MaxBotApiClient(HttpClient.newHttpClient(), json, URI.create(stub.url("")), token, Duration.ofSeconds(5));
    }

    @Test
    void pollSendsTheRawTokenHeaderTheMarkerAndOnlyGuestUpdateTypes() throws Exception {
        stub.route("/updates", call -> Answer.json(200, """
                {"updates":[
                  {"update_type":"bot_started","timestamp":1,"chat_id":11,"user":{"user_id":22,"first_name":"Анна"},"payload":"lunch_aeris_p2"},
                  {"update_type":"message_created","timestamp":2,"message":{"recipient":{"chat_id":11,"chat_type":"dialog"},"body":{"mid":"mid.1","seq":1,"text":"Привет"}}}
                ],"marker":4242}
                """));

        MaxUpdatesPage page = client(TOKEN).getUpdates(4100L, Duration.ofSeconds(30), 100);

        assertThat(page.marker()).isEqualTo(4242L);
        assertThat(page.updates()).extracting(update -> update.path("update_type").asText())
                .containsExactly("bot_started", "message_created");
        RecordingStubServer.Call call = stub.calls("/updates").getFirst();
        assertThat(call.method()).isEqualTo("GET");
        // Raw token, no "Bearer" (MAX answers "Malformed access token" to Bearer) and never in the URL.
        assertThat(call.header("Authorization")).isEqualTo(TOKEN);
        assertThat(call.query()).isEqualTo("limit=100&timeout=30&marker=4100&types=message_created%2Cmessage_callback%2Cbot_started");
        assertThat(call.query()).doesNotContain(TOKEN);
    }

    @Test
    void firstPollGoesWithoutMarkerAndAnEmptyAnswerKeepsNoMarker() {
        stub.route("/updates", call -> Answer.json(200, "{\"updates\":[],\"marker\":null}"));

        MaxUpdatesPage page = client(TOKEN).getUpdates(null, Duration.ofSeconds(0), 10);

        assertThat(page.updates()).isEmpty();
        assertThat(page.marker()).isNull();
        assertThat(stub.calls("/updates").getFirst().query()).doesNotContain("marker");
    }

    @Test
    void sendMessagePostsTextFormatAndInlineKeyboardToTheChat() throws Exception {
        stub.route("/messages", call -> Answer.json(200, "{\"message\":{\"body\":{\"mid\":\"mid.out\"}}}"));
        MaxOutgoingMessage message = new MaxOutgoingMessage("<b>Готово</b>", "html", List.of(
                List.of(MaxButton.message("Меню кухни"), MaxButton.message("Бар")),
                List.of(MaxButton.requestContact("Согласиться и поделиться контактом")),
                List.of(MaxButton.callback("Подробнее", "text:подробнее"), MaxButton.link("Сайт", "https://aeris.bar/"))
        ), true);

        JsonNode answer = client(TOKEN).sendMessage(-7001L, message);

        assertThat(answer.path("message").path("body").path("mid").asText()).isEqualTo("mid.out");
        RecordingStubServer.Call call = stub.calls("/messages").getFirst();
        assertThat(call.method()).isEqualTo("POST");
        assertThat(call.query()).isEqualTo("chat_id=-7001");
        assertThat(call.header("Content-Type")).isEqualTo("application/json");
        assertThat(json.readTree(call.text())).isEqualTo(json.readTree("""
                {"text":"<b>Готово</b>","format":"html","notify":true,"attachments":[{"type":"inline_keyboard","payload":{"buttons":[
                  [{"type":"message","text":"Меню кухни"},{"type":"message","text":"Бар"}],
                  [{"type":"request_contact","text":"Согласиться и поделиться контактом"}],
                  [{"type":"callback","text":"Подробнее","payload":"text:подробнее"},{"type":"link","text":"Сайт","url":"https://aeris.bar/"}]
                ]}}]}
                """));
    }

    @Test
    void plainTextGoesWithoutFormatAndWithoutKeyboard() throws Exception {
        stub.route("/messages", call -> Answer.json(200, "{}"));

        client(TOKEN).sendMessage(5L, MaxOutgoingMessage.plain("Привет"));

        assertThat(json.readTree(stub.calls("/messages").getFirst().text()))
                .isEqualTo(json.readTree("{\"text\":\"Привет\",\"notify\":true,\"attachments\":[]}"));
    }

    @Test
    void callbackAnswerGoesToAnswersWithTheCallbackId() throws Exception {
        stub.route("/answers", call -> Answer.json(200, "{\"success\":true}"));

        client(TOKEN).answerCallback("cb-1", "Принято");

        RecordingStubServer.Call call = stub.calls("/answers").getFirst();
        assertThat(call.query()).isEqualTo("callback_id=cb-1");
        assertThat(json.readTree(call.text())).isEqualTo(json.readTree("{\"notification\":\"Принято\"}"));
    }

    @Test
    void apiErrorsCarryStatusAndCodeButNeverTheToken() {
        stub.route("/me", call -> Answer.json(401, "{\"code\":\"verify.token\",\"message\":\"Invalid access_token\"}"));

        assertThatThrownBy(() -> client(TOKEN).getMe())
                .isInstanceOfSatisfying(MaxApiException.class, e -> {
                    assertThat(e.status()).isEqualTo(401);
                    assertThat(e.code()).isEqualTo("verify.token");
                    assertThat(e.retryable()).isFalse();
                    assertThat(e.getMessage()).contains("Invalid access_token").doesNotContain(TOKEN);
                });
    }

    @Test
    void rateLimitAndServerErrorsAreRetryable() {
        stub.route("/updates", call -> Answer.json(429, "{\"code\":\"too.many.requests\",\"message\":\"slow down\"}"));

        assertThatThrownBy(() -> client(TOKEN).getUpdates(null, Duration.ofSeconds(1), 1))
                .isInstanceOfSatisfying(MaxApiException.class, e -> assertThat(e.retryable()).isTrue());
    }

    @Test
    void withoutATokenNothingLeavesTheProcess() {
        assertThatThrownBy(() -> client(" ").getMe())
                .isInstanceOf(MaxApiException.class)
                .hasMessageContaining("MAX_BOT_TOKEN");
        assertThat(stub.calls()).isEmpty();
    }

    @Test
    void anUnreadableCaBundleKeepsTheJvmTrustInsteadOfFailingStartup() {
        assertThat(MaxBotApiClient.httpClient("/nonexistent/russian_trusted_root_ca.pem")).isNotNull();
        assertThat(MaxBotApiClient.httpClient("")).isNotNull();
    }

    @Test
    void aReadableCaBundleIsAccepted() throws Exception {
        String pem = java.nio.file.Path.of(getClass().getResource("/certs/test-root-ca.pem").toURI()).toString();

        assertThat(MaxBotApiClient.httpClient(pem).sslContext()).isNotNull();
    }
}
