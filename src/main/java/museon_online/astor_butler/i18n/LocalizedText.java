package museon_online.astor_butler.i18n;

/**
 * A guest text ready to send.
 *
 * @param text              the text with placeholders filled in
 * @param language          the language the text is actually in
 * @param requestedLanguage the guest's language; differs from {@code language} after a fallback
 * @param origin            catalog, machine translation, fallback or missing
 */
public record LocalizedText(String text, String language, String requestedLanguage, TextOrigin origin) {
}
