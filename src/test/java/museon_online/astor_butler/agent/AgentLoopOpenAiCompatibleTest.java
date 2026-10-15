package museon_online.astor_butler.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import museon_online.astor_butler.capability.CapabilityContext;
import museon_online.astor_butler.capability.CapabilityProperties;
import museon_online.astor_butler.capability.CapabilityRegistry;
import museon_online.astor_butler.capability.CapabilityResult;
import museon_online.astor_butler.capability.IdempotencyStore;
import museon_online.astor_butler.capability.tools.CapabilityFixtures;
import museon_online.astor_butler.capability.tools.ReservationCreateToolTest;
import museon_online.astor_butler.domain.booking.TableReservationService;
import museon_online.astor_butler.domain.booking.TableReservationStatus;
import museon_online.astor_butler.domain.ledger.LedgerWriter;
import museon_online.astor_butler.model.ModelChatMessage;
import museon_online.astor_butler.model.ModelTool;
import museon_online.astor_butler.model.OpenAiCompatibleModelGateway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.restclient.RestTemplateBuilder;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.test.web.client.MockRestServiceServer;

import java.sql.Connection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/** The agent loop over the real OpenAI-compatible gateway; only the HTTP service is a stub. */
@ExtendWith(OutputCaptureExtension.class)
class AgentLoopOpenAiCompatibleTest {

    private static final String BASE_URL = "https://models.test/v1";
    private static final String COMPLETIONS = BASE_URL + "/chat/completions";
    private static final String API_KEY = "sk-agent-loop-fixture";
    private static final String GUEST = "Мария Иванова, +79120000060, стол на двоих в 19:00";
    private static final List<ModelTool> TOOLS = List.of(
            new ModelTool("table_availability", "Свободные столы", null),
            new ModelTool("reservation_create", "Заявка на стол", null));
    private static final CapabilityContext CTX = new CapabilityContext(CapabilityContext.CONCIERGE, null, "c-1", null,
            "c-1:1", "Мария", null);

    private MockRestServiceServer server;
    private OpenAiCompatibleModelGateway gateway;
    private AgentProperties properties;
    private SimpleMeterRegistry meters;

    @BeforeEach
    void setUp() {
        AtomicReference<MockRestServiceServer> serverRef = new AtomicReference<>();
        gateway = new OpenAiCompatibleModelGateway(
                new RestTemplateBuilder(restTemplate -> serverRef.set(MockRestServiceServer.bindTo(restTemplate).build())),
                BASE_URL, API_KEY, "", "", "", "", 8000, 256, 0.1, true, "");
        server = serverRef.get();
        properties = new AgentProperties();
        properties.setModel("gigachat-3-pro");
        meters = new SimpleMeterRegistry();
    }

    private AgentLoop loop(CapabilityRegistry registry) {
        AgentLoop loop = new AgentLoop(gateway, registry, properties);
        loop.setDefaultProvider("openai-compatible");
        loop.setMeterRegistry(meters);
        return loop;
    }

    private static String call(String id, String tool, String argumentsJson) {
        return "{\"choices\": [{\"finish_reason\": \"tool_calls\", \"message\": {\"role\": \"assistant\", \"content\": null,"
                + " \"tool_calls\": [{\"id\": \"" + id + "\", \"type\": \"function\", \"function\": {\"name\": \"" + tool
                + "\", \"arguments\": " + quoted(argumentsJson) + "}}]}}],"
                + " \"usage\": {\"prompt_tokens\": 120, \"completion_tokens\": 14, \"total_tokens\": 134}}";
    }

    private static String answer(String text) {
        return "{\"choices\": [{\"finish_reason\": \"stop\", \"message\": {\"role\": \"assistant\", \"content\": \"" + text + "\"}}],"
                + " \"usage\": {\"prompt_tokens\": 150, \"completion_tokens\": 20, \"total_tokens\": 170}}";
    }

