package museon_online.astor_butler.domain.booking;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** With the feature off there is no ticker bean at all, so nothing is scheduled; on, the tick never throws. */
class BookingHoldExpirySchedulerTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withBean(BookingHoldExpiryService.class, () -> mock(BookingHoldExpiryService.class))
            .withUserConfiguration(BookingHoldExpiryScheduler.class);

    @Test
    void nothingIsScheduledWhileTheFeatureIsOff() {
        runner.run(context -> assertThat(context).doesNotHaveBean(BookingHoldExpiryScheduler.class));
        runner.withPropertyValues("astor.booking.hold-expiry.enabled=false")
                .run(context -> assertThat(context).doesNotHaveBean(BookingHoldExpiryScheduler.class));
    }

    @Test
    void theTickerExistsOnceTheFeatureIsOn() {
        runner.withPropertyValues("astor.booking.hold-expiry.enabled=true")
                .run(context -> assertThat(context).hasSingleBean(BookingHoldExpiryScheduler.class));
    }

    @Test
    void aFailingTickIsLoggedNotThrown() {
        BookingHoldExpiryService service = mock(BookingHoldExpiryService.class);
        when(service.run()).thenThrow(new IllegalStateException("database is away"));
        assertThatCode(() -> new BookingHoldExpiryScheduler(service).scheduled()).doesNotThrowAnyException();
    }
}
