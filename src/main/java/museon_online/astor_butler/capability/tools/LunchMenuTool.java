package museon_online.astor_butler.capability.tools;

import museon_online.astor_butler.capability.CapabilityArguments;
import museon_online.astor_butler.capability.CapabilityContext;
import museon_online.astor_butler.capability.CapabilityProperties;
import museon_online.astor_butler.capability.CapabilityResult;
import museon_online.astor_butler.capability.CapabilityTool;
import museon_online.astor_butler.capability.GuestAgreement;
import museon_online.astor_butler.domain.journey.GuestJourneyProperties;
import museon_online.astor_butler.domain.lunch.BusinessLunchCatalog;
import museon_online.astor_butler.domain.lunch.BusinessLunchOffer;
import museon_online.astor_butler.domain.lunch.BusinessLunchService;
import museon_online.astor_butler.integration.saby.SabyNutrition;
import museon_online.astor_butler.integration.saby.SabyNutritionService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * {@code lunch_menu{date?, time?}}: the business lunch for today or the given day. {@code text} is the whole menu as the
 * bot prints it ({@link BusinessLunchOffer#menuText}), for the agent and the Concierge to show word for word; {@code served}
 * follows the bot's own rule ({@link BusinessLunchService#windowIssue}) and, when false, comes with the reason and the
 * nearest lunch day ({@link LunchWindowFacts}). Weight and nutrition come from Saby when it knows the dish, cached per dish
 * for ten minutes so the agent does not hammer Saby; unknown values are left out.
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
        return "Меню бизнес-ланча на день визита. На первое сообщение гостя о ланче вызови и сразу покажи text – готовое меню "
                + "(разделы, блюда с порцией и ценой): дословно, целиком, ничего не сокращай и не обобщай. courses – те же блюда "
                + "с кодами (code) и ценой в рублях. Коды – внутренние: гостю их не показывай и у гостя не спрашивай; в "
                + "lunch_order_place передавай блюда так, как их назвал гость. Дни недели (days) и часы подачи (from, to). served – можно ли заказать ланч на этот день "
                + "(и время, если передано); served=false – в это время ланча нет: issue – почему (NOT_A_LUNCH_DAY – не день ланча, "
                + "OUTSIDE_LUNCH_HOURS – не те часы, ALREADY_PASSED – уже прошло), nextServedDay – ближайший день с ланчем, "
                + "его и предложи. Вес и КБЖУ есть только у блюд, о которых знает Saby; если поля нет – данных нет.";
    }

    @Override
    public Map<String, Object> parameters() {
        return Map.of("type", "object",
                "properties", Map.of(
                        "date", Map.of("type", "string", "description", "День визита в формате YYYY-MM-DD; без него – сегодня"),
                        "time", Map.of("type", "string", "description", "Время визита в формате HH:mm, если гость его назвал")),
                "required", List.of());
    }

    @Override
    public boolean commitment() {
        return false;
    }

    @Override
    public CapabilityResult invoke(CapabilityContext ctx, Map<String, Object> args) {
        LocalDate date;
        LocalTime time;
        try {
            date = CapabilityArguments.date(args, "date");
            time = CapabilityArguments.time(args, "time");
        } catch (CapabilityArguments.BadArgument e) {
            return CapabilityResult.error(CapabilityResult.BAD_ARGUMENT);
        }
        ZonedDateTime now = ZonedDateTime.now(clock.withZone(zone()));
        if (date == null) {
            date = now.toLocalDate();
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
        // The bot's rule: a weekend, the hours outside the lunch or a lunch already over are no lunch.
        Optional<BusinessLunchService.WindowIssue> issue = BusinessLunchService.windowIssue(offer, date, time,
                now.toLocalDate(), now.toLocalTime());
        Map<String, Object> facts = new LinkedHashMap<>();
        facts.put("date", date.toString());
        facts.put("dateLabel", GuestAgreement.dateLabel(date));
        if (time != null) {
            facts.put("time", time.format(TIME));
        }
        facts.put("courses", courses);
        facts.putAll(LunchWindowFacts.of(offer, date, issue.isEmpty(), now.toLocalDate(), now.toLocalTime()));
        issue.ifPresent(reason -> facts.put("issue", reason.name()));
        facts.put("text", offer.menuText());
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
