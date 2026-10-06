package museon_online.astor_butler.integration.saby;

import museon_online.astor_butler.domain.booking.external.ExternalReservationProvider;
import org.junit.jupiter.api.Test;
import org.springframework.boot.restclient.RestTemplateBuilder;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;

import java.net.SocketTimeoutException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.ExpectedCount.twice;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.queryParam;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class SabyReservationProviderTest {

    private static final String AUTH_URL = "https://auth.saby.test/oauth/service/";
    private static final String HALL_LIST_URL = "https://api.saby.test/retail/hall/list";

    // 09:00Z is 14:00 in Yekaterinburg.
    private static final ExternalReservationProvider.ExternalAvailabilityRequest REQUEST =
            new ExternalReservationProvider.ExternalAvailabilityRequest(
                    "AERIS",
                    Instant.parse("2026-10-07T09:00:00Z"),
                    Instant.parse("2026-10-07T11:00:00Z"),
                    2,
                    null,
                    null
            );

    private static final String HALLS = """
            {
              "halls": [
                {
                  "id": 271,
                  "name": "Основной зал",
                  "active": true,
                  "items": [
                    { "id": 3037, "name": "Стол 4", "capacity": 4, "busy": false, "isBookingLocked": false, "visible": true },
                    { "id": 3038, "name": "Стол 5", "capacity": 4, "busy": true, "isBookingLocked": false, "visible": true },
                    { "id": 3039, "name": "Стол 6", "capacity": 6, "busy": false, "isBookingLocked": true, "visible": true },
                    { "id": 3040, "name": "Барный", "capacity": 1, "busy": false, "isBookingLocked": false, "visible": true },
                    { "id": 3041, "name": "Скрытый", "capacity": 4, "busy": false, "isBookingLocked": false, "visible": false },
                    { "id": 3042, "name": "Без статуса", "capacity": 4 }
                  ]
                },
                {
                  "id": 272,
                  "name": "Закрытый зал",
                  "active": false,
                  "items": [
                    { "id": 4001, "name": "VIP", "capacity": 8, "busy": false, "isBookingLocked": false, "visible": true }
                  ]
                }
              ],
              "outcome": { "hasMore": false }
            }
            """;

    private static final String NO_FREE_TABLES = """
            {
              "halls": [
                {
                  "id": 271,
                  "name": "Основной зал",
                  "active": true,
                  "items": [
                    { "id": 3038, "name": "Стол 5", "capacity": 4, "busy": true, "isBookingLocked": false, "visible": true }
                  ]
                }
              ]
            }
            """;

    @Test
    void reportsMissingConfigurationWithoutCallingExternalApi() {
        SabyReservationProperties properties = new SabyReservationProperties();
        properties.setEnabled(true);
        Fixture fixture = fixture(properties);

        var status = fixture.provider().status();
        var availability = fixture.provider().checkAvailability(REQUEST);

        assertThat(status.configured()).isFalse();
        assertThat(status.mode()).isEqualTo("CONFIG_REQUIRED");
        assertThat(status.missingConfiguration()).containsExactly(
                "SABY_APP_CLIENT_ID",
                "SABY_APP_SECRET",
                "SABY_SECRET_KEY",
                "SABY_POINT_ID"
        );
        assertThat(availability.status()).isEqualTo("PROVIDER_NOT_CONFIGURED");
        assertThat(availability.providerConfigured()).isFalse();
        fixture.server().verify();
    }

    @Test
    void disabledProviderDoesNotCallSabyEvenWithCredentials() {
        SabyReservationProperties properties = configured();
        properties.setEnabled(false);
        Fixture fixture = fixture(properties);

        var availability = fixture.provider().checkAvailability(REQUEST);

        assertThat(fixture.provider().status().mode()).isEqualTo("DISABLED");
        assertThat(availability.available()).isFalse();
        assertThat(availability.status()).isEqualTo("PROVIDER_NOT_CONFIGURED");
        fixture.server().verify();
    }

    @Test
    void authenticatesAndReturnsOnlyFreeBookableTablesThatFitTheParty() {
        Fixture fixture = fixture(configured());
        expectAuth(fixture.server(), "token-1");
        fixture.server().expect(once(), requestTo(startsWith(HALL_LIST_URL)))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header(SabyApiClient.TOKEN_HEADER, "token-1"))
                .andExpect(queryParam("pointId", "206"))
                .andExpect(queryParam("date", "2026-10-07%2014:00:00"))
                .andRespond(withSuccess(HALLS, MediaType.APPLICATION_JSON));

        var availability = fixture.provider().checkAvailability(REQUEST);

        assertThat(fixture.provider().status().mode()).isEqualTo("READ_ONLY");
        assertThat(availability.available()).isTrue();
        assertThat(availability.status()).isEqualTo("AVAILABLE");
        assertThat(availability.metadata()).containsEntry("localStart", "2026-10-07 14:00:00");
        assertThat(candidates(availability.metadata()))
                .singleElement()
                .satisfies(candidate -> {
                    assertThat(candidate).containsEntry("hallId", 271L);
                    assertThat(candidate).containsEntry("tableId", 3037L);
                    assertThat(candidate).containsEntry("capacity", 4);
                });
        fixture.server().verify();
    }

    @Test
    void passesConfiguredHallToSaby() {
        SabyReservationProperties properties = configured();
        properties.setHallId("271");
        Fixture fixture = fixture(properties);
        expectAuth(fixture.server(), "token-1");
        fixture.server().expect(once(), requestTo(startsWith(HALL_LIST_URL)))
                .andExpect(queryParam("hallId", "271"))
                .andRespond(withSuccess(HALLS, MediaType.APPLICATION_JSON));

        fixture.provider().checkAvailability(REQUEST);

        fixture.server().verify();
    }

    @Test
    void reportsNoTablesWhenEverythingIsBusy() {
        Fixture fixture = fixture(configured());
        expectAuth(fixture.server(), "token-1");
        fixture.server().expect(once(), requestTo(startsWith(HALL_LIST_URL)))
                .andRespond(withSuccess(NO_FREE_TABLES, MediaType.APPLICATION_JSON));

        var availability = fixture.provider().checkAvailability(REQUEST);

        assertThat(availability.available()).isFalse();
        assertThat(availability.status()).isEqualTo("NO_TABLES_AVAILABLE");
        assertThat(candidates(availability.metadata())).isEmpty();
        fixture.server().verify();
    }

    @Test
    void reusesCachedTokenAcrossCalls() {
        Fixture fixture = fixture(configured());
        expectAuth(fixture.server(), "token-1");
        fixture.server().expect(twice(), requestTo(startsWith(HALL_LIST_URL)))
                .andExpect(header(SabyApiClient.TOKEN_HEADER, "token-1"))
                .andRespond(withSuccess(HALLS, MediaType.APPLICATION_JSON));

        fixture.provider().checkAvailability(REQUEST);
        fixture.provider().checkAvailability(REQUEST);

        fixture.server().verify();
    }

    @Test
    void reauthenticatesOnceWhenTokenIsRejected() {
        Fixture fixture = fixture(configured());
        expectAuth(fixture.server(), "token-1");
        fixture.server().expect(once(), requestTo(startsWith(HALL_LIST_URL)))
                .andExpect(header(SabyApiClient.TOKEN_HEADER, "token-1"))
                .andRespond(withStatus(HttpStatus.UNAUTHORIZED));
        expectAuth(fixture.server(), "token-2");
        fixture.server().expect(once(), requestTo(startsWith(HALL_LIST_URL)))
                .andExpect(header(SabyApiClient.TOKEN_HEADER, "token-2"))
                .andRespond(withSuccess(HALLS, MediaType.APPLICATION_JSON));

        var availability = fixture.provider().checkAvailability(REQUEST);

        assertThat(availability.status()).isEqualTo("AVAILABLE");
        fixture.server().verify();
    }

    @Test
    void reportsAuthFailureWhenFreshTokenIsAlsoRejected() {
        Fixture fixture = fixture(configured());
        expectAuth(fixture.server(), "token-1");
        fixture.server().expect(once(), requestTo(startsWith(HALL_LIST_URL)))
                .andRespond(withStatus(HttpStatus.UNAUTHORIZED));
        expectAuth(fixture.server(), "token-2");
        fixture.server().expect(once(), requestTo(startsWith(HALL_LIST_URL)))
                .andRespond(withStatus(HttpStatus.UNAUTHORIZED));

        var availability = fixture.provider().checkAvailability(REQUEST);

        assertThat(availability.available()).isFalse();
        assertThat(availability.status()).isEqualTo("PROVIDER_AUTH_FAILED");
        fixture.server().verify();
    }

    @Test
    void reportsAuthFailureWhenCredentialsAreRejected() {
        Fixture fixture = fixture(configured());
        fixture.server().expect(once(), requestTo(AUTH_URL))
                .andRespond(withStatus(HttpStatus.UNAUTHORIZED));

        var availability = fixture.provider().checkAvailability(REQUEST);

        assertThat(availability.status()).isEqualTo("PROVIDER_AUTH_FAILED");
        assertThat(availability.message()).doesNotContain("secret");
        fixture.server().verify();
    }

    @Test
    void retriesReadOnServerErrorThenSucceeds() {
        Fixture fixture = fixture(configured());
        expectAuth(fixture.server(), "token-1");
        fixture.server().expect(once(), requestTo(startsWith(HALL_LIST_URL)))
                .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));
        fixture.server().expect(once(), requestTo(startsWith(HALL_LIST_URL)))
                .andRespond(withSuccess(HALLS, MediaType.APPLICATION_JSON));

        var availability = fixture.provider().checkAvailability(REQUEST);

        assertThat(availability.status()).isEqualTo("AVAILABLE");
        fixture.server().verify();
    }

    @Test
    void reportsProviderErrorWhenRetriesAreExhausted() {
        Fixture fixture = fixture(configured());
        expectAuth(fixture.server(), "token-1");
        fixture.server().expect(twice(), requestTo(startsWith(HALL_LIST_URL)))
                .andRespond(withStatus(HttpStatus.BAD_GATEWAY));

        var availability = fixture.provider().checkAvailability(REQUEST);

        assertThat(availability.available()).isFalse();
        assertThat(availability.status()).isEqualTo("PROVIDER_ERROR");
        assertThat(availability.metadata()).containsEntry("httpStatus", 502);
        fixture.server().verify();
    }

    @Test
    void doesNotRetryClientErrors() {
        Fixture fixture = fixture(configured());
        expectAuth(fixture.server(), "token-1");
        fixture.server().expect(once(), requestTo(startsWith(HALL_LIST_URL)))
                .andRespond(withStatus(HttpStatus.BAD_REQUEST));

        var availability = fixture.provider().checkAvailability(REQUEST);

        assertThat(availability.status()).isEqualTo("PROVIDER_ERROR");
        assertThat(availability.metadata()).containsEntry("httpStatus", 400);
        fixture.server().verify();
    }

    @Test
    void reportsTimeoutWithoutThrowing() {
        SabyReservationProperties properties = configured();
        properties.setMaxRetries(0);
        Fixture fixture = fixture(properties);
        expectAuth(fixture.server(), "token-1");
        fixture.server().expect(once(), requestTo(startsWith(HALL_LIST_URL)))
                .andRespond(withException(new SocketTimeoutException("Read timed out")));

        var availability = fixture.provider().checkAvailability(REQUEST);

        assertThat(availability.available()).isFalse();
        assertThat(availability.status()).isEqualTo("PROVIDER_TIMEOUT");
        fixture.server().verify();
    }

    @Test
    void reportsInvalidResponseWhenHallsAreMissing() {
        Fixture fixture = fixture(configured());
        expectAuth(fixture.server(), "token-1");
        fixture.server().expect(once(), requestTo(startsWith(HALL_LIST_URL)))
                .andRespond(withSuccess("{\"result\": []}", MediaType.APPLICATION_JSON));

        var availability = fixture.provider().checkAvailability(REQUEST);

        assertThat(availability.status()).isEqualTo("PROVIDER_INVALID_RESPONSE");
        fixture.server().verify();
    }

    @Test
    void rejectsOtherVenuesWithoutCallingSaby() {
        Fixture fixture = fixture(configured());

        var availability = fixture.provider().checkAvailability(new ExternalReservationProvider.ExternalAvailabilityRequest(
                "C3FLEX",
                REQUEST.requestedStartAt(),
                REQUEST.requestedEndAt(),
                2,
                null,
                null
        ));

        assertThat(availability.status()).isEqualTo("UNSUPPORTED_VENUE");
        fixture.server().verify();
    }

    @Test
    void rejectsIncompleteRequestWithoutCallingSaby() {
        Fixture fixture = fixture(configured());

        var availability = fixture.provider().checkAvailability(new ExternalReservationProvider.ExternalAvailabilityRequest(
                "AERIS",
                null,
                null,
                2,
                null,
                null
        ));

        assertThat(availability.status()).isEqualTo("INVALID_REQUEST");
        fixture.server().verify();
    }

    @Test
    void blocksWritesWhenConfiguredUntilWritePathIsEnabled() {
        Fixture fixture = fixture(configured());

        var result = fixture.provider().reserve(null, "idem-1");

        assertThat(fixture.provider().status().configured()).isTrue();
        assertThat(result.created()).isFalse();
        assertThat(result.status()).isEqualTo("SABY_WRITE_DISABLED");
        fixture.server().verify();
    }

    @Test
    void listsRestaurantPointsForSetup() {
        Fixture fixture = fixture(configured());
        expectAuth(fixture.server(), "token-1");
        fixture.server().expect(once(), requestTo(startsWith("https://api.saby.test/retail/point/list")))
                .andExpect(queryParam("product", "restaurant"))
                .andRespond(withSuccess("{\"salesPoints\": [{\"id\": 206, \"name\": \"AERIS\"}]}",
                        MediaType.APPLICATION_JSON));

        var points = fixture.provider().listPoints();

        assertThat(points.path("salesPoints").get(0).path("id").asInt()).isEqualTo(206);
        fixture.server().verify();
    }

    private static void expectAuth(MockRestServiceServer server, String token) {
        server.expect(once(), requestTo(AUTH_URL))
                .andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.app_client_id").value("client"))
                .andExpect(jsonPath("$.app_secret").value("secret"))
                .andExpect(jsonPath("$.secret_key").value("service-key"))
                .andRespond(withSuccess("{\"token\": \"" + token + "\"}", MediaType.APPLICATION_JSON));
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> candidates(Map<String, Object> metadata) {
        return (List<Map<String, Object>>) metadata.get("candidates");
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
