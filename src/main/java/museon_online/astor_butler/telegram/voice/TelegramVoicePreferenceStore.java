package museon_online.astor_butler.telegram.voice;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Service;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/**
 * The guest's hands-free switch lives on {@code telegram_profiles} next to the other per-user Telegram state;
 * the full answers behind the "Подробнее" button live in {@code telegram_reply_details}, because callback data
 * is limited to 64 bytes and the text must survive a restart.
 */
@Service
@RequiredArgsConstructor
public class TelegramVoicePreferenceStore {

    private final JdbcTemplate jdbcTemplate;

    public boolean isHandsFree(Long telegramUserId) {
        if (telegramUserId == null) {
            return false;
        }
        Boolean value = jdbcTemplate.query("""
                SELECT voice_replies_hands_free
                FROM telegram_profiles
                WHERE telegram_user_id = ?
                """,
                resultSet -> resultSet.next() ? resultSet.getObject("voice_replies_hands_free", Boolean.class) : null,
                telegramUserId);
        return Boolean.TRUE.equals(value);
    }

    /** Stores the switch; a guest who has no profile row yet (first message was /voice) gets a minimal one. */
    public void setHandsFree(Long telegramUserId, Long chatId, boolean handsFree) {
        if (telegramUserId == null) {
            return;
        }
        int updated = jdbcTemplate.update("""
                UPDATE telegram_profiles
                SET voice_replies_hands_free = ?,
                    voice_replies_updated_at = CURRENT_TIMESTAMP,
                    updated_at = CURRENT_TIMESTAMP
                WHERE telegram_user_id = ?
                """, handsFree, telegramUserId);
        if (updated == 0) {
            jdbcTemplate.update("""
                    INSERT INTO telegram_profiles (
                        telegram_user_id, chat_id, source_channel, created_at, updated_at,
                        voice_replies_hands_free, voice_replies_updated_at
                    )
                    VALUES (?, ?, 'TELEGRAM', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, ?, CURRENT_TIMESTAMP)
                    """, telegramUserId, chatId == null ? telegramUserId : chatId, handsFree);
        }
    }

    /** Keeps the full answer and returns the id the inline button carries. */
    public long saveFullReply(Long chatId, String fullText, boolean html, Duration ttl) {
        jdbcTemplate.update("DELETE FROM telegram_reply_details WHERE created_at < ?",
                Timestamp.from(Instant.now().minus(ttl)));
        KeyHolder keys = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO telegram_reply_details (chat_id, full_text, html) VALUES (?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            statement.setLong(1, chatId);
            statement.setString(2, fullText);
            statement.setBoolean(3, html);
            return statement;
        }, keys);
        Number id = keys.getKeys() != null && keys.getKeys().size() > 1
                ? (Number) keys.getKeys().get("id")
                : keys.getKey();
        return id == null ? -1L : id.longValue();
    }

    /** The stored answer, only for the chat it was written for: a forwarded button cannot read another chat. */
    public Optional<FullReply> findFullReply(long id, Long chatId) {
        if (chatId == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(jdbcTemplate.query("""
                SELECT full_text, html
                FROM telegram_reply_details
                WHERE id = ? AND chat_id = ?
                """,
                resultSet -> resultSet.next()
                        ? new FullReply(resultSet.getString("full_text"), resultSet.getBoolean("html"))
                        : null,
                id, chatId));
    }

    public record FullReply(String text, boolean html) {
    }
}
