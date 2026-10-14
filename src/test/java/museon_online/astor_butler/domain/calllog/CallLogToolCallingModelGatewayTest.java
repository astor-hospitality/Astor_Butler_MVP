package museon_online.astor_butler.domain.calllog;

import museon_online.astor_butler.agent.AgentLoop;
import museon_online.astor_butler.agent.AgentOutcome;
import museon_online.astor_butler.agent.AgentProperties;
import museon_online.astor_butler.capability.CapabilityContext;
import museon_online.astor_butler.capability.CapabilityRegistry;
import museon_online.astor_butler.capability.CapabilityResult;
import museon_online.astor_butler.model.ModelChatMessage;
import museon_online.astor_butler.model.ModelChatRequest;
import museon_online.astor_butler.model.ModelChatResponse;
import museon_online.astor_butler.model.ModelEmbeddingRequest;
import museon_online.astor_butler.model.ModelEmbeddingResponse;
import museon_online.astor_butler.model.ModelGateway;
import museon_online.astor_butler.model.ModelTextRequest;
import museon_online.astor_butler.model.ModelTextResponse;
import museon_online.astor_butler.model.ModelTool;
import museon_online.astor_butler.model.ModelToolCall;
import museon_online.astor_butler.model.ModelUnavailableException;
import museon_online.astor_butler.model.ModelVisionRequest;
import museon_online.astor_butler.model.ModelVisionResponse;
import museon_online.astor_butler.model.ToolCallingModel;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** A gateway that also calls tools (GigaChat) stays a {@link ToolCallingModel} when wrapped, and its chats are logged. */
class CallLogToolCallingModelGatewayTest {

    /** Like GigaChat: one bean, both interfaces; the agent's chats answer with a tool call first, then text. */
    static class ScriptedGateway implements ModelGateway, ToolCallingModel {
        final AtomicInteger chats = new AtomicInteger();

        @Override
        public ModelTextResponse generateText(ModelTextRequest request) {
            throw new UnsupportedOperationException();
        }

        @Override
        public ModelEmbeddingResponse generateEmbedding(ModelEmbeddingRequest request) {
            throw new UnsupportedOperationException();
        }

        @Override
        public ModelVisionResponse analyzeImage(ModelVisionRequest request) {
            throw new UnsupportedOperationException();
        }

        @Override
        public ModelChatResponse chat(ModelChatRequest request) {
            Map<String, Object> usage = Map.of("usage", Map.of("prompt_tokens", 1000, "completion_tokens", 100));
            if (chats.incrementAndGet() == 1) {
                return new ModelChatResponse("", Optional.of(new ModelToolCall("table_availability", Map.of())), "gigachat",
                        request.model(), Duration.ZERO, usage);
            }
            return new ModelChatResponse("Есть стол в 13:30.", Optional.empty(), "gigachat", request.model(), Duration.ZERO, usage);
        }
    }

    private final ScriptedGateway selected = new ScriptedGateway();
    private final CallLogRepository repository = mock(CallLogRepository.class);

    private CallLogWriter writer() {
        CallLogProperties properties = new CallLogProperties();
        properties.setEnabled(true);
        properties.setSalt("unit-test-salt-not-a-secret");
        return new CallLogWriter(properties, repository, new ModelTariff());
    }

    private ApplicationContextRunner runner() {
        return new ApplicationContextRunner()
                .withUserConfiguration(CallLogModelGatewayConfiguration.class)
                .withBean("gigaChatModelGateway", ScriptedGateway.class, () -> selected)
                .withBean(CallLogWriter.class, this::writer);
    }

    @Test
    void onBothInterfacesResolveToTheWrapperAndEveryAgentModelCallIsOneLlmRow() {
        runner().withPropertyValues("astor.call-log.enabled=true").run(context -> {
            ModelGateway gateway = context.getBean(ModelGateway.class);
            ToolCallingModel model = context.getBean(ToolCallingModel.class);
            assertThat(gateway).isInstanceOf(CallLogModelGateway.class);
            assertThat(model).isSameAs(gateway);
            assertThat(((CallLogModelGateway) gateway).delegate()).isSameAs(selected);

            CapabilityRegistry registry = mock(CapabilityRegistry.class);
            when(registry.invoke(eq("table_availability"), any(), anyMap())).thenReturn(CapabilityResult.ok(Map.of("free", List.of())));
            AgentProperties properties = new AgentProperties();
            properties.setModel("GigaChat-3-Pro");
            AgentLoop loop = new AgentLoop(model, registry, properties);
            AgentOutcome outcome;
            try (CallLogContext.Scope ignored = CallLogContext.open("concierge", "session-1", "t-1")) {
                outcome = loop.run(List.of(ModelChatMessage.user("Стол на двоих")),
                        List.of(new ModelTool("table_availability", "Свободные столы", null)),
                        new CapabilityContext(CapabilityContext.CONCIERGE, null, "session-1", null, "s:1", null, null));
            }

            assertThat(outcome.fallback()).isFalse();
            ArgumentCaptor<CallLogEntry> rows = ArgumentCaptor.forClass(CallLogEntry.class);
            verify(repository, times(2)).append(rows.capture());
            assertThat(rows.getAllValues()).allSatisfy(row -> {
                assertThat(row.callType()).isEqualTo(CallLogEntry.TYPE_LLM);
                assertThat(row.name()).isEqualTo("GigaChat-3-Pro");
                assertThat(row.result()).isEqualTo("ok");
                assertThat(row.tokensIn()).isEqualTo(1000);
                assertThat(row.tokensOut()).isEqualTo(100);
                assertThat(row.costRub()).isNotNull().isPositive();
                assertThat(row.source()).isEqualTo("concierge");
                assertThat(row.pseudoUserId()).isNotNull().doesNotContain("session-1");
                assertThat(row.correlationId()).isEqualTo("t-1");
            });
        });
    }

    @Test
    void aFailedChatIsAnErrorRowAndTheErrorPassesThrough() {
        ToolCallingModel failing = request -> {
            throw new ModelUnavailableException("down", new IllegalStateException());
        };
        CallLogRepository rows = repository;
        CallLogToolCallingModelGateway gateway = new CallLogToolCallingModelGateway(
                new FailingGateway(failing), this::writer);

        assertThatThrownBy(() -> gateway.chat(new ModelChatRequest(List.of(), List.of(), null, "butler_agent", Map.of(), "GigaChat-3-Pro")))
                .isInstanceOf(ModelUnavailableException.class);
        ArgumentCaptor<CallLogEntry> row = ArgumentCaptor.forClass(CallLogEntry.class);
        verify(rows).append(row.capture());
        assertThat(row.getValue().result()).isEqualTo("error");
        assertThat(row.getValue().name()).isEqualTo("GigaChat-3-Pro");
        assertThat(row.getValue().errorCode()).isEqualTo("IllegalStateException");
    }

    @Test
    void offTheGatewayIsUnwrapped() {
        runner().run(context -> {
            assertThat(context.getBean(ModelGateway.class)).isSameAs(selected);
            assertThat(context.getBean(ToolCallingModel.class)).isSameAs(selected);
        });
    }

    private static final class FailingGateway extends ScriptedGateway {
        private final ToolCallingModel chat;

        FailingGateway(ToolCallingModel chat) {
            this.chat = chat;
        }

        @Override
        public ModelChatResponse chat(ModelChatRequest request) {
            return chat.chat(request);
        }
    }
}
