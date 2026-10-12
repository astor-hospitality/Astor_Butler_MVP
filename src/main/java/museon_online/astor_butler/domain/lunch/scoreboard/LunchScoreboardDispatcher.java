package museon_online.astor_butler.domain.lunch.scoreboard;

import lombok.extern.slf4j.Slf4j;
import museon_online.astor_butler.domain.booking.TableReservationNotificationService;
import museon_online.astor_butler.domain.lunch.BusinessLunchCatalog;
import museon_online.astor_butler.domain.lunch.BusinessLunchOffer;
import museon_online.astor_butler.domain.shift.ShiftBriefingProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Optional;

/**
 * Sends the day's «📊 Табло ланча» to the staff chat — the chat the hostess cards go to — once the venue clock has
 * passed {@code send-at}, on lunch days only (the days of the venue's lunch offer; Monday to Friday when the offer
 * is unknown). {@link #run()} is called by the clock every minute and is safe to call as often as it ticks: the day
 * is claimed in {@link LunchScoreboardSentMarker} before anything is built, so the first tick after a restart that
 * missed 16:30 still sends, and the second never does.
 */
@Service
@Slf4j
public class LunchScoreboardDispatcher {

    public enum Outcome { OFF, NOT_A_LUNCH_DAY, NOT_YET, NO_STAFF_CHAT, ALREADY_SENT, SENT, FAILED }

    private final LunchScoreboardProperties properties;
    private final LunchScoreboardService service;
    private final LunchScoreboardFormatter formatter;
    private final LunchScoreboardSentMarker marker;
    private final TableReservationNotificationService notifications;
    private final BusinessLunchCatalog catalog;
    private final ShiftBriefingProperties venue;
    private final String hostessChatId;
    private Clock clock = Clock.systemUTC();

    @Autowired
    public LunchScoreboardDispatcher(LunchScoreboardProperties properties, LunchScoreboardService service,
                                     LunchScoreboardFormatter formatter, LunchScoreboardSentMarker marker,
                                     TableReservationNotificationService notifications, BusinessLunchCatalog catalog,
                                     ShiftBriefingProperties venue,
                                     @Value("${telegram.booking.hostess-chat-id:}") String hostessChatId) {
        this.properties = properties;
        this.service = service;
        this.formatter = formatter;
        this.marker = marker;
        this.notifications = notifications;
        this.catalog = catalog;
        this.venue = venue;
        this.hostessChatId = hostessChatId;
    }

    public synchronized Outcome run() {
        if (!properties.isEnabled()) {
            return Outcome.OFF;
        }
        ZoneId zone = venue.zone();
        Instant now = clock.instant();
        LocalDate today = LocalDate.ofInstant(now, zone);
        if (!lunchDay(today)) {
            return Outcome.NOT_A_LUNCH_DAY;
        }
        if (now.isBefore(today.atTime(properties.sendAt()).atZone(zone).toInstant())) {
            return Outcome.NOT_YET;
        }
        if (hostessChatId == null || hostessChatId.isBlank()) {
            log.debug("Lunch scoreboard for {} not sent: no staff chat (telegram.booking.hostess-chat-id)", today);
            return Outcome.NO_STAFF_CHAT;
        }
        if (!marker.claim(today)) {
            return Outcome.ALREADY_SENT;
        }
        try {
            LunchScoreboard board = service.build(today);
            notifications.sendStaffNotice(hostessChatId, formatter.format(board));
            log.info("Lunch scoreboard for {} sent to the staff chat: lunches={}, visits={}, reviews={}",
                    today, board.lunches(), board.visits(), board.reviews().answered());
            return Outcome.SENT;
        } catch (RuntimeException e) {
            // Not retried: the day is claimed, and a table that arrives an hour late with a twin is worse than /табло by hand.
            log.warn("Lunch scoreboard for {} failed: {}", today, e.toString());
            return Outcome.FAILED;
        }
    }

    /** The days the venue serves lunch; Monday to Friday when the menu file says nothing. */
    boolean lunchDay(LocalDate date) {
        Optional<BusinessLunchOffer> offer = catalog.find(venue.getVenueCode());
        if (offer.isPresent() && offer.get().days() != null && !offer.get().days().isEmpty()) {
            return offer.get().days().contains(date.getDayOfWeek());
        }
        DayOfWeek day = date.getDayOfWeek();
        return day != DayOfWeek.SATURDAY && day != DayOfWeek.SUNDAY;
    }

    void setClock(Clock clock) {
        this.clock = clock;
    }
}
