package museon_online.astor_butler.integration.saby;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import museon_online.astor_butler.domain.lunch.BusinessLunchOrder;
import museon_online.astor_butler.domain.lunch.ExternalLunchOrderProvider;
import org.junit.jupiter.api.Test;
import org.springframework.boot.restclient.RestTemplateBuilder;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.queryParam;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * The lunch dishes go into the booking Butler already wrote to Saby, as one update with nomenclatures;
 * whatever Presto does not know by name keeps the whole order with the hostess.
 */
class SabyLunchOrderProviderTest {

    private static final String AUTH_URL = "https://auth.saby.test/oauth/service/";
    private static final String BOOKING_ID = "43bd679d-ec4a-43f5-9214-b740ef5a3ba6";
    private static final String UPDATE_URL = "https://api.saby.test/retail/order/" + BOOKING_ID + "/update";
    private static final String PRICE_LISTS = "{\"priceLists\": [{\"id\": 4, \"name\": \"Основное меню\"}], \"outcome\": {\"hasMore\": false}}";

    @Test
    void isOffUntilSomeoneSwitchesItOn() {
        Fixture fixture = fixture(new SabyReservationProperties());

        assertThat(fixture.provider().providerId()).isEqualTo("SABY");
        assertThat(fixture.provider().status().enabled()).isFalse();
        assertThat(fixture.provider().submit(order(List.of(dish("Борщ со сметаной", 2))), "astor-lunch-1").status()).isEqualTo("PROVIDER_NOT_CONFIGURED");
        fixture.server().verify();
    }

    @Test
    void readOnlyModeSendsNothingAndLeavesTheOrderToStaff() {
        SabyReservationProperties properties = configured();
        properties.setWriteEnabled(false);
        Fixture fixture = fixture(properties);

        ExternalLunchOrderProvider.Result result = fixture.provider().submit(order(List.of(dish("Борщ со сметаной", 2))), "astor-lunch-1");

        assertThat(result.accepted()).isFalse();
        assertThat(result.attempted()).isTrue();
        assertThat(result.status()).isEqualTo("SABY_WRITE_DISABLED");
        fixture.server().verify();
    }

    @Test
    void withoutASabyBookingTheDishesCannotBeAttached() {
        Fixture fixture = fixture(writable());

        ExternalLunchOrderProvider.Result result = fixture.provider().submit(order(null, List.of(dish("Борщ со сметаной", 2))), "astor-lunch-1");

        assertThat(result.accepted()).isFalse();
        assertThat(result.status()).isEqualTo("NO_SABY_BOOKING");
        fixture.server().verify();
    }

    @Test
    void attachesTheDishesToTheExistingBookingByExactTitle() {
        Fixture fixture = fixture(writable());
        expectAuth(fixture.server());
        fixture.server().expect(once(), requestTo(containsString("/retail/nomenclature/price-list")))
                .andExpect(queryParam("pointId", "206"))
                .andRespond(withSuccess(PRICE_LISTS, MediaType.APPLICATION_JSON));
        fixture.server().expect(once(), requestTo(containsString("/retail/v2/nomenclature/list")))
                .andExpect(queryParam("priceListId", "4"))
                .andRespond(withSuccess("{\"nomenclatures\": ["
                        + "{\"id\": 10, \"name\": \"Супы\", \"isParent\": true},"
                        + "{\"id\": 31, \"name\": \"Борщ со сметаной\", \"cost\": 270, \"isParent\": false}]}", MediaType.APPLICATION_JSON));
        fixture.server().expect(once(), requestTo(containsString("/retail/v2/nomenclature/list")))
                .andRespond(withSuccess("[{\"id\": 55, \"name\": \"Медовик\", \"cost\": 220}]", MediaType.APPLICATION_JSON));
        expectBooking(fixture.server(), "[]");
        fixture.server().expect(once(), requestTo(UPDATE_URL))
                .andExpect(method(HttpMethod.PUT))
                .andExpect(jsonPath("$.product").value("restaurant"))
                .andExpect(jsonPath("$.datetime").value("2026-10-07 13:00:00"))
                .andExpect(jsonPath("$.booking.visitors").value(2))
                .andExpect(jsonPath("$.booking.table").value(42))
                .andExpect(jsonPath("$.booking.woTable").value(false))
                .andExpect(jsonPath("$.customer.email").value("guest@example.test"))
                .andExpect(jsonPath("$.customer.phone").value("79990000000"))
                .andExpect(jsonPath("$.nomenclatures[0].id").value(31))
                .andExpect(jsonPath("$.nomenclatures[0].count").value(2))
                .andExpect(jsonPath("$.nomenclatures[0].priceListId").value(4))
                .andExpect(jsonPath("$.nomenclatures[1].id").value(55))
                .andExpect(jsonPath("$.nomenclatures[1].count").value(1))
                .andExpect(jsonPath("$.comment").value(containsString("Astor Butler #77")))
                .andRespond(withSuccess("", MediaType.APPLICATION_JSON));

        ExternalLunchOrderProvider.Result result = fixture.provider()
                .submit(order(List.of(dish("Борщ со сметаной", 2), dish("Медовик", 1))), "astor-lunch-77");

        assertThat(result.accepted()).isTrue();
        assertThat(result.status()).isEqualTo("SABY_LUNCH_ATTACHED");
        assertThat(result.externalOrderId()).isEqualTo(BOOKING_ID);
        fixture.server().verify();
    }

