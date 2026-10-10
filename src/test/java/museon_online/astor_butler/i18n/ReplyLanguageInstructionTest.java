package museon_online.astor_butler.i18n;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ReplyLanguageInstructionTest {

    @Test
    void russianGuestsKeepTheExistingPrompt() {
        assertThat(ReplyLanguageInstruction.forLocale(new ResolvedLocale("ru", LocaleSource.PLATFORM), "ru")).isEmpty();
        assertThat(ReplyLanguageInstruction.forLocale(null, "ru")).isEmpty();
    }

    @Test
    void otherLanguagesAreNamedWithTheirCode() {
        String instruction = ReplyLanguageInstruction.forLocale(new ResolvedLocale("de", LocaleSource.DETECTED), "ru");

        assertThat(instruction).contains("German (код de)").contains("только на этом языке");
    }
}
