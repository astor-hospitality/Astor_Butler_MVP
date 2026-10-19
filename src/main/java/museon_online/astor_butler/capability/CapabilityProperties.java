package museon_online.astor_butler.capability;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * The Capability API, switched by {@code ASTOR_CAPABILITIES_ENABLED} (off by default). The venue name and address
 * repeat the seeded {@code venue_profiles} row of AERIS.
 */
@Component
@ConfigurationProperties(prefix = "astor.capabilities")
@Getter
@Setter
public class CapabilityProperties {

    private boolean enabled = false;
    private String venueCode = "AERIS";
    private String venueName = "AERIS";
    private String venueAddress = "Екатеринбург, ул. Мамина-Сибиряка, 58";
}
