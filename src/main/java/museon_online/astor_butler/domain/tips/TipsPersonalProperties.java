package museon_online.astor_butler.domain.tips;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.ZoneId;

/** Off by default: the staff commands are unknown and the tip recipient is the venue's default one, as before. */
@Component
@ConfigurationProperties(prefix = "astor.tips.personal")
@Getter
@Setter
public class TipsPersonalProperties {

    /** Tips go to the waiter of the table when the manager assigned one and the waiter has a tip link. */
    private boolean enabled = false;

    /** The tenant of the staff directory ({@code astor_staff_members}) the waiters and assignments live in. */
    private String tenant = "AERIS";

    /** The venue's clock: a table is assigned for a shift day counted in this zone. */
    private String timezone = "Asia/Yekaterinburg";

    public ZoneId zone() {
        try {
            return ZoneId.of(timezone == null || timezone.isBlank() ? "Asia/Yekaterinburg" : timezone.trim());
        } catch (RuntimeException e) {
            return ZoneId.of("Asia/Yekaterinburg");
        }
    }

    public String tenantOrDefault() {
        return tenant == null || tenant.isBlank() ? "AERIS" : tenant.trim();
    }
}