    private static String quoted(String json) {
        return "\"" + json.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private static List<ModelChatMessage> history() {
        return List.of(ModelChatMessage.system("Ты Батлер AERIS"), ModelChatMessage.user(GUEST));
    }

    @Test
    void twoToolCallsInARowThenAnAnswer() {
        CapabilityRegistry registry = mock(CapabilityRegistry.class);
        when(registry.invoke(eq("table_availability"), any(), anyMap()))
                .thenReturn(CapabilityResult.ok(Map.of("available", true, "time", "19:00")));
        when(registry.invoke(eq("reservation_create"), any(), anyMap()))
                .thenReturn(CapabilityResult.ok(Map.of("reservationId", 41, "status", "AWAITING_MANAGER_CONFIRMATION")));
        server.expect(once(), requestTo(COMPLETIONS))
                .andExpect(jsonPath("$.model").value("gigachat-3-pro"))
                .andExpect(jsonPath("$.tools.length()").value(2))
                .andRespond(withSuccess(call("call_a", "table_availability",
                        "{\"date\":\"2026-10-16\",\"time\":\"19:00\",\"partySize\":2}"), MediaType.APPLICATION_JSON));
        server.expect(once(), requestTo(COMPLETIONS))
                .andExpect(jsonPath("$.messages[2].tool_calls[0].id").value("call_a"))
                .andExpect(jsonPath("$.messages[3].role").value("tool"))
                .andExpect(jsonPath("$.messages[3].tool_call_id").value("call_a"))
                .andRespond(withSuccess(call("call_b", "reservation_create",
                        "{\"date\":\"2026-10-16\",\"time\":\"19:00\",\"partySize\":2,\"guestName\":\"Мария\"}"),
                        MediaType.APPLICATION_JSON));
        server.expect(once(), requestTo(COMPLETIONS))
                .andExpect(jsonPath("$.messages[4].tool_calls[0].id").value("call_b"))
                .andExpect(jsonPath("$.messages[5].tool_call_id").value("call_b"))
                .andExpect(jsonPath("$.messages[5].content").value(org.hamcrest.Matchers.containsString("AWAITING_MANAGER_CONFIRMATION")))
                .andRespond(withSuccess(answer("Заявка у хостес, она подтвердит стол."), MediaType.APPLICATION_JSON));

        AgentOutcome outcome = loop(registry).run(history(), TOOLS, CTX);

        server.verify();
        assertThat(outcome.fallback()).isFalse();
        assertThat(outcome.text()).isEqualTo("Заявка у хостес, она подтвердит стол.");
        assertThat(outcome.toolCalls()).isEqualTo(2);
        assertThat(outcome.uses()).extracting(AgentOutcome.ToolUse::tool).containsExactly("table_availability", "reservation_create");
        ArgumentCaptor<CapabilityContext> ctx = ArgumentCaptor.forClass(CapabilityContext.class);
        verify(registry, times(2)).invoke(any(), ctx.capture(), anyMap());
        assertThat(ctx.getAllValues()).extracting(CapabilityContext::idempotencyKey).containsExactly("c-1:1:1", "c-1:1:2");

        assertThat(meters.get("astor.agent.model.calls").tags("provider", "openai-compatible", "model", "gigachat-3-pro",
                "outcome", "tool_call").timer().count()).isEqualTo(2);
        assertThat(meters.get("astor.agent.model.calls").tags("outcome", "answer").timer().count()).isEqualTo(1);
        assertThat(meters.get("astor.agent.model.tokens").tags("direction", "in").counter().count()).isEqualTo(390.0);
        assertThat(meters.get("astor.agent.model.tokens").tags("direction", "out").counter().count()).isEqualTo(48.0);
        assertThat(meters.get("astor.agent.tool.calls").tags("tool", "reservation_create", "outcome", "ok").counter().count()).isEqualTo(1.0);
        assertThat(meters.get("astor.agent.turns").tags("outcome", "answered").counter().count()).isEqualTo(1.0);
    }

    @Test
    void anUnknownToolIsNeverRunAndBadArgumentsAreRefusedByTheTool() throws Exception {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:agent-openai-" + UUID.randomUUID() + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1", "sa", "");
        try (Connection schema = dataSource.getConnection()) {
            ScriptUtils.executeSqlScript(schema, new ClassPathResource("db/changelog/2026-10-15-capability-calls.sql"));
            unknownToolAndBadArguments(new IdempotencyStore(new JdbcTemplate(dataSource), new ObjectMapper()));
            try (var statement = schema.createStatement()) {
                statement.execute("SHUTDOWN");
            }
        }
    }

