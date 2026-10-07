package museon_online.astor_butler.domain.billing;

public enum BillOperationType {
    ESTIMATE_CREATED,
    ISSUED_TO_VENUE,
    VENUE_SUBMIT_FAILED,
    PAY_STATE_CHANGED,
    VENUE_AMOUNT_REPORTED,
    AMOUNT_MISMATCH,
    CANCELLED
}
