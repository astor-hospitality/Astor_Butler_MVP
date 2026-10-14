package museon_online.astor_butler.agent;

import java.util.Map;

/**
 * One turn of the Concierge's talk with the venue. {@code guestText} is the guest's own words, {@code conciergeMessage}
 * what the Concierge says to the venue, {@code conciergePosition} the guest's goals (date, time, partySize, budget,
 * constraints). {@code visitId} is null until the session has a visit.
 */
public record NegotiationTurn(String sessionId, Long visitId, String guestText, Map<String, Object> conciergePosition,
                              String guestName, String guestPhone, String conciergeMessage) {
}
