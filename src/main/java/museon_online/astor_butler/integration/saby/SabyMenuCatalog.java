package museon_online.astor_butler.integration.saby;

import com.fasterxml.jackson.databind.JsonNode;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import lombok.extern.slf4j.Slf4j;

import java.time.Duration;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Finds Saby's own ids for what the guest ordered by name: the venue's price list
 * ({@code GET /retail/nomenclature/price-list}) and the dish in it ({@code GET /retail/v2/nomenclature/list}
 * with {@code searchString}). Butler's menu file carries titles, not Saby ids, and the titles on aeris.bar and in
 * Presto are kept by the same people, so an exact (case-insensitive) match is trusted and anything else is not.
 * Answers are cached for an hour; a miss is a miss for the caller to report, never a guess.
 */
@Slf4j
final class SabyMenuCatalog {

    static final String PRICE_LIST_PATH = "/retail/nomenclature/price-list";
    static final String NOMENCLATURE_PATH = "/retail/v2/nomenclature/list";
    private static final DateTimeFormatter ACTUAL_DATE = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm:ss");

    record Dish(long id, String name, Integer costRub) {
    }

    private final SabyReservationProperties properties;
    private final SabyApiClient client;
    private final Cache<String, Optional<Long>> priceLists = Caffeine.newBuilder().expireAfterWrite(Duration.ofHours(1)).build();
    private final Cache<String, Optional<Dish>> dishes = Caffeine.newBuilder().expireAfterWrite(Duration.ofHours(1)).maximumSize(2_000).build();

    SabyMenuCatalog(SabyReservationProperties properties, SabyApiClient client) {
        this.properties = properties;
        this.client = client;
    }

    /** The configured price list, or the first one Saby reports for the point right now. Throws {@link SabyApiException}. */
    Optional<Long> priceListId(ZonedDateTime now) {
        String configured = properties.getPriceListId();
        if (configured != null && !configured.isBlank()) {
            try {
                return Optional.of(Long.parseLong(configured.trim()));
            } catch (NumberFormatException e) {
                log.warn("SABY_PRICE_LIST_ID is not a number; falling back to the price list Saby reports");
            }
        }
        return priceLists.get("default", key -> {
            Map<String, Object> query = new LinkedHashMap<>();
            query.put("pointId", properties.getPointId());
            query.put("actualDate", ACTUAL_DATE.format(now.withZoneSameInstant(ZoneId.of(properties.getZoneId()))));
            query.put("pageSize", 50);
            JsonNode response = client.get(PRICE_LIST_PATH, query);
            JsonNode items = firstArray(response, "priceLists", "items", "result");
            for (JsonNode item : items) {
                JsonNode id = item.path("id");
                if (id.isIntegralNumber() || (id.isTextual() && id.asText().matches("\\d+"))) {
                    return Optional.of(id.asLong());
                }
            }
            return Optional.empty();
        });
    }

    /** The dish with exactly this title in the price list, if Saby has one. Throws {@link SabyApiException}. */
    Optional<Dish> findDish(String title, long priceListId) {
        String wanted = normalize(title);
        if (wanted.isEmpty()) {
            return Optional.empty();
        }
        return dishes.get(priceListId + "|" + wanted, key -> {
            Map<String, Object> query = new LinkedHashMap<>();
            query.put("pointId", properties.getPointId());
            query.put("priceListId", priceListId);
            query.put("searchString", title.trim());
            query.put("pageSize", 50);
            JsonNode response = client.get(NOMENCLATURE_PATH, query);
            for (JsonNode item : firstArray(response, "nomenclatures", "items", "result")) {
                if (item.path("isParent").asBoolean(false)) {
                    continue;
                }
                if (normalize(item.path("name").asText("")).equals(wanted) && item.path("id").isIntegralNumber()) {
                    JsonNode cost = item.path("cost");
                    return Optional.of(new Dish(item.path("id").asLong(), item.path("name").asText(),
                            cost.isNumber() ? Integer.valueOf(cost.asInt()) : null));
                }
            }
            return Optional.empty();
        });
    }

    private static JsonNode firstArray(JsonNode response, String... fields) {
        if (response.isArray()) {
            return response;
        }
        for (String field : fields) {
            JsonNode node = response.path(field);
            if (node.isArray()) {
                return node;
            }
        }
        return response.path("__none__");
    }

    private static String normalize(String text) {
        return text == null ? "" : text.trim().toLowerCase(Locale.forLanguageTag("ru")).replace('ё', 'е').replaceAll("\\s+", " ");
    }
}
