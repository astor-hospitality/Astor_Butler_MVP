package museon_online.astor_butler.speech;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class CloudRuWhisperSpeechToTextServiceTest {

    @TempDir Path temp;

    private static CloudRuWhisperSpeechToText client(WhisperStubServer stub) {
        return new CloudRuWhisperSpeechToText(stub.baseUrl(), "key-fixture", "CLOUDRU_API_KEY", "openai/whisper-large-v3", "ru", Duration.ofSeconds(5));
    }

    @Test
    void transcribedTextCarriesProviderMetadata() throws Exception {
        try (var stub = WhisperStubServer.start()) {
            stub.reply(200, "{\"text\":\"столик на двоих\"}");
            Path file = Files.write(temp.resolve("voice.ogg"), new byte[]{1, 2, 3});

            SpeechToTextResult result = new CloudRuWhisperSpeechToTextService(true, client(stub)).transcribe(file, Map.of());

            assertThat(result.transcribed()).isTrue();
            assertThat(result.text()).isEqualTo("столик на двоих");
            assertThat(result.metadata()).containsEntry("provider", "cloudru")
                    .containsEntry("model", "openai/whisper-large-v3").containsEntry("language", "ru");
            assertThat(result.metadata().get("endpoint").toString()).endsWith("/v1/audio/transcriptions");
        }
    }

    @Test
    void blankTextAndProviderErrorsAreFailuresNotCrashes() throws Exception {
        try (var stub = WhisperStubServer.start()) {
            stub.reply(200, "{\"text\":\"   \"}").reply(401, "{\"error\":\"invalid api key\"}");
            Path file = Files.write(temp.resolve("voice.ogg"), new byte[]{1, 2, 3});
            var service = new CloudRuWhisperSpeechToTextService(true, client(stub));

            SpeechToTextResult blank = service.transcribe(file, Map.of());
            assertThat(blank.available()).isTrue();
            assertThat(blank.transcribed()).isFalse();
            assertThat(blank.reason()).contains("blank");

            SpeechToTextResult denied = service.transcribe(file, Map.of());
            assertThat(denied.transcribed()).isFalse();
            assertThat(denied.reason()).contains("HTTP 401");
            assertThat(denied.metadata()).containsEntry("httpStatus", 401).containsEntry("provider", "cloudru");
        }
    }

    @Test
    void disabledOrMissingFileDoesNotCallTheProvider() throws Exception {
        try (var stub = WhisperStubServer.start()) {
            assertThat(new CloudRuWhisperSpeechToTextService(false, client(stub)).transcribe(temp.resolve("x.ogg"), Map.of()).available()).isFalse();
            SpeechToTextResult missing = new CloudRuWhisperSpeechToTextService(true, client(stub)).transcribe(null, Map.of());
            assertThat(missing.available()).isTrue();
            assertThat(missing.transcribed()).isFalse();
            assertThat(stub.requests()).isEmpty();
        }
    }
}
