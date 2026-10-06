package museon_online.astor_butler.integration.saby;

import museon_online.astor_butler.domain.billing.BillingTestDatabase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.restclient.RestTemplateBuilder;
import org.springframework.boot.restclient.autoconfigure.RestTemplateAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

import java.net.SocketTimeoutException;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class SabyCallLogInterceptorTest {

    private static final String ORDER_ID = "0b7c1f2e-9d3a-4c55-8e21-5f6a7b8c9d10";

    private final SabyReservationProperties properties = new SabyReservationProperties();
    private JdbcTemplate jdbc;
    private SabyCallLogRepository callLog;
    private RestTemplate restTemplate;
    private MockRestServiceServer server;

    @BeforeEach
    void setUp() {
        properties.setBaseUrl("https://api.saby.test");
        properties.setAuthUrl("https://auth.saby.test/oauth/service/");
        jdbc = BillingTestDatabase.create();
        callLog = new SabyCallLogRepository(jdbc);
        restTemplate = new RestTemplate();
        restTemplate.getInterceptors().add(new SabyCallLogInterceptor(properties, callLog));
        server = MockRestServiceServer.bindTo(restTemplate).build();
    }

    @Test
    void journalsACallToSabyWithoutItsIdsQueryOrBody() {
        server.expect(requestTo("https://api.saby.test/retail/order/" + ORDER_ID + "/state?pointId=206"))
                .andRespond(withSuccess("{\"state\":20,\"payState\":200,\"customer\":{\"phone\":\"88005553535\"}}", MediaType.APPLICATION_JSON));

        String body = restTemplate.getForObject("https://api.saby.test/retail/order/" + ORDER_ID + "/state?pointId=206", String.class);

        assertThat(body).contains("\"payState\":200");
        Map<String, Object> row = jdbc.queryForMap("SELECT * FROM saby_call_log");
        assertThat(row.get("http_method")).isEqualTo("GET");
        assertThat(row.get("operation")).isEqualTo("/retail/order/{id}/state");
        assertThat(row.get("http_status")).isEqualTo(200);
        assertThat(row.get("outcome")).isEqualTo("OK");
        assertThat(row.values().stream().map(String::valueOf)).noneMatch(value ->
                value.contains(ORDER_ID) || value.contains("206") || value.contains("88005553535"));
    }

    @Test
    void journalsTheAuthorizationCallToo() {
        server.expect(requestTo("https://auth.saby.test/oauth/service/")).andRespond(withSuccess("{\"token\":\"secret-token\"}", MediaType.APPLICATION_JSON));

        restTemplate.postForObject("https://auth.saby.test/oauth/service/", Map.of("app_secret", "secret"), String.class);

        Map<String, Object> row = jdbc.queryForMap("SELECT * FROM saby_call_log");
        assertThat(row.get("http_method")).isEqualTo("POST");
        assertThat(row.get("operation")).isEqualTo("/oauth/service");
        assertThat(row.values().stream().map(String::valueOf)).noneMatch(value -> value.contains("secret"));
    }

    @Test
    void journalsAFailureAndStillLetsTheCallerSeeIt() {
        server.expect(requestTo("https://api.saby.test/retail/order/create")).andRespond(withStatus(HttpStatus.BAD_GATEWAY));
        server.expect(requestTo("https://api.saby.test/retail/point/list")).andRespond(withException(new SocketTimeoutException("read timed out")));

        assertThatThrownBy(() -> restTemplate.postForObject("https://api.saby.test/retail/order/create", Map.of(), String.class))
                .isInstanceOf(HttpServerErrorException.class);
        assertThatThrownBy(() -> restTemplate.getForObject("https://api.saby.test/retail/point/list", String.class))
                .isInstanceOf(ResourceAccessException.class);

        assertThat(jdbc.queryForList("SELECT operation, http_status, outcome FROM saby_call_log ORDER BY id"))
                .extracting(row -> row.get("operation") + " " + row.get("http_status") + " " + row.get("outcome"))
                .containsExactly("/retail/order/create 502 HTTP_ERROR", "/retail/point/list 0 IO_ERROR");
    }

    @Test
    void leavesCallsToEveryOtherHostAlone() {
        server.expect(requestTo("https://llm.example.test/v1/completion")).andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

        restTemplate.getForObject("https://llm.example.test/v1/completion", String.class);

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM saby_call_log", Long.class)).isZero();
    }

    @Test
    void aJournalThatCannotBeWrittenDoesNotFailTheCall() {
        SabyCallLogRepository broken = mock(SabyCallLogRepository.class);
        doThrow(new IllegalStateException("database is down")).when(broken).record(anyString(), anyString(), anyInt(), any(), anyLong());
        RestTemplate template = new RestTemplate();
        template.getInterceptors().add(new SabyCallLogInterceptor(properties, broken));
        MockRestServiceServer.bindTo(template).build()
                .expect(requestTo("https://api.saby.test/retail/point/list")).andRespond(withSuccess("{\"salesPoints\":[]}", MediaType.APPLICATION_JSON));

        assertThat(template.getForObject("https://api.saby.test/retail/point/list", String.class)).contains("salesPoints");
    }

    @Test
    void namesAnOperationByWhatItDoes() {
        assertThat(SabyCallLogInterceptor.operation(URI.create("https://api.sbis.ru/retail/order/" + ORDER_ID + "/payment-link?shopURL=x")))
                .isEqualTo("/retail/order/{id}/payment-link");
        assertThat(SabyCallLogInterceptor.operation(URI.create("https://api.sbis.ru/retail/v2/nomenclature/list?pointId=206")))
                .isEqualTo("/retail/v2/nomenclature/list");
        assertThat(SabyCallLogInterceptor.operation(URI.create("https://api.sbis.ru/retail/order/3037/cancel"))).isEqualTo("/retail/order/{id}/cancel");
        assertThat(SabyCallLogInterceptor.operation(URI.create("https://api.sbis.ru"))).isEqualTo("/");
        assertThat(SabyCallLogInterceptor.operation(null)).isEqualTo("/");
    }

    @Test
    void sumsUpHowEachOperationHasBeenDoing() {
        callLog.record("GET", "/retail/order/{id}/state", 200, SabyCallLogRepository.OUTCOME_OK, 100);
        callLog.record("GET", "/retail/order/{id}/state", 200, SabyCallLogRepository.OUTCOME_OK, 300);
        callLog.record("POST", "/retail/order/create", 502, SabyCallLogRepository.OUTCOME_HTTP_ERROR, 900);
        callLog.record("POST", "/retail/order/create", 0, SabyCallLogRepository.OUTCOME_IO_ERROR, 3000);
        callLog.record("POST", "/retail/order/create", 200, SabyCallLogRepository.OUTCOME_OK, 600);

        List<SabyCallLogRepository.OperationSummary> summary = callLog.summarySince(Instant.now().minusSeconds(3600));

        assertThat(summary).containsExactly(
                new SabyCallLogRepository.OperationSummary("POST", "/retail/order/create", 3, 2, 1500, 3000),
                new SabyCallLogRepository.OperationSummary("GET", "/retail/order/{id}/state", 2, 0, 200, 300));
        assertThat(callLog.summarySince(Instant.now().plusSeconds(3600))).isEmpty();
    }

    @Test
    void isAddedToEveryRestTemplateOnlyWhenSwitchedOn() {
        ApplicationContextRunner runner = new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(RestTemplateAutoConfiguration.class))
                .withUserConfiguration(SabyCallLogConfiguration.class)
                .withBean(SabyReservationProperties.class, () -> properties)
                .withBean(SabyCallLogRepository.class, () -> callLog);

        runner.run(context -> assertThat(context.getBean(RestTemplateBuilder.class).build().getInterceptors()).isEmpty());
        runner.withPropertyValues("astor.billing.saby-call-log-enabled=true").run(context ->
                assertThat(context.getBean(RestTemplateBuilder.class).build().getInterceptors())
                        .hasSize(1)
                        .allMatch(SabyCallLogInterceptor.class::isInstance));
    }
}
