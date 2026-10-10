package museon_online.astor_butler.i18n;

import museon_online.astor_butler.service.message.IncomingMessage;
import museon_online.astor_butler.service.message.MessageChannel;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class GuestLocaleServiceTest {

    private final RecordingStore store = new RecordingStore();
    private final GuestLocaleService service = new GuestLocaleService(
            I18nConfig.enabledWithoutTranslation(),
            new ScriptLanguageDetector(8),
            store
    );

    @Test
    void featureOffNeverDetectsOrStores() {
        GuestLocaleService off = new GuestLocaleService(I18nConfig.defaults(), (text, hints) -> {
            throw new AssertionError("detector must not be called while the feature is off");
        }, store);

        ResolvedLocale locale = off.resolve(telegram("Hello, I would like to book a table for two", "en"));

        assertThat(locale).isEqualTo(new ResolvedLocale("ru", LocaleSource.DEFAULT));
        assertThat(store.reads).isZero();
        assertThat(store.conversation).isEmpty();
    }

    @Test
    void englishMessageFromARussianInterfaceIsAnsweredInEnglishAndRemembered() {
        ResolvedLocale locale = service.resolve(telegram("Hello, I would like to book a table for two", "ru"));

        assertThat(locale).isEqualTo(new ResolvedLocale("en", LocaleSource.DETECTED));
        assertThat(store.conversation).containsEntry(77L, "en");
    }

    @Test
    void shortReplyKeepsTheConversationLanguage() {
        store.conversation.put(77L, "en");

        ResolvedLocale locale = service.resolve(telegram("19:00", "ru"));

        assertThat(locale).isEqualTo(new ResolvedLocale("en", LocaleSource.CONVERSATION));
    }

    @Test
    void shortFirstMessageFallsBackToTheTelegramLanguage() {
        ResolvedLocale locale = service.resolve(telegram("hi", "de"));

        assertThat(locale).isEqualTo(new ResolvedLocale("de", LocaleSource.PLATFORM));
        assertThat(store.conversation).isEmpty();
    }

    @Test
    void explicitChoiceIsSavedAndWins() {
        service.choose(telegram("/language fr", "en"), "fr-FR");

        ResolvedLocale locale = service.resolve(telegram("Hello, I would like to book a table for two", "en"));

        assertThat(locale).isEqualTo(new ResolvedLocale("fr", LocaleSource.EXPLICIT));
    }

    @Test
    void webWidgetLocaleComesFromTheViewport() {
        IncomingMessage web = new IncomingMessage(
                MessageChannel.WEB, "session-1", 900L, null, null, null, "ok", null, null, null, null, null,
                false, "corr", Instant.now(), Map.of("viewport", Map.of("locale", "en-GB"))
        );

        assertThat(service.resolve(web)).isEqualTo(new ResolvedLocale("en", LocaleSource.PLATFORM));
    }

    @Test
    void languageStashedInThePayloadIsReusedWithoutDetection() {
        GuestLocaleService strict = new GuestLocaleService(I18nConfig.enabledWithoutTranslation(), (text, hints) -> {
            throw new AssertionError("detector must not run twice for one message");
        }, store);
        IncomingMessage incoming = telegram("Здравствуйте, хочу забронировать стол", "ru");
        IncomingMessage stashed = incoming.withTextAndPayload(
                incoming.text(),
                GuestLocaleService.payloadWith(incoming.payload(), new ResolvedLocale("ja", LocaleSource.EXPLICIT))
        );

        assertThat(strict.resolve(stashed)).isEqualTo(new ResolvedLocale("ja", LocaleSource.EXPLICIT));
    }

    @Test
    void brokenStoreOrDetectorOnlyMeansFewerSignals() {
        GuestLocaleService fragile = new GuestLocaleService(
                I18nConfig.enabledWithoutTranslation(),
                (text, hints) -> {
                    throw new IllegalStateException("detector down");
                },
                new RecordingStore() {
                    @Override
                    public Optional<String> explicitLanguage(GuestLanguageKey key) {
                        throw new IllegalStateException("database down");
                    }
                }
        );

        assertThat(fragile.resolve(telegram("Hello, I would like to book a table for two", "en")))
                .isEqualTo(new ResolvedLocale("en", LocaleSource.PLATFORM));
    }

    private static IncomingMessage telegram(String text, String languageCode) {
        return IncomingMessage.telegram(77L, 77L, 1, 1, text, null, "Guest", null, "guest", languageCode, false, "c1");
    }

    private static class RecordingStore implements GuestLanguagePreferenceStore {
        final Map<Long, String> explicit = new HashMap<>();
        final Map<Long, String> conversation = new HashMap<>();
        int reads;

        @Override
        public Optional<String> explicitLanguage(GuestLanguageKey key) {
            reads++;
            return Optional.ofNullable(explicit.get(key.chatId()));
        }

        @Override
        public Optional<String> conversationLanguage(GuestLanguageKey key) {
            reads++;
            return Optional.ofNullable(conversation.get(key.chatId()));
        }

        @Override
        public void rememberConversationLanguage(GuestLanguageKey key, String language) {
            conversation.put(key.chatId(), language);
        }

        @Override
        public void saveExplicitLanguage(GuestLanguageKey key, String language) {
            explicit.put(key.chatId(), language);
        }
    }
}
