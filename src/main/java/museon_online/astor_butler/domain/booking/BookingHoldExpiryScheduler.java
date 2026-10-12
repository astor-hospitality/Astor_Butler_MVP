package museon_online.astor_butler.domain.booking;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

/**
 * Ticks the table hold expiry (#87). The bean exists only while `astor.booking.hold-expiry.enabled` is true, so with
 * the feature off nothing is scheduled at all. Every tick expires the requests whose TTL ran out; a request is
 * expired once because the status changes with it.
 */
@Component
@ConditionalOnProperty(prefix = "astor.booking.hold-expiry", name = "enabled", havingValue = "true")
@Slf4j
public class BookingHoldExpiryScheduler {

    private final BookingHoldExpiryService service;

    public BookingHoldExpiryScheduler(BookingHoldExpiryService service) {
        this.service = service;
    }

    @Scheduled(
            fixedDelayString = "${astor.booking.hold-expiry.check-seconds:60}",
            initialDelayString = "${astor.booking.hold-expiry.initial-delay-seconds:60}",
            timeUnit = TimeUnit.SECONDS
    )
    public void scheduled() {
        try {
            BookingHoldExpiryService.Report report = service.run();
            if (report.expired() > 0 || report.failed() > 0) {
                log.info("Booking hold expiry: checked={}, expired={}, skipped={}, failed={}",
                        report.checked(), report.expired(), report.skipped(), report.failed());
            }
        } catch (RuntimeException e) {
            log.warn("Booking hold expiry failed: {}", e.toString());
        }
    }
}
