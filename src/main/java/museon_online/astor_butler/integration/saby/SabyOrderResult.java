package museon_online.astor_butler.integration.saby;

/**
 * Outcome of a Saby call on an existing booking ({@code state}, {@code cancel}).
 * Raw {@code state}/{@code productState} codes are kept next to their mapped meaning ({@link SabyBookingState}),
 * so a code outside the documented set is visible and never mistaken for a confirmation.
 */
public record SabyOrderResult(
        boolean ok,
        String status,
        String externalId,
        Integer state,
        Integer productState,
        Integer payState,
        SabyBookingState bookingState,
        String message
) {
    static SabyOrderResult failure(String externalId, String status, String message) {
        return new SabyOrderResult(false, status, externalId, null, null, null, SabyBookingState.UNKNOWN, message);
    }
}
