package museon_online.astor_butler.domain.billing;

/**
 * What came of a bill request made for a reservation (the agents' {@code bill_request}): whether the team was told,
 * and the bill as Butler knows it. {@code billId} and {@code totalRub} are null when the visit has no bill in Butler
 * (the lunch was not ordered through it); {@code paymentUrl} only when the venue's payment page is switched on.
 */
public record BillRequestOutcome(Status status, Long reservationId, Long billId, Long totalRub, String paymentUrl) {

    public enum Status {
        /** {@code ASTOR_GUEST_BILL_REQUEST_ENABLED} is off. */
        OFF,
        /** The shift manager has stopped Butler. */
        PAUSED,
        /** No such reservation, or not one with a visit today. */
        NO_VISIT,
        /** The team was told within the repeat window already; nothing re-sent. */
        ALREADY_SENT,
        /** The staff chat could not be reached. */
        STAFF_NOT_REACHED,
        /** The team got the card now. */
        STAFF_NOTIFIED
    }

    /** The team knows: now or a moment ago. */
    public boolean delivered() {
        return status == Status.STAFF_NOTIFIED || status == Status.ALREADY_SENT;
    }
}
