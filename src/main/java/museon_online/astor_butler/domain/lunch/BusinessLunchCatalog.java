package museon_online.astor_butler.domain.lunch;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Business lunch offers by venue, read once from {@code classpath*:business-lunch/*.json}.
 * A file that does not describe a usable offer is skipped with an error in the log: the scenario then says
 * the lunch is not available for that venue instead of offering a broken menu.
 */
@Component
@Slf4j
public class BusinessLunchCatalog {

    private static final String LOCATION = "classpath*:business-lunch/*.json";

    private final Map<String, BusinessLunchOffer> offers = new LinkedHashMap<>();

    @Autowired
    public BusinessLunchCatalog(ObjectMapper objectMapper) {
        this(load(objectMapper));
    }

    public BusinessLunchCatalog(Collection<BusinessLunchOffer> offers) {
        for (BusinessLunchOffer offer : offers) {
            this.offers.put(key(offer.venueCode()), offer);
        }
    }

    public Optional<BusinessLunchOffer> find(String venueCode) {
        return Optional.ofNullable(offers.get(key(venueCode)));
    }

    public static BusinessLunchOffer read(ObjectMapper objectMapper, InputStream json) throws IOException {
        BusinessLunchOffer offer = objectMapper.readerFor(BusinessLunchOffer.class)
                .without(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .readValue(json);
        List<String> problems = problems(offer);
        if (!problems.isEmpty()) {
            throw new IllegalArgumentException("Business lunch offer is not usable: " + String.join("; ", problems));
        }
        return offer;
    }

    static List<String> problems(BusinessLunchOffer offer) {
        List<String> problems = new ArrayList<>();
        if (offer == null) {
            return List.of("the file is empty");
        }
        if (blank(offer.venueCode())) {
            problems.add("venueCode is missing");
        }
        if (offer.days() == null || offer.days().isEmpty()) {
            problems.add("days are missing");
        }
        if (offer.from() == null || offer.to() == null || !offer.from().isBefore(offer.to())) {
            problems.add("from must be earlier than to");
        }
        Set<String> courseCodes = new HashSet<>();
        Set<String> dishCodes = new HashSet<>();
        if (offer.courses().isEmpty()) {
            problems.add("courses are missing");
        }
        for (BusinessLunchOffer.Course course : offer.courses()) {
            if (blank(course.code()) || blank(course.title()) || !courseCodes.add(course.code())) {
                problems.add("course code and title must be present and unique: " + course.code());
            }
            if (course.dishes() == null || course.dishes().isEmpty()) {
                problems.add("course has no dishes: " + course.code());
                continue;
            }
            for (BusinessLunchOffer.Dish dish : course.dishes()) {
                if (blank(dish.code()) || blank(dish.title()) || !dishCodes.add(dish.code())) {
                    problems.add("dish code and title must be present and unique: " + dish.code());
                }
                if (dish.priceRub() != null && dish.priceRub() <= 0) {
                    problems.add("dish price must be positive or absent: " + dish.code());
                }
                // Without sets the guest pays per dish, so a dish without a price cannot be ordered.
                if (offer.aLaCarte() && dish.priceRub() == null) {
                    problems.add("a dish needs a price when there are no sets: " + dish.code());
                }
            }
        }
        Set<String> setCodes = new HashSet<>();
        for (BusinessLunchOffer.LunchSet set : offer.sets()) {
            if (blank(set.code()) || blank(set.title()) || !setCodes.add(set.code())) {
                problems.add("set code and title must be present and unique: " + set.code());
            }
            if (set.priceRub() != null && set.priceRub() <= 0) {
                problems.add("set price must be positive or absent: " + set.code());
            }
            if (set.slots() == null || set.slots().isEmpty()) {
                problems.add("set has no slots: " + set.code());
                continue;
            }
            for (List<String> slot : set.slots()) {
                if (slot == null || slot.isEmpty() || !courseCodes.containsAll(slot)) {
                    problems.add("set slot names an unknown course: " + set.code() + " " + slot);
                }
            }
        }
        return problems;
    }

    private static List<BusinessLunchOffer> load(ObjectMapper objectMapper) {
        List<BusinessLunchOffer> loaded = new ArrayList<>();
        try {
            for (Resource resource : new PathMatchingResourcePatternResolver().getResources(LOCATION)) {
                try (InputStream json = resource.getInputStream()) {
                    loaded.add(read(objectMapper, json));
                } catch (IOException | IllegalArgumentException e) {
                    log.error("Business lunch offer skipped: file={}, reason={}", resource.getFilename(), e.getMessage());
                }
            }
        } catch (IOException e) {
            log.error("Business lunch offers were not loaded: {}", e.getMessage());
        }
        return loaded;
    }

    private static String key(String venueCode) {
        return venueCode == null ? "" : venueCode.trim().toUpperCase(Locale.ROOT);
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
