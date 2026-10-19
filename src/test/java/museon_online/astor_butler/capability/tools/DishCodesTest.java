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
        // Prod run 16.10, phase 2: «крем-суп и морс» came as drink_mors.
        assertThat(resolve("drink_mors")).contains("CRANBERRY_MORS");
        assertThat(resolve("SOUP_MUSHROOM")).contains("MUSHROOM_CREAM_SOUP");
        assertThat(resolve("MAIN_BOLOGNESE")).contains("PASTA_BOLOGNESE");
        assertThat(resolve("DESSERT_CHEESECAKE")).contains("CHEESECAKE_SAN_SEBASTIAN");
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
        assertThat(resolve("DRINK")).isEmpty();
        assertThat(resolve("SALAD")).isEmpty();
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

    /** Live test 16.10: «SALAD_HERRING_APPLE 2шт» was ordered once. */
    @Test
    void aCountWrittenWithTheDishIsReadOffIt() {
        assertThat(DishCodes.portion("SALAD_HERRING_APPLE 2шт")).isEqualTo(new DishCodes.Portion("SALAD_HERRING_APPLE", 2));
        assertThat(DishCodes.portion("борщ x2")).isEqualTo(new DishCodes.Portion("борщ", 2));
        assertThat(DishCodes.portion("борщ ×3")).isEqualTo(new DishCodes.Portion("борщ", 3));
        assertThat(DishCodes.portion("суп 2 порции")).isEqualTo(new DishCodes.Portion("суп", 2));
        assertThat(DishCodes.portion("Паста болоньезе, 2 шт.")).isEqualTo(new DishCodes.Portion("Паста болоньезе", 2));
        assertThat(DishCodes.portion("2 x щука")).isEqualTo(new DishCodes.Portion("щука", 2));
        assertThat(DishCodes.portion("3 порции борща")).isEqualTo(new DishCodes.Portion("борща", 3));
        assertThat(DishCodes.portion("SALAD_COLESLAW")).isEqualTo(new DishCodes.Portion("SALAD_COLESLAW", 1));
    }

    @Test
    void aNumberThatIsPartOfTheDishIsNoCount() {
        assertThat(DishCodes.portion("Чай ассам, 500 мл")).isEqualTo(new DishCodes.Portion("Чай ассам, 500 мл", 1));
        assertThat(DishCodes.portion("Клюквенный морс, 200 мл")).isEqualTo(new DishCodes.Portion("Клюквенный морс, 200 мл", 1));
        assertThat(resolve(DishCodes.portion("SALAD_HERRING_APPLE 2шт").dish())).contains("SALAD_HERRING_APPLE");
    }

    @Test
    void theTitleOfACodeIsTheMenus() {
        assertThat(DishCodes.title(AERIS, "SALAD_COLESLAW")).isEqualTo("Салат коул слоу");
        assertThat(DishCodes.title(AERIS, "NOPE")).isEqualTo("NOPE");
    }
}
