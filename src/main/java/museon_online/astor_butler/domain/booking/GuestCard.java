package museon_online.astor_butler.domain.booking;

import java.time.Instant;
import java.util.List;

/**
 * What the waiter sees before walking up to the table (#163): only what the guest told the bot and ordered through it.
 * Nothing here is inferred from third-party sources, and nothing identifies the guest beyond a first name.
 *
 * @param firstName  the first word of the name the guest gave with the booking; null when the bot has none
 * @param pastVisits confirmed or seated bookings of this chat before this one
 * @param lastVisit  when the latest of those took place; null on a first visit
 * @param wish       today's wish for the kitchen or the bar from the lunch order (#180); null when none was given
 * @param allergens  what the guest wrote about allergies — in a «запомни» preference or in today's wish; null = not stated
 * @param drinks     explicit DRINK preferences, newest first
 * @param food       explicit FOOD preferences, newest first
 * @param other      explicit SERVICE, COMMUNICATION and GENERAL preferences; seating is of no use once seated
 * @param favourites the dishes ordered most across the guest's earlier lunch orders, at most two
 * @param lastStars  the stars of the latest rated visit; null when the guest never rated one
 * @param waiter     the display name of the waiter assigned to the table today (#161); null when nobody is
 */
public record GuestCard(
        Long orderId,
        String tableName,
        Instant seatedAt,
        Integer partySize,
        String firstName,
        int pastVisits,
        Instant lastVisit,
        String wish,
        String allergens,
        List<String> drinks,
        List<String> food,
        List<String> other,
        List<String> favourites,
        Integer lastStars,
        String waiter
) {

    public boolean firstVisit() {
        return pastVisits == 0;
    }
}
