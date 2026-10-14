package museon_online.astor_butler.domain.journey;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** The usefulness filter for bot-first messages, switched by {@code ASTOR_USEFULNESS_POLICY_ENABLED} (off by default). */
@Component
@ConfigurationProperties(prefix = "astor.guest-journey.usefulness")
@Getter
@Setter
public class UsefulnessPolicyProperties {
    private boolean enabled = false;
}
