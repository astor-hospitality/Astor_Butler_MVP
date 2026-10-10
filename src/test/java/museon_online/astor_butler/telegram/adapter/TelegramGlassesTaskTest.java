package museon_online.astor_butler.telegram.adapter;

import museon_online.astor_butler.telegram.utils.TelegramBot;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.test.util.ReflectionTestUtils;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class TelegramGlassesTaskTest {
    @Test void theCardNamesWhoGaveTheTaskWhatItIsTheTableAndWhoItIsFor() throws Exception {
        var bot = mock(TelegramBot.class);
        assertThat(enabled(bot).sendGlassesTask("Марина", "Принести воду", "Принести воду на пятый стол", "5", "Анна")).isTrue();
        var message = ArgumentCaptor.forClass(SendMessage.class);
        verify(bot).execute(message.capture());
        assertThat(message.getValue().getChatId()).isEqualTo("unit-system-chat");
        assertThat(message.getValue().getParseMode()).isNull();
        assertThat(message.getValue().getText())
                .isEqualTo("Поручение от Марина: Принести воду — Принести воду на пятый стол. Стол 5. Для: Анна");
    }

    @Test void aTaskWithoutATableOrASeparateInstructionReadsWithoutGaps() throws Exception {
        var bot = mock(TelegramBot.class);
        assertThat(enabled(bot).sendGlassesTask("Марина", "Принести воду", "Принести воду", null, "Марина")).isTrue();
        var message = ArgumentCaptor.forClass(SendMessage.class);
        verify(bot).execute(message.capture());
        assertThat(message.getValue().getText()).isEqualTo("Поручение от Марина: Принести воду. Для: Марина");
    }

    @Test void nothingIsSentWhileNotificationsAreOffOrTheBotIsAway() throws Exception {
        var bot = mock(TelegramBot.class);
        var notifier = enabled(bot);
        ReflectionTestUtils.setField(notifier, "notificationsEnabled", false);
        assertThat(notifier.sendGlassesTask("Марина", "Принести воду", "", "5", "Анна")).isFalse();
        verifyNoInteractions(bot);
        var failing = enabled(bot);
        when(bot.execute(any(SendMessage.class))).thenThrow(new RuntimeException("telegram is away"));
        assertThat(failing.sendGlassesTask("Марина", "Принести воду", "", "5", "Анна")).isFalse();
    }

    @SuppressWarnings("unchecked")
    private static TelegramSystemNotifier enabled(TelegramBot bot) {
        ObjectProvider<TelegramBot> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(bot);
        var notifier = new TelegramSystemNotifier(provider);
        ReflectionTestUtils.setField(notifier, "telegramEnabled", true);
        ReflectionTestUtils.setField(notifier, "notificationsEnabled", true);
        ReflectionTestUtils.setField(notifier, "systemChatId", "unit-system-chat");
        return notifier;
    }
}
