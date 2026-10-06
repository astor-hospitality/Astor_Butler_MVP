package museon_online.astor_butler.integration.saby;

import museon_online.astor_butler.domain.booking.external.ExternalReservationProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.restclient.RestTemplateBuilder;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Read-only smoke against a real Saby test account. Skipped unless SABY_SMOKE=true.
 * <p>
 * Run: {@code SABY_SMOKE=true SABY_APP_CLIENT_ID=... SABY_APP_SECRET=... SABY_SECRET_KEY=... [SABY_POINT_ID=...]
 * mvn test -Dtest=SabyReadOnlySmokeTest}. Without SABY_POINT_ID only the point list is fetched, to find it.
 * Never writes to Saby and prints no guest data.
 */
@EnabledIfEnvironmentVariable(named = "SABY_SMOKE", matches = "true")
class SabyReadOnlySmokeTest {

    @Test
    void readsPointsAndAvailabilityFromSaby() {
        SabyReservationProperties properties = new SabyReservationProperties();
        properties.setEnabled(true);
        properties.setBaseUrl(env("SABY_API_BASE_URL", properties.getBaseUrl()));
        properties.setAuthUrl(env("SABY_AUTH_URL", properties.getAuthUrl()));
        properties.setAppClientId(env("SABY_APP_CLIENT_ID", ""));
        properties.setAppSecret(env("SABY_APP_SECRET", ""));
        properties.setSecretKey(env("SABY_SECRET_KEY", ""));
        properties.setPointId(env("SABY_POINT_ID", ""));
        properties.setHallId(env("SABY_HALL_ID", ""));
        properties.setZoneId(env("SABY_ZONE_ID", properties.getZoneId()));
        properties.setTimeoutMs(Long.parseLong(env("SABY_TIMEOUT_MS", "10000")));
        SabyReservationProvider provider = new SabyReservationProvider(properties, new RestTemplateBuilder());

        var points = provider.listPoints();
        System.out.println("Saby point/list: " + points.toPrettyString());

        if (properties.getPointId().isBlank()) {
            System.out.println("SABY_POINT_ID is not set: pick an id from point/list and rerun for availability.");
            return;
        }

        ZoneId zone = ZoneId.of(properties.getZoneId());
        var start = LocalDate.now(zone).plusDays(1).atTime(LocalTime.of(19, 0)).atZone(zone).toInstant();
        var availability = provider.checkAvailability(new ExternalReservationProvider.ExternalAvailabilityRequest(
                properties.getVenueCode(),
                start,
                start.plusSeconds(2 * 60 * 60),
                2,
                null,
                null
        ));
        System.out.println("Saby availability: " + availability.status() + " " + availability.metadata());

        assertThat(availability.status()).isIn("AVAILABLE", "NO_TABLES_AVAILABLE");
    }

    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }
}
