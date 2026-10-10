package museon_online.astor_butler.domain.identity;

import lombok.RequiredArgsConstructor;
import museon_online.astor_butler.service.message.IncomingMessage;
import museon_online.astor_butler.service.message.MessageChannel;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class IdentityService {

    private final JdbcTemplate jdbcTemplate;

    public IdentityRecord identifyTelegram(IncomingMessage incoming) {
        if (incoming == null || incoming.channel() != MessageChannel.TELEGRAM || incoming.telegramUserId() == null) {
            return new IdentityRecord(null, null);
        }

        Long userId = upsertUser(incoming);
        upsertTelegramProfile(userId, incoming);
        upsertPhoneContact(userId, incoming);
        return new IdentityRecord(userId, incoming.telegramUserId());
    }

    /**
     * The phone this guest can be reached at: the primary PHONE contact the guest shared with the bot earlier,
     * as {@link #identifyTelegram} stores it. A guest shares the contact once, at the first touch; every later
     * message is plain text, so a booking placed at the end of a dialogue has to read the phone back from here.
     * Empty for a guest who never shared one. The number is returned to the caller and never logged.
     */
    public Optional<String> primaryPhone(Long telegramUserId) {
        if (telegramUserId == null) {
            return Optional.empty();
        }
        List<String> phones = jdbcTemplate.queryForList("""
                SELECT uc.contact_value
                FROM user_contacts uc
                JOIN users u ON u.id = uc.user_id
                WHERE u.telegram_id = ?
                  AND uc.contact_type = 'PHONE'
                  AND NULLIF(TRIM(uc.contact_value), '') IS NOT NULL
                ORDER BY uc.is_primary DESC, uc.verified_at DESC NULLS LAST, uc.updated_at DESC
                LIMIT 1
                """,
                String.class,
                telegramUserId
        );
        return phones.stream().findFirst();
    }

    private Long upsertUser(IncomingMessage incoming) {
        return jdbcTemplate.queryForObject("""
                INSERT INTO users (
                    telegram_id, display_name, first_name, last_name, username, phone, role, status, updated_at
                )
                VALUES (?, ?, ?, ?, ?, ?, 'GUEST', 'ACTIVE', CURRENT_TIMESTAMP)
                ON CONFLICT (telegram_id)
                DO UPDATE SET
                    display_name = COALESCE(EXCLUDED.display_name, users.display_name),
                    updated_at = CURRENT_TIMESTAMP
                RETURNING id
                """,
                Long.class,
                incoming.telegramUserId(),
                displayName(incoming),
                incoming.firstName(),
                incoming.lastName(),
                incoming.username(),
                incoming.contactPhone()
        );
    }

    private void upsertTelegramProfile(Long userId, IncomingMessage incoming) {
        jdbcTemplate.update("""
                INSERT INTO telegram_profiles (
                    telegram_user_id, user_id, chat_id, username, first_name, last_name, language_code,
                    is_bot, phone_number, source_channel, updated_at, last_seen_at
                )
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                ON CONFLICT (telegram_user_id)
                DO UPDATE SET
                    user_id = COALESCE(telegram_profiles.user_id, EXCLUDED.user_id),
                    chat_id = EXCLUDED.chat_id,
                    username = COALESCE(EXCLUDED.username, telegram_profiles.username),
                    first_name = COALESCE(EXCLUDED.first_name, telegram_profiles.first_name),
                    last_name = COALESCE(EXCLUDED.last_name, telegram_profiles.last_name),
                    language_code = COALESCE(EXCLUDED.language_code, telegram_profiles.language_code),
                    is_bot = COALESCE(EXCLUDED.is_bot, telegram_profiles.is_bot),
                    phone_number = COALESCE(EXCLUDED.phone_number, telegram_profiles.phone_number),
                    source_channel = EXCLUDED.source_channel,
                    updated_at = CURRENT_TIMESTAMP,
                    last_seen_at = CURRENT_TIMESTAMP
                """,
                incoming.telegramUserId(),
                userId,
                incoming.chatId(),
                incoming.username(),
                incoming.firstName(),
                incoming.lastName(),
                incoming.languageCode(),
                incoming.bot() == null ? false : incoming.bot(),
                incoming.contactPhone(),
                incoming.channel().name()
        );
    }

    private void upsertPhoneContact(Long userId, IncomingMessage incoming) {
        if (userId == null || incoming.contactPhone() == null || incoming.contactPhone().isBlank()) {
            return;
        }

        jdbcTemplate.update("""
                INSERT INTO user_contacts (
                    id, user_id, contact_type, contact_value, source, is_primary, verified_at, updated_at
                )
                VALUES (?, ?, 'PHONE', ?, 'TELEGRAM_CONTACT', true, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                ON CONFLICT ON CONSTRAINT uq_user_contacts_user_type_value
                DO UPDATE SET
                    source = EXCLUDED.source,
                    is_primary = true,
                    verified_at = COALESCE(user_contacts.verified_at, EXCLUDED.verified_at),
                    updated_at = CURRENT_TIMESTAMP
                """,
                UUID.randomUUID(),
                userId,
                incoming.contactPhone()
        );
    }

    private String displayName(IncomingMessage incoming) {
        String fullName = String.join(" ",
                nullToBlank(incoming.firstName()),
                nullToBlank(incoming.lastName())
        ).trim();
        if (!fullName.isBlank()) {
            return fullName;
        }
        if (incoming.username() != null && !incoming.username().isBlank()) {
            return "@" + incoming.username();
        }
        return "Telegram guest " + incoming.telegramUserId();
    }

    private String nullToBlank(String value) {
        return value == null ? "" : value;
    }
}
