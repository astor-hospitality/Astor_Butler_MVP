package museon_online.astor_butler.domain.booking;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Ticks the morning reconfirmation (#149). The bean exists only while `astor.booking.morning-check.enabled` is true,
 * so with the feature off nothing is scheduled at all. Every tick asks the service whether the venue clock has passed
 * the send time; the first tick after a start therefore also catches up a send time that passed while Butler was
 * down, and the rows in table_reservation_morning_checks keep every guest to one question.
 */
@Component
@ConditionalOnProperty(prefix = "astor.booking.morning-check", name = "enabled", havingValue = "true")
@Slf4j
public class BookingMorningCheckScheduler {

    private final BookingMorningCheckService service;

    public BookingMorningCheckScheduler(BookingMorningCheckService service) {
        this.service = service;
    }

    @Scheduled(
            fixedDelayString = "${astor.booking.morning-check.poll-ms:60000}",
            initialDelayString = "${astor.booking.morning-check.initial-delay-ms:60000}"
    )
    public void scheduled() {
        try {
            BookingMorningCheckService.Report report = service.run();
            if (report.sent() > 0 || report.failed() > 0) {
                log.info("Morning booking checks: checked={}, sent={}, skipped={}, failed={}",
                        report.checked(), report.sent(), report.skipped(), report.failed());
            }
        } catch (RuntimeException e) {
            log.warn("Morning booking checks failed: {}", e.toString());
        }
    }
}
