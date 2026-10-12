package museon_online.astor_butler.api.glasses;

import jakarta.servlet.http.HttpServletRequest;
import museon_online.astor_butler.domain.lunch.BusinessLunchCatalog;
import museon_online.astor_butler.domain.lunch.BusinessLunchOffer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The numbered lunch dishes of a venue for the glasses runtime (#184): «забить 14 на 12» needs to know that 14 is
 * the borscht, and the runtime has no menu of its own. Read only, server to server, behind the same shared relay
 * token as {@link GlassesStaffTaskController}; the list carries numbers, codes and titles — no prices, no composition.
 *
 * A venue whose dishes have no numbers yet answers with an empty list: the runtime then says the numbers are not
 * set rather than guessing. The runtime caches the answer for ten minutes, so the file is read here, not there.
 */
@RestController
public class GlassesLunchDishesController {
    static final int VENUE_LIMIT = 40;
    private final BusinessLunchCatalog catalog;
    private final String token;

    public GlassesLunchDishesController(BusinessLunchCatalog catalog,
                                        @Value("${astor.glasses.relay-token:}") String token) {
        this.catalog = catalog;
        this.token = token;
    }

    /** One dish as the glasses see it: the spoken number, the code of the menu file and the title to repeat aloud. */
    public record NumberedDish(int number, String code, String title) { }

    @GetMapping("/api/internal/glasses/lunch-dishes")
    public ResponseEntity<?> dishes(HttpServletRequest request, @RequestParam(name = "venue", required = false) String venue) {
        if (token == null || token.length() < 16) return error(503, "RELAY_UNAVAILABLE");
        String presented = request.getHeader("X-Astor-Relay-Token");
        if (presented == null || presented.length() > 512
                || !MessageDigest.isEqual(token.getBytes(StandardCharsets.UTF_8), presented.getBytes(StandardCharsets.UTF_8))) {
            return error(401, "UNAUTHORIZED");
        }
        if (venue == null || venue.isBlank() || venue.length() > VENUE_LIMIT) return error(400, "MALFORMED_REQUEST");
        Optional<BusinessLunchOffer> offer = catalog.find(venue);
        if (offer.isEmpty()) return error(404, "VENUE_NOT_FOUND");
        List<NumberedDish> dishes = offer.get().numberedDishes().stream()
                .map(dish -> new NumberedDish(dish.number(), dish.code(), dish.title())).toList();
        return ResponseEntity.ok().header("Cache-Control", "no-store").contentType(MediaType.APPLICATION_JSON).body(dishes);
    }

    private static ResponseEntity<?> error(int status, String code) {
        return ResponseEntity.status(status).header("Cache-Control", "no-store").contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("error", Map.of("code", code)));
    }
}
