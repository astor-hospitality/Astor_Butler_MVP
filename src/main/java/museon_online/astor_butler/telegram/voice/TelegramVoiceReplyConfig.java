package museon_online.astor_butler.telegram.voice;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/**
 * Everything the Telegram voice reply reads from the environment, in one immutable record so the service and
 * its tests see the same numbers.
 *
 * @param mode             {@link VoiceRepliesMode}
 * @param timeout          hard ceiling for synthesising all notes of one answer; text never waits for it
 * @param summaryMaxChars  the short text message, 400 by default
 * @param speechChunkChars one voice note holds at most this many characters of speech (~1 minute at 900)
 * @param speechMaxChars   the whole answer is spoken up to this many characters (SaluteSpeech body limit is 4000)
 * @param maxVoiceBytes    a note above this size is dropped rather than sent (Telegram is fine with more, the guest is not)
 * @param maxLinks         link buttons under the voice note
 * @param maxPhotos        photos sent next to the summary when the answer references images
 * @param summaryViaModel  false keeps the deterministic summary only, no model call per reply
 * @param detailsTtl       how long the full text behind "Подробнее" stays in the database
 */
public record TelegramVoiceReplyConfig(
        VoiceRepliesMode mode,
        Duration timeout,
        int summaryMaxChars,
        int speechChunkChars,
        int speechMaxChars,
        int maxVoiceBytes,
        int maxLinks,
        int maxPhotos,
        boolean summaryViaModel,
        Duration detailsTtl
) {

    public static TelegramVoiceReplyConfig defaults(VoiceRepliesMode mode) {
        return new TelegramVoiceReplyConfig(mode, Duration.ofMillis(8000), 400, 900, 4000, 1024 * 1024, 3, 3, true,
                Duration.ofDays(7));
    }

    public boolean enabled() {
        return mode != VoiceRepliesMode.OFF;
    }

    @Slf4j
    @Configuration
    public static class Binding {

        @Bean
        public TelegramVoiceReplyConfig telegramVoiceReplyConfig(
                @Value("${astor.telegram.voice-replies.mode:off}") String mode,
                @Value("${astor.telegram.voice-replies.timeout-ms:8000}") long timeoutMs,
                @Value("${astor.telegram.voice-replies.summary-max-chars:400}") int summaryMaxChars,
                @Value("${astor.telegram.voice-replies.speech-chunk-chars:900}") int speechChunkChars,
                @Value("${astor.telegram.voice-replies.speech-max-chars:4000}") int speechMaxChars,
                @Value("${astor.telegram.voice-replies.max-voice-bytes:1048576}") int maxVoiceBytes,
                @Value("${astor.telegram.voice-replies.max-links:3}") int maxLinks,
                @Value("${astor.telegram.voice-replies.max-photos:3}") int maxPhotos,
                @Value("${astor.telegram.voice-replies.summary-via-model:true}") boolean summaryViaModel,
                @Value("${astor.telegram.voice-replies.details-ttl-days:7}") int detailsTtlDays
        ) {
            TelegramVoiceReplyConfig config = new TelegramVoiceReplyConfig(
                    VoiceRepliesMode.parse(mode),
                    Duration.ofMillis(Math.max(500L, timeoutMs)),
                    Math.max(80, summaryMaxChars),
                    Math.max(200, speechChunkChars),
                    Math.max(200, speechMaxChars),
                    Math.max(64 * 1024, maxVoiceBytes),
                    Math.max(0, maxLinks),
                    Math.max(0, maxPhotos),
                    summaryViaModel,
                    Duration.ofDays(Math.max(1, detailsTtlDays)));
            log.info("Telegram voice replies mode={} timeout={}ms summaryViaModel={}", config.mode(),
                    config.timeout().toMillis(), config.summaryViaModel());
            return config;
        }
    }
}
