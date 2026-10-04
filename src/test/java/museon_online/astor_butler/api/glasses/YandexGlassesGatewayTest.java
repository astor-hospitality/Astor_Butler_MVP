package museon_online.astor_butler.api.glasses;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import museon_online.astor_butler.model.ModelTextRequest;
import museon_online.astor_butler.model.ModelVisionRequest;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.*;

class YandexGlassesGatewayTest {
    @Test void sendsActualImageWithLoggingOffAndReturnsOnlyCompleteText() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var seen = new AtomicReference<String>();
        var logging = new AtomicReference<String>();
        server.createContext("/", exchange -> {
            seen.set(new String(exchange.getRequestBody().readAllBytes()));
            logging.set(exchange.getRequestHeaders().getFirst("x-data-logging-enabled"));
            byte[] response = "{\"choices\":[{\"finish_reason\":\"stop\",\"message\":{\"content\":\"Красный квадрат.\"}}]}".getBytes();
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        try {
            var gateway = new YandexGlassesGateway("http://127.0.0.1:" + server.getAddress().getPort(), "unit-fixture", "folder", "text/latest", "vision/latest");
            var response = gateway.analyzeImage(ModelVisionRequest.of("describe", "aGVsbG8=", "image/jpeg", null, "test", "test", "test"));
            assertThat(response.text()).isEqualTo("Красный квадрат.");
            assertThat(response.fallback()).isFalse();
            var body = new ObjectMapper().readTree(seen.get());
            assertThat(body.path("model").asText()).isEqualTo("gpt://folder/vision/latest");
            assertThat(body.path("messages").get(0).path("content").get(1).path("image_url").path("url").asText()).isEqualTo("data:image/jpeg;base64,aGVsbG8=");
            assertThat(body.has("tools")).isFalse();
            assertThat(body.path("stream").asBoolean()).isFalse();
            assertThat(logging.get()).isEqualTo("false");
        } finally { server.stop(0); }
    }

    @Test void upstreamErrorsAndTruncatedOutputCannotBecomeSuccess() throws Exception {
        for (int code : new int[]{401, 429, 503, 200}) {
            var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", exchange -> {
                byte[] bytes = "{\"choices\":[{\"finish_reason\":\"length\",\"message\":{\"content\":\"secret-diagnostic\"}}]}".getBytes();
                exchange.sendResponseHeaders(code, bytes.length);exchange.getResponseBody().write(bytes);exchange.close();
            });
            server.start();
            try {
                var gateway = new YandexGlassesGateway("http://127.0.0.1:" + server.getAddress().getPort(), "unit-fixture", "folder", "text/latest", "vision/latest");
                assertThatThrownBy(() -> gateway.generateText(ModelTextRequest.of("test", "test", "test", "test")))
                        .isInstanceOf(IllegalStateException.class).hasMessage("Provider unavailable");
            } finally { server.stop(0); }
        }
    }
}
