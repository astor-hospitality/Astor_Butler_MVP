package museon_online.astor_butler.domain.booking;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;

/**
 * When the morning reconfirmation goes out, for which venue, and how close to the visit it stops making sense.
 * Off until `astor.booking.morning-check.enabled` says otherwise, so turning the feature on is a separate step
 * from deploying it (#149).
 */
@Component
public class BookingMorningCheckProperties {

    static final LocalTime DEFAULT_TIME = LocalTime.of(10, 30);
    static final ZoneId DEFAULT_ZONE = ZoneId.of("Asia/Yekaterinburg");

    private final boolean enabled;
    private final String time;
    private final int minLeadMinutes;
    private final String venueCode;
    private final String timezone;

    public BookingMorningCheckProperties(
            @Value("${astor.booking.morning-check.enabled:false}") boolean enabled,
            @Value("${astor.booking.morning-check.time:10:30}") String time,
            @Value("${astor.booking.morning-check.min-lead-minutes:90}") int minLeadMinutes,
            @Value("${astor.booking.morning-check.venue-code:AERIS}") String venueCode,
            @Value("${astor.booking.morning-check.timezone:Asia/Yekaterinburg}") String timezone) {
        this.enabled = enabled;
        this.time = time;
        this.minLeadMinutes = minLeadMinutes;
        this.venueCode = venueCode;
        this.timezone = timezone;
    }

    public boolean isEnabled() {
        return enabled;
    }

    /** The venue-clock time after which today's guests are asked; an unreadable value falls back to 10:30. */
    public LocalTime sendTime() {
        if (time == null || time.isBlank()) {
            return DEFAULT_TIME;
        }
        try {
            return LocalTime.parse(time.trim());
        } catch (DateTimeParseException e) {
            return DEFAULT_TIME;
        }
    }

    /** A booking that starts sooner than this after the send is not asked about: the guest is already on the way. */
    public Duration minLead() {
        return Duration.ofMinutes(Math.max(0, minLeadMinutes));
    }

    public String venueCode() {
        return venueCode == null || venueCode.isBlank() ? "AERIS" : venueCode.trim().toUpperCase();
    }

    public ZoneId zone() {
        try {
            return ZoneId.of(timezone);
        } catch (RuntimeException e) {
            return DEFAULT_ZONE;
        }
    }
}
