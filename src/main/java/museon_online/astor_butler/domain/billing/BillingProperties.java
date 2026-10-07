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
}
