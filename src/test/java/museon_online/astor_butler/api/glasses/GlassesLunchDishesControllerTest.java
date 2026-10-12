package museon_online.astor_butler.api.glasses;

import museon_online.astor_butler.domain.lunch.BusinessLunchCatalog;
import museon_online.astor_butler.domain.lunch.BusinessLunchFixtures;
import museon_online.astor_butler.domain.lunch.BusinessLunchOffer;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** #184: the numbered lunch dishes for the glasses runtime, behind the relay token, nothing but numbers, codes, titles. */
class GlassesLunchDishesControllerTest {
    private static final String TOKEN = "unit-relay-token-not-real";
    private final BusinessLunchCatalog catalog = new BusinessLunchCatalog(List.of(BusinessLunchFixtures.aLaCarte(),
            BusinessLunchFixtures.fullMenu()));
    private final GlassesLunchDishesController controller = new GlassesLunchDishesController(catalog, TOKEN);

    private MockHttpServletRequest request(String token) {
        var request = new MockHttpServletRequest("GET", "/api/internal/glasses/lunch-dishes");
        if (token != null) request.addHeader("X-Astor-Relay-Token", token);
        return request;
    }

    @SuppressWarnings("unchecked")
    private static String code(Object body) {
        return (String) ((Map<String, Object>) ((Map<String, Object>) body).get("error")).get("code");
    }

    @Test void withoutTheSharedTokenNothingIsListed() {
        assertThat(controller.dishes(request(null), "carte").getStatusCode().value()).isEqualTo(401);
        assertThat(controller.dishes(request("wrong-token-value-here"), "carte").getStatusCode().value()).isEqualTo(401);
        var unconfigured = new GlassesLunchDishesController(catalog, "short");
        var result = unconfigured.dishes(request("short"), "carte");
        assertThat(result.getStatusCode().value()).isEqualTo(503);
        assertThat(code(result.getBody())).isEqualTo("RELAY_UNAVAILABLE");
    }

    @Test void theNumberedDishesOfTheVenueInNumberOrder() {
        var result = controller.dishes(request(TOKEN), "CARTE");
        assertThat(result.getStatusCode().value()).isEqualTo(200);
        assertThat(result.getHeaders().getCacheControl()).isEqualTo("no-store");
        @SuppressWarnings("unchecked")
        var dishes = (List<GlassesLunchDishesController.NumberedDish>) result.getBody();
        assertThat(dishes).extracting(GlassesLunchDishesController.NumberedDish::number).isSorted();
        assertThat(dishes).extracting(GlassesLunchDishesController.NumberedDish::code)
                .containsExactly("NICOISE", "CAESAR", "BORSCHT", "MORS", "CAPPUCCINO");
        assertThat(dishes).extracting(GlassesLunchDishesController.NumberedDish::title).contains("Борщ со сметаной");
        // The same for the lower-case venue the runtime sends.
        assertThat(controller.dishes(request(TOKEN), "carte").getBody()).isEqualTo(result.getBody());
    }

    @Test void aVenueWithoutNumbersIsAnEmptyListAndAnUnknownVenueIsNotFound() {
        var result = controller.dishes(request(TOKEN), "aeris");
        assertThat(result.getStatusCode().value()).isEqualTo(200);
        assertThat((List<?>) result.getBody()).as("the fixture menu with sets carries no numbers").isEmpty();
        var missing = controller.dishes(request(TOKEN), "nowhere");
        assertThat(missing.getStatusCode().value()).isEqualTo(404);
        assertThat(code(missing.getBody())).isEqualTo("VENUE_NOT_FOUND");
        assertThat(controller.dishes(request(TOKEN), null).getStatusCode().value()).isEqualTo(400);
        assertThat(controller.dishes(request(TOKEN), " ").getStatusCode().value()).isEqualTo(400);
        assertThat(controller.dishes(request(TOKEN), "x".repeat(41)).getStatusCode().value()).isEqualTo(400);
    }

    @Test void theShippedAerisMenuHasNoNumbersYet() {
        var shipped = new GlassesLunchDishesController(new BusinessLunchCatalog(new com.fasterxml.jackson.databind.ObjectMapper()
                .findAndRegisterModules()), TOKEN);
        var result = shipped.dishes(request(TOKEN), "aeris");
        assertThat(result.getStatusCode().value()).isEqualTo(200);
        assertThat((List<?>) result.getBody()).isEmpty();
        BusinessLunchOffer offer = catalog.find("carte").orElseThrow();
        assertThat(offer.dishByNumber(11).map(BusinessLunchOffer.Dish::code)).contains("BORSCHT");
        assertThat(offer.dishByNumber(99)).isEmpty();
    }
}