    private void unknownToolAndBadArguments(IdempotencyStore store) {
        TableReservationService reservations = mock(TableReservationService.class);
        when(reservations.createReservation(any())).thenReturn(CapabilityFixtures.order(41L, null,
                TableReservationStatus.AWAITING_MANAGER_CONFIRMATION));
        CapabilityProperties capabilities = new CapabilityProperties();
        capabilities.setEnabled(true);
        CapabilityRegistry registry = new CapabilityRegistry(List.of(ReservationCreateToolTest.tool(reservations)),
                mock(LedgerWriter.class), capabilities, store);
        server.expect(once(), requestTo(COMPLETIONS))
                .andRespond(withSuccess(call("call_1", "book_spaceship", "{}"), MediaType.APPLICATION_JSON));
        server.expect(once(), requestTo(COMPLETIONS))
                .andExpect(jsonPath("$.messages[3].content").value(org.hamcrest.Matchers.containsString("NOT_FOUND")))
                // Unreadable arguments arrive as {} and the tool's own validation answers them.
                .andRespond(withSuccess(call("call_2", "reservation_create", "{date: tomorrow"), MediaType.APPLICATION_JSON));
        server.expect(once(), requestTo(COMPLETIONS))
                .andExpect(jsonPath("$.messages[5].content").value(org.hamcrest.Matchers.containsString("BAD_ARGUMENT")))
                .andRespond(withSuccess(answer("Уточните дату, пожалуйста."), MediaType.APPLICATION_JSON));

        AgentOutcome outcome = loop(registry).run(history(), registry.asModelTools(), CTX);

        server.verify();
        assertThat(outcome.fallback()).isFalse();
        assertThat(outcome.text()).isEqualTo("Уточните дату, пожалуйста.");
        assertThat(outcome.results()).extracting(CapabilityResult::error)
                .containsExactly(CapabilityResult.NOT_FOUND, CapabilityResult.BAD_ARGUMENT);
        verify(reservations, never()).createReservation(any());
        assertThat(meters.get("astor.agent.tool.calls").tags("tool", "unknown", "outcome", "error").counter().count()).isEqualTo(1.0);
    }

