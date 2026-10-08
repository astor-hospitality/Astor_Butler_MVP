package museon_online.astor_butler.telegram.voice;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ReplyTextTest {

    @Test
    void linksComeFromAnchorsMarkdownAndBareUrlsInOrderWithoutDuplicates() {
        String text = """
                Меню: <a href="https://aeris.bar/menu.pdf">Меню кухни</a> и [винная карта](https://aeris.bar/wine).
                Фото зала: https://aeris.bar/img/hall.jpg. Ещё раз https://aeris.bar/menu.pdf
                """;

        List<ReplyText.Link> links = ReplyText.links(text);

        assertThat(links).extracting(ReplyText.Link::url).containsExactly(
                "https://aeris.bar/menu.pdf", "https://aeris.bar/wine", "https://aeris.bar/img/hall.jpg");
        assertThat(links.get(0).label()).isEqualTo("Меню кухни");
        assertThat(links.get(1).label()).isEqualTo("винная карта");
        assertThat(links.get(2).label()).isEqualTo("aeris.bar");
        assertThat(links.get(2).image()).isTrue();
        assertThat(links.get(0).image()).isFalse();
    }

    @Test
    void plainDropsTagsAndMarkdownButKeepsLinkTargets() {
        String plain = ReplyText.plain("<b>Стол</b> на 20:00 **готов**.<br/>Схема: <a href=\"https://aeris.bar/plan\">план зала</a> &amp; бар");

        assertThat(plain).isEqualTo("Стол на 20:00 готов.\nСхема: план зала https://aeris.bar/plan & бар");
    }

    @Test
    void speechHasNoUrlsOrEmoji() {
        String speech = ReplyText.speech("Бронь подтверждена 🎉. Схема зала: https://aeris.bar/plan — посмотрите.");

        assertThat(speech).isEqualTo("Бронь подтверждена . Схема зала: — посмотрите.");
    }

    @Test
    void chunksRespectSentenceBoundariesAndTotalBudget() {
        String sentence = "Это предложение номер раз, оно довольно длинное для теста. ";
        String speech = sentence.repeat(40).strip();

        List<String> chunks = ReplyText.speechChunks(speech, 200, 1000);

        assertThat(chunks).isNotEmpty();
        assertThat(chunks).allSatisfy(chunk -> {
            assertThat(chunk.length()).isLessThanOrEqualTo(200);
            assertThat(chunk).endsWith(".");
        });
        assertThat(String.join(" ", chunks).length()).isLessThanOrEqualTo(1000);
        assertThat(ReplyText.speechChunks("", 900, 4000)).isEmpty();
    }

    @Test
    void cutAtBoundaryPrefersSentenceEnd() {
        assertThat(ReplyText.cutAtBoundary("Первое. Второе предложение длиннее. Третье.", 30)).isEqualTo("Первое. Второе предложение…");
        assertThat(ReplyText.cutAtBoundary("Короткое.", 30)).isEqualTo("Короткое.");
    }
}
