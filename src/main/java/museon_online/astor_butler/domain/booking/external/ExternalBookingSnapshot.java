package museon_online.astor_butler.domain.booking.external;

import java.util.Map;

/**
 * One read of a booking in the restaurant's own system.
 *
 * @param state     the booking's state in Butler's terms; {@link ExternalBookingState#UNKNOWN} when the read failed
 * @param status    the provider's own status code for logs and cards, for example {@code SABY_STATE_20}
 * @param metadata  raw codes and other details, never guest data
 */
public record ExternalBookingSnapshot(
        String providerId,
        String externalReservationId,
        ExternalBookingState state,
        String status,
        Map<String, Object> metadata
) {
    public static ExternalBookingSnapshot unknown(String providerId, String externalReservationId, String status) {
        return new ExternalBookingSnapshot(providerId, externalReservationId, ExternalBookingState.UNKNOWN, status, Map.of());
    }
}
