package museon_online.astor_butler.telegram.adapter;

import museon_online.astor_butler.service.message.IncomingMessage;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.telegram.telegrambots.meta.bots.AbsSender;

import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TelegramVoiceEnrichmentGuardTest {

    @Test
    void failsOpenWhenVoiceEnrichmentTimesOut() {
        TelegramVoiceTranscriptionService transcriptionService = mock(TelegramVoiceTranscriptionService.class);
        TelegramVoiceEnrichmentGuard guard = new TelegramVoiceEnrichmentGuard(transcriptionService);
        ReflectionTestUtils.setField(guard, "enrichmentTimeoutMs", 250L);
        IncomingMessage incoming = telegramVoice();

        when(transcriptionService.enrich(any(), any())).thenAnswer(invocation -> {
            TimeUnit.MILLISECONDS.sleep(1000);
            return invocation.getArgument(0);
        });

        IncomingMessage enriched = guard.enrich(incoming, mock(AbsSender.class));

        assertThat(enriched.text()).isBlank();
        assertThat(enriched.payload()).containsEntry("mediaKind", "VOICE");
        assertThat(enriched.payload()).containsEntry("transcriptionAvailable", false);
        assertThat(enriched.payload()).containsEntry("transcriptionStatus", "FAILED");
        assertThat(enriched.payload().get("transcriptionReason").toString()).contains("TimeoutException");
    }

    private IncomingMessage telegramVoice() {
        return IncomingMessage.telegram(
                814518440L,
                814518440L,
                351,
                284070688,
                "",
                null,
                "Voice",
                "Smoke",
                "voice_smoke",
                "ru",
                false,
                "284070688",
                Map.of(
                        "mediaKind", "VOICE",
                        "telegramFileId", "file-id",
                        "durationSeconds", 7,
                        "fileSizeBytes", 4096,
                        "mimeType", "audio/ogg"
                )
        );
    }
}
