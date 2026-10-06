package museon_online.astor_butler.integration.saby;

import museon_online.astor_butler.domain.booking.TableReservationCommand;
import museon_online.astor_butler.domain.booking.external.ExternalReservationResult;
import org.junit.jupiter.api.Test;
import org.springframework.boot.restclient.RestTemplateBuilder;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/** A guest's change reaches Presto as one PUT with the whole booking; a doubtful answer is not reported as done. */
class SabyReservationUpdateTest {

    private static final String AUTH_URL = "https://auth.saby.test/oauth/service/";
    private static final String EXTERNAL_ID = "43bd679d-ec4a-43f5-9214-b740ef5a3ba6";
    private static final String UPDATE_URL = "https://api.saby.test/retail/order/" + EXTERNAL_ID + "/update";

    @Test
    void rewritesTheWholeBookingWithTheButlerMarker() {
        Fixture fixture = fixture(writable());
        expectAuth(fixture.server());
        fixture.server().expect(once(), requestTo(UPDATE_URL))
                .andExpect(method(HttpMethod.PUT))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.product").value("restaurant"))
                .andExpect(jsonPath("$.pointId").value(206))
                .andExpect(jsonPath("$.datetime").value("2026-10-07 15:30:00"))
                .andExpect(jsonPath("$.customer.phone").value("79990000000"))
                .andExpect(jsonPath("$.booking.visitors").value(3))
                .andExpect(jsonPath("$.comment").value(containsString("Astor Butler #12")))
                .andRespond(withSuccess("", MediaType.APPLICATION_JSON));

        ExternalReservationResult result = fixture.provider().updateReservation(EXTERNAL_ID, command(3, "2026-10-07T10:30:00Z"), "12");

        assertThat(result.created()).isTrue();
        assertThat(result.status()).isEqualTo("SABY_ORDER_UPDATED");
        assertThat(result.externalReservationId()).isEqualTo(EXTERNAL_ID);
        fixture.server().verify();
    }

    @Test
    void rejectionAndTimeoutAreNotReportedAsDone() {
        Fixture rejected = fixture(writable());
        expectAuth(rejected.server());
        rejected.server().expect(once(), requestTo(UPDATE_URL)).andRespond(withStatus(HttpStatus.CONFLICT));
        ExternalReservationResult conflict = rejected.provider().updateReservation(EXTERNAL_ID, command(3, "2026-10-07T10:30:00Z"), "12");
        assertThat(conflict.created()).isFalse();
        assertThat(conflict.status()).isEqualTo("PROVIDER_REJECTED");
        rejected.server().verify();

        Fixture failed = fixture(writable());
        expectAuth(failed.server());
        failed.server().expect(once(), requestTo(UPDATE_URL)).andRespond(withStatus(HttpStatus.BAD_GATEWAY));
        ExternalReservationResult unknown = failed.provider().updateReservation(EXTERNAL_ID, command(3, "2026-10-07T10:30:00Z"), "12");
        assertThat(unknown.created()).isFalse();
        assertThat(unknown.status()).isEqualTo("PROVIDER_RESULT_UNKNOWN");
        failed.server().verify();
    }

    @Test
    void writeOffBadIdAndBadPhoneNeverReachSaby() {
        SabyReservationProperties off = writable();
        off.setWriteEnabled(false);
        Fixture fixture = fixture(off);
        assertThat(fixture.provider().updateReservation(EXTERNAL_ID, command(2, "2026-10-07T10:30:00Z"), "12").status()).isEqualTo("SABY_WRITE_DISABLED");

        Fixture on = fixture(writable());
        assertThat(on.provider().updateReservation("bad/id", command(2, "2026-10-07T10:30:00Z"), "12").status()).isEqualTo("INVALID_EXTERNAL_ID");
        TableReservationCommand foreign = new TableReservationCommand(1L, null, null, "AERIS", "4", null, null,
                Instant.parse("2026-10-07T10:30:00Z"), Instant.parse("2026-10-07T12:30:00Z"), 2, "Иван", "+1 212 555 0100", null, null, null);
        assertThat(on.provider().updateReservation(EXTERNAL_ID, foreign, "12").status()).isEqualTo("GUEST_DATA_REQUIRED");
        fixture.server().verify();
        on.server().verify();
    }

    private static TableReservationCommand command(int partySize, String startUtc) {
        Instant start = Instant.parse(startUtc);
        return new TableReservationCommand(123456L, null, null, "AERIS", "4", "WINDOW", null,
                start, start.plusSeconds(7200), partySize, "Иван", "+79990000000", "У окна", null, null);
    }

    private static void expectAuth(MockRestServiceServer server) {
        server.expect(once(), requestTo(AUTH_URL)).andExpect(method(HttpMethod.POST))
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
                new RestTemplateBuilder(restTemplate -> serverRef.set(MockRestServiceServer.bindTo(restTemplate).build())));
        return new Fixture(provider, serverRef.get());
    }

    private record Fixture(SabyReservationProvider provider, MockRestServiceServer server) {
    }
}
