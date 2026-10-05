package museon_online.astor_butler.api.glasses.tasks;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import java.util.Map;

@RestController
public class StaffLoginConfigurationController {
    private final boolean enabled;
    private final String issuer;
    public StaffLoginConfigurationController(@Value("${astor.staff.enabled:false}") boolean enabled,
            @Value("${astor.staff.issuer-uri:}") String issuer) {
        this.enabled = enabled; this.issuer = issuer.replaceAll("/+$", "");
    }
    @GetMapping("/api/staff/login-config")
    public Map<String, Object> config() {
        return enabled ? Map.of("enabled", true, "issuer", issuer, "clientId", "astor-staff-ui")
                : Map.of("enabled", false);
    }
}
