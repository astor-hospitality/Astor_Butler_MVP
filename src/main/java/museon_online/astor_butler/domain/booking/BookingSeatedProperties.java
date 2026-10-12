package museon_online.astor_butler.domain.booking;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Off by default: the confirmed hostess card reads exactly as before and nothing is ever SEATED by a button (#151).
 * On, the card carries «🪑 Гость сел», which marks the moment the guest sat down. The Saby side of the same status
 * is switched on separately, by {@code ASTOR_SABY_SEATED_STATES}.
 */
@Component
@ConfigurationProperties(prefix = "astor.booking.seated")
@Getter
@Setter
public class BookingSeatedProperties {

    /** Show «🪑 Гость сел» on the confirmed hostess card and accept the press. */
    private boolean enabled = false;
}
