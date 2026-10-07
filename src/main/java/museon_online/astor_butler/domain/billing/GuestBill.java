package museon_online.astor_butler.domain.billing;

import java.time.Instant;

/**
 * @param estimateMinor Butler's own sum in kopecks from the published menu; null when a price is not published
 * @param venueAmountMinor the sum the venue's system reports; the guest pays this one, not the estimate
 * @param visitEndsAt when the visit the bill is for ends; the review is asked after that
 * @param paymentLinkSentAt when the guest was sent the venue's payment link; null until then
 * @param paidNotifiedAt when the guest was told the venue saw the payment; null until then
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
        Instant visitEndsAt,
        Instant paymentLinkSentAt,
        Instant paidNotifiedAt,
        Instant createdAt,
        Instant updatedAt
) {
}
