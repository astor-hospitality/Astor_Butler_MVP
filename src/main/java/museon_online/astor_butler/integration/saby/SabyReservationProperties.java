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
    /** Hand guests the payment page Saby issues for their order; needs acquiring set up in Presto. */
    private boolean paymentEnabled = false;
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
    /**
     * Codes of «the guest is at the table» (#151), {@code state} or {@code state/productState} separated by commas.
     * Default {@value #DEFAULT_SEATED_STATES}: the documented «принята в работу» together with the product state
     * «выполнение» — the order is open at the table; a plain 50 or 20 stays CONFIRMED. {@code off} (or any value
     * without a number) switches the mapping off. See docs/integrations/SABY_PRESTO_BOOKING_API.md 4.5.
     */
    private String seatedStates = DEFAULT_SEATED_STATES;
    /**
     * Send the table Butler holds to Saby on {@code order/create} ({@code booking.table}, {@code woTable=false}),
     * matched by name from {@code retail/hall/list}. Off — the table is left to the Saby administrator as before.
     */
    private boolean sendTable = true;

    public static final String DEFAULT_SEATED_STATES = "50/1002";

    public boolean configured() {
        return missingConfiguration().isEmpty();
    }

    /** {@link #seatedStates} parsed; {@link SabySeatedStates#NONE} while the variable is empty. */
    public SabySeatedStates seatedCodes() {
        return SabySeatedStates.parse(seatedStates);
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
