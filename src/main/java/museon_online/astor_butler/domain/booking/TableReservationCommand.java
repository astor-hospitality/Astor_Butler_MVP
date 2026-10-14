package museon_online.astor_butler.domain.booking;

import java.time.Instant;

/**
 * @param channel where the guest booked: {@link #CHANNEL_TELEGRAM} (the Butler's bot, the default) or
 *                {@link #CHANNEL_CONCIERGE} (the Concierge's chat; such a booking has no chat of the Butler's bot)
 */
public record TableReservationCommand(
        Long chatId,
        Long telegramUserId,
        Long userId,
        String venueCode,
        String tableCode,
        String preferredZone,
        String seatingPreference,
        Instant requestedStartAt,
        Instant requestedEndAt,
        Integer partySize,
        String guestName,
        String guestPhone,
        String guestComment,
        Long managerTelegramId,
        String hostessChatId,
        String channel
) {

    public static final String CHANNEL_TELEGRAM = "TELEGRAM";
    public static final String CHANNEL_CONCIERGE = "CONCIERGE";

    public TableReservationCommand {
        channel = channel == null || channel.isBlank() ? CHANNEL_TELEGRAM : channel;
    }

    /** The shape before the Concierge channel: a booking made in the Butler's bot. */
    public TableReservationCommand(
            Long chatId,
            Long telegramUserId,
            Long userId,
            String venueCode,
            String tableCode,
            String preferredZone,
            String seatingPreference,
            Instant requestedStartAt,
            Instant requestedEndAt,
            Integer partySize,
            String guestName,
            String guestPhone,
            String guestComment,
            Long managerTelegramId,
            String hostessChatId
    ) {
        this(chatId, telegramUserId, userId, venueCode, tableCode, preferredZone, seatingPreference, requestedStartAt,
                requestedEndAt, partySize, guestName, guestPhone, guestComment, managerTelegramId, hostessChatId, CHANNEL_TELEGRAM);
    }

    public boolean concierge() {
        return CHANNEL_CONCIERGE.equals(channel);
    }
}
