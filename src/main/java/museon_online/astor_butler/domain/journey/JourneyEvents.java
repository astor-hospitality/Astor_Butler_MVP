package museon_online.astor_butler.domain.journey;

import museon_online.astor_butler.domain.booking.TableReservationOrder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Clock;

/**
 * What existing flows call to put a moment of the visit into the ledger: by reservation, by reservation id (only when
 * the visit is already open), or by the guest's chat (the visit whose window is now). Never throws.
 */
@Component
public class JourneyEvents {

    public static final String BOT = "BOT";
    public static final String PRESTO = "PRESTO";
    public static final String STAFF = "STAFF";
    public static final String GUEST = "GUEST";

    static final String DEFAULT_VENUE = "AERIS";

    private static final Logger log = LoggerFactory.getLogger(JourneyEvents.class);

    private final JourneyRecorder recorder;
    private final GuestVisitRepository visits;
    private final Clock clock;

    @Autowired
    public JourneyEvents(JourneyRecorder recorder, GuestVisitRepository visits) {
        this(recorder, visits, Clock.systemUTC());
    }

    JourneyEvents(JourneyRecorder recorder, GuestVisitRepository visits, Clock clock) {
        this.recorder = recorder;
        this.visits = visits;
        this.clock = clock;
    }

    public void onReservation(TableReservationOrder order, VisitEventKind kind, String source) {
        if (order == null || order.id() == null) {
            return;
        }
        recorder.record(order.id(), order.chatId(), order.guestPhone(), DEFAULT_VENUE, order.requestedStartAt(), kind, source, null);
    }

    public void onReservationId(Long reservationId, VisitEventKind kind, String source) {
        if (reservationId == null || !recorder.enabled()) {
            return;
        }
        try {
            visits.findByReservation(reservationId).ifPresent(visit -> recorder.record(visit.reservationId(), visit.chatId(),
                    visit.guestPhone(), visit.venueCode(), visit.visitDay(), kind, source, null));
        } catch (RuntimeException e) {
            log.warn("Guest journey event by reservation not recorded: reservation={}, kind={}, reason={}", reservationId, kind, e.toString());
        }
    }

    public void onChat(Long chatId, VisitEventKind kind, String source, String note) {
        if (chatId == null || chatId <= 0 || !recorder.enabled()) {
            return;
        }
        try {
            visits.activeByChat(chatId, clock.instant()).ifPresent(visit -> recorder.record(visit.reservationId(), visit.chatId(),
                    visit.guestPhone(), visit.venueCode(), visit.visitDay(), kind, source, note));
        } catch (RuntimeException e) {
            log.warn("Guest journey event by chat not recorded: chat={}, kind={}, reason={}", chatId, kind, e.toString());
        }
    }

    /** One message or button press of a guest in a private chat; group chats (staff) never count. */
    public void guestMessage(Long chatId) {
        if (chatId == null || chatId <= 0) {
            return;
        }
        recorder.countGuestMessage(chatId);
    }
}
