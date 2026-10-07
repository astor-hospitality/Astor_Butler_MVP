package museon_online.astor_butler.domain.booking;

/**
 * Who gives the guest the final "confirmed": the hostess in the Butler chat, or the venue's own booking system
 * (Presto), read back by {@link ExternalBookingSync}. With {@link #VENUE_SYSTEM} the hostess button "Да" on an order
 * that is already in that system does nothing but point to it, so one booking is never confirmed in two places.
 * Orders the system does not know (write off, no phone, Saby down) are still confirmed by the hostess.
 */
public enum BookingConfirmationSource {
    HOSTESS,
    VENUE_SYSTEM
}
