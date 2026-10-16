package museon_online.astor_butler.agent;

import museon_online.astor_butler.domain.lunch.BusinessLunchOffer;
import museon_online.astor_butler.domain.lunch.BusinessLunchOffer.Course;
import museon_online.astor_butler.domain.lunch.BusinessLunchOffer.Dish;
import org.junit.jupiter.api.Test;

import java.time.DayOfWeek;
import java.time.LocalTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class LunchDishNamesTest {

    static final BusinessLunchOffer AERIS = new BusinessLunchOffer("AERIS", "AERIS", true,
            List.of(DayOfWeek.FRIDAY), LocalTime.of(12, 0), LocalTime.of(16, 0), 90, null, List.of(
            new Course("SALAD", "Салаты", List.of(new Dish("SALAD_COLESLAW", "Салат коул слоу", 390, null),
                    new Dish("SALAD_HERRING_APPLE", "Салат с сельдью и яблоком", 420, null))),
            new Course("DESSERT", "Десерты", List.of(new Dish("CHEESECAKE_SAN_SEBASTIAN", "Чизкейк Сан-Себастьян", 350, null))),
            new Course("DRINKS", "Напитки", List.of(new Dish("ESPRESSO", "Эспрессо", 150, null)))),
            List.of());

    /** Live test 16.10: the guest was asked for dish codes. */
    @Test
    void aCodeInTheReplyBecomesTheDishTitle() {
        String live = "Напишите коды блюд, например SALAD_COLESLAW или CHEESECAKE_SAN_SEBASTIAN.";

        assertThat(LunchDishNames.replaceCodes(live, AERIS))
                .isEqualTo("Напишите коды блюд, например Салат коул слоу или Чизкейк Сан-Себастьян.")
                .doesNotContain("SALAD_", "CHEESECAKE_");
    }

    @Test
    void aCodeBesideItsTitleIsDropped() {
        assertThat(LunchDishNames.replaceCodes("Салат коул слоу (SALAD_COLESLAW) – 390 ₽", AERIS))
                .isEqualTo("Салат коул слоу – 390 ₽");
        assertThat(LunchDishNames.replaceCodes("Салат с сельдью и яблоком — `SALAD_HERRING_APPLE`, 2 шт.", AERIS))
                .isEqualTo("Салат с сельдью и яблоком, 2 шт.");
        assertThat(LunchDishNames.replaceCodes("салат (salad_herring_apple) x2", AERIS))
                .isEqualTo("салат (Салат с сельдью и яблоком) x2");
    }

    @Test
    void anOrdinaryWordIsNotTouched() {
        String text = "Эспрессо или espresso? Салат коул слоу – 390 ₽.";
        assertThat(LunchDishNames.replaceCodes(text, AERIS)).isEqualTo(text);
        assertThat(LunchDishNames.replaceCodes("ESPRESSO, 150 ₽", AERIS)).isEqualTo("Эспрессо, 150 ₽");
        assertThat(LunchDishNames.replaceCodes(null, AERIS)).isNull();
    }
}