    /** #165: the guest's wish goes into the booking comment Presto shows, after what Butler wrote there, marked as something to agree. */
    @Test
    void theGuestsWishIsWrittenIntoTheBookingCommentWhenItIsNotThereYet() {
        SabyReservationProperties properties = writable();
        properties.setPriceListId("4");
        Fixture fixture = fixture(properties);
        expectAuth(fixture.server());
        fixture.server().expect(once(), requestTo(containsString("/retail/v2/nomenclature/list")))
                .andRespond(withSuccess("{\"nomenclatures\": [{\"id\": 31, \"name\": \"Борщ со сметаной\", \"cost\": 270}]}", MediaType.APPLICATION_JSON));
        expectBooking(fixture.server(), "[]");
        fixture.server().expect(once(), requestTo(UPDATE_URL))
                .andExpect(method(HttpMethod.PUT))
                .andExpect(jsonPath("$.nomenclatures[0].id").value(31))
                .andExpect(jsonPath("$.comment").value("Hostess note · Astor Butler #77 · Пожелание гостя: без лука — согласовать с кухней"))
                .andRespond(withSuccess("", MediaType.APPLICATION_JSON));

        ExternalLunchOrderProvider.Result result = fixture.provider()
                .submit(order(BOOKING_ID, List.of(dish("Борщ со сметаной", 2)), "без лука"), "astor-lunch-77");

        assertThat(result.accepted()).isTrue();
        fixture.server().verify();
    }

    @Test
    void theWishIsNotWrittenTwiceAndNeverPushesTheButlerMarkerOut() {
        ObjectMapper mapper = new ObjectMapper();
        ObjectNode carried = mapper.createObjectNode().put("comment", "Борщ × 1. Пожелание гостя: без лука — согласовать с кухней. · Astor Butler #77");
        SabyLunchOrderProvider.withWishes(carried, "без лука");
        assertThat(carried.get("comment").asText()).isEqualTo("Борщ × 1. Пожелание гостя: без лука — согласовать с кухней. · Astor Butler #77");

        ObjectNode none = mapper.createObjectNode().put("comment", "Hostess note · Astor Butler #77");
        SabyLunchOrderProvider.withWishes(none, "  ");
        assertThat(none.get("comment").asText()).isEqualTo("Hostess note · Astor Butler #77");

        ObjectNode empty = mapper.createObjectNode();
        SabyLunchOrderProvider.withWishes(empty, "соус  отдельно");
        assertThat(empty.get("comment").asText()).isEqualTo("Пожелание гостя: соус отдельно — согласовать с кухней");

        ObjectNode longWish = mapper.createObjectNode().put("comment", "Hostess note · Astor Butler #77");
        SabyLunchOrderProvider.withWishes(longWish, "без лука ".repeat(40).trim());
        String cut = longWish.get("comment").asText();
        assertThat(cut).hasSizeLessThanOrEqualTo(SabyOrderPayload.MAX_GUEST_COMMENT)
                .startsWith("Hostess note · Astor Butler #77 · Пожелание гостя: без лука")
                .contains("… — согласовать с кухней");

        ObjectNode full = mapper.createObjectNode().put("comment", "x".repeat(SabyOrderPayload.MAX_GUEST_COMMENT - 20) + " · Astor Butler #77");
        SabyLunchOrderProvider.withWishes(full, "без лука");
        assertThat(full.get("comment").asText()).endsWith("Astor Butler #77").doesNotContain("Пожелание гостя");
    }

    @Test
    void aDishPrestoDoesNotKnowKeepsTheWholeOrderWithStaff() {
        Fixture fixture = fixture(writable());
        expectAuth(fixture.server());
        fixture.server().expect(once(), requestTo(containsString("/retail/nomenclature/price-list")))
                .andRespond(withSuccess(PRICE_LISTS, MediaType.APPLICATION_JSON));
        fixture.server().expect(once(), requestTo(containsString("/retail/v2/nomenclature/list")))
                .andRespond(withSuccess("{\"nomenclatures\": [{\"id\": 31, \"name\": \"Борщ московский\"}]}", MediaType.APPLICATION_JSON));

        ExternalLunchOrderProvider.Result result = fixture.provider().submit(order(List.of(dish("Борщ со сметаной", 2))), "astor-lunch-1");

        assertThat(result.accepted()).isFalse();
        assertThat(result.status()).isEqualTo("DISH_NOT_IN_SABY");
        assertThat(result.message()).contains("Борщ со сметаной");
        fixture.server().verify();
    }

