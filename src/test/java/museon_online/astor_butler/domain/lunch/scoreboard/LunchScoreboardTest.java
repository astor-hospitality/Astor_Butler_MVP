package museon_online.astor_butler.domain.lunch.scoreboard;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** The arithmetic behind the table: the 5★ share and which dish is the bottom one. */
class LunchScoreboardTest {

    @Test
    void theFiveStarShareIsAWholePercentOfTheAnsweredReviews() {
        assertThat(new LunchScoreboard.Reviews(0, 0, 0, 0, 0).fiveStarPercent()).isZero();
        assertThat(new LunchScoreboard.Reviews(5, 4.8, 4, 0, 0).fiveStarPercent()).isEqualTo(80);
        assertThat(new LunchScoreboard.Reviews(3, 4.0, 1, 0, 0).fiveStarPercent()).isEqualTo(33);
        assertThat(new LunchScoreboard.Reviews(3, 4.7, 2, 0, 0).fiveStarPercent()).isEqualTo(67);
        assertThat(new LunchScoreboard.Reviews(1, 5.0, 1, 0, 0).fiveStarPercent()).isEqualTo(100);
    }

    @Test
    void theBottomDishNeedsTwoRatedDishesAndADifference() {
        LunchScoreboard.Dish borsch = new LunchScoreboard.Dish("B", "Борщ", 5, 2, 4.5);
        LunchScoreboard.Dish cutlet = new LunchScoreboard.Dish("C", "Котлета", 4, 0, 0);
        LunchScoreboard.Dish okroshka = new LunchScoreboard.Dish("O", "Окрошка", 1, 1, 2.0);
        LunchScoreboard.Dish caesar = new LunchScoreboard.Dish("S", "Цезарь", 1, 1, 4.5);

        assertThat(board(List.of(borsch, cutlet)).worst()).isEmpty();
        assertThat(board(List.of(borsch, caesar)).worst()).isEmpty();
        assertThat(board(List.of(borsch, cutlet, okroshka)).worst()).contains(okroshka);
        assertThat(board(List.of()).worst()).isEmpty();
    }

    @Test
    void fewReviewsIsUnderThree() {
        assertThat(new LunchScoreboard.Dish("B", "Борщ", 1, 0, 0).fewReviews()).isFalse();
        assertThat(new LunchScoreboard.Dish("B", "Борщ", 1, 2, 5).fewReviews()).isTrue();
        assertThat(new LunchScoreboard.Dish("B", "Борщ", 1, 3, 5).fewReviews()).isFalse();
    }

    @Test
    void topIsNeverLongerThanTheList() {
        LunchScoreboard board = board(List.of(new LunchScoreboard.Dish("B", "Борщ", 1, 0, 0)));
        assertThat(board.top(3)).hasSize(1);
        assertThat(board.empty()).isFalse();
        assertThat(LunchScoreboardFixtures.emptyDay().empty()).isTrue();
    }

    private static LunchScoreboard board(List<LunchScoreboard.Dish> dishes) {
        return new LunchScoreboard(LunchScoreboardFixtures.MONDAY, "AERIS", 1, 1, 1, LunchScoreboard.Reviews.NONE, dishes, List.of(), true);
    }
}
