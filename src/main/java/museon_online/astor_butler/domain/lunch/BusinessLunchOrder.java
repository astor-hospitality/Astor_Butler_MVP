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
        String externalReservationId,
        /**
         * The guest's wish for the kitchen or the bar in the guest's own words («без лука, лимонад кислее»; #165), one per
         * order, at most {@code BusinessLunchService.WISH_LIMIT} characters. Null when none was given. Every wish is to be
         * agreed with the kitchen; nothing here promises it.
         */
        String wishes
) {
    public BusinessLunchOrder(
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
            String externalReservationId
    ) {
        this(venueCode, tableReservationId, tableCode, startAt, endAt, guests, setCode, setTitle, setPriceRub, dishes, totalRub,
                guestName, guestPhone, comment, source, conciergeRequestId, externalReservationId, null);
    }

    /**
     * One ordered dish. {@code dishTitle} is what the guest and staff read; {@code sabyName} is the exact name of the dish
     * in the Saby price list when it differs from the title, otherwise null.
     */
    public record Item(String courseCode, String dishCode, String dishTitle, int quantity, Integer priceRub, String sabyName) {
        public Item(String courseCode, String dishCode, String dishTitle, int quantity, Integer priceRub) {
            this(courseCode, dishCode, dishTitle, quantity, priceRub, null);
        }

        /** The name to look the dish up by in Saby: {@code sabyName} when present and non-blank, else the title. */
        public String sabyLookupName() {
            return sabyName != null && !sabyName.isBlank() ? sabyName : dishTitle;
        }
    }
}
