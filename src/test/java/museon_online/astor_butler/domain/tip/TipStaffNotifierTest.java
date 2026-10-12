package museon_online.astor_butler.domain.tip;

import museon_online.astor_butler.telegram.utils.TelegramBot;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.test.util.ReflectionTestUtils;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** The card the waiters get in the semi-automatic mode: who, which table, how much, and a button for "received". */
class TipStaffNotifierTest {

    private static final long CHAT = 1773317437L;

    private final TelegramBot bot = mock(TelegramBot.class);
    private TipStaffNotifier notifier;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        ObjectProvider<TelegramBot> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(bot);
        notifier = new TipStaffNotifier(provider);
        ReflectionTestUtils.setField(notifier, "telegramEnabled", true);
        ReflectionTestUtils.setField(notifier, "notificationsEnabled", true);
        ReflectionTestUtils.setField(notifier, "hostessChatId", "-100500");
        ReflectionTestUtils.setField(notifier, "managerChatId", "876857557");
    }

    @Test
    void theCardGoesToTheStaffChatWithTheReceivedButton() throws Exception {
        boolean sent = notifier.tipCard(order(), new TipGuest(CHAT, "Наталья Поединенко", "Poedinenko"), new TipVisit("5", 77L));

        assertThat(sent).isTrue();
        ArgumentCaptor<SendMessage> message = ArgumentCaptor.forClass(SendMessage.class);
        verify(bot).execute(message.capture());
        assertThat(message.getValue().getChatId()).isEqualTo("-100500");
        assertThat(message.getValue().getParseMode()).isEqualTo("HTML");
        assertThat(message.getValue().getText()).isEqualTo("""
                <b>Чаевые от гостя</b>

                <b>Гость:</b> Наталья Поединенко (@Poedinenko)
                <b>Стол:</b> 5, заявка #77
                <b>Сумма:</b> 240 ₽ — Команда AERIS

                Подойдите к гостю с QR или терминалом.""");
        List<InlineKeyboardButton> buttons = ((InlineKeyboardMarkup) message.getValue().getReplyMarkup()).getKeyboard().getFirst();
        assertThat(buttons).extracting(InlineKeyboardButton::getText).containsExactly("Чаевые получены");
        assertThat(buttons).extracting(InlineKeyboardButton::getCallbackData).containsExactly("tip_paid:55");
    }

    @Test
    void aCardWithoutATableOrUsernameSaysWhatItKnows() throws Exception {
        notifier.tipCard(order(), new TipGuest(CHAT, "Наталья", null), null);
        notifier.tipCard(order(), new TipGuest(CHAT, "Наталья", null), new TipVisit(null, 77L));

        ArgumentCaptor<SendMessage> message = ArgumentCaptor.forClass(SendMessage.class);
        verify(bot, org.mockito.Mockito.times(2)).execute(message.capture());
        assertThat(message.getAllValues().get(0).getText())
                .contains("<b>Гость:</b> Наталья\n")
                .doesNotContain("<b>Стол:</b>")
                .doesNotContain("<b>Заявка:</b>")
                .doesNotContain("(@");
        assertThat(message.getAllValues().get(1).getText()).contains("<b>Заявка:</b> #77\n").doesNotContain("<b>Стол:</b>");
    }

    @Test
    void withoutAStaffChatTheCardGoesToTheManager() throws Exception {
        ReflectionTestUtils.setField(notifier, "hostessChatId", "");

        notifier.tipCard(order(), new TipGuest(CHAT, "Наталья", null), null);

        ArgumentCaptor<SendMessage> message = ArgumentCaptor.forClass(SendMessage.class);
        verify(bot).execute(message.capture());
        assertThat(message.getValue().getChatId()).isEqualTo("876857557");
        assertThat(notifier.isStaffChat(876857557L)).isTrue();
        assertThat(notifier.isStaffChat(CHAT)).isFalse();
    }

    @Test
    void namesOfGuestsCannotBreakTheCard() throws Exception {
        notifier.tipCard(order(), new TipGuest(CHAT, "<b>Хакер</b> & Co", null), null);

        ArgumentCaptor<SendMessage> message = ArgumentCaptor.forClass(SendMessage.class);
        verify(bot).execute(message.capture());
        assertThat(message.getValue().getText()).contains("&lt;b&gt;Хакер&lt;/b&gt; &amp; Co");
    }

    @Test
    void sendsNothingWhileTelegramIsOff() throws Exception {
        ReflectionTestUtils.setField(notifier, "telegramEnabled", false);

        assertThat(notifier.tipCard(order(), new TipGuest(CHAT, "Наталья", null), null)).isFalse();
        verify(bot, never()).execute(any(SendMessage.class));
    }

    private static TipOrder order() {
        return new TipOrder(55L, CHAT, CHAT, null, "AERIS", 1L, "Команда AERIS", TipOrderStatus.AWAITING_PAYMENT, "TELEGRAM",
                24_000L, "RUB", "Наталья Поединенко", "10% от 2400 ₽", null, null,
                Instant.parse("2026-10-06T09:00:00Z"), Instant.parse("2026-10-06T09:00:00Z"));
    }
}
