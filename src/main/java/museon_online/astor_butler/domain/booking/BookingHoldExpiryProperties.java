package museon_online.astor_butler.domain.booking;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Off by default: with it off a request the hostess never answers keeps its table for as long as it takes, exactly
 * as before (#87). On, such a request expires after {@link #ttlMinutes}: the table is released, the guest is told,
 * the card goes to the manager.
 */
@Component
@ConfigurationProperties(prefix = "astor.booking.hold-expiry")
@Getter
@Setter
public class BookingHoldExpiryProperties {

    /** Expire requests the hostess did not answer within the TTL. */
    private boolean enabled = false;

    /** Minutes the hostess has before an unanswered request expires and its table is released. */
    private int ttlMinutes = 15;

    /** How often, in seconds, unanswered requests are looked at. */
    private long checkSeconds = 60;

    /** Pause, in seconds, after start-up before the first look. */
    private long initialDelaySeconds = 60;

    /** Never less than a minute, whatever the configuration says. */
    public int ttlMinutes() {
        return Math.max(1, ttlMinutes);
    }
}
