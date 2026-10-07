package museon_online.astor_butler.api.glasses;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Client-reported moments of a glasses session (step started, call, finished). Informational; nothing is acknowledged. */
@RestController
public class GlassesSessionEventsController {
    static final int BODY_LIMIT = 64 * 1024;
    static final int MAX_EVENTS = 50;
    static final int NOTE_LIMIT = 200;
    private static final Set<String> FIELDS = Set.of("requestId", "sessionId", "scenarioCode", "events");
    private static final Set<String> EVENT_FIELDS = Set.of("at", "type", "stageCode", "note");
    private final GlassesAccess access;
    private final GlassesAssistService service;
    private final GlassesSessionJournal journal;
    private final ObjectMapper mapper;

    public GlassesSessionEventsController(GlassesAccess access, GlassesAssistService service,
                                          GlassesSessionJournal journal, ObjectMapper mapper) {
        this.access = access;
        this.service = service;
        this.journal = journal;
        this.mapper = mapper.copy().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    }

    public record Accepted(String requestId, String sessionId, int accepted) { }

    @PostMapping("/api/glasses/session-events")
    public ResponseEntity<?> events(HttpServletRequest request) {
        String requestId = null;
        try {
            var scope = access.check(request.getHeader("Authorization"));
            service.checkRate();
            if (request.getContentLengthLong() > BODY_LIMIT) throw tooLarge();
            if (request.getContentType() == null
                    || !request.getContentType().split(";", 2)[0].trim().equalsIgnoreCase("application/json")) {
                throw malformed();
            }
            byte[] bytes = request.getInputStream().readNBytes(BODY_LIMIT + 1);
            if (bytes.length > BODY_LIMIT) throw tooLarge();
            JsonNode body = mapper.readTree(bytes);
            if (body == null || !body.isObject()) throw malformed();
            var names = body.fieldNames();
            while (names.hasNext()) if (!FIELDS.contains(names.next())) throw malformed();
            requestId = uuid(text(body, "requestId"));
            String sessionId = uuid(text(body, "sessionId"));
            String scenarioCode = text(body, "scenarioCode");
            if (scenarioCode == null || !GlassesPhotoContext.SCENARIOS.containsKey(scenarioCode)) throw malformed();
            JsonNode events = body.get("events");
            if (events == null || !events.isArray() || events.isEmpty() || events.size() > MAX_EVENTS) throw malformed();
            var stages = GlassesPhotoContext.stageOrder(scenarioCode);
            Instant now = Instant.now();
            var parsed = new ArrayList<GlassesSessionJournal.ClientEvent>();
            for (JsonNode event : events) {
                if (!event.isObject()) throw malformed();
                var eventNames = event.fieldNames();
                while (eventNames.hasNext()) if (!EVENT_FIELDS.contains(eventNames.next())) throw malformed();
                String at = text(event, "at");
                String type = text(event, "type");
                String stage = text(event, "stageCode");
                String note = text(event, "note");
                if (at == null || type == null || !GlassesSessionJournal.CLIENT_EVENTS.contains(type)) throw malformed();
                Instant instant = Instant.parse(at);
                if (Duration.between(instant, now).abs().compareTo(Duration.ofHours(24)) > 0) throw malformed();
                if (stage != null && !stages.contains(stage)) throw malformed();
                if (note != null && note.codePointCount(0, note.length()) > NOTE_LIMIT) throw tooLarge();
                parsed.add(new GlassesSessionJournal.ClientEvent(instant.toString(), type, stage, note));
            }
            int accepted = journal.recordEvents(scope, requestId, sessionId, scenarioCode, parsed);
            return ResponseEntity.ok().header("Cache-Control", "no-store").body(new Accepted(requestId, sessionId, accepted));
        } catch (GlassesFailure failure) {
            var response = ResponseEntity.status(failure.status).header("Cache-Control", "no-store");
            if (failure.status == 401) response.header("WWW-Authenticate", "Bearer");
            if (failure.status == 429) response.header("Retry-After", "60");
            return response.body(new GlassesController.ErrorResponse(requestId,
                    Map.of("code", failure.code, "message", failure.getMessage())));
        } catch (IOException | RuntimeException e) {
            return ResponseEntity.badRequest().header("Cache-Control", "no-store")
                    .body(new GlassesController.ErrorResponse(requestId,
                            Map.of("code", "MALFORMED_REQUEST", "message", "Invalid session events payload")));
        }
    }

    private String text(JsonNode node, String name) {
        JsonNode value = node.get(name);
        if (value == null || value.isNull()) return null;
        if (!value.isTextual()) throw malformed();
        return value.textValue();
    }

    private String uuid(String value) {
        if (value == null || !UUID.fromString(value).toString().equalsIgnoreCase(value)) throw malformed();
        return UUID.fromString(value).toString();
    }

    private GlassesFailure malformed() {
        return new GlassesFailure(400, "MALFORMED_REQUEST", "Invalid session events payload");
    }

    private GlassesFailure tooLarge() {
        return new GlassesFailure(413, "PAYLOAD_TOO_LARGE", "Session events exceed limits");
    }
}
