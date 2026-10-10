package museon_online.astor_butler.api.glasses;

import museon_online.astor_butler.speech.CloudRuWhisperSpeechToText;
import museon_online.astor_butler.speech.RecordingStubServer;
import museon_online.astor_butler.speech.RecordingStubServer.Answer;
import museon_online.astor_butler.speech.SpeechToTextProvider;
import museon_online.astor_butler.speech.WhisperStubServer;
import museon_online.astor_butler.speech.YandexSpeechKitSpeechToText;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.*;

class GlassesVoiceTest {
    @TempDir Path temp;

    private GlassesVoice voice(String body, long timeout) throws Exception {
        Path script = temp.resolve("helper.sh");
        Files.writeString(script, "#!/bin/sh\n" + body);
        script.toFile().setExecutable(true);
        return new GlassesVoice(true, "/bin/sh", script.toString(), "unused", temp.resolve("work").toString(), timeout);
    }

    private void emptyWork() throws Exception {
        try (var files = Files.list(temp.resolve("work"))) { assertThat(files.toList()).isEmpty(); }
    }

    @Test void boundsFilesAndDeletesAfterSuccess() throws Exception {
        var voice = voice("stat -f '%Lp' \"$3\" >/dev/null 2>&1 || true\nprintf '%s' '{\"status\":\"transcribed\",\"text\":\"тестовый transcript fixture\"}'", 1000);
        assertThat(voice.transcribe(new byte[]{1})).isEqualTo("тестовый transcript fixture");
        assertThat(voice.ready()).isTrue();
        emptyWork();
    }

    @Test void malformedMediaDoesNotBecomeText() throws Exception {
        var voice = voice("printf '%s' '{\"status\":\"malformed\"}'", 1000);
        assertThatThrownBy(() -> voice.transcribe(new byte[]{1})).satisfies(e -> assertThat(((GlassesFailure)e).status).isEqualTo(400));
        assertThat(voice.ready()).isFalse();
        emptyWork();
    }

    @Test void tooLongReturns413AndCleans() throws Exception {
        var voice = voice("printf '%s' '{\"status\":\"too_large\"}'", 1000);
        assertThatThrownBy(() -> voice.transcribe(new byte[]{1})).satisfies(e -> assertThat(((GlassesFailure)e).status).isEqualTo(413));
        emptyWork();
    }

    @Test void noSpeechIsRetryableInputErrorAndDoesNotDisableWorkingStt() throws Exception {
        var voice = voice("printf '%s' '{\"status\":\"no_speech\"}'", 1000);
        assertThatThrownBy(() -> voice.transcribe(new byte[]{1})).satisfies(e -> {
            assertThat(((GlassesFailure)e).status).isEqualTo(400);
            assertThat(((GlassesFailure)e).code).isEqualTo("NO_SPEECH");
        });
        assertThat(voice.ready()).isTrue();
        emptyWork();
    }

    @Test void timeoutStopsProcessBeforeCleanup() throws Exception {
        var voice = voice("exec sleep 10", 80);
        long before = System.nanoTime();
        assertThatThrownBy(() -> voice.transcribe(new byte[]{1})).isInstanceOf(GlassesFailure.class);
        assertThat((System.nanoTime() - before) / 1000000).isLessThan(4000);
        emptyWork();
    }

    @Test void stderrFloodAndProviderFailureAreUnavailableWithoutDiagnosticLeak() throws Exception {
        var voice = voice("yes secret-diagnostic >&2", 1000);
        assertThatThrownBy(() -> voice.transcribe(new byte[]{1})).isInstanceOf(GlassesFailure.class)
                .hasMessageNotContaining("secret-diagnostic");
        emptyWork();
    }

