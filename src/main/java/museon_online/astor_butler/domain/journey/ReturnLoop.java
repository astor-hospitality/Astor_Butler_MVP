package museon_online.astor_butler.domain.journey;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Optional;

/**
 * The return loop (spec §3.5): one useful message the day after the visit, one invitation a week later, and «не пришёл»
 * for a booking whose day passed without seating. Messages only 10:00–21:00 venue time, through the usefulness filter.
 */
public class ReturnLoop {

    private static final Logger log = LoggerFactory.getLogger(ReturnLoop.class);
    private static final int BATCH = 100;

    /** The guest's first name and the waiter of the table, for the texts. */
    public record Guest(String name, String waiter) {
    }

    public interface Guests {
        Optional<Guest> of(long reservationId);
    }

    public interface GuestText {
        void send(long chatId, String text);
    }

    private final GuestJourneyProperties properties;
    private final GuestVisitRepository visits;
    private final JourneyRecorder recorder;
    private final UsefulnessPolicy policy;
    private final GuestText guest;
    private final Guests guests;
    private final Clock clock;

    public ReturnLoop(GuestJourneyProperties properties, GuestVisitRepository visits, JourneyRecorder recorder, UsefulnessPolicy policy,
                      GuestText guest, Guests guests, Clock clock) {
        this.properties = properties;
        this.visits = visits;
        this.recorder = recorder;
        this.policy = policy;
        this.guest = guest;
        this.guests = guests;
        this.clock = clock;
    }

    public void tick() {
        if (!properties.isEnabled() || !properties.getReturnLoop().isEnabled()) {
            return;
        }
        ZoneId zone = ZoneId.of(properties.getZoneId());
        ZonedDateTime now = clock.instant().atZone(zone);
        Instant today = now.toLocalDate().atStartOfDay(zone).toInstant();
        for (GuestVisit visit : visits.inStagesWithDayBefore(List.of(JourneyStage.REQUESTED, JourneyStage.CONFIRMED, JourneyStage.VISIT_DAY), today, BATCH)) {
            record(visit, VisitEventKind.NO_SHOW);
        }
        if (now.getHour() < 10 || now.getHour() >= 21) {
            return;
        }
        for (GuestVisit visit : visits.inStagesWithDayBefore(List.of(JourneyStage.SEATED, JourneyStage.BILL), today, BATCH)) {
            if (visit.chatId() != null && policy.permits(visit.reservationId(), visit.chatId(), ProactiveMessage.AFTER)) {
                send(visit, afterText(visit));
            }
            record(visit, VisitEventKind.AFTER_SENT);
        }
        Instant inviteBefore = now.toLocalDate().minusDays(properties.getReturnLoop().getInviteAfterDays() - 1L).atStartOfDay(zone).toInstant();
        for (GuestVisit visit : visits.inStagesWithDayBefore(List.of(JourneyStage.AFTER), inviteBefore, BATCH)) {
            if (visit.chatId() == null || visit.returnedVisitId() != null || visits.hasEvent(visit.id(), VisitEventKind.INVITED)
                    || visits.hasLaterVisit(visit.chatId(), visit.guestPhone(), visit.openedAt(), visit.id())) {
                continue;
            }
            if (policy.permits(visit.reservationId(), visit.chatId(), ProactiveMessage.INVITE)) {
                send(visit, inviteText(visit, now.toLocalDate()));
                record(visit, VisitEventKind.INVITED);
            }
        }
    }

    private String afterText(GuestVisit visit) {
        Optional<String> waiter = guests.of(visit.reservationId()).map(Guest::waiter).filter(name -> !name.isBlank());
        if (waiter.isEmpty()) {
            return "Спасибо за отзыв — передали команде AERIS. Хорошего дня!";
        }
        String what = visits.hasEvent(visit.id(), VisitEventKind.TIP) ? "ваш отзыв и чаевые" : "ваш отзыв";
        return waiter.get() + " получил " + what + " — спасибо, ему очень приятно. Хорошего дня!";
    }

    private String inviteText(GuestVisit visit, LocalDate today) {
        String name = guests.of(visit.reservationId()).map(Guest::name).filter(n -> !n.isBlank()).map(n -> n.trim().split("\\s+")[0]).orElse("");
        String hello = name.isEmpty() ? "Давно не виделись!" : name + ", давно не виделись!";
        return hello + " Собрать «как в прошлый раз» на " + nextLunchDay(today) + "? Просто ответьте «как в прошлый раз».";
    }

    static String nextLunchDay(LocalDate today) {
        LocalDate day = today.plusDays(1);
        while (day.getDayOfWeek() == DayOfWeek.SATURDAY || day.getDayOfWeek() == DayOfWeek.SUNDAY) {
            day = day.plusDays(1);
        }
        return switch (day.getDayOfWeek()) {
            case MONDAY -> "понедельник";
            case TUESDAY -> "вторник";
            case WEDNESDAY -> "среду";
            case THURSDAY -> "четверг";
            default -> "пятницу";
        };
    }

    private void send(GuestVisit visit, String text) {
        try {
            guest.send(visit.chatId(), text);
        } catch (RuntimeException e) {
            log.warn("Return loop message not sent: visit={}, reason={}", visit.id(), e.toString());
        }
    }

    private void record(GuestVisit visit, VisitEventKind kind) {
        recorder.record(visit.reservationId(), visit.chatId(), visit.guestPhone(), visit.venueCode(), visit.visitDay(), kind, JourneyEvents.BOT, null);
    }
}
