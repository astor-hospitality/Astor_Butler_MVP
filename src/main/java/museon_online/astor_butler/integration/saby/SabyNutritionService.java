package museon_online.astor_butler.integration.saby;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.restclient.RestTemplateBuilder;
import org.springframework.stereotype.Component;

import java.time.ZonedDateTime;
import java.util.Optional;

/**
 * Weight and nutrition of a lunch dish from its Saby tech card (spec 2026-10-13 §3.3), looked up by the exact Saby
 * name. Empty while Saby is off or not configured, and on any Saby failure: the guest then gets the usual answer.
 */
@Component
@Slf4j
public class SabyNutritionService {

    private final SabyReservationProperties properties;
    private final SabyMenuCatalog catalog;

    @Autowired
    public SabyNutritionService(SabyReservationProperties properties, RestTemplateBuilder restTemplateBuilder) {
        this(properties, new SabyApiClient(properties, restTemplateBuilder));
    }

    SabyNutritionService(SabyReservationProperties properties, SabyApiClient client) {
        this.properties = properties;
        this.catalog = new SabyMenuCatalog(properties, client);
    }

    public Optional<SabyNutrition> nutrition(String sabyName) {
        if (sabyName == null || sabyName.isBlank() || !properties.isEnabled() || !properties.missingConfiguration().isEmpty()) {
            return Optional.empty();
        }
        try {
            return catalog.priceListId(ZonedDateTime.now())
                    .flatMap(priceListId -> catalog.findDish(sabyName, priceListId))
                    .map(SabyMenuCatalog.Dish::nutrition);
        } catch (RuntimeException e) {
            log.warn("Saby nutrition lookup failed: {}", e.toString());
            return Optional.empty();
        }
    }
}
