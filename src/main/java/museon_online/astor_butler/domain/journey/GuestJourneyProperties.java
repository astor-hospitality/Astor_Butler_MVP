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

    /**
     * The startup warning of a feature that needs the journey's visits (the agent, the capabilities): with the journey
     * off no visit is opened, so bookings link to nothing, visit_status and the board stay empty.
     */
    public java.util.Optional<String> prerequisiteWarning(String featureEnv, boolean featureEnabled) {
        if (!featureEnabled || enabled) {
            return java.util.Optional.empty();
        }
        return java.util.Optional.of(featureEnv + "=true needs ASTOR_GUEST_JOURNEY_ENABLED=true (astor.guest-journey.enabled): "
                + "without it no visit is opened, bookings link to no visit and visit_status finds nothing");
    }

    @Getter
    @Setter
    public static class ReturnLoop {
        private boolean enabled = false;
        private int inviteAfterDays = 7;
        private int windowDays = 30;
    }
}
