package museon_online.astor_butler.integration.saby;

import museon_online.astor_butler.domain.booking.external.ExternalReservationProvider;
import org.junit.jupiter.api.Test;
import org.springframework.boot.restclient.RestTemplateBuilder;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class SabyReservationProviderTest {

    @Test
    void reportsMissingConfigurationWithoutCallingExternalApi() {
        SabyReservationProperties properties = new SabyReservationProperties();
        properties.setEnabled(true);
        SabyReservationProvider provider = new SabyReservationProvider(properties, new RestTemplateBuilder());

        var status = provider.status();
        var availability = provider.checkAvailability(new ExternalReservationProvider.ExternalAvailabilityRequest(
                "AERIS",
                Instant.parse("2026-08-02T15:00:00Z"),
                Instant.parse("2026-08-02T17:00:00Z"),
                2,
                null,
                "WINE_ROOM"
        ));

        assertThat(status.configured()).isFalse();
        assertThat(status.missingConfiguration()).containsExactly(
                "SABY_APP_CLIENT_ID",
                "SABY_APP_SECRET",
                "SABY_SECRET_KEY",
                "SABY_POINT_ID"
        );
        assertThat(availability.status()).isEqualTo("PROVIDER_NOT_CONFIGURED");
        assertThat(availability.providerConfigured()).isFalse();
    }

    @Test
    void blocksWritesWhenConfiguredUntilWritePathIsEnabled() {
        SabyReservationProperties properties = new SabyReservationProperties();
        properties.setEnabled(true);
        properties.setAppClientId("client");
        properties.setAppSecret("secret");
        properties.setSecretKey("service-key");
        properties.setPointId("206");
        SabyReservationProvider provider = new SabyReservationProvider(properties, new RestTemplateBuilder());

        var result = provider.reserve(null, "idem-1");

        assertThat(provider.status().configured()).isTrue();
        assertThat(result.created()).isFalse();
        assertThat(result.status()).isEqualTo("SABY_WRITE_DISABLED");
    }
}
