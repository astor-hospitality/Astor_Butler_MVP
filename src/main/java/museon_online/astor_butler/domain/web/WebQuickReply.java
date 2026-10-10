package museon_online.astor_butler.domain.web;

/**
 * Web equivalent of a Telegram keyboard button.
 *
 * <ul>
 *   <li>{@code text} — the widget sends {@code value} as the guest's next message (reply keyboard label);</li>
 *   <li>{@code action} — the widget sends {@code value} as {@code payload.action} (inline callback data);</li>
 *   <li>{@code contact} — the widget switches to the phone step and sends the number as {@code payload.contactPhone};</li>
 *   <li>{@code url} — the widget opens {@code url} in a new tab.</li>
 * </ul>
 */
public record WebQuickReply(String id, String kind, String text, String value, String url) {

    public static WebQuickReply text(String label) {
        return new WebQuickReply(idFor("text", label), "text", label, label, null);
    }

    public static WebQuickReply action(String label, String action) {
        return new WebQuickReply(idFor("action", action), "action", label, action, null);
    }

    public static WebQuickReply contact(String label) {
        return new WebQuickReply("contact", "contact", label, "", null);
    }

    public static WebQuickReply url(String label, String url) {
        return new WebQuickReply(idFor("url", url), "url", label, "", url);
    }

    private static String idFor(String kind, String seed) {
        return kind + "-" + Integer.toHexString((seed == null ? "" : seed).hashCode());
    }
}
