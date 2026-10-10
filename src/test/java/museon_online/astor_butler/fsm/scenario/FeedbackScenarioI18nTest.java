package museon_online.astor_butler.fsm.scenario;

import museon_online.astor_butler.domain.feedback.FeedbackPriority;
import museon_online.astor_butler.domain.feedback.FeedbackSentiment;
import museon_online.astor_butler.domain.feedback.FeedbackService;
import museon_online.astor_butler.domain.feedback.FeedbackStatus;
import museon_online.astor_butler.domain.feedback.FeedbackType;
import museon_online.astor_butler.domain.feedback.GuestFeedback;
import museon_online.astor_butler.domain.feedback.GuestFeedbackCommand;
import museon_online.astor_butler.fsm.core.BotState;
import museon_online.astor_butler.fsm.storage.FSMStorage;
import museon_online.astor_butler.i18n.GuestLanguagePreferenceStore;
import museon_online.astor_butler.i18n.GuestLocaleService;
import museon_online.astor_butler.i18n.GuestTexts;
import museon_online.astor_butler.i18n.I18nConfig;
import museon_online.astor_butler.i18n.InMemoryTranslationCache;
import museon_online.astor_butler.i18n.MessageCatalog;
import museon_online.astor_butler.i18n.NoOpMachineTranslator;
import museon_online.astor_butler.i18n.ScriptLanguageDetector;
import museon_online.astor_butler.service.message.IncomingMessage;
import museon_online.astor_butler.service.message.OutgoingMessage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** The feedback flow is the worked example of the guest-language layer. */
@ExtendWith(MockitoExtension.class)
class FeedbackScenarioI18nTest {

    /** The literals the scenario had before the catalog; with the feature off they must come back unchanged. */
    private static final String RU_ASK = "Напишите отзыв одним сообщением. Я передам его команде AERIS без публичного сравнения и лишнего давления.";
    private static final String RU_THANKS = "Спасибо, передал отзыв команде. Я остаюсь на связи: можно попросить меню, бронь, видео-тур или менеджера.";

    @Mock
    private FSMStorage fsmStorage;

    @Mock
    private FeedbackService feedbackService;

    @Test
    void featureOffKeepsTheRussianTextsForAnEnglishGuest() {
        FeedbackScenario scenario = scenario(I18nConfig.defaults());
        when(feedbackService.create(any(GuestFeedbackCommand.class))).thenReturn(feedback());

        OutgoingMessage ask = scenario.handle(telegram("оставить отзыв", "en"), BotState.READY_FOR_DIALOG, "оставить отзыв");
        OutgoingMessage thanks = scenario.handle(
                telegram("The wine list was great", "en"), BotState.FEEDBACK_COLLECT_TEXT, "The wine list was great");

        assertThat(ask.text()).isEqualTo(RU_ASK);
        assertThat(thanks.text()).isEqualTo(RU_THANKS);
        assertThat(ask.metadata()).doesNotContainKey(GuestTexts.METADATA_GUEST_LANGUAGE);
        assertThat(thanks.adminAlert().text()).doesNotContain("Язык гостя");
    }

    @Test
    void featureOffDoesNotTakeAnEnglishLabelForTheButton() {
        FeedbackScenario scenario = scenario(I18nConfig.defaults());
        when(feedbackService.create(any(GuestFeedbackCommand.class))).thenReturn(feedback());

        OutgoingMessage outgoing = scenario.handle(telegram("Leave feedback", "en"), BotState.READY_FOR_DIALOG, "Leave feedback");

        assertThat(outgoing.actions()).contains("FEEDBACK_DIRECT_TEXT");
    }

