package museon_online.astor_butler.max.adapter;

/**
 * Callback payloads the MAX adapter itself puts on buttons. Phase 1 has one kind: {@code text:<words>}, a button
 * that stands for words the guest could have typed (where a {@code message} button does not fit). Any other payload
 * is acknowledged and dropped, as the Telegram router drops callbacks no handler claims.
 */
public final class MaxCallbackPayload {

    public static final String TEXT_PREFIX = "text:";

    private MaxCallbackPayload() {
    }

    public static String text(String words) {
        return TEXT_PREFIX + (words == null ? "" : words);
    }

    /** The guest's words behind a {@code text:} payload, or null when the payload is not one of ours. */
    public static String guestText(String payload) {
        if (payload == null || !payload.startsWith(TEXT_PREFIX)) {
            return null;
        }
        String words = payload.substring(TEXT_PREFIX.length()).strip();
        return words.isEmpty() ? null : words;
    }
}
