package museon_online.astor_butler.domain.web;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.postgresql.util.PGobject;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

@Repository
@RequiredArgsConstructor
@Slf4j
public class WebSessionRepository {

    private static final long WEB_CHAT_ID_BASE = 9_000_000_000_000L;
    private static final long WEB_CHAT_ID_RANGE = 900_000_000_000L;
    /** How many salted re-hashes to try when a synthetic chat id is already taken by another session. */
    static final int MAX_CHAT_ID_ATTEMPTS = 8;

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public WebSessionResolution upsert(
            String siteCode,
            String sessionId,
            String externalUserId,
            Long requestedChatId,
            String referrer,
            String landingPage,
            String userAgentHash,
            Map<String, Object> metadata
    ) {
        String safeSessionId = requireSessionId(sessionId);
        String safeSiteCode = siteCode == null || siteCode.isBlank() ? "c3flex" : siteCode.trim().toLowerCase();
        String safeExternalUserId = externalUserId == null || externalUserId.isBlank()
                ? "web:anon:" + safeSessionId
                : externalUserId.trim();

        for (int attempt = 0; ; attempt++) {
            Long chatId = requestedChatId == null ? stableChatId(safeSessionId, attempt) : requestedChatId;
            try {
                return insertOrTouch(safeSiteCode, safeSessionId, safeExternalUserId, chatId, referrer, landingPage, userAgentHash, metadata);
            } catch (DuplicateKeyException e) {
                // The session_id arbiter did not fire, so this is the chat_id UNIQUE index: another session owns the
                // hash. Re-derive with a salt; once stored, later upserts conflict on session_id and keep the stored id.
                if (requestedChatId != null || attempt + 1 >= MAX_CHAT_ID_ATTEMPTS) {
                    throw e;
                }
                log.warn("Web session chat id collision: sessionId={}, attempt={}", safeSessionId, attempt + 1);
            }
        }
    }

    private WebSessionResolution insertOrTouch(
            String safeSiteCode,
            String safeSessionId,
            String safeExternalUserId,
            Long safeChatId,
            String referrer,
            String landingPage,
            String userAgentHash,
            Map<String, Object> metadata
    ) {
        return jdbcTemplate.queryForObject("""
                INSERT INTO web_sessions (
                    id, session_id, site_code, external_user_id, chat_id,
                    referrer, landing_page, user_agent_hash, metadata_json,
                    first_seen_at, last_seen_at
                )
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                ON CONFLICT (session_id)
                DO UPDATE SET
                    site_code = EXCLUDED.site_code,
                    external_user_id = EXCLUDED.external_user_id,
                    last_seen_at = CURRENT_TIMESTAMP,
                    referrer = COALESCE(EXCLUDED.referrer, web_sessions.referrer),
                    landing_page = COALESCE(EXCLUDED.landing_page, web_sessions.landing_page),
                    user_agent_hash = COALESCE(EXCLUDED.user_agent_hash, web_sessions.user_agent_hash),
                    metadata_json = web_sessions.metadata_json || EXCLUDED.metadata_json
                RETURNING id, session_id, external_user_id, chat_id, (xmax = 0) AS created
                """,
                (rs, rowNum) -> new WebSessionResolution(
                        rs.getObject("id", UUID.class),
                        rs.getString("session_id"),
                        rs.getString("external_user_id"),
                        rs.getLong("chat_id"),
                        rs.getBoolean("created")
                ),
                UUID.randomUUID(),
                safeSessionId,
                safeSiteCode,
                safeExternalUserId,
                safeChatId,
                blankToNull(referrer),
                blankToNull(landingPage),
                blankToNull(userAgentHash),
                jsonb(metadata)
        );
    }

    public void appendMessage(WebSessionResolution session, String correlationId, String direction,
                              String text, Map<String, Object> payload) {
        if (session == null || session.id() == null) {
            return;
        }
        jdbcTemplate.update("""
                INSERT INTO web_messages (
                    id, web_session_id, correlation_id, direction, text, payload_json, created_at
                )
                VALUES (?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP)
                """,
                UUID.randomUUID(),
                session.id(),
                blankToNull(correlationId),
                direction,
                text,
                jsonb(payload)
        );
    }

    public void upsertConsentIfPresent(WebSessionResolution session, Map<String, Object> payload) {
        if (session == null || session.id() == null || payload == null) {
            return;
        }
        Object consentValue = payload.get("consent");
        if (!(consentValue instanceof Map<?, ?> consent)) {
            return;
        }
        if (!Boolean.parseBoolean(String.valueOf(consent.get("privacyAccepted")))) {
            return;
        }

        String policyVersion = string(consent, "policyVersion");
        if (policyVersion == null || policyVersion.isBlank()) {
            policyVersion = "2026-08-01-c3ag-web";
        }

        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("sessionId", session.sessionId());
        evidence.put("externalUserId", session.externalUserId());
        evidence.put("chatId", session.chatId());
        evidence.put("site", payload.get("site"));
        evidence.put("page", payload.get("page"));
        evidence.put("referrer", payload.get("referrer"));
        evidence.put("acceptedAt", consent.get("acceptedAt"));
        evidence.put("policyVersion", policyVersion);
        evidence.put("consent", consent);

        jdbcTemplate.update("""
                INSERT INTO web_consents (
                    id, web_session_id, consent_type, policy_version, status, source,
                    evidence_json, granted_at, updated_at
                )
                VALUES (?, ?, 'PRIVACY_POLICY', ?, 'GRANTED', 'WEB_CHAT', ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                ON CONFLICT (web_session_id, consent_type, policy_version)
                DO UPDATE SET
                    status = 'GRANTED',
                    source = EXCLUDED.source,
                    evidence_json = EXCLUDED.evidence_json,
                    granted_at = COALESCE(web_consents.granted_at, EXCLUDED.granted_at),
                    revoked_at = NULL,
                    updated_at = CURRENT_TIMESTAMP
                """,
                UUID.randomUUID(),
                session.id(),
                policyVersion,
                jsonb(evidence)
        );
    }

    static Long stableChatId(String sessionId, int attempt) {
        String seed = attempt == 0 ? "web-session:" + sessionId : "web-session:" + sessionId + ":collision:" + attempt;
        UUID uuid = UUID.nameUUIDFromBytes(seed.getBytes(StandardCharsets.UTF_8));
        long mixed = uuid.getMostSignificantBits() ^ uuid.getLeastSignificantBits();
        return WEB_CHAT_ID_BASE + Math.floorMod(mixed, WEB_CHAT_ID_RANGE);
    }

    private String requireSessionId(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            throw new IllegalArgumentException("WEB sessionId is required");
        }
        return sessionId.trim();
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private String string(Map<?, ?> payload, String key) {
        Object value = payload == null ? null : payload.get(key);
        return value == null ? null : value.toString();
    }

    private PGobject jsonb(Map<String, Object> value) {
        PGobject object = new PGobject();
        object.setType("jsonb");
        try {
            object.setValue(objectMapper.writeValueAsString(value == null ? Map.of() : value));
            return object;
        } catch (SQLException | JsonProcessingException e) {
            throw new IllegalStateException("Cannot serialize web session payload", e);
        }
    }
}
