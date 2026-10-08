package museon_online.astor_butler.domain.semantic;

import museon_online.astor_butler.model.ModelGateway;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class SemanticEmbeddingsProviderSelectionTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withBean(ModelGateway.class, () -> mock(ModelGateway.class))
            .withUserConfiguration(SemanticEmbeddingsProviderCheck.class, YandexTextEmbeddingProvider.class,
                    ModelGatewayEmbeddingProvider.class, OllamaEmbeddingProvider.class);

    @Test
    void noneIsTheDefaultAndLeavesNoProvider() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).doesNotHaveBean(EmbeddingProvider.class);
            assertThat(context.getBean(SemanticEmbeddingsProviderCheck.class).provider()).isEqualTo("none");
        });
    }

    @Test
    void yandexIsIndependentOfTheChatModelProvider() {
        runner.withPropertyValues(
                "astor.semantic-memory.embeddings.provider=yandex",
                "astor.model.provider=gigachat",
                "astor.semantic-memory.pgvector.embedding-dimension=256"
        ).run(context -> {
            assertThat(context).hasSingleBean(EmbeddingProvider.class);
            assertThat(context.getBean(EmbeddingProvider.class)).isInstanceOf(YandexTextEmbeddingProvider.class);
            assertThat(context.getBean(EmbeddingProvider.class).model()).isEqualTo("text-search-doc/latest");
        });
    }

    @Test
    void modelGatewayStaysSelectable() {
        runner.withPropertyValues("astor.semantic-memory.embeddings.provider=model-gateway").run(context ->
                assertThat(context.getBean(EmbeddingProvider.class)).isInstanceOf(ModelGatewayEmbeddingProvider.class));
    }

    @Test
    void emptyCompositionVariablesFallBackToTheSpeechKitKeyAndYandexFolder() throws Exception {
        try (YandexEmbeddingStubServer stub = YandexEmbeddingStubServer.start()) {
            runner.withPropertyValues(
                    "astor.semantic-memory.embeddings.provider=yandex",
                    "astor.semantic-memory.pgvector.embedding-dimension=256",
                    "astor.semantic-memory.embeddings.yandex.base-url=" + stub.baseUrl(),
                    // compose passes unset variables as empty strings
                    "astor.semantic-memory.embeddings.yandex.api-key=",
                    "astor.semantic-memory.embeddings.yandex.folder-id=",
                    "astor.semantic-memory.embeddings.yandex.speechkit-api-key=sk-key",
                    "yandex.ai.api-key=",
                    "yandex.ai.folder-id=b1gfolder"
            ).run(context -> {
                assertThat(context.getBean(EmbeddingProvider.class).embedQuery("винная карта")).hasSize(256);
                YandexEmbeddingStubServer.Seen seen = stub.requests().getFirst();
                assertThat(seen.authorization()).isEqualTo("Api-Key sk-key");
                assertThat(seen.body()).contains("emb://b1gfolder/text-search-query/latest");
            });
        }
    }

    @Test
    void unknownProviderFailsStartupNamingTheVariable() {
        runner.withPropertyValues("astor.semantic-memory.embeddings.provider=gigachat").run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).rootCause()
                    .hasMessageContaining("ASTOR_SEMANTIC_EMBEDDINGS_PROVIDER")
                    .hasMessageContaining("gigachat");
        });
    }
}
