package museon_online.astor_butler.api.board;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.health.actuate.endpoint.HealthEndpoint;

import java.net.InetSocketAddress;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class HealthMapTest {

    private static final Instant NOW = Instant.parse("2026-10-15T09:00:00Z");

    private BoardTestDatabase database;
    private BoardProperties properties;
    private HealthMap map;
    private HttpServer server;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        database = BoardTestDatabase.create();
        properties = new BoardProperties();
        properties.setConciergeHealthUrl("");
        map = new HealthMap(new BoardQueries(database.jdbc(), new ObjectMapper()), properties,
                mock(ObjectProvider.class), Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @AfterEach
    void tearDown() throws Exception {
        if (server != null) {
            server.stop(0);
        }
        database.close();
    }

    @Test
    void sabyIsYellowAboveTenPercentErrorsAndRedAboveHalf() {
        for (int i = 0; i < 8; i++) {
            database.sabyCall(200, "OK", NOW.minus(1, ChronoUnit.MINUTES));
        }
        database.sabyCall(500, "HTTP_ERROR", NOW.minus(2, ChronoUnit.MINUTES));
        database.sabyCall(500, "HTTP_ERROR", NOW.minus(3, ChronoUnit.MINUTES));
        // Older than fifteen minutes: not counted.
        for (int i = 0; i < 30; i++) {
            database.sabyCall(0, "IO_ERROR", NOW.minus(20, ChronoUnit.MINUTES));
        }
        assertThat(status("saby")).isEqualTo(HealthMap.Light.YELLOW);

        for (int i = 0; i < 9; i++) {
            database.sabyCall(0, "IO_ERROR", NOW.minus(1, ChronoUnit.MINUTES));
        }
        assertThat(status("saby")).isEqualTo(HealthMap.Light.RED);
    }

    @Test
    void sabyIsGreenWithFewErrors() {
        for (int i = 0; i < 10; i++) {
            database.sabyCall(200, "OK", NOW.minus(1, ChronoUnit.MINUTES));
        }
        database.sabyCall(500, "HTTP_ERROR", NOW.minus(1, ChronoUnit.MINUTES));
        assertThat(status("saby")).isEqualTo(HealthMap.Light.GREEN);
    }

    @Test
    void gigachatRedWhenLastFiveCallsFailed() {
        database.modelCall(true, false, 100, NOW.minus(10, ChronoUnit.MINUTES));
        for (int i = 5; i >= 1; i--) {
            database.modelCall(false, false, 100, NOW.minus(i, ChronoUnit.MINUTES));
        }
        assertThat(status("gigachat")).isEqualTo(HealthMap.Light.RED);

        database.modelCall(true, false, 100, NOW);
        assertThat(status("gigachat")).isEqualTo(HealthMap.Light.YELLOW);
    }

    @Test
    void conciergeRedWhenHealthUrlFails() throws Exception {
        assertThat(status("concierge")).isEqualTo(HealthMap.Light.YELLOW);

        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/readyz", exchange -> {
            exchange.sendResponseHeaders(exchange.getRequestURI().getQuery() == null ? 503 : 200, -1);
            exchange.close();
        });
        server.start();
        String base = "http://127.0.0.1:" + server.getAddress().getPort() + "/readyz";

        properties.setConciergeHealthUrl(base);
        assertThat(status("concierge")).isEqualTo(HealthMap.Light.RED);
        properties.setConciergeHealthUrl(base + "?ok");
        assertThat(status("concierge")).isEqualTo(HealthMap.Light.GREEN);
        properties.setConciergeHealthUrl("http://127.0.0.1:1/readyz");
        assertThat(status("concierge")).isEqualTo(HealthMap.Light.RED);
    }

    @Test
    void everyComponentIsOnTheMapAndButlerIsGreen() {
        assertThat(map.map()).extracting(HealthMap.Component::component)
                .containsExactly("butler", "postgres", "redis", "kafka", "gigachat", "saby", "concierge");
        assertThat(status("butler")).isEqualTo(HealthMap.Light.GREEN);
        assertThat(status("postgres")).isEqualTo(HealthMap.Light.YELLOW);
    }

    private HealthMap.Light status(String component) {
        return map.map().stream().filter(c -> c.component().equals(component)).findFirst().orElseThrow().status();
    }

    /** Kafka is not in /actuator/health (it is off the booking path): the board asks the brokers itself. */
    @Test
    void kafkaIsGreenWithBrokersYellowWhenOffRedWhenSilent() throws Exception {
        KafkaProbe probe = org.mockito.Mockito.mock(KafkaProbe.class);
        map.setKafka(probe);

        org.mockito.Mockito.when(probe.brokers()).thenReturn(java.util.OptionalInt.of(1));
        assertThat(status("kafka")).isEqualTo(HealthMap.Light.GREEN);
        assertThat(detail("kafka")).isEqualTo("брокеров: 1");

        org.mockito.Mockito.when(probe.brokers()).thenReturn(java.util.OptionalInt.empty());
        assertThat(status("kafka")).isEqualTo(HealthMap.Light.YELLOW);
        assertThat(detail("kafka")).isEqualTo("выключена");

        org.mockito.Mockito.when(probe.brokers()).thenThrow(new java.util.concurrent.TimeoutException());
        assertThat(status("kafka")).isEqualTo(HealthMap.Light.RED);
        assertThat(detail("kafka")).isEqualTo("не отвечает");
    }

    private String detail(String component) {
        return map.map().stream().filter(c -> c.component().equals(component)).findFirst().orElseThrow().detail();
    }
}
