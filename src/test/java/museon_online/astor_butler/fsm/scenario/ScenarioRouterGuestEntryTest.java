package museon_online.astor_butler.fsm.scenario;

import museon_online.astor_butler.domain.entry.GuestEntryService;
import museon_online.astor_butler.domain.semantic.IntentExampleRepository;
import museon_online.astor_butler.fsm.core.BotState;
import museon_online.astor_butler.fsm.storage.FSMStorage;
import museon_online.astor_butler.fsm.understanding.GuestInputUnderstandingService;
import museon_online.astor_butler.service.message.AdminAlert;
import museon_online.astor_butler.service.message.IncomingMessage;
import museon_online.astor_butler.service.message.OutgoingMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The router hands every message to the entry log once, before the first touch (#128): a /start with the staff
 * parameter is written exactly once whichever scenario answers it, and without the bean nothing changes.
 */
class ScenarioRouterGuestEntryTest {

    private final FirstTouchScenario firstTouchScenario = mock(FirstTouchScenario.class);
    private final BusinessLunchScenario businessLunchScenario = mock(BusinessLunchScenario.class);
    private final GuestEntryService entries = mock(GuestEntryService.class);
    private ScenarioRouter router;

    @BeforeEach
    void routerAnsweringTheFirstTouch() {
        FSMStorage fsmStorage = mock(FSMStorage.class);
        router = new ScenarioRouter(
                fsmStorage, firstTouchScenario, businessLunchScenario, mock(TableBookingScenario.class),
                mock(EventBookingScenario.class), mock(ChangeCancelScenario.class), mock(ManagerHelpScenario.class),
                mock(FeedbackScenario.class), mock(PreferenceScenario.class), mock(ConciergeScenario.class),
                mock(SafePlayScenario.class), mock(MerchScenario.class), mock(MenuAssetsScenario.class), mock(QuietGuideScenario.class),
                mock(ImpactMeterScenario.class), mock(SmartTipScenario.class), mock(HiddenHeartScenario.class),
                mock(ArtAuctionScenario.class), mock(MainMenuScenario.class), mock(RecoveryScenario.class),
                mock(GuestInputUnderstandingService.class), mock(IntentExampleRepository.class));
        when(fsmStorage.getPendingIntents(any())).thenReturn(List.of());
        when(firstTouchScenario.supports(any(), any(), anyString())).thenReturn(true);
        when(firstTouchScenario.handle(any(), any(), anyString())).thenAnswer(invocation -> consent(invocation.getArgument(0)));
        when(businessLunchScenario.continueAfterFirstTouch(any(), any())).thenAnswer(invocation -> invocation.getArgument(1));
    }

    @Test
    void aStartWithTheStaffParameterReachesTheEntryLogExactlyOnceBeforeTheFirstTouch() {
        router.setGuestEntryService(entries);
        IncomingMessage incoming = telegram("/start lunch_aeris_hanna_n5");

        OutgoingMessage outgoing = router.route(incoming, BotState.UNKNOWN, incoming.text());

        assertThat(outgoing.nextState()).isEqualTo(BotState.CONSENT_REQUIRED.name());
        verify(entries, times(1)).rememberStart(incoming, "/start lunch_aeris_hanna_n5");
        verify(businessLunchScenario).rememberHandoff(incoming, "/start lunch_aeris_hanna_n5");
        verify(firstTouchScenario).handle(incoming, BotState.UNKNOWN, "/start lunch_aeris_hanna_n5");
    }

    @Test
    void withoutTheBeanTheRouterAnswersAsBefore() {
        IncomingMessage incoming = telegram("/start h_anna");

        OutgoingMessage outgoing = router.route(incoming, BotState.UNKNOWN, incoming.text());

        assertThat(outgoing.nextState()).isEqualTo(BotState.CONSENT_REQUIRED.name());
        verify(firstTouchScenario).handle(incoming, BotState.UNKNOWN, "/start h_anna");
    }

    private static OutgoingMessage consent(IncomingMessage incoming) {
        return OutgoingMessage.of(incoming, "Нажимая кнопку…", BotState.CONSENT_REQUIRED.name(), true, true, false, false,
                AdminAlert.none(), List.of("SAFE_RESTART", "REQUEST_CONTACT", "CONSENT_REQUIRED"));
    }

    private static IncomingMessage telegram(String text) {
        return IncomingMessage.telegram(1001L, 1001L, 1, 100, text, null, "Наталья", null, "guest", "ru", false, "test");
    }
}
