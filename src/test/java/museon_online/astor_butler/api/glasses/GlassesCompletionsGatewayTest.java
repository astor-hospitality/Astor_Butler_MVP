package museon_online.astor_butler.api.glasses;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpServer;
import museon_online.astor_butler.model.ModelTextRequest;
import museon_online.astor_butler.model.ModelVisionRequest;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.*;

class GlassesCompletionsGatewayTest {
    private static final String OK = "{\"choices\":[{\"finish_reason\":\"stop\",\"message\":{\"content\":\"Красный квадрат.\"}}]}";

    private HttpServer serve(int status, String json, AtomicReference<String> body, AtomicReference<Headers> headers) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            body.set(new String(exchange.getRequestBody().readAllBytes()));
            headers.set(exchange.getRequestHeaders());
            byte[] response = json.getBytes();
            exchange.sendResponseHeaders(status, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        return server;
    }

    private static String url(HttpServer server) { return "http://127.0.0.1:" + server.getAddress().getPort(); }

    @Test void yandexSendsTheImageUnderItsFolderWithLoggingOffAndReturnsOnlyCompleteText() throws Exception {
        var seen = new AtomicReference<String>();
        var headers = new AtomicReference<Headers>();
        var server = serve(200, OK, seen, headers);
        try {
            var gateway = GlassesCompletionsGateway.yandex(url(server), "unit-fixture", "folder", "text/latest", "vision/latest");
            var response = gateway.analyzeImage(ModelVisionRequest.of("describe", "aGVsbG8=", "image/jpeg", null, "test", "test", "test"));
            assertThat(response.text()).isEqualTo("Красный квадрат.");
            assertThat(response.fallback()).isFalse();
            assertThat(response.provider()).isEqualTo("yandex-glasses");
            var body = new ObjectMapper().readTree(seen.get());
            assertThat(body.path("model").asText()).isEqualTo("gpt://folder/vision/latest");
            assertThat(body.path("messages").get(0).path("content").get(1).path("image_url").path("url").asText()).isEqualTo("data:image/jpeg;base64,aGVsbG8=");
            assertThat(body.has("tools")).isFalse();
            assertThat(body.path("stream").asBoolean()).isFalse();
            assertThat(headers.get().getFirst("Authorization")).isEqualTo("Api-Key unit-fixture");
            assertThat(headers.get().getFirst("OpenAI-Project")).isEqualTo("folder");
            assertThat(headers.get().getFirst("x-data-logging-enabled")).isEqualTo("false");
        } finally { server.stop(0); }
    }

    @Test void cloudRuSpeaksPlainOpenAiWithABearerAndTheModelIdAsListed() throws Exception {
        var seen = new AtomicReference<String>();
        var headers = new AtomicReference<Headers>();
        var server = serve(200, OK, seen, headers);
        try {
            var gateway = GlassesCompletionsGateway.openAiCompatible(url(server), "unit-fixture", "ai-sage/GigaChat3-10B-A1.8B", "Qwen/Qwen3.6-35B-A3B");
            assertThat(gateway.provider()).isEqualTo(GlassesCompletionsGateway.Provider.OPENAI_COMPATIBLE);
            var text = gateway.generateText(ModelTextRequest.of("Стол пять?", "test", "test", "test"));
            assertThat(text.text()).isEqualTo("Красный квадрат.");
            assertThat(text.provider()).isEqualTo("openai-compatible-glasses");
            var body = new ObjectMapper().readTree(seen.get());
            assertThat(body.path("model").asText()).isEqualTo("ai-sage/GigaChat3-10B-A1.8B");
            assertThat(body.has("chat_template_kwargs")).isFalse();
            assertThat(headers.get().getFirst("Authorization")).isEqualTo("Bearer unit-fixture");
            assertThat(headers.get().containsKey("OpenAI-Project")).isFalse();
            assertThat(headers.get().containsKey("x-data-logging-enabled")).isFalse();

            gateway.analyzeImage(ModelVisionRequest.of("describe", "aGVsbG8=", "image/jpeg", null, "test", "test", "test"));
            var vision = new ObjectMapper().readTree(seen.get());
            assertThat(vision.path("model").asText()).isEqualTo("Qwen/Qwen3.6-35B-A3B");
            assertThat(vision.path("chat_template_kwargs").path("enable_thinking").asBoolean(true)).isFalse();
        } finally { server.stop(0); }
    }

    @Test void theProviderIsAnExplicitSettingAndAnUnknownOneIsAConfigurationError() {
        assertThat(GlassesCompletionsGateway.Provider.parse("yandex")).isEqualTo(GlassesCompletionsGateway.Provider.YANDEX);
        assertThat(GlassesCompletionsGateway.Provider.parse(" CloudRu ")).isEqualTo(GlassesCompletionsGateway.Provider.OPENAI_COMPATIBLE);
        assertThat(GlassesCompletionsGateway.Provider.parse("openai-compatible")).isEqualTo(GlassesCompletionsGateway.Provider.OPENAI_COMPATIBLE);
        assertThatThrownBy(() -> GlassesCompletionsGateway.Provider.parse("sber")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> GlassesCompletionsGateway.Provider.parse("")).isInstanceOf(IllegalStateException.class);
    }

    @Test void aMissingKeyOrFolderNeverReachesTheNetwork() throws Exception {
        var seen = new AtomicReference<String>();
        var server = serve(200, OK, seen, new AtomicReference<>());
        try {
            var noKey = GlassesCompletionsGateway.openAiCompatible(url(server), " ", "m", "v");
            assertThatThrownBy(() -> noKey.generateText(ModelTextRequest.of("t", "t", "t", "t"))).hasMessage("Provider unavailable");
            var noFolder = GlassesCompletionsGateway.yandex(url(server), "unit-fixture", "", "m", "v");
            assertThatThrownBy(() -> noFolder.generateText(ModelTextRequest.of("t", "t", "t", "t"))).hasMessage("Provider unavailable");
            assertThat(seen.get()).isNull();
        } finally { server.stop(0); }
    }

    @Test void upstreamErrorsAndTruncatedOutputCannotBecomeSuccess() throws Exception {
        for (int code : new int[]{401, 429, 503, 200}) {
            var server = serve(code, "{\"choices\":[{\"finish_reason\":\"length\",\"message\":{\"content\":\"secret-diagnostic\"}}]}",
                    new AtomicReference<>(), new AtomicReference<>());
            try {
                for (var gateway : new GlassesCompletionsGateway[]{
                        GlassesCompletionsGateway.yandex(url(server), "unit-fixture", "folder", "text/latest", "vision/latest"),
                        GlassesCompletionsGateway.openAiCompatible(url(server), "unit-fixture", "text/latest", "vision/latest")}) {
                    assertThatThrownBy(() -> gateway.generateText(ModelTextRequest.of("test", "test", "test", "test")))
                            .isInstanceOf(IllegalStateException.class).hasMessage("Provider unavailable");
                }
            } finally { server.stop(0); }
        }
    }
}
