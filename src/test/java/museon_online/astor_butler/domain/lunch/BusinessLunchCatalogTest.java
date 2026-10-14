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
     * The AERIS lunch menu as the Saby price list «Бизнес ланчи» (point «Гастробар AERIS») had it on 2026-10-13.
     * Guests read the clean title; Presto is searched by the exact Saby name, suffixes («ЛАНЧ», «500 мл») and all.
     * It stays unconfirmed until the venue has checked the menu itself.
     */
    @Test
    void theShippedAerisOfferIsTheSabyLunchPriceList() {
        BusinessLunchCatalog catalog = new BusinessLunchCatalog(objectMapper);

        BusinessLunchOffer aeris = catalog.find("aeris").orElseThrow();

        assertThat(aeris.aLaCarte()).isTrue();
        assertThat(aeris.confirmedByVenue()).isFalse();
        assertThat(aeris.days()).containsExactly(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY);
        assertThat(aeris.from()).isEqualTo(LocalTime.of(12, 0));
        assertThat(aeris.to()).isEqualTo(LocalTime.of(16, 0));
        assertThat(aeris.seating()).isEqualTo(90);
        assertThat(aeris.courses()).extracting(BusinessLunchOffer.Course::title).containsExactly("Салаты", "Суп", "Горячее", "Десерты", "Напитки");
        assertThat(aeris.courses().stream().flatMap(course -> course.dishes().stream())
                .map(dish -> dish.title() + " | " + dish.sabyLookupName() + " | " + dish.priceRub() + " | " + dish.portion()))
                .containsExactly(
                        "Салат коул слоу | Салат коул слоу ЛАНЧ | 230 | null",
                        "Салат с сельдью и яблоком | Салат с сельдью и яблоком Ланч | 280 | null",
                        "Блинный салат | Блинный салат Ланч | 280 | null",
                        "Гороховый суп с копченостями | Гороховый суп с копченостями ЛАНЧ | 290 | null",
                        "Крем-суп грибной | Крем суп грибной Ланч | 230 | null",
                        "Паста болоньезе | Паста болоньезе Ланч | 420 | null",
                        "Рыбные котлеты с картофельным пюре | Рыбные котлеты с картофельным пюре ЛАНЧ | 490 | null",
                        "Шашлычок из куриного бедра с фасолью и болгарским перцем | Шашлычок из куриного бедра с фасолью и болгарским перцем ЛАНЧ | 450 | null",
                        "Чизкейк Сан-Себастьян | Чизкейк Сан-Себастьян ЛАНЧ | 250 | null",
                        "Эспрессо | Эспрессо | 160 | null",
                        "Лунго | Лунго | 160 | null",
                        "Чай ассам | Ассам 500 мл | 210 | 500 мл",
                        "Чай сенча | Сенча 500 мл | 210 | 500 мл",
                        "Капучино | Капучино | 220 | null",
                        "Лимонад малина-бузина | Лимонад малина-бузина 250 мл | 200 | 250 мл",
                        "Лимонад базилик-жасмин | Лимонад базилик жасмин 250 мл | 200 | 250 мл",
                        "Лимонад маракуйя-ананас | Лимонад маракуйя ананас 250 мл | 200 | 250 мл",
                        "Лимонад классический | Лимонад классический | 200 | null",
                        "Лимонад цитрусовый | Лимонад цитрусовый 250мл | 200 | 250 мл",
                        "Клюквенный морс | Морс клюквенный 200 мл | 140 | 200 мл",
                        "Вино дня белое | Вино дня белое 125 мл | 350 | 125 мл",
                        "Вино дня красное | Вино дня красное 125 мл | 350 | 125 мл");
        assertThat(catalog.find("NOWHERE")).isEmpty();
    }

    /** The catalog reads every JSON in business-lunch/: a file there that is not an offer is logged as broken at start-up. */
    @Test
    void everyShippedFileInTheLunchFolderIsAUsableOffer() throws IOException {
        org.springframework.core.io.Resource[] files = new org.springframework.core.io.support.PathMatchingResourcePatternResolver()
                .getResources("classpath*:business-lunch/*.json");

        assertThat(files).isNotEmpty();
        for (org.springframework.core.io.Resource file : files) {
            try (InputStream json = file.getInputStream()) {
                assertThat(BusinessLunchCatalog.read(objectMapper, json)).as(file.getFilename()).isNotNull();
            }
        }
    }

    /** Every dish in the shipped AERIS file can be shown to a guest and ordered: a title and a positive price. */
    @Test
    void everyShippedAerisDishHasATitleAndAPrice() throws IOException {
        BusinessLunchOffer aeris;
        try (InputStream json = getClass().getResourceAsStream("/business-lunch/aeris.json")) {
            aeris = BusinessLunchCatalog.read(objectMapper, json);
        }

        List<BusinessLunchOffer.Dish> dishes = aeris.courses().stream().flatMap(course -> course.dishes().stream()).toList();
        assertThat(dishes).isNotEmpty();
        assertThat(dishes).allSatisfy(dish -> {
            assertThat(dish.code()).isNotBlank();
            assertThat(dish.title()).isNotBlank();
            assertThat(dish.priceRub()).isNotNull().isPositive();
            assertThat(dish.sabyLookupName()).isNotBlank();
        });
        // The guest never sees the Presto suffix.
        assertThat(dishes).extracting(BusinessLunchOffer.Dish::title).noneMatch(title -> title.toLowerCase().contains("ланч"));
    }

    /** Menus written before sabyName keep looking Saby up by the title; a blank sabyName counts as absent. */
    @Test
    void theSabyNameIsOptionalAndFallsBackToTheTitle() throws IOException {
        BusinessLunchOffer offer = read("""
                {"venueCode": "TEST", "days": ["MONDAY"], "from": "12:00", "to": "16:00",
                 "courses": [{"code": "SOUP", "title": "Суп", "dishes": [
                   {"code": "BORSCHT", "title": "Борщ", "priceRub": 270},
                   {"code": "PEA", "title": "Гороховый суп", "sabyName": "Гороховый суп ЛАНЧ", "priceRub": 290},
                   {"code": "BLANK", "title": "Солянка", "sabyName": "  ", "priceRub": 300}]}]}
                """);

        assertThat(offer.dish("BORSCHT").orElseThrow().sabyName()).isNull();
        assertThat(offer.dish("BORSCHT").orElseThrow().sabyLookupName()).isEqualTo("Борщ");
        assertThat(offer.dish("PEA").orElseThrow().title()).isEqualTo("Гороховый суп");
        assertThat(offer.dish("PEA").orElseThrow().sabyLookupName()).isEqualTo("Гороховый суп ЛАНЧ");
        assertThat(offer.dish("PEA").orElseThrow().withNumber(7).sabyName()).isEqualTo("Гороховый суп ЛАНЧ");
        assertThat(offer.dish("BLANK").orElseThrow().sabyLookupName()).isEqualTo("Солянка");
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
