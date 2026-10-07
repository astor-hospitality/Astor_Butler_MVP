package museon_online.astor_butler.integration.saby;

import com.fasterxml.jackson.databind.ObjectMapper;
import museon_online.astor_butler.domain.billing.ExternalPaymentProvider;
import org.junit.jupiter.api.Test;
import org.springframework.boot.restclient.RestTemplateBuilder;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.ExpectedCount.twice;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/** The payment page is Saby's; Butler only asks for it, and only reads a link it can trust. */
class SabyPaymentProviderTest {

    private static final String AUTH_URL = "https://auth.saby.test/oauth/service/";
    private static final String ORDER_ID = "43bd679d-ec4a-43f5-9214-b740ef5a3ba6";
    private static final String LINK_URL = "https://api.saby.test/retail/order/" + ORDER_ID + "/payment-link";

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void asksSabyForThePageWithTheServiceToken() {
        Fixture fixture = fixture(properties(true));
        expectAuth(fixture.server());
        fixture.server().expect(once(), requestTo(LINK_URL)).andExpect(method(HttpMethod.GET))
                .andExpect(header(SabyApiClient.TOKEN_HEADER, "token-1"))
                .andRespond(withSuccess("{\"link\":\"https://online.sbis.ru/pay/abc\"}", MediaType.APPLICATION_JSON));

        Optional<ExternalPaymentProvider.PaymentLink> link = fixture.provider().paymentLink(ORDER_ID);

        assertThat(link).map(ExternalPaymentProvider.PaymentLink::url).contains("https://online.sbis.ru/pay/abc");
        assertThat(link.orElseThrow().amountMinor()).isNull();
        fixture.server().verify();
    }

    @Test
    void findsTheLinkUnderTheUsualNamesAndNowhereElse() throws Exception {
        assertThat(SabyPaymentProvider.link(objectMapper.readTree("{\"url\":\"https://pay.test/1\"}"))).isEqualTo("https://pay.test/1");
        assertThat(SabyPaymentProvider.link(objectMapper.readTree("{\"paymentLink\":\"https://pay.test/2\"}"))).isEqualTo("https://pay.test/2");
        assertThat(SabyPaymentProvider.link(objectMapper.readTree("{\"result\":{\"payment_url\":\"https://pay.test/3\"}}"))).isEqualTo("https://pay.test/3");
        assertThat(SabyPaymentProvider.link(objectMapper.readTree("\"https://pay.test/4\""))).isEqualTo("https://pay.test/4");
        assertThat(SabyPaymentProvider.link(objectMapper.readTree("{\"link\":\"http://saby-stub:8090/__pay/x\"}"))).isEqualTo("http://saby-stub:8090/__pay/x");

        assertThat(SabyPaymentProvider.link(objectMapper.readTree("{\"link\":\"http://evil.test/pay\"}"))).as("plain http outside the stand").isNull();
        assertThat(SabyPaymentProvider.link(objectMapper.readTree("{\"link\":\"javascript:alert(1)\"}"))).isNull();
        assertThat(SabyPaymentProvider.link(objectMapper.readTree("{\"link\":\"https://pay.test/with space\"}"))).isNull();
        assertThat(SabyPaymentProvider.link(objectMapper.readTree("{\"status\":\"ok\"}"))).isNull();
        assertThat(SabyPaymentProvider.link(objectMapper.readTree("{\"link\":null}"))).isNull();
        assertThat(SabyPaymentProvider.link(null)).isNull();
    }

    @Test
    void anAnswerWithoutALinkIsNoLinkNotAGuess() {
        Fixture fixture = fixture(properties(true));
        expectAuth(fixture.server());
        fixture.server().expect(once(), requestTo(LINK_URL)).andRespond(withSuccess("{\"status\":\"no acquiring\"}", MediaType.APPLICATION_JSON));

        assertThat(fixture.provider().paymentLink(ORDER_ID)).isEmpty();
    }

    @Test
    void aFailedCallIsThrownForTheCallerToJournal() {
        Fixture fixture = fixture(properties(true));
        expectAuth(fixture.server());
        fixture.server().expect(twice(), requestTo(LINK_URL)).andRespond(withStatus(HttpStatus.BAD_GATEWAY));

        assertThatThrownBy(() -> fixture.provider().paymentLink(ORDER_ID)).isInstanceOf(SabyApiException.class);
        assertThatThrownBy(() -> fixture.provider().paymentLink("../admin")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void isOffUntilPaymentIsSwitchedOnAndConfigured() {
        SabyReservationProperties off = properties(false);
        Fixture fixture = fixture(off);

        assertThat(fixture.provider().enabled()).isFalse();
        assertThat(fixture.provider().paymentLink(ORDER_ID)).isEmpty();

        SabyReservationProperties unconfigured = properties(true);
        unconfigured.setPointId("");
        assertThat(fixture(unconfigured).provider().enabled()).isFalse();
        assertThat(fixture(properties(true)).provider().enabled()).isTrue();
        assertThat(fixture(properties(true)).provider().providerId()).isEqualTo("SABY");
        fixture.server().verify();
    }

    private static void expectAuth(MockRestServiceServer server) {
        server.expect(once(), requestTo(AUTH_URL)).andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess("{\"token\":\"token-1\"}", MediaType.APPLICATION_JSON));
    }

    private static SabyReservationProperties properties(boolean paymentEnabled) {
        SabyReservationProperties properties = new SabyReservationProperties();
        properties.setEnabled(true);
        properties.setPaymentEnabled(paymentEnabled);
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
        SabyPaymentProvider provider = new SabyPaymentProvider(properties,
                new RestTemplateBuilder(restTemplate -> serverRef.set(MockRestServiceServer.bindTo(restTemplate).build())));
        return new Fixture(provider, serverRef.get());
    }

    private record Fixture(SabyPaymentProvider provider, MockRestServiceServer server) {
    }
}
