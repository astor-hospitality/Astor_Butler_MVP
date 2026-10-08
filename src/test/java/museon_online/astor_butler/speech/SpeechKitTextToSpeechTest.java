package museon_online.astor_butler.speech;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The rollback provider sends exactly the request the glasses pilot always sent. */
class SpeechKitTextToSpeechTest {

    private static final byte[] MP3 = {(byte) 0xFF, (byte) 0xFB, 1, 2};
    private HttpServer server;
    private final List<String> bodies = new CopyOnWriteArrayList<>();
    private final List<String> authorizations = new CopyOnWriteArrayList<>();
    private final List<String> contentTypes = new CopyOnWriteArrayList<>();
    private final AtomicInteger status = new AtomicInteger(200);

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/speech/v1/tts:synthesize", exchange -> {
            bodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            authorizations.add(exchange.getRequestHeaders().getFirst("Authorization"));
            contentTypes.add(exchange.getRequestHeaders().getFirst("Content-Type"));
            byte[] body = status.get() == 200 ? MP3 : "{\"error\":\"nope\"}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status.get(), body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private SpeechKitTextToSpeech adapter(String apiKey, String folder, String voice) {
        return new SpeechKitTextToSpeech(HttpClient.newHttpClient(), new SpeechKitTextToSpeech.Settings(
                "http://127.0.0.1:" + server.getAddress().getPort() + "/speech/v1/tts:synthesize", apiKey, folder, voice, null, 0.95));
    }

    @Test
    void postsTheFormWithTheApiKeyAndGetsMp3Back() {
        SpeechKitTextToSpeech tts = adapter("unit-key", "unit-folder", "filipp");

        assertThat(tts.synthesize("Стол пять ждёт счёт.")).isEqualTo(MP3);

        assertThat(authorizations).containsExactly("Api-Key unit-key");
        assertThat(contentTypes).containsExactly("application/x-www-form-urlencoded");
        assertThat(bodies.getFirst()).contains("voice=filipp").contains("lang=ru-RU").contains("format=mp3")
                .contains("role=neutral").contains("speed=0.95").contains("folderId=unit-folder")
                .startsWith("text=%D0%A1%D1%82%D0%BE%D0%BB");
        assertThat(tts.provider()).isEqualTo("yandex");
        assertThat(tts.mimeType()).isEqualTo("audio/mpeg");
        assertThat(tts.voiceGender()).isEqualTo("male");
        assertThat(adapter("k", "f", "alena").voiceGender()).isEqualTo("female");
    }

    @Test
    void isNotConfiguredWithoutKeyOrFolderAndReportsProviderErrorsByStatus() {
        assertThat(adapter("", "unit-folder", "filipp").configured()).isFalse();
        assertThat(adapter("unit-key", "", "filipp").configured()).isFalse();
        assertThatThrownBy(() -> adapter("", "", "filipp").synthesize("Строка"))
                .isInstanceOf(TextToSpeechException.class).hasMessageContaining("not configured");
        assertThat(bodies).isEmpty();

        status.set(401);
        assertThatThrownBy(() -> adapter("unit-key", "unit-folder", "filipp").synthesize("Строка"))
                .isInstanceOf(TextToSpeechException.class)
                .satisfies(e -> assertThat(((TextToSpeechException) e).status()).isEqualTo(401));
    }
}
