package museon_online.astor_butler.domain.lunch;

import museon_online.astor_butler.domain.booking.external.ExternalReservationStatus;

/**
 * The restaurant's own system that receives a business lunch order, Saby for AERIS.
 * It is told about an order only after Butler has stored it and held the table, and it never confirms to the guest:
 * the hostess does. An implementation must not throw for a refusal, it returns a result that is not accepted.
 */
public interface ExternalLunchOrderProvider {

    String providerId();

    ExternalReservationStatus status();

    Result submit(BusinessLunchOrder order, String idempotencyKey);

    record Result(boolean accepted, String providerId, String status, String externalOrderId, String message) {

        /** No external system is switched on: the staff enter the order by hand. */
        public static Result manualEntry() {
            return new Result(false, "", "MANUAL_ENTRY", "", "No external order provider is enabled; staff enter the order manually.");
        }

        public static Result failed(String providerId, String message) {
            return new Result(false, providerId, "PROVIDER_ERROR", "", message);
        }

        public boolean attempted() {
            return !"MANUAL_ENTRY".equals(status);
        }
    }
}
