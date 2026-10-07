package museon_online.astor_butler.domain.billing;

import java.time.Instant;

/**
 * @param tableReservationId the reservation the order belongs to; one bill of a kind per reservation
 * @param priceSource where the prices came from, for whoever later asks why the estimate differs from the venue's sum
 * @param visitEndsAt when the visit ends; null when unknown, then no review is asked by time
 */
public record BillDraft(
        Long chatId,
        Long telegramUserId,
        String venueCode,
        GuestBillKind kind,
        String source,
        Long tableReservationId,
        String priceSource,
        OrderEstimate estimate,
        Instant visitEndsAt
) {
}
