package museon_online.astor_butler.domain.billing;

/**
 * {@code ESTIMATED}: priced by Butler, the venue's system does not hold the order.
 * {@code ISSUED}: the venue's system holds the order. {@code PAID}: the venue reports it settled, never Butler's guess.
 */
public enum GuestBillStatus {
    ESTIMATED,
    ISSUED,
    PAID,
    CANCELLED
}
