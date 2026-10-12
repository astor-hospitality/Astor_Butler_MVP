package museon_online.astor_butler.domain.booking;

import java.time.Instant;

/**
 * @param seatedAt when the guest sat down (#151): set with {@link TableReservationStatus#SEATED}, null before that
 */
public record TableReservationOrder(
        Long id,
        Long chatId,
        Long telegramUserId,
        Long userId,
        Long tableId,
        String tableCode,
        String tableDisplayName,
        String preferredZone,
        String seatingPreference,
        TableReservationStatus status,
        String source,
        Instant requestedStartAt,
        Instant requestedEndAt,
        Integer partySize,
        String guestName,
        String guestPhone,
        String guestComment,
        Long managerTelegramId,
        Long managerUserId,
        String hostessChatId,
        String sbisExternalId,
        Instant createdAt,
        Instant updatedAt,
        Instant seatedAt
) {

    /** The shape before #151, for callers that know nothing about the seating time. */
    public TableReservationOrder(
            Long id,
            Long chatId,
            Long telegramUserId,
            Long userId,
            Long tableId,
            String tableCode,
            String tableDisplayName,
            String preferredZone,
            String seatingPreference,
            TableReservationStatus status,
            String source,
            Instant requestedStartAt,
            Instant requestedEndAt,
            Integer partySize,
            String guestName,
            String guestPhone,
            String guestComment,
            Long managerTelegramId,
            Long managerUserId,
            String hostessChatId,
            String sbisExternalId,
            Instant createdAt,
            Instant updatedAt
    ) {
        this(id, chatId, telegramUserId, userId, tableId, tableCode, tableDisplayName, preferredZone, seatingPreference,
                status, source, requestedStartAt, requestedEndAt, partySize, guestName, guestPhone, guestComment,
                managerTelegramId, managerUserId, hostessChatId, sbisExternalId, createdAt, updatedAt, null);
    }
}
