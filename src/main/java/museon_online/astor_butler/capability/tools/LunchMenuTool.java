package museon_online.astor_butler.capability.tools;

import museon_online.astor_butler.capability.CapabilityArguments;
import museon_online.astor_butler.capability.CapabilityContext;
import museon_online.astor_butler.capability.CapabilityProperties;
import museon_online.astor_butler.capability.CapabilityResult;
import museon_online.astor_butler.capability.CapabilityTool;
import museon_online.astor_butler.domain.journey.GuestJourneyProperties;
import museon_online.astor_butler.domain.lunch.BusinessLunchCatalog;
import museon_online.astor_butler.domain.lunch.BusinessLunchOffer;
import museon_online.astor_butler.integration.saby.SabyNutrition;
import museon_online.astor_butler.integration.saby.SabyNutritionService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * {@code lunch_menu{date?}}: the business lunch for today or the given day. Weight and nutrition come from Saby when it
 * knows the dish, cached per dish for ten minutes so the agent does not hammer Saby; unknown values are left out.
 */
@Component
public class LunchMenuTool implements CapabilityTool {

    static final Duration NUTRITION_TTL = Duration.ofMinutes(10);
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm");

    private final BusinessLunchCatalog catalog;
    private final SabyNutritionService nutrition;
    private final CapabilityProperties properties;
    private final GuestJourneyProperties journey;
    private final Clock clock;
    private final Map<String, Cached> cache = new ConcurrentHashMap<>();

    private record Cached(Optional<SabyNutrition> value, Instant until) {
    }

    @Autowired
    public LunchMenuTool(BusinessLunchCatalog catalog, SabyNutritionService nutrition,
                         CapabilityProperties properties, GuestJourneyProperties journey) {
        this(catalog, nutrition, properties, journey, Clock.systemUTC());
    }

    public LunchMenuTool(BusinessLunchCatalog catalog, SabyNutritionService nutrition, CapabilityProperties properties,
                         GuestJourneyProperties journey, Clock clock) {
        this.catalog = catalog;
        this.nutrition = nutrition;
        this.properties = properties;
        this.journey = journey;
        this.clock = clock;
    }

    @Override
    public String name() {
        return "lunch_menu";
    }

    @Override
    public String description() {
        return "Меню бизнес-ланча: блюда по разделам с ценой в рублях, часы подачи (from, to) и дни недели (days). "
                + "served — подают ли ланч в этот день. Вес и КБЖУ есть только у блюд, о которых знает Saby; "
                + "если поля нет — данных нет.";
    }

    @Override
    public Map<String, Object> parameters() {
        return Map.of("type", "object",
                "properties", Map.of("date", Map.of("type", "string",
                        "description", "День в формате YYYY-MM-DD; без него — сегодня")),
                "required", List.of());
    }

    @Override
    public boolean commitment() {
        return false;
    }

    @Override
    public CapabilityResult invoke(CapabilityContext ctx, Map<String, Object> args) {
        LocalDate date;
        try {
            date = CapabilityArguments.date(args, "date");
        } catch (CapabilityArguments.BadArgument e) {
            return CapabilityResult.error(CapabilityResult.BAD_ARGUMENT);
        }
        if (date == null) {
            date = LocalDate.now(clock.withZone(zone()));
        }
        Optional<BusinessLunchOffer> found = catalog.find(properties.getVenueCode());
        if (found.isEmpty()) {
            return CapabilityResult.error(CapabilityResult.NOT_FOUND);
        }
        BusinessLunchOffer offer = found.get();
        List<Map<String, Object>> courses = new ArrayList<>();
        for (BusinessLunchOffer.Course course : offer.courses()) {
            List<Map<String, Object>> dishes = new ArrayList<>();
            for (BusinessLunchOffer.Dish dish : course.dishes()) {
                dishes.add(dish(dish));
            }
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("title", course.title());
            entry.put("dishes", dishes);
            courses.add(entry);
        }
        Map<String, Object> facts = new LinkedHashMap<>();
        facts.put("date", date.toString());
        facts.put("served", offer.days().contains(date.getDayOfWeek()));
        facts.put("courses", courses);
        facts.put("from", offer.from().format(TIME));
        facts.put("to", offer.to().format(TIME));
        facts.put("days", offer.days().stream().map(Enum::name).toList());
        return CapabilityResult.ok(facts);
    }

    private Map<String, Object> dish(BusinessLunchOffer.Dish dish) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("code", dish.code());
        entry.put("title", dish.title());
        entry.put("priceRub", dish.priceRub());
        nutrition(dish.sabyLookupName()).ifPresent(value -> {
            entry.put("grams", value.grams());
            entry.put("kcal", value.kcal());
            entry.put("protein", value.protein());
            entry.put("fat", value.fat());
            entry.put("carbs", value.carbs());
        });
        return entry;
    }

    private Optional<SabyNutrition> nutrition(String sabyName) {
        if (nutrition == null || sabyName == null) {
            return Optional.empty();
        }
        Instant now = clock.instant();
        Cached cached = cache.get(sabyName);
        if (cached != null && now.isBefore(cached.until())) {
            return cached.value();
        }
        Optional<SabyNutrition> value;
        try {
            value = nutrition.nutrition(sabyName);
        } catch (RuntimeException e) {
            value = Optional.empty();
        }
        value = value == null ? Optional.empty() : value;
        cache.put(sabyName, new Cached(value, now.plus(NUTRITION_TTL)));
        return value;
    }

    private ZoneId zone() {
        return ZoneId.of(journey.getZoneId());
    }
}
