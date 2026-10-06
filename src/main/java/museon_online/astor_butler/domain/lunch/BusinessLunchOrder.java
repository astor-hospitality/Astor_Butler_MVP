package museon_online.astor_butler.domain.lunch;

import java.time.Instant;
import java.util.List;

/**
 * The result of the business lunch dialogue, in the shape an external restaurant system needs:
 * who comes, when, to which held table, and what they ordered. Codes come from the venue's offer file.
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
        String guestName,
        String guestPhone,
        String comment,
        String source,
        String conciergeRequestId
) {
    public record Item(String courseCode, String dishCode, String dishTitle) {
    }
}
