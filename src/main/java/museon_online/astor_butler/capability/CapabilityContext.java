package museon_online.astor_butler.capability;

import java.util.Map;

/**
 * Who calls a tool and for which guest. {@code caller} is {@value #CONCIERGE} or {@value #BUTLER_AGENT}; the rest may
 * be null when not known yet. {@code agreement} is what the guest has agreed to this turn; only the Butler's agent
 * sets it, and without it a commitment that needs the guest's yes is refused. {@code position} is the Concierge's
 * position of the guest this turn (date, time, partySize), when a negotiation sent one. {@code guestTelegramUserId} is
 * the Telegram user the Concierge talks to this turn, when its negotiation sent one: the Concierge may then act on a
 * booking it made for that same user through its booking form (see {@code ReservationAccess}).
 */
public record CapabilityContext(String caller, Long chatId, String sessionId, Long visitId, String idempotencyKey,
                                String guestName, String guestPhone, GuestAgreement agreement,
                                java.util.Map<String, Object> position, Long guestTelegramUserId) {

    public static final String CONCIERGE = "CONCIERGE";
    public static final String BUTLER_AGENT = "BUTLER_AGENT";

    /** A call without the guest's Telegram user id. */
    public CapabilityContext(String caller, Long chatId, String sessionId, Long visitId, String idempotencyKey,
                             String guestName, String guestPhone, GuestAgreement agreement,
                             java.util.Map<String, Object> position) {
        this(caller, chatId, sessionId, visitId, idempotencyKey, guestName, guestPhone, agreement, position, null);
    }

    /** A call without the Concierge's position of the guest (date, time, partySize). */
    public CapabilityContext(String caller, Long chatId, String sessionId, Long visitId, String idempotencyKey,
                             String guestName, String guestPhone, GuestAgreement agreement) {
        this(caller, chatId, sessionId, visitId, idempotencyKey, guestName, guestPhone, agreement, null, null);
    }

    /** A call without the guest's agreement. */
    public CapabilityContext(String caller, Long chatId, String sessionId, Long visitId, String idempotencyKey,
                             String guestName, String guestPhone) {
        this(caller, chatId, sessionId, visitId, idempotencyKey, guestName, guestPhone, null, null, null);
    }

    /** The day, time and guest count of the Concierge's position, whichever were sent. */
    public Map<String, Object> slots() {
        Map<String, Object> slots = new java.util.LinkedHashMap<>();
        if (position != null) {
            for (String key : java.util.List.of("date", "time", "partySize")) {
                Object value = position.get(key);
                if (value != null && !value.toString().isBlank()) {
                    slots.put(key, value);
                }
            }
        }
        return slots;
    }

    public boolean butlerAgent() {
        return BUTLER_AGENT.equals(caller);
    }
}
