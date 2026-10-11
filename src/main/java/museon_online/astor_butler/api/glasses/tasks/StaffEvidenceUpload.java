package museon_online.astor_butler.api.glasses.tasks;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.MediaType;

import java.io.IOException;
import java.time.Instant;
import java.util.Locale;

/**
 * One photo as it arrives over HTTP: the file is the request body, its Content-Type is the declared type,
 * and eventId, stageCode and an optional capturedAt are query parameters. Read only after the caller's
 * right to the task is known, and never further than the size limit.
 */
record StaffEvidenceUpload(String eventId, String stageCode, Instant capturedAt, String contentType, byte[] bytes) {

    static StaffEvidenceUpload read(HttpServletRequest request, StaffEvidence.Limits limits) throws IOException {
        // The type first: only then are the parameters asked for, so a form body is never parsed as one.
        String contentType = declaredType(request.getContentType());
        if (contentType == null || !limits.contentTypes().contains(contentType)) throw StaffEvidenceSettings.unsupported();
        String eventId = request.getParameter("eventId");
        String stageCode = request.getParameter("stageCode");
        if (blank(eventId) || eventId.length() > 64 || blank(stageCode) || stageCode.length() > 32) {
            throw malformed("eventId and stageCode are required");
        }
        Instant capturedAt = instant(request.getParameter("capturedAt"));
        long declared = request.getContentLengthLong();
        if (declared > limits.maxBytes()) throw StaffEvidenceSettings.tooLarge(limits.maxBytes());
        if (declared == 0) throw malformed("The photo is empty");
        // A body without a declared length, or longer than it says, is still read no further than the limit.
        byte[] bytes = request.getInputStream().readNBytes(limits.maxBytes() + 1);
        if (bytes.length > limits.maxBytes()) throw StaffEvidenceSettings.tooLarge(limits.maxBytes());
        if (bytes.length == 0) throw malformed("The photo is empty");
        if (!StaffEvidenceImages.matches(contentType, bytes)) throw StaffEvidenceSettings.unsupported();
        return new StaffEvidenceUpload(eventId, stageCode, capturedAt, contentType, bytes);
    }

    private static String declaredType(String header) {
        if (blank(header)) return null;
        try {
            MediaType type = MediaType.parseMediaType(header);
            return (type.getType() + "/" + type.getSubtype()).toLowerCase(Locale.ROOT);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static Instant instant(String value) {
        if (blank(value)) return null;
        try {
            return Instant.parse(value.strip());
        } catch (RuntimeException e) {
            throw malformed("capturedAt must be an ISO-8601 instant");
        }
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private static StaffTaskFailure malformed(String message) {
        return new StaffTaskFailure(400, "MALFORMED_REQUEST", message);
    }
}
