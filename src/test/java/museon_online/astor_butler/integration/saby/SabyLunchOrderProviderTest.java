package museon_online.astor_butler.integration.saby;

import museon_online.astor_butler.domain.lunch.ExternalLunchOrderProvider;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SabyLunchOrderProviderTest {

    @Test
    void isOffUntilSomeoneSwitchesItOn() {
        SabyLunchOrderProvider provider = new SabyLunchOrderProvider(new SabyReservationProperties());

        assertThat(provider.providerId()).isEqualTo("SABY");
        assertThat(provider.status().enabled()).isFalse();
        assertThat(provider.status().configured()).isFalse();
        assertThat(provider.submit(null, "astor-lunch-1").status()).isEqualTo("PROVIDER_NOT_CONFIGURED");
    }

    @Test
    void acceptsNothingEvenWhenConfiguredUntilTheSabyContractIsImplemented() {
        SabyReservationProperties properties = new SabyReservationProperties();
        properties.setEnabled(true);
        properties.setBaseUrl("https://example.invalid");
        properties.setAuthMethod("token");
        properties.setApiToken("placeholder");
        properties.setOrganizationId("org");
        properties.setRestaurantId("restaurant");
        properties.setAvailabilityPath("/availability");
        properties.setReservationPath("/reservations");
        SabyLunchOrderProvider provider = new SabyLunchOrderProvider(properties);

        ExternalLunchOrderProvider.Result result = provider.submit(null, "astor-lunch-1");

        assertThat(provider.status().configured()).isTrue();
        assertThat(result.accepted()).isFalse();
        assertThat(result.attempted()).isTrue();
        assertThat(result.status()).isEqualTo("PROVIDER_CONTRACT_NOT_IMPLEMENTED");
        assertThat(result.externalOrderId()).isEmpty();
    }
}
