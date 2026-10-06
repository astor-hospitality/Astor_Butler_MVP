package museon_online.astor_butler.fsm.understanding;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class GuestMoneyTextTest {

    @Test
    void readsASumWrittenInDigits() {
        Object[][] cases = {
                {"500", 500L}, {"оставить чаевые 1000 рублей", 1000L}, {"1 000", 1000L}, {"50 000", 50_000L}, {"1 500 рублей", 1500L},
                {"12 500 ₽", 12_500L}, {"700р", 700L}, {"700 р.", 700L}, {"1500,50", 1500L}, {"чаевые - 500", 500L},
                {"чаевые официанту 700", 700L}, {"5 рублей", 5L}};
        for (Object[] example : cases) {
            assertThat(GuestMoneyText.read((String) example[0])).as((String) example[0])
                    .isEqualTo(new GuestMoneyText.Reading(GuestMoneyText.Kind.AMOUNT, (Long) example[1]));
        }
    }

    @Test
    void readsThousands() {
        Object[][] cases = {
                {"2к", 2000L}, {"2k", 2000L}, {"5 тысяч", 5000L}, {"2 тыс", 2000L}, {"2тыс.", 2000L}, {"1,5 тыс", 1500L}, {"1.5к", 1500L},
                {"1,25 тысячи рублей", 1250L}, {"10 тыщ", 10_000L}};
        for (Object[] example : cases) {
            assertThat(GuestMoneyText.read((String) example[0])).as((String) example[0])
                    .isEqualTo(new GuestMoneyText.Reading(GuestMoneyText.Kind.AMOUNT, (Long) example[1]));
        }
    }

    @Test
    void readsASumWrittenInWords() {
        Object[][] cases = {
                {"сто рублей", 100L}, {"Пятьсот", 500L}, {"пять тысяч", 5000L}, {"полторы тысячи", 1500L}, {"тысячу", 1000L},
                {"две тысячи пятьсот рублей", 2500L}, {"сто пятьдесят", 150L}, {"хочу оставить триста рублей официанту", 300L},
                {"полтысячи", 500L}, {"двадцать одна тысяча", 21_000L}, {"пять рублей", 5L}, {"столик 5, чаевые двести", 200L}};
        for (Object[] example : cases) {
            assertThat(GuestMoneyText.read((String) example[0])).as((String) example[0])
                    .isEqualTo(new GuestMoneyText.Reading(GuestMoneyText.Kind.AMOUNT, (Long) example[1]));
        }
    }

    @Test
    void aSumWithACurrencyWinsOverAnotherNumberInThePhrase() {
        assertThat(GuestMoneyText.read("чаевые за стол 12, 500 рублей").rubles()).isEqualTo(500L);
        assertThat(GuestMoneyText.read("за стол 12 оставлю 2к").rubles()).isEqualTo(2000L);
        // With nothing to tell them apart the first number is taken, as before.
        assertThat(GuestMoneyText.read("чаевые 300 за стол 12").rubles()).isEqualTo(300L);
    }

    @Test
    void aMinusOrAZeroIsNotASum() {
        for (String text : new String[]{"-100", "−100", "минус 100", "0", "000", "0 рублей", "ноль рублей", "минус сто", "-2к"}) {
            assertThat(GuestMoneyText.read(text).kind()).as(text).isEqualTo(GuestMoneyText.Kind.NOT_POSITIVE);
            assertThat(GuestMoneyText.read(text).isAmount()).as(text).isFalse();
        }
    }

    @Test
    void aTextWithNoSumInItIsNotRead() {
        for (String text : new String[]{"", null, "хочу оставить чаевые", "5", "стол 7", "один момент", "три раза спасибо", "полторы",
                "отмена", "главное меню", "ну не знаю, рублей сколько-нибудь", "100000000", "12345678901234567890"}) {
            assertThat(GuestMoneyText.read(text).kind()).as(String.valueOf(text)).isEqualTo(GuestMoneyText.Kind.NONE);
        }
    }
}
