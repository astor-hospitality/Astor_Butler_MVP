package museon_online.astor_butler.domain.journey.talk;

import museon_online.astor_butler.domain.booking.TableReservationNotificationService;
import museon_online.astor_butler.domain.booking.TableReservationOrder;
import museon_online.astor_butler.domain.booking.TableReservationStatus;
import museon_online.astor_butler.telegram.utils.TelegramBot;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** Table requests land in the «Пожелания» topic addressed to the waiter; without the topic, the old card. */
class TableRequestCardsTest {

    private static final String STAFF_CHAT = "-1002768239841";
    private static final Instant NOW = Instant.parse("2026-10-16T08:30:00Z");

    private final TelegramBot bot = mock(TelegramBot.class);
    private final TableReservationNotificationService notifications = mock(TableReservationNotificationService.class);

    @Test
    void theCardGoesToTheWishesTopicAddressedToTheWaiter() {
        cards("15").send(order("5"), TableRequest.WATER, "можно воды, пожалуйста", new TableRequestRouter.Addressee("Даниил", "WINDOW"));

        SendMessage card = sentOne();
        assertThat(card.getChatId()).isEqualTo(STAFF_CHAT);
        assertThat(card.getMessageThreadId()).isEqualTo(15);
        assertThat(card.getParseMode()).isEqualTo("HTML");
        assertThat(card.getText())
                .startsWith("🙋 <b>Стол 5 · Даниил · воду</b>")
                .contains("Гость написал: «можно воды, пожалуйста»")
                .contains("Бронь #42")
                .doesNotContain("Мария")
                .doesNotContain("+79120000000")
                .doesNotContain("кто возьмёт");
        verifyNoInteractions(notifications);
    }

    @Test
    void nobodyInTheZoneAsksTheTeamWhoTakesIt() {
        cards("15").send(order("19"), TableRequest.WAITER, "официанта", TableRequestRouter.Addressee.team("CORNER"));

        assertThat(sentOne().getText())
                .startsWith("🙋 <b>Стол 19 · команде · официанта</b>")
                .contains("нет официанта в зоне CORNER — кто возьмёт?")
                // the agent passes the request's own word as the text: no echo of it
                .doesNotContain("Гость написал");
    }

    @Test
    void theTakeawayPackingRequestReadsAsSuch() {
        cards("15").send(order("BAR"), TableRequest.TAKEAWAY, "упакуйте остатки с собой", new TableRequestRouter.Addressee("Анна", "BAR"));

        assertThat(sentOne().getText())
                .startsWith("🙋 <b>Барная стойка · Анна · упаковать с собой</b>")
                .contains("«упакуйте остатки с собой»");
    }

    @Test
    void aTableTheZonesDoNotKnowStillAsksWhoTakesIt() {
        assertThat(TableRequestCards.nobody(null)).isEqualTo("официант не назначен — кто возьмёт?");
    }

    @Test
    void withoutTheWishesTopicTheOldCardGoesToTheBookingsTopic() {
        TableReservationOrder order = order("5");

        cards("").send(order, TableRequest.BREAD, "можно хлеба", TableRequestRouter.Addressee.team("WINDOW"));

        verifyNoInteractions(bot);
        verify(notifications).notifyStaffGuestRequest(order, TableRequest.BREAD, "можно хлеба",
                "нет официанта в зоне WINDOW — кто возьмёт?");
    }

    @Test
    void aFailedSendFallsBackToTheBookingsTopic() throws Exception {
        when(bot.execute(any(SendMessage.class))).thenThrow(new TelegramApiException("Bad Request: message thread not found"));
        TableReservationOrder order = order("5");

        cards("15").send(order, TableRequest.WATER, "воду", new TableRequestRouter.Addressee("Даниил", "WINDOW"));

        verify(notifications).notifyStaffGuestRequest(order, TableRequest.WATER, "воду", "Даниил");
    }

    @Test
    void telegramOffOrNoStaffChatIsTheOldWay() {
        TableReservationOrder order = order("5");
        new TableRequestCards(provider(), notifications, false, true, STAFF_CHAT, "15")
                .send(order, TableRequest.WATER, "воду", new TableRequestRouter.Addressee("Даниил", "WINDOW"));
        new TableRequestCards(provider(), notifications, true, true, "", "15")
                .send(order, TableRequest.WATER, "воду", new TableRequestRouter.Addressee("Даниил", "WINDOW"));

        verifyNoInteractions(bot);
        verify(notifications, org.mockito.Mockito.times(2)).notifyStaffGuestRequest(order, TableRequest.WATER, "воду", "Даниил");
    }

    private TableRequestCards cards(String threadId) {
        return new TableRequestCards(provider(), notifications, true, true, STAFF_CHAT, threadId);
    }

    @SuppressWarnings("unchecked")
    private ObjectProvider<TelegramBot> provider() {
        ObjectProvider<TelegramBot> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(bot);
        return provider;
    }

    private SendMessage sentOne() {
        List<SendMessage> sent = mockingDetails(bot).getInvocations().stream()
                .filter(invocation -> invocation.getMethod().getName().equals("execute"))
                .map(invocation -> invocation.getArgument(0))
                .filter(SendMessage.class::isInstance)
                .map(SendMessage.class::cast)
                .toList();
        assertThat(sent).hasSize(1);
        return sent.get(0);
    }

    private static TableReservationOrder order(String tableCode) {
        return new TableReservationOrder(42L, 421L, 421L, null, 5L, tableCode, "Стол " + tableCode, null, null,
                TableReservationStatus.SEATED, "TELEGRAM", NOW, NOW.plusSeconds(5400), 2, "Мария", "+79120000000", null,
                null, null, null, null, NOW, NOW, NOW);
    }
}
