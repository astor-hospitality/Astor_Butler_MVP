package museon_online.astor_butler.domain.billing;

import museon_online.astor_butler.domain.booking.TableReservationOrder;
import museon_online.astor_butler.domain.booking.TableReservationRepository;
import museon_online.astor_butler.domain.booking.TableReservationStatus;
import museon_online.astor_butler.telegram.utils.TelegramBot;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.EditMessageText;
import org.telegram.telegrambots.meta.api.objects.Message;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** The guest's review reaches the team in the reviews topic of the staff group, without the guest's phone. */
class VisitReviewStaffCardTest {

    private static final String STAFF_CHAT = "-1002768239841";
    private static final Instant VISIT = Instant.parse("2026-10-16T08:30:00Z"); // 13:30 in Yekaterinburg

    private final TelegramBot bot = mock(TelegramBot.class);
    private final TableReservationRepository reservations = mock(TableReservationRepository.class);

    @Test
    void publishesTheStarsToTheReviewsTopicWithoutThePhone() throws Exception {
        when(reservations.findOrder(41L)).thenReturn(Optional.of(order("Ирина")));
        when(bot.execute(any(SendMessage.class))).thenReturn(message(901));

        assertThat(card("340").publish(bill(41L), review(4, null), "Очень вкусно, перезвоните 8 912 000-00-60")).isTrue();

        SendMessage sent = sentMessage();
        assertThat(sent.getChatId()).isEqualTo(STAFF_CHAT);
        assertThat(sent.getMessageThreadId()).isEqualTo(340);
        assertThat(sent.getParseMode()).isEqualTo("HTML");
        assertThat(sent.getText())
                .contains("Отзыв гостя")
                .contains("★★★★☆ 4/5")
                .contains("<b>Понравилось:</b> —")
                .contains("Очень вкусно")
                .contains("<b>Бронь:</b> #41")
                .contains("<b>Гость:</b> Ирина")
                .contains("<b>Стол:</b> Стол 5 (5)")
                .contains("<b>Дата визита:</b> 16.10.2026 13:30")
                .doesNotContain("+79120000060")
                .doesNotContain("000-00-60")
                .doesNotContain("79991234567")
                .doesNotContain("Телефон");
    }

    @Test
    void theThumbUpdatesTheSameCardAndKeepsTheWords() throws Exception {
        when(reservations.findOrder(41L)).thenReturn(Optional.of(order("Ирина")));
        when(bot.execute(any(SendMessage.class))).thenReturn(message(901));
        VisitReviewStaffCard card = card("340");

        card.publish(bill(41L), review(5, null), "Спасибо повару");
        card.publish(bill(41L), review(5, VisitReview.Thumb.UP), null);

        assertThat(sent(SendMessage.class)).hasSize(1);
        EditMessageText edit = sent(EditMessageText.class).getFirst();
        assertThat(sent(EditMessageText.class)).hasSize(1);
        assertThat(edit.getChatId()).isEqualTo(STAFF_CHAT);
        assertThat(edit.getMessageId()).isEqualTo(901);
        assertThat(edit.getText()).contains("★★★★★ 5/5").contains("👍 да").contains("Спасибо повару");
    }

    @Test
    void aCardThatCannotBeUpdatedIsPostedAnew() throws Exception {
        when(reservations.findOrder(41L)).thenReturn(Optional.of(order("Ирина")));
        when(bot.execute(any(SendMessage.class))).thenReturn(message(901), message(902));
        when(bot.execute(any(EditMessageText.class))).thenThrow(new TelegramApiException("message to edit not found"));
        VisitReviewStaffCard card = card("340");

        card.publish(bill(41L), review(3, null), null);
        assertThat(card.publish(bill(41L), review(3, VisitReview.Thumb.DOWN), null)).isTrue();

        List<SendMessage> sent = sent(SendMessage.class);
        assertThat(sent).hasSize(2);
        assertThat(sent.get(1).getText()).contains("👎 нет");
        assertThat(sent.get(1).getMessageThreadId()).isEqualTo(340);
    }

