package museon_online.astor_butler.i18n;

import lombok.extern.slf4j.Slf4j;
import museon_online.astor_butler.service.message.IncomingMessage;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Guest-facing texts by key, in the guest's language.
 *
 * <p>Order for a key and a language: the human-edited catalog of that language; a machine translation of the source
 * catalog (only when {@code astor.i18n.machine-translation.enabled} and a provider is available; cached; the
 * translation must keep every {@code {placeholder}}); the fallback language ({@code en}, or {@code ru} for the
 * languages listed in {@code astor.i18n.default-fallback-languages}); the default language; the key itself.
 *
 * <p>With {@code astor.i18n.enabled=false} every text comes from the default (Russian) catalog and no metadata is
 * added, so scenarios that use this class behave exactly as they did with string literals.
 */
@Slf4j
public class GuestTexts {

    public static final String METADATA_GUEST_LANGUAGE = "guestLanguage";
    public static final String METADATA_REPLY_LANGUAGE = "replyLanguage";
    public static final String METADATA_TEXT_ORIGIN = "replyTextOrigin";

    private final I18nConfig config;
    private final MessageCatalog catalog;
    private final MachineTranslator translator;
    private final TranslationCache cache;
    private final GuestLocaleService locales;

    public GuestTexts(
            I18nConfig config,
            MessageCatalog catalog,
            MachineTranslator translator,
            TranslationCache cache,
            GuestLocaleService locales
    ) {
        this.config = config == null ? I18nConfig.defaults() : config;
        this.catalog = catalog == null ? MessageCatalog.of(Map.of()) : catalog;
        this.translator = translator == null ? new NoOpMachineTranslator() : translator;
        this.cache = cache == null ? new InMemoryTranslationCache(100) : cache;
        this.locales = locales == null ? new GuestLocaleService(this.config, null, null) : locales;
    }

    /** Off, the Russian guest catalog from the classpath, no translator: for scenarios built without Spring. */
    public static GuestTexts russianOnly() {
        I18nConfig config = I18nConfig.defaults();
        return new GuestTexts(
                config,
                MessageCatalog.load(MessageCatalog.GUEST_BUNDLE, List.of(config.defaultLanguage())),
                new NoOpMachineTranslator(),
                new InMemoryTranslationCache(100),
                GuestLocaleService.disabled()
        );
    }

    public boolean enabled() {
        return config.enabled();
    }

    public String defaultLanguage() {
        return config.defaultLanguage();
    }

    public ResolvedLocale locale(IncomingMessage incoming) {
        return locales.resolve(incoming);
    }

    public LocalizedText forGuest(IncomingMessage incoming, String key) {
        return text(locale(incoming), key, Map.of());
    }

    public LocalizedText forGuest(IncomingMessage incoming, String key, Map<String, ?> args) {
        return text(locale(incoming), key, args);
    }

    public LocalizedText text(ResolvedLocale locale, String key) {
        return text(locale, key, Map.of());
    }

    public LocalizedText text(ResolvedLocale locale, String key, Map<String, ?> args) {
        String defaultLanguage = config.defaultLanguage();
        String wanted = !config.enabled() || locale == null ? defaultLanguage : locale.language();

        Optional<String> own = catalog.template(wanted, key);
        if (own.isPresent()) {
            return rendered(own.get(), args, wanted, wanted, TextOrigin.CATALOG);
        }
        if (config.enabled()) {
            Optional<String> machine = machineTranslated(wanted, key);
            if (machine.isPresent()) {
                return rendered(machine.get(), args, wanted, wanted, TextOrigin.MACHINE_TRANSLATION);
            }
            String fallback = config.fallbackFor(wanted);
            Optional<String> fallbackText = catalog.template(fallback, key);
            if (fallbackText.isPresent()) {
                return rendered(fallbackText.get(), args, fallback, wanted, TextOrigin.FALLBACK);
            }
        }
        Optional<String> defaultText = catalog.template(defaultLanguage, key);
        if (defaultText.isPresent()) {
            return rendered(defaultText.get(), args, defaultLanguage, wanted, TextOrigin.FALLBACK);
        }
        log.warn("Guest text {} is missing in every catalog", key);
        return new LocalizedText(key, defaultLanguage, wanted, TextOrigin.MISSING);
    }

    /**
     * True when the guest sent back a button label of this key. With the feature off only the default catalog is
     * compared, so a label in another language does not change what a scenario recognises.
     */
    public boolean matchesLabel(String key, String input) {
        String normalized = MessageCatalog.normalizeLabel(input);
        if (normalized.isEmpty()) {
            return false;
        }
        Collection<String> labels = config.enabled()
                ? catalog.allTemplates(key)
                : catalog.template(config.defaultLanguage(), key).stream().toList();
        return labels.stream().map(MessageCatalog::normalizeLabel).anyMatch(normalized::equals);
    }

    /** Like {@link #matchesLabel(String, String)}, and also the label as this guest saw it (machine translated). */
    public boolean matchesLabel(String key, String input, ResolvedLocale locale) {
        if (matchesLabel(key, input)) {
            return true;
        }
        if (!config.enabled() || locale == null) {
            return false;
        }
        String normalized = MessageCatalog.normalizeLabel(input);
        return !normalized.isEmpty() && normalized.equals(MessageCatalog.normalizeLabel(text(locale, key).text()));
    }

    /** Outgoing metadata about the language of a reply; empty with the feature off. */
    public Map<String, Object> metadata(LocalizedText text) {
        if (!config.enabled() || text == null) {
            return Map.of();
        }
        return Map.of(
                METADATA_GUEST_LANGUAGE, text.requestedLanguage(),
                METADATA_REPLY_LANGUAGE, text.language(),
                METADATA_TEXT_ORIGIN, text.origin().name()
        );
    }

    private Optional<String> machineTranslated(String target, String key) {
        if (!config.machineTranslationEnabled() || !translator.available() || config.isCatalogLanguage(target)) {
            return Optional.empty();
        }
        String sourceLanguage = config.machineTranslationSource();
        Optional<String> source = catalog.template(sourceLanguage, key);
        if (source.isEmpty()) {
            return Optional.empty();
        }
        TextFormat format = TextFormat.of(source.get());
        TranslationCache.Key cacheKey = TranslationCache.Key.of(source.get(), sourceLanguage, target, format);
        Optional<String> cached = cache.get(cacheKey);
        if (cached.isPresent()) {
            return cached;
        }
        try {
            Optional<TranslationResult> result = translator.translate(
                    new TranslationRequest(List.of(source.get()), sourceLanguage, target, format));
            Optional<String> translated = result
                    .flatMap(value -> value.texts().stream().findFirst())
                    .filter(value -> !value.isBlank());
            if (translated.isEmpty()) {
                return Optional.empty();
            }
            if (!MessageCatalog.placeholders(translated.get()).equals(MessageCatalog.placeholders(source.get()))) {
                log.warn("Machine translation of {} into {} lost placeholders; using the fallback language", key, target);
                return Optional.empty();
            }
            cache.put(cacheKey, translated.get(), result.get().provider());
            return translated;
        } catch (RuntimeException e) {
            log.warn("Machine translation of {} into {} failed via {}: {}", key, target, translator.provider(), e.toString());
            return Optional.empty();
        }
    }

    private static LocalizedText rendered(
            String template,
            Map<String, ?> args,
            String language,
            String requestedLanguage,
            TextOrigin origin
    ) {
        return new LocalizedText(MessageCatalog.format(template, args), language, requestedLanguage, origin);
    }
}
