package museon_online.astor_butler.integration.saby;

import org.junit.jupiter.api.Test;
import org.springframework.boot.restclient.RestTemplateBuilder;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;

import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class SabyNutritionServiceTest {

    private static final String AUTH_URL = "https://auth.saby.test/oauth/service/";

    private static SabyReservationProperties configured() {
        SabyReservationProperties properties = new SabyReservationProperties();
        properties.setEnabled(true);
        properties.setBaseUrl("https://api.saby.test");
        properties.setAuthUrl(AUTH_URL);
        properties.setAppClientId("client");
        properties.setAppSecret("secret");
        properties.setSecretKey("service-key");
        properties.setPointId("256");
        properties.setPriceListId("129");
        return properties;
    }

    private record Fixture(SabyNutritionService service, MockRestServiceServer server) {
    }

    private static Fixture fixture(SabyReservationProperties properties) {
        AtomicReference<MockRestServiceServer> serverRef = new AtomicReference<>();
        SabyNutritionService service = new SabyNutritionService(properties,
                new RestTemplateBuilder(restTemplate -> serverRef.set(MockRestServiceServer.bindTo(restTemplate).build())));
        return new Fixture(service, serverRef.get());
    }

    private static void expectAuth(MockRestServiceServer server) {
        server.expect(once(), requestTo(AUTH_URL)).andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess("{\"token\": \"token-1\"}", MediaType.APPLICATION_JSON));
    }

    @Test
    void nutritionComesFromTheTechCard() {
        Fixture fixture = fixture(configured());
        expectAuth(fixture.server());
        fixture.server().expect(once(), requestTo(containsString("/retail/v2/nomenclature/list")))
                .andRespond(withSuccess("{\"nomenclatures\": [{\"id\": 31, \"name\": \"Паста болоньезе Ланч\", \"cost\": 420, "
                        + "\"attributes\": {\"calories\": 212.69, \"outQuantity\": \"280\", \"protein\": 11.62, \"fat\": 5.41, "
                        + "\"carbohydrate\": 29.38}}]}", MediaType.APPLICATION_JSON));

        assertThat(fixture.service().nutrition("Паста болоньезе Ланч")).contains(new SabyNutrition(280, 596, 33, 15, 82));
        fixture.server().verify();
    }

    @Test
    void noAttributesNoNutrition() {
        Fixture fixture = fixture(configured());
        expectAuth(fixture.server());
        fixture.server().expect(once(), requestTo(containsString("/retail/v2/nomenclature/list")))
                .andRespond(withSuccess("{\"nomenclatures\": [{\"id\": 31, \"name\": \"Капучино\", \"cost\": 220}]}", MediaType.APPLICATION_JSON));

        assertThat(fixture.service().nutrition("Капучино")).isEmpty();
    }

    @Test
    void disabledSabyGivesNothing() {
        SabyReservationProperties off = configured();
        off.setEnabled(false);
        Fixture fixture = fixture(off);

        assertThat(fixture.service().nutrition("Паста болоньезе Ланч")).isEmpty();
        fixture.server().verify();
    }

    @Test
    void sabyFailureGivesNothing() {
        Fixture fixture = fixture(configured());
        // The client retries a failed call once (SABY_MAX_RETRIES): every attempt fails.
        fixture.server().expect(org.springframework.test.web.client.ExpectedCount.manyTimes(), requestTo(AUTH_URL))
                .andRespond(org.springframework.test.web.client.response.MockRestResponseCreators.withServerError());

        assertThat(fixture.service().nutrition("Паста болоньезе Ланч")).isEmpty();
    }
}
