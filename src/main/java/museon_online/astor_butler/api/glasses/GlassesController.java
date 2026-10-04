package museon_online.astor_butler.api.glasses;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import javax.imageio.ImageIO;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@RestController
@RequestMapping("/api/glasses")
public class GlassesController {
    private static final int BODY_LIMIT = 5 * 1024 * 1024;
    private static final int MEDIA_LIMIT = 2 * 1024 * 1024;
    private static final Set<String> FIELDS = Set.of("requestId", "text", "audioBase64", "audioMimeType",
            "imageBase64", "imageMimeType");
    private final GlassesAccess access;
    private final GlassesAssistService service;
    private final ObjectMapper mapper;

    public GlassesController(GlassesAccess access, GlassesAssistService service, ObjectMapper mapper) {
        this.access = access;
        this.service = service;
        this.mapper = mapper.copy().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    }

    @GetMapping("/capabilities")
    public ResponseEntity<?> capabilities(HttpServletRequest request) {
        try {
            access.check(request.getHeader("Authorization"));
            return ResponseEntity.ok().header("Cache-Control", "no-store").body(service.capabilities());
        } catch (GlassesFailure e) {
            return error(null, e);
        }
    }

    // Read bounded bytes after authentication; @RequestBody would deserialize before access checking.
    @PostMapping("/assist")
    public ResponseEntity<?> assist(HttpServletRequest request) {
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
            String id = field(body, "requestId");
            if (id == null || !UUID.fromString(id).toString().equalsIgnoreCase(id)) throw malformed();
            requestId = id;
            var names = body.fieldNames();
            while (names.hasNext()) if (!FIELDS.contains(names.next())) throw malformed();
            String text = field(body, "text");
            if (text == null) text = "";
            if (text.codePointCount(0, text.length()) > 4000) throw tooLarge();
            String audio = field(body, "audioBase64");
            String image = field(body, "imageBase64");
            String audioMime = field(body, "audioMimeType");
            String imageMime = field(body, "imageMimeType");
            if ((audio != null && image != null) || (audio == null && audioMime != null)
                    || (image == null && imageMime != null)) throw malformed();
            if (audio != null) {
                if (!"audio/mp4".equals(audioMime)) throw malformed();
                byte[] media = decode(audio);
                // Cheap signature check precedes bounded decoder/codec/rate/duration validation in GlassesVoice.
                if (media.length < 16 || !"ftyp".equals(new String(media, 4, 4, StandardCharsets.US_ASCII))) {
                    throw malformed();
                }
                long boxSize = Integer.toUnsignedLong(ByteBuffer.wrap(media).getInt());
                if (boxSize < 16 || boxSize > media.length) throw malformed();
                String answer = service.assistAudio(scope, requestId, text, media);
                return success(requestId, answer);
            }
            if (image != null) {
                if (!"image/jpeg".equals(imageMime)) throw malformed();
                byte[] media = decode(image);
                validateJpeg(media);
                String answer = service.assistImage(scope, requestId, text, media);
                return success(requestId, answer);
            }
            if (text.isBlank()) throw malformed();
            String answer = service.assist(scope, requestId, text);
            return success(requestId, answer);
        } catch (GlassesFailure e) {
            return error(requestId, e);
        } catch (IOException | IllegalArgumentException e) {
            return error(requestId, malformed());
        }
    }

    private ResponseEntity<?> success(String requestId, String answer) {
        return ResponseEntity.ok().header("Cache-Control", "no-store")
                .body(new AssistResponse(requestId, answer, service.capabilities()));
    }

    public record AssistResponse(String requestId, String text, GlassesAssistService.Capabilities capabilities) { }
    public record ErrorResponse(String requestId, Map<String, String> error) { }

    private ResponseEntity<?> error(String requestId, GlassesFailure failure) {
        var response = ResponseEntity.status(failure.status).header("Cache-Control", "no-store");
        if (failure.status == 401) response.header("WWW-Authenticate", "Bearer");
        if (failure.status == 429) response.header("Retry-After", "60");
        return response.body(new ErrorResponse(requestId, Map.of("code", failure.code, "message", failure.getMessage())));
    }

    private String field(JsonNode body, String name) {
        JsonNode value = body.get(name);
        if (value == null || value.isNull()) return null;
        if (!value.isTextual()) throw malformed();
        return value.textValue();
    }

    private byte[] decode(String base64) {
        if (base64.length() > 4 * ((MEDIA_LIMIT + 2) / 3)) throw tooLarge();
        byte[] decoded = Base64.getDecoder().decode(base64);
        if (decoded.length > MEDIA_LIMIT) throw tooLarge();
        if (decoded.length == 0) throw malformed();
        return decoded;
    }

    private void validateJpeg(byte[] media) throws IOException {
        if (media.length < 4 || (media[0] & 255) != 255 || (media[1] & 255) != 216
                || (media[media.length - 2] & 255) != 255 || (media[media.length - 1] & 255) != 217) {
            throw malformed();
        }
        // Inspect dimensions without decoding a potentially huge raster or writing files to disk.
        try (var input = new javax.imageio.stream.MemoryCacheImageInputStream(new ByteArrayInputStream(media))) {
            var readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) throw malformed();
            var reader = readers.next();
            try {
                if (!"JPEG".equalsIgnoreCase(reader.getFormatName())) throw malformed();
                reader.setInput(input);
                int width = reader.getWidth(0);
                int height = reader.getHeight(0);
                if (width < 1 || height < 1) throw malformed();
                if (Math.max(width, height) > 1280) throw tooLarge();
            } finally {
                reader.dispose();
            }
        }
    }

    private GlassesFailure malformed() {
        return new GlassesFailure(400, "MALFORMED_REQUEST", "Invalid assist payload");
    }

    private GlassesFailure tooLarge() {
        return new GlassesFailure(413, "PAYLOAD_TOO_LARGE", "Assist payload exceeds limits");
    }
}
