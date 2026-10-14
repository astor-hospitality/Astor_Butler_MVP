package museon_online.astor_butler.api.board;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.health.actuate.endpoint.HealthDescriptor;
import org.springframework.boot.health.actuate.endpoint.HealthEndpoint;
import org.springframework.boot.health.contributor.Status;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import java.util.List;

/**
 * The live architecture map: one light per part. Infrastructure comes from the actuator health components, GigaChat from
 * the latest audited model calls, Saby from its call log of the last quarter hour, the Concierge from its readiness URL.
 */
@Component
public class HealthMap {

    static final int MODEL_CALLS = 5;
    static final Duration SABY_WINDOW = Duration.ofMinutes(15);
    static final Duration CONCIERGE_TIMEOUT = Duration.ofSeconds(2);

    public enum Light { GREEN, YELLOW, RED }

    public record Component(String component, Light status, String detail) {
    }

    private final BoardQueries queries;
    private final BoardProperties properties;
    private final ObjectProvider<HealthEndpoint> health;
    private final Clock clock;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(CONCIERGE_TIMEOUT)
            .followRedirects(HttpClient.Redirect.NEVER).build();

    @Autowired
    public HealthMap(BoardQueries queries, BoardProperties properties, ObjectProvider<HealthEndpoint> health) {
        this(queries, properties, health, Clock.systemUTC());
    }

    public HealthMap(BoardQueries queries, BoardProperties properties, ObjectProvider<HealthEndpoint> health, Clock clock) {
        this.queries = queries;
        this.properties = properties;
        this.health = health;
        this.clock = clock;
    }

    public List<Component> map() {
        return List.of(
                new Component("butler", Light.GREEN, "отвечает"),
                infrastructure("postgres", "db"),
                infrastructure("redis", "redis"),
                infrastructure("kafka", "kafka"),
                gigachat(),
                saby(),
                concierge());
    }

    private Component infrastructure(String component, String healthComponent) {
        try {
            HealthEndpoint endpoint = health.getIfAvailable();
            HealthDescriptor descriptor = endpoint == null ? null : endpoint.healthForPath(healthComponent);
            if (descriptor == null) {
                return new Component(component, Light.YELLOW, "нет проверки");
            }
            Status status = descriptor.getStatus();
            if (Status.UP.equals(status)) {
                return new Component(component, Light.GREEN, "UP");
            }
            if (Status.DOWN.equals(status) || Status.OUT_OF_SERVICE.equals(status)) {
                return new Component(component, Light.RED, status.getCode());
            }
            return new Component(component, Light.YELLOW, status == null ? "UNKNOWN" : status.getCode());
        } catch (RuntimeException e) {
            return new Component(component, Light.YELLOW, "проверка не удалась");
        }
    }

    private Component gigachat() {
        try {
            List<Boolean> outcomes = queries.lastModelOutcomes(MODEL_CALLS);
            long failed = outcomes.stream().filter(success -> !success).count();
            String detail = "ошибок " + failed + " из последних " + outcomes.size();
            if (outcomes.size() == MODEL_CALLS && failed == MODEL_CALLS) {
                return new Component("gigachat", Light.RED, detail);
            }
            return new Component("gigachat", failed > 0 ? Light.YELLOW : Light.GREEN, outcomes.isEmpty() ? "вызовов не было" : detail);
        } catch (RuntimeException e) {
            return new Component("gigachat", Light.YELLOW, "аудит недоступен");
        }
    }

    private Component saby() {
        try {
            BoardQueries.SabyTotals totals = queries.sabySince(clock.instant().minus(SABY_WINDOW));
            if (totals.calls() == 0) {
                return new Component("saby", Light.GREEN, "вызовов за 15 минут не было");
            }
            double ratio = (double) totals.failures() / totals.calls();
            Light light = ratio > 0.5 ? Light.RED : ratio > 0.1 ? Light.YELLOW : Light.GREEN;
            return new Component("saby", light, "ошибок " + totals.failures() + " из " + totals.calls() + " за 15 минут");
        } catch (RuntimeException e) {
            return new Component("saby", Light.YELLOW, "журнал недоступен");
        }
    }

    private Component concierge() {
        String url = properties.getConciergeHealthUrl();
        if (url == null || url.isBlank()) {
            return new Component("concierge", Light.YELLOW, "адрес проверки не задан");
        }
        try {
            URI uri = URI.create(url.trim());
            if (!"http".equals(uri.getScheme()) && !"https".equals(uri.getScheme())) {
                return new Component("concierge", Light.YELLOW, "адрес проверки не http");
            }
            HttpResponse<Void> response = http.send(HttpRequest.newBuilder(uri).timeout(CONCIERGE_TIMEOUT).GET().build(),
                    HttpResponse.BodyHandlers.discarding());
            return response.statusCode() == 200
                    ? new Component("concierge", Light.GREEN, "готов")
                    : new Component("concierge", Light.RED, "HTTP " + response.statusCode());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Component("concierge", Light.RED, "нет ответа");
        } catch (Exception e) {
            return new Component("concierge", Light.RED, "нет ответа");
        }
    }
}
