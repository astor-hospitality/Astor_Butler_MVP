package museon_online.astor_butler.integration.saby;

import org.junit.jupiter.api.Test;
import org.springframework.boot.restclient.RestTemplateBuilder;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;

class SabyPaymentProviderContextTest {
    @Test
    void springCreatesPaymentProviderWithIntegrationDisabled() {
        try (var context = new AnnotationConfigApplicationContext()) {
            context.registerBean(SabyReservationProperties.class, SabyReservationProperties::new);
            context.registerBean(RestTemplateBuilder.class, () -> new RestTemplateBuilder());
            context.register(SabyPaymentProvider.class);
            context.refresh();
            assertThat(context.getBean(SabyPaymentProvider.class).enabled()).isFalse();
        }
    }
}
