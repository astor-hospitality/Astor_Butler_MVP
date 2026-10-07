package museon_online.astor_butler.api.glasses;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import museon_online.astor_butler.domain.glasses.GlassesTranscriptFeed;
import museon_online.astor_butler.telegram.adapter.TelegramSystemNotifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Where the glasses runtime hands an exchange to Butler: the staff member's question, Astor's answer and
 * the photo of that request go into the system chat and the shift feed.
 *
 * Server to server only, behind a shared token that lives in both services' environments. It is not on
 * the public route, the mobile bearer does not open it, and it creates no task, booking or FSM
 * transition — this endpoint only repeats what was already said.
 */
@RestController
public class GlassesTranscriptController {
    static final int BODY_LIMIT = 3 * 1024 * 1024;
    static final int PHOTO_LIMIT = 2 * 1024 * 1024;
    static final int TEXT_LIMIT = 4000;
    private static final Set<String> FIELDS = Set.of("requestId", "kind", "staff", "venue", "question", "answer",
            "at", "sessionId", "scenarioCode", "stageCode", "photoBase64", "photoMimeType");
    private static final Set<String> KINDS = Set.of("text", "audio", "image");
    private final GlassesTranscriptFeed feed;
    private final TelegramSystemNotifier notifier;
    private final String token;
    private final ObjectMapper mapper;

    public GlassesTranscriptController(GlassesTranscriptFeed feed, TelegramSystemNotifier notifier,
                                       @Value("${astor.glasses.relay-token:}") String token,
                                       ObjectMapper mapper) {
        this.feed = feed;
        this.notifier = notifier;
        this.token = token;
        this.mapper = mapper.copy().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    }

    public record Accepted(String requestId, boolean chat, boolean feed) { }

    @PostMapping("/api/internal/glasses/transcript")
    public ResponseEntity<?> transcript(HttpServletRequest request) {
        try {
            authorize(request);
            if (request.getContentLengthLong() > BODY_LIMIT) throw failure(413, "PAYLOAD_TOO_LARGE");
            if (request.getContentType() == null
                    || !request.getContentType().split(";", 2)[0].trim().equalsIgnoreCase("application/json")) {
                throw failure(400, "MALFORMED_REQUEST");
            }
            byte[] bytes = request.getInputStream().readNBytes(BODY_LIMIT + 1);
            if (bytes.length > BODY_LIMIT) throw failure(413, "PAYLOAD_TOO_LARGE");
            JsonNode body = mapper.readTree(bytes);
            if (body == null || !body.isObject()) throw failure(400, "MALFORMED_REQUEST");
            var names = body.fieldNames();
            while (names.hasNext()) if (!FIELDS.contains(names.next())) throw failure(400, "MALFORMED_REQUEST");

            String requestId = uuid(text(body, "requestId"));
            String kind = text(body, "kind");
            if (kind == null || !KINDS.contains(kind)) throw failure(400, "MALFORMED_REQUEST");
            String answer = cut(text(body, "answer"));
            if (answer.isBlank()) throw failure(400, "MALFORMED_REQUEST");
            String question = cut(text(body, "question"));
            String staff = cut(text(body, "staff"));
            String venue = cut(text(body, "venue"));
            String stageCode = text(body, "stageCode");
            String sessionId = text(body, "sessionId") == null ? null : uuid(text(body, "sessionId"));
            Instant at = at(text(body, "at"));
            byte[] photo = photo(body, kind);

            boolean chat = notifier.sendGlassesExchange(staff, stageCode, question, answer, photo,
                    "astor-glass-" + requestId + ".jpg");
            feed.add(new GlassesTranscriptFeed.Entry(requestId, at.toString(), kind, staff, venue, sessionId,
                    stageCode, question, answer, photo != null));
            return ResponseEntity.ok().header("Cache-Control", "no-store").contentType(MediaType.APPLICATION_JSON)
                    .body(new Accepted(requestId, chat, true));
        } catch (Failure failure) {
            return error(failure.status, failure.code);
        } catch (Exception e) {
            return error(400, "MALFORMED_REQUEST");
        }
    }

    private void authorize(HttpServletRequest request) {
        if (token == null || token.length() < 16) throw failure(503, "RELAY_UNAVAILABLE");
        String presented = request.getHeader("X-Astor-Relay-Token");
        if (presented == null || presented.length() > 512) throw failure(401, "UNAUTHORIZED");
        if (!MessageDigest.isEqual(token.getBytes(StandardCharsets.UTF_8), presented.getBytes(StandardCharsets.UTF_8))) {
            throw failure(401, "UNAUTHORIZED");
        }
    }

    private byte[] photo(JsonNode body, String kind) {
        String encoded = text(body, "photoBase64");
        if (encoded == null) return null;
        if (!"image/jpeg".equals(text(body, "photoMimeType")) || !"image".equals(kind)) throw failure(400, "MALFORMED_REQUEST");
        if (encoded.length() > 4 * ((PHOTO_LIMIT + 2) / 3)) throw failure(413, "PAYLOAD_TOO_LARGE");
        byte[] photo = Base64.getDecoder().decode(encoded);
        if (photo.length == 0 || photo.length > PHOTO_LIMIT) throw failure(413, "PAYLOAD_TOO_LARGE");
        if ((photo[0] & 255) != 255 || (photo[1] & 255) != 216) throw failure(400, "MALFORMED_REQUEST");
        return photo;
    }

    private Instant at(String value) {
        if (value == null) return Instant.now();
        Instant parsed = Instant.parse(value);
        // A clock far from ours is a sign of a confused sender, not of a late shift.
        if (Duration.between(parsed, Instant.now()).abs().compareTo(Duration.ofHours(24)) > 0) throw failure(400, "MALFORMED_REQUEST");
        return parsed;
    }

    private String text(JsonNode body, String name) {
        JsonNode value = body.get(name);
        if (value == null || value.isNull()) return null;
        if (!value.isTextual()) throw failure(400, "MALFORMED_REQUEST");
        return value.textValue();
    }

    private static String cut(String text) {
        if (text == null) return "";
        String trimmed = text.strip();
        return trimmed.length() <= TEXT_LIMIT ? trimmed : trimmed.substring(0, TEXT_LIMIT);
    }

    private String uuid(String value) {
        if (value == null || !UUID.fromString(value).toString().equalsIgnoreCase(value)) throw failure(400, "MALFORMED_REQUEST");
        return UUID.fromString(value).toString();
    }

    private ResponseEntity<?> error(int status, String code) {
        return ResponseEntity.status(status).header("Cache-Control", "no-store").contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("error", Map.of("code", code)));
    }

    private static Failure failure(int status, String code) { return new Failure(status, code); }

    private static final class Failure extends RuntimeException {
        private final int status;
        private final String code;
        private Failure(int status, String code) { super(code); this.status = status; this.code = code; }
    }
}
