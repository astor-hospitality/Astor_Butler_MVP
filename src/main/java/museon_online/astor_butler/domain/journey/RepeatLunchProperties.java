package museon_online.astor_butler.domain.journey;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** «Как в прошлый раз» for a known lunch guest, switched by {@code ASTOR_REPEAT_LUNCH_ENABLED} (off by default). */
@Component
@ConfigurationProperties(prefix = "astor.guest-journey.repeat-lunch")
@Getter
@Setter
public class RepeatLunchProperties {
    private boolean enabled = false;
}
