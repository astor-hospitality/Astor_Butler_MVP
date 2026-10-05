package museon_online.astor_butler.api.glasses;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Semaphore;

/** Original media archive only; no AI calls, identity supplied by the client, or task mutations. */
@RestController
public class GlassesMediaController {
    static final int LIMIT = 64 * 1024 * 1024;
    private static final Set<String> TYPES = Set.of("image/jpeg", "audio/mp4", "video/mp4");
    private final GlassesAccess access;
    private final GlassesS3Storage storage;
    private final Semaphore slot = new Semaphore(1);
    private long rateWindow;
    private int requests;

    public GlassesMediaController(GlassesAccess access, GlassesS3Storage storage) {
        this.access = access;
        this.storage = storage;
    }

    @PostMapping("/api/glasses/media")
    public ResponseEntity<?> archive(HttpServletRequest request) {
        boolean acquired = false;
        try {
            var scope = access.check(request.getHeader("Authorization"));
            if (!storage.archiveCapabilities().enabled()) {
                throw new GlassesFailure(503, "ARCHIVE_UNAVAILABLE", "Private archive is not enabled");
            }
            checkRate();
            long length = request.getContentLengthLong();
            if (length < 0) throw new GlassesFailure(411, "LENGTH_REQUIRED", "Content-Length required");
            if (length > LIMIT) throw new GlassesFailure(413, "PAYLOAD_TOO_LARGE", "Archive file exceeds limits");
            if (length == 0 || request.getContentType() == null || !TYPES.contains(request.getContentType())) throw malformed();
            String fileId = uuid(request.getHeader("X-Glasses-File-Id"));
            String sessionId = uuid(request.getHeader("X-Glasses-Session-Id"));
            String sha256 = request.getHeader("X-Content-SHA256");
            if (sha256 == null || !sha256.matches("[a-f0-9]{64}")) throw malformed();
            if (!(acquired = slot.tryAcquire())) {
                throw new GlassesFailure(429, "BUSY", "Archive upload already in progress");
            }
            // The only potentially large allocation occurs after authorization, length and slot checks.
            byte[] media = request.getInputStream().readNBytes((int) length + 1);
            if (media.length != length) throw malformed();
            if (!sha256.equals(sha256(media))) {
                throw new GlassesFailure(400, "HASH_MISMATCH", "File SHA-256 does not match");
            }
            validateSignature(media, request.getContentType());
            return ResponseEntity.ok().header("Cache-Control", "no-store")
                    .body(storage.archiveFile(scope, fileId, sessionId, sha256, request.getContentType(), media));
        } catch (GlassesFailure failure) {
            var response = ResponseEntity.status(failure.status).header("Cache-Control", "no-store");
            if (failure.status == 401) response.header("WWW-Authenticate", "Bearer");
            if (failure.status == 429) response.header("Retry-After", "60");
            return response.body(Map.of("error", Map.of("code", failure.code, "message", failure.getMessage())));
        } catch (IOException | IllegalArgumentException e) {
            return ResponseEntity.badRequest().header("Cache-Control", "no-store")
                    .body(Map.of("error", Map.of("code", "MALFORMED_REQUEST", "message", "Invalid archive payload")));
        } finally {
            if (acquired) slot.release();
        }
    }

    private synchronized void checkRate() {
        long window = System.currentTimeMillis() / 60000;
        if (window != rateWindow) { rateWindow = window; requests = 0; }
        if (++requests > 10) throw new GlassesFailure(429, "RATE_LIMITED", "Archive request limit reached");
    }

    private String uuid(String value) {
        if (value == null || !UUID.fromString(value).toString().equals(value)) throw malformed();
        return value;
    }

    private void validateSignature(byte[] media, String mime) {
        if (mime.equals("image/jpeg")) {
            if (media.length < 4 || (media[0] & 255) != 255 || (media[1] & 255) != 216
                    || (media[media.length - 2] & 255) != 255 || (media[media.length - 1] & 255) != 217) throw malformed();
        } else {
            if (media.length < 16 || !"ftyp".equals(new String(media, 4, 4, StandardCharsets.US_ASCII))) throw malformed();
            long boxSize = Integer.toUnsignedLong(ByteBuffer.wrap(media).getInt());
            if (boxSize < 16 || boxSize > media.length) throw malformed();
        }
    }

    static String sha256(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }

    private GlassesFailure malformed() {
        return new GlassesFailure(400, "MALFORMED_REQUEST", "Invalid archive payload");
    }
}
