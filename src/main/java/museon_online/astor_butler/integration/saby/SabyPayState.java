package museon_online.astor_butler.integration.saby;

import com.fasterxml.jackson.databind.JsonNode;
import museon_online.astor_butler.domain.billing.BillPayState;

/**
 * Saby's {@code payState} of an order, as listed in docs/integrations/SABY_PRESTO_BOOKING_API.md 4.5.
 * Anything outside the list is {@code UNKNOWN}: Butler does not call an order paid on a code it cannot read.
 */
final class SabyPayState {

    private SabyPayState() {
    }

    static BillPayState fromCode(Integer code) {
        if (code == null) {
            return BillPayState.UNKNOWN;
        }
        return switch (code) {
            case 0 -> BillPayState.UNPAID;
            case 10 -> BillPayState.PREPAID_FULL;
            case 30 -> BillPayState.PREPAID_PARTIAL;
            case 50 -> BillPayState.CREDIT;
            case 200 -> BillPayState.PAID;
            default -> BillPayState.UNKNOWN;
        };
    }

    /** Reads {@code payState} from the answer of {@code GET /retail/order/{id}/state}; a number or a number in quotes. */
    static BillPayState read(JsonNode stateResponse) {
        JsonNode value = stateResponse == null ? null : stateResponse.get("payState");
        if (value == null || value.isNull()) {
            return BillPayState.UNKNOWN;
        }
        if (value.isIntegralNumber() && value.canConvertToInt()) {
            return fromCode(value.intValue());
        }
        if (value.isTextual() && value.asText().trim().matches("\\d{1,9}")) {
            return fromCode(Integer.valueOf(value.asText().trim()));
        }
        return BillPayState.UNKNOWN;
    }
}
