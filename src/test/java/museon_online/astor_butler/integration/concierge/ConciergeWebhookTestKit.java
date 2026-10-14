package museon_online.astor_butler.integration.concierge;

import com.fasterxml.jackson.databind.ObjectMapper;
import museon_online.astor_butler.domain.journey.GuestVisitRepository;
import museon_online.astor_butler.domain.ledger.LedgerWriter;
import museon_online.astor_butler.domain.ledger.VisitTurnRepository;
import org.springframework.web.client.RestTemplate;

import java.util.concurrent.Executor;

/** A webhook for tests of other packages: its own executor and HTTP client, no pauses between attempts. */
public final class ConciergeWebhookTestKit {

    private ConciergeWebhookTestKit() {
    }

    public static ConciergeWebhook webhook(String secret, RestTemplate rest, LedgerWriter ledger, VisitTurnRepository turns,
                                           GuestVisitRepository visits, Executor executor) {
        ConciergeWebhookProperties properties = new ConciergeWebhookProperties();
        properties.setEnabled(true);
        properties.setUrl("http://concierge.test:8096");
        properties.setSecret(secret);
        return new ConciergeWebhook(properties, rest, new ObjectMapper(), ledger, turns, visits, executor, millis -> { });
    }
}
