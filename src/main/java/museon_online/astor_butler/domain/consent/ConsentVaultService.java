package museon_online.astor_butler.domain.consent;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import museon_online.astor_butler.service.message.IncomingMessage;
import museon_online.astor_butler.service.message.MessageChannel;
import org.postgresql.util.PGobject;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class ConsentVaultService {

    public static final String PRIVACY_POLICY = "PRIVACY_POLICY";
    public static final String CURRENT_POLICY_VERSION = "2026-06-02-local";
    /** Consent given in the MAX bot: same table and policy, no Telegram id, keyed by the internal MAX chat id. */
    public static final String MAX_CONTACT_SOURCE = "MAX_CONTACT_FLOW";

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public void grantPrivacyPolicyFromTelegramContact(IncomingMessage incoming) {
        if (incoming == null || incoming.telegramUserId() == null) {
            return;
        }

        Map<String, Object> evidence = Map.of(
                "channel", incoming.channel().name(),
                "chatId", incoming.chatId(),
                "telegramUserId", incoming.telegramUserId(),
                "messageId", incoming.telegramMessageId() == null ? "" : incoming.telegramMessageId(),
                "updateId", incoming.telegramUpdateId() == null ? "" : incoming.telegramUpdateId(),
                "contactPhonePresent", incoming.contactPhone() != null && !incoming.contactPhone().isBlank(),
                "correlationId", incoming.correlationId() == null ? "" : incoming.correlationId()
        );

        Long userId = findUserIdByTelegramUserId(incoming.telegramUserId());
        jdbcTemplate.update("""
                INSERT INTO user_consents (
                    id, user_id, telegram_user_id, chat_id, consent_type, policy_version, status,
                    source, evidence, granted_at, updated_at
                )
                VALUES (?, ?, ?, ?, ?, ?, 'GRANTED', ?, ?, ?, CURRENT_TIMESTAMP)
                ON CONFLICT ON CONSTRAINT uq_user_consents_telegram_policy
                DO UPDATE SET
                    user_id = COALESCE(user_consents.user_id, EXCLUDED.user_id),
                    chat_id = EXCLUDED.chat_id,
                    status = 'GRANTED',
                    source = EXCLUDED.source,
                    evidence = EXCLUDED.evidence,
                    granted_at = EXCLUDED.granted_at,
                    revoked_at = NULL,
                    updated_at = CURRENT_TIMESTAMP
                """,
                UUID.randomUUID(),
                userId,
                incoming.telegramUserId(),
                incoming.chatId(),
                PRIVACY_POLICY,
                CURRENT_POLICY_VERSION,
                "TELEGRAM_CONTACT_FLOW",
                jsonb(evidence),
                OffsetDateTime.ofInstant(Instant.now(), ZoneOffset.UTC)
        );

        log.info(
                "Consent granted: userId={}, telegramUserId={}, type={}, version={}",
                userId,
                incoming.telegramUserId(),
                PRIVACY_POLICY,
                CURRENT_POLICY_VERSION
        );
    }

    public boolean hasGrantedPrivacyPolicy(Long telegramUserId) {
        if (telegramUserId == null) {
            return false;
        }
        Boolean granted = jdbcTemplate.query("""
                SELECT EXISTS (
                    SELECT 1
                    FROM user_consents
                    WHERE telegram_user_id = ?
                      AND consent_type = ?
                      AND policy_version = ?
                      AND status = 'GRANTED'
                      AND revoked_at IS NULL
                )
                """,
                resultSet -> resultSet.next() && resultSet.getBoolean(1),
                telegramUserId,
                PRIVACY_POLICY,
                CURRENT_POLICY_VERSION
        );
        return Boolean.TRUE.equals(granted);
    }

    /**
     * The MAX counterpart of {@link #grantPrivacyPolicyFromTelegramContact}: the guest pressed the contact button in
     * the MAX bot. The row has no telegram_user_id (a MAX id must never land there) and is keyed by the internal
     * dialog chat id, which belongs to exactly one MAX user. Linking to {@code users} comes with the channel-neutral
     * identity (MAX_ADAPTER_PLAN.md, phase 2).
     */
    public void grantPrivacyPolicyFromMaxContact(IncomingMessage incoming) {
        if (incoming == null || incoming.channel() != MessageChannel.MAX || incoming.chatId() == null) {
            return;
        }
        Map<String, Object> payload = incoming.payload() == null ? Map.of() : incoming.payload();
        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("channel", incoming.channel().name());
        evidence.put("chatId", incoming.chatId());
        evidence.put("maxUserId", incoming.externalUserId() == null ? "" : incoming.externalUserId());
        evidence.put("maxChatId", payload.getOrDefault("maxChatId", ""));
        evidence.put("maxMessageId", payload.getOrDefault("maxMessageId", ""));
        evidence.put("contactPhonePresent", incoming.contactPhone() != null && !incoming.contactPhone().isBlank());
        evidence.put("correlationId", incoming.correlationId() == null ? "" : incoming.correlationId());
        OffsetDateTime grantedAt = OffsetDateTime.ofInstant(Instant.now(), ZoneOffset.UTC);

        int updated = jdbcTemplate.update("""
                UPDATE user_consents
                SET status = 'GRANTED',
                    evidence = ?,
                    granted_at = ?,
                    revoked_at = NULL,
                    updated_at = CURRENT_TIMESTAMP
                WHERE telegram_user_id IS NULL
                  AND chat_id = ?
                  AND source = ?
                  AND consent_type = ?
                  AND policy_version = ?
                """,
                jsonb(evidence),
                grantedAt,
                incoming.chatId(),
                MAX_CONTACT_SOURCE,
                PRIVACY_POLICY,
                CURRENT_POLICY_VERSION
        );
        if (updated == 0) {
            jdbcTemplate.update("""
                    INSERT INTO user_consents (
                        id, user_id, telegram_user_id, chat_id, consent_type, policy_version, status,
                        source, evidence, granted_at, updated_at
                    )
                    VALUES (?, NULL, NULL, ?, ?, ?, 'GRANTED', ?, ?, ?, CURRENT_TIMESTAMP)
                    """,
                    UUID.randomUUID(),
                    incoming.chatId(),
                    PRIVACY_POLICY,
                    CURRENT_POLICY_VERSION,
                    MAX_CONTACT_SOURCE,
                    jsonb(evidence),
                    grantedAt
            );
        }
        log.info("Consent granted: channel=MAX, chatId={}, type={}, version={}",
                incoming.chatId(), PRIVACY_POLICY, CURRENT_POLICY_VERSION);
    }

    public boolean hasGrantedMaxPrivacyPolicy(Long internalChatId) {
        if (internalChatId == null) {
            return false;
        }
        Boolean granted = jdbcTemplate.query("""
                SELECT EXISTS (
                    SELECT 1
                    FROM user_consents
                    WHERE telegram_user_id IS NULL
                      AND chat_id = ?
                      AND source = ?
                      AND consent_type = ?
                      AND policy_version = ?
                      AND status = 'GRANTED'
                      AND revoked_at IS NULL
                )
                """,
                resultSet -> resultSet.next() && resultSet.getBoolean(1),
                internalChatId,
                MAX_CONTACT_SOURCE,
                PRIVACY_POLICY,
                CURRENT_POLICY_VERSION
        );
        return Boolean.TRUE.equals(granted);
    }

    private Long findUserIdByTelegramUserId(Long telegramUserId) {
        return jdbcTemplate.query("""
                SELECT user_id
                FROM telegram_profiles
                WHERE telegram_user_id = ?
                """,
                resultSet -> resultSet.next() ? resultSet.getObject("user_id", Long.class) : null,
                telegramUserId
        );
    }

    private PGobject jsonb(Map<String, Object> value) {
        PGobject object = new PGobject();
        object.setType("jsonb");
        try {
            object.setValue(objectMapper.writeValueAsString(value == null ? Map.of() : value));
            return object;
        } catch (SQLException | JsonProcessingException e) {
            throw new IllegalStateException("Cannot serialize consent evidence", e);
        }
    }
}
