package museon_online.astor_butler.integration.saby;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Saby Presto service authorization and booking settings.
 * Contract: docs/integrations/SABY_PRESTO_BOOKING_API.md. Secrets come only from the server environment.
 */
@Component
@ConfigurationProperties(prefix = "astor.integrations.saby")
@Getter
@Setter
public class SabyReservationProperties {

    private boolean enabled = false;
    private boolean writeEnabled = false;
    private String baseUrl = "https://api.sbis.ru";
    private String authUrl = "https://online.sbis.ru/oauth/service/";
    private String appClientId = "";
    private String appSecret = "";
    private String secretKey = "";
    private String pointId = "";
    private String hallId = "";
    /** Optional: the Presto price list that holds the lunch dishes; found through the API when empty. */
    private String priceListId = "";
    private String venueCode = "AERIS";
    private String zoneId = "Asia/Yekaterinburg";
    private long timeoutMs = 3000;
    private int maxRetries = 1;

    public boolean configured() {
        return missingConfiguration().isEmpty();
    }

    public List<String> missingConfiguration() {
        List<String> missing = new ArrayList<>();
        require(missing, "SABY_API_BASE_URL", baseUrl);
        require(missing, "SABY_AUTH_URL", authUrl);
        require(missing, "SABY_APP_CLIENT_ID", appClientId);
        require(missing, "SABY_APP_SECRET", appSecret);
        require(missing, "SABY_SECRET_KEY", secretKey);
        require(missing, "SABY_POINT_ID", pointId);
        return List.copyOf(missing);
    }

    private void require(List<String> missing, String envName, String value) {
        if (isBlank(value)) {
            missing.add(envName);
        }
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
