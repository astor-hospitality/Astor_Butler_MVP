package museon_online.astor_butler.domain.ledger;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** The visit ledger, switched by {@code ASTOR_LEDGER_ENABLED} (off by default). */
@Component
@ConfigurationProperties(prefix = "astor.ledger")
@Getter
@Setter
public class LedgerProperties {

    private boolean enabled = false;
}
