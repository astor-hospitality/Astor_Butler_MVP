package museon_online.astor_butler.i18n;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wiring of the guest-language layer. Every bean is safe with the defaults: the feature is off, the translator is
 * the no-op, the preference store remembers nothing, and the catalogs are read from the classpath.
 *
 * <p>Phase 2 adds {@code provider=yandex} to {@link #machineTranslator(I18nConfig)}; phase 1 replaces
 * {@link #guestLanguagePreferenceStore()} and {@link #translationCache(I18nConfig)} with JDBC implementations.
 */
@Slf4j
@Configuration
public class I18nConfiguration {

    @Bean
    public LanguageDetector languageDetector(I18nConfig config) {
        return new ScriptLanguageDetector(config.detectionMinLetters());
    }

    @Bean
    public MachineTranslator machineTranslator(I18nConfig config) {
        String provider = config.machineTranslationProvider();
        if (!NoOpMachineTranslator.PROVIDER.equals(provider)) {
            log.warn("Machine translation provider '{}' is not implemented yet; guest texts use catalogs only", provider);
        }
        return new NoOpMachineTranslator();
    }

    @Bean
    public TranslationCache translationCache(I18nConfig config) {
        return new InMemoryTranslationCache(config.translationCacheMaxEntries());
    }

    @Bean
    public GuestLanguagePreferenceStore guestLanguagePreferenceStore() {
        return GuestLanguagePreferenceStore.none();
    }

    @Bean
    public MessageCatalog guestMessageCatalog(I18nConfig config) {
        java.util.Set<String> languages = new java.util.LinkedHashSet<>(config.catalogLanguages());
        languages.add(config.defaultLanguage());
        return MessageCatalog.load(MessageCatalog.GUEST_BUNDLE, languages);
    }

    @Bean
    public GuestLocaleService guestLocaleService(
            I18nConfig config,
            LanguageDetector languageDetector,
            GuestLanguagePreferenceStore guestLanguagePreferenceStore
    ) {
        return new GuestLocaleService(config, languageDetector, guestLanguagePreferenceStore);
    }

    @Bean
    public GuestTexts guestTexts(
            I18nConfig config,
            MessageCatalog guestMessageCatalog,
            MachineTranslator machineTranslator,
            TranslationCache translationCache,
            GuestLocaleService guestLocaleService
    ) {
        return new GuestTexts(config, guestMessageCatalog, machineTranslator, translationCache, guestLocaleService);
    }
}
