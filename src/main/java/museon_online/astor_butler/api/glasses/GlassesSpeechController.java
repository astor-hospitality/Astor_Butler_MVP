package museon_online.astor_butler.api.glasses;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Astor's voice, and the words of a staff member who answers with their own.
 *
 * `/speech` reads a line in Astor's server voice, so a message or a step sounds the same on any
 * phone. `/transcribe` turns one bounded recording into text and nothing else: no model call, no
 * answer, no sending. What the staff member does with that text — send it to the restaurant or
 * discard it — is their decision on the phone, not this endpoint's.
 */
@RestController
public class GlassesSpeechController {
    static final int BODY_LIMIT = 3 * 1024 * 1024;
    static final int MEDIA_LIMIT = 2 * 1024 * 1024;
    private static final Set<String> SPEECH_FIELDS = Set.of("requestId", "text");
    private static final Set<String> TRANSCRIBE_FIELDS = Set.of("requestId", "audioBase64", "audioMimeType");
    private final GlassesAccess access;
    private final GlassesAssistService service;
    private final ObjectMapper mapper;

    public GlassesSpeechController(GlassesAccess access, GlassesAssistService service, ObjectMapper mapper) {
        this.access = access;
        this.service = service;
        this.mapper = mapper.copy().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    }

    public record Speech(String requestId, String audioBase64, String audioMimeType, String audioVoiceGender, String voice) { }
    public record Transcript(String requestId, String text) { }

    @PostMapping("/api/glasses/speech")
    public ResponseEntity<?> speech(HttpServletRequest request) {
        String requestId = null;
        try {
            access.check(request.getHeader("Authorization"));
            service.checkRate();
            JsonNode body = read(request, 16 * 1024, SPEECH_FIELDS);
            requestId = uuid(text(body, "requestId"));
            String line = text(body, "text");
            if (line == null || line.isBlank()) throw malformed();
            if (line.codePointCount(0, line.length()) > GlassesSpeech.TEXT_LIMIT) throw tooLarge();
            byte[] audio = service.speak(line);
            if (audio == null) throw new GlassesFailure(503, "SPEECH_UNAVAILABLE", "Server voice is off or unavailable");
            return ResponseEntity.ok().header("Cache-Control", "no-store").contentType(MediaType.APPLICATION_JSON)
                    .body(new Speech(requestId, Base64.getEncoder().encodeToString(audio), service.audioMimeType(),
                            service.voiceGender(), service.voiceName()));
        } catch (GlassesFailure failure) {
            return error(requestId, failure);
        } catch (Exception e) {
            return error(requestId, malformed());
        }
    }

    @PostMapping("/api/glasses/transcribe")
    public ResponseEntity<?> transcribe(HttpServletRequest request) {
        String requestId = null;
        try {
            var scope = access.check(request.getHeader("Authorization"));
            service.checkRate();
            JsonNode body = read(request, BODY_LIMIT, TRANSCRIBE_FIELDS);
            requestId = uuid(text(body, "requestId"));
            if (!"audio/mp4".equals(text(body, "audioMimeType"))) throw malformed();
            String encoded = text(body, "audioBase64");
            if (encoded == null) throw malformed();
            if (encoded.length() > 4 * ((MEDIA_LIMIT + 2) / 3)) throw tooLarge();
            byte[] media = Base64.getDecoder().decode(encoded);
            if (media.length == 0) throw malformed();
            if (media.length > MEDIA_LIMIT) throw tooLarge();
            if (media.length < 16 || !"ftyp".equals(new String(media, 4, 4, StandardCharsets.US_ASCII))) throw malformed();
            long boxSize = Integer.toUnsignedLong(ByteBuffer.wrap(media).getInt());
            if (boxSize < 16 || boxSize > media.length) throw malformed();
            String transcript = service.transcribe(scope, requestId, media);
            return ResponseEntity.ok().header("Cache-Control", "no-store").contentType(MediaType.APPLICATION_JSON)
                    .body(new Transcript(requestId, transcript));
        } catch (GlassesFailure failure) {
            return error(requestId, failure);
        } catch (Exception e) {
            return error(requestId, malformed());
        }
    }

    private JsonNode read(HttpServletRequest request, int limit, Set<String> fields) throws Exception {
        if (request.getContentLengthLong() > limit) throw tooLarge();
        if (request.getContentType() == null
                || !request.getContentType().split(";", 2)[0].trim().equalsIgnoreCase("application/json")) throw malformed();
        byte[] bytes = request.getInputStream().readNBytes(limit + 1);
        if (bytes.length > limit) throw tooLarge();
        JsonNode body = mapper.readTree(bytes);
        if (body == null || !body.isObject()) throw malformed();
        var names = body.fieldNames();
        while (names.hasNext()) if (!fields.contains(names.next())) throw malformed();
        return body;
    }

    private String text(JsonNode body, String name) {
        JsonNode value = body.get(name);
        if (value == null || value.isNull()) return null;
        if (!value.isTextual()) throw malformed();
        return value.textValue();
    }

    private String uuid(String value) {
        if (value == null || !UUID.fromString(value).toString().equalsIgnoreCase(value)) throw malformed();
        return UUID.fromString(value).toString();
    }

    private ResponseEntity<?> error(String requestId, GlassesFailure failure) {
        var response = ResponseEntity.status(failure.status).header("Cache-Control", "no-store")
                .contentType(MediaType.APPLICATION_JSON);
        if (failure.status == 401) response.header("WWW-Authenticate", "Bearer");
        if (failure.status == 429) response.header("Retry-After", "60");
        return response.body(new GlassesController.ErrorResponse(requestId,
                Map.of("code", failure.code, "message", failure.getMessage())));
    }

    private GlassesFailure malformed() {
        return new GlassesFailure(400, "MALFORMED_REQUEST", "Invalid payload");
    }

    private GlassesFailure tooLarge() {
        return new GlassesFailure(413, "PAYLOAD_TOO_LARGE", "Payload exceeds limits");
    }
}
