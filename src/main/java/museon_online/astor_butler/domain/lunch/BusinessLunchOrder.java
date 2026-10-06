package museon_online.astor_butler.domain.lunch;

import java.time.Instant;
import java.util.List;

/**
 * The result of the business lunch dialogue, in the shape an external restaurant system needs:
 * who comes, when, to which held table, and what they ordered. Codes come from the venue's offer file.
 * A set order names the set and its price; an à la carte order has no set and a price on every dish.
 * {@code totalRub} is absent when the venue has not published a price.
 */
public record BusinessLunchOrder(
        String venueCode,
        Long tableReservationId,
        String tableCode,
        Instant startAt,
        Instant endAt,
        int guests,
        String setCode,
        String setTitle,
        Integer setPriceRub,
        List<Item> dishes,
        Integer totalRub,
        String guestName,
        String guestPhone,
        String comment,
        String source,
        String conciergeRequestId,
        /** The booking id in the venue's own system when Butler already wrote the reservation there; otherwise null. */
        String externalReservationId
) {
    public record Item(String courseCode, String dishCode, String dishTitle, int quantity, Integer priceRub) {
    }
}
