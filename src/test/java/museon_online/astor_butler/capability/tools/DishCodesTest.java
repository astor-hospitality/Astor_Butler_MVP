package museon_online.astor_butler.capability.tools;

import museon_online.astor_butler.domain.lunch.BusinessLunchOffer;
import museon_online.astor_butler.domain.lunch.BusinessLunchOffer.Course;
import museon_online.astor_butler.domain.lunch.BusinessLunchOffer.Dish;
import org.junit.jupiter.api.Test;

import java.time.DayOfWeek;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class DishCodesTest {

    /** The AERIS lunch menu codes and titles (business-lunch/aeris.json). */
    private static final BusinessLunchOffer AERIS = new BusinessLunchOffer("AERIS", "AERIS", true,
            List.of(DayOfWeek.FRIDAY), LocalTime.of(12, 0), LocalTime.of(16, 0), 90, null, List.of(
            new Course("SALAD", "Салаты", List.of(new Dish("SALAD_COLESLAW", "Салат коул слоу"),
                    new Dish("SALAD_HERRING_APPLE", "Салат с сельдью и яблоком"), new Dish("SALAD_BLINI", "Блинный салат"))),
            new Course("SOUP", "Суп", List.of(new Dish("PEA_SOUP_SMOKED", "Гороховый суп с копчёностями"),
                    new Dish("MUSHROOM_CREAM_SOUP", "Крем-суп грибной"))),
            new Course("MAIN", "Горячее", List.of(new Dish("PASTA_BOLOGNESE", "Паста болоньезе"),
                    new Dish("FISH_CUTLETS", "Рыбные котлеты с картофельным пюре"),
                    new Dish("CHICKEN_SKEWERS", "Шашлычок из куриного бедра с фасолью и болгарским перцем"))),
            new Course("DESSERT", "Десерты", List.of(new Dish("CHEESECAKE_SAN_SEBASTIAN", "Чизкейк Сан-Себастьян"))),
            new Course("DRINKS", "Напитки", List.of(new Dish("ESPRESSO", "Эспрессо"), new Dish("LUNGO", "Лунго"),
                    new Dish("TEA_ASSAM", "Чай ассам, 500 мл"), new Dish("TEA_SENCHA", "Чай сенча, 500 мл"),
                    new Dish("CAPPUCCINO", "Капучино"), new Dish("LEMONADE_RASPBERRY", "Лимонад малина-бузина, 250 мл"),
                    new Dish("LEMONADE_CLASSIC", "Лимонад классический"), new Dish("CRANBERRY_MORS", "Клюквенный морс, 200 мл")))),
            List.of());

    @Test
    void readsTheLiveTestCodesAsTheDishesTheyMean() {
        // Live test 16.10: the model sent these for «салат, суп, болоньезе, чизкейк, эспрессо».
        assertThat(resolve("COLSLAW")).contains("SALAD_COLESLAW");
        assertThat(resolve("PEAS_SOUP")).contains("PEA_SOUP_SMOKED");
        assertThat(resolve("BOLOGNESE")).contains("PASTA_BOLOGNESE");
        assertThat(resolve("CHEESECAKE")).contains("CHEESECAKE_SAN_SEBASTIAN");
        assertThat(resolve("ESPRESSO")).contains("ESPRESSO");
        assertThat(resolve("SALAD_COLESLAW")).contains("SALAD_COLESLAW");
    }

    @Test
    void readsTitlesInTheGuestsWords() {
        assertThat(resolve("паста болоньезе")).contains("PASTA_BOLOGNESE");
        assertThat(resolve("крем-суп")).contains("MUSHROOM_CREAM_SOUP");
        assertThat(resolve("морс")).contains("CRANBERRY_MORS");
        assertThat(resolve("капучино")).contains("CAPPUCCINO");
    }

    @Test
    void neverGuessesBetweenDishesOrOutsideTheMenu() {
        assertThat(resolve("SOUP")).isEmpty();
        assertThat(resolve("LEMONADE")).isEmpty();
        assertThat(resolve("TEA")).isEmpty();
        assertThat(resolve("PIZZA")).isEmpty();
        assertThat(resolve("CAVIAR")).isEmpty();
        assertThat(resolve("COLESLAW_PIZZA")).isEmpty();
        assertThat(DishCodes.menu(AERIS)).hasSize(17).first().isEqualTo(java.util.Map.of("code", "SALAD_COLESLAW",
                "title", "Салат коул слоу"));
    }

    private static Optional<String> resolve(String code) {
        return DishCodes.resolve(AERIS, code);
    }
}
