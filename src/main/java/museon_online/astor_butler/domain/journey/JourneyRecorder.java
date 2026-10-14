package museon_online.astor_butler.domain.journey;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
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

    /** The visit ledger (negotiation plan, task 1): staff and Presto actions; optional, writes nothing while off. */
    private museon_online.astor_butler.domain.ledger.LedgerWriter ledgerWriter;

    @Autowired(required = false)
    public void setLedgerWriter(museon_online.astor_butler.domain.ledger.LedgerWriter ledgerWriter) {
        this.ledgerWriter = ledgerWriter;
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
                    .orElseGet(() -> open(reservationId, chatId, guestPhone, venueCode, visitDay));
            boolean written = visits.insertEvent(visit.id(), kind, source, note);
            if (kind == VisitEventKind.MOVED && visitDay != null && !visitDay.equals(visit.visitDay())) {
                visits.updateVisitDay(visit.id(), visitDay);
            }
            if (written && kind.stage() != null && moves(visit.stage(), kind.stage())) {
                visits.moveStage(visit.id(), kind.stage());
            }
            if (written) {
                ledgerStaffAction(visit.id(), kind, source);
            }
            return visits.findByReservation(reservationId);
        } catch (RuntimeException e) {
            log.warn("Guest journey event not recorded: reservation={}, kind={}, reason={}", reservationId, kind, e.toString());
            return Optional.empty();
        }
    }

    private void ledgerStaffAction(long visitId, VisitEventKind kind, String source) {
        boolean staff = JourneyEvents.STAFF.equals(source);
        if (ledgerWriter == null || !(staff || JourneyEvents.PRESTO.equals(source))) {
            return;
        }
        ledgerWriter.write(new museon_online.astor_butler.domain.ledger.VisitTurnDraft(visitId, null,
                staff ? museon_online.astor_butler.domain.ledger.TurnSide.STAFF : museon_online.astor_butler.domain.ledger.TurnSide.SYSTEM,
                staff ? museon_online.astor_butler.domain.ledger.TurnChannel.STAFF_GROUP : museon_online.astor_butler.domain.ledger.TurnChannel.SABY,
                museon_online.astor_butler.domain.ledger.TurnKind.STAFF_ACTION, kind.name(), null, null));
    }

    private GuestVisit open(long reservationId, Long chatId, String guestPhone, String venueCode, Instant visitDay) {
        try {
            return visits.open(reservationId, chatId, guestPhone, venueCode == null ? "AERIS" : venueCode, visitDay);
        } catch (DataIntegrityViolationException concurrentlyOpened) {
            return visits.findByReservation(reservationId).orElseThrow(() -> concurrentlyOpened);
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

    public static boolean moves(JourneyStage current, JourneyStage next) {
        if (current != null && current.isExit()) {
            return false;
        }
        return next.isExit() || current == null || next.order() > current.order();
    }
}
