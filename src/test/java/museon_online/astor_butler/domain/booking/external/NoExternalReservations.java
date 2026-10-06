package museon_online.astor_butler.domain.booking.external;

import museon_online.astor_butler.domain.booking.TableReservationCommand;

import java.util.List;

/** A venue with no booking system of its own switched on: every table question is answered locally. */
public final class NoExternalReservations implements ExternalReservationProvider {

    private static final String PROVIDER_ID = "NONE";

    @Override
    public String providerId() {
        return PROVIDER_ID;
    }

    @Override
    public ExternalReservationStatus status() {
        return ExternalReservationStatus.disabled(PROVIDER_ID, List.of());
    }

    @Override
    public ExternalAvailabilityResult checkAvailability(ExternalAvailabilityRequest request) {
        return ExternalAvailabilityResult.unavailableBecauseUnconfigured(PROVIDER_ID, List.of());
    }

    @Override
    public ExternalReservationResult reserve(TableReservationCommand command, String idempotencyKey) {
        return ExternalReservationResult.rejectedBecauseUnconfigured(PROVIDER_ID, List.of());
    }

    @Override
    public boolean cancelReservation(String externalReservationId) {
        return false;
    }

    @Override
    public ExternalReservationResult updateReservation(String externalReservationId, TableReservationCommand command, String butlerReference) {
        return ExternalReservationResult.rejectedBecauseUnconfigured(PROVIDER_ID, List.of());
    }

    @Override
    public ExternalBookingSnapshot fetchReservationState(String externalReservationId) {
        return ExternalBookingSnapshot.unknown(PROVIDER_ID, externalReservationId, "PROVIDER_NOT_CONFIGURED");
    }
}
