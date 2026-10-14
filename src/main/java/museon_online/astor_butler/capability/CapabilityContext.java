package museon_online.astor_butler.capability;

/**
 * Who calls a tool and for which guest. {@code caller} is {@value #CONCIERGE} or {@value #BUTLER_AGENT}; the rest may
 * be null when not known yet.
 */
public record CapabilityContext(String caller, Long chatId, String sessionId, Long visitId, String idempotencyKey,
                                String guestName, String guestPhone) {

    public static final String CONCIERGE = "CONCIERGE";
    public static final String BUTLER_AGENT = "BUTLER_AGENT";

    public boolean butlerAgent() {
        return BUTLER_AGENT.equals(caller);
    }
}
