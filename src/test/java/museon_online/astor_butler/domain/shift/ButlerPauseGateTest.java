package museon_online.astor_butler.domain.shift;

import museon_online.astor_butler.domain.booking.TableReservationNotificationService;
import museon_online.astor_butler.fsm.core.BotState;
import museon_online.astor_butler.fsm.storage.FSMStorage;
import museon_online.astor_butler.service.message.IncomingMessage;
import museon_online.astor_butler.service.message.OutgoingMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** The choke point in front of the writing scenarios: held for the team while Butler is paused, transparent otherwise. */
class ButlerPauseGateTest {

    private final ButlerPauseService pause = mock(ButlerPauseService.class);
    private final FSMStorage fsmStorage = mock(FSMStorage.class);
    private final TableReservationNotificationService notifications = mock(TableReservationNotificationService.class);
    private final ButlerPauseGate gate = new ButlerPauseGate(pause, fsmStorage, notifications, "500");

    private final IncomingMessage guest = IncomingMessage.telegram(
            1001L, 77L, 5, 5, "стол завтра в 20:00 на двоих", "+79990001122", "Иван", "Петров", "ivan", "ru", false, "c-9");

    @BeforeEach
    void paused() {
        when(pause.paused()).thenReturn(true);
        when(pause.state()).thenReturn(new ButlerPauseState(true, "Даша (@dasha)", Instant.parse("2026-10-12T10:40:00Z")));
    }

    @Test
    void aBookingMessageIsAnsweredWithThePauseTextForwardedAsACardAndTheGuestGoesToTheMainMenu() {
        OutgoingMessage held = gate.hold("TABLE_BOOKING", guest, BotState.TABLE_BOOKING_COLLECT_TIME, guest.text());

        assertThat(held).isNotNull();
        assertThat(held.text()).isEqualTo(ButlerPauseGate.GUEST_TEXT);
        assertThat(held.nextState()).isEqualTo(BotState.READY_FOR_DIALOG.name());
        assertThat(held.actions()).containsExactly("BUTLER_PAUSED", "FORWARDED_TO_STAFF");
        assertThat(held.metadata()).containsEntry("scenario", "TABLE_BOOKING").containsEntry("butlerPaused", true);
        verify(fsmStorage).setState(1001L, BotState.READY_FOR_DIALOG);

        ArgumentCaptor<String> card = ArgumentCaptor.forClass(String.class);
        verify(notifications).sendStaffNotice(eq("500"), card.capture());
        assertThat(card.getValue())
                .contains("Butler на паузе")
                .contains("Иван Петров (@ivan)")
                .contains("chat 1001")
                .contains("бронь стола")
                .contains("TABLE_BOOKING_COLLECT_TIME")
                .contains("стол завтра в 20:00 на двоих")
                .contains("Даша (@dasha)")
                .doesNotContain("+79990001122");
    }

    @Test
    void everyWritingScenarioIsHeldAndTheOthersAreNot() {
        for (String writing : new String[] {"TABLE_BOOKING", "CHANGE_CANCEL", "BUSINESS_LUNCH", "EVENT_BOOKING"}) {
            assertThat(gate.hold(writing, guest, BotState.READY_FOR_DIALOG, "x")).as(writing).isNotNull();
        }
        for (String reading : new String[] {"MENU_ASSETS", "QUIET_GUIDE", "MANAGER_HELP", "MAIN_MENU", "FEEDBACK", "FIRST_TOUCH"}) {
            assertThat(gate.hold(reading, guest, BotState.READY_FOR_DIALOG, "x")).as(reading).isNull();
        }
        assertThat(gate.hold(null, guest, BotState.READY_FOR_DIALOG, "x")).isNull();
    }

    @Test
    void whileRunningNothingIsHeldAndNothingIsSent() {
        when(pause.paused()).thenReturn(false);

        assertThat(gate.hold("TABLE_BOOKING", guest, BotState.READY_FOR_DIALOG, guest.text())).isNull();
        assertThat(gate.paused()).isFalse();
        verifyNoInteractions(fsmStorage, notifications);
    }

    @Test
    void withoutAStaffChatTheGuestStillHearsThePauseTextAndNothingIsSent() {
        ButlerPauseGate noChat = new ButlerPauseGate(pause, fsmStorage, notifications, "");

        OutgoingMessage held = noChat.hold("BUSINESS_LUNCH", guest, BotState.READY_FOR_DIALOG, "ланч");

        assertThat(held).isNotNull();
        verify(notifications, never()).sendStaffNotice(anyString(), any());
    }

    @Test
    void htmlInTheGuestTextIsEscapedOnTheCard() {
        gate.hold("CHANGE_CANCEL", guest, BotState.READY_FOR_DIALOG, "<b>отменить</b> & всё");

        verify(notifications).sendStaffNotice(eq("500"), org.mockito.ArgumentMatchers.contains("&lt;b&gt;отменить&lt;/b&gt; &amp; всё"));
    }
}
