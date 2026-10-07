package museon_online.astor_butler.domain.booking;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import museon_online.astor_butler.domain.booking.external.ExternalBookingSnapshot;
import museon_online.astor_butler.domain.booking.external.ExternalBookingState;
import museon_online.astor_butler.domain.booking.external.ExternalReservationProvider;
import museon_online.astor_butler.domain.booking.external.ExternalReservationStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Brings local orders in line with the restaurant's own booking system, where the staff accepts or drops them.
 * Runs on a schedule while a provider is switched on; reads only, and moves each order at most one step
 * (awaiting → confirmed, awaiting → rejected, confirmed → cancelled). A read that fails or says nothing changes
 * nothing, so the hostess flow in Butler stays the fallback.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ExternalBookingSync {

    static final int BATCH = 200;

    private final TableReservationRepository repository;
    private final TableReservationService reservationService;
    private final ExternalReservationProvider externalProvider;
    private long lastCheckedId;

    public record Report(int checked, int confirmed, int cancelled, int unknown, int failed) {
        static final Report NOTHING = new Report(0, 0, 0, 0, 0);
    }

    @Scheduled(
            fixedDelayString = "${astor.booking.external-sync.fixed-delay-ms:60000}",
            initialDelayString = "${astor.booking.external-sync.initial-delay-ms:90000}"
    )
    public void scheduled() {
        Report report = run();
        if (report.checked() > 0) {
            log.info("External booking sync: checked={}, confirmed={}, cancelled={}, unknown={}, failed={}",
                    report.checked(), report.confirmed(), report.cancelled(), report.unknown(), report.failed());
        }
    }

    public synchronized Report run() {
        ExternalReservationStatus status = externalProvider.status();
        if (status == null || !status.enabled() || !status.configured()) {
            return Report.NOTHING;
        }
        List<TableReservationOrder> orders = lastCheckedId == 0
                ? repository.findActiveOrdersWithExternalId(BATCH)
                : repository.findActiveOrdersWithExternalIdAfter(lastCheckedId, BATCH);
        // Rotate across all active bookings, including those whose state never changes.
        // Keyset paging stays stable when orders are cancelled or updated during the sweep.
        lastCheckedId = orders.size() == BATCH ? orders.getLast().id() : 0L;
        int confirmed = 0;
        int cancelled = 0;
        int unknown = 0;
        int failed = 0;
        for (TableReservationOrder order : orders) {
            try {
                ExternalBookingSnapshot snapshot = externalProvider.fetchReservationState(order.sbisExternalId());
                switch (snapshot == null ? ExternalBookingState.UNKNOWN : snapshot.state()) {
                    case CONFIRMED -> {
                        if (order.status() == TableReservationStatus.AWAITING_MANAGER_CONFIRMATION) {
                            reservationService.confirmFromVenue(order.id());
                            confirmed++;
                        }
                    }
                    case CANCELLED -> {
                        reservationService.cancelFromVenue(order.id());
                        cancelled++;
                    }
                    case UNKNOWN -> unknown++;
                    case PENDING, COMPLETED -> {
                        // Nothing to move: still waiting, or the visit is over and the local order ages out on its own.
                    }
                }
            } catch (RuntimeException e) {
                failed++;
                log.warn("External booking sync failed for order {}: {}", order.id(), e.toString());
            }
        }
        return new Report(orders.size(), confirmed, cancelled, unknown, failed);
    }
}
