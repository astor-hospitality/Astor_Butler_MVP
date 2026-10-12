package museon_online.astor_butler.domain.lunch.scoreboard;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.LocalTime;
import java.time.format.DateTimeParseException;

/**
 * Whether the lunch scoreboard (#175) is on and when it goes to the staff chat. Off until
 * {@code astor.business-lunch.scoreboard.enabled} says otherwise: turning it on is a separate step from deploying it.
 * The venue and its clock are the shift briefing's ({@code astor.shift.briefing.venue-code} / {@code .timezone}): the
 * scoreboard reads the same tables for the same venue.
 */
@Component
public class LunchScoreboardProperties {

    static final LocalTime DEFAULT_SEND_AT = LocalTime.of(16, 30);

    private final boolean enabled;
    private final String sendAt;

    public LunchScoreboardProperties(
            @Value("${astor.business-lunch.scoreboard.enabled:false}") boolean enabled,
            @Value("${astor.business-lunch.scoreboard.send-at:16:30}") String sendAt) {
        this.enabled = enabled;
        this.sendAt = sendAt;
    }

    public boolean isEnabled() {
        return enabled;
    }

    /** The venue-clock time after which the day's table is sent; an unreadable value falls back to 16:30. */
    public LocalTime sendAt() {
        if (sendAt == null || sendAt.isBlank()) {
            return DEFAULT_SEND_AT;
        }
        try {
            return LocalTime.parse(sendAt.trim());
        } catch (DateTimeParseException e) {
            return DEFAULT_SEND_AT;
        }
    }
}
