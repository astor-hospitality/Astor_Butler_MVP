package museon_online.astor_butler.integration.saby;

import museon_online.astor_butler.domain.booking.TableReservationCommand;
import museon_online.astor_butler.domain.booking.external.ExternalReservationProvider;
import museon_online.astor_butler.domain.booking.external.ExternalTableOccupancy;
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
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * What the booking flow relies on in the Saby adapter: the availability answer can be read as
 * per-table occupancy, the result statuses are the ones the hostess card explains, and a
 * cancellation reports plainly whether it went through.
 */
class SabyBookingWiringContractTest {

    private static final String AUTH_URL = "https://auth.saby.test/oauth/service/";
    private static final String EXTERNAL_ID = "43bd679d-ec4a-43f5-9214-b740ef5a3ba6";
    private static final String HALLS = """
            {"halls":[{"id":271,"name":"Основной зал","active":true,"items":[
              {"id":3037,"kind":"table","name":"Стол 1","capacity":4,"busy":false,"isBookingLocked":false,"visible":true},
              {"id":3044,"kind":"table","name":"2","capacity":4,"busy":true,"isBookingLocked":false,"visible":true},
              {"id":3046,"kind":"bar","name":"Бар","capacity":8,"busy":false,"isBookingLocked":true,"visible":true}
            ]}],"outcome":{"hasMore":false}}
            """;

    @Test
    void availabilityAnswerIsReadAsOccupancyOfButlerTables() {
        Fixture fixture = fixture(properties(true));
        expectAuth(fixture.server());
        fixture.server().expect(once(), requestTo(startsWith("https://api.saby.test/retail/hall/list")))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(HALLS, MediaType.APPLICATION_JSON));

        var availability = fixture.provider().checkAvailability(new ExternalReservationProvider.ExternalAvailabilityRequest(
                "AERIS", Instant.parse("2026-10-07T08:00:00Z"), Instant.parse("2026-10-07T10:00:00Z"), 2, null, null));
        ExternalTableOccupancy occupancy = ExternalTableOccupancy.from(availability, () -> List.of("1", "2", "BAR"));

        assertThat(availability.status()).isEqualTo(ExternalTableOccupancy.STATUS_AVAILABLE);
        assertThat(occupancy.authoritative()).isTrue();
        assertThat(occupancy.blocks("1")).isFalse();
        assertThat(occupancy.blocks("2")).isTrue();
        assertThat(occupancy.blocks("BAR")).isTrue();
        fixture.server().verify();
    }

    @Test
    void failedAvailabilityIsNotReadAsAFullRestaurant() {
        Fixture fixture = fixture(properties(true));
        expectAuth(fixture.server());
        fixture.server().expect(once(), requestTo(startsWith("https://api.saby.test/retail/hall/list")))
                .andRespond(withStatus(HttpStatus.BAD_REQUEST));

        var availability = fixture.provider().checkAvailability(new ExternalReservationProvider.ExternalAvailabilityRequest(
                "AERIS", Instant.parse("2026-10-07T08:00:00Z"), Instant.parse("2026-10-07T10:00:00Z"), 2, null, null));
        ExternalTableOccupancy occupancy = ExternalTableOccupancy.from(availability, () -> List.of("1", "2"));

        assertThat(occupancy.authoritative()).isFalse();
        assertThat(occupancy.blocks("1")).isFalse();
        fixture.server().verify();
    }

    @Test
    void resultStatusesAreTheOnesTheHostessCardExplains() {
        Fixture readOnly = fixture(properties(false));
        Fixture writable = fixture(properties(true));

        var writeDisabled = readOnly.provider().reserve(command("+79990000000"), "44");
        var withoutPhone = writable.provider().reserve(command(" "), "44");

        assertThat(writeDisabled.providerConfigured()).isTrue();
        assertThat(writeDisabled.status()).isEqualTo("SABY_WRITE_DISABLED");
        assertThat(withoutPhone.status()).isEqualTo("GUEST_DATA_REQUIRED");
        assertThat(SabyReservationProvider.RESULT_UNKNOWN).isEqualTo("PROVIDER_RESULT_UNKNOWN");
        readOnly.server().verify();
        writable.server().verify();
    }

    @Test
    void cancellationSaysWhetherTheBookingIsGone() {
        Fixture fixture = fixture(properties(true));
        expectAuth(fixture.server());
        fixture.server().expect(once(), requestTo("https://api.saby.test/retail/order/" + EXTERNAL_ID + "/cancel"))
                .andExpect(method(HttpMethod.PUT))
                .andRespond(withSuccess());
        Fixture readOnly = fixture(properties(false));

        assertThat(fixture.provider().cancelReservation(EXTERNAL_ID)).isTrue();
        assertThat(readOnly.provider().cancelReservation(EXTERNAL_ID)).isFalse();
        assertThat(fixture.provider().cancelReservation(" ")).isFalse();
        fixture.server().verify();
        readOnly.server().verify();
    }

    private static void expectAuth(MockRestServiceServer server) {
        server.expect(once(), requestTo(AUTH_URL))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess("{\"token\": \"token-1\"}", MediaType.APPLICATION_JSON));
    }

    private static TableReservationCommand command(String guestPhone) {
        return new TableReservationCommand(
                123456L,
                null,
                null,
                "AERIS",
                "5",
                null,
                null,
                Instant.parse("2026-10-07T08:00:00Z"),
                Instant.parse("2026-10-07T10:00:00Z"),
                2,
                "Наталья",
                guestPhone,
                null,
                null,
                null
        );
    }

    private static SabyReservationProperties properties(boolean writeEnabled) {
        SabyReservationProperties properties = new SabyReservationProperties();
        properties.setEnabled(true);
        properties.setWriteEnabled(writeEnabled);
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
