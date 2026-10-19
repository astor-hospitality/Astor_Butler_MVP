package museon_online.astor_butler.agent;

import java.util.Map;

/**
 * One turn of the Concierge's talk with the venue. {@code guestText} is the guest's own words, {@code conciergeMessage}
 * what the Concierge says to the venue, {@code conciergePosition} the guest's goals (date, time, partySize, budget,
 * constraints). {@code visitId} is null until the session has a visit. {@code turnId}, when given, names the turn: a
 * retry with the same id replays its commitments instead of repeating them. {@code guestConsent} — only {@code true}
 * lets the Concierge's message keep its text in the ledger. {@code guestTelegramUserId}, when given, is the Telegram
 * user the Concierge talks to this turn: a booking the Concierge made for that same user through its booking form
 * (POST /api/bookings/table-reservations, never linked to a session) is then the Concierge's to change or cancel.
 */
public record NegotiationTurn(String sessionId, Long visitId, String guestText, Map<String, Object> conciergePosition,
                              String guestName, String guestPhone, String conciergeMessage, String turnId,
                              Boolean guestConsent, Long guestTelegramUserId) {

    /** A turn without the guest's Telegram user id: only the session's own bookings are the Concierge's. */
    public NegotiationTurn(String sessionId, Long visitId, String guestText, Map<String, Object> conciergePosition,
                           String guestName, String guestPhone, String conciergeMessage, String turnId,
                           Boolean guestConsent) {
        this(sessionId, visitId, guestText, conciergePosition, guestName, guestPhone, conciergeMessage, turnId,
                guestConsent, null);
    }
}
