package museon_online.astor_butler.model;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.postgresql.util.PGobject;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.SQLException;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;

@Repository
@RequiredArgsConstructor
@Slf4j
public class ModelInteractionAuditRepository {

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public void capture(ModelInteractionAuditRecord record) {
        if (record == null || isBlank(record.prompt())) {
            return;
        }
        try {
            jdbcTemplate.update("""
                    INSERT INTO model_interaction_audit (
                        interaction_id, venue_code, channel, chat_id, telegram_user_id, correlation_id,
                        scenario, state, purpose, provider, model, profile, prompt, guest_text,
                        approved_fallback, response_text, generated, fallback_used, success,
                        error_type, error_message, latency_ms, metadata, created_at
                    )
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP)
                    """,
                    UUID.randomUUID(),
                    blankToDefault(record.venueCode(), "AERIS"),
                    blankToNull(record.channel()),
                    record.chatId(),
                    record.telegramUserId(),
                    blankToNull(record.correlationId()),
                    blankToNull(record.scenario()),
                    blankToNull(record.state()),
                    blankToNull(record.purpose()),
                    blankToNull(record.provider()),
                    blankToNull(record.model()),
                    blankToNull(record.profile()),
                    record.prompt(),
                    blankToNull(record.guestText()),
                    blankToNull(record.approvedFallback()),
                    blankToNull(record.responseText()),
                    record.generated(),
                    record.fallbackUsed(),
                    record.success(),
                    blankToNull(record.errorType()),
                    blankToNull(record.errorMessage()),
                    latencyMs(record.latency()),
                    jsonb(record.metadata())
            );
        } catch (RuntimeException ex) {
            log.warn("Model interaction audit was not stored: {}", ex.toString());
        }
    }

    private Long latencyMs(Duration latency) {
        return latency == null ? null : latency.toMillis();
    }

    private String blankToDefault(String value, String fallback) {
        return isBlank(value) ? fallback : value.trim();
    }

    private String blankToNull(String value) {
        return isBlank(value) ? null : value.trim();
    }

    private boolean isBlank(String value) {
        return value == null || value.trim().isBlank();
    }

    private PGobject jsonb(Map<String, ?> value) {
        PGobject object = new PGobject();
        object.setType("jsonb");
        try {
            object.setValue(objectMapper.writeValueAsString(value == null ? Map.of() : value));
            return object;
        } catch (SQLException | JsonProcessingException e) {
            throw new IllegalStateException("Cannot serialize model audit metadata", e);
        }
    }
}
