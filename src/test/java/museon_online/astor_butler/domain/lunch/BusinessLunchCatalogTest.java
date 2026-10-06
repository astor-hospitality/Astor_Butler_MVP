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

    @Test
    void theShippedAerisOfferIsUsableAndSaysItIsNotConfirmedByTheVenue() {
        BusinessLunchCatalog catalog = new BusinessLunchCatalog(objectMapper);

        BusinessLunchOffer aeris = catalog.find("aeris").orElseThrow();

        assertThat(aeris.confirmedByVenue()).isFalse();
        assertThat(aeris.days()).containsExactly(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY);
        assertThat(aeris.from()).isEqualTo(LocalTime.of(12, 0));
        assertThat(aeris.to()).isEqualTo(LocalTime.of(16, 0));
        assertThat(aeris.seating()).isEqualTo(90);
        assertThat(aeris.sets()).extracting(BusinessLunchOffer.LunchSet::title)
                .containsExactly("Салат + суп", "Салат или суп + горячее", "Салат + суп + горячее");
        // No price is published until the venue gives one.
        assertThat(aeris.sets()).extracting(BusinessLunchOffer.LunchSet::priceRub).containsOnlyNulls();
        assertThat(catalog.find("NOWHERE")).isEmpty();
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
        assertThat(new BusinessLunchCatalog(List.of(BusinessLunchFixtures.dishOfTheDay())).find("simple")).isPresent();
    }

    private BusinessLunchOffer read(String json) throws IOException {
        try (InputStream stream = new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8))) {
            return BusinessLunchCatalog.read(objectMapper, stream);
        }
    }
}
