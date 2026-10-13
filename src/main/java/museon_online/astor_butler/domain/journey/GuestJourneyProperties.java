package museon_online.astor_butler.domain.journey;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** The guest journey ledger, switched by {@code ASTOR_GUEST_JOURNEY_ENABLED} (off by default). */
@Component
@ConfigurationProperties(prefix = "astor.guest-journey")
@Getter
@Setter
public class GuestJourneyProperties {

    private boolean enabled = false;
    private String zoneId = "Asia/Yekaterinburg";
    private ReturnLoop returnLoop = new ReturnLoop();

    @Getter
    @Setter
    public static class ReturnLoop {
        private boolean enabled = false;
        private int inviteAfterDays = 7;
        private int windowDays = 30;
    }
}
