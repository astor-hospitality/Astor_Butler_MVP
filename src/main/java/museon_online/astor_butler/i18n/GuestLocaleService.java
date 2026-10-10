package museon_online.astor_butler.i18n;

import lombok.extern.slf4j.Slf4j;
import museon_online.astor_butler.service.message.IncomingMessage;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Decides the reply language for one inbound message, from any channel.
 *
 * <p>Signals: the guest's explicit choice and the conversation language from {@link GuestLanguagePreferenceStore},
 * the language of the message itself from {@link LanguageDetector}, the platform language from the message
 * ({@code IncomingMessage.languageCode()}, or {@code payload.viewport.locale} / {@code payload.locale} for the web
 * widget). A language resolved once may be stashed in the payload under {@link #PAYLOAD_LANGUAGE}, and every later
 * call for the same message reuses it instead of detecting again.
 *
 * <p>With the feature off nothing is read, detected or stored: the answer is the default language.
 */
@Slf4j
public class GuestLocaleService {

    public static final String PAYLOAD_LANGUAGE = "guestLanguage";
    public static final String PAYLOAD_LANGUAGE_SOURCE = "guestLanguageSource";

    private final I18nConfig config;
    private final GuestLocaleResolver resolver;
    private final LanguageDetector detector;
    private final GuestLanguagePreferenceStore store;

    public GuestLocaleService(
            I18nConfig config,
            LanguageDetector detector,
            GuestLanguagePreferenceStore store
    ) {
        this.config = config == null ? I18nConfig.defaults() : config;
        this.resolver = new GuestLocaleResolver(this.config);
        this.detector = detector == null ? (text, hints) -> Optional.empty() : detector;
        this.store = store == null ? GuestLanguagePreferenceStore.none() : store;
    }

    /** Off, Russian, no detector, no store: what scenarios get when they are built without Spring. */
    public static GuestLocaleService disabled() {
        return new GuestLocaleService(I18nConfig.defaults(), null, null);
    }

    public I18nConfig config() {
        return config;
    }

    public ResolvedLocale resolve(IncomingMessage incoming) {
        if (!config.enabled() || incoming == null) {
            return ResolvedLocale.defaultLanguage(config.defaultLanguage());
        }
        ResolvedLocale stashed = fromPayload(incoming.payload());
        if (stashed != null) {
            return stashed;
        }
        GuestLanguageKey key = GuestLanguageKey.of(incoming);
        String platform = platformLanguage(incoming);
        String explicit = key.known() ? quietly(() -> store.explicitLanguage(key)).orElse(null) : null;
        String conversation = key.known() ? quietly(() -> store.conversationLanguage(key)).orElse(null) : null;
        DetectedLanguage detected = quietly(() -> detector.detect(incoming.text(), hints(explicit, conversation, platform)))
                .orElse(null);

        ResolvedLocale resolved = resolver.resolve(new LocaleSignals(explicit, detected, conversation, platform));
        if (resolved.source() == LocaleSource.DETECTED && key.known() && !resolved.is(conversation)) {
            quietly(() -> {
                store.rememberConversationLanguage(key, resolved.language());
                return Optional.empty();
            });
        }
        return resolved;
    }

    /** Saves the guest's own choice; it wins over every other signal from the next message on. */
    public ResolvedLocale choose(IncomingMessage incoming, String language) {
        String normalized = LanguageTags.normalize(language);
        GuestLanguageKey key = GuestLanguageKey.of(incoming);
        if (normalized.isEmpty() || !key.known()) {
            return resolve(incoming);
        }
        store.saveExplicitLanguage(key, normalized);
        return new ResolvedLocale(normalized, LocaleSource.EXPLICIT);
    }

    /** A copy of the payload that carries the resolved language, for {@code IncomingMessage.withTextAndPayload}. */
    public static Map<String, Object> payloadWith(Map<String, Object> payload, ResolvedLocale locale) {
        Map<String, Object> result = new HashMap<>(payload == null ? Map.of() : payload);
        if (locale != null) {
            result.put(PAYLOAD_LANGUAGE, locale.language());
            result.put(PAYLOAD_LANGUAGE_SOURCE, locale.source().name());
        }
        return result;
    }

    /** Telegram {@code language_code}; for the web widget {@code payload.viewport.locale} or {@code payload.locale}. */
    static String platformLanguage(IncomingMessage incoming) {
        if (LanguageTags.isValid(incoming.languageCode())) {
            return LanguageTags.normalize(incoming.languageCode());
        }
        Map<String, Object> payload = incoming.payload();
        if (payload == null) {
            return "";
        }
        if (payload.get("viewport") instanceof Map<?, ?> viewport && viewport.get("locale") instanceof String locale
                && LanguageTags.isValid(locale)) {
            return LanguageTags.normalize(locale);
        }
        if (payload.get("locale") instanceof String locale && LanguageTags.isValid(locale)) {
            return LanguageTags.normalize(locale);
        }
        return "";
    }

    private static ResolvedLocale fromPayload(Map<String, Object> payload) {
        if (payload == null || !(payload.get(PAYLOAD_LANGUAGE) instanceof String language) || !LanguageTags.isValid(language)) {
            return null;
        }
        LocaleSource source = LocaleSource.DEFAULT;
        if (payload.get(PAYLOAD_LANGUAGE_SOURCE) instanceof String name) {
            try {
                source = LocaleSource.valueOf(name);
            } catch (IllegalArgumentException ignored) {
                // An unknown source name still carries a usable language.
            }
        }
        return new ResolvedLocale(language, source);
    }

    private static List<String> hints(String... values) {
        List<String> result = new ArrayList<>();
        for (String value : values) {
            if (LanguageTags.isValid(value)) {
                result.add(value);
            }
        }
        return result;
    }

    /** Language is never worth failing a reply over: a broken store or detector just means fewer signals. */
    private static <T> Optional<T> quietly(Supplier<Optional<T>> call) {
        try {
            Optional<T> result = call.get();
            return result == null ? Optional.empty() : result;
        } catch (RuntimeException e) {
            log.warn("Guest language signal skipped: {}", e.toString());
            return Optional.empty();
        }
    }
}
