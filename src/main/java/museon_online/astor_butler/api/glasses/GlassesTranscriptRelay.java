package museon_online.astor_butler.api.glasses;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Sends what was said through the glasses to the rest of Butler, so a staff member's question, Astor's
 * answer and the photo they took appear in the system chat like any other message, instead of living
 * only inside this isolated runtime.
 *
 * What travels: the staff member's own bounded question (typed, or the transcript of what they recorded
 * by pressing a button), Astor's answer, and the photo of that request. Not the audio, not the standby
 * microphone — it never records on its own — and nothing about guests beyond what is in the frame the
 * staff member chose to take.
 *
 * Off by default. A failure here is logged and never raised: a relay that cannot reach Butler must not
 * turn a working answer into an error for the person wearing the glasses.
 */
@Component
public class GlassesTranscriptRelay {
    private static final Logger log = LoggerFactory.getLogger(GlassesTranscriptRelay.class);
    static final int PHOTO_LIMIT = 2 * 1024 * 1024;
    static final int TEXT_LIMIT = 4000;
    private final boolean enabled;
    private final URI upstream;
    private final String token;
    private final boolean photosEnabled;
    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient client;
    // Two at a time: the relay is a courtesy, never a queue that can grow while the shift goes on.
    private final Semaphore slots = new Semaphore(2);
    /* Sending happens off the assist path. The single provider slot must be free the moment the answer is
       ready: the person wearing the glasses waits for Astor, never for Butler's chat. A full queue drops
       the oldest pending relay rather than delaying anyone. */
    private final ThreadPoolExecutor sender = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(8), runnable -> {
                Thread thread = new Thread(runnable, "glasses-transcript-relay");
                thread.setDaemon(true);
                return thread;
            }, new ThreadPoolExecutor.DiscardOldestPolicy());

    @Autowired
    public GlassesTranscriptRelay(
            @Value("${astor.glasses.transcript-relay-enabled:false}") boolean enabled,
            @Value("${astor.glasses.transcript-relay-url:http://aeris-astor-butler-bot:8089/api/internal/glasses/transcript}") String upstream,
            @Value("${astor.glasses.relay-token:}") String token,
            @Value("${astor.glasses.transcript-relay-photos:true}") boolean photosEnabled) {
        this.enabled = enabled;
        this.upstream = URI.create(upstream);
        this.token = token;
        this.photosEnabled = photosEnabled;
        this.client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3))
                .followRedirects(HttpClient.Redirect.NEVER).build();
    }

    GlassesTranscriptRelay(HttpClient client, boolean enabled, String token, boolean photosEnabled) {
        this.enabled = enabled;
        this.upstream = URI.create("http://butler.invalid/api/internal/glasses/transcript");
        this.token = token;
        this.photosEnabled = photosEnabled;
        this.client = client;
    }

    public static GlassesTranscriptRelay disabled() {
        return new GlassesTranscriptRelay(HttpClient.newHttpClient(), false, "", false);
    }

    boolean configured() { return enabled && token.length() >= 16; }

    /** Queues one exchange and returns at once; the answer never waits for Butler. */
    void sendLater(GlassesAccess.Scope scope, String requestId, String kind, String question, String answer,
                   GlassesPhotoContext context, byte[] photo) {
        if (!configured() || answer == null || answer.isBlank()) return;
        byte[] copy = photo == null ? null : photo.clone();
        try {
            sender.execute(() -> send(scope, requestId, kind, question, answer, context, copy));
        } catch (RuntimeException e) {
            log.debug("Glasses transcript relay not queued: {}", e.getClass().getSimpleName());
        }
    }

    /** Hands one exchange to Butler. Returns true when Butler took it; never throws. */
    boolean send(GlassesAccess.Scope scope, String requestId, String kind, String question, String answer,
                 GlassesPhotoContext context, byte[] photo) {
        if (!configured()) return false;
        if (answer == null || answer.isBlank()) return false;
        if (!slots.tryAcquire()) {
            log.debug("Glasses transcript relay skipped: busy");
            return false;
        }
        try {
            var body = new LinkedHashMap<String, Object>();
            body.put("requestId", requestId);
            body.put("kind", kind);
            body.put("staff", scope.staff());
            body.put("venue", scope.tenant());
            body.put("question", cut(question));
            body.put("answer", cut(answer));
            body.put("at", Instant.now().toString());
            if (context != null) {
                body.put("sessionId", context.sessionId());
                body.put("scenarioCode", context.scenarioCode());
                body.put("stageCode", context.stageCode());
            }
            if (photosEnabled && "image".equals(kind) && photo != null && photo.length > 0 && photo.length <= PHOTO_LIMIT) {
                body.put("photoBase64", Base64.getEncoder().encodeToString(photo));
                body.put("photoMimeType", "image/jpeg");
            }
            var request = HttpRequest.newBuilder(upstream).timeout(Duration.ofSeconds(10))
                    .header("Content-Type", "application/json")
                    .header("X-Astor-Relay-Token", token)
                    .POST(HttpRequest.BodyPublishers.ofByteArray(mapper.writeValueAsBytes(body))).build();
            var response = client.send(request, HttpResponse.BodyHandlers.discarding());
            if (response.statusCode() != 200 && response.statusCode() != 202) {
                log.warn("Glasses transcript relay refused: HTTP {}", response.statusCode());
                return false;
            }
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } catch (Exception e) {
            // No URL, token or payload in the log: this runs beside guest-facing media.
            log.warn("Glasses transcript relay failed: {}", e.getClass().getSimpleName());
            return false;
        } finally {
            slots.release();
        }
    }

    @jakarta.annotation.PreDestroy
    void close() { sender.shutdownNow(); }

    private static String cut(String text) {
        if (text == null) return "";
        String trimmed = text.strip();
        return trimmed.length() <= TEXT_LIMIT ? trimmed : trimmed.substring(0, TEXT_LIMIT);
    }
}
