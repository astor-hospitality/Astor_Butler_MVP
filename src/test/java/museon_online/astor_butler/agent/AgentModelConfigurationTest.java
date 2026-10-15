package museon_online.astor_butler.agent;

import museon_online.astor_butler.capability.CapabilityRegistry;
import museon_online.astor_butler.domain.calllog.CallLogModelGatewayConfiguration;
import museon_online.astor_butler.domain.calllog.CallLogToolCallingModelGateway;
import museon_online.astor_butler.model.GigaChatModelGateway;
import museon_online.astor_butler.model.ModelGateway;
import museon_online.astor_butler.model.OpenAiCompatibleModelGateway;
import museon_online.astor_butler.model.ToolCallingModel;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.restclient.RestTemplateBuilder;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/** ASTOR_AGENT_PROVIDER moves only the agent; plain injection keeps the application's model (the rollback path). */
class AgentModelConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withBean(RestTemplateBuilder.class, RestTemplateBuilder::new)
            .withBean(CapabilityRegistry.class, () -> mock(CapabilityRegistry.class))
            .withUserConfiguration(Properties.class, GigaChatModelGateway.class, OpenAiCompatibleModelGateway.class,
                    AgentModelConfiguration.class, AgentLoop.class, Consumer.class)
            .withPropertyValues(
                    "astor.model.gigachat.auth-key=test-key",
                    "astor.model.openai-compatible.base-url=https://models.test/v1",
                    "astor.model.openai-compatible.api-key=test-key");

    @Test
    void withoutAnAgentProviderTheAgentKeepsGigaChat() {
        contextRunner
                .withPropertyValues("astor.model.provider=gigachat")
                .run(context -> {
                    assertThat(context).hasNotFailed().doesNotHaveBean(AgentModelConfiguration.AGENT_MODEL);
                    assertThat(context.getBean(AgentLoop.class).currentModel()).isInstanceOf(GigaChatModelGateway.class);
                    assertThat(context.getBean(Consumer.class).gateway).isInstanceOf(GigaChatModelGateway.class);
                });
    }

    @Test
    void theAgentProviderMovesOnlyTheAgent() {
        contextRunner
                .withPropertyValues("astor.model.provider=gigachat", "astor.agent.provider=openai-compatible",
                        "astor.agent.model=gigachat-3-pro")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(AgentLoop.class).currentModel()).isInstanceOf(OpenAiCompatibleModelGateway.class);
                    Consumer consumer = context.getBean(Consumer.class);
                    // Understanding, replies and visit summaries keep GigaChat.
                    assertThat(consumer.gateway).isInstanceOf(GigaChatModelGateway.class);
                    assertThat(consumer.chat).isInstanceOf(GigaChatModelGateway.class);
                });
    }

    @Test
    void theCallLogWrapsTheAgentsOwnModelToo() {
        contextRunner
                .withUserConfiguration(CallLogModelGatewayConfiguration.class)
                .withPropertyValues("astor.model.provider=gigachat", "astor.agent.provider=openai-compatible",
                        "astor.call-log.enabled=true")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    ToolCallingModel agent = context.getBean(AgentLoop.class).currentModel();
                    assertThat(agent).isInstanceOf(CallLogToolCallingModelGateway.class);
                    assertThat(((CallLogToolCallingModelGateway) agent).delegate()).isInstanceOf(OpenAiCompatibleModelGateway.class);
                    assertThat(((CallLogToolCallingModelGateway) context.getBean(Consumer.class).gateway).delegate())
                            .isInstanceOf(GigaChatModelGateway.class);
                });
    }

    @Test
    void theWholeApplicationOnOpenAiCompatibleStillHasAnAgentModel() {
        contextRunner
                .withPropertyValues("astor.model.provider=openai-compatible", "astor.model.openai-compatible.model=gigachat-3-pro")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(AgentLoop.class).currentModel()).isInstanceOf(OpenAiCompatibleModelGateway.class);
                    assertThat(context.getBean(Consumer.class).chat).isSameAs(context.getBean(AgentLoop.class).currentModel());
                });
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(AgentProperties.class)
    static class Properties {
    }

    /** Injects by plain type, as LlmUnderstandingService and VisitSummaryService do. */
    static class Consumer {
        @Autowired
        ModelGateway gateway;
        @Autowired(required = false)
        ToolCallingModel chat;
    }
}
