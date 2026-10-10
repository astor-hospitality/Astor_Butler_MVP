package museon_online.astor_butler.max.client;

/**
 * One inline keyboard button as the MAX Bot API knows it. Only the kinds phase 1 renders are modelled.
 *
 * <ul>
 *   <li>{@link Kind#MESSAGE} sends its text into the chat as if the guest typed it: the MAX counterpart of a
 *       Telegram reply-keyboard button, so the FSM sees exactly the words it already understands;</li>
 *   <li>{@link Kind#CALLBACK} arrives as {@code message_callback} with {@code payload};</li>
 *   <li>{@link Kind#LINK} opens {@code url};</li>
 *   <li>{@link Kind#REQUEST_CONTACT} asks MAX to share the guest's phone (the consent step).</li>
 * </ul>
 */
public record MaxButton(Kind kind, String text, String payload, String url) {

    public enum Kind {
        MESSAGE("message"),
        CALLBACK("callback"),
        LINK("link"),
        REQUEST_CONTACT("request_contact");

        private final String apiType;

        Kind(String apiType) {
            this.apiType = apiType;
        }

        public String apiType() {
            return apiType;
        }
    }

    public static MaxButton message(String text) {
        return new MaxButton(Kind.MESSAGE, text, null, null);
    }

    public static MaxButton callback(String text, String payload) {
        return new MaxButton(Kind.CALLBACK, text, payload, null);
    }

    public static MaxButton link(String text, String url) {
        return new MaxButton(Kind.LINK, text, null, url);
    }

    public static MaxButton requestContact(String text) {
        return new MaxButton(Kind.REQUEST_CONTACT, text, null, null);
    }
}
