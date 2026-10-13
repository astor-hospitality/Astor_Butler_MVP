package museon_online.astor_butler.domain.journey;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.util.Optional;

/** «Вернулся» (spec §3.5): a new booking with the phone of a visit that happened in the last 30 days closes the loop. */
@Component
public class ReturnMatcher {

    private static final Logger log = LoggerFactory.getLogger(ReturnMatcher.class);

    private final GuestJourneyProperties properties;
    private final GuestVisitRepository visits;
    private final JourneyRecorder recorder;
    private final Clock clock;

    @Autowired
    public ReturnMatcher(GuestJourneyProperties properties, GuestVisitRepository visits, JourneyRecorder recorder) {
        this(properties, visits, recorder, Clock.systemUTC());
    }

    ReturnMatcher(GuestJourneyProperties properties, GuestVisitRepository visits, JourneyRecorder recorder, Clock clock) {
        this.properties = properties;
        this.visits = visits;
        this.recorder = recorder;
        this.clock = clock;
    }

    public void onNewReservation(long reservationId, String guestPhone) {
        if (!properties.isEnabled() || !properties.getReturnLoop().isEnabled() || guestPhone == null || guestPhone.isBlank()) {
            return;
        }
        try {
            Optional<GuestVisit> previous = visits.previousVisitByPhone(guestPhone,
                    clock.instant().minus(Duration.ofDays(properties.getReturnLoop().getWindowDays())), reservationId);
            Optional<GuestVisit> current = visits.findByReservation(reservationId);
            if (previous.isEmpty() || current.isEmpty()) {
                return;
            }
            GuestVisit before = previous.get();
            recorder.record(before.reservationId(), before.chatId(), before.guestPhone(), before.venueCode(), before.visitDay(),
                    VisitEventKind.RETURNED, JourneyEvents.BOT, "reservation " + reservationId);
            visits.linkReturn(before.id(), current.get().id());
        } catch (RuntimeException e) {
            log.warn("Return not matched: reservation={}, reason={}", reservationId, e.toString());
        }
    }
}
