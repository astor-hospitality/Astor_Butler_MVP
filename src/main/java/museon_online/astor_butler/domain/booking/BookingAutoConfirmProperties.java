package museon_online.astor_butler.domain.booking;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Off by default: with it off the hostess card waits for a button for as long as it takes, exactly as before.
 * On, an unanswered card confirms itself after {@link #timeoutMinutes}, unless the request is one a person has to see.
 */
@Component
@ConfigurationProperties(prefix = "astor.booking.auto-confirm")
@Getter
@Setter
public class BookingAutoConfirmProperties {

    /** Confirm an unanswered hostess card by itself after the timeout. */
    private boolean enabled = false;

    /** Minutes the hostess has before the card confirms itself or goes to the manager. */
    private int timeoutMinutes = 7;

    /** A party of this many guests or more is never confirmed automatically; 0 switches the rule off. */
    private int maxParty = 6;

    /** Table zones ({@code venue_tables.zone}) never confirmed automatically, for the wish or the chosen table; empty switches the rule off. */
    private List<String> excludeZones = new ArrayList<>(List.of("VIP_ZONE"));

    /** How often unanswered cards are looked at. */
    private long checkIntervalMs = 30_000;

    /** Pause after start-up before the first look. */
    private long initialDelayMs = 60_000;
}
