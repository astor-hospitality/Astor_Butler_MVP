package museon_online.astor_butler.speech;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/**
 * The application's {@link TextToSpeech}, chosen by {@code ASTOR_TTS_PROVIDER} ({@code astor.tts.provider}):
 * {@code salute} (default, Sber SaluteSpeech) or {@code yandex} (SpeechKit; {@code YANDEX_TTS_FORMAT=oggopus}
 * for Telegram voice replies). The bean always
 * exists; an unconfigured provider logs once and answers "unavailable" to callers instead of failing startup,
 * because speech is optional everywhere it is used.
 */
@Slf4j
@Configuration
public class TextToSpeechConfig {

    @Bean
    public TextToSpeech textToSpeech(
            @Value("${astor.tts.provider:salute}") String provider,
            @Value("${astor.tts.yandex.endpoint:}") String yandexEndpoint,
            @Value("${astor.tts.yandex.api-key:}") String yandexApiKey,
            @Value("${astor.tts.yandex.folder-id:}") String yandexFolder,
            @Value("${astor.tts.yandex.voice:}") String yandexVoice,
            @Value("${astor.tts.yandex.role:}") String yandexRole,
            @Value("${astor.tts.yandex.speed:0.95}") double yandexSpeed,
            @Value("${astor.tts.yandex.format:mp3}") String yandexFormat,
            @Value("${astor.tts.salute.auth-key:}") String saluteAuthKey,
            @Value("${astor.tts.salute.scope:}") String saluteScope,
            @Value("${astor.tts.salute.voice:}") String saluteVoice,
            @Value("${astor.tts.salute.format:}") String saluteFormat,
            @Value("${astor.tts.salute.ca-cert-path:}") String saluteCaCertPath,
            @Value("${astor.tts.salute.oauth-url:}") String saluteOauthUrl,
            @Value("${astor.tts.salute.tts-url:}") String saluteTtsUrl,
            @Value("${astor.tts.salute.timeout-ms:10000}") int saluteTimeoutMs
    ) {
        TextToSpeech speech = TextToSpeechProviders.select(provider,
                new SpeechKitTextToSpeech.Settings(yandexEndpoint, yandexApiKey, yandexFolder, yandexVoice, yandexRole, yandexSpeed,
                        yandexFormat),
                new SaluteSpeechTextToSpeech.Settings(saluteAuthKey, saluteScope, saluteVoice, saluteFormat, saluteCaCertPath,
                        saluteOauthUrl, saluteTtsUrl, Duration.ofMillis(saluteTimeoutMs)));
        if (!speech.configured()) {
            log.warn("TTS provider {} is selected but not configured; server speech answers unavailable until {} is set",
                    speech.provider(), speech.provider().equals(TextToSpeechProviders.SALUTE) ? "SALUTE_AUTH_KEY"
                            : "YANDEX_SPEECHKIT_API_KEY and YANDEX_SPEECHKIT_FOLDER_ID");
        } else if (speech.provider().equals(TextToSpeechProviders.SALUTE) && saluteCaCertPath.isBlank()) {
            log.warn("SALUTE_CA_CERT_PATH is not set: Sber speech endpoints are trusted only if the Russian Trusted Root CA "
                    + "is already in the JVM trust store");
        }
        log.info("TTS provider={} voice={} format={}", speech.provider(), speech.voice(), speech.mimeType());
        return speech;
    }
}
