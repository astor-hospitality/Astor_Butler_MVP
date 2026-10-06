package museon_online.astor_butler.domain.lunch;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.DayOfWeek;
import java.time.LocalTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BusinessLunchCatalogTest {

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    /** The AERIS lunch menu as printed, from the photo Mikhail sent on 2026-10-06. Wine of the day is left out on purpose. */
    @Test
    void theShippedAerisOfferIsThePrintedLunchMenu() {
        BusinessLunchCatalog catalog = new BusinessLunchCatalog(objectMapper);

        BusinessLunchOffer aeris = catalog.find("aeris").orElseThrow();

        assertThat(aeris.aLaCarte()).isTrue();
        assertThat(aeris.confirmedByVenue()).isTrue();
        assertThat(aeris.days()).containsExactly(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY);
        assertThat(aeris.from()).isEqualTo(LocalTime.of(12, 0));
        assertThat(aeris.to()).isEqualTo(LocalTime.of(16, 0));
        assertThat(aeris.seating()).isEqualTo(90);
        assertThat(aeris.courses()).extracting(BusinessLunchOffer.Course::title).containsExactly("Салаты", "Суп", "Горячее", "Десерты", "Напитки");
        assertThat(aeris.courses().stream().flatMap(course -> course.dishes().stream()).map(dish -> dish.title() + " " + dish.priceRub() + " " + dish.portion()))
                .containsExactly(
                        "Нисуаз 290 170 г",
                        "Цезарь с цыплёнком 290 130 г",
                        "Борщ со сметаной 270 320 г",
                        "Куриный бульон с домашней лапшой 230 300 г",
                        "Треска с соусом тартар 520 150 г",
                        "Куриные котлеты с картофельным пюре 370 200 г",
                        "Бефстроганов с картофельным пюре 570 250 г",
                        "Медовик 220 90 г",
                        "Чай ассам 210 500 мл",
                        "Чай сенча 210 500 мл",
                        "Эспрессо / лунго 160 null",
                        "Капучино 220 null",
                        "Лимонад малина-бузина 200 null",
                        "Лимонад базилик-жасмин 200 null",
                        "Лимонад маракуйя-ананас 200 null",
                        "Домашний клюквенный морс 140 200 мл");
        assertThat(catalog.find("NOWHERE")).isEmpty();
    }

    @Test
    void aMenuWithoutSetsNeedsAPriceOnEveryDish() {
        assertThatThrownBy(() -> read("""
                {"venueCode": "X", "days": ["MONDAY"], "from": "12:00", "to": "16:00",
                 "courses": [{"code": "SOUP", "title": "Суп", "dishes": [{"code": "BORSCHT", "title": "Борщ"}, {"code": "BROTH", "title": "Бульон", "priceRub": -5}]}],
                 "sets": []}
                """))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("a dish needs a price when there are no sets: BORSCHT")
                .hasMessageContaining("dish price must be positive or absent: BROTH");
    }

    @Test
    void aSlotOffersTheDishesOfEveryCourseItAllows() {
        BusinessLunchOffer offer = BusinessLunchFixtures.fullMenu();
        BusinessLunchOffer.LunchSet starterAndMain = offer.set("starter_main").orElseThrow();

        assertThat(offer.slotTitle(starterAndMain, 0)).isEqualTo("Салат или суп");
        assertThat(offer.dishesFor(starterAndMain, 0)).extracting(BusinessLunchOffer.Dish::code)
                .containsExactly("GREEK", "BEETROOT", "BROTH", "PUMPKIN");
        assertThat(offer.slotTitle(starterAndMain, 1)).isEqualTo("Горячее");
        assertThat(offer.courseOf("PUMPKIN").map(BusinessLunchOffer.Course::code)).contains("SOUP");
    }

    @Test
    void anOfferThatCannotBeOrderedFromIsRefusedWithTheReason() {
        assertThatThrownBy(() -> read("""
                {"venueCode": "X", "days": ["MONDAY"], "from": "16:00", "to": "12:00",
                 "courses": [{"code": "SOUP", "title": "Суп", "dishes": []}],
                 "sets": [{"code": "A", "title": "Суп + горячее", "priceRub": 0, "slots": [["SOUP"], ["MAIN"]]}]}
                """))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("from must be earlier than to")
                .hasMessageContaining("course has no dishes: SOUP")
                .hasMessageContaining("set price must be positive or absent: A")
                .hasMessageContaining("set slot names an unknown course: A [MAIN]");
    }

    @Test
    void checksFindNothingWrongWithTheFixtures() {
        assertThat(BusinessLunchCatalog.problems(BusinessLunchFixtures.fullMenu())).isEmpty();
        assertThat(BusinessLunchCatalog.problems(BusinessLunchFixtures.dishOfTheDay())).isEmpty();
        assertThat(BusinessLunchCatalog.problems(BusinessLunchFixtures.aLaCarte())).isEmpty();
        assertThat(new BusinessLunchCatalog(List.of(BusinessLunchFixtures.dishOfTheDay())).find("simple")).isPresent();
    }

    private BusinessLunchOffer read(String json) throws IOException {
        try (InputStream stream = new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8))) {
            return BusinessLunchCatalog.read(objectMapper, stream);
        }
    }
}
