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

    /**
     * The AERIS lunch menu, typed in by hand from the photo of the printed menu that Mikhail sent on 2026-10-06.
     * It stays unconfirmed until Mikhail has checked the prices against the menu himself.
     */
    @Test
    void theShippedAerisOfferIsThePrintedLunchMenu() {
        BusinessLunchCatalog catalog = new BusinessLunchCatalog(objectMapper);

        BusinessLunchOffer aeris = catalog.find("aeris").orElseThrow();

        assertThat(aeris.aLaCarte()).isTrue();
        assertThat(aeris.confirmedByVenue()).isFalse();
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
                        "Домашний клюквенный морс 140 200 мл",
                        "Вино дня 350 125 мл");
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

    /** #165: what is in a dish is read when the restaurant has written it, and is simply absent when it has not. Nothing is invented. */
    @Test
    void compositionAndAllergensAreOptionalFieldsOfADish() throws IOException {
        BusinessLunchOffer offer = read("""
                {"venueCode": "X", "days": ["MONDAY"], "from": "12:00", "to": "16:00",
                 "courses": [{"code": "SOUP", "title": "Суп", "dishes": [
                   {"code": "A", "title": "Суп А", "priceRub": 100, "portion": "300 г",
                    "ingredients": "вода, овощи", "allergens": "сельдерей", "canBeWithout": ["зелени", "сметаны"]},
                   {"code": "B", "title": "Суп Б", "priceRub": 100}
                 ]}], "sets": []}
                """);

        BusinessLunchOffer.Dish filled = offer.dish("A").orElseThrow();
        assertThat(filled.ingredients()).isEqualTo("вода, овощи");
        assertThat(filled.allergens()).isEqualTo("сельдерей");
        assertThat(filled.canBeWithout()).containsExactly("зелени", "сметаны");
        assertThat(filled.describesComposition()).isTrue();

        BusinessLunchOffer.Dish bare = offer.dish("B").orElseThrow();
        assertThat(bare.ingredients()).isNull();
        assertThat(bare.allergens()).isNull();
        assertThat(bare.canBeWithout()).isEmpty();
        assertThat(bare.describesComposition()).isFalse();

        // The shipped AERIS file carries no composition yet: the restaurant fills it in (MENU_COMPOSITION_FOR_BOT.md).
        BusinessLunchOffer aeris = new BusinessLunchCatalog(objectMapper).find("AERIS").orElseThrow();
        assertThat(aeris.courses().stream().flatMap(course -> course.dishes().stream())).noneMatch(BusinessLunchOffer.Dish::describesComposition);
    }

    /** #184: a dish may carry the number a waiter says aloud; it is optional, positive and unique within the offer. */
    @Test
    void aDishMayCarryASpokenNumberWhichIsUniqueAndPositive() throws IOException {
        BusinessLunchOffer offer = read("""
                {"venueCode": "X", "days": ["MONDAY"], "from": "12:00", "to": "16:00",
                 "courses": [{"code": "SOUP", "title": "Суп", "dishes": [
                   {"code": "A", "title": "Суп А", "priceRub": 100, "number": 14},
                   {"code": "B", "title": "Суп Б", "priceRub": 100}
                 ]}, {"code": "MAIN", "title": "Горячее", "dishes": [
                   {"code": "C", "title": "Котлеты", "priceRub": 300, "number": 3}
                 ]}], "sets": []}
                """);

        assertThat(offer.dish("A").orElseThrow().number()).isEqualTo(14);
        assertThat(offer.dish("A").orElseThrow().numbered()).isTrue();
        assertThat(offer.dish("B").orElseThrow().number()).isNull();
        assertThat(offer.dish("B").orElseThrow().numbered()).isFalse();
        assertThat(offer.dishByNumber(14).map(BusinessLunchOffer.Dish::code)).contains("A");
        assertThat(offer.dishByNumber(1)).isEmpty();
        assertThat(offer.numberedDishes()).extracting(BusinessLunchOffer.Dish::code).containsExactly("C", "A");

        assertThatThrownBy(() -> read("""
                {"venueCode": "X", "days": ["MONDAY"], "from": "12:00", "to": "16:00",
                 "courses": [{"code": "SOUP", "title": "Суп", "dishes": [
                   {"code": "A", "title": "Суп А", "priceRub": 100, "number": 14},
                   {"code": "B", "title": "Суп Б", "priceRub": 100, "number": 14},
                   {"code": "C", "title": "Суп В", "priceRub": 100, "number": 0}
                 ]}], "sets": []}
                """))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("dish number must be positive and unique: B")
                .hasMessageContaining("dish number must be positive and unique: C");

        // The shipped AERIS file carries no numbers yet: the technologist assigns them (MENU_COMPOSITION_FOR_BOT.md, #174).
        BusinessLunchOffer aeris = new BusinessLunchCatalog(objectMapper).find("AERIS").orElseThrow();
        assertThat(aeris.numberedDishes()).isEmpty();
        // The fixture does, in number order, and a number survives the composition fields.
        BusinessLunchOffer carte = BusinessLunchFixtures.aLaCarte();
        assertThat(carte.numberedDishes()).extracting(BusinessLunchOffer.Dish::number).containsExactly(1, 2, 11, 31, 32);
        assertThat(carte.dish("BORSCHT").orElseThrow().withNumber(null).number()).isNull();
        assertThat(carte.dish("BORSCHT").orElseThrow().ingredients()).isNotBlank();
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
