package museon_online.astor_butler.api.glasses;

import museon_online.astor_butler.speech.CloudRuWhisperSpeechToText;
import museon_online.astor_butler.speech.SpeechToTextProvider;
import museon_online.astor_butler.speech.WhisperStubServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.*;
import java.time.Duration;

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

    @Test void providerIsSelectedByVariableAndUnknownValuesFailFast() {
        var local = new GlassesVoice(true, "local", "python3", "/app/glasses_stt.py", "/models", temp.toString(), 1000,
                "", "", "openai/whisper-large-v3", "ru");
        assertThat(local.provider()).isEqualTo(SpeechToTextProvider.LOCAL);
        var cloud = new GlassesVoice(true, "cloudru", "python3", "/app/glasses_stt.py", "/models", temp.toString(), 1000,
                "", "glasses-key-fixture", "openai/whisper-large-v3", "ru");
        assertThat(cloud.provider()).isEqualTo(SpeechToTextProvider.CLOUDRU);
        assertThatThrownBy(() -> new GlassesVoice(true, "salute", "python3", "", "", temp.toString(), 1000, "", "", "", "ru"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("ASTOR_GLASSES_STT_PROVIDER");
    }
}