    @Test void temporaryMediaIsPrivate() throws Exception {
        var voice = voice("mode=$(stat -c %a \"$3\" 2>/dev/null || stat -f %Lp \"$3\")\ntest \"$mode\" = 600 || exit 1\n"
                + "mode=$(stat -c %a \"$(dirname \"$3\")\" 2>/dev/null || stat -f %Lp \"$(dirname \"$3\")\")\ntest \"$mode\" = 700 || exit 1\n"
                + "printf '%s' '{\"status\":\"transcribed\",\"text\":\"fixture\"}'", 1000);
        assertThat(voice.transcribe(new byte[]{1})).isEqualTo("fixture");
        emptyWork();
    }

    private GlassesVoice cloud(WhisperStubServer stub, String apiKey) {
        var client = new CloudRuWhisperSpeechToText(stub.baseUrl(), apiKey, "ASTOR_GLASSES_CLOUDRU_API_KEY",
                "openai/whisper-large-v3", "ru", Duration.ofSeconds(5));
        return new GlassesVoice(true, client, temp.resolve("work").toString(), 5000);
    }

    @Test void cloudProviderSendsTheRecordingAsMp4AndReturnsOnlyTheText() throws Exception {
        try (var stub = WhisperStubServer.start()) {
            stub.reply(200, "{\"text\":\" Стол семь просит счёт. \"}");
            var voice = cloud(stub, "glasses-key-fixture");
            assertThat(voice.provider()).isEqualTo(SpeechToTextProvider.CLOUDRU);
            assertThat(voice.transcribe(new byte[]{1, 2, 3})).isEqualTo("Стол семь просит счёт.");
            assertThat(voice.ready()).isTrue();
            var seen = stub.only();
            assertThat(seen.authorization()).isEqualTo("Bearer glasses-key-fixture");
            assertThat(seen.bodyText()).contains("filename=\"input.m4a\"\r\nContent-Type: audio/mp4\r\n\r\n\u0001\u0002\u0003\r\n")
                    .contains("name=\"model\"\r\n\r\nopenai/whisper-large-v3\r\n");
            // No temp file is written for the cloud path.
            assertThat(Files.exists(temp.resolve("work"))).isFalse();
        }
    }

    @Test void cloudEmptyTextIsNoSpeechAndKeepsTheProviderReady() throws Exception {
        try (var stub = WhisperStubServer.start()) {
            stub.reply(200, "{\"text\":\"\"}");
            var voice = cloud(stub, "glasses-key-fixture");
            assertThatThrownBy(() -> voice.transcribe(new byte[]{1})).satisfies(e -> {
                assertThat(((GlassesFailure) e).status).isEqualTo(400);
                assertThat(((GlassesFailure) e).code).isEqualTo("NO_SPEECH");
            });
            assertThat(voice.ready()).isTrue();
        }
    }

    @Test void cloudRejectedMediaIsMalformedAndServerFailureIsUnavailableWithoutLeak() throws Exception {
        try (var stub = WhisperStubServer.start()) {
            stub.reply(400, "{\"error\":\"secret-diagnostic unsupported format\"}")
                    .reply(500, "secret-diagnostic").reply(500, "secret-diagnostic");
            var voice = cloud(stub, "glasses-key-fixture");
            assertThatThrownBy(() -> voice.transcribe(new byte[]{1})).hasMessageNotContaining("secret-diagnostic")
                    .satisfies(e -> assertThat(((GlassesFailure) e).code).isEqualTo("MALFORMED_AUDIO"));
            assertThatThrownBy(() -> voice.transcribe(new byte[]{1})).hasMessageNotContaining("secret-diagnostic")
                    .satisfies(e -> assertThat(((GlassesFailure) e).status).isEqualTo(503));
            assertThat(voice.ready()).isFalse();
            assertThat(stub.requests()).hasSize(3);
        }
    }

    @Test void cloudWithoutKeyIsUnavailableAndNeverCallsOut() throws Exception {
        try (var stub = WhisperStubServer.start()) {
            var voice = cloud(stub, "");
            assertThatThrownBy(() -> voice.transcribe(new byte[]{1})).satisfies(e -> assertThat(((GlassesFailure) e).status).isEqualTo(503));
            assertThat(stub.requests()).isEmpty();
        }
    }

