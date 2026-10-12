package museon_online.astor_butler.domain.billing;

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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** What the guest sees after the stars: the comment is offered until the guest asks to write one. */
class GuestBillNotifierTest {

    private static final long CHAT = 1773317437L;
    private static final Instant NOW = Instant.parse("2026-10-06T12:00:00Z");

    private final TelegramBot bot = mock(TelegramBot.class);
    private GuestBillNotifier notifier;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        ObjectProvider<TelegramBot> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(bot);
        notifier = new GuestBillNotifier(provider);
        ReflectionTestUtils.setField(notifier, "telegramEnabled", true);
        ReflectionTestUtils.setField(notifier, "notificationsEnabled", true);
    }

    @Test
    void offersACommentAfterTheStarsUntilTheGuestAsksToWriteOne() throws Exception {
        notifier.reviewThanks(bill(), review(4, null), true);
        notifier.reviewThanks(bill(), review(4, VisitReview.Thumb.UP), false);

        ArgumentCaptor<SendMessage> sent = ArgumentCaptor.forClass(SendMessage.class);
        verify(bot, times(2)).execute(sent.capture());
        assertThat(buttons(sent.getAllValues().get(0)))
                .extracting(InlineKeyboardButton::getText)
                .containsExactly("👍", "👎", "Добавить комментарий", "Оставить чаевые");
        assertThat(buttons(sent.getAllValues().get(0)))
                .extracting(InlineKeyboardButton::getCallbackData)
                .contains("visit_review:12:comment");
        assertThat(buttons(sent.getAllValues().get(1)))
                .extracting(InlineKeyboardButton::getText)
                .containsExactly("Оставить чаевые");
    }

    private static List<InlineKeyboardButton> buttons(SendMessage message) {
        return ((InlineKeyboardMarkup) message.getReplyMarkup()).getKeyboard().stream().flatMap(List::stream).toList();
    }

    private static GuestBill bill() {
        return new GuestBill(12L, CHAT, CHAT, "AERIS", GuestBillKind.BUSINESS_LUNCH, "DIRECT", 77L, null, null,
                GuestBillStatus.ESTIMATED, BillPayState.NOT_REPORTED, 54_000L, null, "RUB", "test",
                NOW, null, null, NOW, NOW);
    }

    private static VisitReview review(int stars, VisitReview.Thumb thumb) {
        return new VisitReview(3L, 12L, CHAT, NOW, stars, thumb, null, NOW, NOW, NOW);
    }
}
