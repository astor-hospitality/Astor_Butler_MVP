package museon_online.astor_butler.model;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.restclient.RestTemplateBuilder;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.HttpClientErrorException;

import java.net.SocketTimeoutException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
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

/** {@link OpenAiCompatibleModelGateway#chat}: the OpenAI {@code tools} / {@code tool_calls} contract. */
class OpenAiCompatibleToolCallingTest {

    private static final String BASE_URL = "https://models.test/v1";
    private static final String COMPLETIONS = BASE_URL + "/chat/completions";
    private static final String API_KEY = "sk-test-fixture-key";
    /** Guest words that must never reach an error message or a hidden audit. */
    private static final String GUEST_TEXT = "Мария, стол на двоих, +79120000060";

    private static final ModelTool FIND_SLOTS = new ModelTool(
            "find_slots",
            "Свободные столы на дату",
            Map.of("type", "object",
                    "properties", Map.of("date", Map.of("type", "string"), "guests", Map.of("type", "integer")),
                    "required", List.of("date")));

    private static String reply(String message, String finish) {
        return "{\"choices\": [{\"finish_reason\": \"" + finish + "\", \"message\": " + message + "}],"
                + " \"usage\": {\"prompt_tokens\": 120, \"completion_tokens\": 14, \"total_tokens\": 134}}";
    }

    private static ModelChatRequest request(List<ModelChatMessage> messages, List<ModelTool> tools) {
        return new ModelChatRequest(messages, tools, ModelProfile.FRONTLINE, "butler_agent",
                Map.of("chatId", 42L, "scenario", "BUTLER_AGENT"), "gigachat-3-pro", 512);
    }

