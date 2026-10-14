package museon_online.astor_butler.fsm.scenario;

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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RecoveryScenarioTest {

    @Mock
    private FSMStorage fsmStorage;

    @Mock
    private RecoveryRetryService recoveryRetryService;

    private RecoveryScenario scenario;

    @BeforeEach
    void setUp() {
        scenario = new RecoveryScenario(fsmStorage, recoveryRetryService);
        ReflectionTestUtils.setField(scenario, "adminChatId", "100500");
    }

    @Test
    void firstUnclearInputClarifiesWithoutAdminAlert() {
        IncomingMessage incoming = telegram("что с этим всем делать дальше");
        when(recoveryRetryService.recordUnclear(incoming.chatId())).thenReturn(1L);

        OutgoingMessage outgoing = scenario.handle(incoming, BotState.READY_FOR_DIALOG, incoming.text());

        assertThat(outgoing.nextState()).isEqualTo(BotState.READY_FOR_DIALOG.name());
        assertThat(outgoing.adminAlert().required()).isFalse();
        assertThat(outgoing.actions()).containsExactly("RECOVERY", "CLARIFY_INTENT", "SHOW_MAIN_OPTIONS");
        assertThat(outgoing.text()).contains("стол", "меню", "менеджер");
        assertThat(outgoing.metadata()).containsEntry("scenario", "RECOVERY");
        verify(fsmStorage).setState(incoming.chatId(), BotState.READY_FOR_DIALOG);
    }

    @Test
    void repeatedUnclearInputEscalatesToAdminWithoutAiFallbackState() {
        IncomingMessage incoming = telegram("я опять не понял");
        when(recoveryRetryService.recordUnclear(incoming.chatId())).thenReturn(2L);

        OutgoingMessage outgoing = scenario.handle(incoming, BotState.READY_FOR_DIALOG, incoming.text());

        assertThat(outgoing.nextState()).isEqualTo(BotState.READY_FOR_DIALOG.name());
        assertThat(outgoing.adminAlert().required()).isTrue();
        assertThat(outgoing.adminAlert().chatId()).isEqualTo("100500");
        assertThat(outgoing.adminAlert().text()).contains("Astor Butler / recovery", "я опять не понял", "Recovery attempts: 2");
        assertThat(outgoing.actions()).containsExactly("RECOVERY", "ADMIN_ALERT", "RETURN_MAIN_MENU");
        verify(fsmStorage).setState(incoming.chatId(), BotState.READY_FOR_DIALOG);
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"Спасибо жду", "спасибо!", "Ок", "понял", "хорошо, жду", "Благодарю 🙏", "👍"})
    void courtesyNeverWakesTheAdmin(String text) {
        IncomingMessage incoming = telegram(text);

        OutgoingMessage outgoing = scenario.handle(incoming, BotState.READY_FOR_DIALOG, text);

        assertThat(outgoing.adminAlert().required()).isFalse();
        assertThat(outgoing.actions()).containsExactly("RECOVERY", "COURTESY");
        assertThat(outgoing.text()).contains("Пожалуйста");
        org.mockito.Mockito.verifyNoInteractions(recoveryRetryService);
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"Эй гудбай ты меня слышишь", "алло", "ты тут?", "Привет"})
    void presenceCheckAnswersWithoutAdmin(String text) {
        IncomingMessage incoming = telegram(text);

        OutgoingMessage outgoing = scenario.handle(incoming, BotState.READY_FOR_DIALOG, text);

        assertThat(outgoing.adminAlert().required()).isFalse();
        assertThat(outgoing.actions()).containsExactly("RECOVERY", "PRESENCE");
        assertThat(outgoing.text()).contains("Я здесь", "бронь", "меню");
        org.mockito.Mockito.verifyNoInteractions(recoveryRetryService);
    }

    @Test
    void courtesyInsideARealRequestStillCountsAsUnclear() {
        IncomingMessage incoming = telegram("спасибо, а можно перенести мою бронь на субботу вечером");
        when(recoveryRetryService.recordUnclear(incoming.chatId())).thenReturn(2L);

        OutgoingMessage outgoing = scenario.handle(incoming, BotState.READY_FOR_DIALOG, incoming.text());

        assertThat(outgoing.adminAlert().required()).isTrue();
    }

    @Test
    void supportsOnlyOpenDialogStatesWithNonBlankText() {
        IncomingMessage incoming = telegram("непонятно");

        assertThat(scenario.supports(incoming, BotState.READY_FOR_DIALOG, incoming.text())).isTrue();
        assertThat(scenario.supports(incoming, BotState.AI_FALLBACK, incoming.text())).isTrue();
        assertThat(scenario.supports(incoming, BotState.TABLE_BOOKING_COLLECT_TIME, incoming.text())).isFalse();
        assertThat(scenario.supports(incoming, BotState.READY_FOR_DIALOG, "")).isFalse();
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
}
