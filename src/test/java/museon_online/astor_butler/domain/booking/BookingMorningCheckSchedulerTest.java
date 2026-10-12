package museon_online.astor_butler.domain.booking;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** With the feature off there is no ticker bean at all, so nothing is scheduled; on, the tick never throws. */
class BookingMorningCheckSchedulerTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withBean(BookingMorningCheckService.class, () -> mock(BookingMorningCheckService.class))
            .withUserConfiguration(BookingMorningCheckScheduler.class);

    @Test
    void nothingIsScheduledWhileTheFeatureIsOff() {
        runner.run(context -> assertThat(context).doesNotHaveBean(BookingMorningCheckScheduler.class));
        runner.withPropertyValues("astor.booking.morning-check.enabled=false")
                .run(context -> assertThat(context).doesNotHaveBean(BookingMorningCheckScheduler.class));
    }

    @Test
    void theTickerExistsOnceTheFeatureIsOn() {
        runner.withPropertyValues("astor.booking.morning-check.enabled=true")
                .run(context -> assertThat(context).hasSingleBean(BookingMorningCheckScheduler.class));
    }

    @Test
    void aFailingTickIsLoggedNotThrown() {
        BookingMorningCheckService service = mock(BookingMorningCheckService.class);
        when(service.run()).thenThrow(new IllegalStateException("database is away"));
        assertThatCode(() -> new BookingMorningCheckScheduler(service).scheduled()).doesNotThrowAnyException();
    }
}
