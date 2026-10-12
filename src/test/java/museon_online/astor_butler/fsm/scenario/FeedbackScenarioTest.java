package museon_online.astor_butler.fsm.scenario;

import museon_online.astor_butler.domain.billing.VisitReviewService;
import museon_online.astor_butler.domain.feedback.FeedbackPriority;
import museon_online.astor_butler.domain.feedback.FeedbackSentiment;
import museon_online.astor_butler.domain.feedback.FeedbackService;
import museon_online.astor_butler.domain.feedback.FeedbackStatus;
import museon_online.astor_butler.domain.feedback.FeedbackType;
import museon_online.astor_butler.domain.feedback.GuestFeedback;
import museon_online.astor_butler.domain.feedback.GuestFeedbackCommand;
import museon_online.astor_butler.fsm.core.BotState;
import museon_online.astor_butler.fsm.storage.FSMStorage;
import museon_online.astor_butler.service.message.IncomingMessage;
import museon_online.astor_butler.service.message.OutgoingMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class FeedbackScenarioTest {

    @Mock
    private FSMStorage fsmStorage;

    @Mock
    private FeedbackService feedbackService;

    private FeedbackScenario scenario;

    @BeforeEach
    void setUp() {
        scenario = new FeedbackScenario(fsmStorage, feedbackService);
        ReflectionTestUtils.setField(scenario, "adminChatId", "100500");
    }

    @Test
    void asksForFeedbackTextWhenGuestOnlyStartsFeedback() {
        IncomingMessage incoming = telegram("оставить отзыв");

        OutgoingMessage outgoing = scenario.handle(incoming, BotState.READY_FOR_DIALOG, incoming.text());

        assertThat(outgoing.nextState()).isEqualTo(BotState.FEEDBACK_COLLECT_TEXT.name());
        assertThat(outgoing.adminAlert().required()).isFalse();
        assertThat(outgoing.actions()).containsExactly("FEEDBACK", "ASK_FEEDBACK_TEXT");
        verify(fsmStorage).setState(incoming.chatId(), BotState.FEEDBACK_COLLECT_TEXT);
    }

    @Test
    void sendsAdminAlertWhenFeedbackTextWasCollected() {
        IncomingMessage incoming = telegram("Очень понравился сервис и винная карта");
        when(feedbackService.create(any(GuestFeedbackCommand.class))).thenReturn(feedback());

        OutgoingMessage outgoing = scenario.handle(incoming, BotState.FEEDBACK_COLLECT_TEXT, incoming.text());

        assertThat(outgoing.nextState()).isEqualTo(BotState.READY_FOR_DIALOG.name());
        assertThat(outgoing.adminAlert().required()).isTrue();
        assertThat(outgoing.adminAlert().chatId()).isEqualTo("100500");
        assertThat(outgoing.adminAlert().text()).contains(
                "Astor Butler / feedback",
                "Наталья Поединенко",
                "Очень понравился сервис",
                "Feedback: #88",
                "Type: PRAISE",
                "Sentiment: POSITIVE",
                "Priority: NORMAL"
        );
        assertThat(outgoing.metadata()).containsEntry("feedbackId", 88L);
        assertThat(outgoing.metadata()).containsEntry("feedbackType", "PRAISE");
        assertThat(outgoing.metadata()).containsEntry("sentiment", "POSITIVE");
        assertThat(outgoing.actions()).containsExactly("FEEDBACK", "FEEDBACK_TEXT_RECEIVED", "ADMIN_ALERT", "RETURN_MAIN_MENU");
        verify(fsmStorage).setState(incoming.chatId(), BotState.READY_FOR_DIALOG);
    }

    @Test
    void aCommentAfterTheStarsReachesTheTeamWithTheRatedVisit() {
        VisitReviewService visitReviews = mock(VisitReviewService.class);
        ReflectionTestUtils.setField(scenario, "visitReviews", visitReviews);
        IncomingMessage incoming = telegram("Вкусно, но горячее ждали долго");
        when(feedbackService.create(any(GuestFeedbackCommand.class))).thenReturn(feedback());
        when(visitReviews.attachComment(incoming.chatId(), 88L)).thenReturn(Optional.of(new VisitReviewService.CommentLink(12L, 77L, 4, true)));

        OutgoingMessage outgoing = scenario.handle(incoming, BotState.FEEDBACK_COLLECT_TEXT, incoming.text());

        assertThat(outgoing.adminAlert().text()).contains("Комментарий к оценке визита: ★★★★☆, заявка #77");
        assertThat(outgoing.metadata()).containsEntry("visitReviewBillId", 12L);
        // The tip button stayed on the message before the comment; after it the tip is offered again, without pressure.
        assertThat(outgoing.text()).isEqualTo("""
                Спасибо, передал отзыв команде. Я остаюсь на связи: можно попросить меню, бронь, видео-тур или менеджера.

                Если захотите поблагодарить команду, можно оставить чаевые.""");
        assertThat(outgoing.metadata()).containsEntry("replyKeyboardRows", List.of(List.of("Оставить чаевые"), List.of("Главное меню")));
        assertThat(outgoing.actions()).contains("TIP_OFFERED");
    }

    @Test
    void theTipIsNotOfferedAgainOnceTheGuestOpenedIt() {
        VisitReviewService visitReviews = mock(VisitReviewService.class);
        ReflectionTestUtils.setField(scenario, "visitReviews", visitReviews);
        IncomingMessage incoming = telegram("Вкусно, но горячее ждали долго");
        when(feedbackService.create(any(GuestFeedbackCommand.class))).thenReturn(feedback());
        when(visitReviews.attachComment(incoming.chatId(), 88L)).thenReturn(Optional.of(new VisitReviewService.CommentLink(12L, 77L, 4, false)));

        OutgoingMessage outgoing = scenario.handle(incoming, BotState.FEEDBACK_COLLECT_TEXT, incoming.text());

        assertThat(outgoing.text()).doesNotContain("чаевые");
        assertThat(outgoing.metadata()).doesNotContainKey("replyKeyboardRows");
        assertThat(outgoing.actions()).doesNotContain("TIP_OFFERED");
    }

    @Test
    void feedbackNotTiedToAVisitSaysNothingAboutStars() {
        VisitReviewService visitReviews = mock(VisitReviewService.class);
        ReflectionTestUtils.setField(scenario, "visitReviews", visitReviews);
        IncomingMessage incoming = telegram("Очень понравился сервис и винная карта");
        when(feedbackService.create(any(GuestFeedbackCommand.class))).thenReturn(feedback());
        when(visitReviews.attachComment(incoming.chatId(), 88L)).thenThrow(new IllegalStateException("database is down"));

        OutgoingMessage outgoing = scenario.handle(incoming, BotState.FEEDBACK_COLLECT_TEXT, incoming.text());

        assertThat(outgoing.adminAlert().required()).isTrue();
        assertThat(outgoing.adminAlert().text()).doesNotContain("Комментарий к оценке визита");
        assertThat(outgoing.metadata()).doesNotContainKey("visitReviewBillId");
        assertThat(outgoing.metadata()).doesNotContainKey("replyKeyboardRows");
    }

    @Test
    void sendsDirectFeedbackToAdminChat() {
        IncomingMessage incoming = telegram("отзыв: интерьер красивый, но музыку хотелось тише");
        when(feedbackService.create(any(GuestFeedbackCommand.class))).thenReturn(feedback());

        OutgoingMessage outgoing = scenario.handle(incoming, BotState.READY_FOR_DIALOG, incoming.text());

        assertThat(outgoing.nextState()).isEqualTo(BotState.READY_FOR_DIALOG.name());
        assertThat(outgoing.adminAlert().required()).isTrue();
        assertThat(outgoing.actions()).containsExactly("FEEDBACK", "FEEDBACK_DIRECT_TEXT", "ADMIN_ALERT", "RETURN_MAIN_MENU");
        verify(fsmStorage).setState(incoming.chatId(), BotState.READY_FOR_DIALOG);
    }

    private IncomingMessage telegram(String text) {
        return IncomingMessage.telegram(
                1773317437L,
                1773317437L,
                351,
                284069875,
                text,
                null,
                "Наталья",
                "Поединенко",
                "Poedinenko",
                "ru",
                false,
                "284069875"
        );
    }

    private GuestFeedback feedback() {
        return new GuestFeedback(
                88L,
                1773317437L,
                1773317437L,
                null,
                "AERIS",
                FeedbackStatus.OPEN,
                "TELEGRAM",
                FeedbackType.PRAISE,
                FeedbackSentiment.POSITIVE,
                FeedbackPriority.NORMAL,
                "Наталья Поединенко",
                "Очень понравился сервис и винная карта",
                BotState.FEEDBACK_COLLECT_TEXT.name(),
                "284069875",
                "100500",
                null,
                "{}",
                Instant.parse("2026-06-15T10:00:00Z"),
                Instant.parse("2026-06-15T10:00:00Z")
        );
    }
}
