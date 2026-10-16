package museon_online.astor_butler.domain.booking;

import museon_online.astor_butler.domain.lunch.BusinessLunchService;
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
import static org.mockito.Mockito.when;

/** The guest's wishes go to the wishes topic as a short note of their own: no phone, no name, once per wish. */
class GuestWishNotifierTest {

    private static final String STAFF_CHAT = "-1002768239841";
    private static final Instant START = Instant.parse("2026-10-16T08:30:00Z"); // 13:30 in Yekaterinburg

    private final TelegramBot bot = mock(TelegramBot.class);

    @Test
    void aTableWishGoesToTheWishesTopicWithoutPhoneOrName() {
        assertThat(notifier("15").onBooking(order(41L, "у окна, будет детский стул", null))).isTrue();

        SendMessage note = sentOne();
        assertThat(note.getChatId()).isEqualTo(STAFF_CHAT);
        assertThat(note.getMessageThreadId()).isEqualTo(15);
        assertThat(note.getParseMode()).isEqualTo("HTML");
        assertThat(note.getText())
                .contains("Пожелание гостя</b> — бронь #41")
                .contains("<b>Когда:</b> 16.10.2026 13:30–15:30")
                .contains("<b>Стол:</b> Стол 5 (5)")
                .contains("<b>Гостей:</b> 2")
                .contains("<blockquote>у окна, будет детский стул</blockquote>")
                .doesNotContain("+79991234567")
                .doesNotContain("Ирина");
    }

    @Test
    void theNotesAmongTheGuestsWordsAndTheLunchWishAreRead() {
        TableReservationOrder table = order(41L, null,
                "стол на завтра на двоих | Пожелание: без лука | Пожелание: у нас годовщина, позвоните 8 912 000-00-60");
        TableReservationOrder lunch = order(42L, BusinessLunchService.SEATING_LABEL,
                "Бизнес-ланч: 2 × «Сет 1», 540 ₽. Борщ, Котлета. " + BusinessLunchService.wishLine("аллергия на орехи")
                        + ". Пожелание: принести побыстрее. В Saby внести вручную.");

        assertThat(GuestWishNotifier.wishes(table)).containsExactly("без лука", "у нас годовщина, позвоните 8 912 000-00-60");
        assertThat(GuestWishNotifier.wishes(lunch)).containsExactly("аллергия на орехи", "принести побыстрее");

        notifier("15").onBooking(table);
        notifier("15").onBooking(lunch);
        List<SendMessage> sent = sent();
        assertThat(sent.get(0).getText()).contains("без лука; у нас годовщина, позвоните +7 912 ***-**-60")
                .doesNotContain("000-00-60");
        assertThat(sent.get(1).getText()).contains("бронь #42, бизнес-ланч").contains("аллергия на орехи; принести побыстрее");
    }

    @Test
    void noWishNoNote() {
        GuestWishNotifier notifier = notifier("15");

        assertThat(notifier.onBooking(order(41L, null, "стол на завтра на двоих"))).isFalse();
        assertThat(notifier.onBooking(order(42L, "Подбери сам", null))).isFalse();
        assertThat(notifier.onBooking(order(43L, BusinessLunchService.SEATING_LABEL, "Бизнес-ланч: 2 × «Сет 1». Борщ."))).isFalse();
        assertThat(sent()).isEmpty();
    }

    @Test
    void theSameWishIsPostedOnceAndAChangedOneAgain() {
        GuestWishNotifier notifier = notifier("15");

        assertThat(notifier.onBooking(order(41L, "у окна", null))).isTrue();
        assertThat(notifier.onBooking(order(41L, "у окна", null))).isFalse(); // the guest moved the time: same wish
        assertThat(notifier.onBooking(order(41L, "у окна, без лука", null))).isTrue();

        List<SendMessage> sent = sent();
        assertThat(sent).hasSize(2);
        assertThat(sent.get(1).getText()).contains("Пожелание изменилось").contains("у окна, без лука");
    }

    @Test
    void nothingIsPostedWithoutTheTopicTheChatOrTheBot() {
        TableReservationOrder order = order(41L, "у окна", null);

        assertThat(notifier("").onBooking(order)).isFalse();
        assertThat(notifier(null).onBooking(order)).isFalse();
        assertThat(new GuestWishNotifier(provider(), true, true, "", "15").onBooking(order)).isFalse();
        assertThat(new GuestWishNotifier(provider(), false, true, STAFF_CHAT, "15").onBooking(order)).isFalse();
        assertThat(new GuestWishNotifier(provider(), true, false, STAFF_CHAT, "15").onBooking(order)).isFalse();
        assertThat(sent()).isEmpty();
    }

    @Test
    void aTelegramFailureIsReportedAndTheWishTriedAgainLater() throws Exception {
        when(bot.execute(any(SendMessage.class))).thenThrow(new TelegramApiException("chat not found")).thenReturn(null);
        GuestWishNotifier notifier = notifier("15");

        assertThat(notifier.onBooking(order(41L, "у окна", null))).isFalse();
        assertThat(notifier.onBooking(order(41L, "у окна", null))).isTrue();
    }

    private GuestWishNotifier notifier(String threadId) {
        return new GuestWishNotifier(provider(), true, true, STAFF_CHAT, threadId);
    }

    @SuppressWarnings("unchecked")
    private ObjectProvider<TelegramBot> provider() {
        ObjectProvider<TelegramBot> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(bot);
        return provider;
    }

    private SendMessage sentOne() {
        List<SendMessage> sent = sent();
        assertThat(sent).hasSize(1);
        return sent.getFirst();
    }

    private List<SendMessage> sent() {
        return mockingDetails(bot).getInvocations().stream()
                .filter(invocation -> invocation.getMethod().getName().equals("execute"))
                .map(invocation -> invocation.getArgument(0))
                .filter(SendMessage.class::isInstance)
                .map(SendMessage.class::cast)
                .toList();
    }

    private static TableReservationOrder order(Long id, String seatingPreference, String guestComment) {
        return new TableReservationOrder(id, 1773317437L, 1773317437L, null, 5L, "5", "Table 5", null, seatingPreference,
                TableReservationStatus.AWAITING_MANAGER_CONFIRMATION, "TELEGRAM", START, START.plusSeconds(7200), 2, "Ирина",
                "+79991234567", guestComment, null, null, STAFF_CHAT, null, START, START);
    }
}
