package museon_online.astor_butler.i18n;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class LanguageTagsTest {

    @Test
    void keepsOnlyThePrimarySubtagInLowerCase() {
        assertThat(LanguageTags.normalize("en")).isEqualTo("en");
        assertThat(LanguageTags.normalize(" RU ")).isEqualTo("ru");
        assertThat(LanguageTags.normalize("pt-BR")).isEqualTo("pt");
        assertThat(LanguageTags.normalize("pt_br")).isEqualTo("pt");
        assertThat(LanguageTags.normalize("zh-hans")).isEqualTo("zh");
        assertThat(LanguageTags.normalize("ckb-IQ")).isEqualTo("ckb");
    }

    @Test
    void mapsDeprecatedCodes() {
        assertThat(LanguageTags.normalize("iw")).isEqualTo("he");
        assertThat(LanguageTags.normalize("in")).isEqualTo("id");
        assertThat(LanguageTags.normalize("ji")).isEqualTo("yi");
    }

    @Test
    void rejectsWhatIsNotALanguage() {
        assertThat(LanguageTags.normalize(null)).isEmpty();
        assertThat(LanguageTags.normalize("")).isEmpty();
        assertThat(LanguageTags.normalize("und")).isEmpty();
        assertThat(LanguageTags.normalize("e")).isEmpty();
        assertThat(LanguageTags.normalize("english")).isEmpty();
        assertThat(LanguageTags.normalize("12")).isEmpty();
        assertThat(LanguageTags.isValid("de-AT")).isTrue();
        assertThat(LanguageTags.isValid("x")).isFalse();
    }

    @Test
    void namesLanguagesForPromptsAndStaff() {
        assertThat(LanguageTags.englishName("de")).isEqualTo("German");
        assertThat(LanguageTags.englishName("zh-hans")).isEqualTo("Chinese");
        assertThat(LanguageTags.russianName("en")).isEqualTo("английский");
        assertThat(LanguageTags.englishName("")).isEmpty();
    }
}
