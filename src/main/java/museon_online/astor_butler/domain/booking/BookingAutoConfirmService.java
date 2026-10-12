package museon_online.astor_butler.domain.booking;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import museon_online.astor_butler.api.common.ApiException;
import museon_online.astor_butler.domain.booking.BookingAutoConfirmPolicy.Decision;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * The timer behind the hostess card. Every run looks at the cards nobody answered within the timeout and, per card,
 * either confirms the booking to the guest by itself or hands the card to the manager, once. A card the hostess
 * decided meanwhile is not touched: the status is read again under the lock of the confirming transaction.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class BookingAutoConfirmService {

    static final int BATCH = 100;

    private final BookingAutoConfirmProperties properties;
    private final BookingAutoConfirmPolicy policy;
    private final BookingHostessStateStorage storage;
    private final TableReservationRepository repository;
    private final TableReservationService reservationService;
    private final TableReservationNotificationService notificationService;
    private Clock clock = Clock.systemUTC();

    @Value("${telegram.booking.hostess-chat-id:}")
    private String hostessChatId;

    public record Report(int checked, int confirmed, int escalated, int skipped, int failed) {
        static final Report NOTHING = new Report(0, 0, 0, 0, 0);
    }

    @Scheduled(
            fixedDelayString = "${astor.booking.auto-confirm.check-interval-ms:30000}",
            initialDelayString = "${astor.booking.auto-confirm.initial-delay-ms:60000}"
    )
    public void scheduled() {
        Report report = run();
        if (report.checked() > 0) {
            log.info("Booking auto-confirm: checked={}, confirmed={}, escalated={}, skipped={}, failed={}",
                    report.checked(), report.confirmed(), report.escalated(), report.skipped(), report.failed());
        }
    }

    @EventListener(ApplicationReadyEvent.class)
    public void warnAtStartup() {
        startupWarning().ifPresent(log::warn);
    }

    /** Switched on without a hostess chat, nobody can decline: every request would confirm itself unseen. */
    Optional<String> startupWarning() {
        if (!properties.isEnabled()) {
            return Optional.empty();
        }
        if (hostessChatId == null || hostessChatId.isBlank()) {
            return Optional.of("Booking auto-confirm is enabled (ASTOR_BOOKING_AUTO_CONFIRM_ENABLED=true) but no hostess chat is "
                    + "configured (TELEGRAM_HOSTESS_CHAT_ID is blank): nobody sees the cards, so every request will be confirmed "
                    + "automatically after " + policy.timeoutMinutes() + " min with no chance to decline");
        }
        return Optional.empty();
    }

    public synchronized Report run() {
        if (!properties.isEnabled()) {
            return Report.NOTHING;
        }
        int minutes = policy.timeoutMinutes();
        Instant now = clock.instant();
        List<TableReservationOrder> due = repository.findAwaitingManagerConfirmationUnansweredSince(
                now.minus(Duration.ofMinutes(minutes)), now, BATCH);
        int confirmed = 0;
        int escalated = 0;
        int skipped = 0;
        int failed = 0;
        for (TableReservationOrder order : due) {
            try {
                if (storage.escalated(order.id())) {
                    // The manager has this card already; it is theirs to decide even if the mode changed since.
                    skipped++;
                    continue;
                }
                Decision decision = policy.decide(order);
                if (decision == Decision.AUTO) {
                    reservationService.confirmAutomatically(order.id(), minutes);
                    confirmed++;
                } else if (decision.goesToTheManager()) {
                    if (!storage.markEscalated(order.id())) {
                        skipped++;
                        continue;
                    }
                    notificationService.notifyManagerEscalation(order, decision, minutes);
                    notificationService.notifyHostessEscalated(order, decision, minutes);
                    notificationService.notifyGuestEscalatedToManager(order);
                    escalated++;
                } else {
                    skipped++;
                }
            } catch (ApiException e) {
                // Decided by a person between the query and the write: nothing to do.
                skipped++;
                log.debug("Booking auto-confirm left order {} alone: {}", order.id(), e.getMessage());
            } catch (RuntimeException e) {
                failed++;
                log.warn("Booking auto-confirm failed for order {}: {}", order.id(), e.toString());
            }
        }
        return new Report(due.size(), confirmed, escalated, skipped, failed);
    }

    void setClock(Clock clock) {
        this.clock = clock;
    }

    void setHostessChatId(String hostessChatId) {
        this.hostessChatId = hostessChatId;
    }
}
