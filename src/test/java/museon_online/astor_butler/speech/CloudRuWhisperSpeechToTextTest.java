package museon_online.astor_butler.speech;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CloudRuWhisperSpeechToTextTest {

    private static final String KEY = "cloudru-stt-key-fixture";
    private static final byte[] AUDIO = "OggS-voice-fixture-bytes".getBytes(StandardCharsets.US_ASCII);

    @TempDir Path temp;

    private static CloudRuWhisperSpeechToText client(WhisperStubServer stub, String language, Duration timeout) {
        return new CloudRuWhisperSpeechToText(stub.baseUrl(), KEY, "CLOUDRU_API_KEY", "openai/whisper-large-v3", language, timeout);
    }

    @Test
    void postsMultipartWithFileModelLanguageAndBearerAndReadsTheText() throws Exception {
        try (var stub = WhisperStubServer.start()) {
            stub.reply(200, "{\"text\": \"  Покажи меню, пожалуйста.  \"}");
            Path file = temp.resolve("voice-fixture.ogg");
            Files.write(file, AUDIO);

            String text = client(stub, "ru", Duration.ofSeconds(5)).transcribe(file);

            assertThat(text).isEqualTo("Покажи меню, пожалуйста.");
            var seen = stub.only();
            assertThat(seen.method()).isEqualTo("POST");
            assertThat(seen.path()).isEqualTo("/v1/audio/transcriptions");
            assertThat(seen.authorization()).isEqualTo("Bearer " + KEY);
            assertThat(seen.contentType()).startsWith("multipart/form-data; boundary=");
            String boundary = seen.contentType().substring("multipart/form-data; boundary=".length());
            String body = seen.bodyText();
            assertThat(body).contains("--" + boundary + "\r\n")
                    .contains("name=\"model\"\r\n\r\nopenai/whisper-large-v3\r\n")
                    .contains("name=\"language\"\r\n\r\nru\r\n")
                    .contains("name=\"response_format\"\r\n\r\njson\r\n")
                    .contains("name=\"file\"; filename=\"voice-fixture.ogg\"\r\nContent-Type: audio/ogg\r\n\r\nOggS-voice-fixture-bytes\r\n")
                    .endsWith("--" + boundary + "--\r\n");
        }
    }

    @Test
    void blankLanguageIsNotSentAndMimeTypeFollowsTheFileName() throws Exception {
        try (var stub = WhisperStubServer.start()) {
            stub.reply(200, "{\"text\":\"ok\"}");
            client(stub, "", Duration.ofSeconds(5)).transcribe(AUDIO, "clip.m4a", "audio/mp4");
            assertThat(stub.only().bodyText()).doesNotContain("name=\"language\"")
                    .contains("filename=\"clip.m4a\"\r\nContent-Type: audio/mp4\r\n");
        }
        assertThat(CloudRuWhisperSpeechToText.contentType("a.mp3")).isEqualTo("audio/mpeg");
        assertThat(CloudRuWhisperSpeechToText.contentType("a.oga")).isEqualTo("audio/ogg");
        assertThat(CloudRuWhisperSpeechToText.contentType("a.bin")).isEqualTo("application/octet-stream");
    }

    @Test
    void clientErrorIsNotRetriedAndNamesTheStatus() throws Exception {
        try (var stub = WhisperStubServer.start()) {
            stub.reply(400, "{\"error\":{\"message\":\"unsupported file format\"}}");
            assertThatThrownBy(() -> client(stub, "ru", Duration.ofSeconds(5)).transcribe(AUDIO, "a.ogg", "audio/ogg"))
                    .isInstanceOf(CloudRuWhisperException.class)
                    .hasMessageContaining("HTTP 400").hasMessageContaining("unsupported file format")
                    .satisfies(e -> {
                        assertThat(((CloudRuWhisperException) e).status()).isEqualTo(400);
                        assertThat(((CloudRuWhisperException) e).clientError()).isTrue();
                    });
            assertThat(stub.requests()).hasSize(1);
        }
    }

    @Test
    void serverErrorIsRetriedOnceThenSucceeds() throws Exception {
        try (var stub = WhisperStubServer.start()) {
            stub.reply(502, "bad gateway").reply(200, "{\"text\":\"второй раз\"}");
            assertThat(client(stub, "ru", Duration.ofSeconds(5)).transcribe(AUDIO, "a.ogg", "audio/ogg")).isEqualTo("второй раз");
            assertThat(stub.requests()).hasSize(2);
        }
    }

    @Test
    void twoServerErrorsFailWithTheLastStatus() throws Exception {
        try (var stub = WhisperStubServer.start()) {
            stub.reply(503, "{\"error\":\"busy\"}").reply(500, "{\"error\":\"down\"}");
            assertThatThrownBy(() -> client(stub, "ru", Duration.ofSeconds(5)).transcribe(AUDIO, "a.ogg", "audio/ogg"))
                    .isInstanceOf(CloudRuWhisperException.class).hasMessageContaining("HTTP 500 twice")
                    .satisfies(e -> assertThat(((CloudRuWhisperException) e).status()).isEqualTo(500));
            assertThat(stub.requests()).hasSize(2);
        }
    }

    @Test
    void timeoutIsRetriedOnceThenReported() throws Exception {
        try (var stub = WhisperStubServer.start()) {
            stub.replyAfter(1500, 200, "{\"text\":\"late\"}").replyAfter(1500, 200, "{\"text\":\"late\"}");
            long before = System.nanoTime();
            assertThatThrownBy(() -> client(stub, "ru", Duration.ofMillis(250)).transcribe(AUDIO, "a.ogg", "audio/ogg"))
                    .isInstanceOf(CloudRuWhisperException.class).hasMessageContaining("timed out")
                    .satisfies(e -> assertThat(((CloudRuWhisperException) e).status()).isZero());
            assertThat((System.nanoTime() - before) / 1_000_000).isLessThan(3000);
            assertThat(stub.requests()).hasSize(2);
        }
    }

    @Test
    void missingKeyOversizedAndEmptyAudioNeverLeaveTheProcess() throws Exception {
        try (var stub = WhisperStubServer.start()) {
            var noKey = new CloudRuWhisperSpeechToText(stub.baseUrl(), " ", "ASTOR_GLASSES_CLOUDRU_API_KEY", "", "ru", Duration.ofSeconds(5));
            assertThat(noKey.configured()).isFalse();
            assertThat(noKey.model()).isEqualTo(CloudRuWhisperSpeechToText.DEFAULT_MODEL);
            assertThatThrownBy(() -> noKey.transcribe(AUDIO, "a.ogg", "audio/ogg"))
                    .isInstanceOf(CloudRuWhisperException.class).hasMessageContaining("ASTOR_GLASSES_CLOUDRU_API_KEY");

            var client = client(stub, "ru", Duration.ofSeconds(5));
            assertThatThrownBy(() -> client.transcribe(new byte[(int) CloudRuWhisperSpeechToText.MAX_FILE_BYTES + 1], "big.ogg", "audio/ogg"))
                    .isInstanceOf(CloudRuWhisperException.class).hasMessageContaining("25");
            assertThatThrownBy(() -> client.transcribe(new byte[0], "a.ogg", "audio/ogg"))
                    .isInstanceOf(CloudRuWhisperException.class).hasMessageContaining("empty");
            assertThat(stub.requests()).isEmpty();
        }
    }

    @Test
    void answerWithoutTextOrNotJsonIsAnError() throws Exception {
        try (var stub = WhisperStubServer.start()) {
            stub.reply(200, "{\"segments\":[]}").reply(200, "<html>login</html>");
            var client = client(stub, "ru", Duration.ofSeconds(5));
            assertThatThrownBy(() -> client.transcribe(AUDIO, "a.ogg", "audio/ogg"))
                    .isInstanceOf(CloudRuWhisperException.class).hasMessageContaining("\"text\"");
            assertThatThrownBy(() -> client.transcribe(AUDIO, "a.ogg", "audio/ogg"))
                    .isInstanceOf(CloudRuWhisperException.class).hasMessageContaining("not JSON");
        }
    }

    @Test
    void defaultsToTheFoundationModelsEndpoint() {
        var client = new CloudRuWhisperSpeechToText("", KEY, "CLOUDRU_API_KEY", null, "ru", null);
        assertThat(client.endpoint().toString()).isEqualTo("https://foundation-models.api.cloud.ru/v1/audio/transcriptions");
        var trailing = new CloudRuWhisperSpeechToText("https://models.test/v1/", KEY, "CLOUDRU_API_KEY", null, "ru", null);
        assertThat(trailing.endpoint().toString()).isEqualTo("https://models.test/v1/audio/transcriptions");
    }
}
