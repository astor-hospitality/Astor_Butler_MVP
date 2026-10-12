package museon_online.astor_butler.api.glasses;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The numbered lunch dishes of a venue, read from Butler ({@code GET /api/internal/glasses/lunch-dishes?venue=…}, the
 * same shared relay token as the staff-task relay) and kept for ten minutes: a short order resolves «14» to the
 * borscht here, and the runtime holds no menu of its own (#184).
 *
 * A failure to read is a refusal of the order the wearer can act on — say it again — never a guess from stale or
 * missing data. A venue whose dishes carry no numbers answers with an empty list, which is a fact and is cached too.
 */
@Component
public class GlassesLunchDishes {
    private static final Logger log = LoggerFactory.getLogger(GlassesLunchDishes.class);
    static final Duration TTL = Duration.ofMinutes(10);
    static final int MAX_DISHES = 500;
    private final URI upstream;
    private final String token;
    private final HttpClient client;
    private final Clock clock;
    private final ObjectMapper mapper = new ObjectMapper();
    private final Map<String, Cached> cache = new ConcurrentHashMap<>();

    /** One dish as Butler lists it: the spoken number, the code of the menu file and the title to repeat aloud. */
    public record Dish(int number, String code, String title) { }

    private record Cached(List<Dish> dishes, Instant until) { }

    @Autowired
    public GlassesLunchDishes(
            @Value("${astor.glasses.lunch-dishes-url:http://aeris-astor-butler-bot:8089/api/internal/glasses/lunch-dishes}") String upstream,
            @Value("${astor.glasses.relay-token:}") String token) {
        this(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).followRedirects(HttpClient.Redirect.NEVER).build(),
                URI.create(upstream), token, Clock.systemUTC());
    }

    GlassesLunchDishes(HttpClient client, URI upstream, String token, Clock clock) {
        this.client = client;
        this.upstream = upstream;
        this.token = token == null ? "" : token;
        this.clock = clock;
    }

    public static GlassesLunchDishes disabled() {
        return new GlassesLunchDishes(HttpClient.newHttpClient(), URI.create("http://butler.invalid/api/internal/glasses/lunch-dishes"),
                "", Clock.systemUTC());
    }

    boolean configured() { return token.length() >= 16; }

    /** The venue's numbered dishes, from the cache while it is fresh; empty when the venue has no numbers yet. */
    List<Dish> dishes(String venue) {
        String key = venue == null ? "" : venue.strip().toLowerCase(Locale.ROOT);
        Instant now = clock.instant();
        Cached cached = cache.get(key);
        if (cached != null && now.isBefore(cached.until())) return cached.dishes();
        List<Dish> dishes = fetch(key);
        cache.put(key, new Cached(dishes, now.plus(TTL)));
        return dishes;
    }

    /** The dish under this number, if the venue has one. */
    Optional<Dish> dish(String venue, int number) {
        return dishes(venue).stream().filter(dish -> dish.number() == number).findFirst();
    }

    /** Forgets what was read, so the next order reads the menu again (a new menu version, a test). */
    void forget() { cache.clear(); }

    private List<Dish> fetch(String venue) {
        if (!configured()) throw GlassesShortOrders.refused();
        try {
            URI uri = URI.create(upstream + "?venue=" + URLEncoder.encode(venue, StandardCharsets.UTF_8));
            var request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(8))
                    .header("Accept", "application/json")
                    .header("X-Astor-Relay-Token", token)
                    .GET().build();
            var response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                log.warn("Glasses lunch dishes refused by Butler: HTTP {}", response.statusCode());
                throw GlassesShortOrders.refused();
            }
            return parse(mapper.readTree(response.body()));
        } catch (GlassesFailure failure) {
            throw failure;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw GlassesShortOrders.refused();
        } catch (Exception e) {
            log.warn("Glasses lunch dishes relay failed: {}", e.getClass().getSimpleName());
            throw GlassesShortOrders.refused();
        }
    }

    private static List<Dish> parse(JsonNode json) {
        if (json == null || !json.isArray()) throw GlassesShortOrders.refused();
        List<Dish> dishes = new ArrayList<>();
        for (JsonNode node : json) {
            if (!node.isObject() || !node.path("number").canConvertToInt()) throw GlassesShortOrders.refused();
            int number = node.path("number").intValue();
            String code = node.path("code").asText("");
            String title = node.path("title").asText("");
            if (number <= 0 || title.isBlank()) throw GlassesShortOrders.refused();
            dishes.add(new Dish(number, code.isBlank() ? null : code, title.strip()));
            if (dishes.size() > MAX_DISHES) throw GlassesShortOrders.refused();
        }
        return List.copyOf(dishes);
    }
}
