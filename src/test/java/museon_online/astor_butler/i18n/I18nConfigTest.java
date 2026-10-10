package museon_online.astor_butler.i18n;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class I18nConfigTest {

    @Test
    void defaultsAreOffRussianAndFree() {
        I18nConfig config = I18nConfig.defaults();

        assertThat(config.enabled()).isFalse();
        assertThat(config.defaultLanguage()).isEqualTo("ru");
        assertThat(config.machineTranslationEnabled()).isFalse();
        assertThat(config.machineTranslationProvider()).isEqualTo("none");
    }

    @Test
    void bindingNormalizesTheEnvironment() {
        I18nConfig config = new I18nConfig.Binding().i18nConfig(
                true, " RU ", "ru, en-US, ,xx-YY", "EN", "kk,uz", 1.5, 0, true, " Yandex ", "", 1);

        assertThat(config.catalogLanguages()).containsExactlyInAnyOrder("ru", "en", "xx");
        assertThat(config.defaultLanguage()).isEqualTo("ru");
        assertThat(config.detectionMinConfidence()).isEqualTo(1.0);
        assertThat(config.detectionMinLetters()).isEqualTo(1);
        assertThat(config.machineTranslationProvider()).isEqualTo("yandex");
        assertThat(config.machineTranslationSource()).isEqualTo("ru");
        assertThat(config.translationCacheMaxEntries()).isEqualTo(100);
    }

    @Test
    void fallbackIsEnglishExceptForConfiguredLanguages() {
        I18nConfig config = new I18nConfig(true, "ru", Set.of("ru", "en"), "en", Set.of("kk"), 0.7, 8, false, "none", "ru", 100);

        assertThat(config.fallbackFor("de")).isEqualTo("en");
        assertThat(config.fallbackFor("kk-KZ")).isEqualTo("ru");
        assertThat(config.isCatalogLanguage("en-GB")).isTrue();
    }
}
