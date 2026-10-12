package museon_online.astor_butler.domain.booking;

import museon_online.astor_butler.telegram.utils.TelegramBot;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;

import java.time.Instant;
import java.time.LocalTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** The morning question, the thank-you and the staff line, word for word, without the guest's phone anywhere. */
class BookingMorningCheckNotifierTest {

    private final TelegramBot bot = mock(TelegramBot.class);

    @Test
    void theQuestionNamesTheTimeAndThePartyAndCarriesThreeButtons() throws Exception {
        assertThat(enabled(bot, "staff-chat").ask(order(41L, 1001L, 2, "Ирина", null), LocalTime.of(13, 0))).isTrue();

        SendMessage message = sent();
        assertThat(message.getChatId()).isEqualTo("1001");
        assertThat(message.getText()).isEqualTo("Сегодня ждём вас в 13:00, 2 гостя. Всё в силе?");
        assertThat(message.getReplyMarkup()).isInstanceOf(InlineKeyboardMarkup.class);
        var rows = ((InlineKeyboardMarkup) message.getReplyMarkup()).getKeyboard();
        assertThat(rows).hasSize(1);
        assertThat(rows.getFirst()).extracting(InlineKeyboardButton::getText).containsExactly("Всё в силе", "Перенести", "Отменить");
        assertThat(rows.getFirst()).extracting(InlineKeyboardButton::getCallbackData)
                .containsExactly("morning_check:41:keep", "morning_check:41:move", "morning_check:41:cancel");
    }

    @Test
    void theQuestionWithoutAPartySizeStillReads() {
        assertThat(BookingMorningCheckNotifier.askText(order(41L, 1001L, null, "Ирина", null), LocalTime.of(19, 30)))
                .isEqualTo("Сегодня ждём вас в 19:30. Всё в силе?");
    }

    @Test
    void theThankYouPointsAtTheMenuButtonForLaterChanges() throws Exception {
        assertThat(enabled(bot, "staff-chat").thanks(order(41L, 1001L, 2, "Ирина", null), LocalTime.of(13, 0))).isTrue();
        SendMessage message = sent();
        assertThat(message.getChatId()).isEqualTo("1001");
        assertThat(message.getText()).isEqualTo(
                "Спасибо, ждём вас сегодня в 13:00! Если планы изменятся, нажмите «Изменить / отменить» в главном меню.");
        assertThat(message.getReplyMarkup()).isNull();
    }

    @Test
    void theStaffLineGoesToTheHostessChatOfTheOrderOrTheConfiguredOne() throws Exception {
        assertThat(enabled(bot, "staff-chat").hostessReconfirmed(order(41L, 1001L, 2, "Ирина", null), LocalTime.of(13, 0))).isTrue();
        SendMessage configured = sent();
        assertThat(configured.getChatId()).isEqualTo("staff-chat");
        assertThat(configured.getText()).isEqualTo("✅ Гость подтвердил утром бронь #41: сегодня 13:00, 2 гостя, стол 5 — Ирина.");
        assertThat(configured.getText()).doesNotContain("+7999");

        TelegramBot other = mock(TelegramBot.class);
        enabled(other, "staff-chat").hostessReconfirmed(order(42L, 1002L, 5, " ", "-100777"), LocalTime.of(14, 0));
        ArgumentCaptor<SendMessage> captor = ArgumentCaptor.forClass(SendMessage.class);
        verify(other).execute(captor.capture());
        assertThat(captor.getValue().getChatId()).isEqualTo("-100777");
        assertThat(captor.getValue().getText()).isEqualTo("✅ Гость подтвердил утром бронь #42: сегодня 14:00, 5 гостей, стол 5.");
    }

    @Test
    void nothingLeavesWhileTelegramOrTheBookingNotificationsAreOff() {
        assertThat(new BookingMorningCheckNotifier(provider(bot), false, true, "staff-chat")
                .ask(order(41L, 1001L, 2, "Ирина", null), LocalTime.NOON)).isFalse();
        assertThat(new BookingMorningCheckNotifier(provider(bot), true, false, "staff-chat")
                .ask(order(41L, 1001L, 2, "Ирина", null), LocalTime.NOON)).isFalse();
        assertThat(new BookingMorningCheckNotifier(provider(bot), true, true, "")
                .hostessReconfirmed(order(41L, 1001L, 2, "Ирина", null), LocalTime.NOON)).isFalse();
        verifyNoInteractions(bot);
    }

    @Test
    void aTelegramFailureIsReportedAsNotSent() throws Exception {
        when(bot.execute(any(SendMessage.class))).thenThrow(new RuntimeException("telegram is away"));
        assertThat(enabled(bot, "staff-chat").ask(order(41L, 1001L, 2, "Ирина", null), LocalTime.NOON)).isFalse();
    }

    @Test
    void theRussianPluralOfGuestsFollowsTheLastDigits() {
        assertThat(BookingMorningCheckNotifier.guests(1)).isEqualTo("1 гость");
        assertThat(BookingMorningCheckNotifier.guests(2)).isEqualTo("2 гостя");
        assertThat(BookingMorningCheckNotifier.guests(4)).isEqualTo("4 гостя");
        assertThat(BookingMorningCheckNotifier.guests(5)).isEqualTo("5 гостей");
        assertThat(BookingMorningCheckNotifier.guests(11)).isEqualTo("11 гостей");
        assertThat(BookingMorningCheckNotifier.guests(12)).isEqualTo("12 гостей");
        assertThat(BookingMorningCheckNotifier.guests(21)).isEqualTo("21 гость");
        assertThat(BookingMorningCheckNotifier.guests(22)).isEqualTo("22 гостя");
    }

    private SendMessage sent() throws Exception {
        ArgumentCaptor<SendMessage> captor = ArgumentCaptor.forClass(SendMessage.class);
        verify(bot).execute(captor.capture());
        return captor.getValue();
    }

    private static BookingMorningCheckNotifier enabled(TelegramBot bot, String hostessChatId) {
        return new BookingMorningCheckNotifier(provider(bot), true, true, hostessChatId);
    }

    @SuppressWarnings("unchecked")
    private static ObjectProvider<TelegramBot> provider(TelegramBot bot) {
        ObjectProvider<TelegramBot> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(bot);
        return provider;
    }

    static TableReservationOrder order(Long id, Long chatId, Integer party, String guest, String hostessChatId) {
        Instant start = Instant.parse("2026-10-12T08:00:00Z");
        return new TableReservationOrder(id, chatId, null, null, 5L, "5", "Table 5", "WINDOW", null,
                TableReservationStatus.CONFIRMED, "TELEGRAM", start, start.plusSeconds(7200), party, guest, "+79990000000",
                null, null, null, hostessChatId, null, start.minusSeconds(86400), start.minusSeconds(86400));
    }
}
