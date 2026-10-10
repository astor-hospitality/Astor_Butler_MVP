package museon_online.astor_butler.i18n;

/**
 * Picks the reply language from the available signals. Pure and stateless: no I/O, so the order of signals can be
 * tested on its own and reused by every channel.
 *
 * <p>Order: explicit choice, confidently detected language of the current message, language detected earlier in
 * the conversation, platform language, default. With the feature off the answer is always the default language.
 */
public final class GuestLocaleResolver {

    private final I18nConfig config;

    public GuestLocaleResolver(I18nConfig config) {
        this.config = config == null ? I18nConfig.defaults() : config;
    }

    public ResolvedLocale resolve(LocaleSignals signals) {
        if (!config.enabled() || signals == null) {
            return ResolvedLocale.defaultLanguage(config.defaultLanguage());
        }
        if (LanguageTags.isValid(signals.explicitLanguage())) {
            return new ResolvedLocale(signals.explicitLanguage(), LocaleSource.EXPLICIT);
        }
        DetectedLanguage detected = signals.detected();
        if (detected != null
                && !detected.language().isEmpty()
                && detected.confidence() >= config.detectionMinConfidence()) {
            return new ResolvedLocale(detected.language(), LocaleSource.DETECTED);
        }
        if (LanguageTags.isValid(signals.conversationLanguage())) {
            return new ResolvedLocale(signals.conversationLanguage(), LocaleSource.CONVERSATION);
        }
        if (LanguageTags.isValid(signals.platformLanguage())) {
            return new ResolvedLocale(signals.platformLanguage(), LocaleSource.PLATFORM);
        }
        return ResolvedLocale.defaultLanguage(config.defaultLanguage());
    }
}
