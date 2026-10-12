package museon_online.astor_butler.domain.lunch.scoreboard;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Ticks the lunch scoreboard (#175). The bean exists only while {@code astor.business-lunch.scoreboard.enabled} is
 * true, so with the feature off nothing is scheduled at all — the same shape as the morning booking check (#149).
 */
@Component
@ConditionalOnProperty(prefix = "astor.business-lunch.scoreboard", name = "enabled", havingValue = "true")
@Slf4j
public class LunchScoreboardScheduler {

    private final LunchScoreboardDispatcher dispatcher;

    public LunchScoreboardScheduler(LunchScoreboardDispatcher dispatcher) {
        this.dispatcher = dispatcher;
    }

    @Scheduled(
            fixedDelayString = "${astor.business-lunch.scoreboard.poll-ms:60000}",
            initialDelayString = "${astor.business-lunch.scoreboard.initial-delay-ms:60000}"
    )
    public void scheduled() {
        try {
            dispatcher.run();
        } catch (RuntimeException e) {
            log.warn("Lunch scoreboard tick failed: {}", e.toString());
        }
    }
}
