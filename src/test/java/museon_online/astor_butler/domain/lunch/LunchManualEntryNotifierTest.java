package museon_online.astor_butler.domain.lunch;

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
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** One «Внести в кассу вручную» card per order, in the bookings topic, with every dish, its quantity and the total; no phone. */
class LunchManualEntryNotifierTest {

    private static final Instant START = Instant.parse("2026-10-16T09:00:00Z"); // 14:00 in Yekaterinburg
    private static final ExternalLunchOrderProvider.Result UNKNOWN =
            new ExternalLunchOrderProvider.Result(false, "SABY", "PROVIDER_RESULT_UNKNOWN", "", "Saby /update returned HTTP 500");

    private final TelegramBot bot = mock(TelegramBot.class);

    @Test
    void theCardNamesTheBookingTheTableTheGuestEveryDishAndTheTotal() throws Exception {
        assertThat(notifier("12").manualEntry(order(68L, "<Анна>", "без льда"), UNKNOWN)).isEqualTo(LunchManualEntryNotifier.Outcome.SENT);

        var card = org.mockito.ArgumentCaptor.forClass(SendMessage.class);
        verify(bot).execute(card.capture());
        assertThat(card.getValue().getChatId()).isEqualTo("-100500");
        assertThat(card.getValue().getMessageThreadId()).isEqualTo(12);
        assertThat(card.getValue().getParseMode()).isEqualTo("HTML");
        assertThat(card.getValue().getText())
                .startsWith("✍️ <b>Внести в кассу вручную · бронь #68 · стол 4 · &lt;Анна&gt;</b>\n")
                .contains("Бизнес-ланч 16.10 в 14:00 · гостей 2")
                .contains("• Вино дня белое × 1\n", "• Чай ассам × 2\n", "• Борщ со сметаной × 2\n")
                .contains("<b>Итого:</b> 1310 ₽")
                .contains("Пожелание гостя: без льда — согласовать с кухней")
                .contains("PROVIDER_RESULT_UNKNOWN")
                .doesNotContain("79990000000", "+7 999");
    }

    @Test
    void theSameOrderIsAnnouncedOnceAndAnotherOrderOfTheBookingAgain() throws Exception {
        LunchManualEntryNotifier notifier = notifier("12");

        assertThat(notifier.manualEntry(order(68L, "Анна", null), UNKNOWN)).isEqualTo(LunchManualEntryNotifier.Outcome.SENT);
        assertThat(notifier.manualEntry(order(68L, "Анна", null), UNKNOWN)).isEqualTo(LunchManualEntryNotifier.Outcome.ALREADY_SENT);
        BusinessLunchOrder more = withDishes(order(68L, "Анна", null), List.of(new BusinessLunchOrder.Item("DRINKS", "TEA_ASSAM", "Чай ассам", 3, 210)));
        assertThat(notifier.manualEntry(more, UNKNOWN)).isEqualTo(LunchManualEntryNotifier.Outcome.SENT);
        verify(bot, times(2)).execute(any(SendMessage.class));
    }

    @Test
    void aCardThatFailedToLeaveMayBeSentLater() throws Exception {
        LunchManualEntryNotifier notifier = notifier("12");
        when(bot.execute(any(SendMessage.class))).thenThrow(new TelegramApiException("429")).thenReturn(null);

        assertThat(notifier.manualEntry(order(68L, "Анна", null), UNKNOWN)).isEqualTo(LunchManualEntryNotifier.Outcome.FAILED);
        assertThat(notifier.manualEntry(order(68L, "Анна", null), UNKNOWN)).isEqualTo(LunchManualEntryNotifier.Outcome.SENT);
    }

    @Test
    void noStaffChatOrTelegramOffSendsNothing() throws Exception {
        assertThat(new LunchManualEntryNotifier(provider(), true, true, " ", "12").manualEntry(order(68L, "Анна", null), UNKNOWN))
                .isEqualTo(LunchManualEntryNotifier.Outcome.OFF);
        assertThat(new LunchManualEntryNotifier(provider(), false, true, "-100500", "12").manualEntry(order(68L, "Анна", null), UNKNOWN))
                .isEqualTo(LunchManualEntryNotifier.Outcome.OFF);
        verify(bot, never()).execute(any(SendMessage.class));
    }

    @Test
    void withoutABookingsTopicTheCardGoesToTheGroupAsTheRouterWould() throws Exception {
        notifier("").manualEntry(order(68L, "Анна", null), ExternalLunchOrderProvider.Result.manualEntry());

        var card = org.mockito.ArgumentCaptor.forClass(SendMessage.class);
        verify(bot).execute(card.capture());
        assertThat(card.getValue().getMessageThreadId()).isNull();
        assertThat(card.getValue().getText()).contains("Касса не подключена");
    }

    private LunchManualEntryNotifier notifier(String thread) {
        return new LunchManualEntryNotifier(provider(), true, true, "-100500", thread);
    }

    @SuppressWarnings("unchecked")
    private ObjectProvider<TelegramBot> provider() {
        ObjectProvider<TelegramBot> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(bot);
        return provider;
    }

    private static BusinessLunchOrder order(long reservation, String guest, String wishes) {
        return new BusinessLunchOrder("AERIS", reservation, "4", START, START.plusSeconds(5400), 2, null, null, null, List.of(
                new BusinessLunchOrder.Item("DRINKS", "WINE_OF_THE_DAY_WHITE", "Вино дня белое", 1, 350, "Вино дня белое 125 мл"),
                new BusinessLunchOrder.Item("DRINKS", "TEA_ASSAM", "Чай ассам", 2, 210, "Ассам 500 мл"),
                new BusinessLunchOrder.Item("SOUP", "BORSCHT", "Борщ со сметаной", 2, 270)),
                1310, guest, "+7 999 000-00-00", null, "TELEGRAM", null, "43bd679d", wishes);
    }

    private static BusinessLunchOrder withDishes(BusinessLunchOrder o, List<BusinessLunchOrder.Item> dishes) {
        return new BusinessLunchOrder(o.venueCode(), o.tableReservationId(), o.tableCode(), o.startAt(), o.endAt(), o.guests(), o.setCode(),
                o.setTitle(), o.setPriceRub(), dishes, 630, o.guestName(), o.guestPhone(), o.comment(), o.source(), o.conciergeRequestId(),
                o.externalReservationId(), o.wishes());
    }
}
