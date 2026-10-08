package museon_online.astor_butler.model;

import museon_online.astor_butler.llm.OllamaClient;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.restclient.RestTemplateBuilder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class ModelGatewayProviderTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withBean(OllamaClient.class, () -> {
                OllamaClient client = mock(OllamaClient.class);
                return client;
            })
            .withBean(RestTemplateBuilder.class, RestTemplateBuilder::new)
            .withUserConfiguration(
                    SpringAiOllamaModelGateway.class,
                    OllamaModelGateway.class,
                    YandexModelGateway.class,
                    YandexAiStudioAgentModelGateway.class,
                    OpenAiCompatibleModelGateway.class,
                    CloudRuModelGateway.class,
                    GigaChatModelGateway.class
            )
            .withPropertyValues(
                    "llm.ollama.base-url=http://localhost:11434",
                    "llm.ollama.model=qwen2.5:1.5b",
                    "llm.ollama.frontline-model=qwen2.5:1.5b",
                    "llm.ollama.quality-model=qwen2.5:3b",
                    "llm.ollama.keep-alive=30m",
                    "yandex.ai.folder-id=test-folder",
                    "yandex.ai.api-key=test-key"
            );

    @Test
    void springAiProviderIsDefaultModelGateway() {
        contextRunner.run(context -> {
            assertThat(context).hasSingleBean(ModelGateway.class);
            assertThat(context.getBean(ModelGateway.class)).isInstanceOf(SpringAiOllamaModelGateway.class);
        });
    }

    @Test
    void rawOllamaProviderCanBeSelectedExplicitly() {
        contextRunner
                .withPropertyValues("astor.model.provider=ollama-raw")
                .run(context -> {
                    assertThat(context).hasSingleBean(ModelGateway.class);
                    assertThat(context.getBean(ModelGateway.class)).isInstanceOf(OllamaModelGateway.class);
                });
    }

    @Test
    void yandexProviderCanBeSelectedExplicitly() {
        contextRunner
                .withPropertyValues("astor.model.provider=yandex")
                .run(context -> {
                    assertThat(context).hasSingleBean(ModelGateway.class);
                    assertThat(context.getBean(ModelGateway.class)).isInstanceOf(YandexModelGateway.class);
                });
    }

    @Test
    void openAiCompatibleProviderCanBeSelectedExplicitly() {
        contextRunner
                .withPropertyValues(
                        "astor.model.provider=openai-compatible",
                        "astor.model.openai-compatible.base-url=https://models.test/v1",
                        "astor.model.openai-compatible.api-key=test-key",
                        "astor.model.openai-compatible.model=test-model"
                )
                .run(context -> {
                    assertThat(context).hasSingleBean(ModelGateway.class);
                    assertThat(context.getBean(ModelGateway.class)).isInstanceOf(OpenAiCompatibleModelGateway.class);
                });
    }

    @Test
    void cloudRuProviderCanBeSelectedExplicitly() {
        contextRunner
                .withPropertyValues(
                        "astor.model.provider=cloudru",
                        "astor.model.cloudru.api-key=test-key",
                        "astor.model.cloudru.model=GigaChat/GigaChat-2-Max"
                )
                .run(context -> {
                    assertThat(context).hasSingleBean(ModelGateway.class);
                    assertThat(context.getBean(ModelGateway.class)).isInstanceOf(CloudRuModelGateway.class);
                });
    }

    @Test
    void gigaChatProviderCanBeSelectedExplicitly() {
        contextRunner
                .withPropertyValues(
                        "astor.model.provider=gigachat",
                        "astor.model.gigachat.auth-key=test-key"
                )
                .run(context -> {
                    assertThat(context).hasSingleBean(ModelGateway.class);
                    assertThat(context.getBean(ModelGateway.class)).isInstanceOf(GigaChatModelGateway.class);
                });
    }

    @Test
    void yandexAgentProviderCanBeSelectedExplicitly() {
        contextRunner
                .withPropertyValues("astor.model.provider=yandex-agent")
                .run(context -> {
                    assertThat(context).hasSingleBean(ModelGateway.class);
                    assertThat(context.getBean(ModelGateway.class)).isInstanceOf(YandexAiStudioAgentModelGateway.class);
                });
    }
}
