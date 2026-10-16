package museon_online.astor_butler.api.concierge;

import museon_online.astor_butler.domain.consent.ConsentVaultService;
import museon_online.astor_butler.domain.identity.IdentityService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * One consent for both bots of one operator (https://astor-ai.ru/policy.html covers Astor Concierge and the venue's
 * Butler bot). The guest bases stay separate: the Concierge and the Butler exchange only the consent fact and the
 * contact the guest agreed to give the restaurant, keyed by the Telegram user id both bots see. Internal, behind the
 * {@code /api/concierge/**} token (InternalApiGuardFilter).
 */
@RestController
@RequestMapping("/api/concierge/consents")
public class ConciergeConsentController {

    private static final Pattern PHONE = Pattern.compile("^\\+\\d{10,15}$");

    private final ConsentVaultService consents;
    private final IdentityService identity;

    public ConciergeConsentController(ConsentVaultService consents, IdentityService identity) {
        this.consents = consents;
        this.identity = identity;
    }

    public record ShareRequest(Long telegramUserId, String phone, String firstName) {
    }

    /** The guest consented in the Concierge and shared this contact: the Butler's bot will not ask again. */
    @PostMapping
    public ResponseEntity<Map<String, Object>> share(@RequestBody ShareRequest request) {
        if (request == null || request.telegramUserId() == null || request.telegramUserId() < 1
                || request.phone() == null || !PHONE.matcher(request.phone().trim()).matches()) {
            return ResponseEntity.badRequest().body(Map.of("error", "BAD_ARGUMENT"));
        }
        identity.rememberConciergeContact(request.telegramUserId(), request.firstName(), request.phone().trim());
        consents.grantPrivacyPolicyFromConcierge(request.telegramUserId());
        return ResponseEntity.ok(Map.of("granted", true));
    }

    /** Whether the guest already consented in the Butler's bot, and the contact given there, for the Concierge. */
    @GetMapping("/{telegramUserId}")
    public ResponseEntity<Map<String, Object>> read(@PathVariable("telegramUserId") long telegramUserId) {
        Map<String, Object> body = new LinkedHashMap<>();
        boolean granted = consents.hasGrantedPrivacyPolicy(telegramUserId);
        body.put("granted", granted);
        body.put("phone", granted ? identity.primaryPhone(telegramUserId).orElse(null) : null);
        body.put("firstName", granted ? identity.firstName(telegramUserId).orElse(null) : null);
        return ResponseEntity.ok(body);
    }

    /** The guest withdrew consent in the Concierge (/forget): it is withdrawn here too. */
    @PostMapping("/{telegramUserId}/withdraw")
    public ResponseEntity<Map<String, Object>> withdraw(@PathVariable("telegramUserId") long telegramUserId) {
        consents.revokePrivacyPolicy(telegramUserId);
        return ResponseEntity.ok(Map.of("granted", false));
    }
}
