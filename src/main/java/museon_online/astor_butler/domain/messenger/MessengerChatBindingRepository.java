package museon_online.astor_butler.domain.messenger;

import lombok.RequiredArgsConstructor;
import museon_online.astor_butler.service.message.MessageChannel;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * {@code messenger_chat_bindings}: the internal chat id of every MAX chat (and later any other non-Telegram
 * messenger). The internal id is {@code 8e12 + n} for a dialog and {@code -(8e12 + n)} for a group chat, n from
 * {@code messenger_internal_chat_seq}: the gateway treats negative ids as groups, Telegram ids stay far below the
 * range and the web chat uses {@code 9e12..9.9e12}. Plain SQL that runs on PostgreSQL and on H2 in tests.
 */
@Repository
@RequiredArgsConstructor
public class MessengerChatBindingRepository {

    public static final long INTERNAL_CHAT_ID_BASE = 8_000_000_000_000L;

    private static final RowMapper<MessengerChatBinding> ROW = (rs, rowNum) -> new MessengerChatBinding(
            MessageChannel.valueOf(rs.getString("channel")),
            rs.getLong("external_chat_id"),
            rs.getObject("external_user_id", Long.class),
            rs.getString("chat_type"),
            rs.getLong("internal_chat_id"),
            rs.getString("contact_phone")
    );

    private final JdbcTemplate jdbcTemplate;

    /** Profile fields refreshed on every message; all of them may be null. */
    public record Profile(String firstName, String lastName, String username, String locale) {
        public static Profile empty() {
            return new Profile(null, null, null, null);
        }
    }

    /**
     * Returns the binding of this chat, creating it on first contact. Refreshes the profile and last_seen_at.
     *
     * @param group true for a group chat: the internal id is then negative
     */
    public MessengerChatBinding resolve(MessageChannel channel, long externalChatId, Long externalUserId, String chatType,
                                        boolean group, Profile profile) {
        Profile safeProfile = profile == null ? Profile.empty() : profile;
        Optional<MessengerChatBinding> existing = find(channel, externalChatId);
        if (existing.isPresent()) {
            touch(channel, externalChatId, externalUserId, safeProfile);
            return existing.get();
        }
        Long next = jdbcTemplate.queryForObject("SELECT nextval('messenger_internal_chat_seq')", Long.class);
        long internalChatId = (group ? -1L : 1L) * (INTERNAL_CHAT_ID_BASE + (next == null ? 0L : next));
        try {
            jdbcTemplate.update("""
                    INSERT INTO messenger_chat_bindings (
                        channel, external_chat_id, external_user_id, chat_type, internal_chat_id,
                        first_name, last_name, username, locale, created_at, last_seen_at
                    )
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                    """,
                    channel.name(), externalChatId, externalUserId, chatType == null ? "unknown" : chatType, internalChatId,
                    safeProfile.firstName(), safeProfile.lastName(), safeProfile.username(), safeProfile.locale());
        } catch (DuplicateKeyException raced) {
            // Another worker bound the same chat first; its row wins.
        }
        return find(channel, externalChatId)
                .orElseThrow(() -> new IllegalStateException("Messenger chat binding was not stored: " + channel));
    }

    public Optional<MessengerChatBinding> find(MessageChannel channel, long externalChatId) {
        return jdbcTemplate.query("""
                SELECT channel, external_chat_id, external_user_id, chat_type, internal_chat_id, contact_phone
                FROM messenger_chat_bindings
                WHERE channel = ? AND external_chat_id = ?
                """, ROW, channel.name(), externalChatId).stream().findFirst();
    }

    /** Reverse lookup for outbound messages that only know the FSM chat id. */
    public Optional<MessengerChatBinding> findByInternalChatId(long internalChatId) {
        return jdbcTemplate.query("""
                SELECT channel, external_chat_id, external_user_id, chat_type, internal_chat_id, contact_phone
                FROM messenger_chat_bindings
                WHERE internal_chat_id = ?
                """, ROW, internalChatId).stream().findFirst();
    }

    /** Keeps the phone the guest shared with the contact button, next to the chat it came from. */
    public void saveContactPhone(MessageChannel channel, long externalChatId, String phone) {
        if (phone == null || phone.isBlank()) {
            return;
        }
        jdbcTemplate.update("""
                UPDATE messenger_chat_bindings
                SET contact_phone = ?, last_seen_at = CURRENT_TIMESTAMP
                WHERE channel = ? AND external_chat_id = ?
                """, phone.trim(), channel.name(), externalChatId);
    }

    private void touch(MessageChannel channel, long externalChatId, Long externalUserId, Profile profile) {
        jdbcTemplate.update("""
                UPDATE messenger_chat_bindings
                SET external_user_id = COALESCE(?, external_user_id),
                    first_name = COALESCE(?, first_name),
                    last_name = COALESCE(?, last_name),
                    username = COALESCE(?, username),
                    locale = COALESCE(?, locale),
                    last_seen_at = CURRENT_TIMESTAMP
                WHERE channel = ? AND external_chat_id = ?
                """,
                externalUserId, profile.firstName(), profile.lastName(), profile.username(), profile.locale(),
                channel.name(), externalChatId);
    }
}
