package museon_online.astor_butler.integration.concierge;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * The Butler → Concierge webhook (negotiation plan, task 9), switched by {@code ASTOR_CONCIERGE_WEBHOOK_ENABLED} (off by
 * default). The body is signed with {@code ASTOR_WEBHOOK_SECRET}, the same secret the Concierge checks; a secret shorter
 * than {@link #MIN_SECRET} characters sends nothing.
 */
@Component
@ConfigurationProperties(prefix = "astor.concierge-webhook")
@Getter
@Setter
public class ConciergeWebhookProperties {

    public static final int MIN_SECRET = 16;

    private boolean enabled = false;
    private String url = "http://astor-concierge-concierge-1:8096";
    private String secret = "";
    private int timeoutMs = 5000;

    public boolean secretUsable() {
        return secret != null && secret.length() >= MIN_SECRET;
    }
}
