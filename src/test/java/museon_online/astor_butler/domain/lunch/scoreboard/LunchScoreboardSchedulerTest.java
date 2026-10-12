package museon_online.astor_butler.domain.lunch.scoreboard;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** With the flag off there is no ticker bean at all, so nothing is scheduled; on, the tick never throws. */
class LunchScoreboardSchedulerTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withBean(LunchScoreboardDispatcher.class, () -> mock(LunchScoreboardDispatcher.class))
            .withUserConfiguration(LunchScoreboardScheduler.class);

    @Test
    void nothingIsScheduledWhileTheFeatureIsOff() {
        runner.run(context -> assertThat(context).doesNotHaveBean(LunchScoreboardScheduler.class));
        runner.withPropertyValues("astor.business-lunch.scoreboard.enabled=false")
                .run(context -> assertThat(context).doesNotHaveBean(LunchScoreboardScheduler.class));
    }

    @Test
    void theTickerExistsOnceTheFeatureIsOn() {
        runner.withPropertyValues("astor.business-lunch.scoreboard.enabled=true")
                .run(context -> assertThat(context).hasSingleBean(LunchScoreboardScheduler.class));
    }

    @Test
    void aFailingTickIsLoggedNotThrown() {
        LunchScoreboardDispatcher dispatcher = mock(LunchScoreboardDispatcher.class);
        when(dispatcher.run()).thenThrow(new IllegalStateException("database is away"));
        assertThatCode(() -> new LunchScoreboardScheduler(dispatcher).scheduled()).doesNotThrowAnyException();
    }
}
