package museon_online.astor_butler.domain.entry;

import java.time.Instant;

/**
 * One row of {@code guest_entry_events}: a guest came in through a staff member's link or the host stand QR (#128).
 * No guest name or phone: the chat id points at what the dialogue already keeps.
 */
public record GuestEntryEvent(
        Long id,
        String tenant,
        Long chatId,
        String staffId,
        String tableCode,
        String source,
        Instant createdAt
) {
    public static final String SOURCE_STAFF_HANDOFF = "STAFF_HANDOFF";
}
