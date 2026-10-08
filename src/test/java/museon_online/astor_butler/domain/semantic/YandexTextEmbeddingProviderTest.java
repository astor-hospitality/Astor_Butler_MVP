package museon_online.astor_butler.domain.semantic;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class YandexTextEmbeddingProviderTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private YandexEmbeddingStubServer stub;

    @BeforeEach
    void startStub() throws Exception {
        stub = YandexEmbeddingStubServer.start();
    }

    @AfterEach
    void stopStub() {
        stub.close();
    }

    private YandexTextEmbeddingProvider provider(int dimension, int maxAttempts, Duration throttle) {
        return new YandexTextEmbeddingProvider(stub.baseUrl() + "/", "test-api-key", "b1gfolder",
                "text-search-doc/latest", "text-search-query/latest", dimension, Duration.ofSeconds(5), throttle,
                maxAttempts, Duration.ofMillis(1));
    }

    @Test
    void documentEmbeddingPostsDocModelUriWithApiKey() throws Exception {
        List<Double> vector = provider(256, 1, Duration.ZERO).embed("Винная карта AERIS");

        assertThat(vector).hasSize(256);
        assertThat(vector.getFirst()).isEqualTo(0.001);
        YandexEmbeddingStubServer.Seen seen = stub.requests().getFirst();
        assertThat(seen.method()).isEqualTo("POST");
        assertThat(seen.path()).isEqualTo("/foundationModels/v1/textEmbedding");
        assertThat(seen.authorization()).isEqualTo("Api-Key test-api-key");
        assertThat(seen.contentType()).startsWith("application/json");
        JsonNode body = objectMapper.readTree(seen.body());
        assertThat(body.size()).isEqualTo(2);
        assertThat(body.path("modelUri").asText()).isEqualTo("emb://b1gfolder/text-search-doc/latest");
        assertThat(body.path("text").asText()).isEqualTo("Винная карта AERIS");
    }

    @Test
    void queryEmbeddingUsesQueryModelAndModelLabelStaysTheDocumentModel() throws Exception {
        YandexTextEmbeddingProvider provider = provider(256, 1, Duration.ZERO);

        assertThat(provider.embedQuery("что выпить к стейку")).hasSize(256);

        JsonNode body = objectMapper.readTree(stub.requests().getFirst().body());
        assertThat(body.path("modelUri").asText()).isEqualTo("emb://b1gfolder/text-search-query/latest");
        assertThat(provider.model()).isEqualTo("text-search-doc/latest");
    }

    @Test
    void fullEmbUriIsSentAsIsWithoutFolder() throws Exception {
        YandexTextEmbeddingProvider provider = new YandexTextEmbeddingProvider(stub.baseUrl(), "k", "",
                "emb://other/text-search-doc/latest", "emb://other/text-search-query/latest", 256,
                Duration.ofSeconds(5), Duration.ZERO, 1, Duration.ofMillis(1));

        provider.embed("стол у окна");

        assertThat(objectMapper.readTree(stub.requests().getFirst().body()).path("modelUri").asText())
                .isEqualTo("emb://other/text-search-doc/latest");
    }

    @Test
    void dimensionMismatchFailsNamingTheVariableAndTheRightValue() {
        assertThatThrownBy(() -> provider(1536, 1, Duration.ZERO).embed("бронь"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ASTOR_SEMANTIC_EMBEDDING_DIMENSION=1536")
                .hasMessageContaining("set ASTOR_SEMANTIC_EMBEDDING_DIMENSION=256");
    }

    @Test
    void retriesRateLimitAndServerErrorsThenSucceeds() {
        stub.reply(429, "{\"error\":\"quota\"}").reply(503, "{}");

        assertThat(provider(256, 3, Duration.ZERO).embed("меню")).hasSize(256);
        assertThat(stub.requests()).hasSize(3);
    }

    @Test
    void givesUpAfterMaxAttempts() {
        stub.reply(500, "{}").reply(500, "{}").reply(500, "{}");

        assertThatThrownBy(() -> provider(256, 2, Duration.ZERO).embed("меню"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("status=500");
        assertThat(stub.requests()).hasSize(2);
    }

    @Test
    void clientErrorsAreNotRetriedAndTheBodyIsNotEchoed() {
        stub.reply(400, "{\"error\":\"secret-echo\"}");

        assertThatThrownBy(() -> provider(256, 5, Duration.ZERO).embed("меню"))
                .hasMessageContaining("status=400")
                .hasMessageNotContaining("secret-echo");
        assertThat(stub.requests()).hasSize(1);
    }

    @Test
    void emptyVectorIsAnError() {
        stub.reply(200, "{\"embedding\":[]}");

        assertThatThrownBy(() -> provider(256, 1, Duration.ZERO).embed("меню"))
                .hasMessageContaining("no vector");
    }

    @Test
    void blankTextIsNotSent() {
        assertThat(provider(256, 1, Duration.ZERO).embed("  ")).isEmpty();
        assertThat(stub.requests()).isEmpty();
    }

    @Test
    void missingKeyOrFolderFailsBeforeAnyCall() {
        YandexTextEmbeddingProvider noKey = new YandexTextEmbeddingProvider(stub.baseUrl(), " ", "f", null, null,
                256, Duration.ofSeconds(5), Duration.ZERO, 1, Duration.ofMillis(1));
        YandexTextEmbeddingProvider noFolder = new YandexTextEmbeddingProvider(stub.baseUrl(), "k", null, null, null,
                256, Duration.ofSeconds(5), Duration.ZERO, 1, Duration.ofMillis(1));

        assertThatThrownBy(() -> noKey.embed("x")).hasMessageContaining("ASTOR_EMBEDDINGS_YANDEX_API_KEY");
        assertThatThrownBy(() -> noFolder.embed("x")).hasMessageContaining("YANDEX_FOLDER_ID");
        assertThat(stub.requests()).isEmpty();
    }

    @Test
    void throttleKeepsTheMinimumGapBetweenRequests() {
        YandexTextEmbeddingProvider provider = provider(256, 1, Duration.ofMillis(150));

        provider.embed("первый");
        provider.embed("второй");

        List<YandexEmbeddingStubServer.Seen> seen = stub.requests();
        assertThat(Duration.ofNanos(seen.get(1).atNanos() - seen.get(0).atNanos()))
                .isGreaterThanOrEqualTo(Duration.ofMillis(140));
    }

    @Test
    void springConstructorFallsBackFromDedicatedKeyToSpeechKitThenYandexKey() {
        new YandexTextEmbeddingProvider(stub.baseUrl(), "", "", "speechkit-key", "yandex-key", "", "b1gfolder", "",
                "text-search-doc/latest", "text-search-query/latest", 256, 5000, 0, 1, 250).embed("a");
        new YandexTextEmbeddingProvider(stub.baseUrl(), "", "", "", "yandex-key", "", "", "speechkit-folder",
                "text-search-doc/latest", "text-search-query/latest", 256, 5000, 0, 1, 250).embed("b");
        new YandexTextEmbeddingProvider("", stub.baseUrl(), "dedicated-key", "speechkit-key", "yandex-key", "own-folder",
                "b1gfolder", "", "", "", 256, 5000, 0, 1, 250).embed("c");

        List<YandexEmbeddingStubServer.Seen> seen = stub.requests();
        assertThat(seen).extracting(YandexEmbeddingStubServer.Seen::authorization)
                .containsExactly("Api-Key speechkit-key", "Api-Key yandex-key", "Api-Key dedicated-key");
        assertThat(seen.get(1).body()).contains("emb://speechkit-folder/text-search-doc/latest");
        assertThat(seen.get(2).body()).contains("emb://own-folder/text-search-doc/latest");
    }
}
