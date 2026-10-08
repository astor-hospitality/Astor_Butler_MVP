package museon_online.astor_butler.api.speech;

import museon_online.astor_butler.speech.TextToSpeech;
import museon_online.astor_butler.speech.TextToSpeechException;
import org.junit.jupiter.api.Test;

import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class ChatSpeechControllerTest {

    private static final byte[] WAV = {'R', 'I', 'F', 'F', 0, 0, 0, 0};
    private final TextToSpeech speech = mock(TextToSpeech.class);

    private void saluteVoice() {
        when(speech.provider()).thenReturn("salute");
        when(speech.voice()).thenReturn("Nec_24000");
        when(speech.voiceGender()).thenReturn("female");
        when(speech.mimeType()).thenReturn("audio/wav");
        when(speech.configured()).thenReturn(true);
    }

    @Test
    void readsOneReplyAndAnswersWhatTheWidgetPlays() {
        saluteVoice();
        when(speech.synthesize("Расскажу о сроках.")).thenReturn(WAV);
        var controller = new ChatSpeechController(speech, true, 2, 60);

        var result = controller.speak(new ChatSpeechController.SpeakRequest("  Расскажу о сроках.  ", "clio-feminine-built-in"));

        assertThat(result.getStatusCode().value()).isEqualTo(200);
        assertThat(result.getHeaders().getFirst("Cache-Control")).isEqualTo("no-store");
        var body = result.getBody();
        assertThat(body.status()).isEqualTo("READY");
        assertThat(body.provider()).isEqualTo("salute");
        assertThat(body.voice()).isEqualTo("clio-female-Nec_24000");
        assertThat(body.audioMimeType()).isEqualTo("audio/wav");
        assertThat(Base64.getDecoder().decode(body.audioBase64())).isEqualTo(WAV);
        assertThat(body.audioUrl()).isEqualTo("data:audio/wav;base64," + body.audioBase64());
        assertThat(body.createdAt()).isNotBlank();
    }

    @Test
    void isOffUntilEnabledAndConfiguredAndNeverCallsTheProviderThen() {
        saluteVoice();
        var disabled = new ChatSpeechController(speech, false, 2, 60);
        var off = disabled.speak(new ChatSpeechController.SpeakRequest("Строка", null));
        assertThat(off.getStatusCode().value()).isEqualTo(503);
        assertThat(off.getBody().status()).isEqualTo("UNAVAILABLE");
        assertThat(off.getBody().audioUrl()).isNull();
        assertThat(off.getBody().message()).contains("ASTOR_TTS_WEB_ENABLED");

        when(speech.configured()).thenReturn(false);
        var unconfigured = new ChatSpeechController(speech, true, 2, 60);
        assertThat(unconfigured.speak(new ChatSpeechController.SpeakRequest("Строка", null)).getStatusCode().value()).isEqualTo(503);
        verify(speech, never()).synthesize(any());
    }

    @Test
    void rejectsEmptyAndOversizedTextBeforeSpendingMoney() {
        saluteVoice();
        var controller = new ChatSpeechController(speech, true, 2, 60);
        assertThat(controller.speak(null).getStatusCode().value()).isEqualTo(400);
        assertThat(controller.speak(new ChatSpeechController.SpeakRequest("   ", null)).getStatusCode().value()).isEqualTo(400);
        var tooLong = controller.speak(new ChatSpeechController.SpeakRequest("ё".repeat(ChatSpeechController.TEXT_LIMIT + 1), null));
        assertThat(tooLong.getStatusCode().value()).isEqualTo(413);
        verify(speech, never()).synthesize(any());
    }

    @Test
    void aProviderFailureIsUnavailableWithoutDetailsAndTheRateCapHolds() {
        saluteVoice();
        when(speech.synthesize(any())).thenThrow(new TextToSpeechException("SaluteSpeech synthesis answered 500", 500));
        var controller = new ChatSpeechController(speech, true, 2, 2);

        var failed = controller.speak(new ChatSpeechController.SpeakRequest("Строка", null));
        assertThat(failed.getStatusCode().value()).isEqualTo(503);
        assertThat(failed.getBody().status()).isEqualTo("UNAVAILABLE");
        assertThat(failed.getBody().message()).doesNotContain("500");

        controller.speak(new ChatSpeechController.SpeakRequest("Строка", null));
        var limited = controller.speak(new ChatSpeechController.SpeakRequest("Строка", null));
        assertThat(limited.getStatusCode().value()).isEqualTo(429);
        assertThat(limited.getHeaders().getFirst("Retry-After")).isEqualTo("10");
        verify(speech, times(2)).synthesize(any());
    }
}
