package museon_online.astor_butler.api.glasses;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.*;

import static museon_online.astor_butler.api.glasses.GlassesMessagePriority.ALERT;
import static museon_online.astor_butler.api.glasses.GlassesMessagePriority.NORMAL;

/**
 * Messages the restaurant addresses to the staff member wearing the glasses.
 *
 * The restaurant queues one with the server password; the phone picks it up with its bearer and
 * decides when to read it aloud — it is read only in a pause, and only while the phone is not in the
 * staff member's hands. Delivery is informational: nothing here acknowledges a message or reports
 * back that it was heard, so an unanswered message stays unanswered for whoever sent it.
 *
 * With {@code ASTOR_GLASSES_MESSAGE_PRIORITY_ENABLED} (issue #186) every entry also says how it should
 * be delivered: an {@code ALERT} is spoken at once ({@code quiet: false}), an ordinary message waits for
 * a pause ({@code quiet: true}), and what exceeds the rate per minute stays on the screen
 * ({@code speak: false}) behind one spoken line «Ещё N сообщений — на экране». A repeated text within
 * two minutes is answered with the id it already has. The rules live in {@link GlassesMessagePriority};
 * with the flag off the queue and its JSON are exactly what they were.
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
    private static final Set<String> FIELDS = Set.of("text", "priority", "source");
    private final GlassesAccess access;
    private final GlassesAssistService service;
    private final GlassesReportAuth reportAuth;
    private final ObjectMapper mapper;
    private final GlassesMessagePriority priority;
    private final Clock clock;
    private final Map<String, List<Message>> outbox = new HashMap<>();

    /** The queue as before #186: no priority, no rate, no repeat check. */
    public GlassesMessagesController(GlassesAccess access, GlassesAssistService service,
                                     GlassesReportAuth reportAuth, ObjectMapper mapper) {
        this(access, service, reportAuth, mapper, GlassesMessagePriority.disabled(), Clock.systemUTC());
    }

    @Autowired
    public GlassesMessagesController(GlassesAccess access, GlassesAssistService service, GlassesReportAuth reportAuth,
                                     ObjectMapper mapper, GlassesMessagePriority priority) {
        this(access, service, reportAuth, mapper, priority, Clock.systemUTC());
    }

    GlassesMessagesController(GlassesAccess access, GlassesAssistService service, GlassesReportAuth reportAuth,
                              ObjectMapper mapper, GlassesMessagePriority priority, Clock clock) {
        this.access = access;
        this.service = service;
        this.reportAuth = reportAuth;
        this.mapper = mapper.copy().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
        this.priority = priority;
        this.clock = clock;
    }

    /**
     * One entry of the outbox. {@code priority}, {@code quiet}, {@code speak}, {@code source} and {@code coalesced}
     * exist only with message priority on; without it the entry is {@code {id, text, createdAt}} as before.
     * {@code coalesced} marks the one line that stands for the messages held by the rate and counts them.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Message(String id, String text, String createdAt, String priority, Boolean quiet, Boolean speak,
                          String source, Integer coalesced) {
        public Message(String id, String text, String createdAt) {
            this(id, text, createdAt, null, null, null, null, null);
        }
        boolean alert() { return ALERT.equals(priority); }
        boolean summary() { return coalesced != null; }
        /** An ordinary message the phone reads aloud (in a pause): what the rate per minute counts. */
        boolean spokenNormal() { return NORMAL.equals(priority) && Boolean.TRUE.equals(speak) && !summary(); }
        Instant created() { return Instant.parse(createdAt); }
    }
    public record Pending(List<Message> messages) { }
    /** The receipt. {@code duplicate} says the text was already waiting and {@code id} is that message's. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Queued(String id, int pending, String priority, Boolean quiet, Boolean speak, Boolean duplicate) {
        public Queued(String id, int pending) { this(id, pending, null, null, null, null); }
    }

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
            if (body == null || !body.isObject() || !body.has("text") || !body.get("text").isTextual()) throw malformed();
            if (priority.enabled() ? !FIELDS.containsAll(names(body)) : body.size() != 1) throw malformed();
            String text = body.get("text").textValue().strip();
            if (text.isEmpty()) throw malformed();
            if (text.codePointCount(0, text.length()) > TEXT_LIMIT) throw tooLarge();
            Queued receipt = priority.enabled()
                    ? admit(scope, text, optional(body, "source"), optional(body, "priority"))
                    : plain(scope, text);
            return ResponseEntity.ok().header("Cache-Control", "no-store").body(receipt);
        } catch (GlassesFailure failure) {
            return error(failure, true);
        } catch (Exception e) {
            return error(malformed(), true);
        }
    }

    synchronized List<Message> pending(GlassesAccess.Scope scope) {
        var queue = outbox.get(GlassesS3Storage.scopeKey(scope));
        if (queue == null) return List.of();
        expire(queue, Instant.now(clock));
        return List.copyOf(queue);
    }

    private synchronized Queued plain(GlassesAccess.Scope scope, String text) {
        Instant now = Instant.now(clock);
        var queue = queue(scope, now);
        if (queue.size() >= PENDING_LIMIT) throw full();
        Message message = new Message(UUID.randomUUID().toString(), text, now.toString());
        queue.add(message);
        return new Queued(message.id(), queue.size());
    }

    /**
     * Message priority on: a repeat answers with the id it already has; an alert goes in as it is and, when the
     * outbox is full, the oldest ordinary entry gives way to it; an ordinary message is spoken while the rate per
     * minute allows and otherwise stays on the screen, counted in this minute's «Ещё N сообщений — на экране».
     */
    private synchronized Queued admit(GlassesAccess.Scope scope, String text, String source, String requested) {
        String origin = GlassesMessagePriority.source(source);
        String asked = GlassesMessagePriority.requested(requested);
        if (origin == null || (requested != null && asked == null)) throw malformed();
        Instant now = Instant.now(clock);
        var queue = queue(scope, now);
        Message earlier = repeat(queue, text, now);
        if (earlier != null) {
            return new Queued(earlier.id(), queue.size(), earlier.priority(), earlier.quiet(), earlier.speak(), true);
        }
        boolean alert = ALERT.equals(priority.classify(text, origin, asked));
        if (queue.size() >= PENDING_LIMIT && !(alert && evictOldestOrdinary(queue))) throw full();
        Message message;
        if (alert) {
            message = new Message(UUID.randomUUID().toString(), text, now.toString(), ALERT, false, true, origin, null);
            queue.add(message);
        } else {
            boolean spoken = spokenThisMinute(queue, now) < priority.perMinute();
            message = new Message(UUID.randomUUID().toString(), text, now.toString(), NORMAL, true, spoken, origin, null);
            queue.add(message);
            if (!spoken) coalesce(queue, now);
        }
        return new Queued(message.id(), queue.size(), message.priority(), message.quiet(), message.speak(), null);
    }

    private List<Message> queue(GlassesAccess.Scope scope, Instant now) {
        var queue = outbox.computeIfAbsent(GlassesS3Storage.scopeKey(scope), k -> new ArrayList<>());
        expire(queue, now);
        return queue;
    }

    /** An alert does not wait behind a full outbox: the oldest entry that is not an alert gives way to it. */
    private static boolean evictOldestOrdinary(List<Message> queue) {
        for (int i = 0; i < queue.size(); i++) {
            if (!queue.get(i).alert()) {
                queue.remove(i);
                return true;
            }
        }
        return false;
    }

    /** The message already waiting with this text, if it is younger than the repeat window. */
    private static Message repeat(List<Message> queue, String text, Instant now) {
        String key = GlassesMessagePriority.duplicateKey(text);
        Instant limit = now.minus(GlassesMessagePriority.DUPLICATE_WINDOW);
        for (Message entry : queue) {
            if (entry.summary() || entry.created().isBefore(limit)) continue;
            if (GlassesMessagePriority.duplicateKey(entry.text()).equals(key)) return entry;
        }
        return null;
    }

    private static int spokenThisMinute(List<Message> queue, Instant now) {
        Instant limit = now.minus(GlassesMessagePriority.RATE_WINDOW);
        int count = 0;
        for (Message entry : queue) {
            if (entry.spokenNormal() && !entry.created().isBefore(limit)) count++;
        }
        return count;
    }

    /** This minute's summary line grows by one; when there is none yet, one is added after the held message. */
    private static void coalesce(List<Message> queue, Instant now) {
        Instant limit = now.minus(GlassesMessagePriority.RATE_WINDOW);
        for (int i = queue.size() - 1; i >= 0; i--) {
            Message entry = queue.get(i);
            if (!entry.summary() || entry.created().isBefore(limit)) continue;
            int count = entry.coalesced() + 1;
            queue.set(i, new Message(entry.id(), GlassesMessagePriority.summary(count), entry.createdAt(),
                    NORMAL, true, true, null, count));
            return;
        }
        queue.add(new Message(UUID.randomUUID().toString(), GlassesMessagePriority.summary(1), now.toString(),
                NORMAL, true, true, null, 1));
    }

    private static void expire(List<Message> queue, Instant now) {
        Instant limit = now.minus(TTL);
        queue.removeIf(message -> message.created().isBefore(limit));
    }

    private static List<String> names(JsonNode body) {
        List<String> names = new ArrayList<>();
        body.fieldNames().forEachRemaining(names::add);
        return names;
    }

    /** A text field that may be absent (or null); anything else in its place is a malformed request. */
    private String optional(JsonNode body, String name) {
        JsonNode value = body.get(name);
        if (value == null || value.isNull()) return null;
        if (!value.isTextual()) throw malformed();
        return value.textValue();
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

    private static GlassesFailure full() {
        return new GlassesFailure(429, "OUTBOX_FULL", "The staff member has too many unread messages");
    }
}
