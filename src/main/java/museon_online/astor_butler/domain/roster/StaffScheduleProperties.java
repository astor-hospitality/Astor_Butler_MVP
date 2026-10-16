package museon_online.astor_butler.domain.roster;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.ZoneId;

/** Off by default: the «График» topic is not touched, /смена is unknown and {@link StaffRoster} knows nobody. */
@Component
@ConfigurationProperties(prefix = "astor.staff.schedule")
@Getter
@Setter
public class StaffScheduleProperties {

    private boolean enabled = false;

    /** The staff group the schedule is posted to. */
    private Long chatId;

    /** The forum topic «График» in that group ({@code message_thread_id}). */
    private Integer threadId = 4;

    /** The tenant of the staff directory ({@code astor_staff_members}) the names are matched against. */
    private String tenant = "AERIS";

    /** The venue's clock: «today» and «now» of the shift are counted in this zone. */
    private String timezone = "Asia/Yekaterinburg";

    /** {@code max_tokens} of the vision answer: a week for eight people is one to two thousand tokens of JSON. */
    private int maxTokens = 2048;

    /** A larger picture is not sent to the model. */
    private long maxImageBytes = 8L * 1024 * 1024;

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

    /** The message is in the schedule topic of the schedule chat. */
    public boolean isScheduleTopic(Long chat, Integer thread) {
        return enabled && chatId != null && chatId.equals(chat) && threadId != null && threadId.equals(thread);
    }

    public boolean isScheduleChat(Long chat) {
        return enabled && chatId != null && chatId.equals(chat);
    }
}
