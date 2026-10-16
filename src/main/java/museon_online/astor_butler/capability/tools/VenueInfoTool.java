package museon_online.astor_butler.capability.tools;

import museon_online.astor_butler.capability.CapabilityContext;
import museon_online.astor_butler.capability.CapabilityProperties;
import museon_online.astor_butler.capability.CapabilityResult;
import museon_online.astor_butler.capability.CapabilityTool;
import museon_online.astor_butler.domain.journey.talk.VenueFactsCatalog;
import museon_online.astor_butler.domain.journey.talk.VenueFactsCatalog.Topic;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/** {@code venue_info}: the venue's name, address and confirmed facts; a null fact means «нет данных». */
@Component
public class VenueInfoTool implements CapabilityTool {

    private final VenueFactsCatalog facts;
    private final CapabilityProperties properties;

    public VenueInfoTool(VenueFactsCatalog facts, CapabilityProperties properties) {
        this.facts = facts;
        this.properties = properties;
    }

    @Override
    public String name() {
        return "venue_info";
    }

    @Override
    public String description() {
        return "Сведения о ресторане: название, адрес и подтверждённые факты — парковка, дети, Wi-Fi, подбор вина. "
                + "null означает «нет данных»: не придумывай и не обещай уточнить у команды (передать ей вопрос нельзя) — "
                + "честно скажи, что таких сведений пока нет.";
    }

    @Override
    public Map<String, Object> parameters() {
        return Map.of("type", "object", "properties", Map.of(), "required", java.util.List.of());
    }

    @Override
    public boolean commitment() {
        return false;
    }

    @Override
    public CapabilityResult invoke(CapabilityContext ctx, Map<String, Object> args) {
        Map<String, Object> known = new LinkedHashMap<>();
        known.put("parking", facts.fact(Topic.PARKING).orElse(null));
        known.put("kids", facts.fact(Topic.KIDS).orElse(null));
        known.put("wifi", facts.fact(Topic.WIFI).orElse(null));
        known.put("winePairing", facts.fact(Topic.WINE).orElse(null));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("name", properties.getVenueName());
        result.put("address", properties.getVenueAddress());
        result.put("facts", known);
        return CapabilityResult.ok(result);
    }
}