    @Test
    void sendsToolsAndParsesTheFirstToolCallWithItsId() {
        Fixture fixture = fixture();
        ModelInteractionAuditRepository audit = mock(ModelInteractionAuditRepository.class);
        fixture.gateway().setAuditRepository(audit);
        fixture.server().expect(once(), requestTo(COMPLETIONS))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "Bearer " + API_KEY))
                .andExpect(jsonPath("$.model").value("gigachat-3-pro"))
                .andExpect(jsonPath("$.max_tokens").value(512))
                .andExpect(jsonPath("$.stream").value(false))
                .andExpect(jsonPath("$.messages[0].role").value("system"))
                .andExpect(jsonPath("$.messages[1].role").value("user"))
                .andExpect(jsonPath("$.tools[0].type").value("function"))
                .andExpect(jsonPath("$.tools[0].function.name").value("find_slots"))
                .andExpect(jsonPath("$.tools[0].function.description").value("Свободные столы на дату"))
                .andExpect(jsonPath("$.tools[0].function.parameters.required[0]").value("date"))
                .andExpect(jsonPath("$.tool_choice").value("auto"))
                .andExpect(jsonPath("$.parallel_tool_calls").doesNotExist())
                .andExpect(jsonPath("$.functions").doesNotExist())
                .andRespond(withSuccess(reply("""
                        {"role": "assistant", "content": null, "tool_calls": [
                          {"id": "call_abc", "type": "function",
                           "function": {"name": "find_slots", "arguments": "{\\"date\\": \\"2026-10-16\\", \\"guests\\": 2}"}}
                        ]}
                        """, "tool_calls"), MediaType.APPLICATION_JSON));

        ModelChatResponse response = fixture.gateway().chat(request(List.of(
                ModelChatMessage.system("Ты Батлер"), ModelChatMessage.user("Стол на завтра на двоих")), List.of(FIND_SLOTS)));

        ModelToolCall call = response.toolCall().orElseThrow();
        assertThat(call.name()).isEqualTo("find_slots");
        assertThat(call.id()).isEqualTo("call_abc");
        assertThat(call.arguments()).containsEntry("date", "2026-10-16").containsEntry("guests", 2);
        assertThat(response.text()).isEmpty();
        assertThat(response.provider()).isEqualTo("openai-compatible");
        assertThat(response.model()).isEqualTo("gigachat-3-pro");
        assertThat(response.metadata()).containsEntry("finishReason", "tool_calls");
        assertThat(response.metadata().get("usage")).isEqualTo(
                Map.of("prompt_tokens", 120, "completion_tokens", 14, "total_tokens", 134));
        fixture.server().verify();

        ArgumentCaptor<ModelInteractionAuditRecord> captor = ArgumentCaptor.forClass(ModelInteractionAuditRecord.class);
        verify(audit).capture(captor.capture());
        ModelInteractionAuditRecord record = captor.getValue();
        assertThat(record.provider()).isEqualTo("openai-compatible");
        assertThat(record.model()).isEqualTo("gigachat-3-pro");
        assertThat(record.chatId()).isEqualTo(42L);
        assertThat(record.success()).isTrue();
        assertThat(record.metadata()).containsEntry("capability", "TOOL_CALLING").containsEntry("toolCall", "find_slots");
        // Tool arguments are never stored, only the tool's name.
        assertThat(record.responseText()).isEqualTo("function_call:find_slots").doesNotContain("2026-10-16");
    }

    @Test
    void toolResultsGoBackAsToolMessagesUnderTheirCallIds() {
        Fixture fixture = fixture();
        fixture.server().expect(once(), requestTo(COMPLETIONS))
                .andExpect(jsonPath("$.messages[1].role").value("assistant"))
                .andExpect(jsonPath("$.messages[1].content").doesNotExist())
                .andExpect(jsonPath("$.messages[1].tool_calls[0].id").value("call_abc"))
                .andExpect(jsonPath("$.messages[1].tool_calls[0].type").value("function"))
                .andExpect(jsonPath("$.messages[1].tool_calls[0].function.name").value("find_slots"))
                .andExpect(jsonPath("$.messages[1].tool_calls[0].function.arguments").value("{\"date\":\"2026-10-16\"}"))
                .andExpect(jsonPath("$.messages[2].role").value("tool"))
                .andExpect(jsonPath("$.messages[2].tool_call_id").value("call_abc"))
                .andExpect(jsonPath("$.messages[2].name").doesNotExist())
                .andExpect(jsonPath("$.messages[2].content").value("{\"ok\":true,\"facts\":{\"slots\":[\"19:00\"]}}"))
                // A call without a provider id (GigaChat history) gets a generated one, and its result the same.
                .andExpect(jsonPath("$.messages[3].tool_calls[0].id").value("call00001"))
                .andExpect(jsonPath("$.messages[4].tool_call_id").value("call00001"))
                .andExpect(jsonPath("$.messages[5].tool_calls[0].id").value("call00002"))
                .andExpect(jsonPath("$.messages[6].tool_call_id").value("call00002"))
                .andRespond(withSuccess(reply("""
                        {"role": "assistant", "content": "Есть 19:00, держу заявку для хостес."}
                        """, "stop"), MediaType.APPLICATION_JSON));

        ModelChatResponse response = fixture.gateway().chat(request(List.of(
                ModelChatMessage.user("Стол на завтра"),
                ModelChatMessage.assistantCall("", new ModelToolCall("find_slots", Map.of("date", "2026-10-16"), "call_abc")),
                ModelChatMessage.functionResult("find_slots", "{\"ok\":true,\"facts\":{\"slots\":[\"19:00\"]}}"),
                ModelChatMessage.assistantCall("", new ModelToolCall("find_slots", Map.of())),
                ModelChatMessage.functionResult("find_slots", "{\"ok\":true}"),
                ModelChatMessage.assistantCall("", new ModelToolCall("hold_table", Map.of("slot", "19:00"))),
                ModelChatMessage.functionResult("hold_table", "{\"ok\":true}")), List.of(FIND_SLOTS)));

        assertThat(response.text()).isEqualTo("Есть 19:00, держу заявку для хостес.");
        assertThat(response.toolCall()).isEmpty();
        fixture.server().verify();
    }

    @Test
    void aResultWithoutItsCallIsSentAsAUserLine() {
        List<Map<String, Object>> messages = OpenAiCompatibleModelGateway.chatMessages(List.of(
                ModelChatMessage.functionResult("find_slots", "{\"ok\":false}")));

        assertThat(messages).singleElement().satisfies(message -> {
            assertThat(message).containsEntry("role", "user").doesNotContainKey("tool_call_id");
            assertThat((String) message.get("content")).contains("find_slots").contains("{\"ok\":false}");
        });
    }

    @Test
    void severalToolCallsInOneAnswerTakeTheFirst() {
        Fixture fixture = fixture();
        fixture.server().expect(once(), requestTo(COMPLETIONS))
                .andRespond(withSuccess(reply("""
                        {"role": "assistant", "content": "", "tool_calls": [
                          {"id": "call_1", "type": "function", "function": {"name": "find_slots", "arguments": "{\\"date\\": \\"2026-10-16\\"}"}},
                          {"id": "call_2", "type": "function", "function": {"name": "hold_table", "arguments": "{}"}}
                        ]}
                        """, "tool_calls"), MediaType.APPLICATION_JSON));

        ModelChatResponse response = fixture.gateway().chat(request(List.of(ModelChatMessage.user("Стол")), List.of(FIND_SLOTS)));

        assertThat(response.toolCall().orElseThrow().id()).isEqualTo("call_1");
        assertThat(response.metadata()).containsEntry("tool_calls_ignored", 1);
    }

    @Test
    void unreadableArgumentsBecomeEmptyAndAreFlagged() {
        Fixture fixture = fixture();
        fixture.server().expect(once(), requestTo(COMPLETIONS))
                .andRespond(withSuccess(reply("""
                        {"role": "assistant", "tool_calls": [
                          {"id": "call_1", "type": "function", "function": {"name": "find_slots", "arguments": "{not json"}}
                        ]}
                        """, "tool_calls"), MediaType.APPLICATION_JSON));
        fixture.server().expect(once(), requestTo(COMPLETIONS))
                .andRespond(withSuccess(reply("""
                        {"role": "assistant", "tool_calls": [
                          {"id": "call_2", "type": "function", "function": {"name": "find_slots", "arguments": "[1, 2]"}}
                        ]}
                        """, "tool_calls"), MediaType.APPLICATION_JSON));
        fixture.server().expect(once(), requestTo(COMPLETIONS))
                .andRespond(withSuccess(reply("""
                        {"role": "assistant", "tool_calls": [
                          {"id": "call_3", "type": "function", "function": {"name": "find_slots", "arguments": {"date": "2026-10-16"}}}
                        ]}
                        """, "tool_calls"), MediaType.APPLICATION_JSON));

        ModelChatResponse broken = fixture.gateway().chat(request(List.of(ModelChatMessage.user("Стол")), List.of(FIND_SLOTS)));
        ModelChatResponse array = fixture.gateway().chat(request(List.of(ModelChatMessage.user("Стол")), List.of(FIND_SLOTS)));
        ModelChatResponse object = fixture.gateway().chat(request(List.of(ModelChatMessage.user("Стол")), List.of(FIND_SLOTS)));

        assertThat(broken.toolCall().orElseThrow().arguments()).isEmpty();
        assertThat(broken.metadata()).containsEntry("arguments_unparsed", true);
        assertThat(array.toolCall().orElseThrow().arguments()).isEmpty();
        assertThat(array.metadata()).containsEntry("arguments_unparsed", true);
        assertThat(object.toolCall().orElseThrow().arguments()).containsEntry("date", "2026-10-16");
        assertThat(object.metadata()).doesNotContainKey("arguments_unparsed");
    }

    @Test
    void aLegacyFunctionCallIsReadToo() {
        Fixture fixture = fixture();
        fixture.server().expect(once(), requestTo(COMPLETIONS))
                .andRespond(withSuccess(reply("""
                        {"role": "assistant", "content": "", "function_call": {"name": "find_slots", "arguments": "{\\"date\\": \\"2026-10-16\\"}"}}
                        """, "function_call"), MediaType.APPLICATION_JSON));

        ModelToolCall call = fixture.gateway().chat(request(List.of(ModelChatMessage.user("Стол")), List.of(FIND_SLOTS)))
                .toolCall().orElseThrow();

        assertThat(call.name()).isEqualTo("find_slots");
        assertThat(call.id()).isNull();
        assertThat(call.arguments()).containsEntry("date", "2026-10-16");
    }

    @Test
    void withoutToolsItIsAPlainCompletionOnTheConfiguredModel() {
        Fixture fixture = fixture();
        fixture.server().expect(once(), requestTo(COMPLETIONS))
                .andExpect(jsonPath("$.model").value("test-model"))
                .andExpect(jsonPath("$.max_tokens").value(128))
                .andExpect(jsonPath("$.tools").doesNotExist())
                .andExpect(jsonPath("$.tool_choice").doesNotExist())
                .andExpect(jsonPath("$.response_format").doesNotExist())
                .andRespond(withSuccess(reply("""
                        {"role": "assistant", "content": "Добрый вечер!"}
                        """, "stop"), MediaType.APPLICATION_JSON));

        ModelChatResponse response = fixture.gateway().chat(new ModelChatRequest(
                List.of(ModelChatMessage.user("Привет")), List.of(), ModelProfile.FRONTLINE, "visit-summary", Map.of()));

        assertThat(response.text()).isEqualTo("Добрый вечер!");
        assertThat(response.toolCall()).isEmpty();
        assertThat(response.model()).isEqualTo("test-model");
    }

    @Test
    void parallelToolCallsIsSentOnlyWhenConfigured() {
        Fixture fixture = fixture();
        fixture.gateway().setParallelToolCalls("false");
        fixture.server().expect(once(), requestTo(COMPLETIONS))
                .andExpect(jsonPath("$.parallel_tool_calls").value(false))
                .andRespond(withSuccess(reply("{\"role\": \"assistant\", \"content\": \"Да\"}", "stop"), MediaType.APPLICATION_JSON));

        fixture.gateway().chat(request(List.of(ModelChatMessage.user("Стол")), List.of(FIND_SLOTS)));

        fixture.server().verify();
    }

    @ParameterizedTest
    @ValueSource(ints = {401, 402, 403, 408, 429, 500, 502, 503})
    void cannotBeUsedNowIsModelUnavailableWithoutTheBody(int status) {
        Fixture fixture = fixture();
        ModelInteractionAuditRepository audit = mock(ModelInteractionAuditRepository.class);
        fixture.gateway().setAuditRepository(audit);
        fixture.server().expect(once(), requestTo(COMPLETIONS))
                .andRespond(withStatus(HttpStatus.valueOf(status)).contentType(MediaType.APPLICATION_JSON).body(
                        "{\"error\": {\"message\": \"rejected: " + GUEST_TEXT + " key " + API_KEY + "\", \"type\": \"rate_limit_error\", \"code\": \""
                                + status + "\"}}"));

        Throwable error = catchThrowable(() -> fixture.gateway().chat(request(
                List.of(ModelChatMessage.user(GUEST_TEXT)), List.of(FIND_SLOTS))));

        assertThat(error).isInstanceOf(ModelUnavailableException.class)
                .hasMessageContaining("HTTP " + status)
                .hasMessageContaining("code " + status)
                .hasMessageNotContaining("Мария")
                .hasMessageNotContaining("79120000060")
                .hasMessageNotContaining(API_KEY);
        // The cause names the status for the call log, and carries no body either.
        assertThat(error.getCause()).hasMessageNotContaining("Мария").hasMessageNotContaining(API_KEY);
        if (error.getCause() instanceof HttpClientErrorException cause) {
            assertThat(cause.getResponseBodyAsString()).isEmpty();
        }
        ArgumentCaptor<ModelInteractionAuditRecord> captor = ArgumentCaptor.forClass(ModelInteractionAuditRecord.class);
        verify(audit).capture(captor.capture());
        assertThat(captor.getValue().success()).isFalse();
        assertThat(captor.getValue().errorMessage()).doesNotContain("Мария").doesNotContain(API_KEY);
    }

    @Test
    void aRejectedRequestIsNotUnavailableAndCarriesNoBody() {
        Fixture fixture = fixture();
        fixture.server().expect(once(), requestTo(COMPLETIONS))
                .andRespond(withStatus(HttpStatus.BAD_REQUEST).contentType(MediaType.APPLICATION_JSON).body("""
                        {"error": {"message": "Invalid message: Мария, стол на двоих", "type": "invalid_request_error",
                                   "param": "messages", "code": "400"}}
                        """));

        assertThatThrownBy(() -> fixture.gateway().chat(request(List.of(ModelChatMessage.user(GUEST_TEXT)), List.of(FIND_SLOTS))))
                .isInstanceOf(IllegalStateException.class)
                .isNotInstanceOf(ModelUnavailableException.class)
                .hasMessageContaining("HTTP 400 (code 400, type invalid_request_error, param messages)")
                .hasMessageNotContaining("Мария");
    }

    @Test
    void aTimeoutIsModelUnavailable() {
        Fixture fixture = fixture();
        fixture.server().expect(once(), requestTo(COMPLETIONS))
                .andRespond(withException(new SocketTimeoutException("Read timed out")));

        assertThatThrownBy(() -> fixture.gateway().chat(request(List.of(ModelChatMessage.user("Привет")), List.of())))
                .isInstanceOf(ModelUnavailableException.class);
    }

    @Test
    void anErrorHintKeepsShortIdentifiersOnly() {
        assertThat(OpenAiCompatibleModelGateway.errorHint("""
                {"error": {"code": "429", "type": "rate limit for Мария", "param": "model"}}
                """)).isEqualTo(" (code 429, param model)");
        assertThat(OpenAiCompatibleModelGateway.errorHint("not json")).isEmpty();
        assertThat(OpenAiCompatibleModelGateway.errorHint("")).isEmpty();
    }

    @Test
    void withoutConsentTheAuditKeepsNoWords() {
        Fixture fixture = fixture();
        ModelInteractionAuditRepository audit = mock(ModelInteractionAuditRepository.class);
        fixture.gateway().setAuditRepository(audit);
        fixture.server().expect(once(), requestTo(COMPLETIONS))
                .andRespond(withSuccess(reply("{\"role\": \"assistant\", \"content\": \"Мария, жду вас в 19:00\"}", "stop"),
                        MediaType.APPLICATION_JSON));

        fixture.gateway().chat(new ModelChatRequest(List.of(ModelChatMessage.user(GUEST_TEXT)), List.of(FIND_SLOTS),
                ModelProfile.FRONTLINE, "butler_agent", Map.of("auditContent", false), "gigachat-3-pro"));

        ArgumentCaptor<ModelInteractionAuditRecord> captor = ArgumentCaptor.forClass(ModelInteractionAuditRecord.class);
        verify(audit).capture(captor.capture());
        assertThat(captor.getValue().prompt()).contains(ModelChatAudit.HIDDEN).doesNotContain("Мария");
        assertThat(captor.getValue().responseText()).isEqualTo(ModelChatAudit.HIDDEN);
    }

    private static Fixture fixture() {
        AtomicReference<MockRestServiceServer> serverRef = new AtomicReference<>();
        OpenAiCompatibleModelGateway gateway = new OpenAiCompatibleModelGateway(
                new RestTemplateBuilder(restTemplate -> serverRef.set(MockRestServiceServer.bindTo(restTemplate).build())),
                BASE_URL, API_KEY, "test-model", "", "", "", 8000, 128, 0.1, true, "");
        return new Fixture(gateway, serverRef.get());
    }

    private record Fixture(OpenAiCompatibleModelGateway gateway, MockRestServiceServer server) {
    }
}
