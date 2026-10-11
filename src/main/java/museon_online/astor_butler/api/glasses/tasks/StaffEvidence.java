package museon_online.astor_butler.api.glasses.tasks;

import java.time.Instant;
import java.util.Set;

/**
 * One stored proof photo and what it is tied to: the task, the stage, the task version the photo produced
 * and the audit event of that upload. The object key and the venue stay on the server and are not part of
 * this value, so nothing here tells a client where the bytes live.
 */
public record StaffEvidence(String evidenceId, String taskId, String stageCode, long versionAfter, String eventId,
                            String uploadedBy, String contentType, long size, String sha256,
                            Instant capturedAt, Instant receivedAt, Instant expiresAt) {

    /** The answer to an upload: what was stored, and the task with the photo counted on its stage. */
    public record Receipt(StaffEvidence evidence, StaffTask task) { }

    /** The bytes of one photo, for a reader the portal has already let in. */
    public record File(StaffEvidence evidence, byte[] bytes) { }

    /** What an upload may be, so a transport can refuse early. The portal checks the same again itself. */
    public record Limits(int maxBytes, Set<String> contentTypes) {
        public Limits {
            contentTypes = Set.copyOf(contentTypes);
        }
    }
}