    @Test
    void aRateLimitAfterAToolRanFallsBackWithoutAnotherCallAndLogsNoGuestData(CapturedOutput output) {
        CapabilityRegistry registry = mock(CapabilityRegistry.class);
        when(registry.invoke(eq("reservation_create"), any(), anyMap()))
                .thenReturn(CapabilityResult.ok(Map.of("reservationId", 41)));
        server.expect(once(), requestTo(COMPLETIONS))
                .andRespond(withSuccess(call("call_1", "reservation_create", "{\"guestName\":\"Мария Иванова\"}"),
                        MediaType.APPLICATION_JSON));
        server.expect(once(), requestTo(COMPLETIONS))
                .andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS).contentType(MediaType.APPLICATION_JSON).body(
                        "{\"error\": {\"message\": \"limit for " + GUEST + " with " + API_KEY + "\", \"code\": \"429\"}}"));

        AgentOutcome outcome = loop(registry).run(history(), TOOLS, CTX);

        // Exactly the two expected requests: the failed step is not retried, through this provider or another.
        server.verify();
        assertThat(outcome.fallback()).isTrue();
        assertThat(outcome.toolCalls()).isEqualTo(1);
        verify(registry, times(1)).invoke(any(), any(), anyMap());
        assertThat(meters.get("astor.agent.turns").tags("outcome", "fallback", "reason", "model_error").counter().count())
                .isEqualTo(1.0);
        assertThat(meters.get("astor.agent.model.calls").tags("outcome", "error").timer().count()).isEqualTo(1);
        assertThat(output.getAll())
                .contains("Agent turn falls back")
                .doesNotContain("Мария")
                .doesNotContain("79120000060")
                .doesNotContain(API_KEY);
    }

    @Test
    void aSlowProviderIsATimeoutFallback() {
        properties.setModelTimeoutMs(100);
        AgentLoop loop = new AgentLoop(request -> {
            try {
                Thread.sleep(2_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return null;
        }, mock(CapabilityRegistry.class), properties);
        loop.setDefaultProvider("openai-compatible");
        loop.setMeterRegistry(meters);

        AgentOutcome outcome = loop.run(history(), TOOLS, CTX);

        assertThat(outcome.fallback()).isTrue();
        assertThat(meters.get("astor.agent.turns").tags("reason", "timeout").counter().count()).isEqualTo(1.0);
        assertThat(meters.get("astor.agent.model.calls").tags("outcome", "timeout").timer().count()).isEqualTo(1);
    }

    @Test
    void aSpentDailyBudgetStopsModelCallsUntilTheNextDay() {
        CapabilityRegistry registry = mock(CapabilityRegistry.class);
        when(registry.invoke(eq("table_availability"), any(), anyMap())).thenReturn(CapabilityResult.ok(Map.of("available", true)));
        server.expect(once(), requestTo(COMPLETIONS)).andRespond(withSuccess(call("call_a", "table_availability", "{}"),
                MediaType.APPLICATION_JSON).header("x-litellm-response-cost", "0.03"));
        server.expect(once(), requestTo(COMPLETIONS)).andRespond(withSuccess(answer("Есть стол в 19:00. Оформить?"),
                MediaType.APPLICATION_JSON).header("x-litellm-response-cost", "0.03"));
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:agent-loop-budget-" + UUID.randomUUID() + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1", "sa", "");
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        jdbc.execute(budgetSchema());
        // 0.05 ₽ reserved per call against 0.10 ₽: the first turn's two calls (0.03 each) fit, a third reservation does not.
        AgentBudget budget = new AgentBudget(new java.math.BigDecimal("0.10"), new java.math.BigDecimal("0.05"),
                java.time.ZoneId.of("Asia/Yekaterinburg"), java.time.Clock.systemUTC(), jdbc);
        AgentLoop loop = loop(registry);
        loop.setBudget(budget);

        AgentOutcome first = loop.run(history(), TOOLS, CTX);
        AgentOutcome second = loop.run(history(), TOOLS, CTX);

        // Exactly the two calls of the first turn: the second turn never reaches the model.
        server.verify();
        assertThat(first.fallback()).isFalse();
        assertThat(budget.spentToday()).isEqualTo(0.06, org.assertj.core.data.Offset.offset(1e-9));
        assertThat(second.fallback()).isTrue();
        assertThat(meters.get("astor.agent.turns").tags("outcome", "fallback", "reason", "daily_budget").counter().count()).isEqualTo(1.0);
    }

    @Test
    void aFailedCallKeepsItsWholeReservation() {
        server.expect(once(), requestTo(COMPLETIONS)).andRespond(withStatus(HttpStatus.GATEWAY_TIMEOUT));
        JdbcTemplate jdbc = new JdbcTemplate(new DriverManagerDataSource(
                "jdbc:h2:mem:agent-loop-budget-" + UUID.randomUUID() + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1", "sa", ""));
        jdbc.execute(budgetSchema());
        AgentBudget budget = new AgentBudget(new java.math.BigDecimal("10"), new java.math.BigDecimal("1"),
                java.time.ZoneId.of("Asia/Yekaterinburg"), java.time.Clock.systemUTC(), jdbc);
        AgentLoop loop = loop(mock(CapabilityRegistry.class));
        loop.setBudget(budget);

        assertThat(loop.run(history(), TOOLS, CTX).fallback()).isTrue();
        assertThat(budget.spentToday()).isEqualTo(1.0, org.assertj.core.data.Offset.offset(1e-9));
    }

    private static String budgetSchema() {
        try {
            return new String(new ClassPathResource("db/changelog/2026-10-16-agent-budget.sql").getInputStream().readAllBytes(),
                    java.nio.charset.StandardCharsets.UTF_8).replaceAll("(?m)^--.*$", "");
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
