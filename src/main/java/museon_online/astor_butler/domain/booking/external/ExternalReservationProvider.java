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
