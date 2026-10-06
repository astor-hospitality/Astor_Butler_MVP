package museon_online.astor_butler.integration.saby;

import museon_online.astor_butler.domain.booking.TableReservationCommand;
import org.junit.jupiter.api.Test;
import org.springframework.boot.restclient.RestTemplateBuilder;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.endsWith;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * Review fixes for the write adapter (PR #38): the booking id from {@code create} is validated like the one
 * {@code state}/{@code cancel} accept, the guest phone is sent the way Saby examples show it, the comment is bounded,
 * raw state codes are mapped, and an unknown outcome can be resolved by hand.
 */
class SabyWriteReviewFixesTest {

    private static final String AUTH_URL = "https://auth.saby.test/oauth/service/";
    private static final String CREATE_URL = "https://api.saby.test/retail/order/create";
    private static final String EXTERNAL_ID = "43bd679d-ec4a-43f5-9214-b740ef5a3ba6";
    private static final String CREATED = "{\"externalId\": \"" + EXTERNAL_ID + "\"}";

    @Test
    void unusableCreateIdIsNotReportedCreatedAndBlocksRepeats() {
        Fixture fixture = fixture(writable());
        expectAuth(fixture.server(), "token-1");
        fixture.server().expect(once(), requestTo(CREATE_URL))
                .andRespond(withSuccess("{\"externalId\": \"invalid/id\"}", MediaType.APPLICATION_JSON));

        var first = fixture.provider().reserve(command("Иван", "+79990000000"), "order-1");
        var second = fixture.provider().reserve(command("Иван", "+79990000000"), "order-1");

        assertThat(first.created()).isFalse();
        assertThat(first.status()).isEqualTo("PROVIDER_RESULT_UNKNOWN");
        assertThat(first.externalReservationId()).isEmpty();
        assertThat(first.metadata()).containsEntry("butlerMarker", "Astor Butler #order-1");
        assertThat(first.metadata()).containsEntry("externalIdRejected", true);
        assertThat(first.metadata().toString()).doesNotContain("invalid/id");
        assertThat(second).isEqualTo(first);
        assertThat(fixture.provider().state("invalid/id").status()).isEqualTo("INVALID_EXTERNAL_ID");
        fixture.server().verify();
    }

    @Test
    void booleanOrEmptyCreateIdIsUnknownButNumericIdIsAccepted() {
        for (String body : List.of("{\"externalId\": true}", "{\"externalId\": \"\"}", "{\"id\": \" \"}")) {
            Fixture fixture = fixture(writable());
            expectAuth(fixture.server(), "token-1");
            fixture.server().expect(once(), requestTo(CREATE_URL)).andRespond(withSuccess(body, MediaType.APPLICATION_JSON));

            var result = fixture.provider().reserve(command("Иван", "+79990000000"), "order-1");

            assertThat(result.created()).as(body).isFalse();
            assertThat(result.status()).as(body).isEqualTo("PROVIDER_RESULT_UNKNOWN");
            fixture.server().verify();
        }

        Fixture numeric = fixture(writable());
        expectAuth(numeric.server(), "token-1");
        numeric.server().expect(once(), requestTo(CREATE_URL))
                .andRespond(withSuccess("{\"id\": 4711}", MediaType.APPLICATION_JSON));

        var result = numeric.provider().reserve(command("Иван", "+79990000000"), "order-1");

        assertThat(result.created()).isTrue();
        assertThat(result.externalReservationId()).isEqualTo("4711");
        numeric.server().verify();
    }

    @Test
    void phoneIsSentAsDigitsStartingWithSeven() {
        Fixture fixture = fixture(writable());
        expectAuth(fixture.server(), "token-1");
        fixture.server().expect(once(), requestTo(CREATE_URL))
                .andExpect(jsonPath("$.customer.phone").value("79123456789"))
                .andRespond(withSuccess(CREATED, MediaType.APPLICATION_JSON));

        var result = fixture.provider().reserve(command("Иван", "+7 (912) 345-67-89"), "order-1");

        assertThat(result.created()).isTrue();
        fixture.server().verify();
    }

    @Test
    void phoneThatIsNotARussianNumberStaysWithHostess() {
        Fixture fixture = fixture(writable());

        var tooShort = fixture.provider().reserve(command("Иван", "+7 912 345"), "order-1");
        var letters = fixture.provider().reserve(command("Иван", "позвоню сам"), "order-2");
        var foreign = fixture.provider().reserve(command("Иван", "+1 212 555 0100"), "order-3");

        for (var result : List.of(tooShort, letters, foreign)) {
            assertThat(result.created()).isFalse();
            assertThat(result.status()).isEqualTo("GUEST_DATA_REQUIRED");
            assertThat(result.metadata()).containsEntry("reason", "PHONE_FORMAT");
        }
        fixture.server().verify();
    }

    @Test
    void longGuestCommentIsCutButTheButlerMarkerStaysAtTheEnd() {
        Fixture fixture = fixture(writable());
        expectAuth(fixture.server(), "token-1");
        fixture.server().expect(once(), requestTo(CREATE_URL))
                .andExpect(jsonPath("$.comment").value(endsWith("Astor Butler #order-1")))
                .andExpect(jsonPath("$.comment").value(containsString("…")))
                .andRespond(withSuccess(CREATED, MediaType.APPLICATION_JSON));

        String longComment = "очень длинное пожелание ".repeat(40);
        var result = fixture.provider().reserve(command("Иван", "+79990000000", longComment), "order-1");

        assertThat(result.created()).isTrue();
        fixture.server().verify();
    }

    @Test
    void hallIsSentOnlyWhenTheTableIsChosenByButler() {
        SabyReservationProperties properties = writable();
        properties.setHallId("271");
        Map<String, Object> adminPicks = SabyOrderPayload.create(command("Иван", "+79990000000"), "k", properties, "2026-10-07 14:00:00");
        Map<String, Object> butlerPicks = SabyOrderPayload.create(command("Иван", "+79990000000"), "k", properties, "2026-10-07 14:00:00", 3037L);

        @SuppressWarnings("unchecked") Map<String, Object> adminBooking = (Map<String, Object>) adminPicks.get("booking");
        @SuppressWarnings("unchecked") Map<String, Object> butlerBooking = (Map<String, Object>) butlerPicks.get("booking");
        assertThat(adminBooking).containsEntry("woTable", true).doesNotContainKey("table").containsEntry("hall", 271L);
        assertThat(butlerBooking).containsEntry("woTable", false).containsEntry("table", 3037L).containsEntry("hall", 271L);
    }

    @Test
    void tableWithoutHallIsRefusedBeforeAnyCall() {
        SabyReservationProperties properties = writable();
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> SabyOrderPayload.create(command("Иван", "+79990000000"), "k", properties, "2026-10-07 14:00:00", 3037L));
    }

    @Test
    void stateCodesAreMappedAndRawCodesKept() {
        Fixture fixture = fixture(writable());
        expectAuth(fixture.server(), "token-1");
        fixture.server().expect(once(), requestTo("https://api.saby.test/retail/order/" + EXTERNAL_ID + "/state"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("{\"state\": 20, \"productState\": 1001, \"payState\": 0}", MediaType.APPLICATION_JSON));

        var state = fixture.provider().state(EXTERNAL_ID);

        assertThat(state.ok()).isTrue();
        assertThat(state.status()).isEqualTo("SABY_STATE_20");
        assertThat(state.bookingState()).isEqualTo(SabyBookingState.CONFIRMED);
        assertThat(state.state()).isEqualTo(20);
        assertThat(state.productState()).isEqualTo(1001);
        fixture.server().verify();
    }

    @Test
    void bookingStateMappingFollowsTheDocumentedCodes() {
        assertThat(SabyBookingState.of(10, null)).isEqualTo(SabyBookingState.PENDING);
        assertThat(SabyBookingState.of(5, 1000)).isEqualTo(SabyBookingState.PENDING);
        assertThat(SabyBookingState.of(20, null)).isEqualTo(SabyBookingState.CONFIRMED);
        assertThat(SabyBookingState.of(50, 1002)).isEqualTo(SabyBookingState.CONFIRMED);
        assertThat(SabyBookingState.of(10, 1001)).as("product state says accepted").isEqualTo(SabyBookingState.CONFIRMED);
        assertThat(SabyBookingState.of(220, null)).isEqualTo(SabyBookingState.CANCELLED);
        assertThat(SabyBookingState.of(20, 1998)).as("cancelled wins over confirmed").isEqualTo(SabyBookingState.CANCELLED);
        assertThat(SabyBookingState.of(200, null)).isEqualTo(SabyBookingState.COMPLETED);
        assertThat(SabyBookingState.of(180, 1999)).isEqualTo(SabyBookingState.COMPLETED);
        assertThat(SabyBookingState.of(150, null)).isEqualTo(SabyBookingState.UNKNOWN);
        assertThat(SabyBookingState.of(null, null)).isEqualTo(SabyBookingState.UNKNOWN);
        assertThat(SabyBookingState.of(999, 5)).isEqualTo(SabyBookingState.UNKNOWN);
    }

    @Test
    void unknownOutcomeCanBeForgottenOrAdoptedAfterManualCheck() {
        Fixture fixture = fixture(writable());
        expectAuth(fixture.server(), "token-1");
        // Two creates in order: the first answer is unusable, the second (after forget) is a real booking.
        fixture.server().expect(once(), requestTo(CREATE_URL))
                .andRespond(withSuccess("{\"result\": true}", MediaType.APPLICATION_JSON));
        fixture.server().expect(once(), requestTo(CREATE_URL)).andRespond(withSuccess(CREATED, MediaType.APPLICATION_JSON));

        var unknown = fixture.provider().reserve(command("Иван", "+79990000000"), "order-1");
        assertThat(unknown.status()).isEqualTo("PROVIDER_RESULT_UNKNOWN");

        // The hostess found the booking in Saby: Butler adopts its id and no second create is ever sent.
        var adopted = fixture.provider().adopt("order-1", EXTERNAL_ID);
        assertThat(adopted.created()).isTrue();
        assertThat(adopted.status()).isEqualTo("SABY_ORDER_ADOPTED");
        assertThat(adopted.externalReservationId()).isEqualTo(EXTERNAL_ID);
        assertThat(fixture.provider().reserve(command("Иван", "+79990000000"), "order-1")).isEqualTo(adopted);
        assertThat(fixture.provider().adopt("order-1", "bad/id").status()).isEqualTo("INVALID_EXTERNAL_ID");

        // The hostess found nothing: forgetting the key allows one more create.
        assertThat(fixture.provider().forget("order-1")).isTrue();
        assertThat(fixture.provider().forget("order-1")).isFalse();
        var retried = fixture.provider().reserve(command("Иван", "+79990000000"), "order-1");
        assertThat(retried.created()).isTrue();
        fixture.server().verify();
    }

    private static TableReservationCommand command(String guestName, String guestPhone) {
        return command(guestName, guestPhone, "У окна");
    }

    private static TableReservationCommand command(String guestName, String guestPhone, String guestComment) {
        return new TableReservationCommand(123456L, null, null, "AERIS", "4", "WINDOW", null,
                Instant.parse("2026-10-07T09:00:00Z"), Instant.parse("2026-10-07T11:00:00Z"), 2,
                guestName, guestPhone, guestComment, null, null);
    }

    private static void expectAuth(MockRestServiceServer server, String token) {
        server.expect(once(), requestTo(AUTH_URL))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess("{\"token\": \"" + token + "\"}", MediaType.APPLICATION_JSON));
    }

    private static SabyReservationProperties writable() {
        SabyReservationProperties properties = new SabyReservationProperties();
        properties.setEnabled(true);
        properties.setWriteEnabled(true);
        properties.setBaseUrl("https://api.saby.test");
        properties.setAuthUrl(AUTH_URL);
        properties.setAppClientId("client");
        properties.setAppSecret("secret");
        properties.setSecretKey("service-key");
        properties.setPointId("206");
        return properties;
    }

    private static Fixture fixture(SabyReservationProperties properties) {
        AtomicReference<MockRestServiceServer> serverRef = new AtomicReference<>();
        SabyReservationProvider provider = new SabyReservationProvider(properties,
                new RestTemplateBuilder(restTemplate -> serverRef.set(MockRestServiceServer.bindTo(restTemplate).build())));
        return new Fixture(provider, serverRef.get());
    }

    private record Fixture(SabyReservationProvider provider, MockRestServiceServer server) {
    }
}
