package museon_online.astor_butler.fsm.understanding;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class GuestPercentTextTest {

    @Test
    void readsAPercentWithItsSign() {
        Object[][] cases = {{"5%", 5}, {"10 %", 10}, {"15%", 15}, {"чаевые 20%", 20}, {"100%", 100}, {"20 процентов", 20}, {"Пятнадцать: 15 процентов", 15}};
        for (Object[] example : cases) {
            GuestPercentText.Reading reading = GuestPercentText.read((String) example[0]);

            assertThat(reading.kind()).as((String) example[0]).isEqualTo(GuestPercentText.Kind.MARKED);
            assertThat(reading.percent()).as((String) example[0]).isEqualTo(example[1]);
        }
    }

    @Test
    void readsALoneNumberAsBare() {
        Object[][] cases = {{"20", 20}, {" 15 ", 15}, {"5", 5}, {"500", 500}};
        for (Object[] example : cases) {
            GuestPercentText.Reading reading = GuestPercentText.read((String) example[0]);

            assertThat(reading.kind()).as((String) example[0]).isEqualTo(GuestPercentText.Kind.BARE);
            assertThat(reading.percent()).as((String) example[0]).isEqualTo(example[1]);
        }
    }

    @Test
    void findsNoPercentWhereThereIsNone() {
        for (String text : new String[]{"", null, "чаевые", "500 ₽", "пятьсот рублей", "2к", "17,5%", "1000%", "стол 12, 20 минут"}) {
            assertThat(GuestPercentText.read(text).kind()).as(String.valueOf(text)).isEqualTo(GuestPercentText.Kind.NONE);
            assertThat(GuestPercentText.read(text).found()).as(String.valueOf(text)).isFalse();
        }
    }
}
