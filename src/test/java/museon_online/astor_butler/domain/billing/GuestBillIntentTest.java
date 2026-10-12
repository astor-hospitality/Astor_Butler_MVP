package museon_online.astor_butler.domain.billing;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/** The guest's words for the bill: whole words only, «ё» and «е» alike, no model. */
class GuestBillIntentTest {

    @ParameterizedTest
    @ValueSource(strings = {
            "счёт", "Счет", "счёт, пожалуйста", "можно счёт?", "Можно счет", "принесите счёт", "СЧЁТ!",
            "чек", "дайте чек пожалуйста",
            "посчитайте", "Посчитайте нас, пожалуйста", "рассчитайте", "рассчитайте нас",
            "хотим оплатить", "можно оплатить?", "закрыть стол", "Закройте? закрыть  стол"
    })
    void asksForTheBill(String text) {
        assertThat(GuestBillIntent.matches(text)).as(text).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "счётчик", "счетчик воды", "чековая книжка", "расчётный час", "посчитай", "оплатила", "оплатите сами",
            "закрыть столик", "стол закрыть", "чекин", "меню кухни", "бронь стола на двоих", "", "   ", "чаевые"
    })
    void doesNotAskForTheBill(String text) {
        assertThat(GuestBillIntent.matches(text)).as(text).isFalse();
    }

    @org.junit.jupiter.api.Test
    void nullIsNotARequest() {
        assertThat(GuestBillIntent.matches(null)).isFalse();
    }
}
