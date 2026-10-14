package museon_online.astor_butler.integration.saby;

import lombok.Getter;
import lombok.Setter;
import museon_online.astor_butler.domain.journey.JourneyStage;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.Map;

/**
 * The mirror of the guest journey into a Saby CRM funnel (spec 2026-10-13 §3.4), switched by
 * {@code ASTOR_SABY_CRM_MIRROR_ENABLED}. The funnel «Визит гостя AERIS» is created in Saby first; its regulation id and the
 * transition ids per stage come from there ({@code SABY_CRM_PHASE_DIRECTIONS="REQUESTED=11,CONFIRMED=12,…"}).
 */
@Component
@ConfigurationProperties(prefix = "astor.integrations.saby.crm")
@Getter
@Setter
public class SabyCrmProperties {

    private boolean enabled = false;
    private Long regulationId;
    private String phaseDirections = "";
    private String responsibleId;

    public boolean ready() {
        return enabled && regulationId != null;
    }

    /** The transition into the stage, or null when the funnel has none for it. */
    public Long phaseDirection(JourneyStage stage) {
        return stage == null ? null : phases().get(stage);
    }

    Map<JourneyStage, Long> phases() {
        Map<JourneyStage, Long> phases = new EnumMap<>(JourneyStage.class);
        if (phaseDirections == null) {
            return phases;
        }
        for (String pair : phaseDirections.split(",")) {
            String[] parts = pair.trim().split("=");
            if (parts.length == 2) {
                try {
                    phases.put(JourneyStage.valueOf(parts[0].trim().toUpperCase()), Long.parseLong(parts[1].trim()));
                } catch (IllegalArgumentException ignored) {
                    // an unknown stage or a non-number is skipped, not fatal
                }
            }
        }
        return phases;
    }
}
