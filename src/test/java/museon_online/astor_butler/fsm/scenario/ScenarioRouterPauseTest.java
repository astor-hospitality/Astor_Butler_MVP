package museon_online.astor_butler.fsm.scenario;

import museon_online.astor_butler.domain.semantic.IntentExampleRepository;
import museon_online.astor_butler.domain.shift.ButlerPauseGate;
import museon_online.astor_butler.fsm.core.BotState;
import museon_online.astor_butler.fsm.storage.FSMStorage;
import museon_online.astor_butler.fsm.understanding.GuestInputUnderstandingService;
import museon_online.astor_butler.fsm.understanding.InputIntent;
import museon_online.astor_butler.fsm.understanding.UnderstoodInput;
import museon_online.astor_butler.service.message.AdminAlert;
import museon_online.astor_butler.service.message.IncomingMessage;
import museon_online.astor_butler.service.message.OutgoingMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The router asks the kill switch before a writing scenario gets the message (#134): a booking is held with the
 * pause text and the scenario is never called, while a menu request is answered as usual.
 */
class ScenarioRouterPauseTest {

    private final FSMStorage fsmStorage = mock(FSMStorage.class);
    private final FirstTouchScenario firstTouchScenario = mock(FirstTouchScenario.class);
    private final BusinessLunchScenario businessLunchScenario = mock(BusinessLunchScenario.class);
    private final TableBookingScenario tableBookingScenario = mock(TableBookingScenario.class);
    private final MenuAssetsScenario menuAssetsScenario = mock(MenuAssetsScenario.class);
    private final GuestInputUnderstandingService understanding = mock(GuestInputUnderstandingService.class);
    private final ButlerPauseGate gate = mock(ButlerPauseGate.class);
    private ScenarioRouter router;

    @BeforeEach
    void routerWithTheGate() {
        router = new ScenarioRouter(
                fsmStorage, firstTouchScenario, businessLunchScenario, tableBookingScenario,
                mock(EventBookingScenario.class), mock(ChangeCancelScenario.class), mock(ManagerHelpScenario.class),
                mock(FeedbackScenario.class), mock(PreferenceScenario.class), mock(ConciergeScenario.class),
                mock(SafePlayScenario.class), mock(MerchScenario.class), menuAssetsScenario, mock(QuietGuideScenario.class),
                mock(ImpactMeterScenario.class), mock(SmartTipScenario.class), mock(HiddenHeartScenario.class),
                mock(ArtAuctionScenario.class), mock(MainMenuScenario.class), mock(RecoveryScenario.class),
                understanding, mock(IntentExampleRepository.class));
        router.setButlerPauseGate(gate);
        when(tableBookingScenario.id()).thenReturn("TABLE_BOOKING");
        when(menuAssetsScenario.id()).thenReturn("MENU_ASSETS");
        when(businessLunchScenario.id()).thenReturn("BUSINESS_LUNCH");
        when(fsmStorage.getPendingIntents(any())).thenReturn(List.of());
    }

    @Test
    void aBookingRequestIsHeldAndTheBookingScenarioIsNeverCalled() {
        IncomingMessage incoming = telegram("хочу стол завтра в 20:00 на двоих");
        when(understanding.understand(anyString(), any(), any())).thenReturn(understood(incoming, InputIntent.TABLE_BOOKING));
        when(tableBookingScenario.supports(any(), any(), anyString(), any())).thenReturn(true);
        OutgoingMessage held = reply(incoming, "Сейчас заявки принимает команда вручную — передаю ваше сообщение, с вами свяжутся.", "BUTLER_PAUSED");
        when(gate.hold(eq("TABLE_BOOKING"), eq(incoming), any(), anyString())).thenReturn(held);

        OutgoingMessage outgoing = router.route(incoming, BotState.READY_FOR_DIALOG, incoming.text());

        assertThat(outgoing.text()).startsWith("Сейчас заявки принимает команда вручную");
        assertThat(outgoing.actions()).contains("BUTLER_PAUSED");
        verify(tableBookingScenario, never()).handle(any(), any(), anyString());
        verify(tableBookingScenario, never()).handle(any(), any(), anyString(), any());
    }

    @Test
    void aGuestInTheMiddleOfABookingIsHeldToo() {
        IncomingMessage incoming = telegram("да");
        when(understanding.understand(anyString(), any(), any())).thenReturn(understood(incoming, InputIntent.UNKNOWN));
        OutgoingMessage held = reply(incoming, "Сейчас заявки принимает команда вручную — передаю ваше сообщение, с вами свяжутся.", "BUTLER_PAUSED");
        when(gate.hold(eq("TABLE_BOOKING"), eq(incoming), eq(BotState.TABLE_BOOKING_CONFIRMATION), anyString())).thenReturn(held);

        OutgoingMessage outgoing = router.route(incoming, BotState.TABLE_BOOKING_CONFIRMATION, incoming.text());

        assertThat(outgoing.actions()).contains("BUTLER_PAUSED");
        verify(tableBookingScenario, never()).handle(any(), any(), anyString(), any());
    }

    @Test
    void aLunchRequestIsHeldBeforeTheLunchScenario() {
        IncomingMessage incoming = telegram("бизнес-ланч");
        when(businessLunchScenario.supports(any(), any(), anyString())).thenReturn(true);
        OutgoingMessage held = reply(incoming, "Сейчас заявки принимает команда вручную — передаю ваше сообщение, с вами свяжутся.", "BUTLER_PAUSED");
        when(gate.hold(eq("BUSINESS_LUNCH"), eq(incoming), any(), anyString())).thenReturn(held);

        OutgoingMessage outgoing = router.route(incoming, BotState.READY_FOR_DIALOG, incoming.text());

        assertThat(outgoing.actions()).contains("BUTLER_PAUSED");
        verify(businessLunchScenario, never()).handle(any(), any(), anyString());
    }

    @Test
    void aMenuRequestIsAnsweredAsUsualWhilePaused() {
        IncomingMessage incoming = telegram("покажи меню");
        when(understanding.understand(anyString(), any(), any())).thenReturn(understood(incoming, InputIntent.MENU_ASSETS));
        when(menuAssetsScenario.supports(any(), any(), anyString(), any())).thenReturn(true);
        OutgoingMessage menu = reply(incoming, "Вот меню.", "MENU_SENT");
        when(menuAssetsScenario.handle(any(), any(), anyString(), any())).thenReturn(menu);

        OutgoingMessage outgoing = router.route(incoming, BotState.READY_FOR_DIALOG, incoming.text());

        assertThat(outgoing.text()).isEqualTo("Вот меню.");
        verify(gate).hold(eq("MENU_ASSETS"), eq(incoming), any(), anyString());
    }

    @Test
    void whileRunningTheBookingScenarioHandlesTheMessage() {
        IncomingMessage incoming = telegram("хочу стол завтра в 20:00 на двоих");
        when(understanding.understand(anyString(), any(), any())).thenReturn(understood(incoming, InputIntent.TABLE_BOOKING));
        when(tableBookingScenario.supports(any(), any(), anyString(), any())).thenReturn(true);
        OutgoingMessage booking = reply(incoming, "На какую дату?", "TABLE_BOOKING_STARTED");
        when(tableBookingScenario.handle(any(), any(), anyString(), any())).thenReturn(booking);

        OutgoingMessage outgoing = router.route(incoming, BotState.READY_FOR_DIALOG, incoming.text());

        assertThat(outgoing.text()).isEqualTo("На какую дату?");
    }

    @Test
    void withoutTheGateTheRouterWorksAsBefore() {
        router.setButlerPauseGate(null);
        IncomingMessage incoming = telegram("хочу стол завтра в 20:00 на двоих");
        when(understanding.understand(anyString(), any(), any())).thenReturn(understood(incoming, InputIntent.TABLE_BOOKING));
        when(tableBookingScenario.supports(any(), any(), anyString(), any())).thenReturn(true);
        when(tableBookingScenario.handle(any(), any(), anyString(), any())).thenReturn(reply(incoming, "На какую дату?", "TABLE_BOOKING_STARTED"));

        assertThat(router.route(incoming, BotState.READY_FOR_DIALOG, incoming.text()).text()).isEqualTo("На какую дату?");
    }

    private static UnderstoodInput understood(IncomingMessage incoming, InputIntent intent) {
        return new UnderstoodInput(incoming.text(), incoming.text(), intent, 0.95, Map.of(), List.of(intent), false, null);
    }

    private static OutgoingMessage reply(IncomingMessage incoming, String text, String action) {
        return OutgoingMessage.of(incoming, text, BotState.READY_FOR_DIALOG.name(), false, false, false, false, AdminAlert.none(), List.of(action));
    }

    private static IncomingMessage telegram(String text) {
        return IncomingMessage.telegram(1001L, 77L, 5, 5, text, null, "Иван", null, "ivan", "ru", false, "c-1");
    }
}
