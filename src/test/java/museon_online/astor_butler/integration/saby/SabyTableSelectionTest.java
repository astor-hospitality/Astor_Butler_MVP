package museon_online.astor_butler.integration.saby;

import museon_online.astor_butler.domain.booking.TableReservationCommand;
import org.junit.jupiter.api.Test;
import org.springframework.boot.restclient.RestTemplateBuilder;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.ExpectedCount.twice;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.queryParam;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * Prod 16.10, booking #68: Butler held table 4, but {@code order/create} went with {@code woTable=true} and the hostess
 * moved the booking to table 4 in Presto by hand. Now the Butler table goes to Saby by its id from {@code hall/list}.
 */
class SabyTableSelectionTest {

    private static final String AUTH_URL = "https://auth.saby.test/oauth/service/";
    private static final String HALL_LIST_URL = "https://api.saby.test/retail/hall/list";
    private static final String CREATE_URL = "https://api.saby.test/retail/order/create";
    private static final String CREATED = "{\"externalId\": \"43bd679d-ec4a-43f5-9214-b740ef5a3ba6\"}";

    private static final String HALLS = """
            {
              "halls": [
                {
                  "id": 271,
                  "name": "Основной зал",
                  "active": true,
                  "items": [
                    { "id": 3037, "name": "Стол 4", "capacity": 4, "busy": false },
                    { "id": 3038, "name": "Стол 05", "capacity": 4, "busy": true },
                    { "id": 3050, "name": "Бар", "capacity": 6, "busy": false }
                  ]
                },
                {
                  "id": 280,
                  "name": "VIP",
                  "active": true,
                  "items": [
                    { "id": 4013, "name": "VIP 13", "capacity": 8, "busy": false },
                    { "id": 4007, "name": "Стол 7", "capacity": 2 },
                    { "id": 4008, "name": "7", "capacity": 2 }
                  ]
                },
                {
                  "id": 290,
                  "name": "Закрытый зал",
                  "active": false,
                  "items": [ { "id": 5001, "name": "Стол 9", "capacity": 4 } ]
                }
              ]
            }
            """;

    @Test
    void theButlerTableGoesToSabyWithItsOwnHall() {
        Fixture fixture = fixture(writable());
        expectAuth(fixture.server());
        expectHalls(fixture.server());
        fixture.server().expect(once(), requestTo(CREATE_URL))
                .andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.booking.table").value(3037))
                .andExpect(jsonPath("$.booking.hall").value(271))
                .andExpect(jsonPath("$.booking.woTable").value(false))
                .andRespond(withSuccess(CREATED, MediaType.APPLICATION_JSON));

        var result = fixture.provider().reserve(command("4"), "68");

        assertThat(result.created()).isTrue();
        assertThat(result.metadata()).containsEntry("tableSelection", "BUTLER").containsEntry("tableMatch", "MATCHED")
                .containsEntry("sabyTableId", 3037L).containsEntry("sabyHallId", 271L);
        fixture.server().verify();
    }

    @Test
    void namesWithZerosVipPrefixesAndTheBarMatchTheButlerCodes() {
        Fixture fixture = fixture(writable());
        expectAuth(fixture.server());
        expectHalls(fixture.server());

        assertThat(fixture.provider().matchTable("5", "2026-10-16 19:00:00").table().tableId()).isEqualTo(3038L);
        assertThat(fixture.provider().matchTable("13", "2026-10-16 19:00:00").table())
                .isEqualTo(new SabyReservationProvider.SabyTable(280L, 4013L, "VIP 13"));
        assertThat(fixture.provider().matchTable("BAR", "2026-10-16 19:00:00").table().tableId()).isEqualTo(3050L);
        // The table list is read once and kept: the three matches above made one hall/list call.
        fixture.server().verify();
    }

    @Test
    void anUnknownAnAmbiguousOrAClosedHallTableIsLeftToTheAdministrator() {
        Fixture fixture = fixture(writable());
        expectAuth(fixture.server());
        expectHalls(fixture.server());

        assertThat(fixture.provider().matchTable("19", "2026-10-16 19:00:00").reason()).isEqualTo("UNMATCHED");
        assertThat(fixture.provider().matchTable("7", "2026-10-16 19:00:00").reason()).isEqualTo("AMBIGUOUS");
        assertThat(fixture.provider().matchTable("9", "2026-10-16 19:00:00").reason()).isEqualTo("UNMATCHED");
        assertThat(fixture.provider().matchTable(null, "2026-10-16 19:00:00").reason()).isEqualTo("NO_TABLE_CODE");
        fixture.server().verify();
    }

