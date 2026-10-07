package museon_online.astor_butler.integration.saby;

import museon_online.astor_butler.domain.booking.TableReservationCommand;
import org.junit.jupiter.api.Test;
import org.springframework.boot.restclient.RestTemplateBuilder;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;

import java.net.SocketTimeoutException;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.ExpectedCount.twice;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class SabyReservationWriteTest {

    private static final String AUTH_URL = "https://auth.saby.test/oauth/service/";
    private static final String CREATE_URL = "https://api.saby.test/retail/order/create";
    private static final String EXTERNAL_ID = "43bd679d-ec4a-43f5-9214-b740ef5a3ba6";
    private static final String CREATED = "{\"externalId\": \"" + EXTERNAL_ID + "\", \"state\": 0}";

    @Test
    void reportsReadWriteModeWhenWriteIsEnabled() {
        Fixture fixture = fixture(writable());

        assertThat(fixture.provider().status().mode()).isEqualTo("READ_WRITE");
        fixture.server().verify();
    }

    @Test
    void writeDisabledNeverCallsSaby() {
        SabyReservationProperties properties = writable();
        properties.setWriteEnabled(false);
        Fixture fixture = fixture(properties);

        var result = fixture.provider().reserve(command("Иван", "+79990000000"), "order-1");
        var cancel = fixture.provider().cancel(EXTERNAL_ID);

        assertThat(result.created()).isFalse();
        assertThat(result.status()).isEqualTo("SABY_WRITE_DISABLED");
        assertThat(cancel.status()).isEqualTo("SABY_WRITE_DISABLED");
        fixture.server().verify();
    }

    @Test
    void createsBookingWithPrestoPayloadAndReportsItUnconfirmed() {
        Fixture fixture = fixture(writable());
        expectAuth(fixture.server(), "token-1");
        fixture.server().expect(once(), requestTo(CREATE_URL))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header(SabyApiClient.TOKEN_HEADER, "token-1"))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.product").value("restaurant"))
                .andExpect(jsonPath("$.pointId").value(206))
                .andExpect(jsonPath("$.datetime").value("2026-10-07 14:00:00"))
                .andExpect(jsonPath("$.customer.name").value("Иван"))
                .andExpect(jsonPath("$.customer.phone").value("+79990000000"))
                .andExpect(jsonPath("$.booking.visitors").value(2))
                .andExpect(jsonPath("$.booking.woTable").value(true))
                .andExpect(jsonPath("$.booking.hall").doesNotExist())
                .andExpect(jsonPath("$.comment").value(containsString("У окна")))
                .andExpect(jsonPath("$.comment").value(containsString("Зона: WINDOW")))
                .andExpect(jsonPath("$.comment").value(containsString("Astor Butler #order-1")))
                .andRespond(withSuccess(CREATED, MediaType.APPLICATION_JSON));

        var result = fixture.provider().reserve(command("Иван", "+79990000000"), "order-1");

        assertThat(result.created()).isTrue();
        assertThat(result.status()).isEqualTo("SABY_ORDER_CREATED_UNCONFIRMED");
        assertThat(result.externalReservationId()).isEqualTo(EXTERNAL_ID);
        assertThat(result.metadata()).containsEntry("tableSelection", "SABY_ADMINISTRATOR");
        assertThat(result.metadata()).containsEntry("butlerMarker", "Astor Butler #order-1");
        fixture.server().verify();
    }

    @Test
    void sendsConfiguredHall() {
        SabyReservationProperties properties = writable();
        properties.setHallId("271");
        Fixture fixture = fixture(properties);
        expectAuth(fixture.server(), "token-1");
        fixture.server().expect(once(), requestTo(CREATE_URL))
                .andExpect(jsonPath("$.booking.hall").value(271))
                .andRespond(withSuccess(CREATED, MediaType.APPLICATION_JSON));

        fixture.provider().reserve(command("Иван", "+79990000000"), "order-1");

        fixture.server().verify();
    }

    @Test
    void repeatedReserveWithSameKeyCreatesOnlyOnce() {
        Fixture fixture = fixture(writable());
        expectAuth(fixture.server(), "token-1");
        fixture.server().expect(once(), requestTo(CREATE_URL))
                .andRespond(withSuccess(CREATED, MediaType.APPLICATION_JSON));

        var first = fixture.provider().reserve(command("Иван", "+79990000000"), "order-1");
        var second = fixture.provider().reserve(command("Иван", "+79990000000"), "order-1");

        assertThat(second).isEqualTo(first);
        fixture.server().verify();
    }

    @Test
    void serverErrorOnCreateIsNotRetriedAndBlocksRepeats() {
        Fixture fixture = fixture(writable());
        expectAuth(fixture.server(), "token-1");
        fixture.server().expect(once(), requestTo(CREATE_URL))
                .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));

        var first = fixture.provider().reserve(command("Иван", "+79990000000"), "order-1");
        var repeat = fixture.provider().reserve(command("Иван", "+79990000000"), "order-1");

        assertThat(first.created()).isFalse();
        assertThat(first.status()).isEqualTo("PROVIDER_RESULT_UNKNOWN");
        assertThat(first.message()).contains("Astor Butler #order-1");
        assertThat(repeat.status()).isEqualTo("PROVIDER_RESULT_UNKNOWN");
        fixture.server().verify();
    }

    @Test
    void timeoutOnCreateIsNotRetried() {
        Fixture fixture = fixture(writable());
        expectAuth(fixture.server(), "token-1");
        fixture.server().expect(once(), requestTo(CREATE_URL))
                .andRespond(withException(new SocketTimeoutException("Read timed out")));

        var result = fixture.provider().reserve(command("Иван", "+79990000000"), "order-1");

        assertThat(result.created()).isFalse();
        assertThat(result.status()).isEqualTo("PROVIDER_RESULT_UNKNOWN");
        fixture.server().verify();
    }

    @Test
    void clientErrorIsRejectedAndMayBeRetriedLater() {
        Fixture fixture = fixture(writable());
        expectAuth(fixture.server(), "token-1");
        fixture.server().expect(twice(), requestTo(CREATE_URL))
                .andRespond(withStatus(HttpStatus.BAD_REQUEST));

        var first = fixture.provider().reserve(command("Иван", "+79990000000"), "order-1");
        var second = fixture.provider().reserve(command("Иван", "+79990000000"), "order-1");

        assertThat(first.status()).isEqualTo("PROVIDER_REJECTED");
        assertThat(first.metadata()).containsEntry("httpStatus", 400);
        assertThat(second.status()).isEqualTo("PROVIDER_REJECTED");
        fixture.server().verify();
    }

    @Test
    void rejectedTokenOnCreateIsRefreshedOnceBecauseNothingWasBooked() {
        Fixture fixture = fixture(writable());
        expectAuth(fixture.server(), "token-1");
        fixture.server().expect(once(), requestTo(CREATE_URL))
                .andExpect(header(SabyApiClient.TOKEN_HEADER, "token-1"))
                .andRespond(withStatus(HttpStatus.UNAUTHORIZED));
        expectAuth(fixture.server(), "token-2");
        fixture.server().expect(once(), requestTo(CREATE_URL))
                .andExpect(header(SabyApiClient.TOKEN_HEADER, "token-2"))
                .andRespond(withSuccess(CREATED, MediaType.APPLICATION_JSON));

        var result = fixture.provider().reserve(command("Иван", "+79990000000"), "order-1");

        assertThat(result.created()).isTrue();
        fixture.server().verify();
    }

    @Test
    void authFailureBeforeCreateIsNotReportedAsUnknown() {
        Fixture fixture = fixture(writable());
        fixture.server().expect(once(), requestTo(AUTH_URL))
                .andRespond(withStatus(HttpStatus.UNAUTHORIZED));

        var result = fixture.provider().reserve(command("Иван", "+79990000000"), "order-1");

        assertThat(result.created()).isFalse();
        assertThat(result.status()).isEqualTo("PROVIDER_AUTH_FAILED");
        fixture.server().verify();
    }

    @Test
    void successWithoutBookingIdIsReportedUnknownWithResponseFieldNames() {
        Fixture fixture = fixture(writable());
        expectAuth(fixture.server(), "token-1");
        fixture.server().expect(once(), requestTo(CREATE_URL))
                .andRespond(withSuccess("{\"result\": true}", MediaType.APPLICATION_JSON));

        var result = fixture.provider().reserve(command("Иван", "+79990000000"), "order-1");

        assertThat(result.created()).isFalse();
        assertThat(result.status()).isEqualTo("PROVIDER_RESULT_UNKNOWN");
        assertThat(result.metadata()).containsEntry("responseFields", List.of("result"));
        fixture.server().verify();
    }

    @Test
    void missingGuestContactStaysWithHostess() {
        Fixture fixture = fixture(writable());

        var noPhone = fixture.provider().reserve(command("Иван", " "), "order-1");
        var noName = fixture.provider().reserve(command(null, "+79990000000"), "order-2");

        assertThat(noPhone.status()).isEqualTo("GUEST_DATA_REQUIRED");
        assertThat(noName.status()).isEqualTo("GUEST_DATA_REQUIRED");
        fixture.server().verify();
    }

    @Test
    void requiresIdempotencyKey() {
        Fixture fixture = fixture(writable());

        var result = fixture.provider().reserve(command("Иван", "+79990000000"), " ");

        assertThat(result.status()).isEqualTo("INVALID_REQUEST");
        fixture.server().verify();
    }

    @Test
    void readsRawBookingState() {
        Fixture fixture = fixture(writable());
        expectAuth(fixture.server(), "token-1");
        fixture.server().expect(once(), requestTo("https://api.saby.test/retail/order/" + EXTERNAL_ID + "/state"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("{\"state\": 10, \"payState\": 0, \"payments\": []}",
                        MediaType.APPLICATION_JSON));

        var state = fixture.provider().state(EXTERNAL_ID);

        assertThat(state.ok()).isTrue();
        assertThat(state.status()).isEqualTo("SABY_STATE_10");
        assertThat(state.state()).isEqualTo(10);
        assertThat(state.payState()).isZero();
        assertThat(state.productState()).isNull();
        fixture.server().verify();
    }

    @Test
    void rejectsMalformedBookingIdWithoutCallingSaby() {
        Fixture fixture = fixture(writable());

        var state = fixture.provider().state("../point/list");
        var cancel = fixture.provider().cancel("id?x=1");

        assertThat(state.status()).isEqualTo("INVALID_EXTERNAL_ID");
        assertThat(cancel.status()).isEqualTo("INVALID_EXTERNAL_ID");
        fixture.server().verify();
    }

    @Test
    void cancelsBookingOnceWithEmptyResponse() {
        Fixture fixture = fixture(writable());
        expectAuth(fixture.server(), "token-1");
        fixture.server().expect(once(), requestTo("https://api.saby.test/retail/order/" + EXTERNAL_ID + "/cancel"))
                .andExpect(method(HttpMethod.PUT))
                .andRespond(withSuccess());

        var cancel = fixture.provider().cancel(EXTERNAL_ID);

        assertThat(cancel.ok()).isTrue();
        assertThat(cancel.status()).isEqualTo("CANCEL_REQUESTED");
        fixture.server().verify();
    }

    @Test
    void cancelServerErrorIsNotRetried() {
        Fixture fixture = fixture(writable());
        expectAuth(fixture.server(), "token-1");
        fixture.server().expect(once(), requestTo("https://api.saby.test/retail/order/" + EXTERNAL_ID + "/cancel"))
                .andRespond(withStatus(HttpStatus.BAD_GATEWAY));

        var cancel = fixture.provider().cancel(EXTERNAL_ID);

        assertThat(cancel.ok()).isFalse();
        assertThat(cancel.status()).isEqualTo("PROVIDER_RESULT_UNKNOWN");
        fixture.server().verify();
    }

    private static TableReservationCommand command(String guestName, String guestPhone) {
        return new TableReservationCommand(
                123456L,
                null,
                null,
                "AERIS",
                "4",
                "WINDOW",
                null,
                Instant.parse("2026-10-07T09:00:00Z"),
                Instant.parse("2026-10-07T11:00:00Z"),
                2,
                guestName,
                guestPhone,
                "У окна",
                null,
                null
        );
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
                new RestTemplateBuilder(restTemplate ->
                        serverRef.set(MockRestServiceServer.bindTo(restTemplate).build())));
        return new Fixture(provider, serverRef.get());
    }

    private record Fixture(SabyReservationProvider provider, MockRestServiceServer server) {
    }
}
