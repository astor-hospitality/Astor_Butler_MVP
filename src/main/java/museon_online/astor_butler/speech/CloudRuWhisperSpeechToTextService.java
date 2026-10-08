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
 * {@link SpeechToTextService} on Cloud.ru whisper-large-v3, selected with {@code ASTOR_STT_PROVIDER=cloudru}
 * (the default). Reuses {@code CLOUDRU_BASE_URL} / {@code CLOUDRU_API_KEY} of the model gateway; the model
 * comes from {@code ASTOR_STT_CLOUDRU_MODEL}, the language hint from {@code ASTOR_STT_LANGUAGE} and the
 * timeout from {@code ASTOR_STT_TIMEOUT_SECONDS}, the same knobs the local subprocess used.
 */
@Service
@ConditionalOnProperty(prefix = "astor.speech-to-text", name = "provider", havingValue = "cloudru", matchIfMissing = true)
@Slf4j
public class CloudRuWhisperSpeechToTextService implements SpeechToTextService {

    static final String PROVIDER = "cloudru";

    private final boolean enabled;
    private final CloudRuWhisperSpeechToText client;

    @Autowired
    public CloudRuWhisperSpeechToTextService(
            @Value("${astor.speech-to-text.enabled:false}") boolean enabled,
            @Value("${astor.speech-to-text.cloudru.base-url:" + CloudRuWhisperSpeechToText.DEFAULT_BASE_URL + "}") String baseUrl,
            @Value("${astor.speech-to-text.cloudru.api-key:}") String apiKey,
            @Value("${astor.speech-to-text.cloudru.model:" + CloudRuWhisperSpeechToText.DEFAULT_MODEL + "}") String model,
            @Value("${astor.speech-to-text.language:ru}") String language,
            @Value("${astor.speech-to-text.timeout-seconds:30}") long timeoutSeconds) {
        this(enabled, new CloudRuWhisperSpeechToText(baseUrl, apiKey, "CLOUDRU_API_KEY", model, language,
                Duration.ofSeconds(Math.max(1, timeoutSeconds))));
    }

    CloudRuWhisperSpeechToTextService(boolean enabled, CloudRuWhisperSpeechToText client) {
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
                return SpeechToTextResult.failed("Cloud.ru STT returned blank text", metadata(null));
            }
            return SpeechToTextResult.transcribed(text, metadata(null));
        } catch (CloudRuWhisperException e) {
            // The message names the cause (status, timeout, missing key); never the audio or the text.
            log.warn("Cloud.ru STT failed: {}", e.getMessage());
            return SpeechToTextResult.failed(e.getMessage(), metadata(e.status()));
        }
    }

    private Map<String, Object> metadata(Integer status) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("provider", PROVIDER);
        metadata.put("model", client.model());
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
