package museon_online.astor_butler.i18n;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class MessageCatalogTest {

    @Test
    void readsNestedYamlAsDottedKeys() {
        MessageCatalog catalog = MessageCatalog.load(MessageCatalog.GUEST_BUNDLE, List.of("ru", "en"));

        assertThat(catalog.languages()).containsExactlyInAnyOrder("ru", "en");
        assertThat(catalog.template("ru", "feedback.button")).contains("Оставить отзыв");
        assertThat(catalog.template("en-US", "feedback.button")).contains("Leave feedback");
        assertThat(catalog.template("de", "feedback.button")).isEmpty();
        assertThat(catalog.template("ru", "no.such.key")).isEmpty();
    }

    @Test
    void missingLanguageFileIsSkipped() {
        MessageCatalog catalog = MessageCatalog.load(MessageCatalog.GUEST_BUNDLE, List.of("ru", "xx"));

        assertThat(catalog.languages()).containsExactly("ru");
    }

    @Test
    void formatsNamedPlaceholdersAndLeavesUnknownOnes() {
        String text = MessageCatalog.format("Бронь #{orderId} на {time}, {missing}", Map.of("orderId", 42, "time", "19:00"));

        assertThat(text).isEqualTo("Бронь #42 на 19:00, {missing}");
        assertThat(MessageCatalog.format("Цена $5 {x}", Map.of("x", "$1\\"))).isEqualTo("Цена $5 $1\\");
        assertThat(MessageCatalog.placeholders("{a} и {b_2} и {a}")).containsExactly("a", "b_2");
    }

    @Test
    void collectsALabelInEveryLanguage() {
        MessageCatalog catalog = MessageCatalog.of(Map.of(
                "ru", Map.of("menu.button", "Меню"),
                "en", Map.of("menu.button", "Menu")
        ));

        assertThat(catalog.allTemplates("menu.button")).containsExactlyInAnyOrder("Меню", "Menu");
        assertThat(MessageCatalog.normalizeLabel("  Ещё   раз ")).isEqualTo("еще раз");
    }
}
