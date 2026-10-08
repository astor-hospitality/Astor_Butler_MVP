package museon_online.astor_butler.speech;

import museon_online.astor_butler.speech.RecordingStubServer.Answer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The SpeechKit v1 sync recognition contract against a local stub: what leaves, what comes back, what never leaves. */
class YandexSpeechKitSpeechToTextTest {

    private static final String PATH = "/speech/v1/stt:recognize";
    private static final String KEY = "unit-speechkit-key";
    /** An Ogg page header is all the adapter looks at; the stub does not decode audio. */
    private static final byte[] OGG = {'O', 'g', 'g', 'S', 0, 2, 0, 0, 0, 0, 0, 0, 0, 0, 1, 2, 3};
    private static final byte[] M4A = {0, 0, 0, 24, 'f', 't', 'y', 'p', 'M', '4', 'A', ' ', 0, 0, 0, 0};

    @TempDir Path temp;
    private RecordingStubServer stub;

    @BeforeEach
    void start() throws Exception {
        stub = RecordingStubServer.start();
    }

    @AfterEach
    void stop() {
        stub.close();
    }

    private YandexSpeechKitSpeechToText adapter(String key, String language) {
        return new YandexSpeechKitSpeechToText(HttpClient.newHttpClient(),
                new YandexSpeechKitSpeechToText.Settings(stub.url(PATH), key, language, Duration.ofSeconds(5)));
    }

    @Test
    void oggOpusGoesOutAsItIsWithTheApiKeyLanguageAndFormatAndTheResultComesBack() throws Exception {
        stub.route(PATH, call -> Answer.json(200, "{\"result\":\"  Забронируйте стол на семь вечера  \"}"));
        Path voice = temp.resolve("voice.oga");
        Files.write(voice, OGG);

        String text = adapter(KEY, "ru").transcribe(voice);

        assertThat(text).isEqualTo("Забронируйте стол на семь вечера");
        var call = stub.calls(PATH).getFirst();
        assertThat(call.method()).isEqualTo("POST");
        assertThat(call.query()).isEqualTo("lang=ru-RU&format=oggopus");
        assertThat(call.header("Authorization")).isEqualTo("Api-Key " + KEY);
        assertThat(call.header("Content-Type")).isEqualTo("application/octet-stream");
        assertThat(call.body()).isEqualTo(OGG);
        // A service-account key carries its folder; folderId is only for user accounts.
        assertThat(call.query()).doesNotContain("folderId");
    }

    @Test
    void languageHintsMapToSpeechKitCodes() {
        assertThat(YandexSpeechKitSpeechToText.locale("ru")).isEqualTo("ru-RU");
        assertThat(YandexSpeechKitSpeechToText.locale("EN")).isEqualTo("en-US");
        assertThat(YandexSpeechKitSpeechToText.locale("kk-KZ")).isEqualTo("kk-KZ");
        assertThat(YandexSpeechKitSpeechToText.locale(" ")).isEmpty();
        assertThat(adapter(KEY, "").endpoint().getRawQuery()).isEqualTo("format=oggopus");
    }

    @Test
    void otherEncodingsTooLargeAudioAndAMissingKeyNeverLeaveTheServer() {
        YandexSpeechKitSpeechToText stt = adapter(KEY, "ru");
        assertThatThrownBy(() -> stt.transcribe(M4A)).isInstanceOf(SpeechToTextException.class)
                .hasMessageContaining("MP4/M4A").satisfies(e -> assertThat(((SpeechToTextException) e).status()).isZero());
        byte[] big = new byte[(int) YandexSpeechKitSpeechToText.MAX_SYNC_BYTES + 1];
        System.arraycopy(OGG, 0, big, 0, OGG.length);
        assertThatThrownBy(() -> stt.transcribe(big)).hasMessageContaining("1 MB");
        assertThatThrownBy(() -> stt.transcribe(new byte[0])).hasMessageContaining("empty");
        YandexSpeechKitSpeechToText noKey = adapter("", "ru");
        assertThat(noKey.configured()).isFalse();
        assertThatThrownBy(() -> noKey.transcribe(OGG)).hasMessageContaining("YANDEX_SPEECHKIT_API_KEY");
        assertThat(stub.calls()).isEmpty();
    }

    @Test
    void aRejectionIsNotRetriedAServerErrorIsRetriedOnceAndNothingLeaks() {
        stub.route(PATH, call -> Answer.json(400, "{\"error_code\":\"BAD_REQUEST\",\"error_message\":\"audio duration should be less than 30s\"}"));
        YandexSpeechKitSpeechToText stt = adapter(KEY, "ru");
        assertThatThrownBy(() -> stt.transcribe(OGG)).isInstanceOf(SpeechToTextException.class)
                .hasMessageContaining("400").hasMessageContaining("30s")
                .satisfies(e -> {
                    assertThat(((SpeechToTextException) e).status()).isEqualTo(400);
                    assertThat(e.getMessage()).doesNotContain(KEY);
                });
        assertThat(stub.calls(PATH)).hasSize(1);

        stub.route(PATH, call -> Answer.json(503, "{\"error_code\":\"UNAVAILABLE\"}"));
        assertThatThrownBy(() -> stt.transcribe(OGG)).hasMessageContaining("503 twice")
                .satisfies(e -> assertThat(((SpeechToTextException) e).status()).isEqualTo(503));
        assertThat(stub.calls(PATH)).hasSize(3);

        stub.route(PATH, call -> Answer.json(200, "{\"text\":\"wrong shape\"}"));
        assertThatThrownBy(() -> stt.transcribe(OGG)).hasMessageContaining("\"result\"");
    }

    @Test
    void theServiceReportsTextFailuresAndSilenceInTheSharedResultShape() throws Exception {
        Path voice = temp.resolve("voice.ogg");
        Files.write(voice, OGG);
        stub.route(PATH, call -> Answer.json(200, "{\"result\":\"Счёт, пожалуйста\"}"));
        var service = new YandexSpeechKitSpeechToTextService(true, adapter(KEY, "ru"));

        SpeechToTextResult ok = service.transcribe(voice, Map.of());
        assertThat(ok.transcribed()).isTrue();
        assertThat(ok.text()).isEqualTo("Счёт, пожалуйста");
        assertThat(ok.metadata()).containsEntry("provider", "yandex").containsEntry("language", "ru-RU");

        stub.route(PATH, call -> Answer.json(200, "{\"result\":\"\"}"));
        assertThat(service.transcribe(voice, Map.of()).reason()).contains("blank");

        stub.route(PATH, call -> Answer.json(401, "{\"error_code\":\"UNAUTHORIZED\"}"));
        SpeechToTextResult failed = service.transcribe(voice, Map.of());
        assertThat(failed.available()).isTrue();
        assertThat(failed.transcribed()).isFalse();
        assertThat(failed.metadata()).containsEntry("httpStatus", 401);

        assertThat(new YandexSpeechKitSpeechToTextService(false, adapter(KEY, "ru")).transcribe(voice, Map.of()).available())
                .isFalse();
    }
}
