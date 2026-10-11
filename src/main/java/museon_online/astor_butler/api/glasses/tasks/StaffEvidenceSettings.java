package museon_online.astor_butler.api.glasses.tasks;

import java.time.Duration;
import java.util.Optional;
import java.util.Set;

/**
 * What the portal needs to keep proof photos: where the bytes go and the limits of one upload.
 * Signatures only: no configuration turns proof photos on yet.
 */
public record StaffEvidenceSettings(StaffEvidenceStorage storage, int maxBytes, Set<String> contentTypes, Duration retention) {
    static final int MAX_BYTES_CEILING = 32 * 1024 * 1024;
    static final int MAX_RETENTION_DAYS = 3650;

    StaffEvidence.Limits limits() {
        return new StaffEvidence.Limits(maxBytes, contentTypes);
    }

    void accept(String contentType, byte[] photo) {
        throw new UnsupportedOperationException("Proof photos are not implemented yet");
    }

    static Optional<StaffEvidenceSettings> from(boolean enabled, String endpoint, String region, String bucket,
                                                String accessKey, String secretKey, String maxBytes,
                                                String contentTypes, String retentionDays) {
        return Optional.empty();
    }
}
