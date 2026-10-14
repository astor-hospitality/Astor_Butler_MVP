package museon_online.astor_butler.model;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.restclient.RestTemplateBuilder;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;

import java.net.SocketTimeoutException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class GigaChatToolCallingTest {

    private static final String OAUTH = "https://ngw.test:9443/api/v2/oauth";
    private static final String BASE_URL = "https://gigachat.test/api/v1";
    private static final String COMPLETIONS = BASE_URL + "/chat/completions";
    private static final String AUTH_KEY = "YmFzZTY0LWZpeHR1cmU=";

    private static final ModelTool FIND_SLOTS = new ModelTool(
            "find_slots",
            "Свободные столы на дату",
            Map.of("type", "object",
                    "properties", Map.of("date", Map.of("type", "string"), "guests", Map.of("type", "integer")),
                    "required", List.of("date")));

    private static String tokenJson(String token) {
        return "{\"access_token\": \"" + token + "\", \"expires_at\": " + (System.currentTimeMillis() + 30 * 60_000) + "}";
    }

    private static String reply(String message) {
        return "{\"choices\": [{\"finish_reason\": \"function_call\", \"message\": " + message + "}],"
                + " \"usage\": {\"prompt_tokens\": 10, \"completion_tokens\": 5, \"total_tokens\": 15}}";
    }

    private static ModelChatRequest request(List<ModelChatMessage> messages, List<ModelTool> tools) {
        return new ModelChatRequest(messages, tools, ModelProfile.FRONTLINE, "agent-turn", Map.of("chatId", 42L));
    }

    @Test
    void sendsFunctionsAndParsesAFunctionCall() {
        Fixture fixture = fixture();
        ModelInteractionAuditRepository audit = mock(ModelInteractionAuditRepository.class);
        fixture.gateway().setAuditRepository(audit);
        fixture.expectToken();
        fixture.server().expect(once(), requestTo(COMPLETIONS))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "Bearer tok-1"))
                .andExpect(jsonPath("$.model").value("GigaChat"))
                .andExpect(jsonPath("$.messages[0].role").value("system"))
                .andExpect(jsonPath("$.messages[1].role").value("user"))
                .andExpect(jsonPath("$.messages[1].content").value("Стол на завтра на двоих"))
                .andExpect(jsonPath("$.functions[0].name").value("find_slots"))
                .andExpect(jsonPath("$.functions[0].description").value("Свободные столы на дату"))
                .andExpect(jsonPath("$.functions[0].parameters.type").value("object"))
                .andExpect(jsonPath("$.functions[0].parameters.required[0]").value("date"))
                .andExpect(jsonPath("$.function_call").value("auto"))
                .andRespond(withSuccess(reply("""
                        {"role": "assistant", "content": "", "function_call": {"name": "find_slots", "arguments": {"date": "2026-10-15", "guests": 2}}}
                        """), MediaType.APPLICATION_JSON));

        ModelChatResponse response = fixture.gateway().chat(request(List.of(
                new ModelChatMessage("system", "Ты Батлер", null, null),
                new ModelChatMessage("user", "Стол на завтра на двоих", null, null)), List.of(FIND_SLOTS)));

        assertThat(response.toolCall()).isPresent();
        assertThat(response.toolCall().get().name()).isEqualTo("find_slots");
        assertThat(response.toolCall().get().arguments()).containsEntry("date", "2026-10-15").containsEntry("guests", 2);
        assertThat(response.provider()).isEqualTo("gigachat");
        assertThat(response.model()).isEqualTo("GigaChat");
        assertThat(response.latency()).isNotNull();
        fixture.server().verify();

        ArgumentCaptor<ModelInteractionAuditRecord> captor = ArgumentCaptor.forClass(ModelInteractionAuditRecord.class);
        verify(audit).capture(captor.capture());
        ModelInteractionAuditRecord record = captor.getValue();
        assertThat(record.purpose()).isEqualTo("agent-turn");
        assertThat(record.chatId()).isEqualTo(42L);
        assertThat(record.success()).isTrue();
        assertThat(record.metadata()).containsEntry("capability", "TOOL_CALLING").containsEntry("toolCall", "find_slots");
        assertThat(record.responseText()).doesNotContain("2026-10-15");
    }

    @Test
    void argumentsAsAJsonStringAreParsedToo() {
        Fixture fixture = fixture();
        fixture.expectToken();
        fixture.server().expect(once(), requestTo(COMPLETIONS))
                .andRespond(withSuccess(reply("""
                        {"role": "assistant", "content": "", "function_call": {"name": "find_slots", "arguments": "{\\"date\\": \\"2026-10-15\\", \\"guests\\": 4}"}}
                        """), MediaType.APPLICATION_JSON));
        fixture.server().expect(once(), requestTo(COMPLETIONS))
                .andRespond(withSuccess(reply("""
                        {"role": "assistant", "content": "", "function_call": {"name": "find_slots", "arguments": "{not json"}}
                        """), MediaType.APPLICATION_JSON));

        ModelChatResponse parsed = fixture.gateway().chat(request(
                List.of(new ModelChatMessage("user", "Стол на четверых", null, null)), List.of(FIND_SLOTS)));
        ModelChatResponse malformed = fixture.gateway().chat(request(
                List.of(new ModelChatMessage("user", "Стол", null, null)), List.of(FIND_SLOTS)));

        assertThat(parsed.toolCall().orElseThrow().arguments()).containsEntry("date", "2026-10-15").containsEntry("guests", 4);
        assertThat(parsed.metadata()).doesNotContainKey("arguments_unparsed");
        assertThat(malformed.toolCall().orElseThrow().name()).isEqualTo("find_slots");
        assertThat(malformed.toolCall().orElseThrow().arguments()).isEmpty();
        assertThat(malformed.metadata()).containsEntry("arguments_unparsed", true);
    }

    @Test
    void plainAnswerHasNoToolCall() {
        Fixture fixture = fixture();
        fixture.expectToken();
        fixture.server().expect(once(), requestTo(COMPLETIONS))
                .andExpect(jsonPath("$.model").value("GigaChat-3-Pro"))
                .andExpect(jsonPath("$.functions").doesNotExist())
                .andExpect(jsonPath("$.function_call").doesNotExist())
                .andRespond(withSuccess(reply("""
                        {"role": "assistant", "content": "Добрый вечер!"}
                        """), MediaType.APPLICATION_JSON));

        ModelChatResponse response = fixture.gateway().chat(new ModelChatRequest(
                List.of(new ModelChatMessage("user", "Привет", null, null)), List.of(),
                ModelProfile.FRONTLINE, "agent-turn", Map.of(), "GigaChat-3-Pro"));

        assertThat(response.text()).isEqualTo("Добрый вечер!");
        assertThat(response.toolCall()).isEmpty();
        assertThat(response.model()).isEqualTo("GigaChat-3-Pro");
    }

    @Test
    void maxTokensOfTheRequestReplacesTheConfiguredOne() {
        Fixture fixture = fixture();
        fixture.expectToken();
        fixture.server().expect(once(), requestTo(COMPLETIONS))
                .andExpect(jsonPath("$.max_tokens").value(900))
                .andRespond(withSuccess(reply("""
                        {"role": "assistant", "content": "{}"}
                        """), MediaType.APPLICATION_JSON));
        fixture.server().expect(once(), requestTo(COMPLETIONS))
                .andExpect(jsonPath("$.max_tokens").value(128))
                .andRespond(withSuccess(reply("""
                        {"role": "assistant", "content": "{}"}
                        """), MediaType.APPLICATION_JSON));

        fixture.gateway().chat(new ModelChatRequest(List.of(new ModelChatMessage("user", "Саммари", null, null)), List.of(),
                ModelProfile.FRONTLINE, "visit-summary", Map.of(), null, 900));
        fixture.gateway().chat(request(List.of(new ModelChatMessage("user", "Привет", null, null)), List.of()));

        fixture.server().verify();
    }

    @Test
    void functionResultIsSentWithFunctionRole() {
        Fixture fixture = fixture();
        fixture.expectToken();
        fixture.server().expect(once(), requestTo(COMPLETIONS))
                .andExpect(jsonPath("$.messages[1].role").value("assistant"))
                .andExpect(jsonPath("$.messages[1].function_call.name").value("find_slots"))
                .andExpect(jsonPath("$.messages[1].function_call.arguments.date").value("2026-10-15"))
                .andExpect(jsonPath("$.messages[2].role").value("function"))
                .andExpect(jsonPath("$.messages[2].name").value("find_slots"))
                .andExpect(jsonPath("$.messages[2].content").value("{\"slots\":[\"19:00\",\"21:00\"]}"))
                .andRespond(withSuccess(reply("""
                        {"role": "assistant", "content": "Есть 19:00 и 21:00"}
                        """), MediaType.APPLICATION_JSON));

        ModelChatResponse response = fixture.gateway().chat(request(List.of(
                new ModelChatMessage("user", "Стол на завтра", null, null),
                new ModelChatMessage("assistant", "", null, new ModelToolCall("find_slots", Map.of("date", "2026-10-15"))),
                new ModelChatMessage("function", "{\"slots\":[\"19:00\",\"21:00\"]}", "find_slots", null)), List.of(FIND_SLOTS)));

        assertThat(response.text()).isEqualTo("Есть 19:00 и 21:00");
        assertThat(response.toolCall()).isEmpty();
        fixture.server().verify();
    }

    @Test
    void http402IsAModelUnavailableError() {
        Fixture fixture = fixture();
        ModelInteractionAuditRepository audit = mock(ModelInteractionAuditRepository.class);
        fixture.gateway().setAuditRepository(audit);
        fixture.expectToken();
        fixture.server().expect(once(), requestTo(COMPLETIONS))
                .andRespond(withStatus(HttpStatus.PAYMENT_REQUIRED));

        assertThatThrownBy(() -> fixture.gateway().chat(request(
                List.of(new ModelChatMessage("user", "Привет", null, null)), List.of(FIND_SLOTS))))
                .isInstanceOf(ModelUnavailableException.class);

        ArgumentCaptor<ModelInteractionAuditRecord> captor = ArgumentCaptor.forClass(ModelInteractionAuditRecord.class);
        verify(audit).capture(captor.capture());
        assertThat(captor.getValue().success()).isFalse();
    }

    @Test
    void forbiddenAfterOneTokenRefreshIsAModelUnavailableError() {
        Fixture fixture = fixture();
        fixture.expectToken();
        fixture.server().expect(once(), requestTo(COMPLETIONS))
                .andRespond(withStatus(HttpStatus.FORBIDDEN));
        fixture.server().expect(once(), requestTo(OAUTH))
                .andRespond(withSuccess(tokenJson("tok-2"), MediaType.APPLICATION_JSON));
        fixture.server().expect(once(), requestTo(COMPLETIONS))
                .andExpect(header("Authorization", "Bearer tok-2"))
                .andRespond(withStatus(HttpStatus.UNAUTHORIZED));

        assertThatThrownBy(() -> fixture.gateway().chat(request(
                List.of(new ModelChatMessage("user", "Привет", null, null)), List.of())))
                .isInstanceOf(ModelUnavailableException.class);
        fixture.server().verify();
    }

    @Test
    void timeoutIsAModelUnavailableError() {
        Fixture fixture = fixture();
        fixture.expectToken();
        fixture.server().expect(once(), requestTo(COMPLETIONS))
                .andRespond(withException(new SocketTimeoutException("Read timed out")));

        assertThatThrownBy(() -> fixture.gateway().chat(request(
                List.of(new ModelChatMessage("user", "Привет", null, null)), List.of())))
                .isInstanceOf(ModelUnavailableException.class);
    }

    private static Fixture fixture() {
        AtomicReference<MockRestServiceServer> serverRef = new AtomicReference<>();
        GigaChatModelGateway gateway = new GigaChatModelGateway(
                new RestTemplateBuilder(restTemplate ->
                        serverRef.set(MockRestServiceServer.bindTo(restTemplate).build())),
                OAUTH, BASE_URL, AUTH_KEY, "GIGACHAT_API_PERS", "GigaChat", "", "", "Embeddings",
                "", 8000, 128, 128, 0.1);
        return new Fixture(gateway, serverRef.get());
    }

    private record Fixture(GigaChatModelGateway gateway, MockRestServiceServer server) {
        void expectToken() {
            server.expect(once(), requestTo(OAUTH))
                    .andRespond(withSuccess(tokenJson("tok-1"), MediaType.APPLICATION_JSON));
        }
    }
}
