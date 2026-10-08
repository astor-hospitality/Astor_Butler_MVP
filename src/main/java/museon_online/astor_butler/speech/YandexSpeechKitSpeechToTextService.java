package museon_online.astor_butler.speech;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * {@link SpeechToTextService} on Yandex SpeechKit v1 sync recognition, selected with {@code ASTOR_STT_PROVIDER=yandex}.
 * Uses the voice's key ({@code YANDEX_SPEECHKIT_API_KEY}, else {@code YANDEX_API_KEY}); the endpoint is
 * {@code YANDEX_SPEECHKIT_STT_ENDPOINT}, the language hint {@code ASTOR_STT_LANGUAGE} ({@code ru} goes out as
 * {@code ru-RU}) and the timeout {@code ASTOR_STT_TIMEOUT_SECONDS}. Telegram voice notes are Ogg Opus, which SpeechKit
 * takes as they are; a note over 1 MB or 30 seconds fails with the reason instead of being cut.
 */
@Service
@ConditionalOnProperty(prefix = "astor.speech-to-text", name = "provider", havingValue = "yandex")
@Slf4j
public class YandexSpeechKitSpeechToTextService implements SpeechToTextService {

    static final String PROVIDER = YandexSpeechKitSpeechToText.PROVIDER;

    private final boolean enabled;
    private final YandexSpeechKitSpeechToText client;

    @Autowired
    public YandexSpeechKitSpeechToTextService(
            @Value("${astor.speech-to-text.enabled:false}") boolean enabled,
            @Value("${astor.speech-to-text.yandex.endpoint:" + YandexSpeechKitSpeechToText.DEFAULT_ENDPOINT + "}") String endpoint,
            @Value("${astor.speech-to-text.yandex.api-key:}") String apiKey,
            @Value("${astor.speech-to-text.language:ru}") String language,
            @Value("${astor.speech-to-text.timeout-seconds:30}") long timeoutSeconds) {
        this(enabled, new YandexSpeechKitSpeechToText(new YandexSpeechKitSpeechToText.Settings(endpoint, apiKey, language,
                Duration.ofSeconds(Math.max(1, timeoutSeconds)))));
        if (enabled && !client.configured()) {
            log.warn("STT provider yandex is selected but not configured; voice notes fail until YANDEX_SPEECHKIT_API_KEY is set");
        }
    }

    YandexSpeechKitSpeechToTextService(boolean enabled, YandexSpeechKitSpeechToText client) {
        this.enabled = enabled;
        this.client = client;
    }

    @Override
    public SpeechToTextResult transcribe(Path audioFile, Map<String, Object> context) {
        if (!enabled) {
            return SpeechToTextResult.unavailable("STT disabled");
        }
        if (audioFile == null) {
            return SpeechToTextResult.failed("Audio file is missing", context);
        }
        try {
            String text = client.transcribe(audioFile);
            if (text.isBlank()) {
                return SpeechToTextResult.failed("SpeechKit STT returned blank text", metadata(null));
            }
            return SpeechToTextResult.transcribed(text, metadata(null));
        } catch (SpeechToTextException e) {
            // The message names the cause (status, limit, encoding, missing key); never the audio or the text.
            log.warn("SpeechKit STT failed: {}", e.getMessage());
            return SpeechToTextResult.failed(e.getMessage(), metadata(e.status()));
        }
    }

    private Map<String, Object> metadata(Integer status) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("provider", PROVIDER);
        metadata.put("endpoint", client.endpoint().toString());
        if (!client.language().isEmpty()) {
            metadata.put("language", client.language());
        }
        if (status != null && status > 0) {
            metadata.put("httpStatus", status);
        }
        return Map.copyOf(metadata);
    }
}
