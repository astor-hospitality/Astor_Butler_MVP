package museon_online.astor_butler.domain.booking;

import museon_online.astor_butler.api.common.ApiException;
import museon_online.astor_butler.api.common.ErrorCode;
import museon_online.astor_butler.telegram.utils.TelegramBot;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.telegram.telegrambots.meta.api.methods.BotApiMethod;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.EditMessageReplyMarkup;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.EditMessageText;
import org.telegram.telegrambots.meta.api.objects.Chat;
import org.telegram.telegrambots.meta.api.objects.Message;
import org.telegram.telegrambots.meta.api.objects.MessageEntity;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * «🪑 Гость сел» on the confirmed hostess card (#151): the press, what it does to the booking and to the card, a
 * second press, and who may press at all. 09:32 UTC is 14:32 at the venue.
 */
class HostessSeatedServiceTest {

    private static final Instant SEATED_AT = Instant.parse("2026-10-12T09:32:00Z");

    private final BookingSeatedProperties properties = new BookingSeatedProperties();
    private final TableReservationService reservations = mock(TableReservationService.class);
    private final TableReservationNotificationService notifications = mock(TableReservationNotificationService.class);
    private final TelegramBot bot = mock(TelegramBot.class);
    private final HostessSeatedService service = new HostessSeatedService(properties, reservations, notifications, provider(bot));

    private final TableReservationOrder confirmed = order(44L, TableReservationStatus.CONFIRMED, null);
    private final TableReservationOrder seated = order(44L, TableReservationStatus.SEATED, SEATED_AT);
    private final InlineKeyboardMarkup rest = InlineKeyboardMarkup.builder()
            .keyboard(List.of(List.of(InlineKeyboardButton.builder().text("Счёт").callbackData("table_booking:bill:44").build())))
            .build();

    @BeforeEach
    void theFeatureIsOnAndTheHostessChatIsKnown() {
        properties.setEnabled(true);
        service.setHostessChatId("500");
        service.setTelegramEnabled(true);
        when(reservations.getReservation(44L)).thenReturn(confirmed);
        when(reservations.seatByStaff(44L, "@dasha")).thenReturn(seated);
        when(notifications.seatedCardKeyboard(44L)).thenReturn(rest);
    }

    @Test
    void otherChatsAndOtherCallbacksAreNotHandled() {
        assertThat(service.handleCallback("table_booking:seated:44", 501L, card(), "@dasha").handled()).isFalse();
        assertThat(service.handleCallback("table_booking:seated:44", null, card(), "@dasha").handled()).isFalse();
        assertThat(service.handleCallback("table_booking:confirm:44", 500L, card(), "@dasha").handled()).isFalse();
        assertThat(service.handleCallback("table_booking:seated:x", 500L, card(), "@dasha").handled()).isFalse();
        assertThat(service.handleCallback(null, 500L, card(), "@dasha").handled()).isFalse();
        verifyNoInteractions(reservations, bot);
    }

    @Test
    void withTheFlagOffAnOldButtonSaysSoAndSeatsNobody() {
        properties.setEnabled(false);

        var result = service.handleCallback("table_booking:seated:44", 500L, card(), "@dasha");

        assertThat(result.handled()).isTrue();
        assertThat(result.answerText()).contains("выключена", "ASTOR_BOOKING_SEATED_ENABLED");
        verifyNoInteractions(reservations, bot);
    }

    @Test
    void thePressSeatsTheGuestWritesTheTimeOnTheCardAndDropsTheButton() throws Exception {
        Message card = card();

        var result = service.handleCallback("table_booking:seated:44", 500L, card, "@dasha");

        assertThat(result.handled()).isTrue();
        assertThat(result.answerText()).isEqualTo("Бронь #44: гость сел в 14:32");
        verify(reservations).seatByStaff(44L, "@dasha");

        EditMessageText edit = lastOf(EditMessageText.class);
        assertThat(edit.getChatId()).isEqualTo("500");
        assertThat(edit.getMessageId()).isEqualTo(100);
        assertThat(edit.getText()).isEqualTo(card.getText() + "\n\n🪑 Гость сел 14:32 — @dasha");
        assertThat(edit.getParseMode()).isNull();
        // The bold labels of the card ride along as entities; appending at the end leaves their offsets intact.
        assertThat(edit.getEntities()).extracting(MessageEntity::getType, MessageEntity::getOffset, MessageEntity::getLength)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("bold", 0, 32));
        assertThat(((InlineKeyboardMarkup) edit.getReplyMarkup()).getKeyboard().getFirst())
                .extracting(InlineKeyboardButton::getCallbackData).containsExactly("table_booking:bill:44");
    }

    @Test
    void aSecondPressRepeatsTheTimeAndCleansTheCardWithoutSeatingAgain() throws Exception {
        when(reservations.getReservation(44L)).thenReturn(seated);
        Message card = card();

        var result = service.handleCallback("table_booking:seated:44", 500L, card, "@dasha");

        assertThat(result.answerText()).isEqualTo("Бронь #44: гость уже сел в 14:32");
        verify(reservations, never()).seatByStaff(anyLong(), anyString());
        EditMessageText edit = lastOf(EditMessageText.class);
        assertThat(edit.getText()).endsWith("\n\n🪑 Гость сел 14:32");
        assertThat(edit.getText()).doesNotContain("@dasha");
    }

    @Test
    void aCardThatAlreadyCarriesTheLineOnlyLosesTheButton() throws Exception {
        when(reservations.getReservation(44L)).thenReturn(seated);
        Message card = card();
        card.setText(card.getText() + "\n\n🪑 Гость сел 14:32");

        service.handleCallback("table_booking:seated:44", 500L, card, "@dasha");

        EditMessageReplyMarkup edit = lastOf(EditMessageReplyMarkup.class);
        assertThat(edit.getMessageId()).isEqualTo(100);
        assertThat(((InlineKeyboardMarkup) edit.getReplyMarkup()).getKeyboard()).hasSize(1);
    }

    @Test
    void aBookingThatIsNotConfirmedCannotBeSeated() {
        for (TableReservationStatus status : List.of(TableReservationStatus.AWAITING_MANAGER_CONFIRMATION,
                TableReservationStatus.REJECTED, TableReservationStatus.CANCELLED, TableReservationStatus.EXPIRED)) {
            when(reservations.getReservation(44L)).thenReturn(order(44L, status, null));

            var result = service.handleCallback("table_booking:seated:44", 500L, card(), "@dasha");

            assertThat(result.handled()).as(status.name()).isTrue();
            assertThat(result.answerText()).as(status.name()).contains("#44", "отметить посадку нельзя");
        }
        when(reservations.getReservation(44L)).thenReturn(order(44L, TableReservationStatus.AWAITING_MANAGER_CONFIRMATION, null));
        assertThat(service.handleCallback("table_booking:seated:44", 500L, card(), "@dasha").answerText())
                .isEqualTo("Бронь #44 ещё не подтверждена — отметить посадку нельзя");
        verify(reservations, never()).seatByStaff(anyLong(), anyString());
        verifyNoInteractions(bot);
    }

    @Test
    void aBookingNobodyFindsOrARefusedSeatingIsAnsweredOnTheButton() {
        when(reservations.getReservation(45L))
                .thenThrow(new ApiException(HttpStatus.NOT_FOUND, ErrorCode.NOT_FOUND, "Table reservation was not found"));
        assertThat(service.handleCallback("table_booking:seated:45", 500L, card(), "@dasha").answerText())
                .isEqualTo("Бронь #45 не найдена");

        when(reservations.seatByStaff(44L, "@dasha"))
                .thenThrow(new ApiException(HttpStatus.CONFLICT, ErrorCode.CONFLICT, "Only confirmed reservations can be seated"));
        assertThat(service.handleCallback("table_booking:seated:44", 500L, card(), "@dasha").answerText())
                .contains("#44", "не удалось");
        verifyNoInteractions(bot);
    }

    @Test
    void withoutTheMessageOrTheBotTheBookingIsStillSeated() {
        assertThat(service.handleCallback("table_booking:seated:44", 500L, null, "@dasha").answerText())
                .isEqualTo("Бронь #44: гость сел в 14:32");

        service.setTelegramEnabled(false);
        assertThat(service.handleCallback("table_booking:seated:44", 500L, card(), "@dasha").answerText())
                .isEqualTo("Бронь #44: гость сел в 14:32");
        verifyNoInteractions(bot);
    }

    @Test
    void aFailedCardEditIsNotTheHostessProblem() throws Exception {
        when(bot.execute(any(EditMessageText.class))).thenThrow(new IllegalStateException("message is too old"));

        var result = service.handleCallback("table_booking:seated:44", 500L, card(), "@dasha");

        assertThat(result.answerText()).isEqualTo("Бронь #44: гость сел в 14:32");
        verify(reservations).seatByStaff(44L, "@dasha");
    }

    @Test
    void anUnknownPresserIsStillRecorded() {
        when(reservations.seatByStaff(eq(44L), anyString())).thenReturn(seated);

        service.handleCallback("table_booking:seated:44", 500L, card(), "");

        verify(reservations).seatByStaff(44L, "неизвестно");
    }

    // ---- helpers ----

    /** The confirmed card as Telegram hands it back with the press: plain text plus the bold label as an entity. */
    private static Message card() {
        Message message = new Message();
        message.setMessageId(100);
        Chat chat = new Chat();
        chat.setId(500L);
        message.setChat(chat);
        message.setText("Бронь стола подтверждена командой\n\nЗаказ: #44\nСтол: Стол 5 (5)");
        message.setEntities(List.of(new MessageEntity("bold", 0, 32)));
        return message;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private <T extends BotApiMethod<?>> T lastOf(Class<T> type) throws Exception {
        ArgumentCaptor<BotApiMethod> captor = ArgumentCaptor.forClass(BotApiMethod.class);
        verify(bot, atLeastOnce()).execute(captor.capture());
        List<BotApiMethod> all = captor.getAllValues();
        for (int i = all.size() - 1; i >= 0; i--) {
            if (type.isInstance(all.get(i))) {
                return type.cast(all.get(i));
            }
        }
        throw new AssertionError("Nothing of " + type.getSimpleName() + " was sent; sent: " + all);
    }

    @SuppressWarnings("unchecked")
    private static ObjectProvider<TelegramBot> provider(TelegramBot bot) {
        ObjectProvider<TelegramBot> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(bot);
        return provider;
    }

    private static TableReservationOrder order(Long id, TableReservationStatus status, Instant seatedAt) {
        Instant start = Instant.parse("2026-10-12T08:00:00Z");
        return new TableReservationOrder(id, 1773317437L, 1773317437L, null, 5L, "5", "Table 5", null, null, status, "TELEGRAM",
                start, start.plusSeconds(5400), 2, "Наталья", "+79990000000", null, 876857557L, null, "500", null,
                start.minusSeconds(86400), start.minusSeconds(86400), seatedAt);
    }
}