    // --- yandex: SpeechKit v1 after an ffmpeg transcode to Ogg Opus ---

    private static final String STT = "/speech/v1/stt:recognize";
    private static final byte[] M4A = {0, 0, 0, 24, 'f', 't', 'y', 'p', 'M', '4', 'A', ' ', 0, 0, 0, 0, 1, 2, 3};
    private static final byte[] OGG = {'O', 'g', 'g', 'S', 0, 2, 0, 0, 0, 0, 0, 0, 0, 0, 1, 2, 3};
    /** What the stand-in ffmpeg writes: an Ogg page header, so the SpeechKit adapter lets it through. */
    private static final byte[] TRANSCODED = "OggS\0\2fixture-opus".getBytes(StandardCharsets.ISO_8859_1);
    /**
     * A stand-in ffmpeg: finds the input after {@code -i} and the output as the last argument, checks the
     * transcoding flags and that the temp files are private, then writes {@link #TRANSCODED}.
     */
    private static final String FFMPEG_OK = """
            in=''; prev=''; last=''
            for a in "$@"; do if [ "$prev" = -i ]; then in="$a"; fi; prev="$a"; last="$a"; done
            case " $* " in *' -ac 1 '*) ;; *) exit 3;; esac
            case " $* " in *' -ar 16000 '*) ;; *) exit 3;; esac
            case " $* " in *' -c:a libopus '*) ;; *) exit 3;; esac
            case " $* " in *' -t 30 '*) ;; *) exit 3;; esac
            test -s "$in" || exit 4
            mode=$(stat -c %a "$in" 2>/dev/null || stat -f %Lp "$in"); test "$mode" = 600 || exit 5
            mode=$(stat -c %a "$(dirname "$in")" 2>/dev/null || stat -f %Lp "$(dirname "$in")"); test "$mode" = 700 || exit 5
            printf 'OggS\\000\\002fixture-opus' > "$last"
            """;

    private Path ffmpeg(String body) throws Exception {
        Path script = temp.resolve("ffmpeg.sh");
        Files.writeString(script, "#!/bin/sh\n" + body);
        script.toFile().setExecutable(true);
        return script;
    }

    private GlassesVoice speechKit(RecordingStubServer stub, String apiKey, String ffmpeg, long timeout) {
        var client = new YandexSpeechKitSpeechToText(HttpClient.newHttpClient(),
                new YandexSpeechKitSpeechToText.Settings(stub.url(STT), apiKey, "ru", Duration.ofSeconds(5)));
        return new GlassesVoice(true, client, ffmpeg, temp.resolve("work").toString(), timeout);
    }

    @Test void speechKitTranscodesTheRecordingWithFfmpegAndSendsOggOpus() throws Exception {
        try (var stub = RecordingStubServer.start()) {
            stub.route(STT, call -> Answer.json(200, "{\"result\":\" Стол семь просит счёт. \"}"));
            var voice = speechKit(stub, "unit-speechkit-key", ffmpeg(FFMPEG_OK).toString(), 5000);
            assertThat(voice.provider()).isEqualTo(SpeechToTextProvider.YANDEX);
            assertThat(voice.transcribe(M4A)).isEqualTo("Стол семь просит счёт.");
            assertThat(voice.ready()).isTrue();
            var call = stub.calls(STT).getFirst();
            assertThat(call.query()).isEqualTo("lang=ru-RU&format=oggopus");
            assertThat(call.header("Authorization")).isEqualTo("Api-Key unit-speechkit-key");
            assertThat(call.body()).isEqualTo(TRANSCODED);
            emptyWork();
        }
    }

