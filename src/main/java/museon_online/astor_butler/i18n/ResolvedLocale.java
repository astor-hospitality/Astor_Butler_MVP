package museon_online.astor_butler.i18n;

/**
 * The language a guest is answered in for one message, and the signal it came from.
 *
 * @param language normalized primary subtag, see {@link LanguageTags#normalize(String)}
 * @param source   which signal won
 */
public record ResolvedLocale(String language, LocaleSource source) {

    public ResolvedLocale {
        String normalized = LanguageTags.normalize(language);
        language = normalized.isEmpty() ? "ru" : normalized;
        source = source == null ? LocaleSource.DEFAULT : source;
    }

    public static ResolvedLocale defaultLanguage(String language) {
        return new ResolvedLocale(language, LocaleSource.DEFAULT);
    }

    public boolean is(String other) {
        return language.equals(LanguageTags.normalize(other));
    }
}
