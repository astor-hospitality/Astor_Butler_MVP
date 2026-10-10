package museon_online.astor_butler.i18n;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class GuestTextsTest {

    private final MessageCatalog catalog = MessageCatalog.of(Map.of(
            "ru", Map.of(
                    "booking.confirmed", "Бронь #{orderId} подтверждена.",
                    "menu.button", "Меню кухни",
                    "only.ru", "Только по-русски"
            ),
            "en", Map.of(
                    "booking.confirmed", "Booking #{orderId} is confirmed.",
                    "menu.button", "Kitchen menu"
            )
    ));

    @Test
    void featureOffAlwaysUsesTheRussianCatalogAndAddsNoMetadata() {
        GuestTexts texts = new GuestTexts(I18nConfig.defaults(), catalog, null, null, null);

        LocalizedText text = texts.text(new ResolvedLocale("en", LocaleSource.DETECTED), "booking.confirmed", Map.of("orderId", 7));

        assertThat(text).isEqualTo(new LocalizedText("Бронь #7 подтверждена.", "ru", "ru", TextOrigin.CATALOG));
        assertThat(texts.metadata(text)).isEmpty();
    }

    @Test
    void catalogLanguageIsServedAsIs() {
        GuestTexts texts = new GuestTexts(I18nConfig.enabledWithoutTranslation(), catalog, null, null, null);

        LocalizedText text = texts.text(locale("en"), "booking.confirmed", Map.of("orderId", 7));

        assertThat(text).isEqualTo(new LocalizedText("Booking #7 is confirmed.", "en", "en", TextOrigin.CATALOG));
        assertThat(texts.metadata(text)).containsEntry("guestLanguage", "en").containsEntry("replyTextOrigin", "CATALOG");
    }

    @Test
    void languageWithoutCatalogFallsBackToEnglishWhileTranslationIsOff() {
        GuestTexts texts = new GuestTexts(I18nConfig.enabledWithoutTranslation(), catalog, new FakeTranslator(), null, null);

        LocalizedText text = texts.text(locale("de"), "booking.confirmed", Map.of("orderId", 7));

        assertThat(text).isEqualTo(new LocalizedText("Booking #7 is confirmed.", "en", "de", TextOrigin.FALLBACK));
    }

    @Test
    void configuredLanguagesFallBackToRussian() {
        I18nConfig config = new I18nConfig(true, "ru", Set.of("ru", "en"), "en", Set.of("kk"), 0.7, 8, false, "none", "ru", 100);
        GuestTexts texts = new GuestTexts(config, catalog, null, null, null);

        assertThat(texts.text(locale("kk"), "booking.confirmed", Map.of("orderId", 7)).text())
                .isEqualTo("Бронь #7 подтверждена.");
    }

    @Test
    void missingEnglishTextFallsBackToRussianAndMissingKeyReturnsTheKey() {
        GuestTexts texts = new GuestTexts(I18nConfig.enabledWithoutTranslation(), catalog, null, null, null);

        assertThat(texts.text(locale("en"), "only.ru").text()).isEqualTo("Только по-русски");
        assertThat(texts.text(locale("en"), "no.such.key"))
                .isEqualTo(new LocalizedText("no.such.key", "ru", "en", TextOrigin.MISSING));
    }

    @Test
    void machineTranslationTranslatesTheTemplateOnceAndCachesIt() {
        FakeTranslator translator = new FakeTranslator();
        GuestTexts texts = new GuestTexts(withTranslation(), catalog, translator, new InMemoryTranslationCache(100), null);

        LocalizedText first = texts.text(locale("de"), "booking.confirmed", Map.of("orderId", 7));
        LocalizedText second = texts.text(locale("de"), "booking.confirmed", Map.of("orderId", 8));

        assertThat(first).isEqualTo(new LocalizedText("[de] Бронь #7 подтверждена.", "de", "de", TextOrigin.MACHINE_TRANSLATION));
        assertThat(second.text()).isEqualTo("[de] Бронь #8 подтверждена.");
        assertThat(translator.requests).hasSize(1);
        assertThat(translator.requests.getFirst().texts()).containsExactly("Бронь #{orderId} подтверждена.");
        assertThat(translator.requests.getFirst().sourceLanguage()).isEqualTo("ru");
    }

    @Test
    void catalogLanguagesAreNeverMachineTranslated() {
        FakeTranslator translator = new FakeTranslator();
        GuestTexts texts = new GuestTexts(withTranslation(), catalog, translator, null, null);

        assertThat(texts.text(locale("en"), "only.ru").origin()).isEqualTo(TextOrigin.FALLBACK);
        assertThat(translator.requests).isEmpty();
    }

    @Test
    void translationThatLosesAPlaceholderIsRejected() {
        FakeTranslator translator = new FakeTranslator() {
            @Override
            public Optional<TranslationResult> translate(TranslationRequest request) {
                requests.add(request);
                return Optional.of(new TranslationResult(List.of("Buchung bestätigt."), "fake"));
            }
        };
        GuestTexts texts = new GuestTexts(withTranslation(), catalog, translator, null, null);

        LocalizedText text = texts.text(locale("de"), "booking.confirmed", Map.of("orderId", 7));

        assertThat(text.origin()).isEqualTo(TextOrigin.FALLBACK);
        assertThat(text.text()).isEqualTo("Booking #7 is confirmed.");
    }

    @Test
    void failingTranslatorFallsBackQuietly() {
        FakeTranslator translator = new FakeTranslator() {
            @Override
            public Optional<TranslationResult> translate(TranslationRequest request) {
                throw new IllegalStateException("provider down");
            }
        };
        GuestTexts texts = new GuestTexts(withTranslation(), catalog, translator, null, null);

        assertThat(texts.text(locale("de"), "booking.confirmed", Map.of("orderId", 7)).language()).isEqualTo("en");
    }

    @Test
    void unavailableTranslatorIsNotCalled() {
        GuestTexts texts = new GuestTexts(withTranslation(), catalog, new NoOpMachineTranslator(), null, null);

        assertThat(texts.text(locale("de"), "menu.button").text()).isEqualTo("Kitchen menu");
    }

    @Test
    void recognisesButtonLabelsInEveryCatalogLanguageOnlyWhenEnabled() {
        GuestTexts on = new GuestTexts(I18nConfig.enabledWithoutTranslation(), catalog, null, null, null);
        GuestTexts off = new GuestTexts(I18nConfig.defaults(), catalog, null, null, null);

        assertThat(on.matchesLabel("menu.button", " kitchen MENU ")).isTrue();
        assertThat(on.matchesLabel("menu.button", "меню кухни")).isTrue();
        assertThat(on.matchesLabel("menu.button", "menu")).isFalse();
        assertThat(off.matchesLabel("menu.button", "Kitchen menu")).isFalse();
        assertThat(off.matchesLabel("menu.button", "Меню кухни")).isTrue();
    }

    @Test
    void recognisesAMachineTranslatedLabel() {
        GuestTexts texts = new GuestTexts(withTranslation(), catalog, new FakeTranslator(), new InMemoryTranslationCache(100), null);

        assertThat(texts.matchesLabel("menu.button", "[de] Меню кухни", locale("de"))).isTrue();
        assertThat(texts.matchesLabel("menu.button", "[fr] Меню кухни", locale("de"))).isFalse();
    }

    @Test
    void russianOnlyReadsTheBundledCatalog() {
        GuestTexts texts = GuestTexts.russianOnly();

        assertThat(texts.enabled()).isFalse();
        assertThat(texts.text(locale("en"), "feedback.button").text()).isEqualTo("Оставить отзыв");
    }

    private static I18nConfig withTranslation() {
        return new I18nConfig(true, "ru", Set.of("ru", "en"), "en", Set.of(), 0.7, 8, true, "fake", "ru", 100);
    }

    private static ResolvedLocale locale(String language) {
        return new ResolvedLocale(language, LocaleSource.DETECTED);
    }

    /** Prefixes the target language, so tests see what was translated and into what. */
    private static class FakeTranslator implements MachineTranslator {
        final List<TranslationRequest> requests = new ArrayList<>();

        @Override
        public String provider() {
            return "fake";
        }

        @Override
        public boolean available() {
            return true;
        }

        @Override
        public Optional<TranslationResult> translate(TranslationRequest request) {
            requests.add(request);
            return Optional.of(new TranslationResult(
                    request.texts().stream().map(text -> "[" + request.targetLanguage() + "] " + text).toList(),
                    "fake"
            ));
        }
    }
}