    @Test void speechKitEmptyResultIsNoSpeechAndKeepsTheProviderReady() throws Exception {
        try (var stub = RecordingStubServer.start()) {
            stub.route(STT, call -> Answer.json(200, "{\"result\":\"  \"}"));
            var voice = speechKit(stub, "unit-speechkit-key", ffmpeg(FFMPEG_OK).toString(), 5000);
            assertThatThrownBy(() -> voice.transcribe(M4A)).satisfies(e -> {
                assertThat(((GlassesFailure) e).status).isEqualTo(400);
                assertThat(((GlassesFailure) e).code).isEqualTo("NO_SPEECH");
            });
            assertThat(voice.ready()).isTrue();
            emptyWork();
        }
    }

    @Test void ffmpegRejectingTheMediaIsMalformedAudioAndNothingLeavesTheServer() throws Exception {
        try (var stub = RecordingStubServer.start()) {
            stub.route(STT, call -> Answer.json(200, "{\"result\":\"never\"}"));
            var voice = speechKit(stub, "unit-speechkit-key", ffmpeg("echo secret-diagnostic >&2\nexit 1").toString(), 5000);
            assertThatThrownBy(() -> voice.transcribe(new byte[]{1, 2, 3})).hasMessageNotContaining("secret-diagnostic")
                    .satisfies(e -> {
                        assertThat(((GlassesFailure) e).status).isEqualTo(400);
                        assertThat(((GlassesFailure) e).code).isEqualTo("MALFORMED_AUDIO");
                    });
            assertThat(stub.calls()).isEmpty();
            emptyWork();
        }
    }

    @Test void speechKitRejectionIsMalformedAndServerFailureIsUnavailableWithoutLeak() throws Exception {
        try (var stub = RecordingStubServer.start()) {
            stub.route(STT, call -> Answer.json(200, "{\"result\":\"ok\"}"));
            var voice = speechKit(stub, "unit-speechkit-key", ffmpeg(FFMPEG_OK).toString(), 5000);
            assertThat(voice.transcribe(M4A)).isEqualTo("ok");
            assertThat(voice.ready()).isTrue();

            stub.route(STT, call -> Answer.json(400, "{\"error_code\":\"BAD_REQUEST\",\"error_message\":\"secret-diagnostic\"}"));
            assertThatThrownBy(() -> voice.transcribe(M4A)).hasMessageNotContaining("secret-diagnostic")
                    .satisfies(e -> assertThat(((GlassesFailure) e).code).isEqualTo("MALFORMED_AUDIO"));
            // A rejected recording does not take a working provider out of readiness.
            assertThat(voice.ready()).isTrue();

            stub.route(STT, call -> Answer.json(503, "{\"error_code\":\"secret-diagnostic\"}"));
            assertThatThrownBy(() -> voice.transcribe(M4A)).hasMessageNotContaining("secret-diagnostic")
                    .satisfies(e -> assertThat(((GlassesFailure) e).status).isEqualTo(503));
            assertThat(voice.ready()).isFalse();
            // One success, one rejection, one 5xx retried once.
            assertThat(stub.calls(STT)).hasSize(4);
            emptyWork();
        }
    }

    @Test void oggOpusRecordingsSkipFfmpegAndGoOutAsTheyAre() throws Exception {
        try (var stub = RecordingStubServer.start()) {
            stub.route(STT, call -> Answer.json(200, "{\"result\":\"Счёт, пожалуйста\"}"));
            Path marker = temp.resolve("ffmpeg-was-called");
            var voice = speechKit(stub, "unit-speechkit-key", ffmpeg("touch '" + marker + "'\nexit 1").toString(), 5000);
            assertThat(voice.transcribe(OGG)).isEqualTo("Счёт, пожалуйста");
            assertThat(stub.calls(STT).getFirst().body()).isEqualTo(OGG);
            assertThat(Files.exists(marker)).isFalse();
            // No temp file is written when nothing is transcoded.
            assertThat(Files.exists(temp.resolve("work"))).isFalse();
        }
    }

