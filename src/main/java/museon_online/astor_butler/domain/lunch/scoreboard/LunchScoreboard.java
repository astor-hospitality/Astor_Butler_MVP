package museon_online.astor_butler.domain.lunch.scoreboard;

import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * The staff's look at one day of business lunch (#175): how many lunches and guests, what the guests answered after
 * the visit, which dishes were ordered most and how they were rated, and what the guests wished for.
 *
 * <p>Facts from what Butler already holds: lunch reservations, their bills with the ordered lines, and the one review
 * per bill. No names, no phones — the table is read in the staff group. A dish is rated through the visit it was part
 * of: the guest rates the visit, not the dish, so a star on a dish means «the visits with this dish were rated so».
 *
 * @param lunches lunch reservations of the day that were not cancelled, rejected or expired
 * @param guests the sum of their party sizes
 * @param visits lunch bills found for those reservations; the reviews and the dishes come from them
 * @param billsAvailable {@code astor.billing.enabled}: without bills there are no reviews and no dish lines to read
 */
public record LunchScoreboard(
        LocalDate date,
        String venueCode,
        int lunches,
        int guests,
        int visits,
        Reviews reviews,
        List<Dish> dishes,
        List<String> wishes,
        boolean billsAvailable
) {
    /** A dish is rated by fewer visits than this is «мало данных». */
    public static final int FEW_REVIEWS = 3;

    public LunchScoreboard {
        reviews = reviews == null ? Reviews.NONE : reviews;
        dishes = dishes == null ? List.of() : List.copyOf(dishes);
        wishes = wishes == null ? List.of() : List.copyOf(wishes);
    }

    /** The guests' word after the lunch visits of the day: stars (1–5) and thumbs. */
    public record Reviews(int answered, double averageStars, int fiveStars, int thumbsUp, int thumbsDown) {
        public static final Reviews NONE = new Reviews(0, 0, 0, 0, 0);

        /** The share of 5★ among the answered reviews, whole percent; 0 when nobody answered. */
        public int fiveStarPercent() {
            return answered == 0 ? 0 : (int) Math.round(100.0 * fiveStars / answered);
        }
    }

    /**
     * @param portions how many were ordered today, over every lunch bill
     * @param reviews how many rated visits had this dish
     * @param averageStars the mean of those visits' stars; 0 when none
     */
    public record Dish(String code, String title, int portions, int reviews, double averageStars) {
        public boolean rated() {
            return reviews > 0;
        }

        public boolean fewReviews() {
            return reviews > 0 && reviews < FEW_REVIEWS;
        }
    }

    public boolean empty() {
        return lunches == 0 && visits == 0 && reviews.answered() == 0;
    }

    /** The most ordered dishes, in the order the service ranked them. */
    public List<Dish> top(int count) {
        return dishes.subList(0, Math.min(count, dishes.size()));
    }

    /**
     * The lowest-rated dish, when there is something to compare: at least two rated dishes and this one below the best.
     * A single rated dish is the top and the bottom at once, which says nothing.
     */
    public Optional<Dish> worst() {
        List<Dish> rated = dishes.stream().filter(Dish::rated).toList();
        if (rated.size() < 2) {
            return Optional.empty();
        }
        Dish worst = rated.stream().min(Comparator.comparingDouble(Dish::averageStars)).orElseThrow();
        double best = rated.stream().mapToDouble(Dish::averageStars).max().orElse(0);
        return worst.averageStars() < best ? Optional.of(worst) : Optional.empty();
    }
}