    @Test
    void englishGuestGetsTheWholeFlowInEnglish() {
        FeedbackScenario scenario = scenario(I18nConfig.enabledWithoutTranslation());
        when(feedbackService.create(any(GuestFeedbackCommand.class))).thenReturn(feedback());

        IncomingMessage button = telegram("Leave feedback", "en");
        assertThat(scenario.supports(button, BotState.READY_FOR_DIALOG, button.text())).isTrue();
        OutgoingMessage ask = scenario.handle(button, BotState.READY_FOR_DIALOG, button.text());

        IncomingMessage review = telegram("The wine list was great and the music was a little loud", "ru");
        OutgoingMessage thanks = scenario.handle(review, BotState.FEEDBACK_COLLECT_TEXT, review.text());

        assertThat(ask.nextState()).isEqualTo(BotState.FEEDBACK_COLLECT_TEXT.name());
        assertThat(ask.text()).startsWith("Please write your feedback in one message.");
        assertThat(ask.metadata())
                .containsEntry("scenario", "FEEDBACK")
                .containsEntry(GuestTexts.METADATA_GUEST_LANGUAGE, "en")
                .containsEntry(GuestTexts.METADATA_TEXT_ORIGIN, "CATALOG");
        assertThat(thanks.text()).startsWith("Thank you, I have passed your feedback on to the team.");
        assertThat(thanks.adminAlert().text())
                .contains("Язык гостя: английский (en)")
                .contains("The wine list was great");
        assertThat(thanks.metadata()).containsEntry("feedbackId", 88L);
        verify(fsmStorage).setState(button.chatId(), BotState.FEEDBACK_COLLECT_TEXT);
    }

    @Test
    void russianGuestKeepsRussianWithTheFeatureOn() {
        FeedbackScenario scenario = scenario(I18nConfig.enabledWithoutTranslation());

        IncomingMessage incoming = telegram("оставить отзыв", "ru");
        OutgoingMessage ask = scenario.handle(incoming, BotState.READY_FOR_DIALOG, incoming.text());

        assertThat(ask.text()).isEqualTo(RU_ASK);
        assertThat(ask.metadata()).containsEntry(GuestTexts.METADATA_GUEST_LANGUAGE, "ru");
    }

    @Test
    void languageWithoutCatalogFallsBackToEnglishWhileTranslationIsOff() {
        FeedbackScenario scenario = scenario(I18nConfig.enabledWithoutTranslation());

        IncomingMessage incoming = telegram("feedback", "de");
        OutgoingMessage ask = scenario.handle(incoming, BotState.READY_FOR_DIALOG, incoming.text());

        assertThat(ask.text()).startsWith("Please write your feedback in one message.");
        assertThat(ask.metadata())
                .containsEntry(GuestTexts.METADATA_GUEST_LANGUAGE, "de")
                .containsEntry(GuestTexts.METADATA_REPLY_LANGUAGE, "en")
                .containsEntry(GuestTexts.METADATA_TEXT_ORIGIN, "FALLBACK");
    }

    private FeedbackScenario scenario(I18nConfig config) {
        GuestTexts texts = new GuestTexts(
                config,
                MessageCatalog.load(MessageCatalog.GUEST_BUNDLE, List.of("ru", "en")),
                new NoOpMachineTranslator(),
                new InMemoryTranslationCache(100),
                new GuestLocaleService(config, new ScriptLanguageDetector(8), GuestLanguagePreferenceStore.none())
        );
        FeedbackScenario scenario = new FeedbackScenario(fsmStorage, feedbackService, texts);
        ReflectionTestUtils.setField(scenario, "adminChatId", "100500");
        return scenario;
    }

    private static IncomingMessage telegram(String text, String languageCode) {
        return IncomingMessage.telegram(
                1773317437L, 1773317437L, 351, 284069875, text, null, "Anna", null, "anna", languageCode, false, "284069875");
    }

    private static GuestFeedback feedback() {
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
                "Anna",
                "The wine list was great",
                BotState.FEEDBACK_COLLECT_TEXT.name(),
                "284069875",
                "100500",
                null,
                "{}",
                Instant.parse("2026-10-10T10:00:00Z"),
                Instant.parse("2026-10-10T10:00:00Z")
        );
    }
}
