package museon_online.astor_butler.domain.ledger;

import java.util.Map;

/**
 * A turn to write. {@code visitId} may be null while only the Concierge session is known; the session is linked to
 * the visit later. A payload with {@code hidden=true} marks a turn without the guest's consent: its text is not kept.
 */
public record VisitTurnDraft(Long visitId, String sessionId, TurnSide side, TurnChannel channel, TurnKind kind,
                             String text, Map<String, Object> payload, String goal) {

    public boolean hidden() {
        return payload != null && Boolean.TRUE.equals(payload.get("hidden"));
    }

    VisitTurnDraft withText(String newText) {
        return new VisitTurnDraft(visitId, sessionId, side, channel, kind, newText, payload, goal);
    }
}
