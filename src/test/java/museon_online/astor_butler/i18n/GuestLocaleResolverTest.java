package museon_online.astor_butler.i18n;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class GuestLocaleResolverTest {

    private final GuestLocaleResolver resolver = new GuestLocaleResolver(I18nConfig.enabledWithoutTranslation());

    @Test
    void explicitChoiceWinsOverEverything() {
        ResolvedLocale locale = resolver.resolve(new LocaleSignals("de", detected("en", 0.95), "fr", "it"));

        assertThat(locale).isEqualTo(new ResolvedLocale("de", LocaleSource.EXPLICIT));
    }

    @Test
    void confidentDetectionWinsOverConversationAndPlatform() {
        ResolvedLocale locale = resolver.resolve(new LocaleSignals(null, detected("en", 0.8), "ru", "ru"));

        assertThat(locale).isEqualTo(new ResolvedLocale("en", LocaleSource.DETECTED));
    }

    @Test
    void weakDetectionDoesNotFlipTheConversationLanguage() {
        ResolvedLocale locale = resolver.resolve(new LocaleSignals(null, detected("ru", 0.5), "bg", "ru"));

        assertThat(locale).isEqualTo(new ResolvedLocale("bg", LocaleSource.CONVERSATION));
    }

    @Test
    void platformLanguageIsUsedWhenTheMessageSaysNothing() {
        ResolvedLocale locale = resolver.resolve(new LocaleSignals(null, null, null, "en-US"));

        assertThat(locale).isEqualTo(new ResolvedLocale("en", LocaleSource.PLATFORM));
    }

    @Test
    void defaultsToRussian() {
        assertThat(resolver.resolve(new LocaleSignals("", null, " ", "und")))
                .isEqualTo(new ResolvedLocale("ru", LocaleSource.DEFAULT));
        assertThat(resolver.resolve(null)).isEqualTo(new ResolvedLocale("ru", LocaleSource.DEFAULT));
    }

    @Test
    void featureOffAlwaysAnswersInTheDefaultLanguage() {
        GuestLocaleResolver off = new GuestLocaleResolver(I18nConfig.defaults());

        assertThat(off.resolve(new LocaleSignals("de", detected("en", 0.99), "fr", "it")))
                .isEqualTo(new ResolvedLocale("ru", LocaleSource.DEFAULT));
    }

    private static DetectedLanguage detected(String language, double confidence) {
        return new DetectedLanguage(language, confidence, "test");
    }
}
