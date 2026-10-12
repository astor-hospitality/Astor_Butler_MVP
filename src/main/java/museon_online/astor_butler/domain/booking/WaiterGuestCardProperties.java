package museon_online.astor_butler.domain.booking;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Off by default: a booking that becomes SEATED (#151) changes nothing for the waiter. On (#163), the staff chat gets
 * «🧾 Стол N — гость сел» — the guest card built from the guest's own words and orders in the bot — under the hostess
 * card of the booking. Switched by {@code ASTOR_WAITER_GUEST_CARD_ENABLED}.
 */
@Component
@ConfigurationProperties(prefix = "astor.booking.waiter-guest-card")
@Getter
@Setter
public class WaiterGuestCardProperties {

    /** Send the guest card to the staff chat when a booking becomes SEATED. */
    private boolean enabled = false;
}
