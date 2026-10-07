package museon_online.astor_butler.domain.billing;

/** What the venue's system says about the money. {@code NOT_REPORTED} until it has said anything. */
public enum BillPayState {
    NOT_REPORTED,
    UNPAID,
    PREPAID_PARTIAL,
    PREPAID_FULL,
    CREDIT,
    PAID,
    UNKNOWN;

    /** Nothing is left for the guest to pay. */
    public boolean settled() {
        return this == PREPAID_FULL || this == PAID;
    }
}
