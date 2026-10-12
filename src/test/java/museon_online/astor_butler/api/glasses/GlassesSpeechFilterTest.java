package museon_online.astor_butler.api.glasses;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

class GlassesSpeechFilterTest {
    private final GlassesSpeechFilter filter = GlassesSpeechFilter.defaults();

    /* ---------- swearing ---------- */

    @Test void aSingleSwearWordIsCutAndTheSentenceStays() {
        assertThat(filter.spoken("Какой, блядь, стол просил счёт?")).isEqualTo("Какой, стол просил счёт?");
        assertThat(filter.spoken("Стол пять ждёт уже охуенно долго.")).isEqualTo("Стол пять ждёт уже долго.");
        assertThat(filter.spoken("Это полный пиздец.")).isEqualTo("Это полный.");
    }

    @Test void twoSwearWordsDropTheWholeSentenceAndTheRestIsKept() {
        assertThat(filter.spoken("Стол пять свободен. Нахуй эту ёбаную смену! Гости придут в 14:30."))
                .isEqualTo("Стол пять свободен. Гости придут в 14:30.");
    }

    @ParameterizedTest
    @ValueSource(strings = {"бл*дь", "Бл*дь", "БЛЯДЬ", "блядь", "пи3да", "хyй", "хуёвый", "cyka", "пидорас", "еба́ть",
            "f*ck", "FUCK", "fucking", "sh1t", "bullshit", "nahuy", "долбоёб", "заебали", "мудак", "х*йня"})
    void swearWordsAndTheirUsualDisguisesAreRecognised(String word) {
        assertThat(filter.profane(word)).as(word).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"хрен", "херес", "мандарин", "команда", "сук", "мудрый", "хулиган", "ebay", "assist",
            "shiitake", "cocktail", "стол", "Анна", "Илья", "рубль", "требование", "хлеб", "ребёнок", "потребность",
            "**", "а*", "говение"})
    void ordinaryWordsAreNotSwearing(String word) {
        assertThat(filter.profane(word)).as(word).isFalse();
    }

    @Test void extraWordsFromTheEnvironmentJoinTheLexicon() {
        var custom = new GlassesSpeechFilter(true, true, " козёл , урод* ");
        assertThat(custom.spoken("Этот козёл опять опоздал.")).isEqualTo("Этот опять опоздал.");
        assertThat(custom.spoken("Уродливый стол.")).isEqualTo("Стол.");
        assertThat(filter.spoken("Этот козёл опять опоздал.")).isEqualTo("Этот козёл опять опоздал.");
    }

    @Test void whenNothingSayableIsLeftTheWearerIsSentToTheScreen() {
        assertThat(filter.spoken("Блядь.")).isEqualTo(GlassesSpeechFilter.NOTHING_LEFT);
        assertThat(filter.spoken("Пиздец нахуй!")).isEqualTo(GlassesSpeechFilter.NOTHING_LEFT);
        assertThat(filter.spoken("   ")).isEqualTo("");
        assertThat(filter.spoken(null)).isNull();
    }

    /* ---------- sums and prices ---------- */

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "Бизнес-ланч стоит 650 рублей.                      | Бизнес-ланч — цена в меню.",
            "Борщ — 450 ₽, солянка — 520 ₽.                     | Борщ — цена в меню, солянка — цена в меню.",
            "Сет обойдётся в 1 500 ₽ за двоих.                   | Сет обойдётся — цена в меню за двоих.",
            "Ужин выйдет примерно 1500 руб.                      | Ужин выйдет — цена в меню.",
            "Дегустация — 1.5 тыс. рублей с человека.            | Дегустация — цена в меню с человека.",
            "Вино по 20$ за бокал, десерт €15.                   | Вино — цена в меню за бокал, десерт — цена в меню.",
            "Кофе 250 р.                                         | Кофе — цена в меню.",
            "Стоимость — от 450 до 600 рублей.                   | Цена в меню.",
            "Обед обойдётся в полторы тысячи рублей.             | Обед обойдётся — цена в меню.",
            "Цена 1200.                                          | Цена в меню.",
            "Салат стоит 450, суп дня стоит 380 рублей.          | Салат — цена в меню, суп дня — цена в меню.",
            "450 рублей за порцию.                               | Цена в меню.",
            "Бизнес-ланч: 650 рублей, с 12 до 16 часов.          | Бизнес-ланч — цена в меню, с 12 до 16 часов.",
            "Итого 2 000 000 рублей.                             | Цена в меню.",
            "Кофе 250 р. Чай 120 руб. Вода бесплатно.            | Кофе — цена в меню. Чай — цена в меню. Вода бесплатно.",
            "Суп дня, т.е. борщ, стоит 450 руб. сегодня.         | Суп дня, т.е. борщ — цена в меню сегодня."
    })
    void anAmountBecomesThePriceInTheMenu(String answer, String spoken) {
        assertThat(filter.spoken(answer.strip())).isEqualTo(spoken.strip());
    }

    @ParameterizedTest
    @ValueSource(strings = {"Стол 12 просит счёт.", "Гости придут в 14:30.", "Бронь на 4 гостя.", "Через 10 минут.",
            "Бизнес-ланч с 12 до 16 часов.", "Скидка 15% по карте.", "Порция 250 г, подача 10 минут.",
            "Столик на 6 человек у окна.", "Стол 5 ждёт 20 минут.", "Заказ номер 1234.", "Цена за 4 гостя та же.",
            "Записал поручение для Анна: Принести воду. Стол 5.", "Поручение записал на вас: Принести воду.",
            "Встреча в 12.30, стол 7.", "Температура 180 градусов, 25 минут."})
    void tablesTimesPartySizesAndOtherNumbersAreSpokenAsTheyAre(String answer) {
        assertThat(filter.spoken(answer)).as(answer).isEqualTo(answer);
    }

    /* ---------- names, the switch, idempotence ---------- */

    @Test void namesAreNotTouchedTheSwitchOnlyAffectsTheTaskConfirmation() {
        assertThat(filter.spoken("Анна на пятом столе, Илья на седьмом.")).isEqualTo("Анна на пятом столе, Илья на седьмом.");
        assertThat(filter.speakNames()).isTrue();
        assertThat(new GlassesSpeechFilter(true, false, "").speakNames()).isFalse();
    }

    @Test void whenDisabledEverythingPassesThrough() {
        var off = GlassesSpeechFilter.disabled();
        String answer = "Пиздец, бизнес-ланч стоит 650 рублей, блядь.";
        assertThat(off.spoken(answer)).isSameAs(answer);
        assertThat(off.enabled()).isFalse();
        assertThat(filter.enabled()).isTrue();
    }

    @Test void filteringTheSpokenTextAgainChangesNothing() {
        for (String answer : new String[]{"Какой, блядь, стол просил счёт?", "Борщ — 450 ₽, солянка — 520 ₽.",
                "Стол пять свободен. Нахуй эту ёбаную смену! Гости придут в 14:30.", "450 рублей за порцию.", "Блядь."}) {
            String once = filter.spoken(answer);
            assertThat(filter.spoken(once)).as(answer).isEqualTo(once);
        }
    }
}
