package museon_online.astor_butler.api.glasses;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** #184: the short order is a fixed grammar, no model — the table says what is and is not an order. */
class GlassesShortOrderGrammarTest {

    private static GlassesShortOrderGrammar.Item item(int number, int quantity) {
        return new GlassesShortOrderGrammar.Item(number, quantity);
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "забить 14 на 12            | 12 | 14",
            "Забить 14 на 12.           | 12 | 14",
            "Пробить 14 на стол 12      | 12 | 14",
            "заказ 14 на 12             | 12 | 14",
            "Заказ: 14 на 12            | 12 | 14",
            "забей 7 на 3               | 3  | 7",
            "14 на стол 12              | 12 | 14",
            "14 на 12                   | 12 | 14",
            "«14 на 12»                 | 12 | 14",
            "Стол 12: 14                | 12 | 14",
            "стол 12 — 14               | 12 | 14",
            "СТОЛ 5 14                  | 5  | 14",
            "забить 140 на 120          | 120 | 140",
    })
    void oneDishToOneTable(String spoken, String table, int number) {
        var order = GlassesShortOrderGrammar.parse(spoken);
        assertThat(order).as(spoken).isNotNull();
        assertThat(order.tableCode()).isEqualTo(table);
        assertThat(order.items()).containsExactly(item(number, 1));
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "забить 14 и 7 на 12        | 12 | 14,7",
            "пробить 14, 7 на 12        | 12 | 14,7",
            "14, 7 и 3 на стол 12       | 12 | 14,7,3",
            "Стол 12: 14, 7             | 12 | 14,7",
            "стол 12 — 14 и 7           | 12 | 14,7",
            "стол 12: 14 7 3            | 12 | 14,7,3",
    })
    void severalDishesInTheOrderTheyWereNamed(String spoken, String table, String numbers) {
        var order = GlassesShortOrderGrammar.parse(spoken);
        assertThat(order).as(spoken).isNotNull();
        assertThat(order.tableCode()).isEqualTo(table);
        assertThat(order.items()).extracting(GlassesShortOrderGrammar.Item::number)
                .containsExactly(java.util.Arrays.stream(numbers.split(",")).map(Integer::valueOf).toArray(Integer[]::new));
        assertThat(order.items()).allSatisfy(item -> assertThat(item.quantity()).isEqualTo(1));
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "забить 14 два раза на 12       | 12 | 14 | 2",
            "забить 14 два на 12            | 12 | 14 | 2",
            "забить 14 две на 12            | 12 | 14 | 2",
            "забить 14 три раза на 12       | 12 | 14 | 3",
            "забить 14 четыре на 12         | 12 | 14 | 4",
            "забить 14 пять раз на 12       | 12 | 14 | 5",
            "забить 14 ×2 на 12             | 12 | 14 | 2",
            "забить 14×3 на 12              | 12 | 14 | 3",
            "забить 14 x2 на 12             | 12 | 14 | 2",
            "забить 14 х 4 на 12            | 12 | 14 | 4",
            "забить 14 2 раза на 12         | 12 | 14 | 2",
            "14 два раза на стол 12         | 12 | 14 | 2",
            "стол 12: 14 два раза           | 12 | 14 | 2",
            "стол 12: 14 ×9                 | 12 | 14 | 9",
    })
    void aQuantityFollowsTheDishNumber(String spoken, String table, int number, int quantity) {
        var order = GlassesShortOrderGrammar.parse(spoken);
        assertThat(order).as(spoken).isNotNull();
        assertThat(order.tableCode()).isEqualTo(table);
        assertThat(order.items()).containsExactly(item(number, quantity));
    }

    @Test void quantitiesAndSeveralDishesCombine() {
        var order = GlassesShortOrderGrammar.parse("Забить 14 два раза, 7 и 3 ×2 на 12");
        assertThat(order).isNotNull();
        assertThat(order.tableCode()).isEqualTo("12");
        assertThat(order.items()).containsExactly(item(14, 2), item(7, 1), item(3, 2));
        assertThat(GlassesShortOrderGrammar.parse("стол 12: 14 ×2, 7 три раза").items())
                .containsExactly(item(14, 2), item(7, 3));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            // questions and tasks that mention tables or numbers
            "Что на стол 12?", "Какие блюда на 12", "Сколько гостей на 12", "Заказ на стол 12 готов?",
            "Задача: 14 на 12", "Поручи Анне принести воду на 5", "Перенести 14 на 12", "Счёт на 12",
            "с 12 на 14", "Что по бизнес-ланчу?", "Стол 12 свободен", "стол 12", "14", "на 12", "забить на 12",
            // broken numbers and quantities
            "забить 1234 на 12", "забить 14 на 1234", "забить 14 десять раз на 12", "забить 14 ×0 на 12",
            "забить 14 ×10 на 12", "забить 0 на 12", "забить 14 шесть раз на 12", "забить 14 и на 12",
            "забить четырнадцать на 12", "стол 12: борщ",
            // too many dishes
            "стол 12: 1, 2, 3, 4, 5, 6, 7",
            "", "   "
    })
    void anythingElseIsNotAnOrder(String spoken) {
        assertThat(GlassesShortOrderGrammar.parse(spoken)).as(spoken).isNull();
        assertThat(GlassesShortOrderGrammar.parse(null)).isNull();
    }

    @Test void theOrderKeepsTheTableAsSpokenAndTheDishesAsAList() {
        var order = GlassesShortOrderGrammar.parse("забить 14 на 12а");
        assertThat(order).isEqualTo(new GlassesShortOrderGrammar.Order("12а", List.of(item(14, 1))));
        assertThat(GlassesShortOrderGrammar.parse("стол 12: 1, 2, 3, 4, 5, 6").items()).hasSize(GlassesShortOrderGrammar.MAX_ITEMS);
    }
}
