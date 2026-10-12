package museon_online.astor_butler.domain.lunch.scoreboard;

import java.time.LocalDate;
import java.util.List;

/** Scoreboards for the formatter tests; the dishes are made up and belong to no real venue. */
final class LunchScoreboardFixtures {

    static final LocalDate MONDAY = LocalDate.of(2026, 10, 12);

    private LunchScoreboardFixtures() {
    }

    static LunchScoreboard emptyDay() {
        return new LunchScoreboard(MONDAY, "AERIS", 0, 0, 0, LunchScoreboard.Reviews.NONE, List.of(), List.of(), true);
    }

    static LunchScoreboard billingOff() {
        return new LunchScoreboard(MONDAY, "AERIS", 2, 3, 0, LunchScoreboard.Reviews.NONE, List.of(), List.of("без лука"), false);
    }

    /** One lunch, one bill, one 5★ review with a thumb up. */
    static LunchScoreboard oneReview() {
        return new LunchScoreboard(MONDAY, "AERIS", 1, 2, 1,
                new LunchScoreboard.Reviews(1, 5.0, 1, 1, 0),
                List.of(new LunchScoreboard.Dish("BORSCH", "Борщ", 2, 1, 5.0)),
                List.of(), true);
    }

    /** Five lunches, four rated visits (5, 5, 5, 2), a dish nobody rated, a dish rated low, one wish with markup in it. */
    static LunchScoreboard mixed() {
        return new LunchScoreboard(MONDAY, "AERIS", 5, 9, 5,
                new LunchScoreboard.Reviews(4, 4.25, 3, 3, 1),
                List.of(
                        new LunchScoreboard.Dish("BORSCH", "Борщ", 6, 3, 4.0),
                        new LunchScoreboard.Dish("CAESAR", "Цезарь", 4, 2, 5.0),
                        new LunchScoreboard.Dish("CUTLET", "Котлета", 3, 0, 0),
                        new LunchScoreboard.Dish("OKROSHKA", "Окрошка", 1, 1, 2.0)),
                List.of("без лука", "соус <отдельно>"), true);
    }
}
