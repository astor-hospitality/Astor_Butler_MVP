package museon_online.astor_butler.i18n;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Everything the guest-language layer reads from the environment, in one immutable record so services and tests
 * see the same values.
 *
 * @param enabled                     master switch; false keeps every guest on {@link #defaultLanguage()}
 * @param defaultLanguage             the language of the FSM and of staff, {@code ru}
 * @param catalogLanguages            languages with a human-edited catalog file; never machine translated
 * @param fallbackLanguage            used when the guest language has no catalog and no translation, {@code en}
 * @param defaultFallbackLanguages    guest languages whose fallback is {@link #defaultLanguage()} rather than
 *                                    {@link #fallbackLanguage()}; empty until the owner decides (see the plan)
 * @param detectionMinConfidence      a detected language below this confidence does not override other signals
 * @param detectionMinLetters         Latin/Cyrillic messages shorter than this are not used for detection
 * @param machineTranslationEnabled   allows paid translation of catalog texts for languages without a catalog
 * @param machineTranslationProvider  {@code none} today; {@code yandex} once the adapter exists
 * @param machineTranslationSource    the catalog language that is sent for translation
 * @param translationCacheMaxEntries  in-memory cache size for machine translations
 */
public record I18nConfig(
        boolean enabled,
        String defaultLanguage,
        Set<String> catalogLanguages,
        String fallbackLanguage,
        Set<String> defaultFallbackLanguages,
        double detectionMinConfidence,
        int detectionMinLetters,
        boolean machineTranslationEnabled,
        String machineTranslationProvider,
        String machineTranslationSource,
        int translationCacheMaxEntries
) {

    public I18nConfig {
        defaultLanguage = orDefault(LanguageTags.normalize(defaultLanguage), "ru");
        catalogLanguages = normalizedSet(catalogLanguages);
        if (catalogLanguages.isEmpty()) {
            catalogLanguages = Set.of(defaultLanguage);
        }
        fallbackLanguage = orDefault(LanguageTags.normalize(fallbackLanguage), defaultLanguage);
        defaultFallbackLanguages = normalizedSet(defaultFallbackLanguages);
        detectionMinConfidence = Math.min(1.0, Math.max(0.0, detectionMinConfidence));
        detectionMinLetters = Math.max(1, detectionMinLetters);
        machineTranslationProvider = machineTranslationProvider == null || machineTranslationProvider.isBlank()
                ? "none"
                : machineTranslationProvider.trim().toLowerCase(java.util.Locale.ROOT);
        machineTranslationSource = orDefault(LanguageTags.normalize(machineTranslationSource), defaultLanguage);
        translationCacheMaxEntries = Math.max(100, translationCacheMaxEntries);
    }

    /** What the application runs with when nothing is configured: off, Russian, no paid calls. */
    public static I18nConfig defaults() {
        return new I18nConfig(false, "ru", Set.of("ru", "en"), "en", Set.of(), 0.7, 8, false, "none", "ru", 5000);
    }

    /** Same as {@link #defaults()} but switched on; machine translation stays off. Handy in tests. */
    public static I18nConfig enabledWithoutTranslation() {
        return new I18nConfig(true, "ru", Set.of("ru", "en"), "en", Set.of(), 0.7, 8, false, "none", "ru", 5000);
    }

    public boolean isCatalogLanguage(String language) {
        return catalogLanguages.contains(LanguageTags.normalize(language));
    }

    /** The language a guest gets when their own language has neither a catalog nor a translation. */
    public String fallbackFor(String guestLanguage) {
        String language = LanguageTags.normalize(guestLanguage);
        return defaultFallbackLanguages.contains(language) ? defaultLanguage : fallbackLanguage;
    }

    private static Set<String> normalizedSet(Set<String> values) {
        if (values == null || values.isEmpty()) {
            return Set.of();
        }
        Set<String> result = new LinkedHashSet<>();
        for (String value : values) {
            String normalized = LanguageTags.normalize(value);
            if (!normalized.isEmpty()) {
                result.add(normalized);
            }
        }
        return Set.copyOf(result);
    }

    private static String orDefault(String value, String fallback) {
        return value == null || value.isEmpty() ? fallback : value;
    }

    static Set<String> csv(String value) {
        if (value == null || value.isBlank()) {
            return Set.of();
        }
        List<String> parts = Arrays.stream(value.split(","))
                .map(String::trim)
                .filter(part -> !part.isEmpty())
                .toList();
        return new LinkedHashSet<>(parts);
    }

    @Slf4j
    @Configuration
    public static class Binding {

        @Bean
        public I18nConfig i18nConfig(
                @Value("${astor.i18n.enabled:false}") boolean enabled,
                @Value("${astor.i18n.default-language:ru}") String defaultLanguage,
                @Value("${astor.i18n.catalog-languages:ru,en}") String catalogLanguages,
                @Value("${astor.i18n.fallback-language:en}") String fallbackLanguage,
                @Value("${astor.i18n.default-fallback-languages:}") String defaultFallbackLanguages,
                @Value("${astor.i18n.detection.min-confidence:0.7}") double detectionMinConfidence,
                @Value("${astor.i18n.detection.min-letters:8}") int detectionMinLetters,
                @Value("${astor.i18n.machine-translation.enabled:false}") boolean machineTranslationEnabled,
                @Value("${astor.i18n.machine-translation.provider:none}") String machineTranslationProvider,
                @Value("${astor.i18n.machine-translation.source-language:ru}") String machineTranslationSource,
                @Value("${astor.i18n.machine-translation.cache-max-entries:5000}") int translationCacheMaxEntries
        ) {
            I18nConfig config = new I18nConfig(
                    enabled,
                    defaultLanguage,
                    csv(catalogLanguages),
                    fallbackLanguage,
                    csv(defaultFallbackLanguages),
                    detectionMinConfidence,
                    detectionMinLetters,
                    machineTranslationEnabled,
                    machineTranslationProvider,
                    machineTranslationSource,
                    translationCacheMaxEntries
            );
            log.info("Guest languages enabled={} default={} catalogs={} machineTranslation={} provider={}",
                    config.enabled(), config.defaultLanguage(), config.catalogLanguages(),
                    config.machineTranslationEnabled(), config.machineTranslationProvider());
            return config;
        }
    }
}
