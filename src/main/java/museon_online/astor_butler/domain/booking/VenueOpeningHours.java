package museon_online.astor_butler.domain.booking;

import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;

/**
 * When the venue serves guests, by weekday. A day reads "12:00-02:00": it opens at noon and closes at two the next
 * morning, so one o'clock on Tuesday night still belongs to Monday's evening. "closed" means no service that day.
 * The defaults are the hours AERIS publishes on aeris.bar, read on 2026-10-06; each day can be overridden with
 * {@code astor.booking.opening-hours.days.<weekday>}.
 */
@Component
@ConfigurationProperties(prefix = "astor.booking.opening-hours")
@Getter
@Setter
@Slf4j
public class VenueOpeningHours {

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm");
    private static final DateTimeFormatter CONFIGURED = DateTimeFormatter.ofPattern("H:mm");

    private boolean enabled = true;

    private Map<DayOfWeek, String> days = new EnumMap<>(Map.of(
            DayOfWeek.MONDAY, "12:00-02:00",
            DayOfWeek.TUESDAY, "12:00-02:00",
            DayOfWeek.WEDNESDAY, "12:00-02:00",
            DayOfWeek.THURSDAY, "12:00-02:00",
            DayOfWeek.FRIDAY, "12:00-04:00",
            DayOfWeek.SATURDAY, "14:00-04:00",
            DayOfWeek.SUNDAY, "14:00-02:00"
    ));

    /** Whether a guest can be seated at this local day and time. Unknown or switched-off hours never refuse a guest. */
    public boolean isOpen(LocalDate date, LocalTime time) {
        if (!enabled || date == null || time == null) {
            return true;
        }
        Service today = service(date.getDayOfWeek());
        if (today.known() && today.open() != null && today.servesOnItsOwnDay(time)) {
            return true;
        }
        Service evening = service(date.minusDays(1).getDayOfWeek());
        if (evening.known() && evening.runsPastMidnight() && time.isBefore(evening.close())) {
            return true;
        }
        return !today.known();
    }

    /**
     * Whether this time is a late hour of the evening that starts on that day: after midnight and before the close.
     * Half past midnight is a late hour of a day that closes at 02:00; noon and three in the morning are not.
     */
    public boolean isLateHourOf(LocalDate eveningOf, LocalTime time) {
        if (!enabled || eveningOf == null || time == null) {
            return false;
        }
        Service evening = service(eveningOf.getDayOfWeek());
        return evening.known() && evening.runsPastMidnight() && time.isBefore(evening.close());
    }

    /** The service that starts on that day, in words: "с 12:00 до 02:00". Empty when the venue is closed or the hours are unknown. */
    public Optional<String> describe(LocalDate date) {
        if (!enabled || date == null) {
            return Optional.empty();
        }
        Service service = service(date.getDayOfWeek());
        if (!service.known() || service.open() == null) {
            return Optional.empty();
        }
        return Optional.of("с " + service.open().format(TIME) + " до " + service.close().format(TIME));
    }

    private Service service(DayOfWeek day) {
        String configured = days == null ? null : days.get(day);
        if (configured == null || configured.isBlank()) {
            return Service.UNKNOWN;
        }
        if ("closed".equalsIgnoreCase(configured.trim())) {
            return Service.CLOSED;
        }
        String[] parts = configured.trim().split("\\s*-\\s*");
        try {
            if (parts.length == 2) {
                return new Service(true, LocalTime.parse(parts[0], CONFIGURED), LocalTime.parse(parts[1], CONFIGURED));
            }
        } catch (DateTimeParseException e) {
            // Falls through to the warning: hours that cannot be read must not refuse guests.
        }
        log.warn("Opening hours for {} are not readable and are ignored: '{}'", day, configured);
        return Service.UNKNOWN;
    }

    /** One day's service. {@code open == null} with {@code known} set means the venue is closed that day. */
    private record Service(boolean known, LocalTime open, LocalTime close) {
        private static final Service UNKNOWN = new Service(false, null, null);
        private static final Service CLOSED = new Service(true, null, null);

        boolean runsPastMidnight() {
            return open != null && !close.isAfter(open);
        }

        boolean servesOnItsOwnDay(LocalTime time) {
            return runsPastMidnight() ? !time.isBefore(open) : !time.isBefore(open) && time.isBefore(close);
        }
    }
}
