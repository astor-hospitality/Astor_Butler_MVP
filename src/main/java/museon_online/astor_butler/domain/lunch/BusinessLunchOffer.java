package museon_online.astor_butler.domain.lunch;

import java.time.DayOfWeek;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The business lunch of one venue: when it is served, the courses with their dishes, and the sets a guest picks from.
 * A slot of a set names the courses it may be filled from, so "salad or soup + main" is two slots: [SALAD, SOUP] and [MAIN].
 */
public record BusinessLunchOffer(
        String venueCode,
        String venueName,
        boolean confirmedByVenue,
        List<DayOfWeek> days,
        LocalTime from,
        LocalTime to,
        int seatingMinutes,
        String included,
        List<Course> courses,
        List<LunchSet> sets
) {
    private static final int DEFAULT_SEATING_MINUTES = 90;

    public record Course(String code, String title, List<Dish> dishes) {
    }

    public record Dish(String code, String title) {
    }

    public record LunchSet(String code, String title, Integer priceRub, List<List<String>> slots) {
    }

    public int seating() {
        return seatingMinutes > 0 ? seatingMinutes : DEFAULT_SEATING_MINUTES;
    }

    public Optional<LunchSet> set(String code) {
        return sets.stream().filter(set -> set.code().equalsIgnoreCase(code == null ? "" : code.trim())).findFirst();
    }

    public Optional<Course> course(String code) {
        return courses.stream().filter(course -> course.code().equals(code)).findFirst();
    }

    /** Every dish a guest may pick for this slot of the set. */
    public List<Dish> dishesFor(LunchSet set, int slot) {
        List<Dish> dishes = new ArrayList<>();
        for (String courseCode : set.slots().get(slot)) {
            course(courseCode).ifPresent(course -> dishes.addAll(course.dishes()));
        }
        return dishes;
    }

    public Optional<Dish> dish(String code) {
        return courses.stream().flatMap(course -> course.dishes().stream()).filter(dish -> dish.code().equals(code)).findFirst();
    }

    public Optional<Course> courseOf(String dishCode) {
        return courses.stream().filter(course -> course.dishes().stream().anyMatch(dish -> dish.code().equals(dishCode))).findFirst();
    }

    /** What the slot asks for, in words: "Салат" or "Салат или суп". */
    public String slotTitle(LunchSet set, int slot) {
        List<String> titles = set.slots().get(slot).stream()
                .map(code -> course(code).map(Course::title).orElse(code))
                .toList();
        if (titles.size() == 1) {
            return titles.getFirst();
        }
        return titles.getFirst() + " или " + String.join(" или ", titles.subList(1, titles.size())).toLowerCase(java.util.Locale.forLanguageTag("ru"));
    }
}
