package museon_online.astor_butler.api.speech;

import lombok.extern.slf4j.Slf4j;
import museon_online.astor_butler.speech.TextToSpeech;
import museon_online.astor_butler.speech.TextToSpeechException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.Base64;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Clio's voice for the C3AG web chat: {@code POST /api/chat/speak {"text": "..."}} reads one reply in the
 * server voice and answers the shape the widget already plays ({@code audioUrl} as a data URL, plus the
 * raw {@code audioBase64}/{@code audioMimeType}). The frontend's own {@code /api/chat/speak} route stays
 * a test double; production points {@code NEXT_PUBLIC_CLIO_TTS_ENDPOINT} at this endpoint instead.
 *
 * <p>Every call is a paid synthesis on an anonymous website, so the endpoint is off until
 * {@code ASTOR_TTS_WEB_ENABLED=true}, reads at most {@value #TEXT_LIMIT} characters, runs a bounded number
 * of syntheses at once (SaluteSpeech allows 5 parallel streams for individuals) and caps calls per minute.
 * A disabled or failing voice is 503 with the same JSON shape; the text reply is the source of truth.
 */
@Slf4j
@RestController
public class ChatSpeechController {

    static final int TEXT_LIMIT = 600;
    static final String VOICE_LABEL_PREFIX = "clio-";

    public record SpeakRequest(String text, String voice) { }

    public record SpeakResponse(String provider, String status, String voice, String audioUrl, String audioBase64,
                                String audioMimeType, String message, String createdAt) { }

    private final TextToSpeech speech;
    private final boolean enabled;
    private final int ratePerMinute;
    private final Semaphore slots;
    private final AtomicLong windowStart = new AtomicLong(System.currentTimeMillis());
    private final AtomicInteger windowCalls = new AtomicInteger();

    public ChatSpeechController(TextToSpeech speech,
                                @Value("${astor.tts.web-enabled:false}") boolean enabled,
                                @Value("${astor.tts.web-concurrency:2}") int concurrency,
                                @Value("${astor.tts.web-rate-per-minute:60}") int ratePerMinute) {
        this.speech = speech;
        this.enabled = enabled;
        this.slots = new Semaphore(Math.max(1, concurrency));
        this.ratePerMinute = Math.max(1, ratePerMinute);
    }

    @PostMapping(value = "/api/chat/speak", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<SpeakResponse> speak(@RequestBody(required = false) SpeakRequest request) {
        String text = request == null || request.text() == null ? "" : request.text().strip();
        if (text.isEmpty()) {
            return reply(HttpStatus.BAD_REQUEST, "FAILED", null, "text is required");
        }
        if (text.codePointCount(0, text.length()) > TEXT_LIMIT) {
            return reply(HttpStatus.PAYLOAD_TOO_LARGE, "FAILED", null, "text exceeds " + TEXT_LIMIT + " characters");
        }
        if (!enabled || !speech.configured()) {
            return reply(HttpStatus.SERVICE_UNAVAILABLE, "UNAVAILABLE", null,
                    "Server voice is off. Set ASTOR_TTS_WEB_ENABLED=true and the provider credentials on the server.");
        }
        if (!withinRate()) {
            return reply(HttpStatus.TOO_MANY_REQUESTS, "UNAVAILABLE", null, "Voice is rate limited; the text reply stands.");
        }
        if (!slots.tryAcquire()) {
            return reply(HttpStatus.TOO_MANY_REQUESTS, "UNAVAILABLE", null, "Voice is busy; the text reply stands.");
        }
        try {
            byte[] audio = speech.synthesize(text);
            return reply(HttpStatus.OK, "READY", audio, null);
        } catch (TextToSpeechException e) {
            // The provider's status is enough for ops; the key, token and the line itself never reach the log.
            log.warn("Web chat TTS failed provider={} status={}: {}", speech.provider(), e.status(), e.getMessage());
            return reply(HttpStatus.SERVICE_UNAVAILABLE, "UNAVAILABLE", null, "Voice is unavailable right now.");
        } finally {
            slots.release();
        }
    }

    private boolean withinRate() {
        long now = System.currentTimeMillis();
        long start = windowStart.get();
        if (now - start >= 60_000 && windowStart.compareAndSet(start, now)) {
            windowCalls.set(0);
        }
        return windowCalls.incrementAndGet() <= ratePerMinute;
    }

    private ResponseEntity<SpeakResponse> reply(HttpStatus status, String state, byte[] audio, String message) {
        String base64 = audio == null ? null : Base64.getEncoder().encodeToString(audio);
        String mime = audio == null ? null : speech.mimeType();
        String dataUrl = audio == null ? null : "data:" + mime + ";base64," + base64;
        var response = ResponseEntity.status(status).header("Cache-Control", "no-store");
        if (status == HttpStatus.TOO_MANY_REQUESTS) response.header("Retry-After", "10");
        return response.body(new SpeakResponse(speech.provider(), state, VOICE_LABEL_PREFIX + speech.voiceGender() + "-" + speech.voice(),
                dataUrl, base64, mime, message, Instant.now().toString()));
    }
}
