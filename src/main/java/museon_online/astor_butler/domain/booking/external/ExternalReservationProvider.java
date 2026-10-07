package museon_online.astor_butler.domain.booking.external;

import museon_online.astor_butler.domain.booking.TableReservationCommand;

import java.time.Instant;

public interface ExternalReservationProvider {

    String providerId();

    ExternalReservationStatus status();

    ExternalAvailabilityResult checkAvailability(ExternalAvailabilityRequest request);

    ExternalReservationResult reserve(TableReservationCommand command, String idempotencyKey);

    /**
     * Asks the provider to cancel a booking it created.
     *
     * @return false when the booking is not known to be cancelled, so a person has to check it
     */
    boolean cancelReservation(String externalReservationId);

    /**
     * Reads what the venue's system now says about a booking Butler wrote there. Never throws: a failed read is
     * {@link ExternalBookingState#UNKNOWN}, and the caller changes nothing on it.
     */
    ExternalBookingSnapshot fetchReservationState(String externalReservationId);

    /**
     * Rewrites a booking Butler created in the venue's system with the order's current data (time, party size,
     * wishes). {@code created()} on the result means the venue's system now has the new data; anything else means
     * it may still hold the old data and a person has to look.
     *
     * @param butlerReference the local order id, kept in the booking as its marker
     */
    ExternalReservationResult updateReservation(String externalReservationId, TableReservationCommand command, String butlerReference);

    record ExternalAvailabilityRequest(
            String venueCode,
            Instant requestedStartAt,
            Instant requestedEndAt,
            Integer partySize,
            String tableCode,
            String preferredZone
    ) {
    }
}
