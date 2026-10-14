package museon_online.astor_butler.agent;

import java.util.List;
import java.util.Map;

/**
 * The Butler's answer to a negotiation turn. {@code butlerText} is the Butler's voice without the «AERIS · Батлер»
 * prefix (the Concierge adds it); {@code offer} the facts of the turn's last availability, booking or lunch order with
 * its {@code kind}; {@code facts} every tool result of the turn; {@code fallback} — the agent could not answer and
 * {@code butlerText} is null.
 */
public record NegotiationReply(String butlerText, Map<String, Object> offer, List<Map<String, Object>> facts, Long visitId,
                               boolean fallback) {

    public NegotiationReply {
        facts = facts == null ? List.of() : facts;
    }
}
