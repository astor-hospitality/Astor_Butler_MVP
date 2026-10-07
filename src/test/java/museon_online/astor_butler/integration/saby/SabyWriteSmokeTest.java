package museon_online.astor_butler.integration.saby;

import museon_online.astor_butler.domain.booking.TableReservationCommand;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.restclient.RestTemplateBuilder;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Write smoke against a Saby TEST point: create, read state, cancel, read state again.
 * Skipped unless SABY_SMOKE_WRITE=true. Never point it at a production venue.
 * <p>
 * Run: {@code SABY_SMOKE_WRITE=true SABY_APP_CLIENT_ID=... SABY_APP_SECRET=... SABY_SECRET_KEY=...
 * SABY_POINT_ID=<test point> SABY_SMOKE_GUEST_PHONE=<test phone> mvn test -Dtest=SabyWriteSmokeTest}.
 * Prints the create response field names (blocker B4) and raw state codes (blocker B5).
 */
@EnabledIfEnvironmentVariable(named = "SABY_SMOKE_WRITE", matches = "true")
class SabyWriteSmokeTest {

    @Test
    void createsReadsAndCancelsTestBooking() {
        SabyReservationProperties properties = new SabyReservationProperties();
        properties.setEnabled(true);
        properties.setWriteEnabled(true);
        properties.setBaseUrl(env("SABY_API_BASE_URL", properties.getBaseUrl()));
        properties.setAuthUrl(env("SABY_AUTH_URL", properties.getAuthUrl()));
        properties.setAppClientId(env("SABY_APP_CLIENT_ID", ""));
        properties.setAppSecret(env("SABY_APP_SECRET", ""));
        properties.setSecretKey(env("SABY_SECRET_KEY", ""));
        properties.setPointId(env("SABY_POINT_ID", ""));
        properties.setHallId(env("SABY_HALL_ID", ""));
        properties.setZoneId(env("SABY_ZONE_ID", properties.getZoneId()));
        properties.setTimeoutMs(Long.parseLong(env("SABY_TIMEOUT_MS", "10000")));
        String phone = env("SABY_SMOKE_GUEST_PHONE", "");
        assertThat(properties.getPointId()).as("SABY_POINT_ID of a test point").isNotBlank();
        assertThat(phone).as("SABY_SMOKE_GUEST_PHONE, a test number, not a real guest").isNotBlank();
        SabyReservationProvider provider = new SabyReservationProvider(properties, new RestTemplateBuilder());

        ZoneId zone = ZoneId.of(properties.getZoneId());
        var start = LocalDate.now(zone).plusDays(3).atTime(LocalTime.of(19, 0)).atZone(zone).toInstant();
        String key = "smoke-" + UUID.randomUUID();
        var command = new TableReservationCommand(
                null, null, null,
                properties.getVenueCode(), null, null, null,
                start, start.plusSeconds(2 * 60 * 60),
                2,
                "Astor Smoke Test",
                phone,
                "ТЕСТ Astor Butler, отменяется автоматически",
                null, null
        );

        var created = provider.reserve(command, key);
        System.out.println("Saby create: " + created.status() + " id=" + created.externalReservationId()
                + " metadata=" + created.metadata());
        if (!created.created()) {
            System.out.println("Booking was not confirmed as created. If the status is PROVIDER_RESULT_UNKNOWN, "
                    + "find and cancel '" + SabyOrderPayload.BUTLER_MARKER + key + "' in Saby manually.");
        }
        assertThat(created.created()).as(created.message()).isTrue();

        var stateAfterCreate = provider.state(created.externalReservationId());
        System.out.println("Saby state after create: " + stateAfterCreate);
        var cancel = provider.cancel(created.externalReservationId());
        System.out.println("Saby cancel: " + cancel);
        var stateAfterCancel = provider.state(created.externalReservationId());
        System.out.println("Saby state after cancel: " + stateAfterCancel);

        assertThat(cancel.ok()).as(cancel.message()).isTrue();
    }

    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }
}
