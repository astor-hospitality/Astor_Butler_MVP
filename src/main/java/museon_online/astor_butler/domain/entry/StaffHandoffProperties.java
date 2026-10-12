package museon_online.astor_butler.domain.entry;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Locale;

/**
 * The staff member's personal guest link and the entry log (#128). Off until {@code astor.staff-handoff.enabled}
 * says otherwise: with the flag off the {@code _h} / {@code h_} start parameters are ignored exactly as before and
 * {@code /ссылка} is an unknown command, so turning the feature on is a separate step from deploying it.
 */
@Component
public class StaffHandoffProperties {

    static final int DEFAULT_SESSION_HOURS = 6;

    private final boolean enabled;
    private final String tenant;
    private final int sessionHours;
    private final String botUsername;
    private final String venueCode;

    public StaffHandoffProperties(
            @Value("${astor.staff-handoff.enabled:false}") boolean enabled,
            @Value("${astor.staff-handoff.tenant:}") String tenant,
            @Value("${astor.staff-handoff.session-hours:6}") int sessionHours,
            @Value("${telegram.bot.username:astor_butler_bot}") String botUsername,
            @Value("${astor.business-lunch.default-venue-code:AERIS}") String venueCode) {
        this.enabled = enabled;
        this.tenant = tenant == null ? "" : tenant.trim();
        this.sessionHours = sessionHours;
        this.botUsername = botUsername == null ? "" : botUsername.trim().replaceFirst("^@", "");
        this.venueCode = venueCode == null || venueCode.isBlank() ? "AERIS" : venueCode.trim();
    }

    public boolean isEnabled() {
        return enabled;
    }

    /** The tenant of {@code astor_staff_members} the display names are read from; blank means any tenant. */
    public String tenant() {
        return tenant;
    }

    /** How long after the entry a request of that guest still carries «Привёл(а)» on the hostess card. */
    public Duration sessionWindow() {
        return Duration.ofHours(sessionHours > 0 ? sessionHours : DEFAULT_SESSION_HOURS);
    }

    /** The bot the link opens, without the {@code @}. */
    public String botUsername() {
        return botUsername;
    }

    /** The venue code as the lunch link spells it: {@code lunch_aeris…}. */
    public String venueCode() {
        return venueCode.toLowerCase(Locale.ROOT);
    }
}
