package museon_online.astor_butler.domain.billing;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** Both switches are off by default: with them off nothing is written and no call is observed. */
@Component
@ConfigurationProperties(prefix = "astor.billing")
@Getter
@Setter
public class BillingProperties {

    /** Keep a bill and its journal for every order Butler places. */
    private boolean enabled = false;

    /** Journal every HTTP call to Saby: method, path, status, duration. */
    private boolean sabyCallLogEnabled = false;

    /** How often confirmed bookings are checked for a payment link to hand the guest. */
    private long paymentPromptDelayMs = 30_000;

    /** How long after the visit ends the guest is asked how it went, unless the venue closed the order earlier. */
    private long reviewDelayMinutes = 60;

    /** Visits that ended longer ago than this are not asked about any more. */
    private long reviewLookbackHours = 24;
}
