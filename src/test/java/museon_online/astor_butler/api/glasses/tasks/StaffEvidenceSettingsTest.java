package museon_online.astor_butler.api.glasses.tasks;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Optional;
import java.util.Set;

import static museon_online.astor_butler.api.glasses.tasks.InMemoryStaffEvidenceStorage.jpeg;
import static museon_online.astor_butler.api.glasses.tasks.InMemoryStaffEvidenceStorage.png;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Proof photos stay off unless their storage is configured completely; a wrong setting never stops the application. */
class StaffEvidenceSettingsTest {
    private static final String ENDPOINT = "https://storage.example.test", TYPES = "image/jpeg,image/png,image/webp,image/heic,image/heif";

    private static Optional<StaffEvidenceSettings> from(boolean enabled, String endpoint, String bucket, String accessKey,
                                                        String secretKey, String maxBytes, String types, String days) {
        return StaffEvidenceSettings.from(enabled, endpoint, "ru-central1", bucket, accessKey, secretKey, maxBytes, types, days);
    }

    @Test void offUnlessSwitchedOnAndCompletelyConfigured() {
        assertThat(from(false, ENDPOINT, "bucket", "key-id", "key-value", "10485760", TYPES, "30")).isEmpty();
        assertThat(from(true, "", "bucket", "key-id", "key-value", "10485760", TYPES, "30")).isEmpty();
        assertThat(from(true, ENDPOINT, " ", "key-id", "key-value", "10485760", TYPES, "30")).isEmpty();
        assertThat(from(true, ENDPOINT, "bucket", "", "key-value", "10485760", TYPES, "30")).isEmpty();
        assertThat(from(true, ENDPOINT, "bucket", "key-id", null, "10485760", TYPES, "30")).isEmpty();
        assertThat(from(true, "not an address", "bucket", "key-id", "key-value", "10485760", TYPES, "30")).isEmpty();
    }

    @Test void aCompleteConfigurationGivesTheLimitsItWasGiven() {
        var settings = from(true, ENDPOINT, "bucket", "key-id", "key-value", " 5242880 ", "image/jpeg, IMAGE/PNG ,", "14").orElseThrow();
        assertThat(settings.maxBytes()).isEqualTo(5242880);
        assertThat(settings.contentTypes()).containsExactlyInAnyOrder("image/jpeg", "image/png");
        assertThat(settings.retention()).isEqualTo(Duration.ofDays(14));
        assertThat(settings.storage()).isInstanceOf(S3StaffEvidenceStorage.class);
        assertThat(settings.limits()).isEqualTo(new StaffEvidence.Limits(5242880, Set.of("image/jpeg", "image/png")));
        // The region is optional: a MinIO inside the stand has none.
        assertThat(StaffEvidenceSettings.from(true, "http://minio:9000", "", "bucket", "key-id", "key-value", "10485760", TYPES, "30")).isPresent();
    }

    @Test void unusableLimitsKeepPhotosOffInsteadOfStoppingTheApplication() {
        for (String size : new String[]{"0", "-1", "ten megabytes", "", String.valueOf(StaffEvidenceSettings.MAX_BYTES_CEILING + 1)}) {
            assertThat(from(true, ENDPOINT, "bucket", "key-id", "key-value", size, TYPES, "30")).as("size " + size).isEmpty();
        }
        for (String days : new String[]{"0", "-3", "forever", String.valueOf(StaffEvidenceSettings.MAX_RETENTION_DAYS + 1)}) {
            assertThat(from(true, ENDPOINT, "bucket", "key-id", "key-value", "10485760", TYPES, days)).as("days " + days).isEmpty();
        }
        for (String types : new String[]{"", " , ", "image/jpeg,image/gif", "image/svg+xml", "video/mp4", "*/*"}) {
            assertThat(from(true, ENDPOINT, "bucket", "key-id", "key-value", "10485760", types, "30")).as("types " + types).isEmpty();
        }
        assertThat(from(true, ENDPOINT, "bucket", "key-id", "key-value",
                String.valueOf(StaffEvidenceSettings.MAX_BYTES_CEILING), TYPES, "30")).isPresent();
    }

    @Test void theServiceItselfRefusesWhatTheVenueDoesNotKeep() {
        var settings = new StaffEvidenceSettings(new InMemoryStaffEvidenceStorage(), 1000, Set.of("image/jpeg"), Duration.ofDays(30));
        settings.accept("image/jpeg", jpeg(1000));
        refused(() -> settings.accept("image/jpeg", jpeg(1001)), 413, "PAYLOAD_TOO_LARGE");
        refused(() -> settings.accept("image/png", png()), 415, "UNSUPPORTED_MEDIA_TYPE");
        refused(() -> settings.accept("image/jpeg", png()), 415, "UNSUPPORTED_MEDIA_TYPE");
        refused(() -> settings.accept(null, jpeg(100)), 415, "UNSUPPORTED_MEDIA_TYPE");
        refused(() -> settings.accept("image/jpeg", new byte[0]), 400, "MALFORMED_REQUEST");
        refused(() -> settings.accept("image/jpeg", null), 400, "MALFORMED_REQUEST");
    }

    private static void refused(org.assertj.core.api.ThrowableAssert.ThrowingCallable call, int status, String code) {
        assertThatThrownBy(call).isInstanceOfSatisfying(StaffTaskFailure.class, failure -> {
            assertThat(failure.status).isEqualTo(status);
            assertThat(failure.code).isEqualTo(code);
        });
    }
}
