package museon_online.astor_butler.i18n;

/**
 * Everything known about a guest's language for one message. Any field may be null or blank.
 *
 * @param explicitLanguage     what the guest chose themselves
 * @param detected             what the current message looks like
 * @param conversationLanguage the language detected earlier in this conversation
 * @param platformLanguage     Telegram {@code language_code}, web {@code viewport.locale}, MAX locale
 */
public record LocaleSignals(
        String explicitLanguage,
        DetectedLanguage detected,
        String conversationLanguage,
        String platformLanguage
) {

    public static LocaleSignals platformOnly(String platformLanguage) {
        return new LocaleSignals(null, null, null, platformLanguage);
    }
}
