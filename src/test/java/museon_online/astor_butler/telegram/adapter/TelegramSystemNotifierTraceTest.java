package museon_online.astor_butler.telegram.adapter;

import museon_online.astor_butler.fsm.core.BotState;
import museon_online.astor_butler.service.message.IncomingMessage;
import museon_online.astor_butler.service.message.OutgoingMessage;
import museon_online.astor_butler.telegram.utils.TelegramBot;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.test.util.ReflectionTestUtils;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** The system chat gets useful notices; the per-message trace is off unless asked for, and never for group chats. */
class TelegramSystemNotifierTraceTest {

    private TelegramBot bot;
    private TelegramSystemNotifier notifier;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        bot = mock(TelegramBot.class);
        ObjectProvider<TelegramBot> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(bot);
        notifier = new TelegramSystemNotifier(provider);
        ReflectionTestUtils.setField(notifier, "telegramEnabled", true);
        ReflectionTestUtils.setField(notifier, "notificationsEnabled", true);
        ReflectionTestUtils.setField(notifier, "systemChatId", "-1001");
    }

    @Test
    void traceIsOffByDefaultWhileNoticesStillGo() throws Exception {
        notifier.sendTransition(privateMessage(), BotState.UNKNOWN, reply(privateMessage()), true);
        verify(bot, never()).execute(any(SendMessage.class));

        assertThat(notifier.sendSystemNotice("Стол 4 просит счёт")).isTrue();
        verify(bot, times(1)).execute(any(SendMessage.class));
    }

    @Test
    void traceNeverCopiesGroupChatMessages() throws Exception {
        ReflectionTestUtils.setField(notifier, "traceEnabled", true);
        IncomingMessage group = IncomingMessage.telegram(-1003722769239L, 5L, 1, 1, "Команды, привет!", null, null, null,
                null, null, false, "c", null);

        notifier.sendTransition(group, BotState.UNKNOWN, reply(group), true);

        verify(bot, never()).execute(any(SendMessage.class));
    }

    @Test
    void traceForPrivateChatWhenEnabled() throws Exception {
        ReflectionTestUtils.setField(notifier, "traceEnabled", true);

        notifier.sendTransition(privateMessage(), BotState.UNKNOWN, reply(privateMessage()), true);

        verify(bot, times(1)).execute(any(SendMessage.class));
    }

    private static IncomingMessage privateMessage() {
        return IncomingMessage.telegram(421L, 421L, 1, 1, "привет", null, null, null, null, null, false, "c", null);
    }

    private static OutgoingMessage reply(IncomingMessage incoming) {
        return OutgoingMessage.of(incoming, "ok", "UNKNOWN", false, false, false, false, null, List.of());
    }
}
