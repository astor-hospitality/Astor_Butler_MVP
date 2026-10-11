package museon_online.astor_butler.api.glasses.tasks;

import jakarta.servlet.http.HttpServletRequest;

import java.io.IOException;
import java.time.Instant;

/**
 * One photo as it arrives over HTTP: the file is the request body, its Content-Type is the declared type,
 * and eventId, stageCode and an optional capturedAt are query parameters. Signatures only: nothing is read yet.
 */
record StaffEvidenceUpload(String eventId, String stageCode, Instant capturedAt, String contentType, byte[] bytes) {

    static StaffEvidenceUpload read(HttpServletRequest request, StaffEvidence.Limits limits) throws IOException {
        throw new UnsupportedOperationException("Proof photos are not implemented yet");
    }
}
