package museon_online.astor_butler.integration.saby;

import museon_online.astor_butler.domain.billing.BillPayState;
import museon_online.astor_butler.domain.booking.external.ExternalBookingSnapshot;
import museon_online.astor_butler.domain.booking.external.ExternalBookingState;
import org.junit.jupiter.api.Test;
import org.springframework.boot.restclient.RestTemplateBuilder;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;

import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.ExpectedCount.twice;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/** The read the sync job relies on: one GET per booking, documented codes mapped, every failure is UNKNOWN. */
class SabyReservationStateSyncTest {

    private static final String AUTH_URL = "https://auth.saby.test/oauth/service/";
    private static final String EXTERNAL_ID = "43bd679d-ec4a-43f5-9214-b740ef5a3ba6";
    private static final String STATE_URL = "https://api.saby.test/retail/order/" + EXTERNAL_ID + "/state";

    @Test
    void confirmedAndCancelledCodesReachTheDomain() {
        for (var expected : new Object[][] {
                {"{\"state\": 20}", ExternalBookingState.CONFIRMED, "SABY_STATE_20"},
                {"{\"state\": 10, \"productState\": 1998}", ExternalBookingState.CANCELLED, "SABY_STATE_10"},
                {"{\"state\": 10}", ExternalBookingState.PENDING, "SABY_STATE_10"},
                {"{\"state\": 200}", ExternalBookingState.COMPLETED, "SABY_STATE_200"},
                {"{\"state\": 150}", ExternalBookingState.UNKNOWN, "SABY_STATE_150"},
        }) {
            Fixture fixture = fixture();
            expectAuth(fixture.server());
            fixture.server().expect(once(), requestTo(STATE_URL)).andExpect(method(HttpMethod.GET))
                    .andRespond(withSuccess((String) expected[0], MediaType.APPLICATION_JSON));

            ExternalBookingSnapshot snapshot = fixture.provider().fetchReservationState(EXTERNAL_ID);

            assertThat(snapshot.state()).as((String) expected[0]).isEqualTo(expected[1]);
            assertThat(snapshot.status()).isEqualTo(expected[2]);
            assertThat(snapshot.externalReservationId()).isEqualTo(EXTERNAL_ID);
            assertThat(snapshot.providerId()).isEqualTo("SABY");
            fixture.server().verify();
        }
    }

    @Test
    void thePayStateRidesAlongForBilling() {
        for (var expected : new Object[][] {
                {"{\"state\": 20, \"payState\": 200}", BillPayState.PAID},
                {"{\"state\": 20, \"payState\": 0}", BillPayState.UNPAID},
                {"{\"state\": 20}", BillPayState.UNKNOWN},
        }) {
            Fixture fixture = fixture();
            expectAuth(fixture.server());
            fixture.server().expect(once(), requestTo(STATE_URL)).andRespond(withSuccess((String) expected[0], MediaType.APPLICATION_JSON));

            ExternalBookingSnapshot snapshot = fixture.provider().fetchReservationState(EXTERNAL_ID);

            assertThat(snapshot.metadata().get("billPayState")).as((String) expected[0]).isEqualTo(expected[1]);
        }
    }

    @Test
    void failedReadIsUnknownAndNeverThrows() {
        Fixture fixture = fixture();
        expectAuth(fixture.server());
        // A 5xx on a read is retried once by the client (SABY_MAX_RETRIES=1), so the stand-in answers twice.
        fixture.server().expect(twice(), requestTo(STATE_URL)).andRespond(withStatus(HttpStatus.BAD_GATEWAY));

        ExternalBookingSnapshot snapshot = fixture.provider().fetchReservationState(EXTERNAL_ID);

        assertThat(snapshot.state()).isEqualTo(ExternalBookingState.UNKNOWN);
        assertThat(snapshot.status()).startsWith("PROVIDER_");
        assertThat(fixture.provider().fetchReservationState("bad/id").state()).isEqualTo(ExternalBookingState.UNKNOWN);
        assertThat(fixture.provider().fetchReservationState("bad/id").status()).isEqualTo("INVALID_EXTERNAL_ID");
        fixture.server().verify();
    }

    @Test
    void unconfiguredProviderAnswersUnknownWithoutAnyCall() {
        SabyReservationProperties properties = new SabyReservationProperties();
        properties.setEnabled(false);
        Fixture fixture = fixture(properties);

        assertThat(fixture.provider().fetchReservationState(EXTERNAL_ID).state()).isEqualTo(ExternalBookingState.UNKNOWN);
        fixture.server().verify();
    }

    private static void expectAuth(MockRestServiceServer server) {
        server.expect(once(), requestTo(AUTH_URL)).andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess("{\"token\": \"token-1\"}", MediaType.APPLICATION_JSON));
    }

    private static Fixture fixture() {
        SabyReservationProperties properties = new SabyReservationProperties();
        properties.setEnabled(true);
        properties.setBaseUrl("https://api.saby.test");
        properties.setAuthUrl(AUTH_URL);
        properties.setAppClientId("client");
        properties.setAppSecret("secret");
        properties.setSecretKey("service-key");
        properties.setPointId("206");
        return fixture(properties);
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
