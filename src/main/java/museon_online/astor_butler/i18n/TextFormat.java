package museon_online.astor_butler.i18n;

/** How a text is marked up; Telegram messages with {@code parseMode=HTML} must be translated as HTML. */
public enum TextFormat {
    PLAIN,
    HTML;

    private static final java.util.regex.Pattern TAG = java.util.regex.Pattern.compile("</?[a-zA-Z][^>]*>");

    public static TextFormat of(String text) {
        return text != null && TAG.matcher(text).find() ? HTML : PLAIN;
    }
}
