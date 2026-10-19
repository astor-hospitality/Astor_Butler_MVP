package museon_online.astor_butler.domain.telegram;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import museon_online.astor_butler.domain.identity.IdentityRecord;
import museon_online.astor_butler.domain.identity.IdentityService;
import museon_online.astor_butler.service.message.IncomingMessage;
import museon_online.astor_butler.service.message.MessageChannel;
import org.postgresql.util.PGobject;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class TelegramIntakeService {

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final IdentityService identityService;

    /** The unified call log (docs/operations/CALL_LOG.md): optional so the constructor and its tests stay as they are. */
    private museon_online.astor_butler.domain.calllog.CallLogWriter callLog;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setCallLog(museon_online.astor_butler.domain.calllog.CallLogWriter callLog) {
        this.callLog = callLog;
    }

    public void capture(IncomingMessage incoming) {
        if (incoming == null || incoming.chatId() == null) {
            return;
        }
        if (incoming.channel() != MessageChannel.TELEGRAM) {
            return;
        }
        if (callLog != null && callLog.enabled()) {
            callLog.guestMessage("telegram", museon_online.astor_butler.domain.calllog.CallLogWriter.firstId(incoming.telegramUserId(), incoming.chatId()), incoming.correlationId());
        }

        IdentityRecord identity = identityService.identifyTelegram(incoming);
        saveMessage(incoming, identity.userId());
    }

    private void saveMessage(IncomingMessage incoming, Long userId) {
        String eventId = incoming.correlationId() == null || incoming.correlationId().isBlank()
                ? UUID.randomUUID().toString()
                : incoming.correlationId();

        int inserted = jdbcTemplate.update("""
                INSERT INTO telegram_messages (
                    id, event_id, user_id, telegram_user_id, chat_id, message_id, update_id, message_kind,
                    text, contact_phone, raw_payload, received_at
                )
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT ON CONSTRAINT uq_telegram_messages_event_id DO NOTHING
                """,
                UUID.randomUUID(),
                eventId,
                userId,
                incoming.telegramUserId(),
                incoming.chatId(),
                incoming.telegramMessageId(),
                incoming.telegramUpdateId(),
                messageKind(incoming),
                incoming.text(),
                incoming.contactPhone(),
                jsonb(rawPayload(incoming)),
                OffsetDateTime.ofInstant(incoming.receivedAt(), ZoneOffset.UTC)
        );

        if (inserted > 0) {
            log.info("Telegram message stored: eventId={}, userId={}, chatId={}", eventId, userId, incoming.chatId());
        }
    }

    private String messageKind(IncomingMessage incoming) {
        if (incoming.contactPhone() != null && !incoming.contactPhone().isBlank()) {
            return "CONTACT";
        }
        Object mediaKind = incoming.payload() == null ? null : incoming.payload().get("mediaKind");
        if ("VOICE".equals(mediaKind) || "AUDIO".equals(mediaKind)) {
            return mediaKind.toString();
        }
        String text = incoming.text() == null ? "" : incoming.text().trim();
        if (text.startsWith("/")) {
            return "COMMAND";
        }
        if (!text.isBlank()) {
            return "TEXT";
        }
        return "UNKNOWN";
    }

    private Map<String, Object> rawPayload(IncomingMessage incoming) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("channel", incoming.channel().name());
        payload.put("externalUserId", incoming.externalUserId());
        payload.put("chatId", incoming.chatId());
        payload.put("telegramUserId", incoming.telegramUserId());
        payload.put("messageId", incoming.telegramMessageId());
        payload.put("updateId", incoming.telegramUpdateId());
        payload.put("firstName", incoming.firstName());
        payload.put("lastName", incoming.lastName());
        payload.put("username", incoming.username());
        payload.put("languageCode", incoming.languageCode());
        payload.put("bot", incoming.bot());
        payload.put("payload", incoming.payload() == null ? Map.of() : incoming.payload());
        return payload;
    }

    private PGobject jsonb(Map<String, Object> value) {
        PGobject object = new PGobject();
        object.setType("jsonb");
        try {
            object.setValue(objectMapper.writeValueAsString(value == null ? Map.of() : value));
            return object;
        } catch (SQLException | JsonProcessingException e) {
            throw new IllegalStateException("Cannot serialize Telegram message payload", e);
        }
    }
}
