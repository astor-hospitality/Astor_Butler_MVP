package museon_online.astor_butler.api.glasses;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.*;

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
}
