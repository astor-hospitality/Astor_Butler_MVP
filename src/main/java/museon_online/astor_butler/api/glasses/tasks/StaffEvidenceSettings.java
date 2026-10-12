package museon_online.astor_butler.api.glasses.tasks;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * What the portal needs to keep proof photos: where the bytes go and the limits of one upload. Without it
 * the portal has no storage and the upload answers 503; there is no default storage and no in-memory one.
 */
public record StaffEvidenceSettings(StaffEvidenceStorage storage, int maxBytes, Set<String> contentTypes, Duration retention) {
    /** A photo is held in memory while it is checked and stored, so the configured limit has a ceiling. */
    static final int MAX_BYTES_CEILING = 32 * 1024 * 1024;
    static final int MAX_RETENTION_DAYS = 3650;
    private static final Logger log = LoggerFactory.getLogger(StaffEvidenceSettings.class);

    public StaffEvidenceSettings {
        if (storage == null) throw new IllegalArgumentException("Evidence storage is required");
        if (maxBytes < 1 || maxBytes > MAX_BYTES_CEILING) throw new IllegalArgumentException("Evidence size limit is out of range");
        contentTypes = Set.copyOf(contentTypes);
        if (contentTypes.isEmpty() || !StaffEvidenceImages.TYPES.containsAll(contentTypes)) {
            throw new IllegalArgumentException("Evidence content types must be a non-empty subset of " + StaffEvidenceImages.TYPES);
        }
        if (retention == null || retention.compareTo(Duration.ofDays(1)) < 0
                || retention.compareTo(Duration.ofDays(MAX_RETENTION_DAYS)) > 0) {
            throw new IllegalArgumentException("Evidence retention is out of range");
        }
    }

    StaffEvidence.Limits limits() {
        return new StaffEvidence.Limits(maxBytes, contentTypes);
    }

    /** Refuses a photo the venue does not keep: too large, of another type, or not the type it claims to be. */
    void accept(String contentType, byte[] photo) {
        if (photo == null || photo.length == 0) throw new StaffTaskFailure(400, "MALFORMED_REQUEST", "The photo is empty");
        if (photo.length > maxBytes) throw tooLarge(maxBytes);
        if (contentType == null || !contentTypes.contains(contentType) || !StaffEvidenceImages.matches(contentType, photo)) {
            throw unsupported();
        }
    }

    static StaffTaskFailure tooLarge(int maxBytes) {
        return new StaffTaskFailure(413, "PAYLOAD_TOO_LARGE", "The photo is larger than " + maxBytes + " bytes");
    }

    static StaffTaskFailure unsupported() {
        return new StaffTaskFailure(415, "UNSUPPORTED_MEDIA_TYPE", "The file is not a photo of an accepted type");
    }

    /**
     * Settings from configuration, or empty when proof photos stay off. An incomplete or invalid
     * configuration does not stop the application, which also serves guests: it is logged once by property
     * name, never by value, and the upload keeps answering 503.
     */
    static Optional<StaffEvidenceSettings> from(boolean enabled, String endpoint, String region, String bucket,
                                                String accessKey, String secretKey, String maxBytes,
                                                String contentTypes, String retentionDays) {
        if (!enabled) return Optional.empty();
        List<String> missing = new ArrayList<>();
        if (blank(endpoint)) missing.add("astor.staff.evidence.s3-endpoint");
        if (blank(bucket)) missing.add("astor.staff.evidence.s3-bucket");
        if (blank(accessKey)) missing.add("astor.staff.evidence.s3-access-key");
        if (blank(secretKey)) missing.add("astor.staff.evidence.s3-secret-key");
        if (!missing.isEmpty()) {
            log.warn("Staff evidence is enabled but its storage is not configured, uploads answer 503. Missing: {}", missing);
            return Optional.empty();
        }
        StaffEvidenceStorage storage;
        try {
            storage = new S3StaffEvidenceStorage(endpoint.strip(), region, bucket.strip(), accessKey, secretKey);
        } catch (RuntimeException e) {
            // A client error may quote the setting it refused, so only the property name is logged.
            log.warn("Staff evidence is enabled but astor.staff.evidence.s3-endpoint is not usable, uploads answer 503");
            return Optional.empty();
        }
        try {
            Set<String> types = Arrays.stream((contentTypes == null ? "" : contentTypes).split(","))
                    .map(type -> type.strip().toLowerCase(Locale.ROOT)).filter(type -> !type.isEmpty())
                    .collect(Collectors.toUnmodifiableSet());
            return Optional.of(new StaffEvidenceSettings(storage, number(maxBytes, "size limit"), types,
                    Duration.ofDays(number(retentionDays, "retention"))));
        } catch (IllegalArgumentException e) {
            log.warn("Staff evidence is enabled but its limits are not usable, uploads answer 503: {}", e.getMessage());
            return Optional.empty();
        }
    }

    /** A number written wrong is a setting to fix, not a reason to stop the application. */
    private static int number(String value, String name) {
        try {
            return Integer.parseInt(value == null ? "" : value.strip());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Evidence " + name + " is not a number");
        }
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
