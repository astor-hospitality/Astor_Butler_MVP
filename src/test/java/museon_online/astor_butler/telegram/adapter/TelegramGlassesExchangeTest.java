package museon_online.astor_butler.telegram.adapter;

import museon_online.astor_butler.telegram.utils.TelegramBot;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.test.util.ReflectionTestUtils;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.methods.send.SendPhoto;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class TelegramGlassesExchangeTest {
    @Test void longPlainTextIsSplitWithoutLosingCharactersOrSplittingEmoji() throws Exception {
        var bot = mock(TelegramBot.class);
        var notifier = enabled(bot);
        String question = "<>&🙂".repeat(700), answer = "ответ🙂".repeat(600);
        assertThat(notifier.sendGlassesExchange("alice", "SHIFT_ASSIST", question, answer, null, null)).isTrue();
        var messages = ArgumentCaptor.forClass(SendMessage.class);
        verify(bot, atLeast(2)).execute(messages.capture());
        StringBuilder joined = new StringBuilder();
        for (var message : messages.getAllValues()) {
            assertThat(message.getText()).hasSizeLessThanOrEqualTo(4096);
            assertThat(message.getParseMode()).isNull();
            assertThat(Character.isHighSurrogate(message.getText().charAt(message.getText().length() - 1))).isFalse();
            joined.append(message.getText());
        }
        assertThat(joined.toString()).isEqualTo("Astor Glass · alice · SHIFT_ASSIST\n\nСотрудник\n" + question
                + "\n\nАстор\n" + answer);
    }
    @Test void longPhotoCaptionUsesShortHeaderAndKeepsCompleteExchangeInMessages() throws Exception {
        var bot = mock(TelegramBot.class);
        var notifier = enabled(bot);
        String answer = "<>&".repeat(1500);
        assertThat(notifier.sendGlassesExchange("alice", null, "question", answer, new byte[]{1, 2}, "photo.jpg")).isTrue();
        var photo = ArgumentCaptor.forClass(SendPhoto.class);
        verify(bot).execute(photo.capture());
        assertThat(photo.getValue().getCaption()).isEqualTo("Astor Glass · alice");
        assertThat(photo.getValue().getParseMode()).isNull();
        var messages = ArgumentCaptor.forClass(SendMessage.class);
        verify(bot, atLeast(2)).execute(messages.capture());
        assertThat(messages.getAllValues().stream().map(SendMessage::getText).collect(java.util.stream.Collectors.joining()))
                .isEqualTo("Astor Glass · alice\n\nСотрудник\nquestion\n\nАстор\n" + answer);
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
