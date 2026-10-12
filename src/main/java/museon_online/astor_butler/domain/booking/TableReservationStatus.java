package museon_online.astor_butler.domain.booking;

public enum TableReservationStatus {
    DRAFT,
    AWAITING_GUEST_SELECTION,
    AWAITING_MANAGER_CONFIRMATION,
    CONFIRMED,
    /**
     * The guest is at the table (#151): the hostess pressed «🪑 Гость сел», or the venue's own system said so.
     * Still holds the table like {@link #CONFIRMED}; never expires, is never auto-confirmed, may still be edited.
     */
    SEATED,
    REJECTED,
    CANCELLED,
    EXPIRED
}
