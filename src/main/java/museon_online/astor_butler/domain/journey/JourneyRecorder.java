package museon_online.astor_butler.domain.journey;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.OptionalInt;

/**
 * The only way into the guest journey ledger (spec §3.1). With the flag off it touches nothing; a failure is logged and
 * never reaches the guest's scenario.
 */
@Component
public class JourneyRecorder {

    private static final Logger log = LoggerFactory.getLogger(JourneyRecorder.class);

    private final GuestVisitRepository visits;
    private final GuestJourneyProperties properties;
    private final Clock clock;

    @Autowired
    public JourneyRecorder(GuestVisitRepository visits, GuestJourneyProperties properties) {
        this(visits, properties, Clock.systemUTC());
    }

    JourneyRecorder(GuestVisitRepository visits, GuestJourneyProperties properties, Clock clock) {
        this.visits = visits;
        this.properties = properties;
        this.clock = clock;
    }

    public boolean enabled() {
        return properties.isEnabled();
    }

    public Optional<GuestVisit> record(long reservationId, Long chatId, String guestPhone, String venueCode, Instant visitDay,
                                       VisitEventKind kind, String source, String note) {
        if (!properties.isEnabled() || kind == null) {
            return Optional.empty();
        }
        try {
            GuestVisit visit = visits.findByReservation(reservationId)
                    .orElseGet(() -> visits.open(reservationId, chatId, guestPhone, venueCode == null ? "AERIS" : venueCode, visitDay));
            boolean written = visits.insertEvent(visit.id(), kind, source, note);
            if (written && kind.stage() != null && moves(visit.stage(), kind.stage())) {
                visits.moveStage(visit.id(), kind.stage());
            }
            return visits.findByReservation(reservationId);
        } catch (RuntimeException e) {
            log.warn("Guest journey event not recorded: reservation={}, kind={}, reason={}", reservationId, kind, e.toString());
            return Optional.empty();
        }
    }

    /** Counts one guest message into the visit whose window holds now; empty when there is none. */
    public OptionalInt countGuestMessage(long chatId) {
        if (!properties.isEnabled()) {
            return OptionalInt.empty();
        }
        try {
            return visits.activeByChat(chatId, clock.instant())
                    .map(visit -> OptionalInt.of(visits.incrementGuestMessages(visit.id())))
                    .orElse(OptionalInt.empty());
        } catch (RuntimeException e) {
            log.warn("Guest message not counted: chat={}, reason={}", chatId, e.toString());
            return OptionalInt.empty();
        }
    }

    static boolean moves(JourneyStage current, JourneyStage next) {
        if (current != null && current.isExit()) {
            return false;
        }
        return next.isExit() || current == null || next.order() > current.order();
    }
}
