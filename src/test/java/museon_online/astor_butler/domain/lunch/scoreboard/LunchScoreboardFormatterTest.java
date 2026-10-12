package museon_online.astor_butler.domain.lunch.scoreboard;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** The table as the staff chat reads it: counts, the 5★ share, the hit-parade, the wishes, and never a guest. */
class LunchScoreboardFormatterTest {

    private final LunchScoreboardFormatter formatter = new LunchScoreboardFormatter();

    @Test
    void anEmptyDaySaysSoAndStillCarriesThePlaceholderForTheMediaIdeas() {
        String text = formatter.format(LunchScoreboardFixtures.emptyDay());

        assertThat(text).startsWith("📊 <b>Табло ланча</b> · 12 октября, понедельник")
                .contains("Ланчей через Butler в этот день не было.")
                .doesNotContain("Оценок")
                .doesNotContain("Хит-парад")
                .doesNotContain("Пожелания гостей")
                .contains(LunchScoreboardFormatter.MEDIA_IDEAS_PLACEHOLDER)
                .endsWith("Табло по данным Butler. Решения за сменой.");
    }

    @Test
    void withoutBillsTheTableSaysWhyThereAreNoStarsAndNoDishes() {
        String text = formatter.format(LunchScoreboardFixtures.billingOff());

        assertThat(text).contains("Ланчей: 2, гостей: 3")
                .contains("Счета выключены (ASTOR_BILLING_ENABLED=false): оценок и блюд в табло нет.")
                .contains("Пожелания гостей: без лука")
                .doesNotContain("Оценок");
    }

    @Test
    void oneReviewIsOneOfOneAndAHundredPercent() {
        String text = formatter.format(LunchScoreboardFixtures.oneReview());

        assertThat(text).contains("Ланчей: 1, гостей: 2")
                .contains("Оценок: 1 из 1 визита · в среднем 5,0 · 5★: 1 из 1 (100 %) · 👍 1 / 👎 0")
                .contains("<b>Хит-парад блюд</b>\n1. Борщ — 2 порции · ★ 5,0 (мало данных)")
                .doesNotContain("Антирейтинг")
                .contains("Пожелания гостей: не было");
    }

    @Test
    void aMixedDayShowsTheShareOfFiveStarsTopThreeAndTheLowestRatedDish() {
        String text = formatter.format(LunchScoreboardFixtures.mixed());

        assertThat(text).contains("Ланчей: 5, гостей: 9")
                .contains("Оценок: 4 из 5 визитов · в среднем 4,3 · 5★: 3 из 4 (75 %) · 👍 3 / 👎 1")
                .contains("1. Борщ — 6 порций · ★ 4,0\n")
                .contains("2. Цезарь — 4 порции · ★ 5,0 (мало данных)\n")
                .contains("3. Котлета — 3 порции · без оценок\n")
                .doesNotContain("4. Окрошка")
                .contains("Антирейтинг: Окрошка — ★ 2,0 (мало данных)")
                .contains("Пожелания гостей: без лука; соус &lt;отдельно&gt;")
                .contains("Идеи для СМИ: соберём из отзывов после теста");
    }

    @Test
    void portionsAreCountedInRussian() {
        assertThat(LunchScoreboardFormatter.portions(1)).isEqualTo("1 порция");
        assertThat(LunchScoreboardFormatter.portions(3)).isEqualTo("3 порции");
        assertThat(LunchScoreboardFormatter.portions(5)).isEqualTo("5 порций");
        assertThat(LunchScoreboardFormatter.portions(11)).isEqualTo("11 порций");
        assertThat(LunchScoreboardFormatter.portions(22)).isEqualTo("22 порции");
    }
}