    @Test
    void anUnmatchedTableKeepsTodaysBookingForTheAdministrator() {
        Fixture fixture = fixture(writable());
        expectAuth(fixture.server());
        expectHalls(fixture.server());
        fixture.server().expect(once(), requestTo(CREATE_URL))
                .andExpect(jsonPath("$.booking.woTable").value(true))
                .andExpect(jsonPath("$.booking.table").doesNotExist())
                .andRespond(withSuccess(CREATED, MediaType.APPLICATION_JSON));

        var result = fixture.provider().reserve(command("19"), "69");

        assertThat(result.created()).isTrue();
        assertThat(result.metadata()).containsEntry("tableSelection", "SABY_ADMINISTRATOR").containsEntry("tableMatch", "UNMATCHED");
        fixture.server().verify();
    }

    @Test
    void aFailedTableListDoesNotStopTheBooking() {
        Fixture fixture = fixture(writable());
        expectAuth(fixture.server());
        fixture.server().expect(once(), requestTo(startsWith(HALL_LIST_URL)))
                .andRespond(withStatus(HttpStatus.BAD_REQUEST));
        fixture.server().expect(once(), requestTo(CREATE_URL))
                .andExpect(jsonPath("$.booking.woTable").value(true))
                .andRespond(withSuccess(CREATED, MediaType.APPLICATION_JSON));

        var result = fixture.provider().reserve(command("4"), "70");

        assertThat(result.created()).isTrue();
        assertThat(result.metadata()).containsEntry("tableMatch", "HALL_LIST_FAILED");
        fixture.server().verify();
    }

    @Test
    void aTableSabyRefusesIsSentOnceMoreWithoutIt() {
        Fixture fixture = fixture(writable());
        expectAuth(fixture.server());
        expectHalls(fixture.server());
        fixture.server().expect(once(), requestTo(CREATE_URL))
                .andExpect(jsonPath("$.booking.table").value(3037))
                .andRespond(withStatus(HttpStatus.UNPROCESSABLE_ENTITY));
        fixture.server().expect(once(), requestTo(CREATE_URL))
                .andExpect(jsonPath("$.booking.woTable").value(true))
                .andExpect(jsonPath("$.booking.table").doesNotExist())
                .andRespond(withSuccess(CREATED, MediaType.APPLICATION_JSON));

        var result = fixture.provider().reserve(command("4"), "71");

        assertThat(result.created()).isTrue();
        assertThat(result.metadata()).containsEntry("tableSelection", "SABY_ADMINISTRATOR")
                .containsEntry("tableMatch", "TABLE_REJECTED").doesNotContainKey("sabyTableId");
        fixture.server().verify();
    }

    @Test
    void aServerErrorWithTheTableIsNeverSentTwice() {
        Fixture fixture = fixture(writable());
        expectAuth(fixture.server());
        expectHalls(fixture.server());
        fixture.server().expect(once(), requestTo(CREATE_URL))
                .andRespond(withStatus(HttpStatus.BAD_GATEWAY));

        var result = fixture.provider().reserve(command("4"), "72");

        assertThat(result.created()).isFalse();
        assertThat(result.status()).isEqualTo("PROVIDER_RESULT_UNKNOWN");
        fixture.server().verify();
    }

    @Test
    void withTheSwitchOffSabyIsNotAskedForTables() {
        SabyReservationProperties properties = writable();
        properties.setSendTable(false);
        Fixture fixture = fixture(properties);
        expectAuth(fixture.server());
        fixture.server().expect(once(), requestTo(CREATE_URL))
                .andExpect(jsonPath("$.booking.woTable").value(true))
                .andRespond(withSuccess(CREATED, MediaType.APPLICATION_JSON));

        var result = fixture.provider().reserve(command("4"), "73");

        assertThat(result.metadata()).containsEntry("tableMatch", "SEND_TABLE_OFF");
        fixture.server().verify();
    }

    @Test
    void theTableListIsReadOnceForSeveralBookings() {
        Fixture fixture = fixture(writable());
        expectAuth(fixture.server());
        expectHalls(fixture.server());
        fixture.server().expect(twice(), requestTo(CREATE_URL))
                .andExpect(jsonPath("$.booking.table").value(3037))
                .andRespond(withSuccess(CREATED, MediaType.APPLICATION_JSON));

        fixture.provider().reserve(command("4"), "74");
        fixture.provider().reserve(command("4"), "75");

        fixture.server().verify();
    }

    private static void expectHalls(MockRestServiceServer server) {
        server.expect(once(), requestTo(startsWith(HALL_LIST_URL)))
                .andExpect(method(HttpMethod.GET))
                .andExpect(queryParam("pointId", "206"))
                .andRespond(withSuccess(HALLS, MediaType.APPLICATION_JSON));
    }

    private static TableReservationCommand command(String tableCode) {
        return new TableReservationCommand(123456L, null, null, "AERIS", tableCode, null, null,
                Instant.parse("2026-10-16T14:00:00Z"), Instant.parse("2026-10-16T16:00:00Z"), 2,
                "Иван", "+79990000000", null, null, null);
    }

    private static void expectAuth(MockRestServiceServer server) {
        server.expect(once(), requestTo(AUTH_URL))
                .andRespond(withSuccess("{\"token\": \"token-1\"}", MediaType.APPLICATION_JSON));
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
