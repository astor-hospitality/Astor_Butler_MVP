package museon_online.astor_butler.fsm.scenario;

import museon_online.astor_butler.domain.semantic.IntentExampleRepository;
import museon_online.astor_butler.fsm.core.BotState;
import museon_online.astor_butler.fsm.storage.FSMStorage;
import museon_online.astor_butler.fsm.understanding.GuestInputUnderstandingService;
import museon_online.astor_butler.fsm.understanding.InputIntent;
import museon_online.astor_butler.fsm.understanding.SlotValue;
import museon_online.astor_butler.fsm.understanding.UnderstoodInput;
import museon_online.astor_butler.service.message.AdminAlert;
import museon_online.astor_butler.service.message.IncomingMessage;
import museon_online.astor_butler.service.message.OutgoingMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ScenarioRouterTest {

    @Mock
    private FSMStorage fsmStorage;

    @Mock
    private FirstTouchScenario firstTouchScenario;

    @Mock
    private BusinessLunchScenario businessLunchScenario;

    @Mock
    private TableBookingScenario tableBookingScenario;

    @Mock
    private EventBookingScenario eventBookingScenario;

    @Mock
    private ChangeCancelScenario changeCancelScenario;

    @Mock
    private ManagerHelpScenario managerHelpScenario;

    @Mock
    private FeedbackScenario feedbackScenario;

    @Mock
    private PreferenceScenario preferenceScenario;

    @Mock
    private ConciergeScenario conciergeScenario;

    @Mock
    private SafePlayScenario safePlayScenario;

    @Mock
    private MerchScenario merchScenario;

    @Mock
    private MenuAssetsScenario menuAssetsScenario;

    @Mock
    private QuietGuideScenario quietGuideScenario;

    @Mock
    private ImpactMeterScenario impactMeterScenario;

    @Mock
    private SmartTipScenario smartTipScenario;

    @Mock
    private HiddenHeartScenario hiddenHeartScenario;

    @Mock
    private ArtAuctionScenario artAuctionScenario;

    @Mock
    private MainMenuScenario mainMenuScenario;

    @Mock
    private RecoveryScenario recoveryScenario;

    @Mock
    private GuestInputUnderstandingService inputUnderstandingService;

    @Mock
    private IntentExampleRepository intentExampleRepository;

    private ScenarioRouter router;

    @BeforeEach
    void setUp() {
        router = new ScenarioRouter(
                fsmStorage,
                firstTouchScenario,
                businessLunchScenario,
                tableBookingScenario,
                eventBookingScenario,
                changeCancelScenario,
                managerHelpScenario,
                feedbackScenario,
                preferenceScenario,
                conciergeScenario,
                safePlayScenario,
                merchScenario,
                menuAssetsScenario,
                quietGuideScenario,
                impactMeterScenario,
                smartTipScenario,
                hiddenHeartScenario,
                artAuctionScenario,
                mainMenuScenario,
                recoveryScenario,
                inputUnderstandingService,
                intentExampleRepository
        );
    }

    @Test
    void passesUnderstoodSlotsToTableBookingWhenCompositeIntentDefersMenu() {
        IncomingMessage incoming = telegram("забронируй стол завтра в 20:00 на двоих и пришли винную карту");
        UnderstoodInput understood = new UnderstoodInput(
                incoming.text(),
                "забронируй стол завтра в 20:00 на 2 гостей и пришли винную карту",
                InputIntent.TABLE_BOOKING,
                0.92,
                Map.of(
                        "date", new SlotValue("date", "завтра", 0.95),
                        "time", new SlotValue("time", "20:00", 0.98),
                        "partySize", new SlotValue("partySize", "2", 0.95)
                ),
                List.of(InputIntent.TABLE_BOOKING, InputIntent.MENU_ASSETS),
                false,
                null
        );
        OutgoingMessage bookingResponse = OutgoingMessage.of(
                incoming,
                "Отправляю план зала AERIS.",
                BotState.TABLE_BOOKING_WAIT_TABLE_SELECTION.name(),
                false,
                false,
                false,
                false,
                AdminAlert.none(),
                List.of("SEND_HALL_PLAN", "ASK_TABLE_SELECTION")
        );

        when(firstTouchScenario.supports(incoming, BotState.READY_FOR_DIALOG, incoming.text()))
                .thenReturn(false);
        when(inputUnderstandingService.understand(eq(incoming.text()), eq(BotState.READY_FOR_DIALOG), any()))
                .thenReturn(understood);
        when(tableBookingScenario.supports(incoming, BotState.READY_FOR_DIALOG, understood.routeText(), understood))
                .thenReturn(true);
        when(menuAssetsScenario.supports(incoming, BotState.READY_FOR_DIALOG, understood.routeText(), understood))
                .thenReturn(true);
        when(quietGuideScenario.supports(incoming, BotState.READY_FOR_DIALOG, understood.routeText(), understood))
                .thenReturn(false);
        when(tableBookingScenario.handle(incoming, BotState.READY_FOR_DIALOG, understood.routeText(), understood))
                .thenReturn(bookingResponse);

        OutgoingMessage outgoing = router.route(incoming, BotState.READY_FOR_DIALOG, incoming.text());

        assertThat(outgoing.nextState()).isEqualTo(BotState.TABLE_BOOKING_WAIT_TABLE_SELECTION.name());
        assertThat(outgoing.metadata()).containsEntry("primaryIntent", "TABLE_BOOKING");
        assertThat(outgoing.metadata().get("pendingIntents")).asList().containsExactly("MENU_ASSETS");
        verify(tableBookingScenario).handle(incoming, BotState.READY_FOR_DIALOG, understood.routeText(), understood);
        verify(fsmStorage).setPendingIntents(incoming.chatId(), List.of("MENU_ASSETS::покажи винную карту"));
        verify(menuAssetsScenario, never()).handle(any(), any(), anyString(), eq(understood));
    }

    @Test
    void routesExactKeyboardLabelsBeforeLlmUnderstanding() {
        IncomingMessage incoming = telegram("Бар");
        OutgoingMessage menuResponse = OutgoingMessage.of(
                incoming,
                "Отправляю барную карту.",
                BotState.READY_FOR_DIALOG.name(),
                false,
                false,
                true,
                false,
                AdminAlert.none(),
                List.of("MENU_ASSETS", "MENU_ASSETS_DELIVERED")
        );

        when(firstTouchScenario.supports(incoming, BotState.READY_FOR_DIALOG, incoming.text()))
                .thenReturn(false);
        when(menuAssetsScenario.supports(incoming, BotState.READY_FOR_DIALOG, incoming.text()))
                .thenReturn(true);
        when(menuAssetsScenario.handle(incoming, BotState.READY_FOR_DIALOG, incoming.text()))
                .thenReturn(menuResponse);

        OutgoingMessage outgoing = router.route(incoming, BotState.READY_FOR_DIALOG, incoming.text());

        assertThat(outgoing.actions()).containsExactly("MENU_ASSETS", "MENU_ASSETS_DELIVERED");
        verify(inputUnderstandingService, never()).understand(anyString(), any(), any());
        verify(tableBookingScenario, never()).handle(any(), any(), anyString(), any());
    }

    @Test
    void aBusinessLunchAnswerIsNotOfferedToAnyOtherScenario() {
        IncomingMessage incoming = telegram("13:00");
        OutgoingMessage lunchReply = OutgoingMessage.of(incoming, "Отправляю заявку команде?", BotState.BUSINESS_LUNCH_CONFIRMATION.name(),
                false, false, false, false, AdminAlert.none(), List.of("BUSINESS_LUNCH", "ASK_LUNCH_CONFIRMATION"));
        when(firstTouchScenario.supports(incoming, BotState.BUSINESS_LUNCH_COLLECT_TIME, incoming.text())).thenReturn(false);
        when(businessLunchScenario.supports(incoming, BotState.BUSINESS_LUNCH_COLLECT_TIME, incoming.text())).thenReturn(true);
        when(businessLunchScenario.handle(incoming, BotState.BUSINESS_LUNCH_COLLECT_TIME, incoming.text())).thenReturn(lunchReply);

        OutgoingMessage outgoing = router.route(incoming, BotState.BUSINESS_LUNCH_COLLECT_TIME, incoming.text());

        assertThat(outgoing).isSameAs(lunchReply);
        verify(businessLunchScenario).rememberHandoff(incoming, incoming.text());
        verify(inputUnderstandingService, never()).understand(anyString(), any(), any());
        verify(tableBookingScenario, never()).handle(any(), any(), anyString(), any());
    }

    @Test
    void aLunchFromTheConciergeIsKeptThroughConsentAndResumedByTheLunchScenario() {
        IncomingMessage incoming = telegram("/start lunch_aeris");
        OutgoingMessage consent = OutgoingMessage.of(incoming, "Нажимая кнопку...", BotState.CONSENT_REQUIRED.name(),
                true, true, false, false, AdminAlert.none(), List.of("REQUEST_CONTACT", "CONSENT_REQUIRED"));
        OutgoingMessage afterFirstTouch = OutgoingMessage.of(incoming, "Какой вариант выбираете?", BotState.BUSINESS_LUNCH_CHOOSE_SET.name(),
                false, false, false, false, AdminAlert.none(), List.of("BUSINESS_LUNCH", "ASK_LUNCH_SET"));
        when(firstTouchScenario.supports(incoming, BotState.UNKNOWN, incoming.text())).thenReturn(true);
        when(firstTouchScenario.handle(incoming, BotState.UNKNOWN, incoming.text())).thenReturn(consent);
        when(businessLunchScenario.continueAfterFirstTouch(incoming, consent)).thenReturn(afterFirstTouch);

        OutgoingMessage outgoing = router.route(incoming, BotState.UNKNOWN, incoming.text());

        assertThat(outgoing).isSameAs(afterFirstTouch);
        var order = org.mockito.Mockito.inOrder(businessLunchScenario, firstTouchScenario);
        order.verify(businessLunchScenario).rememberHandoff(incoming, incoming.text());
        order.verify(firstTouchScenario).handle(incoming, BotState.UNKNOWN, incoming.text());
        order.verify(businessLunchScenario).continueAfterFirstTouch(incoming, consent);
        verify(businessLunchScenario, never()).handle(any(), any(), anyString());
    }

    private IncomingMessage telegram(String text) {
        return IncomingMessage.telegram(
                1773317437L,
                1773317437L,
                1,
                100,
                text,
                null,
                "Michael",
                null,
                "michaelwelly",
                "ru",
                false,
                "test-correlation"
        );
    }
}
