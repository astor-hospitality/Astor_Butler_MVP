package museon_online.astor_butler.domain.booking;

import museon_online.astor_butler.domain.shift.ButlerPauseService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import museon_online.astor_butler.api.common.ApiException;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * The clock behind the table hold (#87). Every run looks at the requests nobody answered within the TTL and expires
 * each of them once: the status becomes EXPIRED, the hold is released, the guest, the hostess and the manager hear
 * about it. A request decided meanwhile is left alone: the status is checked again by the expiring transaction, and
 * a second run never finds an expired request, so nothing is sent twice.
 *
 * A request the venue's own system already knows (a Saby id on the order) never expires here: Presto holds that
 * booking and is the one to confirm or drop it.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class BookingHoldExpiryService {

    static final int BATCH = 100;

    private final BookingHoldExpiryProperties properties;
    private final TableReservationRepository repository;
    private final TableReservationService reservationService;
    private Clock clock = Clock.systemUTC();

    /** The shift manager's kill switch (#134): optional so the constructor and its tests stay as they are. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private ButlerPauseService butlerPause;

    public record Report(int checked, int expired, int skipped, int failed) {
        static final Report NOTHING = new Report(0, 0, 0, 0);
    }

    public synchronized Report run() {
        if (!properties.isEnabled()) {
            return Report.NOTHING;
        }
        if (butlerPause != null && butlerPause.paused()) {
            log.debug("Booking hold expiry skipped: Butler is paused by the shift manager");
            return Report.NOTHING;
        }
        int minutes = properties.ttlMinutes();
        Instant now = clock.instant();
        List<TableReservationOrder> due = repository.findAwaitingManagerConfirmationUnansweredSince(
                now.minus(Duration.ofMinutes(minutes)), now, BATCH);
        int expired = 0;
        int skipped = 0;
        int failed = 0;
        for (TableReservationOrder order : due) {
            try {
                if (inVenueSystem(order)) {
                    skipped++;
                    log.debug("Booking hold expiry left order {} alone: it is in the venue system", order.id());
                    continue;
                }
                reservationService.expireHold(order.id(), minutes);
                expired++;
            } catch (ApiException e) {
                // Decided by a person between the query and the write: nothing to do.
                skipped++;
                log.debug("Booking hold expiry left order {} alone: {}", order.id(), e.getMessage());
            } catch (RuntimeException e) {
                failed++;
                log.warn("Booking hold expiry failed for order {}: {}", order.id(), e.toString());
            }
        }
        return new Report(due.size(), expired, skipped, failed);
    }

    static boolean inVenueSystem(TableReservationOrder order) {
        return order.sbisExternalId() != null && !order.sbisExternalId().isBlank();
    }

    void setClock(Clock clock) {
        this.clock = clock;
    }

    void setButlerPause(ButlerPauseService butlerPause) {
        this.butlerPause = butlerPause;
    }
}
