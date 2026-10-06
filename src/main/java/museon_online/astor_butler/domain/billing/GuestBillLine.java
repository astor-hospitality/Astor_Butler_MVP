package museon_online.astor_butler.domain.billing;

/** A priced position as it was when the bill was estimated; later menu changes do not rewrite it. */
public record GuestBillLine(
        Long id,
        Long billId,
        int lineNo,
        String itemCode,
        String title,
        int quantity,
        Long unitPriceMinor,
        boolean included
) {
}
