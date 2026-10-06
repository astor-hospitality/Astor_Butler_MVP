package museon_online.astor_butler.domain.booking.external;

/** What the restaurant's own system says about a booking Butler wrote there, in Butler's terms. */
public enum ExternalBookingState {
    /** Written, not yet accepted by the venue. */
    PENDING,
    /** The venue accepted the booking. */
    CONFIRMED,
    /** The visit took place or the booking was closed. */
    COMPLETED,
    /** Cancelled on the venue's side. */
    CANCELLED,
    /** The system gave no usable answer; nothing is changed on it. */
    UNKNOWN
}
