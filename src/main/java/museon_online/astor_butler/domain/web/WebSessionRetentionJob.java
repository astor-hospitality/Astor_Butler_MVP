package museon_online.astor_butler.domain.web;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Keeps the web audit trail bounded: sessions not seen for N days go away (messages and consents cascade),
 * and old messages of still-active sessions are trimmed too. Sized for Postgres; one statement per table.
 */
@Component
@Slf4j
public class WebSessionRetentionJob {

    private final JdbcTemplate jdbcTemplate;

    @Value("${astor.web.retention.enabled:true}")
    private boolean enabled;

    @Value("${astor.web.retention.days:30}")
    private int days;

    public WebSessionRetentionJob(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Scheduled(
            fixedDelayString = "${astor.web.retention.fixed-delay-ms:3600000}",
            initialDelayString = "${astor.web.retention.initial-delay-ms:300000}"
    )
    public void purge() {
        try {
            purgeNow();
        } catch (RuntimeException e) {
            log.warn("Web session retention run failed: {}", e.getMessage());
        }
    }

    /** Deletes expired rows and returns how many were removed. */
    public int purgeNow() {
        if (!enabled || days <= 0) {
            return 0;
        }
        int messages = jdbcTemplate.update(
                "DELETE FROM web_messages WHERE created_at < CURRENT_TIMESTAMP - make_interval(days => ?)",
                days
        );
        int sessions = jdbcTemplate.update(
                "DELETE FROM web_sessions WHERE last_seen_at < CURRENT_TIMESTAMP - make_interval(days => ?)",
                days
        );
        if (messages > 0 || sessions > 0) {
            log.info("Web session retention: removed {} messages and {} sessions older than {} days", messages, sessions, days);
        }
        return messages + sessions;
    }
}