    @Test void audioAboveTheSpeechKitSyncLimitIs413AndNeverLeaves() throws Exception {
        try (var stub = RecordingStubServer.start()) {
            stub.route(STT, call -> Answer.json(200, "{\"result\":\"never\"}"));
            var voice = speechKit(stub, "unit-speechkit-key", ffmpeg(FFMPEG_OK).toString(), 5000);
            byte[] big = Arrays.copyOf(OGG, (int) YandexSpeechKitSpeechToText.MAX_SYNC_BYTES + 1);
            assertThatThrownBy(() -> voice.transcribe(big)).satisfies(e -> {
                assertThat(((GlassesFailure) e).status).isEqualTo(413);
                assertThat(((GlassesFailure) e).code).isEqualTo("AUDIO_TOO_LONG");
            });
            assertThat(stub.calls()).isEmpty();
        }
    }

    @Test void speechKitWithoutKeyOrFfmpegIsUnavailableAndNeverCallsOut() throws Exception {
        try (var stub = RecordingStubServer.start()) {
            stub.route(STT, call -> Answer.json(200, "{\"result\":\"never\"}"));
            var noKey = speechKit(stub, "", ffmpeg(FFMPEG_OK).toString(), 5000);
            assertThatThrownBy(() -> noKey.transcribe(M4A)).satisfies(e -> assertThat(((GlassesFailure) e).status).isEqualTo(503));
            assertThat(noKey.ready()).isFalse();
            var noFfmpeg = speechKit(stub, "unit-speechkit-key", temp.resolve("missing/ffmpeg").toString(), 5000);
            assertThatThrownBy(() -> noFfmpeg.transcribe(M4A)).satisfies(e -> assertThat(((GlassesFailure) e).status).isEqualTo(503));
            assertThat(stub.calls()).isEmpty();
            emptyWork();
        }
    }

    @Test void ffmpegTimeoutIsBoundedAndCleansUp() throws Exception {
        try (var stub = RecordingStubServer.start()) {
            var voice = speechKit(stub, "unit-speechkit-key", ffmpeg("exec sleep 10").toString(), 80);
            long before = System.nanoTime();
            assertThatThrownBy(() -> voice.transcribe(M4A)).satisfies(e -> assertThat(((GlassesFailure) e).status).isEqualTo(503));
            assertThat((System.nanoTime() - before) / 1000000).isLessThan(4000);
            assertThat(stub.calls()).isEmpty();
            emptyWork();
        }
    }

    @Test void providerIsSelectedByVariableAndUnknownValuesFailFast() {
        var local = new GlassesVoice(true, "local", "python3", "/app/glasses_stt.py", "/models", temp.toString(), 1000,
                "", "", "openai/whisper-large-v3", "ru", "", "", "ffmpeg");
        assertThat(local.provider()).isEqualTo(SpeechToTextProvider.LOCAL);
        var cloud = new GlassesVoice(true, "cloudru", "python3", "/app/glasses_stt.py", "/models", temp.toString(), 1000,
                "", "glasses-key-fixture", "openai/whisper-large-v3", "ru", "", "", "ffmpeg");
        assertThat(cloud.provider()).isEqualTo(SpeechToTextProvider.CLOUDRU);
        // SpeechKit takes the phone's MP4/AAC after an ffmpeg transcode; the key may come from YANDEX_SPEECHKIT_API_KEY.
        var yandex = new GlassesVoice(true, "yandex", "python3", "", "", temp.toString(), 1000,
                "", "", "", "ru", "unit-speechkit-key", "", "ffmpeg");
        assertThat(yandex.provider()).isEqualTo(SpeechToTextProvider.YANDEX);
        assertThatThrownBy(() -> new GlassesVoice(true, "salute", "python3", "", "", temp.toString(), 1000, "", "", "", "ru", "", "", "ffmpeg"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("ASTOR_GLASSES_STT_PROVIDER");
    }
}
