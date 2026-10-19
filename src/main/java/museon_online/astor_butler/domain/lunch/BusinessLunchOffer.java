package museon_online.astor_butler.domain.lunch;

import java.time.DayOfWeek;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The business lunch of one venue: when it is served, and what a guest orders from. Two shapes exist.
 * <ul>
 * <li>Sets: the guest picks one set and fills its slots. A slot names the courses it may be filled from,
 * so "salad or soup + main" is two slots, [SALAD, SOUP] and [MAIN]. The price belongs to the set.</li>
 * <li>À la carte, when there are no sets: the guest takes any dishes from any course and every dish has its own price.</li>
 * </ul>
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

    public BusinessLunchOffer {
        courses = courses == null ? List.of() : List.copyOf(courses);
        sets = sets == null ? List.of() : List.copyOf(sets);
    }

    public record Course(String code, String title, List<Dish> dishes) {
    }

    /**
     * {@code portion} is how the menu states the size: "170 г", "500 мл".
     * <p>{@code ingredients}, {@code allergens} and {@code canBeWithout} are what the restaurant fills in so that the bot can
     * answer «состав …», «что в …» and «аллергены …» (#165, {@code docs/operations/MENU_COMPOSITION_FOR_BOT.md}). All three
     * are optional and empty until the restaurant writes them; the bot never makes them up.
     * <p>{@code number} is the short number a waiter says aloud through the glasses — «забить 14 на 12» (#184). Optional:
     * the technologist assigns it once per dish and keeps it stable across menu versions (#174); a dish without a number
     * cannot be ordered by voice. Numbers are unique within the offer ({@link BusinessLunchCatalog#problems}).
     */
    public record Dish(String code, String title, Integer priceRub, String portion, String ingredients, String allergens,
                       List<String> canBeWithout, Integer number, String sabyName) {
        public Dish {
            canBeWithout = canBeWithout == null ? List.of() : List.copyOf(canBeWithout);
        }

        public Dish(String code, String title) {
            this(code, title, null, null);
        }

        public Dish(String code, String title, Integer priceRub, String portion) {
            this(code, title, priceRub, portion, null, null, null);
        }

        public Dish(String code, String title, Integer priceRub, String portion, String ingredients, String allergens, List<String> canBeWithout) {
            this(code, title, priceRub, portion, ingredients, allergens, canBeWithout, null);
        }

        public Dish(String code, String title, Integer priceRub, String portion, String ingredients, String allergens,
                    List<String> canBeWithout, Integer number) {
            this(code, title, priceRub, portion, ingredients, allergens, canBeWithout, number, null);
        }

        /** The same dish under a spoken number; {@code null} removes it. */
        public Dish withNumber(Integer number) {
            return new Dish(code, title, priceRub, portion, ingredients, allergens, canBeWithout, number, sabyName);
        }

        /**
         * The name the dish is looked up by in the Saby (Presto) price list: {@code sabyName} when the menu file gives one
         * (Presto names carry suffixes like «ЛАНЧ» or «500 мл» that guests should not see), otherwise the guest-facing title.
         */
        public String sabyLookupName() {
            return hasText(sabyName) ? sabyName : title;
        }

        /** Whether the dish may be ordered by its number through the glasses. */
        public boolean numbered() {
            return number != null;
        }

        /** Whether the restaurant has written anything about what is in the dish. */
        public boolean describesComposition() {
            return hasText(ingredients) || hasText(allergens) || !canBeWithout.isEmpty();
        }

        private static boolean hasText(String value) {
            return value != null && !value.isBlank();
        }
    }

    public record LunchSet(String code, String title, Integer priceRub, List<List<String>> slots) {
    }

    public boolean aLaCarte() {
        return sets.isEmpty();
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

    /** The dish the waiter means by its spoken number (#184), if the technologist gave it one. */
    public Optional<Dish> dishByNumber(int number) {
        return numberedDishes().stream().filter(dish -> dish.number() == number).findFirst();
    }

    /** Every dish with a spoken number, in number order: the printed cheat sheet «номер — блюдо» for the waiters. */
    public List<Dish> numberedDishes() {
        return courses.stream().flatMap(course -> course.dishes().stream()).filter(Dish::numbered)
                .sorted(java.util.Comparator.comparingInt(Dish::number)).toList();
    }

    public Optional<Course> courseOf(String dishCode) {
        return courses.stream().filter(course -> course.dishes().stream().anyMatch(dish -> dish.code().equals(dishCode))).findFirst();
    }

    /**
     * The whole menu the way the printed one reads: each course's title, then one dish a line with its portion and price
     * («• Клюквенный морс, 200 мл · 140 ₽»), a blank line between courses; a dish without a published price is listed
     * without one. The agents show it to the guest word for word, so no course is summed up in other words.
     */
    public String menuText() {
        List<String> lines = new ArrayList<>();
        for (Course course : courses) {
            if (!lines.isEmpty()) {
                lines.add("");
            }
            lines.add(course.title());
            for (Dish dish : course.dishes()) {
                String portion = dish.portion() == null || dish.portion().isBlank() ? "" : ", " + dish.portion().trim();
                String price = dish.priceRub() == null ? "" : " · " + dish.priceRub() + " ₽";
                lines.add("• " + dish.title() + portion + price);
            }
        }
        return String.join("\n", lines);
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