    @Test
    void configuredPriceListSkipsTheLookupAndAServerErrorIsNotRetried() {
        SabyReservationProperties properties = writable();
        properties.setPriceListId("4");
        Fixture fixture = fixture(properties);
        expectAuth(fixture.server());
        fixture.server().expect(once(), requestTo(containsString("/retail/v2/nomenclature/list")))
                .andExpect(queryParam("priceListId", "4"))
                .andRespond(withSuccess("[{\"id\": 31, \"name\": \"борщ со сметаной\"}]", MediaType.APPLICATION_JSON));
        expectBooking(fixture.server(), "[]");
        fixture.server().expect(once(), requestTo(UPDATE_URL)).andRespond(withStatus(HttpStatus.BAD_GATEWAY));

        ExternalLunchOrderProvider.Result result = fixture.provider().submit(order(List.of(dish("Борщ со сметаной", 2))), "astor-lunch-1");

        assertThat(result.accepted()).isFalse();
        assertThat(result.status()).isEqualTo("PROVIDER_RESULT_UNKNOWN");
        fixture.server().verify();
    }

    @Test
    void existingDishesAreNeitherOverwrittenNorDuplicated() {
        SabyReservationProperties properties = writable();
        properties.setPriceListId("4");
        Fixture fixture = fixture(properties);
        expectAuth(fixture.server());
        fixture.server().expect(once(), requestTo(containsString("/retail/v2/nomenclature/list")))
                .andRespond(withSuccess("[{\"id\":31,\"name\":\"Борщ со сметаной\"}]", MediaType.APPLICATION_JSON));
        expectBooking(fixture.server(), "[{\"id\":90,\"count\":1}]");
        var result = fixture.provider().submit(order(List.of(dish("Борщ со сметаной", 2))), "astor-lunch-1");
        assertThat(result.accepted()).isFalse();
        assertThat(result.status()).isEqualTo("EXISTING_DISHES_REQUIRE_REVIEW");
        fixture.server().verify();
    }

    private static void expectBooking(MockRestServiceServer server, String dishes) {
        server.expect(once(), requestTo("https://api.saby.test/retail/order/" + BOOKING_ID))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("""
                        {"product":"restaurant","pointId":206,"datetime":"2026-10-07 13:00:00",
                         "comment":"Hostess note · Astor Butler #77",
                         "booking":{"visitors":2,"table":42,"woTable":false},
                         "customer":{"phone":"79990000000","email":"guest@example.test"},
                         "nomenclatures":%s}
                        """.formatted(dishes), MediaType.APPLICATION_JSON));
    }

    private static BusinessLunchOrder.Item dish(String title, int quantity) {
        return new BusinessLunchOrder.Item("SOUP", title.toUpperCase(), title, quantity, null);
    }

    private static BusinessLunchOrder order(List<BusinessLunchOrder.Item> dishes) {
        return order(BOOKING_ID, dishes);
    }

    private static BusinessLunchOrder order(String externalReservationId, List<BusinessLunchOrder.Item> dishes) {
        return order(externalReservationId, dishes, null);
    }

    private static BusinessLunchOrder order(String externalReservationId, List<BusinessLunchOrder.Item> dishes, String wishes) {
        Instant start = Instant.parse("2026-10-07T08:00:00Z");
        return new BusinessLunchOrder("AERIS", 77L, "4", start, start.plusSeconds(5400), 2, null, null, null, dishes, null,
                "Иван", "+7 999 000-00-00", null, "TELEGRAM", null, externalReservationId, wishes);
    }

    private static void expectAuth(MockRestServiceServer server) {
        server.expect(once(), requestTo(AUTH_URL)).andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess("{\"token\": \"token-1\"}", MediaType.APPLICATION_JSON));
    }

    private static SabyReservationProperties configured() {
        SabyReservationProperties properties = new SabyReservationProperties();
        properties.setEnabled(true);
        properties.setBaseUrl("https://api.saby.test");
        properties.setAuthUrl(AUTH_URL);
        properties.setAppClientId("client");
        properties.setAppSecret("secret");
        properties.setSecretKey("service-key");
        properties.setPointId("206");
        return properties;
    }

    private static SabyReservationProperties writable() {
        SabyReservationProperties properties = configured();
        properties.setWriteEnabled(true);
        return properties;
    }

    private static Fixture fixture(SabyReservationProperties properties) {
        AtomicReference<MockRestServiceServer> serverRef = new AtomicReference<>();
        SabyLunchOrderProvider provider = new SabyLunchOrderProvider(properties,
                new RestTemplateBuilder(restTemplate -> serverRef.set(MockRestServiceServer.bindTo(restTemplate).build())));
        return new Fixture(provider, serverRef.get());
    }

    private record Fixture(SabyLunchOrderProvider provider, MockRestServiceServer server) {
    }
}
