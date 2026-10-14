package museon_online.astor_butler.agent;

import museon_online.astor_butler.capability.CapabilityContext;
import museon_online.astor_butler.capability.CapabilityRegistry;
import museon_online.astor_butler.capability.CapabilityResult;
import museon_online.astor_butler.model.ModelChatMessage;
import museon_online.astor_butler.model.ModelChatRequest;
import museon_online.astor_butler.model.ModelChatResponse;
import museon_online.astor_butler.model.ModelTool;
import museon_online.astor_butler.model.ModelToolCall;
import museon_online.astor_butler.model.ModelUnavailableException;
import museon_online.astor_butler.model.ToolCallingModel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentLoopTest {

    private static final List<ModelTool> TOOLS = List.of(
            new ModelTool("table_availability", "Свободные столы", null),
            new ModelTool("reservation_create", "Заявка на стол", null));
    private static final CapabilityContext CTX = new CapabilityContext(CapabilityContext.CONCIERGE, null, "c-1", null,
            "c-1:1", "Мария", null);

    private ToolCallingModel model;
    private CapabilityRegistry registry;
    private AgentProperties properties;
    private AgentLoop loop;
    private final List<ModelChatRequest> requests = new ArrayList<>();

    @BeforeEach
    void setUp() {
        model = mock(ToolCallingModel.class);
        registry = mock(CapabilityRegistry.class);
        properties = new AgentProperties();
        properties.setModel("GigaChat-3-Pro");
        loop = new AgentLoop(model, registry, properties);
    }

    private static ModelChatResponse call(String tool, Map<String, Object> args) {
        return new ModelChatResponse("", Optional.of(new ModelToolCall(tool, args)), "gigachat", "GigaChat-3-Pro", Duration.ZERO);
    }

    private static ModelChatResponse text(String text) {
        return new ModelChatResponse(text, Optional.empty(), "gigachat", "GigaChat-3-Pro", Duration.ZERO);
    }

    private List<ModelChatMessage> history() {
        return List.of(ModelChatMessage.system("Ты Батлер"), ModelChatMessage.user("Стол на двоих в 13:00"));
    }

    @Test
    void toolCallThenAnswer() {
        when(model.chat(any())).thenAnswer(invocation -> {
            requests.add(invocation.getArgument(0));
            return requests.size() == 1 ? call("table_availability", Map.of("date", "2026-10-15", "time", "13:00", "partySize", 2))
                    : text("В 13:00 занято, есть 13:30 у окна.");
        });
        when(registry.invoke(eq("table_availability"), any(), anyMap()))
                .thenReturn(CapabilityResult.ok(Map.of("available", false, "alternatives", List.of("13:30"))));

        AgentOutcome outcome = loop.run(history(), TOOLS, CTX);

        assertThat(outcome.fallback()).isFalse();
        assertThat(outcome.text()).isEqualTo("В 13:00 занято, есть 13:30 у окна.");
        assertThat(outcome.toolCalls()).isEqualTo(1);
        assertThat(outcome.results()).hasSize(1);
        assertThat(outcome.uses()).extracting(AgentOutcome.ToolUse::tool).containsExactly("table_availability");
        ModelChatRequest first = requests.get(0);
        assertThat(first.model()).isEqualTo("GigaChat-3-Pro");
        assertThat(first.maxTokens()).isEqualTo(512);
        assertThat(first.tools()).hasSize(2);
        List<ModelChatMessage> second = requests.get(1).messages();
        assertThat(second).hasSize(4);
        assertThat(second.get(2).functionCall().name()).isEqualTo("table_availability");
        assertThat(second.get(3).role()).isEqualTo("function");
        assertThat(second.get(3).name()).isEqualTo("table_availability");
        assertThat(second.get(3).content()).contains("\"ok\":true").contains("13:30");
        ArgumentCaptor<CapabilityContext> ctx = ArgumentCaptor.forClass(CapabilityContext.class);
        verify(registry).invoke(eq("table_availability"), ctx.capture(), anyMap());
        assertThat(ctx.getValue().idempotencyKey()).isEqualTo("c-1:1:1");
        assertThat(ctx.getValue().sessionId()).isEqualTo("c-1");
    }

    @Test
    void stopsAfterFiveToolCalls() {
        when(model.chat(any())).thenAnswer(invocation -> {
            ModelChatRequest request = invocation.getArgument(0);
            requests.add(request);
            return request.tools().isEmpty() ? text("Свободно в 14:00.") : call("table_availability", Map.of());
        });
        when(registry.invoke(any(), any(), anyMap())).thenReturn(CapabilityResult.ok(Map.of("available", false)));

        AgentOutcome outcome = loop.run(history(), TOOLS, CTX);

        assertThat(outcome.toolCalls()).isEqualTo(5);
        assertThat(outcome.text()).isEqualTo("Свободно в 14:00.");
        assertThat(outcome.fallback()).isFalse();
        verify(registry, times(5)).invoke(any(), any(), anyMap());
        assertThat(requests).hasSize(6);
        assertThat(requests.get(5).tools()).isEmpty();
        assertThat(requests.subList(0, 5)).allSatisfy(request -> assertThat(request.tools()).hasSize(2));
    }

    @Test
    void unknownToolNameGetsAnErrorFactAndTheLoopGoesOn() {
        when(model.chat(any())).thenAnswer(invocation -> {
            requests.add(invocation.getArgument(0));
            return requests.size() == 1 ? call("book_spaceship", Map.of()) : text("Такого я не умею, но стол найду.");
        });

        AgentOutcome outcome = loop.run(history(), TOOLS, CTX);

        assertThat(outcome.fallback()).isFalse();
        assertThat(outcome.text()).isEqualTo("Такого я не умею, но стол найду.");
        assertThat(outcome.toolCalls()).isEqualTo(1);
        assertThat(outcome.results()).singleElement().satisfies(result -> {
            assertThat(result.ok()).isFalse();
            assertThat(result.error()).isEqualTo(CapabilityResult.NOT_FOUND);
        });
        assertThat(requests.get(1).messages().get(3).content()).contains("NOT_FOUND");
        verify(registry, never()).invoke(any(), any(), anyMap());
    }

    @Test
    void modelUnavailableIsAFallback() {
        when(model.chat(any())).thenThrow(new ModelUnavailableException("402", null));

        AgentOutcome outcome = loop.run(history(), TOOLS, CTX);

        assertThat(outcome.fallback()).isTrue();
        assertThat(outcome.text()).isNull();
    }

    @Test
    void anyModelErrorIsAFallback() {
        when(model.chat(any())).thenThrow(new IllegalStateException("no auth key"));

        AgentOutcome outcome = loop.run(history(), TOOLS, CTX);

        assertThat(outcome.fallback()).isTrue();
        assertThat(outcome.text()).isNull();
    }

    @Test
    void aSlowModelIsAFallback() {
        properties.setModelTimeoutMs(100);
        when(model.chat(any())).thenAnswer(invocation -> {
            Thread.sleep(2000);
            return text("поздно");
        });

        long started = System.nanoTime();
        AgentOutcome outcome = loop.run(history(), TOOLS, CTX);

        assertThat(outcome.fallback()).isTrue();
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofMillis(1500));
    }

    @Test
    void noModelIsAFallback() {
        AgentOutcome outcome = new AgentLoop(null, registry, properties).run(history(), TOOLS, CTX);

        assertThat(outcome.fallback()).isTrue();
    }

    @Test
    void blankAnswerIsAFallback() {
        when(model.chat(any())).thenReturn(text("  "));

        assertThat(loop.run(history(), TOOLS, CTX).fallback()).isTrue();
    }

    @Test
    void phonesInToolFactsAreMaskedBeforeTheModelSeesThem() {
        when(model.chat(any())).thenAnswer(invocation -> {
            requests.add(invocation.getArgument(0));
            return requests.size() == 1 ? call("table_availability", Map.of()) : text("Готово.");
        });
        when(registry.invoke(eq("table_availability"), any(), anyMap())).thenReturn(CapabilityResult.ok(Map.of(
                "guestPhone", "+79120000060",
                "note", "перезвонить 8 (912) 000-00-60",
                "nested", Map.of("phone", "89120000060"))));

        loop.run(history(), TOOLS, CTX);

        String content = requests.get(1).messages().get(3).content();
        assertThat(content).contains("+7 912 ***-**-60").doesNotContain("000-00-60").doesNotContain("9120000060");
    }
}
