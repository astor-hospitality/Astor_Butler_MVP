package museon_online.astor_butler.domain.billing;

import museon_online.astor_butler.domain.booking.TableReservationOrder;
import museon_online.astor_butler.domain.booking.TableReservationStatus;
import museon_online.astor_butler.domain.journey.talk.TableRequestRouter;
import museon_online.astor_butler.telegram.utils.TelegramBot;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.Message;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.when;

/** 16.10, booking #68: a Concierge guest's «Я готова оплатить счёт» reached no one in the staff group. */
class GuestBillStaffCardTest {

    private static final String STAFF_CHAT = "-1002768239841";
    private static final Instant AT = Instant.parse("2026-10-16T08:30:00Z");

    private final TelegramBot bot = mock(TelegramBot.class);
    private final TableRequestRouter waiters = mock(TableRequestRouter.class);

    @Test
    void theCardNamesTheTableTheWaiterAndTheGuestAndGoesToTheBookingsTopic() throws Exception {
        when(bot.execute(any(SendMessage.class))).thenReturn(message(700));
        when(waiters.waiterName(any())).thenReturn(Optional.of("Анна"));
        GuestBillStaffCard card = card("15");

        assertThat(card.billAsked(order(68L, null), bill(68L, 251000L, GuestBillStatus.ISSUED))).isTrue();

        SendMessage sent = sent().getFirst();
        assertThat(sent.getChatId()).isEqualTo(STAFF_CHAT);
        assertThat(sent.getMessageThreadId()).as("no topic of its own: the router puts it in bookings").isNull();
        assertThat(sent.getText())
                .contains("Счёт · Стол у окна (T5) · Анна · гость Яна")
                .contains("Бронь #68")
                .contains("в Консьерже")
                .contains("В Saby: 2510 ₽")
                .doesNotContain("+7");
        List<InlineKeyboardButton> row = ((InlineKeyboardMarkup) sent.getReplyMarkup()).getKeyboard().getFirst();
        assertThat(row).extracting(InlineKeyboardButton::getText).containsExactly("Счёт", "Отменить счёт");
        assertThat(row).extracting(InlineKeyboardButton::getCallbackData)
                .containsExactly(HostessBillRequestService.callbackData(68L), "table_booking:bill-cancel:68");
    }

    @Test
    void withoutAWaiterOrABillTheCardStillGoesWithoutASum() throws Exception {
        when(bot.execute(any(SendMessage.class))).thenReturn(message(701));
        when(waiters.waiterName(any())).thenReturn(Optional.empty());

        assertThat(card("15").billAsked(order(69L, 501L), null)).isTrue();

        SendMessage sent = sent().getFirst();
        assertThat(sent.getText()).contains("официант не назначен").contains("в боте").contains("Сумму гостю не называли")
                .doesNotContain("₽");
        assertThat(sent.getReplyMarkup()).isNull();
    }

    @Test
    void aPaidBillHasNoCancelButton() throws Exception {
        when(bot.execute(any(SendMessage.class))).thenReturn(message(702));

        card("15").billAsked(order(68L, null), bill(68L, 251000L, GuestBillStatus.PAID));

        List<InlineKeyboardButton> row = ((InlineKeyboardMarkup) sent().getFirst().getReplyMarkup()).getKeyboard().getFirst();
        assertThat(row).extracting(InlineKeyboardButton::getText).containsExactly("Счёт");
    }

    @Test
    void theCancellationLineGoesToTheWishesTopic() throws Exception {
        when(bot.execute(any(SendMessage.class))).thenReturn(message(703));
        when(waiters.waiterName(any())).thenReturn(Optional.of("Анна"));

        assertThat(card("15").billCancelled(order(68L, null))).isTrue();

        SendMessage sent = sent().getFirst();
        assertThat(sent.getMessageThreadId()).isEqualTo(15);
        assertThat(sent.getText()).isEqualTo("Стол у окна (T5): счёт отменён · Анна");
    }

    @Test
    void withoutTheWishesTopicTheLineGoesWhereTheCardGoes() throws Exception {
        when(bot.execute(any(SendMessage.class))).thenReturn(message(704));

        assertThat(card("").billCancelled(order(68L, null))).isTrue();

        assertThat(sent().getFirst().getMessageThreadId()).isNull();
        assertThat(sent().getFirst().getText()).isEqualTo("Стол у окна (T5): счёт отменён");
    }

    @Test
    void nothingGoesWithoutTheStaffChatOrWhenTelegramFails() throws Exception {
        GuestBillStaffCard offline = new GuestBillStaffCard(provider(), true, true, "", "15");
        assertThat(offline.billAsked(order(68L, null), null)).isFalse();
        when(bot.execute(any(SendMessage.class))).thenThrow(new org.telegram.telegrambots.meta.exceptions.TelegramApiException("chat not found"));
        assertThat(card("15").billAsked(order(68L, null), null)).isFalse();
    }

    private GuestBillStaffCard card(String wishesThread) {
        GuestBillStaffCard card = new GuestBillStaffCard(provider(), true, true, STAFF_CHAT, wishesThread);
        card.setWaiters(waiters);
        return card;
    }

    @SuppressWarnings("unchecked")
    private ObjectProvider<TelegramBot> provider() {
        ObjectProvider<TelegramBot> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(bot);
        return provider;
    }

    private List<SendMessage> sent() {
        return mockingDetails(bot).getInvocations().stream()
                .flatMap(invocation -> java.util.Arrays.stream(invocation.getArguments()))
                .filter(SendMessage.class::isInstance).map(SendMessage.class::cast).toList();
    }

    private static Message message(int id) {
        Message message = new Message();
        message.setMessageId(id);
        return message;
    }

    static GuestBill bill(long reservationId, Long venueAmountMinor, GuestBillStatus status) {
        return new GuestBill(21L, null, null, "AERIS", GuestBillKind.BUSINESS_LUNCH, "CONCIERGE", reservationId, "SABY", "order-9",
                status, null, 223000L, venueAmountMinor, "RUB", null, AT, null, null, AT, AT);
    }

    static TableReservationOrder order(long id, Long chatId) {
        return new TableReservationOrder(id, chatId, chatId, null, 5L, "T5", "Стол у окна", null, null, TableReservationStatus.SEATED,
                chatId == null ? "CONCIERGE" : "TELEGRAM", AT, AT.plus(Duration.ofMinutes(90)), 2, "Яна",
                "+79120000060", null, null, null, null, null, null, null);
    }
}