    @Test
    void aBillWithoutABookingStillReachesTheTeam() throws Exception {
        GuestBill bill = new GuestBill(7L, 1001L, 1001L, "AERIS", GuestBillKind.BUSINESS_LUNCH, "DIRECT", null, null, null,
                GuestBillStatus.ISSUED, null, 54000L, null, "RUB", "menu", VISIT, null, null, VISIT, VISIT);

        assertThat(card("340").publish(bill, review(2, null), null)).isTrue();

        assertThat(sentMessage().getText()).contains("<b>Заказ:</b> #B7").contains("<b>Дата визита:</b> 16.10.2026")
                .doesNotContain("<b>Стол:</b>").doesNotContain("<b>Комментарий:</b>");
        verifyNoInteractions(reservations);
    }

    @Test
    void theNameIsTheOneOnTheHostessCard() throws Exception {
        when(reservations.findOrder(41L)).thenReturn(Optional.of(order(" ")));

        card("340").publish(bill(41L), review(5, null), null);

        assertThat(sentMessage().getText()).contains("<b>Гость:</b> гость из Telegram");
    }

    @Test
    void publishesNothingWithoutTheTopicTheChatOrTheBot() throws Exception {
        assertThat(card("").publish(bill(41L), review(5, null), null)).isFalse();
        assertThat(card(null).publish(bill(41L), review(5, null), null)).isFalse();
        assertThat(card("not-a-topic").publish(bill(41L), review(5, null), null)).isFalse();
        assertThat(new VisitReviewStaffCard(provider(), reservations, true, true, "", "340").publish(bill(41L), review(5, null), null)).isFalse();
        assertThat(new VisitReviewStaffCard(provider(), reservations, false, true, STAFF_CHAT, "340").publish(bill(41L), review(5, null), null)).isFalse();
        assertThat(new VisitReviewStaffCard(provider(), reservations, true, false, STAFF_CHAT, "340").publish(bill(41L), review(5, null), null)).isFalse();

        assertThat(sent(SendMessage.class)).isEmpty();
    }

    @Test
    void aTelegramFailureIsReportedNotThrown() throws Exception {
        when(bot.execute(any(SendMessage.class))).thenThrow(new TelegramApiException("chat not found"));

        assertThat(card("340").publish(bill(41L), review(5, null), null)).isFalse();
    }

    private VisitReviewStaffCard card(String threadId) {
        return new VisitReviewStaffCard(provider(), reservations, true, true, STAFF_CHAT, threadId);
    }

    @SuppressWarnings("unchecked")
    private ObjectProvider<TelegramBot> provider() {
        ObjectProvider<TelegramBot> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(bot);
        return provider;
    }

    private SendMessage sentMessage() {
        List<SendMessage> sent = sent(SendMessage.class);
        assertThat(sent).hasSize(1);
        return sent.getFirst();
    }

    /** What the bot was asked to send, of one type: SendMessage and EditMessageText share the generic execute. */
    private <T> List<T> sent(Class<T> type) {
        return mockingDetails(bot).getInvocations().stream()
                .filter(invocation -> invocation.getMethod().getName().equals("execute"))
                .map(invocation -> invocation.getArgument(0))
                .filter(type::isInstance)
                .map(type::cast)
                .toList();
    }

    private static Message message(int id) {
        Message message = new Message();
        message.setMessageId(id);
        return message;
    }

    private static GuestBill bill(long reservationId) {
        return new GuestBill(7L, 1001L, 1001L, "AERIS", GuestBillKind.BUSINESS_LUNCH, "DIRECT", reservationId, null, null,
                GuestBillStatus.ISSUED, null, 54000L, null, "RUB", "menu", VISIT.plusSeconds(3600), null, null, VISIT, VISIT);
    }

    private static VisitReview review(Integer stars, VisitReview.Thumb thumb) {
        return new VisitReview(3L, 7L, 1001L, VISIT, stars, thumb, null, VISIT, VISIT, VISIT);
    }

    private static TableReservationOrder order(String guestName) {
        return new TableReservationOrder(41L, 1001L, 1001L, null, 5L, "5", "Table 5", "WINDOW", null,
                TableReservationStatus.CONFIRMED, "TELEGRAM", VISIT, VISIT.plusSeconds(7200), 2, guestName, "+79991234567",
                null, null, null, STAFF_CHAT, null, VISIT, VISIT);
    }
}
