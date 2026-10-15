package museon_online.astor_butler.capability;

/**
 * Who calls a tool and for which guest. {@code caller} is {@value #CONCIERGE} or {@value #BUTLER_AGENT}; the rest may
 * be null when not known yet. {@code agreement} is what the guest has agreed to this turn; only the Butler's agent
 * sets it, and without it a commitment that needs the guest's yes is refused.
 */
public record CapabilityContext(String caller, Long chatId, String sessionId, Long visitId, String idempotencyKey,
                                String guestName, String guestPhone, GuestAgreement agreement) {

    public static final String CONCIERGE = "CONCIERGE";
    public static final String BUTLER_AGENT = "BUTLER_AGENT";

    /** A call without the guest's agreement. */
    public CapabilityContext(String caller, Long chatId, String sessionId, Long visitId, String idempotencyKey,
                             String guestName, String guestPhone) {
        this(caller, chatId, sessionId, visitId, idempotencyKey, guestName, guestPhone, null);
    }

    public boolean butlerAgent() {
        return BUTLER_AGENT.equals(caller);
    }
}
