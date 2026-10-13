package museon_online.astor_butler.domain.journey.talk;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** The butler's talk (venue facts, nutrition, table requests, seated hint), switched by {@code ASTOR_BUTLER_TALK_ENABLED}. */
@Component
@ConfigurationProperties(prefix = "astor.guest-journey.talk")
@Getter
@Setter
public class ButlerTalkProperties {
    private boolean enabled = false;
    /** The staff directory tenant whose table assignments name the waiter. */
    private String staffTenant = "astor";
    private String firstDishMinutes = "7–10";
}
