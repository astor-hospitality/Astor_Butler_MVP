package museon_online.astor_butler.domain.billing;

import java.time.Instant;

/**
 * @param estimateMinor Butler's own sum in kopecks from the published menu; null when a price is not published
 * @param venueAmountMinor the sum the venue's system reports; the guest pays this one, not the estimate
 */
public record GuestBill(
        Long id,
        Long chatId,
        Long telegramUserId,
        String venueCode,
        GuestBillKind kind,
        String source,
        Long tableReservationId,
        String externalProvider,
        String externalOrderId,
        GuestBillStatus status,
        BillPayState payState,
        Long estimateMinor,
        Long venueAmountMinor,
        String currency,
        String priceSource,
        Instant createdAt,
        Instant updatedAt
) {
}
