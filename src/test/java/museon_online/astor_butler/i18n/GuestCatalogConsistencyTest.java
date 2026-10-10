package museon_online.astor_butler.i18n;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** The human-edited catalogs move together: same keys, same placeholders, nothing blank. */
class GuestCatalogConsistencyTest {

    private static final List<String> HUMAN_LANGUAGES = List.of("ru", "en");

    private final MessageCatalog catalog = MessageCatalog.load(MessageCatalog.GUEST_BUNDLE, HUMAN_LANGUAGES);

    @Test
    void everyHumanLanguageHasACatalogFile() {
        assertThat(catalog.languages()).containsExactlyInAnyOrderElementsOf(HUMAN_LANGUAGES);
    }

    @Test
    void everyLanguageHasTheSameKeysAsRussian() {
        for (String language : HUMAN_LANGUAGES) {
            assertThat(catalog.keys(language))
                    .as("keys of %s.yaml", language)
                    .containsExactlyInAnyOrderElementsOf(catalog.keys("ru"));
        }
    }

    @Test
    void placeholdersMatchAndNothingIsBlank() {
        for (String key : catalog.keys("ru")) {
            String source = catalog.template("ru", key).orElseThrow();
            for (String language : HUMAN_LANGUAGES) {
                String text = catalog.template(language, key).orElseThrow();
                assertThat(text).as("%s in %s", key, language).isNotBlank();
                assertThat(MessageCatalog.placeholders(text))
                        .as("placeholders of %s in %s", key, language)
                        .isEqualTo(MessageCatalog.placeholders(source));
            }
        }
    }
}
