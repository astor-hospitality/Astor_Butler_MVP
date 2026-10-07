package museon_online.astor_butler.api.glasses;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.time.Instant;
import java.util.*;

/**
 * Messages the restaurant addresses to the staff member wearing the glasses.
 *
 * The restaurant queues one with the server password; the phone picks it up with its bearer and
 * decides when to read it aloud — it is read only in a pause, and only while the phone is not in the
 * staff member's hands. Delivery is informational: nothing here acknowledges a message or reports
 * back that it was heard, so an unanswered message stays unanswered for whoever sent it.
 *
 * A pilot outbox: in memory, bounded, two hours. A restart loses what was not picked up, which is
 * the honest behaviour for a channel with no durable queue behind it yet.
 */
@RestController
public class GlassesMessagesController {
    static final int BODY_LIMIT = 8 * 1024;
    static final int TEXT_LIMIT = 600;
    static final int PENDING_LIMIT = 20;
    static final Duration TTL = Duration.ofHours(2);
    private final GlassesAccess access;
    private final GlassesAssistService service;
    private final GlassesReportAuth reportAuth;
    private final ObjectMapper mapper;
    private final Map<String, Deque<Message>> outbox = new HashMap<>();

    public GlassesMessagesController(GlassesAccess access, GlassesAssistService service,
                                     GlassesReportAuth reportAuth, ObjectMapper mapper) {
        this.access = access;
        this.service = service;
        this.reportAuth = reportAuth;
        this.mapper = mapper.copy().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    }

    public record Message(String id, String text, String createdAt) { }
    public record Pending(List<Message> messages) { }
    public record Queued(String id, int pending) { }

    /** The phone, with its bearer: what is waiting for this staff member. Picking up does not remove it. */
    @GetMapping("/api/glasses/messages")
    public ResponseEntity<?> messages(HttpServletRequest request) {
        try {
            var scope = access.check(request.getHeader("Authorization"));
            service.checkRate();
            return ResponseEntity.ok().header("Cache-Control", "no-store")
                    .contentType(MediaType.APPLICATION_JSON).body(new Pending(pending(scope)));
        } catch (GlassesFailure failure) {
            return error(failure, false);
        }
    }

    /** The restaurant, with the server password: queue one message for the staff member. */
    @PostMapping("/api/glasses/messages")
    public ResponseEntity<?> queue(HttpServletRequest request) {
        try {
            var scope = reportAuth.authorize(request);
            if (request.getContentLengthLong() > BODY_LIMIT) throw tooLarge();
            byte[] bytes = request.getInputStream().readNBytes(BODY_LIMIT + 1);
            if (bytes.length > BODY_LIMIT) throw tooLarge();
            JsonNode body = mapper.readTree(bytes);
            if (body == null || !body.isObject() || body.size() != 1 || !body.has("text")
                    || !body.get("text").isTextual()) throw malformed();
            String text = body.get("text").textValue().strip();
            if (text.isEmpty()) throw malformed();
            if (text.codePointCount(0, text.length()) > TEXT_LIMIT) throw tooLarge();
            Message message = new Message(UUID.randomUUID().toString(), text, Instant.now().toString());
            int waiting = add(scope, message);
            return ResponseEntity.ok().header("Cache-Control", "no-store").body(new Queued(message.id, waiting));
        } catch (GlassesFailure failure) {
            return error(failure, true);
        } catch (Exception e) {
            return error(malformed(), true);
        }
    }

    synchronized List<Message> pending(GlassesAccess.Scope scope) {
        var queue = outbox.get(GlassesS3Storage.scopeKey(scope));
        if (queue == null) return List.of();
        expire(queue);
        return List.copyOf(queue);
    }

    private synchronized int add(GlassesAccess.Scope scope, Message message) {
        var queue = outbox.computeIfAbsent(GlassesS3Storage.scopeKey(scope), k -> new ArrayDeque<>());
        expire(queue);
        if (queue.size() >= PENDING_LIMIT) {
            throw new GlassesFailure(429, "OUTBOX_FULL", "The staff member has too many unread messages");
        }
        queue.addLast(message);
        return queue.size();
    }

    private static void expire(Deque<Message> queue) {
        Instant limit = Instant.now().minus(TTL);
        queue.removeIf(message -> Instant.parse(message.createdAt()).isBefore(limit));
    }

    private ResponseEntity<?> error(GlassesFailure failure, boolean basic) {
        var response = ResponseEntity.status(failure.status).header("Cache-Control", "no-store")
                .contentType(MediaType.APPLICATION_JSON);
        if (failure.status == 401) response.header("WWW-Authenticate", basic ? GlassesReportAuth.challenge() : "Bearer");
        if (failure.status == 429) response.header("Retry-After", "60");
        return response.body(new GlassesController.ErrorResponse(null,
                Map.of("code", failure.code, "message", failure.getMessage())));
    }

    private GlassesFailure malformed() {
        return new GlassesFailure(400, "MALFORMED_REQUEST", "Invalid message payload");
    }

    private GlassesFailure tooLarge() {
        return new GlassesFailure(413, "PAYLOAD_TOO_LARGE", "Message exceeds limits");
    }
}
